package com.example.phoneagent

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String, val retryAfterMs: Long? = null) :
    RuntimeException(message)

/** Image ya PDF jo model ko bhejni hai. */
class Attachment(val name: String, val mime: String, val bytes: ByteArray) {
    val kind: String
        get() = when {
            mime.startsWith("image/") -> "image"
            mime == "application/pdf" -> "pdf"
            else -> "other"
        }
}

/** Ek (provider, model) jodi jise call kiya ja sakta hai. */
class Cand(val provider: Provider, val model: String) {
    val label: String
        get() = if (provider.type == "local") "local:${model.take(30)}" else "${provider.type}:${model.take(30)}"

    /** Stable id: manual model switch (pin) isi se yaad rakha jata hai. */
    val id: String get() = "${provider.type}|${provider.key.takeLast(8)}|$model"

    val online: Boolean get() = provider.type != "local"

    /** Sirf wahi capability True jo sach me pata hai. */
    val vision: Boolean
        get() = when (provider.type) {
            "gemini" -> true
            "local" -> false
            "openrouter" -> OpenRouterModels.hasVision(model)
            else -> false
        }

    val contextSize: Int
        get() = when (provider.type) {
            "local" -> LocalLlm.appCtx?.let { Config.localCtx(it) } ?: 0
            "openrouter" -> OpenRouterModels.ctxOf(model)
            else -> 0
        }

    fun accepts(a: Attachment): Boolean = when (a.kind) {
        "image" -> vision
        "pdf" -> provider.type == "gemini"
        else -> false
    }

    fun caps(): String {
        val sb = StringBuilder(if (online) "🌐 Online" else "🟢 Offline")
        sb.append(" · Text ✓")
        if (vision) sb.append(" · Vision ✓")
        if (provider.type == "gemini") sb.append(" · PDF ✓")
        val c = contextSize
        if (c > 0) sb.append(" · Ctx ").append(if (c >= 1000) "${c / 1000}k" else c.toString())
        return sb.toString()
    }
}

/** LLM ka jawab + kis model ne diya. */
class LlmResult(val text: String, val label: String, val online: Boolean)

/** User ne Stop dabaya: error nahi, safe exit. */
class StopException : RuntimeException("Stop kiya gaya")

/** OpenRouter ke free models ki list (model box khali ho to ye "auto pool" bante hain). */
object OpenRouterModels {
    @Volatile
    private var cache: List<String> = emptyList()

    @Volatile
    private var at = 0L

    @Volatile
    private var visionIds: Set<String> = emptySet()

    @Volatile
    private var ctxMap: Map<String, Int> = emptyMap()

    fun hasVision(id: String): Boolean = id in visionIds
    fun ctxOf(id: String): Int = ctxMap[id] ?: 0

    fun free(): List<String> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - at < 30 * 60_000L) return cache
        val conn = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            if (conn.responseCode != 200) return cache
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(text).getJSONArray("data")

            class M(val id: String, val structured: Boolean, val ctx: Int, val vision: Boolean)

            val found = ArrayList<M>()
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val pr = o.optJSONObject("pricing") ?: continue
                val isFree = pr.optString("prompt").toDoubleOrNull() == 0.0 &&
                    pr.optString("completion").toDoubleOrNull() == 0.0
                if (!isFree) continue
                val arch = o.optJSONObject("architecture")
                val outs = arch?.optJSONArray("output_modalities")
                if (outs != null && (0 until outs.length()).none { outs.optString(it) == "text" }) continue
                val ins = arch?.optJSONArray("input_modalities")
                val vision = ins != null && (0 until ins.length()).any { ins.optString(it) == "image" }
                val sp = o.optJSONArray("supported_parameters")
                val structured = sp != null && (0 until sp.length()).any {
                    sp.optString(it) == "response_format" || sp.optString(it) == "structured_outputs"
                }
                found.add(M(id, structured, o.optInt("context_length", 0), vision))
            }
            val top = found
                .sortedWith(compareByDescending<M> { it.structured }.thenByDescending { it.ctx })
                .take(8)
            if (top.isNotEmpty()) {
                cache = top.map { it.id }
                visionIds = top.filter { it.vision }.map { it.id }.toSet()
                ctxMap = top.associate { it.id to it.ctx }
                at = System.currentTimeMillis()
            }
            return cache
        } finally {
            conn.disconnect()
        }
    }
}

/**
 * Sab providers/models ki list + cooldown. Koi fail ho to us par cooldown lagta hai
 * aur agla available candidate automatically use hota hai.
 *
 * Offline-first: local model hamesha online models se pehle aate hain (jab tak user ne
 * koi model pin na kiya ho). Data mode "local_only" me online models list me aate hi nahi.
 */
object Pool {
    private var providers: List<Provider> = emptyList()
    private val cooldown = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val dead = HashSet<String>()

    /** User ka manually chuna model (Cand.id). Ye sabse pehle try hota hai; fail ho to auto fallback. */
    @Volatile
    var pinned: String? = null

    /** local_only | ask | allow */
    @Volatile
    var mode: String = "ask"

    /** "ask" mode me user ne online processing allow ki. */
    @Volatile
    var approved: Boolean = false

    /** "ask" mode me user ne online processing deny ki: ab sirf local models. */
    @Volatile
    var denied: Boolean = false

    @Synchronized
    fun set(list: List<Provider>) {
        providers = list
        dead.clear() // har run par galat key/model ko ek baar phir try karo
    }

    fun begin(mode: String, approved: Boolean) {
        this.mode = mode
        this.approved = approved
        this.denied = false
    }

    private fun pid(p: Provider) = "${p.type}|${p.key.takeLast(8)}"
    private fun cid(c: Cand) = pid(c.provider) + "|" + c.model

    private fun modelsFor(p: Provider): List<String> {
        if (p.models.isNotEmpty()) return p.models
        return when (p.type) {
            "gemini" -> listOf("gemini-3.8-flash")
            "openrouter" -> try {
                OpenRouterModels.free()
            } catch (e: Exception) {
                emptyList()
            }
            else -> emptyList()
        }
    }

    fun candidates(): List<Cand> {
        val snapshot = synchronized(this) { providers }
        val allowOnline = mode != "local_only" && !denied
        val out = ArrayList<Cand>()
        for (p in snapshot) {
            if (p.type != "local" && !allowOnline) continue
            for (m in modelsFor(p)) out.add(Cand(p, m))
        }
        val sorted = ArrayList(out.sortedBy { if (it.online) 1 else 0 })
        val pin = pinned
        if (pin != null) {
            val i = sorted.indexOfFirst { it.id == pin }
            if (i > 0) sorted.add(0, sorted.removeAt(i))
        }
        return sorted
    }

    @Synchronized
    fun ready(c: Cand): Boolean {
        val now = System.currentTimeMillis()
        val pk = pid(c.provider)
        val id = cid(c)
        if (pk in dead || id in dead) return false
        return now >= (cooldown[pk] ?: 0L) && now >= (cooldown[id] ?: 0L)
    }

    /** Sabse jaldi kitne ms baad koi candidate ready hoga. null = sab permanently band. */
    @Synchronized
    fun soonest(cands: List<Cand>): Long? {
        val now = System.currentTimeMillis()
        var best: Long? = null
        for (c in cands) {
            val pk = pid(c.provider)
            val id = cid(c)
            if (pk in dead || id in dead) continue
            val until = maxOf(cooldown[pk] ?: 0L, cooldown[id] ?: 0L)
            val wait = maxOf(0L, until - now)
            if (best == null || wait < best) best = wait
        }
        return best
    }

    @Synchronized
    fun ok(c: Cand) {
        fails.remove(cid(c))
    }

    @Synchronized
    fun cool(c: Cand, ms: Long) {
        cooldown[cid(c)] = System.currentTimeMillis() + ms
    }

    @Synchronized
    fun fail(c: Cand, e: ApiException) {
        val pk = pid(c.provider)
        val id = cid(c)
        val n = (fails[id] ?: 0) + 1
        fails[id] = n
        val msg = (e.message ?: "").lowercase()
        val now = System.currentTimeMillis()
        when {
            e.code == 401 || e.code == 403 || (e.code == 400 && msg.contains("api key")) ->
                dead.add(pk)
            e.code == 404 -> dead.add(id)
            e.code == 402 -> cooldown[pk] = now + 30 * 60_000L
            e.code == 429 -> {
                val base = e.retryAfterMs ?: when {
                    msg.contains("per day") || msg.contains("daily") || msg.contains("perday") ->
                        6 * 3600_000L
                    n == 1 -> 60_000L
                    n == 2 -> 5 * 60_000L
                    else -> 30 * 60_000L
                }
                val until = now + base.coerceIn(5_000L, 6 * 3600_000L)
                // OpenRouter ki limit poore account par lagti hai, sirf model par nahi
                if (c.provider.type == "openrouter") cooldown[pk] = until else cooldown[id] = until
            }
            e.code in 500..599 || e.code == 408 ->
                cooldown[id] = now + minOf(15_000L * n, 120_000L)
            else -> cooldown[id] = now + 10 * 60_000L
        }
    }
}

object LlmClient {

    /** Chal rahi HTTP call. Stop dabane par ise tod dete hain taaki agent turant ruke. */
    @Volatile
    private var activeConn: HttpURLConnection? = null

    fun abort() {
        try {
            activeConn?.disconnect()
        } catch (_: Exception) {
        }
    }

    /**
     * @param compact local (chhote) model ke liye chhota system+prompt banata hai.
     * @param attachments image/PDF; sirf unhi models par jaati hain jo inhe samajhte hain (online).
     * @param jsonMode true = model se sirf JSON maango (agent actions). Chat ke liye false.
     * @param gate "ask" mode me pehli baar online model use hone se pehle user se poochta hai.
     */
    fun ask(
        system: String,
        prompt: String,
        isCancelled: () -> Boolean,
        onStatus: (String) -> Unit,
        compact: (() -> Pair<String, String>)? = null,
        attachments: List<Attachment> = emptyList(),
        jsonMode: Boolean = true,
        gate: ((String) -> Boolean)? = null
    ): LlmResult {
        val started = System.currentTimeMillis()
        var attempts = 0
        var netFails = 0
        var lastErr = ""

        while (true) {
            if (isCancelled()) throw StopException()
            attempts++
            if (attempts > 30 || System.currentTimeMillis() - started > 6 * 60_000L) {
                throw RuntimeException("LLM se jawab nahi mila. Aakhri error: $lastErr")
            }

            val cands = Pool.candidates().filter { c -> attachments.all { c.accepts(it) } }
            if (cands.isEmpty()) throw RuntimeException(noCandidateMessage(attachments.isNotEmpty(), lastErr))
            val ready = cands.filter { Pool.ready(it) }

            if (ready.isEmpty()) {
                val waitMs = Pool.soonest(cands)
                    ?: throw RuntimeException("Sab providers band hain (key galat?). $lastErr")
                if (waitMs > 90_000L) {
                    throw RuntimeException(
                        "Sab providers ki limit khatam. Lagbhag ${human(waitMs)} baad dobara try karo. $lastErr"
                    )
                }
                onStatus("Sab providers busy. ${waitMs / 1000}s ruk raha hu...")
                sleepCancellable(waitMs + 500, isCancelled)
                continue
            }

            val c = ready.first()

            // Online model se pehle permission (Local Only me online candidates aate hi nahi)
            if (c.online && Pool.mode == "ask" && !Pool.approved) {
                val ok = gate?.invoke(c.provider.type) ?: false
                if (ok) {
                    Pool.approved = true
                } else {
                    Pool.denied = true
                    onStatus("Online processing deny hui.")
                    continue
                }
            }

            try {
                val out = dispatch(c, system, prompt, compact, attachments, jsonMode)
                Pool.ok(c)
                return LlmResult(out, c.label, c.online)
            } catch (e: ApiException) {
                lastErr = e.message ?: ""
                Pool.fail(c, e)
                val next = Pool.candidates().firstOrNull { Pool.ready(it) }
                onStatus(
                    "${c.label} fail (${e.code}). " +
                        (if (next != null) "Ab ${next.label} par switch." else "Sab busy, wait.")
                )
            } catch (e: org.json.JSONException) {
                lastErr = "[${c.model}] response samajh nahi aaya: ${e.message?.take(80)}"
                Pool.fail(c, ApiException(502, lastErr))
                val next = Pool.candidates().firstOrNull { Pool.ready(it) }
                onStatus(
                    "${c.label} ka response kharab. " +
                        (if (next != null) "Ab ${next.label} par switch." else "Sab busy, wait.")
                )
            } catch (e: IOException) {
                if (isCancelled()) throw StopException()
                lastErr = e.message ?: "network error"
                netFails++
                if (netFails >= 3) {
                    throw RuntimeException("Internet ya server se connect nahi ho pa raha: $lastErr")
                }
                Pool.cool(c, 5_000L)
                onStatus("Network error, dobara koshish...")
            }
        }
    }

    private fun noCandidateMessage(hasAttachments: Boolean, lastErr: String): String {
        val base = when {
            Pool.denied ->
                "Online processing ki permission nahi mili, aur is kaam ke liye koi local model available nahi hai."
            hasAttachments && Pool.mode == "local_only" ->
                "Local Only mode me ye image/PDF process nahi ho sakti (local model sirf text samajhta hai). " +
                    "Data Processing badlo."
            hasAttachments ->
                "Is image/PDF ke liye koi vision-capable model nahi mila (Gemini ya vision wala OpenRouter model jodo)."
            Pool.mode == "local_only" ->
                "Local Only mode me koi local model nahi hai. More me Local model jodo, ya Data Processing badlo."
            else -> "Koi usable provider/model nahi mila. App me provider aur model check karo."
        }
        return "$base $lastErr".trim()
    }

    private fun human(ms: Long): String =
        if (ms >= 3600_000L) "${ms / 3600_000L} ghante" else "${maxOf(1L, ms / 60_000L)} minute"

    private fun sleepCancellable(ms: Long, isCancelled: () -> Boolean) {
        var waited = 0L
        while (waited < ms) {
            if (isCancelled()) throw StopException()
            Thread.sleep(100)
            waited += 100
        }
    }

    private fun dispatch(
        c: Cand,
        system: String,
        prompt: String,
        compact: (() -> Pair<String, String>)?,
        atts: List<Attachment>,
        jsonMode: Boolean
    ): String {
        val p = c.provider
        return when (p.type) {
            "local" -> {
                val sp = compact?.invoke() ?: (system to prompt)
                LocalLlm.generate(p.key, sp.first, sp.second)
            }
            "gemini" -> callGemini(p.key, c.model, system, prompt, atts, jsonMode)
            else -> callOpenAi(p, c.model, system, prompt, atts)
        }
    }

    private fun errMsg(text: String): String = try {
        val e = JSONObject(text).opt("error")
        when (e) {
            is JSONObject -> e.optString("message", text)
            is String -> e
            else -> text
        }
    } catch (ex: Exception) {
        text
    }

    private fun retryAfter(conn: HttpURLConnection): Long? =
        conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.let { it * 1000L }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    /** OpenAI-compatible (OpenRouter, Groq, custom). */
    private fun callOpenAi(
        p: Provider,
        model: String,
        system: String,
        prompt: String,
        atts: List<Attachment>
    ): String {
        val url = URL(p.baseUrl.trimEnd('/') + "/chat/completions")
        val images = atts.filter { it.kind == "image" }
        val userContent: Any = if (images.isEmpty()) {
            prompt
        } else {
            val arr = JSONArray()
            arr.put(JSONObject().put("type", "text").put("text", prompt))
            for (a in images) {
                arr.put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:${a.mime};base64,${b64(a.bytes)}")
                    )
                )
            }
            arr
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", userContent))
            )

        val conn = url.openConnection() as HttpURLConnection
        activeConn = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${p.key}")
            if (p.type == "openrouter") conn.setRequestProperty("X-Title", "Phone Agent")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(code, "[$model] $code: ${errMsg(text).take(140)}", retryAfter(conn))
            }

            val json = JSONObject(text)
            if (json.has("error")) {
                val err = json.optJSONObject("error")
                throw ApiException(
                    err?.optInt("code", 500) ?: 500,
                    "[$model] ${err?.optString("message", "error")?.take(140)}"
                )
            }
            return json.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            if (activeConn === conn) activeConn = null
            conn.disconnect()
        }
    }

    private fun callGemini(
        apiKey: String,
        model: String,
        system: String,
        prompt: String,
        atts: List<Attachment>,
        jsonMode: Boolean
    ): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val parts = JSONArray()
        for (a in atts) {
            if (a.kind == "other") continue
            parts.put(
                JSONObject().put(
                    "inlineData",
                    JSONObject().put("mimeType", a.mime).put("data", b64(a.bytes))
                )
            )
        }
        parts.put(JSONObject().put("text", prompt))

        val gen = JSONObject().put("temperature", 0.2)
        if (jsonMode) gen.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)

        val conn = url.openConnection() as HttpURLConnection
        activeConn = conn
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 90000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(code, "[$model] $code: ${errMsg(text).take(140)}", retryAfter(conn))
            }

            return JSONObject(text)
                .getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts").getJSONObject(0)
                .getString("text")
        } finally {
            if (activeConn === conn) activeConn = null
            conn.disconnect()
        }
    }
}
