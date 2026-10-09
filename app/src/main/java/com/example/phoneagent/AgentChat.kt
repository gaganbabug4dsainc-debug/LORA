package com.example.phoneagent

/**
 * Chat ka dimaag: floating panel, dashboard aur normal chat teeno isi se agent ko control karte hain,
 * isliye sab ek hi task state (TaskStore) ko dekhte hain. Chhote commands (stop, pause, skip,
 * status...) model ke bina, local hi chal jaate hain — internet ya quota ki zarurat nahi.
 */
object AgentChat {

    private fun norm(t: String): String =
        t.lowercase()
            .replace(Regex("[^a-z0-9\\u0900-\\u097F ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun has(l: String, vararg keys: String): Boolean = keys.any { l.contains(it) }

    private fun reply(s: String, speak: Boolean = true) {
        AgentLog.add("Agent: $s")
        if (speak && s.length <= 200 && !s.contains('\n')) VoiceOut.say(s, true)
    }

    /** Ye message agent ka command hai (pause, stop, status...) ya nahi. */
    fun isCommand(text: String): Boolean = detect(norm(text.trim())) != null

    /** Normal Chat ke model ko current task ki poori state batane ke liye (user ko dobara samjhana na pade). */
    fun contextSummary(): String {
        val st = TaskStore.current ?: return "Abhi koi agent task nahi hai."
        val model = if (AgentState.model.isEmpty()) "auto" else AgentState.model
        val history = TaskStore.sync { st.history.takeLast(6) }
        val errors = TaskStore.sync { st.errors.takeLast(3) }
        val queued = TaskStore.queueSnapshot(st).filter { it.enabled && !it.checkpoint }.take(5)
        val files = TaskStore.filesSnapshot(st).keys
        val sb = StringBuilder()
        sb.append("AGENT TASK: ").append(st.goal).append("\n")
        sb.append("Status: ${AgentState.statusLabel()} (${st.status}), ${AgentState.stepText()}, model $model\n")
        sb.append("Last checkpoint: ").append(TaskStore.lastCheckpointText(st)).append("\n")
        if (st.skill.isNotEmpty()) sb.append("Skill: ").append(st.skill).append("\n")
        if (files.isNotEmpty()) sb.append("Files: ").append(files.joinToString(", ")).append("\n")
        if (queued.isNotEmpty()) sb.append("Next steps: ").append(queued.joinToString(" | ") { it.text }).append("\n")
        if (history.isNotEmpty()) sb.append("Recent actions:\n").append(history.joinToString("\n")).append("\n")
        if (errors.isNotEmpty()) sb.append("Recent errors: ").append(errors.joinToString(" | ")).append("\n")
        return sb.toString()
    }

    /** Short message ko command me badalta hai. Lambe instruction ko command nahi samajhta. */
    private fun detect(l: String): String? {
        val words = if (l.isEmpty()) 0 else l.split(" ").size
        if (words == 0 || words > 5) return null
        return when {
            has(l, "stop", "abort", "cancel", "band kar", "roko", "rok do", "रोको", "बंद", "स्टॉप") -> "stop"
            has(l, "pause", "ruko", "ruk jao", "thehro", "hold on", "wait", "रुको", "रुक जाओ", "पॉज") -> "pause"
            has(
                l, "resume", "continue", "chalo", "jaari", "jari rakho", "aage badho", "carry on", "go on",
                "जारी", "चलो", "आगे बढ़ो", "कंटिन्यू"
            ) -> "resume"
            has(l, "skip", "chhod do", "chod do", "स्किप", "छोड़ दो") -> "skip"
            has(l, "next", "अगला", "नेक्स्ट") -> "next"
            has(l, "retry", "dobara", "phir se", "दोबारा", "फिर से") -> "retry"
            has(l, "checkpoint", "चेकपॉइंट") -> "checkpoint"
            has(l, "go back", "wapas jao", "peeche jao", "वापस जाओ", "पीछे जाओ") -> "back"
            has(
                l, "change goal", "change the goal", "change the current goal", "goal badlo", "naya goal",
                "गोल बदलो", "नया गोल"
            ) -> "goal"
            has(
                l, "status", "what are you doing", "kya kar", "kya ho raha", "where are you",
                "kaha ho", "what is the problem", "problem", "स्टेटस", "क्या कर रहे", "क्या हो रहा", "समस्या"
            ) -> "status"
            else -> null
        }
    }

    fun statusText(): String {
        val st = TaskStore.current ?: return "Abhi koi task nahi hai."
        val last = TaskStore.sync { st.history.lastOrNull() } ?: "(abhi kuch nahi)"
        val queued = TaskStore.queueSnapshot(st).count { it.enabled && !it.checkpoint }
        val model = if (AgentState.model.isEmpty()) "-" else AgentState.model
        return "Task: ${st.goal}\n" +
            "${AgentState.stepText()} • ${AgentState.statusLabel()} (${st.status})\n" +
            "Abhi: ${AgentState.detail}\n" +
            "Pichla action: $last\n" +
            "Model: $model ${AgentState.modeLabel()}\n" +
            "Checkpoint: ${TaskStore.lastCheckpointText(st)}\n" +
            "Queue me: $queued step"
    }

    /** Chat se aaya hua har message yahi aata hai. */
    fun handle(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        AgentLog.add("Tum: $t")

        if (AgentLoop.awaitingGoal) {
            AgentLoop.changeGoal(t)
            return
        }

        when (detect(norm(t))) {
            "stop" -> {
                if (AgentLoop.isRunning()) AgentLoop.stop() else reply("Abhi koi agent chal nahi raha.")
                return
            }
            "pause" -> {
                AgentLoop.pause()
                return
            }
            "resume" -> {
                AgentLoop.resume()
                return
            }
            "skip" -> {
                AgentLoop.skip()
                return
            }
            "next" -> {
                AgentLoop.next()
                return
            }
            "retry" -> {
                AgentLoop.retry()
                return
            }
            "checkpoint" -> {
                AgentLoop.manualCheckpoint()
                return
            }
            "back" -> {
                AgentLoop.goBack()
                return
            }
            "goal" -> {
                if (TaskStore.current == null) {
                    reply("Abhi koi task nahi hai. Seedha naya goal likh do.")
                } else {
                    AgentLoop.awaitingGoal = true
                    reply("Naya goal likho (agla message goal ban jayega).")
                }
                return
            }
            "status" -> {
                reply(statusText(), false)
                VoiceOut.say("${AgentState.stepText()}. ${AgentState.detail}", true)
                return
            }
        }

        // Command nahi: chal raha ho to user ka naya instruction, warna naya goal.
        if (AgentLoop.isRunning()) {
            AgentLoop.addNote(t)
            reply("Samajh gaya. Agle step me is instruction ko follow karunga.")
            return
        }
        val svc = AgentAccessibilityService.instance
        if (svc == null) {
            reply("Pehle Settings → Accessibility me Phone Agent ko on karo.")
            return
        }
        AgentLoop.startNew(svc, t)
    }
}
