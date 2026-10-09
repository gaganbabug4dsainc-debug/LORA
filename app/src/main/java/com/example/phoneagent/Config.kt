package com.example.phoneagent

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ek LLM provider. type: gemini | openrouter | groq | custom | local.
 * local ke liye `key` = model file ka path (secret nahi), baaki ke liye API key.
 */
data class Provider(
    val type: String,
    val key: String,
    val baseUrl: String,
    val models: List<String>
) {
    val label: String
        get() = if (type == "local") "local:${File(key).name}" else "$type ..${key.takeLast(4)}"
}

/** App ke har entry point (service, activity) se ek baar chalta hai. */
object Boot {
    fun init(c: Context) {
        val app = c.applicationContext
        TaskStore.init(app)
        LocalLlm.appCtx = app
        VoiceOut.appCtx = app
    }
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

    /**
     * Providers (API keys) Keystore se encrypted JSON me rakhe jaate hain.
     * Purana plain-text format mile to padhkar turant encrypted me badal diya jata hai.
     */
    fun load(c: Context): List<Provider> {
        val p = prefs(c)
        val stored = p.getString("providers", null)
        if (stored == null) {
            // Bahut purane version ki ek key/model ko migrate karo
            val key = p.getString("key", "")?.trim().orEmpty()
            if (key.isEmpty()) return emptyList()
            val type = detectType(key)
            val base = presetBase(type)
            if (type == "custom" && base.isEmpty()) return emptyList()
            var model = p.getString("model", "") ?: ""
            if (model.contains(' ')) model = "" // display name tha, slug nahi
            val list = listOf(Provider(type, key, base, splitModels(model)))
            save(c, list)
            p.edit().remove("key").remove("model").apply()
            return list
        }
        val json = Secure.decrypt(stored)
        if (json.isEmpty()) return emptyList()
        val out = try {
            val arr = JSONArray(json)
            val list = ArrayList<Provider>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val ms = o.optJSONArray("models")
                val models = ArrayList<String>()
                if (ms != null) for (j in 0 until ms.length()) models.add(ms.getString(j))
                list.add(Provider(o.getString("type"), o.getString("key"), o.optString("baseUrl"), models))
            }
            list
        } catch (e: Exception) {
            return emptyList()
        }
        if (!Secure.isEncrypted(stored)) save(c, out) // plain -> encrypted migration
        return out
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
        prefs(c).edit().putString("providers", Secure.encrypt(arr.toString())).apply()
    }

    fun bubbleEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_BUBBLE, true)

    fun setBubble(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_BUBBLE, on).apply()
    }

    // ---- Agent settings ----

    /** 0 = Unlimited. Default 100 (15 ka purana hard limit hata diya). */
    fun stepLimit(c: Context): Int = prefs(c).getInt("step_limit", 100)

    fun setStepLimit(c: Context, v: Int) {
        prefs(c).edit().putInt("step_limit", v).apply()
    }

    /** local_only | ask | allow. Default: ask (online bhejne se pehle poochho). */
    fun dataMode(c: Context): String = prefs(c).getString("data_mode", "ask") ?: "ask"

    fun setDataMode(c: Context, v: String) {
        prefs(c).edit().putString("data_mode", v).apply()
    }

    /** Har N step par auto checkpoint. 0 = off. */
    fun autoCheckpoint(c: Context): Int = prefs(c).getInt("auto_cp", 10)

    fun setAutoCheckpoint(c: Context, v: Int) {
        prefs(c).edit().putInt("auto_cp", v).apply()
    }

    /** true = har step ke baad pause (Next dabane par agla step). */
    fun stepMode(c: Context): Boolean = prefs(c).getBoolean("step_mode", false)

    fun setStepMode(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean("step_mode", v).apply()
    }

    /** User ne manually jo model chuna (Cand.id). null = auto. */
    fun pinned(c: Context): String? = prefs(c).getString("pinned", null)

    fun setPinned(c: Context, id: String?) {
        val e = prefs(c).edit()
        if (id == null) e.remove("pinned") else e.putString("pinned", id)
        e.apply()
    }

    // ---- Local model ----

    /** Local model ka total token window (input + output). Model file ke hisaab se rakho. */
    fun localCtx(c: Context): Int = prefs(c).getInt("local_ctx", 1280)

    fun setLocalCtx(c: Context, v: Int) {
        prefs(c).edit().putInt("local_ctx", v).apply()
    }

    // ---- Voice ----

    /** silent | important | every */
    fun voiceMode(c: Context): String = prefs(c).getString("voice_mode", "important") ?: "important"

    fun setVoiceMode(c: Context, v: String) {
        prefs(c).edit().putString("voice_mode", v).apply()
    }

    // ---- Generic helpers (floating window geometry, voice settings...) ----

    fun geti(c: Context, key: String, def: Int): Int = prefs(c).getInt(key, def)

    fun puti(c: Context, key: String, v: Int) {
        prefs(c).edit().putInt(key, v).apply()
    }

    fun getb(c: Context, key: String, def: Boolean): Boolean = prefs(c).getBoolean(key, def)

    fun putb(c: Context, key: String, v: Boolean) {
        prefs(c).edit().putBoolean(key, v).apply()
    }

    fun gets(c: Context, key: String, def: String): String = prefs(c).getString(key, def) ?: def

    fun puts(c: Context, key: String, v: String) {
        prefs(c).edit().putString(key, v).apply()
    }

    fun getf(c: Context, key: String, def: Float): Float = prefs(c).getFloat(key, def)

    fun putf(c: Context, key: String, v: Float) {
        prefs(c).edit().putFloat(key, v).apply()
    }
}
