package com.example.phoneagent

import android.content.Context

/**
 * Bolkar ya likhkar agent ko control: "pause", "continue", "skip this step", "stop", "status"...
 * Ye local (offline) chalta hai, LLM nahi lagta. Sirf chhote messages (<= 9 shabd) aur sirf tab jab koi task
 * chal raha ho ya adhura ho, taaki normal baatcheet na bigde. Floating Agent Chat aur normal Chat dono yahi use karte hain.
 */
object AgentCommands {
    @Volatile
    var awaitingGoal = false

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun has(t: String, vararg ks: String) = ks.any { k -> " $t ".contains(" $k ") }

    /** Command samjha to jawab ka text, warna null (matlab ye command nahi hai). */
    fun handle(ctx: Context, raw: String): String? {
        val app = ctx.applicationContext
        if (awaitingGoal) {
            awaitingGoal = false
            AgentLoop.changeGoal(app, raw.trim())
            return "Goal badal diya: ${raw.trim()}"
        }
        val t = norm(raw)
        if (t.isEmpty() || t.split(' ').size > 9) return null
        val running = AgentLoop.isRunning()
        val tk = TaskStore.unfinished(app)
        if (!running && tk == null) return null
        val svc = AgentAccessibilityService.instance

        return when {
            has(t, "stop", "ruko", "roko", "abort", "cancel", "band karo", "ruk jao") -> {
                if (!running) "Agent abhi chal nahi raha. 'continue' bolo to Task #${tk?.id} aage badhega."
                else {
                    AgentLoop.stop()
                    "Rok diya. State aur checkpoint save ho gaye; baad me continue kar sakte ho."
                }
            }
            has(t, "pause", "thehro", "ruk", "hold", "ek minute") -> {
                if (!running) "Agent chal nahi raha."
                else {
                    AgentLoop.setPaused(true)
                    "Pause kar diya. 'continue' ya 'next' bolo."
                }
            }
            has(t, "checkpoint") && has(t, "continue", "resume", "wapas", "se") -> resume(app, svc, running, tk, "User ne last checkpoint se continue karne ko kaha.")
            has(t, "resume", "continue", "chalo", "jari rakho", "aage badho", "chalu karo", "aage chalo") ->
                resume(app, svc, running, tk, null)
            has(t, "skip", "chhod do", "chhodo", "chod do") -> {
                if (!running) "Agent chal nahi raha."
                else {
                    AgentLoop.skip()
                    "Theek hai, ye step skip kar raha hu."
                }
            }
            has(t, "next", "agla", "agla step") -> {
                if (!running) "Agent chal nahi raha."
                else {
                    AgentLoop.next()
                    "Agla step chala raha hu, phir ruk jaunga."
                }
            }
            has(t, "go back", "piche jao", "peeche jao", "back") -> {
                AgentLoop.userBack()
                "Piche gaya."
            }
            has(t, "retry", "dobara", "phir se", "dobara try") -> {
                if (!running) "Agent chal nahi raha."
                else {
                    AgentLoop.retry()
                    "Pichla step dobara try kar raha hu."
                }
            }
            has(t, "restart", "shuru se") -> {
                if (svc == null) "Pehle Accessibility me LoRA on karo."
                else {
                    AgentLoop.restart(svc)
                    "Task ko Step 1 se dobara shuru kar raha hu."
                }
            }
            has(t, "checkpoint") -> {
                if (!running) "Agent chal nahi raha."
                else {
                    AgentLoop.checkpointNow(app, "manual")
                    "Checkpoint bana diya."
                }
            }
            has(t, "change the goal", "goal badlo", "goal badal do", "naya goal") -> {
                awaitingGoal = true
                "Naya goal likho ya bolo."
            }
            has(t, "status", "progress", "what are you doing", "what is the problem", "kya kar rahe ho", "kya ho raha hai",
                "problem kya hai", "kahan tak pahuche", "kaha tak pahuche") -> AgentLoop.statusText(app)
            else -> null
        }
    }

    private fun resume(app: Context, svc: AgentAccessibilityService?, running: Boolean, tk: Task?, extra: String?): String {
        if (running) {
            AgentLoop.setPaused(false)
            return "Continue kar raha hu."
        }
        if (svc == null) return "Pehle Accessibility me LoRA on karo."
        val id = tk?.id ?: return "Koi adhura task nahi hai."
        AgentLoop.resumeTask(svc, id, extra)
        return "Task #$id ko Step ${tk.step + 1} se continue kar raha hu."
    }
}
