package com.example.phoneagent

import android.content.Context
import kotlin.concurrent.thread

class SkillDraft(val name: String, val platform: String, val goal: String, val steps: String)

/**
 * Skill banane / sudharne / merge karne ke tools. "AI" wale tools abhi chuna hua model use karte hain
 * (Data Processing setting ke hisaab se local ya online-permission ke baad). Simple wale tools bina AI ke chalte hain.
 */
object SkillAI {
    private const val SYS = """
Tum ek "skill writer" ho. Skill = Android phone agent ke liye dobara-use hone wali steps ki list.
Rules:
- Steps screen-level hon: app kholo, '<button naam>' dabao, {value} type karo, scroll karo, 'wait' karo, jawab padho. Koi element id mat likho.
- Jo value har baar badalti hai use {curly} placeholder me likho (jaise {sawal}, {naam}).
- Chhote, saaf, ginti wale steps (max 15). Risky step (send/delete/pay) ke aage [CONFIRM] likho.
- Platform (app/website ka naam) pata ho to likho, warna khali.
SIRF is format me jawab do, aur kuch nahi:
NAME: <chhota naam>
PLATFORM: <app ka naam ya khali>
GOAL: <ek line ka goal>
STEPS:
1. ...
2. ...
"""

    // ---------- text helpers ----------

    fun lines(steps: String): List<String> = steps.lines()
        .map { it.trim().replace(Regex("^\\d+[.)]\\s*"), "") }
        .filter { it.isNotBlank() }

    fun numbered(l: List<String>): String = l.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n")

    /** Raw history line ("click id=5 Send -> ok") ko reusable line me badlo. */
    fun cleanStep(raw: String): String = raw
        .replace(Regex("\\s*id=-?\\d+"), "")
        .replace(Regex("\\s*->\\s*(ok|failed)\\s*$"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** Task ke safal steps (ok) safaai ke saath. */
    fun stepsFromTask(ctx: Context, id: Long): List<String> =
        TaskStore.historyRows(ctx, id)
            .filter { it.status == "ok" && !it.text.startsWith("done:") }
            .map { cleanStep(it.text) }
            .filter { it.isNotBlank() }

    fun parse(text: String): SkillDraft? {
        val t = text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
        val name = Regex("(?im)^\\s*NAME:\\s*(.+)$").find(t)?.groupValues?.get(1)?.trim() ?: ""
        val platform = Regex("(?im)^\\s*PLATFORM:\\s*(.*)$").find(t)?.groupValues?.get(1)?.trim() ?: ""
        val goal = Regex("(?im)^\\s*GOAL:\\s*(.+)$").find(t)?.groupValues?.get(1)?.trim() ?: ""
        val i = t.indexOf("STEPS:", ignoreCase = true)
        if (i < 0) return null
        val steps = lines(t.substring(i + 6))
        if (steps.isEmpty()) return null
        val plat = if (platform.equals("khali", true) || platform == "-" || platform.equals("none", true)) "" else platform
        return SkillDraft(name.ifBlank { goal.take(40) }, plat, goal, numbered(steps))
    }

    // ---------- simple (bina AI) ----------

    /** Kai skills ko jodo: steps ek ke baad ek, duplicate lines hata kar, dobara ginti. */
    fun mergeSimple(list: List<Skill>, name: String): SkillDraft {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (s in list) for (l in lines(s.steps)) {
            if (seen.add(l.lowercase())) out.add(l)
        }
        val plats = list.map { it.platform }.filter { it.isNotBlank() }.distinct()
        return SkillDraft(
            name.ifBlank { list.joinToString(" + ") { it.name }.take(60) },
            if (plats.size == 1) plats[0] else plats.joinToString("/"),
            list.joinToString(" -> ") { it.goal },
            numbered(out)
        )
    }

    // ---------- AI tools (background thread; callback usi thread se) ----------

    private fun run(ctx: Context, user: String, onStatus: (String) -> Unit, onDone: (SkillDraft?, String?) -> Unit) {
        val app = ctx.applicationContext
        thread(name = "skill-ai") {
            try {
                Pool.refresh(app)
                val r = LlmClient.ask(SYS, listOf(Turn("user", user)), false, { false }, onStatus)
                val d = parse(r)
                if (d == null) onDone(null, "Model ka jawab samajh nahi aaya. Dobara try karo ya model badlo.") else onDone(d, null)
            } catch (e: Exception) {
                onDone(null, e.message ?: "error")
            }
        }
    }

    fun generate(ctx: Context, description: String, platform: String, onStatus: (String) -> Unit, onDone: (SkillDraft?, String?) -> Unit) {
        val hint = if (platform.isNotBlank()) "\nPlatform: $platform. ${Platforms.hint(platform)}" else ""
        run(ctx, "Is kaam ka skill banao:\n$description$hint", onStatus, onDone)
    }

    fun refine(ctx: Context, d: SkillDraft, instruction: String, onStatus: (String) -> Unit, onDone: (SkillDraft?, String?) -> Unit) {
        val cur = "NAME: ${d.name}\nPLATFORM: ${d.platform}\nGOAL: ${d.goal}\nSTEPS:\n${d.steps}"
        run(ctx, "Ye skill sudharo/saaf karo. Instruction: ${instruction.ifBlank { "steps ko saaf, general aur kam karo; badalne wali values ko {placeholder} banao" }}\n\n$cur", onStatus, onDone)
    }

    fun mergeAi(ctx: Context, list: List<Skill>, onStatus: (String) -> Unit, onDone: (SkillDraft?, String?) -> Unit) {
        val sb = StringBuilder("In skills ko ek behtar skill me merge karo (duplicate hatao, order theek rakho):\n")
        for ((i, s) in list.withIndex()) {
            sb.append("\n--- Skill ${i + 1}: ${s.name} (platform: ${s.platform}) ---\nGOAL: ${s.goal}\nSTEPS:\n${s.steps}\n")
        }
        run(ctx, sb.toString(), onStatus, onDone)
    }
}
