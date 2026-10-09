package com.example.phoneagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String, val retryAfterMs: Long? = null) :
    RuntimeException(message)

/** Ek baatcheet ka turn. role: "user" ya "assistant". images = base64 JPEG list. */
class Turn(val role: String, val text: String, val images: List<String> = emptyList())

/** Ek (provider, model) jodi jise call kiya ja sakta hai. */
class Cand(val provider: Provider, val model: String) {
    val label: String get() = "${provider.type}:${model.take(30)}"
}

/** OpenRouter ke free models ki list (model box khali ho to ye "auto pool" bante hain). */
object OpenRouterModels {
    @Volatile
    private var cache: List<String> = emptyList()

    @Volatile
    private var at = 0L

    fun free(): List<String> {
        if (cache.isNotEmpty() && System.currentTimeMillis() - at < 30 * 60_000L) return cache
        val conn = URL("https://openrouter.ai/api/v1/models").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000
            conn.readTimeout = 20000
            if (conn.responseCode != 200) return cache
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val data = JSONObject(text).getJSONArray("data")

            class M(val id: String, val structured: Boolean, val ctx: Int)

            val found = ArrayList<M>()
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: continue
                val id = o.optString("id")
                if (id.isEmpty()) continue
                val pr = o.optJSONObject("pricing") ?: continue
                val isFree = pr.optString("prompt").toDoubleOrNull() == 0.0 &&
                    pr.optString("completion").toDoubleOrNull() == 0.0
                if (!isFree) continue
                val outs = o.optJSONObject("architecture")?.optJSONArray("output_modalities")
                if (outs != null && (0 until outs.length()).none { outs.optString(it) == "text" }) continue
                val sp = o.optJSONArray("supported_parameters")
                val structured = sp != null && (0 until sp.length()).any {
                    sp.optString(it) == "response_format" || sp.optString(it) == "structured_outputs"
                }
                found.add(M(id, structured, o.optInt("context_length", 0)))
            }
            val ids = found
                .sortedWith(compareByDescending<M> { it.structured }.thenByDescending { it.ctx })
                .take(8)
                .map { it.id }
            if (ids.isNotEmpty()) {
                cache = ids
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
 * `pinned` set ho to sirf wahi model use hota hai (manual switch).
 */
object Pool {
    private var providers: List<Provider> = emptyList()
    private val cooldown = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val dead = HashSet<String>()

    @Volatile
    var pinned: String? = null

    @Synchronized
    fun set(list: List<Provider>) {
        providers = list
        dead.clear() // har run par galat key/model ko ek baar phir try karo
    }

    /** Saved providers + manual pin ko prefs se load karo. */
    fun refresh(ctx: Context) {
        Privacy.appCtx = ctx.applicationContext
        set(Config.load(ctx))
        pinned = Config.pin(ctx)
    }

    private fun pid(p: Provider) = "${p.type}|${p.key.takeLast(8)}"
    private fun cid(c: Cand) = pid(c.provider) + "|" + c.model

    fun pinId(p: Provider, model: String) = pid(p) + "|" + model

    private fun modelsFor(p: Provider): List<String> {
        if (p.models.isNotEmpty()) return p.models
        return when (p.type) {
            "gemini" -> listOf("gemini-3.8-flash")
            "device" -> listOf(p.key.substringAfterLast('/'))
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
        val pin = pinned
        if (pin != null) {
            val parts = pin.split('|', limit = 3)
            if (parts.size == 3) {
                val p = snapshot.firstOrNull { pid(it) == parts[0] + "|" + parts[1] }
                if (p != null) {
                    if (Privacy.localOnly() && !p.isOffline) return emptyList()
                    return listOf(Cand(p, parts[2]))
                }
            }
        }
        val out = ArrayList<Cand>()
        for (p in snapshot) for (m in modelsFor(p)) out.add(Cand(p, m))
        // Offline-first: local models pehle, online baad me (sortedBy stable hai, baaki order wahi rehta hai)
        val sorted = out.sortedBy { if (it.provider.type == "device") 0 else if (it.provider.type == "local") 1 else 2 }
        return if (Privacy.localOnly()) sorted.filter { it.provider.isOffline } else sorted
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

    /** Aakhri successful call kis provider:model se hui. */
    @Volatile
    var lastUsed: String = ""

    /** Agent ke liye: ek prompt, JSON jawab. */
    fun ask(
        system: String,
        prompt: String,
        isCancelled: () -> Boolean,
        onStatus: (String) -> Unit
    ): String = ask(system, listOf(Turn("user", prompt)), true, isCancelled, onStatus)

    fun ask(
        system: String,
        turns: List<Turn>,
        json: Boolean,
        isCancelled: () -> Boolean,
        onStatus: (String) -> Unit
    ): String {
        val started = System.currentTimeMillis()
        var attempts = 0
        var netFails = 0
        var lastErr = ""

        while (true) {
            if (isCancelled()) throw RuntimeException("Stop kiya gaya")
            attempts++
            if (attempts > 30 || System.currentTimeMillis() - started > 6 * 60_000L) {
                throw RuntimeException("LLM se jawab nahi mila. Aakhri error: $lastErr")
            }

            val pinnedNow = Pool.pinned != null
            val cands = Pool.candidates()
            if (cands.isEmpty()) {
                throw RuntimeException(
                    if (Privacy.localOnly()) "Data Processing = Local Only hai par koi local model nahi mila. Local model jodo ya setting badlo."
                    else "Koi usable provider/model nahi mila. Provider aur model check karo. $lastErr"
                )
            }
            val ready = cands.filter { Pool.ready(it) }

            if (ready.isEmpty()) {
                val waitMs = Pool.soonest(cands)
                    ?: throw RuntimeException(
                        (if (pinnedNow) "Tumhara chuna hua model kaam nahi kar raha (Model badlo ya Auto karo). "
                        else "Sab providers band hain (key galat?). ") + lastErr
                    )
                if (waitMs > 90_000L) {
                    throw RuntimeException(
                        (if (pinnedNow) "Chuna hua model abhi limit par hai. " else "Sab providers ki limit khatam. ") +
                            "Lagbhag ${human(waitMs)} baad dobara try karo ya Model badlo. $lastErr"
                    )
                }
                onStatus("Busy. ${waitMs / 1000}s ruk raha hu...")
                sleepCancellable(waitMs + 500, isCancelled)
                continue
            }

            val c = ready.first()
            if (!Privacy.allow(c)) {
                throw RuntimeException("Online processing ki permission nahi mili. Local model use karo ya Data Processing setting badlo.")
            }
            try {
                val out = dispatch(c, system, turns, json)
                Pool.ok(c)
                lastUsed = c.label
                return out
            } catch (e: ApiException) {
                lastErr = e.message ?: ""
                Pool.fail(c, e)
                val next = Pool.candidates().firstOrNull { Pool.ready(it) }
                onStatus(
                    "${c.label} fail (${e.code}). " +
                        (if (next != null) "Ab ${next.label} par switch." else "Sab busy, wait.")
                )
            } catch (e: IOException) {
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

    private fun human(ms: Long): String =
        if (ms >= 3600_000L) "${ms / 3600_000L} ghante" else "${maxOf(1L, ms / 60_000L)} minute"

    private fun sleepCancellable(ms: Long, isCancelled: () -> Boolean) {
        var waited = 0L
        while (waited < ms) {
            if (isCancelled()) throw RuntimeException("Stop kiya gaya")
            Thread.sleep(100)
            waited += 100
        }
    }

    private fun dispatch(c: Cand, system: String, turns: List<Turn>, json: Boolean): String {
        val p = c.provider
        return when (p.type) {
            "gemini" -> callGemini(p.key, c.model, system, turns, json)
            "device" -> DeviceLlm.generate(p.key, system, flatten(turns))
            else -> callOpenAi(p, c.model, system, turns, json)
        }
    }

    /** On-device model ek hi text leta hai: baatcheet ko "User:/Assistant:" me jodo. */
    private fun flatten(turns: List<Turn>): String {
        if (turns.size == 1) return turns[0].text
        val sb = StringBuilder()
        for (t in turns) sb.append(if (t.role == "assistant") "Assistant: " else "User: ").append(t.text).append('\n')
        sb.append("Assistant:")
        return sb.toString()
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

    /** OpenAI-compatible (OpenRouter, Groq, custom). */
    private fun callOpenAi(p: Provider, model: String, system: String, turns: List<Turn>, json: Boolean): String {
        val url = URL(p.baseUrl.trimEnd('/') + "/chat/completions")
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (t in turns) {
            val role = if (t.role == "assistant") "assistant" else "user"
            if (t.images.isEmpty()) {
                messages.put(JSONObject().put("role", role).put("content", t.text))
            } else {
                val parts = JSONArray()
                parts.put(JSONObject().put("type", "text").put("text", if (t.text.isEmpty()) " " else t.text))
                for (img in t.images) {
                    parts.put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$img"))
                    )
                }
                messages.put(JSONObject().put("role", role).put("content", parts))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", if (json) 0.2 else 0.7)
            .put("messages", messages)

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = if (p.type == "local") 240000 else 90000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (p.key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer ${p.key}")
            if (p.type == "openrouter") conn.setRequestProperty("X-Title", "LoRA")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                throw ApiException(code, "[$model] $code: ${errMsg(text).take(140)}", retryAfter(conn))
            }

            val j = JSONObject(text)
            if (j.has("error")) {
                val err = j.optJSONObject("error")
                throw ApiException(
                    err?.optInt("code", 500) ?: 500,
                    "[$model] ${err?.optString("message", "error")?.take(140)}"
                )
            }
            return j.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } finally {
            conn.disconnect()
        }
    }

    private fun callGemini(apiKey: String, model: String, system: String, turns: List<Turn>, json: Boolean): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val contents = JSONArray()
        for (t in turns) {
            val parts = JSONArray()
            if (t.text.isNotEmpty()) parts.put(JSONObject().put("text", t.text))
            for (img in t.images) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", "image/jpeg").put("data", img)
                    )
                )
            }
            if (parts.length() == 0) parts.put(JSONObject().put("text", " "))
            contents.put(
                JSONObject()
                    .put("role", if (t.role == "assistant") "model" else "user")
                    .put("parts", parts)
            )
        }
        val cfg = JSONObject().put("temperature", if (json) 0.2 else 0.7)
        if (json) cfg.put("responseMimeType", "application/json")
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put("contents", contents)
            .put("generationConfig", cfg)

        val conn = url.openConnection() as HttpURLConnection
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
            conn.disconnect()
        }
    }
}
