package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateUtils
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Home: ek nazar me sab kuch. Upar status + controls, phir goal likhne ki jagah, setup checklist,
 * quick tiles (Chat / Skill Studio / History / Doosre AI), recent tasks. Settings alag screen me.
 */
class MainActivity : Activity() {

    companion object {
        private var recoveryShown = false
    }

    private lateinit var root: LinearLayout
    private lateinit var heroBox: LinearLayout
    private lateinit var setupBox: LinearLayout
    private lateinit var tasksBox: LinearLayout
    private lateinit var statsView: TextView
    private lateinit var logView: TextView
    private lateinit var goal: EditText
    private lateinit var badgeView: TextView

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { logView.append(line + "\n") }
    }
    private val stateListener: () -> Unit = { runOnUiThread { refreshHero() } }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun service(): AgentAccessibilityService? {
        val s = AgentAccessibilityService.instance
        if (s == null) toast("Pehle Accessibility me LoRA ko on karo")
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TaskStore.recoverOnStart(this)
        Pool.refresh(this)

        root = Ui.vbox(this)
        root.setPadding(dp(16), dp(28), dp(16), dp(28))

        // ---- header ----
        val head = Ui.hbox(this)
        val titles = Ui.vbox(this)
        titles.addView(Ui.title(this, "LoRA"))
        titles.addView(Ui.text(this, "Phone Agent · v0.6", 12f, Ui.MUTED))
        head.addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.button(this, "⚙", "tonal") {
            startActivity(Intent(this, SettingsActivity::class.java))
        }.apply { textSize = 18f })
        root.addView(head)

        badgeView = Ui.pill(this, "", Ui.INFO_SOFT, Ui.BLUE)
        root.addView(badgeView, Ui.lp(this, top = 8).also { it.width = ViewGroup.LayoutParams.WRAP_CONTENT })

        // ---- hero (agent status + controls) ----
        heroBox = Ui.card(this, Ui.SURFACE, 16)
        root.addView(heroBox, Ui.lp(this, top = 12))

        // ---- composer ----
        val comp = Ui.card(this)
        comp.addView(Ui.text(this, "Agent ko kaam do", 15f, Ui.TEXT, true))
        goal = EditText(this).apply {
            hint = "Jaise: Clock app kholo aur 7 baje ka alarm lagao"
            minLines = 2
            background = Ui.rounded(Ui.BG, dp(12))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            textSize = 14f
        }
        comp.addView(goal, Ui.lp(this, top = 8))
        val chipScroll = HorizontalScrollView(this)
        chipScroll.isHorizontalScrollBarEnabled = false
        val chips = Ui.hbox(this)
        val templates = listOf(
            "📱 App kholo" to "Settings app kholo",
            "⏰ Alarm" to "Clock app kholo aur subah 7 baje ka alarm lagao",
            "📶 Wi-Fi" to "Settings me jaakar Wi-Fi on karo",
            "💬 Message" to "WhatsApp kholo aur ",
            "🔎 Search" to "Chrome kholo aur search karo: "
        )
        for ((label, text) in templates) {
            chips.addView(Ui.chip(this, label, Ui.PRIMARY_SOFT, Ui.PRIMARY, 12f) {
                goal.setText(text)
                goal.setSelection(text.length)
            }, Ui.lp(this, right = 6).also { it.width = ViewGroup.LayoutParams.WRAP_CONTENT })
        }
        chipScroll.addView(chips)
        comp.addView(chipScroll, Ui.lp(this, top = 8))
        comp.addView(Ui.button(this, "▶  Agent start / message bhejo", "primary") { startAgent() }, Ui.lp(this, top = 10))
        root.addView(comp, Ui.lp(this, top = 12))

        // ---- setup checklist ----
        setupBox = Ui.vbox(this)
        root.addView(setupBox, Ui.lp(this, top = 12))

        // ---- quick tiles ----
        root.addView(Ui.section(this, "SHORTCUTS"))
        val t1 = Ui.row(
            this,
            Ui.tile(this, "💬", "Chat", "Offline history") { startActivity(Intent(this, ChatActivity::class.java)) },
            Ui.tile(this, "🧩", "Skill Studio", "Skills banao") { startActivity(Intent(this, SkillsActivity::class.java)) },
            gap = 10
        )
        val t2 = Ui.row(
            this,
            Ui.tile(this, "🕘", "History", "Purane tasks") { startActivity(Intent(this, HistoryActivity::class.java)) },
            Ui.tile(this, "🤖", "Doosre AI", "ChatGPT, Gemini...") { Platforms.askDialog(this) },
            gap = 10
        )
        root.addView(t1)
        root.addView(t2, Ui.lp(this, top = 10))
        root.addView(
            Ui.button(this, "🧠 Model badlo  (${ModelPicker.shortLabel(this)})", "outline") {
                ModelPicker.show(this, false) { runOnUiThread { refreshAll() } }
            }.also { it.tag = "modelBtn" },
            Ui.lp(this, top = 10)
        )

        // ---- recent tasks ----
        root.addView(Ui.section(this, "HAAL KE TASKS"))
        statsView = Ui.text(this, "", 12f, Ui.MUTED)
        root.addView(statsView)
        tasksBox = Ui.vbox(this)
        root.addView(tasksBox, Ui.lp(this, top = 6))

        // ---- log ----
        logView = Ui.text(this, "", 12f, Ui.TEXT)
        root.addView(Ui.collapsible(this, "📜", "Live log", "Agent kya kar raha hai", logView), Ui.lp(this, top = 16))

        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
    }

    // ---------------- actions ----------------

    private fun startAgent() {
        val svc = service() ?: return
        val g = goal.text.toString().trim()
        if (g.isEmpty()) {
            toast("Goal likho")
            return
        }
        if (Config.load(this).isEmpty()) {
            toast("Pehle Settings me provider ya local model jodo")
            return
        }
        AgentLoop.submit(svc, g)
        goal.setText("")
    }

    private fun resumeAgent() {
        if (AgentLoop.isRunning()) {
            AgentLoop.setPaused(false)
            return
        }
        val svc = service() ?: return
        val tk = TaskStore.unfinished(this)
        if (tk == null) toast("Koi adhura task nahi") else AgentLoop.resumeTask(svc, tk.id, null)
    }

    private fun checkRecovery() {
        if (recoveryShown) return
        recoveryShown = true
        val t = TaskStore.interrupted(this) ?: return
        AlertDialog.Builder(this)
            .setTitle("Adhura Agent task mila")
            .setMessage("Task #${t.id}: ${t.goal.take(80)}\nStep ${t.step} tak ho chuka tha.")
            .setPositiveButton("Continue") { _, _ ->
                val svc = service() ?: return@setPositiveButton
                AgentLoop.resumeTask(svc, t.id, null)
            }
            .setNeutralButton("Restart") { _, _ ->
                val svc = service() ?: return@setNeutralButton
                TaskStore.reset(this, t.id)
                AgentLoop.resumeTask(svc, t.id, null)
            }
            .setNegativeButton("Delete") { _, _ ->
                TaskStore.discard(this, t.id)
                refreshTasks()
            }
            .show()
    }

    // ---------------- refresh ----------------

    private fun refreshAll() {
        refreshBadge()
        refreshHero()
        refreshSetup()
        refreshTasks()
        (root.findViewWithTag<View>("modelBtn") as? TextView)?.text = "🧠 Model badlo  (${ModelPicker.shortLabel(this)})"
    }

    private fun refreshBadge() {
        val label = Privacy.activeLabel(this)
        val offline = label.startsWith("🟢")
        Ui.restyle(
            badgeView, label + "  ·  " + ModelPicker.shortLabel(this),
            if (offline) Ui.OK_SOFT else if (label.startsWith("⚠")) Ui.WARN_SOFT else Ui.INFO_SOFT,
            if (offline) Ui.OK else if (label.startsWith("⚠")) Ui.WARN else Ui.BLUE
        )
    }

    private fun refreshHero() {
        heroBox.removeAllViews()
        val running = AgentLoop.isRunning()
        val tk = if (AgentLoop.taskId > 0) TaskStore.get(this, AgentLoop.taskId)
        else TaskStore.unfinished(this) ?: TaskStore.latest(this)
        val (stateText, bg, fg) = when (AgentState.status) {
            Status.IDLE -> Triple("⚪ Idle", Ui.LINE, Ui.MUTED)
            Status.RUNNING -> Triple("🟢 Running", Ui.OK_SOFT, Ui.OK)
            Status.WAITING -> Triple("🟡 Waiting", Ui.WARN_SOFT, Ui.WARN)
            Status.PAUSED -> Triple("🔵 Paused", Ui.INFO_SOFT, Ui.BLUE)
            Status.ERROR -> Triple("🔴 Error", Ui.ERR_SOFT, Ui.ERR)
        }
        val step = if (running) AgentLoop.step else (tk?.step ?: 0)
        val total = AgentLoop.totalLabel(this)

        val top = Ui.hbox(this)
        top.addView(Ui.pill(this, stateText, bg, fg))
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(Ui.text(this, "Step $step / $total", 12.5f, Ui.MUTED, true))
        heroBox.addView(top)

        heroBox.addView(
            Ui.text(this, tk?.goal?.take(90) ?: "Abhi koi task nahi — neeche goal likho", 16f, Ui.TEXT, true),
            Ui.lp(this, top = 10)
        )
        val frac = if (total == "∞") (if (step > 0) 0.15f else 0f) else (step.toFloat() / (total.toIntOrNull() ?: 1).coerceAtLeast(1))
        heroBox.addView(Ui.bar(this, frac, fg.takeIf { it != Ui.MUTED } ?: Ui.PRIMARY), Ui.lp(this, top = 10))
        heroBox.addView(Ui.text(this, AgentState.detail, 13f, Ui.MUTED), Ui.lp(this, top = 8))
        if (tk != null) {
            heroBox.addView(
                Ui.text(this, "⚑ Checkpoint: Step ${tk.checkpoint}  ·  ${tk.status}", 11.5f, Ui.MUTED),
                Ui.lp(this, top = 2)
            )
        }

        val paused = AgentLoop.paused
        val r1 = Ui.row(
            this,
            if (running && !paused) Ui.button(this, "⏸ Pause", "tonal") { AgentLoop.setPaused(true) }
            else Ui.button(this, "▶ Resume", "primary") { resumeAgent() },
            Ui.button(this, "Skip", "tonal") { AgentLoop.skip() },
            Ui.button(this, "Next", "tonal") { AgentLoop.next() },
            Ui.button(this, "■ Stop", "danger") { AgentLoop.stop() }
        )
        val r2 = Ui.row(
            this,
            Ui.button(this, "↻ Retry", "outline") { if (running) AgentLoop.retry() else toast("Agent chal nahi raha") },
            Ui.button(this, "⟲ Restart", "outline") {
                val svc = service() ?: return@button
                AlertDialog.Builder(this).setTitle("Restart?")
                    .setMessage("Task Step 1 se dobara shuru hoga.")
                    .setPositiveButton("Restart") { _, _ -> AgentLoop.restart(svc) }
                    .setNegativeButton("Cancel", null).show()
            },
            Ui.button(this, "✎ Steps", "outline") { service()?.showStepsEditor() },
            Ui.button(this, "⚑", "outline") {
                if (running) AgentLoop.checkpointNow(this, "manual") else toast("Agent chal nahi raha")
            }
        )
        heroBox.addView(r1, Ui.lp(this, top = 12))
        heroBox.addView(r2, Ui.lp(this, top = 8))
    }

    private fun refreshSetup() {
        setupBox.removeAllViews()
        val accOn = AgentAccessibilityService.instance != null
        val hasModel = Config.load(this).isNotEmpty()
        if (accOn && hasModel) {
            setupBox.addView(Ui.pill(this, "✓ Setup poora: Accessibility on, model jodha hua", Ui.OK_SOFT, Ui.OK))
            return
        }
        val card = Ui.card(this, Ui.WARN_SOFT)
        card.addView(Ui.text(this, "Setup baaki hai", 15f, Ui.WARN, true))
        if (!accOn) {
            card.addView(
                Ui.text(this, "1. Accessibility me LoRA on karo (Android 13+: App info → ⋮ → Allow restricted settings).", 13f, Ui.TEXT),
                Ui.lp(this, top = 6)
            )
            card.addView(Ui.button(this, "Accessibility settings kholo", "primary") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }, Ui.lp(this, top = 8))
        }
        if (!hasModel) {
            card.addView(
                Ui.text(this, (if (accOn) "1" else "2") + ". Koi model jodo: cloud API key ya phone ke andar ka model.", 13f, Ui.TEXT),
                Ui.lp(this, top = 8)
            )
            card.addView(Ui.button(this, "Settings kholo", "tonal") {
                startActivity(Intent(this, SettingsActivity::class.java))
            }, Ui.lp(this, top = 8))
        }
        setupBox.addView(card)
    }

    private fun refreshTasks() {
        tasksBox.removeAllViews()
        val all = TaskStore.recent(this, 200)
        val done = all.count { it.status == "completed" }
        statsView.text = "${all.size} task · $done poore · ${all.sumOf { it.step }} total step"
        val list = all.take(4)
        if (list.isEmpty()) {
            tasksBox.addView(Ui.note(this, "(abhi koi task nahi)"))
            return
        }
        for (t in list) {
            val c = Ui.card(this, Ui.SURFACE, 12)
            val top = Ui.hbox(this)
            top.addView(
                Ui.text(this, t.goal.take(48), 14f, Ui.TEXT, true),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            val ok = t.status == "completed"
            top.addView(Ui.pill(this, t.status, if (ok) Ui.OK_SOFT else Ui.LINE, if (ok) Ui.OK else Ui.MUTED))
            c.addView(top)
            c.addView(
                Ui.text(
                    this,
                    "Step ${t.step} · " + DateUtils.getRelativeTimeSpanString(t.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
                    12f, Ui.MUTED
                )
            )
            val acts = Ui.hbox(this)
            if (!ok) acts.addView(Ui.chip(this, "▶ Resume", Ui.PRIMARY, android.graphics.Color.WHITE, 12f) {
                val svc = service() ?: return@chip
                AgentLoop.resumeTask(svc, t.id, null)
            }, Ui.lp(this, right = 6).also { it.width = ViewGroup.LayoutParams.WRAP_CONTENT })
            acts.addView(Ui.chip(this, "Kholo", Ui.PRIMARY_SOFT, Ui.PRIMARY, 12f) {
                startActivity(Intent(this, HistoryActivity::class.java))
            }, Ui.lp(this, right = 6).also { it.width = ViewGroup.LayoutParams.WRAP_CONTENT })
            acts.addView(Ui.chip(this, "Hatao", Ui.ERR_SOFT, Ui.ERR, 12f) {
                TaskStore.discard(this, t.id)
                refreshTasks()
            })
            c.addView(acts, Ui.lp(this, top = 8))
            tasksBox.addView(c, Ui.lp(this, bottom = 8))
        }
        if (all.size > list.size) {
            tasksBox.addView(Ui.button(this, "Saare tasks dekho (${all.size})", "tonal") {
                startActivity(Intent(this, HistoryActivity::class.java))
            })
        }
    }

    override fun onResume() {
        super.onResume()
        Pool.refresh(this)
        refreshAll()
        logView.text = AgentLog.lines.joinToString("\n")
        AgentLog.addListener(logListener)
        AgentState.addListener(stateListener)
        checkRecovery()
    }

    override fun onPause() {
        super.onPause()
        AgentLog.removeListener(logListener)
        AgentState.removeListener(stateListener)
    }
}
