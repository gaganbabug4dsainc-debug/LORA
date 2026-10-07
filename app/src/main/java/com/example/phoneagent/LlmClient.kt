package com.example.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : RuntimeException(message)

/**
 * Gemini REST API ko call karta hai.
 * - `models` comma se alag kai naam ho sakte hain ("a,b"): ek fail ho to agla try hota hai.
 * - 429/500/502/503/504 aur network error par dheere-dheere ruk ke dobara koshish karta hai.
 */
object LlmClient {
    private const val ROUNDS = 4
    private val RETRYABLE = setOf(429, 500, 502, 503, 504)

    fun ask(
        apiKey: String,
        models: String,
        system: String,
        prompt: String,
        isCancelled: () -> Boolean,
        onRetry: (String) -> Unit
    ): String {
        val list = models.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        require(list.isNotEmpty()) { "Model ka naam khali hai" }

        var last: Exception? = null
        for (round in 0 until ROUNDS) {
            for ((i, model) in list.withIndex()) {
                if (isCancelled()) throw RuntimeException("Stop kiya gaya")
                try {
                    return call(apiKey, model, system, prompt)
                } catch (e: ApiException) {
                    last = e
                    val moreModels = i < list.size - 1
                    val canRetry = e.code in RETRYABLE
                    // 404 par agla model try karo; baaki non-retryable errors turant bahar
                    if (!canRetry && !(e.code == 404 && moreModels)) throw e
                } catch (e: IOException) {
                    last = e
                }
            }
            if (round < ROUNDS - 1) {
                val wait = 4 * (round + 1)
                onRetry("Server busy/error (${last?.message?.take(60)}). ${wait}s baad dobara try ($models)...")
                var waited = 0
                while (waited < wait * 10) {
                    if (isCancelled()) throw RuntimeException("Stop kiya gaya")
                    Thread.sleep(100)
                    waited++
                }
            }
        }
        throw last ?: RuntimeException("LLM call fail")
    }

    private fun call(apiKey: String, model: String, system: String, prompt: String): String {
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
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text
                }
                throw ApiException(code, "[$model] $code: ${msg.take(140)}")
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
