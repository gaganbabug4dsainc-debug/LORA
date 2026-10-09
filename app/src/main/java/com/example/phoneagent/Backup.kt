package com.example.phoneagent

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup export/import (JSON). Settings + Skills (+ chahe to chat text). API keys kabhi nahi jaati.
 * Passphrase do to poori file AES-GCM se encrypt hoti hai (PBKDF2). Images/files backup me nahi.
 */
object Backup {
    private val SETTING_KEYS = listOf(
        "bubble", "max_steps", "unlimited", "manual_step", "tts", "voice_lang", "panel_mode",
        "data_mode", "speak_mode", "tts_rate", "tts_volume", "tts_voice", "auto_checkpoint", "device_ctx"
    )
    private const val MAGIC = "lora-backup-enc1:"

    fun build(ctx: Context, includeChats: Boolean, pass: String?): String {
        val p = Config.prefs(ctx)
        val settings = JSONObject()
        for ((k, v) in p.all) {
            if (k in SETTING_KEYS && v != null) settings.put(k, v)
        }
        val skills = JSONArray()
        for (s in TaskStore.skills(ctx)) {
            skills.put(
                JSONObject().put("name", s.name).put("goal", s.goal).put("steps", s.steps)
                    .put("platform", s.platform).put("notes", s.notes)
            )
        }
        val root = JSONObject().put("v", 1).put("app", "lora").put("settings", settings).put("skills", skills)
        if (includeChats) {
            val chats = JSONArray()
            for (c in ChatStore.convs(ctx)) {
                val msgs = JSONArray()
                for (m in ChatStore.messages(ctx, c.id, 5000)) {
                    if (m.role == "error") continue
                    msgs.put(JSONObject().put("role", m.role).put("text", m.text).put("model", m.model))
                }
                chats.put(JSONObject().put("title", c.title).put("msgs", msgs))
            }
            root.put("chats", chats)
        }
        val plain = root.toString()
        return if (pass.isNullOrEmpty()) plain else MAGIC + encrypt(plain, pass)
    }

    fun isEncrypted(data: String) = data.trimStart().startsWith(MAGIC)

    /** Success par summary; galat passphrase/file par exception. */
    fun restore(ctx: Context, data: String, pass: String?): String {
        var json = data.trim()
        if (json.startsWith(MAGIC)) {
            if (pass.isNullOrEmpty()) throw IllegalArgumentException("Ye backup passphrase se locked hai")
            json = try {
                decrypt(json.removePrefix(MAGIC), pass)
            } catch (e: Exception) {
                throw IllegalArgumentException("Passphrase galat hai ya file kharab hai")
            }
        }
        val root = JSONObject(json)
        if (root.optString("app") != "lora") throw IllegalArgumentException("Ye LoRA ka backup nahi lagta")

        var nSet = 0
        val e = Config.prefs(ctx).edit()
        val st = root.optJSONObject("settings")
        if (st != null) {
            for (k in SETTING_KEYS) {
                if (!st.has(k)) continue
                val v = st.get(k)
                if (k == "tts_rate" || k == "tts_volume") {
                    if (v is Number) e.putFloat(k, v.toFloat()) else continue
                    nSet++
                    continue
                }
                when (v) {
                    is Boolean -> e.putBoolean(k, v)
                    is Int -> e.putInt(k, v)
                    is Long -> e.putInt(k, v.toInt())
                    is Double -> e.putFloat(k, v.toFloat())
                    is String -> e.putString(k, v)
                    else -> continue
                }
                nSet++
            }
        }
        e.apply()

        var nSkills = 0
        val have = TaskStore.skills(ctx).map { it.name + "|" + it.goal }.toHashSet()
        val sa = root.optJSONArray("skills")
        if (sa != null) {
            for (i in 0 until sa.length()) {
                val o = sa.optJSONObject(i) ?: continue
                val name = o.optString("name")
                val goal = o.optString("goal")
                if (name.isBlank() || (name + "|" + goal) in have) continue
                TaskStore.saveSkill(ctx, name, goal, o.optString("steps"), o.optString("platform"), o.optString("notes"))
                nSkills++
            }
        }

        var nChats = 0
        val ca = root.optJSONArray("chats")
        if (ca != null) {
            for (i in 0 until ca.length()) {
                val o = ca.optJSONObject(i) ?: continue
                val id = ChatStore.newConv(ctx, o.optString("title", "Imported chat"))
                val ms = o.optJSONArray("msgs") ?: continue
                for (j in 0 until ms.length()) {
                    val m = ms.optJSONObject(j) ?: continue
                    ChatStore.add(
                        ctx, id, m.optString("role", "user"), m.optString("text"),
                        emptyList(), emptyList(), m.optString("model"), ""
                    )
                }
                nChats++
            }
        }
        return "Restore ho gaya: $nSet settings, $nSkills skills, $nChats chats"
    }

    // ---------- passphrase crypto ----------

    private fun keyFrom(pass: String, salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    private fun encrypt(plain: String, pass: String): String {
        val r = SecureRandom()
        val salt = ByteArray(16).also { r.nextBytes(it) }
        val iv = ByteArray(12).also { r.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, keyFrom(pass, salt), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(salt + iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(b64: String, pass: String): String {
        val raw = Base64.decode(b64.trim(), Base64.NO_WRAP)
        val salt = raw.copyOfRange(0, 16)
        val iv = raw.copyOfRange(16, 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, keyFrom(pass, salt), GCMParameterSpec(128, iv))
        return String(c.doFinal(raw, 28, raw.size - 28), Charsets.UTF_8)
    }
}
