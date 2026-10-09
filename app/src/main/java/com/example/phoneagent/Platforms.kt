package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast

class AiApp(val name: String, val hint: String)

/**
 * Phone ke doosre AI apps (ChatGPT, Gemini...) se chat karwane aur platform-wise skills ke liye madad.
 * Agent unhe Accessibility se chalata hai: app kholo -> sawal type karo -> jawab ka intezaar -> screen se padho.
 * UI hints sirf andaza hain; app update hone par badal sakte hain, isliye skill ko asli run ke baad "Update" karo.
 */
object Platforms {
    private const val GENERIC =
        "Message box ek editable field hota hai (hint jaise 'Message' / 'Ask anything'). Sawal type karke Send ya arrow button dabao. " +
            "Jawab likhna band hone tak 'wait' karo (stop/streaming indicator hatne tak). Naye chat ke liye 'New chat' icon. " +
            "Jawab screen ke text me milta hai; poora padhne ke liye zarurat ho to scroll karo."

    val AI_APPS = listOf(
        AiApp("ChatGPT", GENERIC),
        AiApp("Gemini", GENERIC),
        AiApp("Claude", GENERIC),
        AiApp("Copilot", GENERIC),
        AiApp("Perplexity", GENERIC),
        AiApp("DeepSeek", GENERIC)
    )

    /** Launcher me jo AI apps installed hain (label se match). */
    fun installed(ctx: Context): List<String> {
        val pm = ctx.packageManager
        val labels = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() }
        } catch (e: Exception) {
            emptyList()
        }
        return AI_APPS.map { it.name }.filter { n -> labels.any { it.contains(n, true) } }
    }

    /** Text me kisi known platform ka naam ho to wo, warna "". */
    fun detect(text: String): String =
        AI_APPS.firstOrNull { text.contains(it.name, true) }?.name ?: ""

    fun hint(platform: String): String =
        AI_APPS.firstOrNull { it.name.equals(platform, true) }?.hint ?: ""

    fun askGoal(app: String, question: String): String =
        "Open the app '$app'. Start a NEW chat if possible. In its message box type exactly this question: \"$question\". " +
            "Send it. Wait until the reply is completely written (streaming/stop indicator gone). Then read the reply from the screen " +
            "(scroll if needed) and finish with action done, putting the FULL reply text in message."

    /** Kisi AI app ke liye starter skill ke steps ({sawal} = run se pehle poochhi jane wali value). */
    fun starterSteps(app: String): String = SkillAI.numbered(
        listOf(
            "Open the app '$app' (open_app).",
            "Start a new chat if possible (New chat icon).",
            "Tap the message box and type {sawal}.",
            "Tap the Send button.",
            "Wait until the reply is completely written (streaming/stop indicator gone).",
            "Read the full reply from the screen (scroll if needed).",
            "Finish and report the full reply text."
        )
    )

    /** Skill chalate waqt agent ko diya jane wala hint. */
    fun runHint(s: Skill): String {
        val sb = StringBuilder()
        if (s.platform.isNotBlank()) {
            sb.append("Platform: ${s.platform}. Pehle '${s.platform}' app kholo (open_app). ")
            val h = hint(s.platform)
            if (h.isNotBlank()) sb.append(h).append(' ')
        }
        sb.append("Skill '${s.name}' v${s.version} ke steps (screen ke hisaab se badal sakte ho):\n").append(s.steps)
        return sb.toString()
    }

    /** {naam} jaise placeholders (jinki value run se pehle poochhi jati hai). */
    fun placeholders(vararg texts: String): List<String> =
        texts.flatMap { t -> Regex("\\{([^{}\\n]{1,30})}").findAll(t).map { it.groupValues[1] }.toList() }.distinct()

    /** Placeholder values poochho aur phir onReady(goal, steps). */
    fun fillAndRun(act: Activity, s: Skill, onReady: (String, String) -> Unit) {
        val keys = placeholders(s.goal, s.steps)
        if (keys.isEmpty()) {
            onReady(s.goal, s.steps)
            return
        }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0)
        }
        val fields = keys.map { k ->
            EditText(act).apply { hint = k; setSingleLine() }.also { box.addView(it) }
        }
        AlertDialog.Builder(act)
            .setTitle("Values bharo")
            .setView(box)
            .setPositiveButton("Run") { _, _ ->
                var g = s.goal
                var st = s.steps
                for ((i, k) in keys.withIndex()) {
                    val v = fields[i].text.toString().trim()
                    g = g.replace("{$k}", v)
                    st = st.replace("{$k}", v)
                }
                onReady(g, st)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** "Doosre AI app se poochho": app chuno + sawal likho -> agent chalta hai. */
    fun askDialog(act: Activity) {
        val inst = installed(act)
        val names = if (inst.isNotEmpty()) inst else AI_APPS.map { it.name }
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(act, 16), Ui.dp(act, 8), Ui.dp(act, 16), 0)
        }
        val group = RadioGroup(act)
        for ((i, n) in names.withIndex()) {
            group.addView(RadioButton(act).apply {
                text = n
                id = i + 1
                isChecked = i == 0
            })
        }
        val q = EditText(act).apply {
            hint = "Sawal jo us AI app se poochna hai"
            minLines = 2
        }
        box.addView(group)
        box.addView(q)
        if (inst.isEmpty()) {
            Toast.makeText(act, "Koi known AI app installed nahi dikha; naam se try karega", Toast.LENGTH_LONG).show()
        }
        AlertDialog.Builder(act)
            .setTitle("Doosre AI app se poochho")
            .setMessage("Ye sawal us app/service ko jayega (LoRA ke control se bahar). Agent uska jawab screen se padhega.")
            .setView(box)
            .setPositiveButton("Poochho") { _, _ ->
                val app = names.getOrNull(group.checkedRadioButtonId - 1) ?: names[0]
                val question = q.text.toString().trim()
                val svc = AgentAccessibilityService.instance
                when {
                    question.isEmpty() -> Toast.makeText(act, "Sawal likho", Toast.LENGTH_SHORT).show()
                    svc == null -> Toast.makeText(act, "Pehle Accessibility me LoRA on karo", Toast.LENGTH_LONG).show()
                    AgentLoop.isRunning() -> Toast.makeText(act, "Agent pehle se chal raha hai", Toast.LENGTH_LONG).show()
                    else -> {
                        val hintText = "Platform: $app. ${hint(app)}"
                        AgentLoop.start(svc, askGoal(app, question), hintText)
                        Toast.makeText(act, "$app se pooch raha hu. Floating window me dekho.", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
