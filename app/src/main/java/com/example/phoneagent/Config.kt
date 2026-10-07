package com.example.phoneagent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Ek LLM provider (key + models). type: gemini | openrouter | groq | custom */
data class Provider(
    val type: String,
    val key: String,
    val baseUrl: String,
    val models: List<String>
) {
    val label: String get() = "$type ..${key.takeLast(4)}"
}

object Config {
    const val KEY_BUBBLE = "bubble"

    fun prefs(c: Context): SharedPreferences =
        c.getSharedPreferences("agent", Context.MODE_PRIVATE)

    fun detectType(key: String): String = when {
        key.startsWith("sk-or-") -> "openrouter"
        key.startsWith("AIza") -> "gemini"
        key.startsWith("gsk_") -> "groq"
        else -> "custom"
    }

    fun presetBase(type: String): String = when (type) {
        "openrouter" -> "https://openrouter.ai/api/v1"
        "groq" -> "https://api.groq.com/openai/v1"
        else -> ""
    }

    fun splitModels(s: String): List<String> =
        s.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun load(c: Context): List<Provider> {
        val p = prefs(c)
        val s = p.getString("providers", null)
        if (s == null) {
            // Purane version ki ek key/model ko migrate karo
            val key = p.getString("key", "")?.trim().orEmpty()
            if (key.isEmpty()) return emptyList()
            val type = detectType(key)
            val base = presetBase(type)
            if (type == "custom" && base.isEmpty()) return emptyList()
            var model = p.getString("model", "") ?: ""
            if (model.contains(' ')) model = "" // display name tha, slug nahi
            val list = listOf(Provider(type, key, base, splitModels(model)))
            save(c, list)
            return list
        }
        return try {
            val arr = JSONArray(s)
            val out = ArrayList<Provider>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ms = o.optJSONArray("models")
                val models = ArrayList<String>()
                if (ms != null) for (j in 0 until ms.length()) models.add(ms.getString(j))
                out.add(Provider(o.getString("type"), o.getString("key"), o.optString("baseUrl"), models))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(c: Context, list: List<Provider>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(
                JSONObject()
                    .put("type", p.type)
                    .put("key", p.key)
                    .put("baseUrl", p.baseUrl)
                    .put("models", JSONArray(p.models))
            )
        }
        prefs(c).edit().putString("providers", arr.toString()).apply()
    }

    fun bubbleEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_BUBBLE, true)

    fun setBubble(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_BUBBLE, on).apply()
    }
}
