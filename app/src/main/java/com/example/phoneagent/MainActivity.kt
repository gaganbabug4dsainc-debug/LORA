package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        /** Crash-recovery dialog process me ek hi baar dikhao. */
        private var recoveryShown = false
    }

    private lateinit var logView: TextView
    private lateinit var dashStatus: TextView
    private lateinit var dashGoal: TextView
    private lateinit var pauseBtn: Button
    private lateinit var chatInput: EditText
    private lateinit var queueBox: LinearLayout
    private lateinit var modelsBox: LinearLayout
    private lateinit var providersBox: LinearLayout
    private lateinit var settingsBox: LinearLayout
    private var lastQueueSig = ""

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { logView.append(line + "\n") }
    }
    private val stateListener: () -> Unit = {
        runOnUiThread { refreshDash() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun heading(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(4))
    }

    private fun small(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
    }

    private fun btn(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun btnRow(vararg bs: Button): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (b in bs) {
            r.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return r
    }

    private fun radioGroup(options: List<String>, selected: Int, onPick: (Int) -> Unit): RadioGroup {
        val g = RadioGroup(this)
        g.orientation = RadioGroup.VERTICAL
        for ((i, o) in options.withIndex()) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = o
            g.addView(rb)
            rb.setOnClickListener { onPick(i) }
        }
        (g.getChildAt(selected) as? RadioButton)?.isChecked = true
        return g
    }

    private fun askText(
        title: String,
        initial: String,
        onDismiss: (() -> Unit)? = null,
        onOk: (String) -> Unit
    ) {
        val input = EditText(this)
        input.setText(initial)
        input.setSelection(initial.length)
        val d = AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ ->
                val t = input.text.toString().trim()
                if (t.isNotEmpty()) onOk(t)
            }
            .setNegativeButton("Cancel", null)
            .create()
        if (onDismiss != null) d.setOnDismissListener { onDismiss() }
        d.show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Boot.init(this)
        val pad = dp(16)

        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(pad, pad * 2, pad, pad)

        val title = TextView(this).apply {
            text = "Phone Agent v0.4"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        page.addView(title)
        val navRow = LinearLayout(this)
        navRow.orientation = LinearLayout.HORIZONTAL
        navRow.addView(btn("💬 Chat") { startActivity(android.content.Intent(this, ChatActivity::class.java)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        navRow.addView(btn("⚙️ Aur settings") { startActivity(android.content.Intent(this, MoreActivity::class.java)) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        page.addView(navRow)

        // ---------------- Dashboard ----------------
        page.addView(heading("AGENT"))
        dashGoal = TextView(this).apply { textSize = 14f }
        dashStatus = TextView(this).apply { textSize = 13f }
        page.addView(dashGoal)
        page.addView(dashStatus)

        pauseBtn = btn("⏸ Pause") { togglePause() }
        page.addView(
            btnRow(
                pauseBtn,
                btn("■ Stop") { AgentLoop.stop() },
                btn("⏭ Skip") { AgentLoop.skip() },
                btn("▷ Next") { AgentLoop.next() }
            )
        )
        page.addView(
            btnRow(
                btn("↻ Retry") { AgentLoop.retry() },
                btn("⚑ Checkpoint") { AgentLoop.manualCheckpoint() },
                btn("⟲ Restart") { restartTask() }
            )
        )
        page.addView(btn("1. Accessibility settings kholo") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        // ---------------- Chat ----------------
        page.addView(heading("Agent Chat / naya goal"))
        page.addView(
            small(
                "Chal raha ho to ye message agent ko instruction hai. Ruka ho to naya goal. " +
                    "Commands: stop, pause, continue, skip, next, retry, status, checkpoint, change goal."
            )
        )
        chatInput = EditText(this).apply {
            hint = "Likho ya command do (jaise: pause, status)"
            minLines = 2
        }
        page.addView(chatInput)
        page.addView(btn("Bhejo / Start") {
            val t = chatInput.text.toString().trim()
            if (t.isEmpty()) {
                toast("Kuch likho")
            } else {
                if (AgentAccessibilityService.instance == null && !AgentLoop.isRunning()) {
                    toast("Pehle Accessibility me Phone Agent ko on karo")
                } else {
                    chatInput.setText("")
                    AgentChat.handle(t)
                }
            }
        })

        // ---------------- Steps queue ----------------
        page.addView(heading("Steps (user ke add kiye hue)"))
        page.addView(small("Agent inhe order me karta hai. Item par tap karke Edit/Delete/Move/Checkpoint wagaira."))
        queueBox = LinearLayout(this)
        queueBox.orientation = LinearLayout.VERTICAL
        page.addView(queueBox)
        val stepInput = EditText(this).apply { hint = "Naya step, jaise: screenshot lo" }
        page.addView(stepInput)
        page.addView(
            btnRow(
                btn("+ Agla step") { addQueueStep(stepInput, true) },
                btn("+ Aakhir me") { addQueueStep(stepInput, false) }
            )
        )

        // ---------------- Models ----------------
        page.addView(heading("Models"))
        page.addView(
            small("Model badalne se task reset nahi hota; naya model wahin se continue karta hai. Local model 'Aur settings' se jodo.")
        )
        modelsBox = LinearLayout(this)
        modelsBox.orientation = LinearLayout.VERTICAL
        page.addView(modelsBox)
        page.addView(btn("Models refresh") { renderModels() })

        // ---------------- Providers ----------------
        page.addView(heading("Providers (API keys)"))
        page.addView(small("Upar wala pehle chalta hai; limit/error aaye to agla apne aap."))
        providersBox = LinearLayout(this)
        providersBox.orientation = LinearLayout.VERTICAL
        page.addView(providersBox)

        val keyField = EditText(this).apply {
            hint = "API key (Gemini AIza... / OpenRouter sk-or-... / Groq gsk_... / custom)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelsField = EditText(this).apply {
            hint = "Model(s), comma se. Khali = auto (Gemini/OpenRouter)"
        }
        val baseField = EditText(this).apply { hint = "Base URL (sirf custom provider ke liye)" }
        page.addView(keyField)
        page.addView(modelsField)
        page.addView(baseField)
        page.addView(btn("+ Provider jodo") {
            val key = keyField.text.toString().trim()
            if (key.isEmpty()) {
                toast("API key daalo")
            } else {
                val type = Config.detectType(key)
                val base = if (type == "custom") baseField.text.toString().trim() else Config.presetBase(type)
                val models = Config.splitModels(modelsField.text.toString())
                if (type == "custom" && base.isEmpty()) {
                    toast("Is key ke liye Base URL chahiye")
                } else if ((type == "groq" || type == "custom") && models.isEmpty()) {
                    toast("Is provider ke liye model ka naam do")
                } else if (models.any { it.contains(' ') }) {
                    toast("Model ka slug likho (jaise vendor/model-name), display name nahi")
                } else {
                    val list = Config.load(this).filter { it.key != key } + Provider(type, key, base, models)
                    Config.save(this, list)
                    keyField.setText("")
                    modelsField.setText("")
                    baseField.setText("")
                    renderProviders()
                    renderModels()
                    toast("Jod diya: $type")
                }
            }
        })

        // ---------------- Settings ----------------
        page.addView(heading("Settings"))
        settingsBox = LinearLayout(this)
        settingsBox.orientation = LinearLayout.VERTICAL
        page.addView(settingsBox)

        val stepSwitch = Switch(this).apply {
            text = "Step-by-step mode (har step ke baad pause)"
            isChecked = Config.stepMode(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Config.setStepMode(this@MainActivity, on) }
        }
        val bubbleSwitch = Switch(this).apply {
            text = "Floating icon dikhao"
            isChecked = Config.bubbleEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Config.setBubble(this@MainActivity, on) }
        }
        page.addView(stepSwitch)
        page.addView(bubbleSwitch)

        // ---------------- Log ----------------
        page.addView(heading("Log"))
        logView = TextView(this).apply { textSize = 13f }
        page.addView(logView)

        setContentView(
            ScrollView(this).apply {
                addView(
                    page,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
        renderProviders()
        renderSettings()
        renderModels()
    }

    // ------------------------------------------------------------------

    private fun togglePause() {
        when {
            !AgentLoop.isRunning() -> AgentLoop.resume()
            AgentLoop.isPaused() -> AgentLoop.resume()
            else -> AgentLoop.pause()
        }
    }

    private fun restartTask() {
        val svc = AgentAccessibilityService.instance
        if (svc == null) {
            toast("Pehle Accessibility me Phone Agent ko on karo")
        } else {
            AgentLoop.restart(svc)
        }
    }

    private fun refreshDash() {
        val st = TaskStore.current
        val model = if (AgentState.model.isEmpty()) "auto" else AgentState.model
        val cp = if (AgentState.checkpoint.isEmpty()) "-" else AgentState.checkpoint
        dashGoal.text = "Task: ${st?.goal ?: "(koi nahi)"}"
        dashStatus.text = "${AgentState.statusLabel()} · ${AgentState.stepText()}\n" +
            "${AgentState.modeLabel()} · Model: $model\n" +
            "Checkpoint: $cp\n${AgentState.detail}"
        pauseBtn.text = if (AgentLoop.isRunning() && !AgentLoop.isPaused()) "⏸ Pause" else "▶ Resume"
        renderQueue(false)
    }

    // ---- steps queue ----

    private fun addQueueStep(field: EditText, front: Boolean) {
        val t = field.text.toString().trim()
        if (t.isEmpty()) {
            toast("Step likho")
            return
        }
        if (TaskStore.current == null) {
            toast("Pehle koi task chalao, phir step add karo")
            return
        }
        AgentLoop.addStep(t, front)
        field.setText("")
        renderQueue(true)
    }

    private fun renderQueue(force: Boolean) {
        val st = TaskStore.current
        val items: List<QueuedStep> = if (st == null) emptyList() else TaskStore.queueSnapshot(st)
        val sig = items.joinToString("|") { "${it.id}:${it.text}:${it.enabled}:${it.checkpoint}" }
        if (!force && sig == lastQueueSig) return
        lastQueueSig = sig
        queueBox.removeAllViews()
        if (items.isEmpty()) {
            queueBox.addView(small("(queue khali)"))
            return
        }
        for ((i, q) in items.withIndex()) {
            val prefix = when {
                q.checkpoint -> "⚑ "
                !q.enabled -> "⊘ "
                else -> "${i + 1}. "
            }
            val b = btn(prefix + q.text) { queueItemMenu(q) }
            b.gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
            queueBox.addView(b)
        }
    }

    private fun queueItemMenu(q: QueuedStep) {
        val st = TaskStore.current ?: return
        val opts = arrayOf(
            "Edit", "Delete", "Move Up", "Move Down", "Duplicate",
            "Convert to Checkpoint", if (q.enabled) "Disable" else "Enable"
        )
        AlertDialog.Builder(this).setTitle(q.text.take(40)).setItems(opts) { _, which ->
            when (which) {
                0 -> askText("Step edit karo", q.text) {
                    TaskStore.editStep(st, q.id, it)
                    renderQueue(true)
                }
                1 -> TaskStore.deleteStep(st, q.id)
                2 -> TaskStore.moveStep(st, q.id, -1)
                3 -> TaskStore.moveStep(st, q.id, 1)
                4 -> TaskStore.duplicateStep(st, q.id)
                5 -> TaskStore.toCheckpoint(st, q.id)
                6 -> TaskStore.toggleStep(st, q.id)
            }
            renderQueue(true)
        }.setNegativeButton("Cancel", null).show()
    }

    // ---- models ----

    private fun renderModels() {
        thread(name = "models-ui") {
            val providers = Config.load(this)
            val cands: List<Cand> = if (providers.isEmpty()) {
                emptyList()
            } else {
                Pool.set(providers)
                Pool.candidates()
            }
            runOnUiThread {
                modelsBox.removeAllViews()
                if (cands.isEmpty()) {
                    modelsBox.addView(small("(koi model nahi — pehle provider jodo)"))
                    return@runOnUiThread
                }
                val cur = Config.pinned(this)
                modelsBox.addView(modelRow("Auto (pehla available chalega)", "", cur == null) {
                    switchModel(null)
                })
                for (c in cands) {
                    val caps = c.caps()
                    modelsBox.addView(modelRow(c.label, caps, c.id == cur) { switchModel(c) })
                }
            }
        }
    }

    private fun modelRow(name: String, caps: String, current: Boolean, onUse: () -> Unit): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        val info = TextView(this).apply {
            text = (if (current) "● " else "○ ") + name + (if (caps.isEmpty()) "" else "\n    $caps")
            textSize = 13f
        }
        r.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (!current) r.addView(btn("Use") { onUse() })
        return r
    }

    private fun switchModel(c: Cand?) {
        if (AgentLoop.isRunning()) {
            val step = (TaskStore.current?.step ?: 0) + 1
            AlertDialog.Builder(this)
                .setTitle("Model badlein?")
                .setMessage("Model badalne se task state same rahega. Naya model Step $step se continue karega.")
                .setPositiveButton("Switch") { _, _ ->
                    AgentLoop.applyModelSwitch(this, c)
                    renderModels()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            AgentLoop.applyModelSwitch(this, c)
            renderModels()
        }
    }

    // ---- providers ----

    private fun renderProviders() {
        providersBox.removeAllViews()
        val list = Config.load(this)
        if (list.isEmpty()) {
            providersBox.addView(small("(abhi koi provider nahi)"))
            return
        }
        for ((i, p) in list.withIndex()) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            val models = if (p.models.isEmpty()) "auto" else p.models.joinToString(", ")
            val info = TextView(this).apply {
                text = "${i + 1}. ${p.type}  ..${p.key.takeLast(4)}\n    $models"
                textSize = 13f
            }
            val up = btn("↑") {
                if (i > 0) {
                    val m = list.toMutableList()
                    val t = m[i]
                    m[i] = m[i - 1]
                    m[i - 1] = t
                    Config.save(this, m)
                    renderProviders()
                    renderModels()
                }
            }
            val del = btn("Hatao") {
                Config.save(this, list.filterIndexed { idx, _ -> idx != i })
                renderProviders()
                renderModels()
            }
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(up)
            row.addView(del)
            providersBox.addView(row)
        }
    }

    // ---- settings ----

    private fun renderSettings() {
        settingsBox.removeAllViews()

        // Agent Step Limit
        settingsBox.addView(TextView(this).apply {
            text = "Agent Step Limit"
            typeface = Typeface.DEFAULT_BOLD
        })
        val limits = listOf(10, 20, 50, 100, 200, 0)
        val cur = Config.stepLimit(this)
        val opts = ArrayList<String>()
        for (l in limits) opts.add(if (l == 0) "♾ Unlimited" else "$l steps")
        opts.add(if (cur in limits) "Custom…" else "Custom ($cur)")
        val sel = if (cur in limits) limits.indexOf(cur) else limits.size
        settingsBox.addView(radioGroup(opts, sel) { i ->
            if (i < limits.size) {
                Config.setStepLimit(this, limits[i])
                renderSettings()
            } else {
                askText("Custom step limit (number)", cur.toString(), { renderSettings() }) {
                    val n = it.toIntOrNull()
                    if (n != null && n > 0) Config.setStepLimit(this, n) else toast("Sahi number likho")
                }
            }
        })
        settingsBox.addView(small("Unlimited me bhi infinite-loop protection chalti hai."))

        // Data processing
        settingsBox.addView(TextView(this).apply {
            text = "Data Processing"
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, 0)
        })
        val modes = listOf("local_only", "ask", "allow")
        val modeLabels = listOf(
            "Local Only (kuch online nahi bhejna)",
            "Ask Before Online Processing (default)",
            "Allow Online Processing"
        )
        settingsBox.addView(radioGroup(modeLabels, modes.indexOf(Config.dataMode(this)).coerceAtLeast(0)) { i ->
            Config.setDataMode(this, modes[i])
        })
        settingsBox.addView(small("'Local Only' me sirf jode hue local (offline) model chalenge; local model 'Aur settings' se jodo."))

        // Auto checkpoint
        settingsBox.addView(TextView(this).apply {
            text = "Auto checkpoint"
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(12), 0, 0)
        })
        val cps = listOf(0, 5, 10, 20)
        val cpLabels = listOf("Off", "Har 5 steps", "Har 10 steps", "Har 20 steps")
        settingsBox.addView(radioGroup(cpLabels, cps.indexOf(Config.autoCheckpoint(this)).coerceAtLeast(0)) { i ->
            Config.setAutoCheckpoint(this, cps[i])
        })
        settingsBox.addView(small("Risky action se pehle, pause/stop par aur model switch par checkpoint hamesha banta hai."))
    }

    // ---- crash recovery ----

    private fun showRecovery(st: TaskData) {
        AlertDialog.Builder(this)
            .setTitle("Adhoora Agent task mila")
            .setMessage("Goal: ${st.goal}\nStep ${st.step} tak ho chuka hai (${st.status}).")
            .setPositiveButton("Continue") { _, _ ->
                val svc = AgentAccessibilityService.instance
                if (svc == null) toast("Pehle Accessibility me Phone Agent ko on karo")
                else AgentLoop.resumeSaved(svc)
            }
            .setNeutralButton("Restart") { _, _ -> restartTask() }
            .setNegativeButton("Delete") { _, _ ->
                TaskStore.clear()
                refreshDash()
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        TaskStore.init(this)
        renderProviders()
        logView.text = AgentLog.lines.joinToString("\n")
        AgentLog.addListener(logListener)
        AgentState.addListener(stateListener)
        refreshDash()
        if (!recoveryShown && !AgentLoop.isRunning()) {
            val st = TaskStore.unfinished()
            if (st != null) {
                recoveryShown = true
                showRecovery(st)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        AgentLog.removeListener(logListener)
        AgentState.removeListener(stateListener)
    }
}
