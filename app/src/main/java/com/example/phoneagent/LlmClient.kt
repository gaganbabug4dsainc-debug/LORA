package com.example.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String, val retryAfterMs: Long? = null) :
    RuntimeException(message)

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
 */
object Pool {
    private var providers: List<Provider> = emptyList()
    private val cooldown = HashMap<String, Long>()
    private val fails = HashMap<String, Int>()
    private val dead = HashSet<String>()

    @Synchronized
    fun set(list: List<Provider>) {
        providers = list
        dead.clear() // har run par galat key/model ko ek baar phir try karo
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
        val out = ArrayList<Cand>()
        for (p in snapshot) for (m in modelsFor(p)) out.add(Cand(p, m))
        return out
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

    fun ask(
        system: String,
        prompt: String,
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

            val cands = Pool.candidates()
            if (cands.isEmpty()) {
                throw RuntimeException("Koi usable provider/model nahi mila. App me provider aur model check karo. $lastErr")
            }
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
            try {
                val out = dispatch(c, system, prompt)
                Pool.ok(c)
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

    private fun dispatch(c: Cand, system: String, prompt: String): String {
        val p = c.provider
        return if (p.type == "gemini") callGemini(p.key, c.model, system, prompt)
        else callOpenAi(p, c.model, system, prompt)
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
    private fun callOpenAi(p: Provider, model: String, system: String, prompt: String): String {
        val url = URL(p.baseUrl.trimEnd('/') + "/chat/completions")
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", prompt))
            )

        val conn = url.openConnection() as HttpURLConnection
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
            conn.disconnect()
        }
    }

    private fun callGemini(apiKey: String, model: String, system: String, prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
        val body = JSONObject()
            .put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
            )
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt)))
                )
            )
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.2).put("responseMimeType", "application/json")
            )

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 60000
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
