package com.example.phoneagent

import android.content.Context
import android.util.Base64
import java.io.File
import kotlin.concurrent.thread

/**
 * Chat ka dimaag. History ChatStore (phone) me rehti hai aur har baar naye model ko bheji jati hai,
 * isliye model beech me badal do (manual ya auto fallback) to baat wahin se jari rehti hai.
 */
object ChatEngine {

    private const val SYSTEM = """
Tum LoRA ho, user ke phone ka dost jaisa AI assistant.
- User jis bhasha me likhe (Hindi / Hinglish / English) usi me jawab do.
- Jawab chhota, saaf aur kaam ka rakho. Zarurat ho tabhi lamba likho.
- Image ya file di gayi ho to usse dekhkar jawab do.
- Agar user phone par kuch karwana chahe (app kholna, message bhejna, settings badalna), to bolo ki floating window ko "Agent" mode me karke goal likhe ya bole.
- Jo cheez tumhe nahi pata, wo mat banao; seedha bolo.
"""

    private val ATTACH = Regex(
        "use this|is (file|pdf|image|screenshot|photo)|ye (file|pdf|image|screenshot|photo)|current task|task me|continue",
        RegexOption.IGNORE_CASE
    )

    /** Chalte/adhure agent task ki halat system prompt me, taaki Agent Chat ko task dobara samjhana na pade. */
    private fun systemWithTask(ctx: Context): String {
        val tk = TaskStore.unfinished(ctx) ?: return SYSTEM
        return SYSTEM + "\nAGENT STATE (user ka chalta/adhura task; user se dobara mat poochho):\n" +
            TaskStore.summary(ctx, tk.id) +
            "Tum khud phone par action nahi chala sakte. User chhote message me pause, continue, skip, next, stop, status bol sakta hai."
    }

    @Volatile
    var busy = false
        private set

    @Volatile
    private var cancel = false

    fun stop() {
        cancel = true
    }

    /**
     * Background thread me chalta hai. onStatus aur onDone bhi usi thread se aate hain
     * (UI me runOnUiThread/post use karo). onDone(reply, error): ek hi non-null hota hai.
     */
    fun send(
        ctx: Context,
        conv: Long,
        text: String,
        imagePaths: List<String>,
        files: List<Pair<String, String>>,
        onStatus: (String) -> Unit,
        onDone: (String?, String?) -> Unit
    ) {
        if (busy) {
            onDone(null, "Pehla jawab abhi aa raha hai.")
            return
        }
        busy = true
        cancel = false
        val app = ctx.applicationContext
        thread(name = "chat-send") {
            var reply: String? = null
            var err: String? = null
            try {
                val extra = files.joinToString("\n\n") { "[File: ${it.first}]\n${it.second}" }
                ChatStore.add(app, conv, "user", text, imagePaths, files.map { it.first }, "", extra)

                // File/image ko chalte (ya adhure) agent task ke context me jodna: "use this PDF for the current task"
                var attachedTo: Task? = null
                if ((imagePaths.isNotEmpty() || files.isNotEmpty()) && ATTACH.containsMatchIn(text)) {
                    val tk = TaskStore.unfinished(app)
                    if (tk != null) {
                        for (f in files) TaskStore.addFile(app, tk.id, f.first, f.second, emptyList())
                        if (imagePaths.isNotEmpty()) TaskStore.addFile(app, tk.id, "image", "", imagePaths)
                        attachedTo = tk
                    }
                }
                val local = if (attachedTo != null && text.trim().split(Regex("\\s+")).size <= 12) {
                    "File/image Task #${attachedTo.id} ke context me jod di. Wo phone me hi hai; agent ab isse use karega."
                } else {
                    AgentCommands.handle(app, text)
                }
                if (local != null) {
                    ChatStore.add(app, conv, "assistant", local, emptyList(), emptyList(), "🟢 local", "")
                    reply = local
                } else {
                    Pool.refresh(app)
                    val turns = buildTurns(app, conv)
                    onStatus("Soch raha hu...")
                    val r = LlmClient.ask(systemWithTask(app), turns, false, { cancel }, onStatus)
                    val clean = r.replace(Regex("(?s)<think>.*?</think>"), "").trim()
                    val finalText = clean.ifEmpty { "(khali jawab aaya)" }
                    reply = finalText
                    ChatStore.add(app, conv, "assistant", finalText, emptyList(), emptyList(), LlmClient.lastUsed, "")
                }
            } catch (e: Exception) {
                val m = e.message ?: "error"
                err = m
                ChatStore.add(app, conv, "error", m, emptyList(), emptyList(), "", "")
            } finally {
                busy = false
            }
            onDone(reply, err)
        }
    }

    private fun readB64(path: String): String? = try {
        Base64.encodeToString(File(path).readBytes(), Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    private fun buildTurns(ctx: Context, conv: Long): List<Turn> {
        val all = ChatStore.messages(ctx, conv, 40).filter { it.role == "user" || it.role == "assistant" }
        var imgBudget = 2 // sirf aakhri 2 image-wale messages ki image dobara bhejte hain
        var charBudget = 30000
        val picked = ArrayList<Turn>() // naye se purane
        for (m in all.asReversed()) {
            var imgs: List<String> = emptyList()
            if (m.role == "user" && m.images.isNotEmpty() && imgBudget > 0) {
                imgs = m.images.mapNotNull { readB64(it) }
                imgBudget--
            }
            val sb = StringBuilder(m.text)
            if (m.extra.isNotBlank()) sb.append("\n\n").append(m.extra)
            if (m.role == "user" && m.images.isNotEmpty() && imgs.isEmpty()) {
                sb.append("\n[Is message ke saath image thi, ab nahi bheji ja rahi]")
            }
            val t = sb.toString()
            if (t.isBlank() && imgs.isEmpty()) continue
            if (picked.isNotEmpty() && charBudget - t.length < 0) break
            charBudget -= t.length
            picked.add(Turn(m.role, t, imgs))
        }
        picked.reverse() // ab purane se naye

        // Pehla turn user ka ho aur roles ek ke baad ek aayein (Gemini ye maangta hai)
        val out = ArrayList<Turn>()
        for (t in picked) {
            if (out.isEmpty() && t.role != "user") continue
            val last = out.lastOrNull()
            if (last != null && last.role == t.role) {
                out[out.size - 1] = Turn(t.role, last.text + "\n\n" + t.text, last.images + t.images)
            } else {
                out.add(t)
            }
        }
        return out
    }
}
