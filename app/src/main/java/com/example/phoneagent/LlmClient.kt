package com.example.phoneagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Gemini REST API ko call karta hai. v2 me iski jagah local model aayega. */
object LlmClient {
    fun ask(apiKey: String, model: String, system: String, prompt: String): String {
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
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) throw RuntimeException("API error $code: ${text.take(200)}")

        return JSONObject(text)
            .getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts").getJSONObject(0)
            .getString("text")
    }
}
