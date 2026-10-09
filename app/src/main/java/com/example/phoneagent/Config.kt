package com.example.phoneagent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Ek LLM provider (key + models). type: gemini | openrouter | groq | local | custom */
data class Provider(
    val type: String,
    val key: String,
    val baseUrl: String,
    val models: List<String>
) {
    /** local (Ollama server) ya device (phone ke andar chalne wala model): internet ki zarurat nahi. */
    val isOffline: Boolean get() = type == "local" || type == "device"

    val label: String
        get() = when {
            type == "device" -> "device:" + key.substringAfterLast('/').take(24)
            key.isEmpty() -> type
            else -> "$type ..${key.takeLast(4)}"
        }
}

object Config {
    const val KEY_BUBBLE = "bubble"
    private const val KEY_PIN = "pin"
    private const val KEY_MAX_STEPS = "max_steps"
    private const val KEY_UNLIMITED = "unlimited"
    private const val KEY_MANUAL = "manual_step"
    private const val KEY_ACTIVE_CHAT = "active_chat"
    private const val KEY_TTS = "tts"
    private const val KEY_LANG = "voice_lang"
    private const val KEY_MODE = "panel_mode"
    private const val KEY_DATA = "data_mode"
    private const val KEY_SPEAK = "speak_mode"
    private const val KEY_RATE = "tts_rate"
    private const val KEY_VOL = "tts_volume"
    private const val KEY_VOICE = "tts_voice"
    private const val KEY_AUTOCP = "auto_checkpoint"

    val LANGS = listOf("hi-IN", "en-IN", "en-US")
    val STEP_CHOICES = listOf(10, 20, 50, 100, 200)

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
                out.add(Provider(o.getString("type"), SecureStore.dec(o.getString("key")), o.optString("baseUrl"), models))
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
                    .put("key", SecureStore.enc(p.key))
                    .put("baseUrl", p.baseUrl)
                    .put("models", JSONArray(p.models))
            )
        }
        prefs(c).edit().putString("providers", arr.toString()).apply()
    }

    // ---------- floating icon ----------
    fun bubbleEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_BUBBLE, true)

    fun setBubble(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_BUBBLE, on).apply()
    }

    // ---------- manual model switch (agent + chat dono) ----------
    /** "type|keySuffix|model" ya null (= Auto). */
    fun pin(c: Context): String? = prefs(c).getString(KEY_PIN, null)?.takeIf { it.isNotEmpty() }

    fun setPin(c: Context, id: String?) {
        val e = prefs(c).edit()
        if (id == null) e.remove(KEY_PIN) else e.putString(KEY_PIN, id)
        e.apply()
        AgentLoop.beforeModelSwitch(c) // checkpoint, phir naya model wahin se chalega
        Pool.pinned = id // chalte agent/chat par turant lagu
        AgentLoop.afterModelSwitch(c)
    }

    // ---------- step control ----------
    fun maxSteps(c: Context): Int = prefs(c).getInt(KEY_MAX_STEPS, 50).coerceIn(1, 100000)

    fun setMaxSteps(c: Context, n: Int) {
        prefs(c).edit().putInt(KEY_MAX_STEPS, n.coerceIn(1, 100000)).apply()
    }

    fun unlimited(c: Context): Boolean = prefs(c).getBoolean(KEY_UNLIMITED, true)

    fun setUnlimited(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_UNLIMITED, on).apply()
    }

    fun manualStep(c: Context): Boolean = prefs(c).getBoolean(KEY_MANUAL, false)

    fun setManualStep(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_MANUAL, on).apply()
    }

    // ---------- chat / voice ----------
    fun activeChat(c: Context): Long = prefs(c).getLong(KEY_ACTIVE_CHAT, 0L)

    fun setActiveChat(c: Context, id: Long) {
        prefs(c).edit().putLong(KEY_ACTIVE_CHAT, id).apply()
    }

    fun ttsOn(c: Context): Boolean = prefs(c).getBoolean(KEY_TTS, false)

    fun setTts(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_TTS, on).apply()
    }

    fun voiceLang(c: Context): String = prefs(c).getString(KEY_LANG, LANGS[0]) ?: LANGS[0]

    fun setVoiceLang(c: Context, l: String) {
        prefs(c).edit().putString(KEY_LANG, l).apply()
    }

    /** Floating panel ka mode: "agent" (phone chalao) ya "chat" (baat karo). */
    fun panelMode(c: Context): String = prefs(c).getString(KEY_MODE, "agent") ?: "agent"

    fun setPanelMode(c: Context, m: String) {
        prefs(c).edit().putString(KEY_MODE, m).apply()
    }

    // ---------- data processing: "local" | "ask" | "allow" ----------
    fun dataMode(c: Context): String = prefs(c).getString(KEY_DATA, "ask") ?: "ask"

    fun setDataMode(c: Context, m: String) {
        prefs(c).edit().putString(KEY_DATA, m).apply()
    }

    // ---------- speech: "silent" | "important" | "every" ----------
    fun speakMode(c: Context): String = prefs(c).getString(KEY_SPEAK, "important") ?: "important"

    fun setSpeakMode(c: Context, m: String) {
        prefs(c).edit().putString(KEY_SPEAK, m).apply()
    }

    fun ttsRate(c: Context): Float = prefs(c).getFloat(KEY_RATE, 1.0f)

    fun setTtsRate(c: Context, r: Float) {
        prefs(c).edit().putFloat(KEY_RATE, r).apply()
    }

    fun ttsVolume(c: Context): Float = prefs(c).getFloat(KEY_VOL, 1.0f)

    fun setTtsVolume(c: Context, v: Float) {
        prefs(c).edit().putFloat(KEY_VOL, v).apply()
    }

    fun ttsVoice(c: Context): String? = prefs(c).getString(KEY_VOICE, null)?.takeIf { it.isNotEmpty() }

    fun setTtsVoice(c: Context, v: String?) {
        val e = prefs(c).edit()
        if (v == null) e.remove(KEY_VOICE) else e.putString(KEY_VOICE, v)
        e.apply()
    }

    // ---------- on-device model ----------
    fun deviceCtx(c: Context): Int = prefs(c).getInt("device_ctx", 1280)

    fun setDeviceCtx(c: Context, n: Int) {
        prefs(c).edit().putInt("device_ctx", n.coerceIn(512, 8192)).apply()
    }

    /** Har N step par auto checkpoint (0 = band). */
    fun autoCheckpoint(c: Context): Int = prefs(c).getInt(KEY_AUTOCP, 10)

    fun setAutoCheckpoint(c: Context, n: Int) {
        prefs(c).edit().putInt(KEY_AUTOCP, n.coerceAtLeast(0)).apply()
    }
}
