package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Skill Studio: skills banao (khali / AI se / task se), edit + update (version badhta hai), merge, duplicate,
 * copy / paste (export-import), platform-wise (ChatGPT, Gemini...), test run, aur doosre AI app se poochho.
 */
class SkillsActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var cards: LinearLayout
    private var mode = "list"
    private var mergeMode = false
    private var query = ""
    private val selected = HashSet<Long>()
    private val consentAsk: (String) -> Int = { Consent.ask(this, it) }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private val light = Color.parseColor("#E0E0E0")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        setContentView(root)
        showList()
    }

    override fun onResume() {
        super.onResume()
        Privacy.activityAsk = consentAsk
        if (mode == "list") renderCards()
    }

    override fun onPause() {
        super.onPause()
        if (Privacy.activityAsk === consentAsk) Privacy.activityAsk = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode != "list") showList() else super.onBackPressed()
    }

    // ---------- clipboard / text ----------

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("LoRA skill", text))
        toast("Copy ho gaya")
    }

    private fun paste(): String? {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
    }

    private fun skillText(name: String, platform: String, goal: String, steps: String) =
        "NAME: $name\nPLATFORM: $platform\nGOAL: $goal\nSTEPS:\n$steps"

    // ---------- list ----------

    private fun showList() {
        mode = "list"
        root.removeAllViews()
        val pad = dp(10)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        head.addView(TextView(this).apply {
            text = "🧩 Skill Studio"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.chip(this, "✕ Band") { finish() })

        val row1 = chipRow(
            Ui.chip(this, "+ AI se banao") { aiCreateDialog() },
            Ui.chip(this, "+ Khali") { showEditor(null, null) },
            Ui.chip(this, "📥 Paste") { importFromClipboard() }
        )
        val row2 = chipRow(
            Ui.chip(this, "🤖 Ask AI app") { Platforms.askDialog(this) },
            Ui.chip(this, "Task → Skill") { taskToSkill() },
            Ui.chip(this, if (mergeMode) "🔀 Merge ON" else "🔀 Merge", if (mergeMode) Ui.GREEN else Ui.GREY) {
                mergeMode = !mergeMode
                selected.clear()
                showList()
            }
        )

        val row3 = chipRow(
            Ui.chip(this, "📦 Starter skills (ChatGPT/Gemini...)") { installStarters() },
            Ui.chip(this, "🕘 Agent History") { startActivity(android.content.Intent(this, HistoryActivity::class.java)) }
        )

        val search = EditText(this).apply {
            hint = "Search: naam / platform"
            setText(query)
            setSingleLine()
            setTextColor(Color.BLACK)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString().orEmpty()
                    renderCards()
                }
            })
        }

        cards = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(cards) }

        root.addView(head)
        root.addView(row1)
        root.addView(row2)
        root.addView(row3)
        root.addView(search, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(pad, 0, pad, 0) })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        if (mergeMode) {
            root.addView(Ui.chip(this, "Merge selected skills", Ui.BLUE, size = 14f) { mergeSelected() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { setMargins(pad, pad, pad, pad) })
        }
        renderCards()
    }

    /** Installed AI apps (ya sab known) ke liye "Ask <app>" skills, jo pehle se nahi hain. */
    private fun installStarters() {
        val existing = TaskStore.skills(this).map { it.name }.toSet()
        val names = Platforms.installed(this).ifEmpty { Platforms.AI_APPS.map { it.name } }
        var n = 0
        for (a in names) {
            val nm = "Ask $a"
            if (nm in existing) continue
            TaskStore.saveSkill(
                this, nm, "$a se poochho: {sawal}", Platforms.starterSteps(a), a,
                "Starter template. Asli run ke baad Task -> Skill / History se update karo."
            )
            n++
        }
        toast(if (n == 0) "Starter skills pehle se hain" else "$n starter skills ban gayi")
        renderCards()
    }

    private fun chipRow(vararg chips: TextView): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), 0)
        }
        for (c in chips) {
            r.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        return r
    }

    private fun renderCards() {
        cards.removeAllViews()
        val q = query.trim().lowercase()
        val all = TaskStore.skills(this).filter {
            q.isEmpty() || it.name.lowercase().contains(q) || it.platform.lowercase().contains(q) || it.goal.lowercase().contains(q)
        }
        if (all.isEmpty()) {
            cards.addView(TextView(this).apply {
                text = "Abhi koi skill nahi.\n+ AI se banao, + Khali, ya Task → Skill se shuru karo. Task poora hone par floating window me 'Skill' bhi dabaa sakte ho."
                setTextColor(Color.DKGRAY)
            })
            return
        }
        for (s in all) cards.addView(card(s), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(8) })
    }

    private fun card(s: Skill): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.rounded(Color.parseColor("#F1F3F4"), dp(12))
        }
        val titleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        if (mergeMode) {
            titleRow.addView(CheckBox(this).apply {
                isChecked = selected.contains(s.id)
                setOnCheckedChangeListener { _, on -> if (on) selected.add(s.id) else selected.remove(s.id) }
            })
        }
        titleRow.addView(TextView(this).apply {
            text = s.name + (if (s.platform.isNotBlank()) "   🏷 ${s.platform}" else "") + "   v${s.version}"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        })
        c.addView(titleRow)
        c.addView(TextView(this).apply {
            text = "${s.goal.take(90)}\n${SkillAI.lines(s.steps).size} steps"
            textSize = 12f
            setTextColor(Color.DKGRAY)
        })
        val btns = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        btns.addView(Ui.chip(this, "▶ Run", Ui.GREEN) { runSkill(s) })
        btns.addView(Ui.chip(this, "✎ Edit") { showEditor(s, null) }, marginLeft())
        btns.addView(Ui.chip(this, "⋯") { skillMenu(s) }, marginLeft())
        c.addView(btns)
        return c
    }

    private fun marginLeft() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = dp(6) }

    private fun skillMenu(s: Skill) {
        AlertDialog.Builder(this)
            .setTitle(s.name)
            .setItems(arrayOf("⧉ Duplicate", "📋 Copy (export text)", "🗑 Delete")) { _, i ->
                when (i) {
                    0 -> {
                        TaskStore.saveSkill(this, s.name + " (copy)", s.goal, s.steps, s.platform, s.notes)
                        renderCards()
                    }
                    1 -> copy(skillText(s.name, s.platform, s.goal, s.steps))
                    2 -> AlertDialog.Builder(this)
                        .setTitle("Skill hatani hai?")
                        .setMessage(s.name)
                        .setPositiveButton("Hatao") { _, _ ->
                            TaskStore.deleteSkill(this, s.id)
                            renderCards()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
            .setNegativeButton("Band", null)
            .show()
    }

    // ---------- run ----------

    private fun runSkill(s: Skill) {
        Platforms.fillAndRun(this, s) { g, st -> startAgent(Skill(s.id, s.name, g, st, s.platform, s.notes, s.version, s.updated)) }
    }

    private fun startAgent(s: Skill) {
        val svc = AgentAccessibilityService.instance
        if (svc == null) {
            toast("Pehle Accessibility me LoRA on karo")
            return
        }
        if (AgentLoop.isRunning()) {
            toast("Agent pehle se chal raha hai")
            return
        }
        AgentLoop.start(svc, s.goal, Platforms.runHint(s))
        toast("Skill '${s.name}' chal rahi hai. Floating window me dekho.")
    }

    // ---------- create: AI / import / task ----------

    private fun aiCreateDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val desc = EditText(this).apply {
            hint = "Kya kaam karwana hai? (jaise: ChatGPT se sawal poochho aur jawab copy karo)"
            minLines = 3
        }
        val plat = EditText(this).apply {
            hint = "Platform (optional): ChatGPT / Gemini / WhatsApp..."
            setSingleLine()
        }
        box.addView(desc)
        box.addView(plat)
        AlertDialog.Builder(this)
            .setTitle("AI se skill banao")
            .setView(box)
            .setPositiveButton("Banao") { _, _ ->
                val d = desc.text.toString().trim()
                if (d.isEmpty()) {
                    toast("Kaam likho")
                    return@setPositiveButton
                }
                toast("Skill ka draft ban raha hai...")
                SkillAI.generate(this, d, plat.text.toString().trim(), { }) { draft, err ->
                    runOnUiThread {
                        if (draft != null) showEditor(null, draft) else toast(err ?: "error")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromClipboard() {
        val t = paste()
        if (t.isNullOrBlank()) {
            toast("Clipboard khali hai")
            return
        }
        val d = SkillAI.parse(t)
        if (d != null) {
            showEditor(null, d)
        } else {
            val l = SkillAI.lines(t)
            showEditor(null, SkillDraft("Imported skill", Platforms.detect(t), "", SkillAI.numbered(l)))
        }
    }

    private fun taskToSkill() {
        val tasks = TaskStore.recent(this, 20)
        if (tasks.isEmpty()) {
            toast("Abhi koi task nahi hai")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kaunsa task skill banana hai?")
            .setItems(tasks.map { "#${it.id} ${it.goal.take(40)}  (${it.status}, step ${it.step})" }.toTypedArray()) { _, i ->
                pickTaskSteps(tasks[i])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickTaskSteps(t: Task) {
        val steps = SkillAI.stepsFromTask(this, t.id)
        if (steps.isEmpty()) {
            toast("Is task me koi safal step nahi mila")
            return
        }
        val checked = BooleanArray(steps.size) { true }
        AlertDialog.Builder(this)
            .setTitle("Kaun se steps rakhne hain?")
            .setMultiChoiceItems(steps.map { it.take(70) }.toTypedArray(), checked) { _, i, on -> checked[i] = on }
            .setPositiveButton("Aage") { _, _ ->
                val chosen = steps.filterIndexed { i, _ -> checked[i] }
                if (chosen.isEmpty()) {
                    toast("Koi step nahi chuna")
                } else {
                    val plat = Platforms.detect(t.goal + " " + chosen.joinToString(" "))
                    showEditor(null, SkillDraft(t.goal.take(40), plat, t.goal, SkillAI.numbered(chosen)))
                    toast("Tip: 'AI se saaf karo' dabao to steps general ban jayenge.")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- merge ----------

    private fun mergeSelected() {
        val list = TaskStore.skills(this).filter { selected.contains(it.id) }
        if (list.size < 2) {
            toast("Kam se kam 2 skills chuno")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("${list.size} skills merge")
            .setItems(arrayOf("Simple merge (duplicate hata kar jodo)", "AI se merge (behtar order/saaf)")) { _, i ->
                if (i == 0) {
                    mergeMode = false
                    selected.clear()
                    showEditor(null, SkillAI.mergeSimple(list, ""))
                } else {
                    toast("AI merge kar raha hai...")
                    SkillAI.mergeAi(this, list, { }) { d, err ->
                        runOnUiThread {
                            if (d != null) {
                                mergeMode = false
                                selected.clear()
                                showEditor(null, d)
                            } else {
                                toast(err ?: "error")
                            }
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------- editor ----------

    private fun showEditor(skill: Skill?, draft: SkillDraft?) {
        mode = "edit"
        root.removeAllViews()
        val pad = dp(12)

        fun field(h: String, v: String, lines: Int) = EditText(this).apply {
            hint = h
            setText(v)
            setTextColor(Color.BLACK)
            if (lines > 1) {
                minLines = lines
                gravity = android.view.Gravity.TOP
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            } else setSingleLine()
        }

        val name = field("Skill ka naam", skill?.name ?: draft?.name ?: "", 1)
        val platform = field("Platform (app/website: ChatGPT, Gemini, WhatsApp...)", skill?.platform ?: draft?.platform ?: "", 1)
        val goal = field("Goal (ek line; badalne wali value {naam} me)", skill?.goal ?: draft?.goal ?: "", 2)
        val steps = field("Steps (har line ek step; 1. 2. 3.)", skill?.steps ?: draft?.steps ?: "", 8)
        val notes = field("Notes (optional)", skill?.notes ?: "", 2)

        val platRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (a in Platforms.AI_APPS.take(4)) {
            platRow.addView(Ui.chip(this, a.name, light, Color.BLACK, 11f) { platform.setText(a.name) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        col.addView(TextView(this).apply {
            text = if (skill == null) "Nayi skill" else "Skill edit · v${skill.version}"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
        })
        col.addView(name)
        col.addView(platform)
        col.addView(platRow)
        col.addView(goal)
        col.addView(steps)
        col.addView(notes)

        fun current() = Skill(skill?.id ?: 0L, name.text.toString().trim(), goal.text.toString().trim(),
            SkillAI.numbered(SkillAI.lines(steps.text.toString())), platform.text.toString().trim(),
            notes.text.toString().trim(), skill?.version ?: 1, 0L)

        fun valid(c: Skill): Boolean {
            if (c.name.isEmpty() || c.steps.isEmpty()) {
                toast("Naam aur kam se kam ek step zaruri hai")
                return false
            }
            return true
        }

        fun save(asNew: Boolean) {
            val c = current()
            if (!valid(c)) return
            if (skill != null && !asNew) {
                TaskStore.updateSkill(this, skill.id, c.name, c.goal, c.steps, c.platform, c.notes)
                toast("Update ho gayi (v${skill.version + 1})")
            } else {
                TaskStore.saveSkill(this, c.name, c.goal, c.steps, c.platform, c.notes)
                toast("Skill save ho gayi")
            }
            showList()
        }

        val r1 = chipRow(
            Ui.chip(this, if (skill != null) "💾 Update" else "💾 Save", Ui.GREEN, size = 13f) { save(false) },
            Ui.chip(this, "🤖 AI se saaf karo", size = 13f) {
                val c = current()
                val et = EditText(this).apply { hint = "Instruction (optional)"; setSingleLine() }
                AlertDialog.Builder(this)
                    .setTitle("AI se sudharo")
                    .setView(et)
                    .setPositiveButton("Sudharo") { _, _ ->
                        toast("AI kaam kar raha hai...")
                        SkillAI.refine(this, SkillDraft(c.name, c.platform, c.goal, c.steps), et.text.toString().trim(), { }) { d, err ->
                            runOnUiThread {
                                if (d != null) {
                                    name.setText(d.name)
                                    platform.setText(d.platform)
                                    goal.setText(d.goal)
                                    steps.setText(d.steps)
                                    toast("Draft badal gaya; check karke Save/Update karo")
                                } else toast(err ?: "error")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        )
        val r2 = chipRow(
            Ui.chip(this, "▶ Test run", size = 13f) {
                val c = current()
                if (valid(c)) runSkill(c)
            },
            Ui.chip(this, "📋 Copy", size = 13f) {
                val c = current()
                copy(skillText(c.name, c.platform, c.goal, c.steps))
            },
            Ui.chip(this, if (skill != null) "💾 Naya banao" else "← Wapas", size = 13f) {
                if (skill != null) save(true) else showList()
            }
        )
        val r3 = chipRow(Ui.chip(this, "← Wapas (bina save)", light, Color.BLACK, 13f) { showList() })

        root.addView(ScrollView(this).apply { addView(col) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(r1)
        root.addView(r2)
        root.addView(r3)
    }
}
