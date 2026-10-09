package com.example.phoneagent

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Model ki jaankari. vision/ctx sirf tab bharosemand hai jab provider ne bataya ho; warna naam se andaza. */
class ModelMeta(val vision: Boolean, val ctx: Int)

/** Kisi provider ke saare available models ki list + capabilities (Model Manager ke liye). */
object ModelLister {
    private val cache = HashMap<String, Pair<Long, List<String>>>()
    private val metas = HashMap<String, ModelMeta>()

    private fun key(p: Provider) = p.type + "|" + p.key.takeLast(8) + "|" + p.baseUrl

    fun meta(p: Provider, id: String): ModelMeta {
        if (p.type == "device") return ModelMeta(false, Privacy.appCtx?.let { Config.deviceCtx(it) } ?: 0)
        val hit = synchronized(metas) { metas[key(p) + "|" + id] }
        return hit ?: ModelMeta(guessVision(id, p.type), 0)
    }

    fun guessVision(id: String, type: String): Boolean {
        val s = id.lowercase()
        if (type == "gemini") return s.contains("gemini") && !s.contains("embedding")
        return Regex(
            "llava|vision|[-:_]vl|vl[-:_]|minicpm-v|moondream|gemma3|gemma-3|pixtral|bakllava|gpt-4o|gpt-4\\.1|claude|gemini|qwen2\\.5vl|llama-4|internvl|molmo"
        ).containsMatchIn(s)
    }

    fun list(p: Provider): List<String> {
        val k = key(p)
        synchronized(cache) {
            val hit = cache[k]
            if (hit != null && System.currentTimeMillis() - hit.first < 10 * 60_000L) return hit.second
        }
        val out = if (p.type == "gemini") listGemini(p) else listOpenAi(p)
        synchronized(cache) { cache[k] = Pair(System.currentTimeMillis(), out) }
        return out
    }

    private fun getJson(url: String, headers: Map<String, String>): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15000
            conn.readTimeout = 30000
            for ((k, v) in headers) conn.setRequestProperty(k, v)
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun listGemini(p: Provider): List<String> {
        val j = getJson(
            "https://generativelanguage.googleapis.com/v1beta/models?pageSize=200",
            mapOf("x-goog-api-key" to p.key)
        )
        val arr = j.optJSONArray("models") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val methods = o.optJSONArray("supportedGenerationMethods")
            val ok = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            if (!ok) continue
            val id = o.optString("name").removePrefix("models/")
            out.add(id)
            synchronized(metas) {
                metas[key(p) + "|" + id] = ModelMeta(guessVision(id, "gemini"), o.optInt("inputTokenLimit", 0))
            }
        }
        return out.sorted()
    }

    private fun listOpenAi(p: Provider): List<String> {
        val j = getJson(
            p.baseUrl.trimEnd('/') + "/models",
            if (p.key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${p.key}")
        )
        val arr = j.optJSONArray("data") ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isEmpty()) continue
            out.add(id)
            val mods = o.optJSONObject("architecture")?.optJSONArray("input_modalities")
            val vision = if (mods != null) (0 until mods.length()).any { mods.optString(it) == "image" }
            else guessVision(id, p.type)
            val ctx = o.optInt("context_length", o.optInt("max_model_len", 0))
            synchronized(metas) { metas[key(p) + "|" + id] = ModelMeta(vision, ctx) }
        }
        return out.sorted()
    }
}
