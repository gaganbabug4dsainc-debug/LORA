package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * Agent History tool: purane tasks dekho, steps copy / edit / delete karo, goal + instructions badlo,
 * task duplicate karo, resume karo, aur task se skill banao ya maujooda skill update karo.
 * (Ye tool jaan-boojhkar history badalne deta hai; chalte agent ke upcoming steps ke liye floating "📋 Steps" use karo.)
 */
class HistoryActivity : Activity() {

    private lateinit var root: LinearLayout
    private var mode = "list"
    private var taskId = 0L
    private val light = Color.parseColor("#E0E0E0")

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        setContentView(root)
        showTasks()
    }

    override fun onResume() {
        super.onResume()
        if (mode == "list") showTasks() else showTask(taskId)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (mode != "list") showTasks() else super.onBackPressed()
    }

    private fun copy(text: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("LoRA", text))
        toast("Copy ho gaya")
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

    private fun title(t: String) = TextView(this).apply {
        text = t
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.BLACK)
        setPadding(dp(12), dp(12), dp(12), dp(4))
    }

    private fun fmt(ts: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(ts))

    // ---------- tasks list ----------

    private fun showTasks() {
        mode = "list"
        root.removeAllViews()
        root.addView(title("🕘 Agent History"))
        root.addView(chipRow(Ui.chip(this, "🧩 Skill Studio") { startActivity(Intent(this, SkillsActivity::class.java)) },
            Ui.chip(this, "✕ Band") { finish() }))

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        val tasks = TaskStore.recent(this, 200)
        if (tasks.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "Abhi koi task nahi hai. Agent chalane par yahan history dikhegi."
                setTextColor(Color.DKGRAY)
            })
        }
        for (t in tasks) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = Ui.rounded(Color.parseColor("#F1F3F4"), dp(12))
                isClickable = true
                setOnClickListener { showTask(t.id) }
            }
            c.addView(TextView(this).apply {
                text = "#${t.id}  ${t.goal.take(60)}"
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.BLACK)
            })
            c.addView(TextView(this).apply {
                text = "${t.status} · step ${t.step} · ${fmt(t.updated)}"
                textSize = 12f
                setTextColor(Color.DKGRAY)
            })
            box.addView(c, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    // ---------- one task ----------

    private fun showTask(id: Long) {
        val t = TaskStore.get(this, id)
        if (t == null) {
            showTasks()
            return
        }
        mode = "task"
        taskId = id
        root.removeAllViews()
        root.addView(title("Task #$id"))

        val info = TextView(this).apply {
            text = "Goal: ${t.goal}\nStatus: ${t.status} · Step ${t.step} · Checkpoint ${t.checkpoint}\nUpdated: ${fmt(t.updated)}"
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(dp(12), 0, dp(12), 0)
            setTextIsSelectable(true)
        }
        root.addView(info)

        root.addView(chipRow(
            Ui.chip(this, "▶ Resume", Ui.GREEN) { resume(id) },
            Ui.chip(this, "⧉ Duplicate") {
                val n = TaskStore.duplicateTask(this, id)
                toast("Naya task #$n bana (goal, instructions, aage ke steps ke saath)")
                if (n > 0) showTask(n)
            },
            Ui.chip(this, "📋 Copy all") { copy(TaskStore.taskText(this, id)) }
        ))
        root.addView(chipRow(
            Ui.chip(this, "✎ Goal") { editGoal(t) },
            Ui.chip(this, "✎ Instructions") { editInstructions(t) },
            Ui.chip(this, "📋 Aage ke steps") {
                val svc = AgentAccessibilityService.instance
                if (svc == null) toast("Pehle Accessibility me LoRA on karo") else svc.showStepsEditor(id)
            }
        ))
        root.addView(chipRow(
            Ui.chip(this, "🧩 Skill banao") { makeSkill(t) },
            Ui.chip(this, "↻ Skill update") { updateSkillFrom(t) },
            Ui.chip(this, "🗑 Task hatao", Ui.RED) { confirmPurge(t) }
        ))
        root.addView(TextView(this).apply {
            text = "Steps (history) — edit/copy/delete:"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            setPadding(dp(12), dp(12), dp(12), dp(4))
        })

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), 0, dp(10), dp(10))
        }
        val rows = TaskStore.historyRows(this, id)
        if (rows.isEmpty()) box.addView(TextView(this).apply { text = "(abhi koi step nahi)"; setTextColor(Color.DKGRAY) })
        for (r in rows) box.addView(stepCard(r), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(6) })
        root.addView(ScrollView(this).apply { addView(box) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(chipRow(Ui.chip(this, "← Saare tasks", light, Color.BLACK, 13f) { showTasks() }))
    }

    private fun stepCard(r: HRow): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = Ui.rounded(Color.parseColor("#F1F3F4"), dp(10))
        }
        c.addView(TextView(this).apply {
            text = "${r.n}. [${r.status}] ${r.text}"
            textSize = 13f
            setTextColor(Color.BLACK)
            setTextIsSelectable(true)
        })
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        b.addView(Ui.chip(this, "✎", light, Color.BLACK) { editRow(r) })
        b.addView(Ui.chip(this, "📋", light, Color.BLACK) { copy(r.text) }, lm())
        b.addView(Ui.chip(this, "🗑", light, Color.BLACK) {
            TaskStore.deleteHist(this, r.rowId)
            showTask(taskId)
        }, lm())
        c.addView(b)
        return c
    }

    private fun lm() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = dp(6) }

    // ---------- actions ----------

    private fun resume(id: Long) {
        val svc = AgentAccessibilityService.instance
        when {
            svc == null -> toast("Pehle Accessibility me LoRA on karo")
            AgentLoop.isRunning() -> toast("Agent pehle se chal raha hai")
            else -> {
                AgentLoop.resumeTask(svc, id, null)
                toast("Task #$id continue ho raha hai. Floating window me dekho.")
            }
        }
    }

    private fun editBox(initial: String, multi: Boolean) = EditText(this).apply {
        setText(initial)
        setTextColor(Color.BLACK)
        if (multi) {
            minLines = 4
            gravity = android.view.Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
    }

    private fun editRow(r: HRow) {
        val et = editBox(r.text, true)
        AlertDialog.Builder(this)
            .setTitle("Step ${r.n} edit")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                TaskStore.updateHist(this, r.rowId, et.text.toString().trim())
                showTask(taskId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editGoal(t: Task) {
        val et = editBox(t.goal, true)
        AlertDialog.Builder(this)
            .setTitle("Goal edit")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val g = et.text.toString().trim()
                if (g.isNotEmpty()) TaskStore.setGoal(this, t.id, g)
                showTask(t.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun editInstructions(t: Task) {
        val et = editBox(t.instructions.joinToString("\n"), true)
        AlertDialog.Builder(this)
            .setTitle("Instructions (har line ek)")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                TaskStore.setInstructions(this, t.id, et.text.toString().lines().map { it.trim() }.filter { it.isNotEmpty() })
                showTask(t.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmPurge(t: Task) {
        AlertDialog.Builder(this)
            .setTitle("Task hamesha ke liye hatana hai?")
            .setMessage("#${t.id} ${t.goal.take(60)}\nIski history, pending steps aur checkpoints sab hat jayenge.")
            .setPositiveButton("Hatao") { _, _ ->
                TaskStore.purgeTask(this, t.id)
                showTasks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun makeSkill(t: Task) {
        val steps = SkillAI.stepsFromTask(this, t.id)
        if (steps.isEmpty()) {
            toast("Is task me koi safal step nahi mila")
            return
        }
        val plat = Platforms.detect(t.goal + " " + steps.joinToString(" "))
        TaskStore.saveSkill(this, t.goal.take(40), t.goal, SkillAI.numbered(steps), plat, "Task #${t.id} se bani")
        AlertDialog.Builder(this)
            .setTitle("Skill ban gayi")
            .setMessage("Skill Studio me kholkar edit karo ya 'AI se saaf karo' se general banao.")
            .setPositiveButton("Studio kholo") { _, _ -> startActivity(Intent(this, SkillsActivity::class.java)) }
            .setNegativeButton("Baad me", null)
            .show()
    }

    private fun updateSkillFrom(t: Task) {
        val skills = TaskStore.skills(this)
        if (skills.isEmpty()) {
            toast("Abhi koi skill nahi hai. Pehle '🧩 Skill banao' dabao.")
            return
        }
        val steps = SkillAI.stepsFromTask(this, t.id)
        if (steps.isEmpty()) {
            toast("Is task me koi safal step nahi mila")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Kaunsi skill update karni hai?")
            .setItems(skills.map { "${it.name}  v${it.version}" + (if (it.platform.isNotBlank()) "  (${it.platform})" else "") }.toTypedArray()) { _, i ->
                val s = skills[i]
                AlertDialog.Builder(this)
                    .setTitle("'${s.name}' ke steps badal dein?")
                    .setMessage("Purane steps hat kar Task #${t.id} ke ${steps.size} safal steps lagenge. Version v${s.version + 1} ban jayega.")
                    .setPositiveButton("Update") { _, _ ->
                        val notes = (s.notes + "\nUpdated from task #${t.id}").trim()
                        TaskStore.updateSkill(this, s.id, s.name, s.goal, SkillAI.numbered(steps), s.platform, notes)
                        toast("Skill update ho gayi (v${s.version + 1})")
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
