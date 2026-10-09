package com.example.phoneagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Export / import. Isme API keys, local model files, passwords KABHI nahi jaate.
 * Jaata hai: non-sensitive settings, skills (task definitions) aur (agar chaho) chat history.
 * Passphrase doge to poori file AES-256-GCM se encrypt hoti hai.
 */
object Backup {
    private val INTS = listOf("step_limit", "auto_cp", "local_ctx")
    private val STRS = listOf("data_mode", "voice_mode", "tts_lang", "tts_voice", "stt_lang")
    private val BOOLS = listOf("step_mode", "bubble")
    private val FLOATS = listOf("tts_rate", "tts_vol")

    fun build(c: Context, includeChats: Boolean, pass: String?): String {
        val p = Config.prefs(c)
        val settings = JSONObject()
        for (k in INTS) if (p.contains(k)) settings.put(k, p.getInt(k, 0))
        for (k in STRS) if (p.contains(k)) settings.put(k, p.getString(k, ""))
        for (k in BOOLS) if (p.contains(k)) settings.put(k, p.getBoolean(k, false))
        for (k in FLOATS) if (p.contains(k)) settings.put(k, p.getFloat(k, 1f).toDouble())

        val skills = JSONArray()
        for (s in SkillStore.load(c)) {
            skills.put(
                JSONObject().put("name", s.name).put("goal", s.goal).put("steps", JSONArray(s.steps))
            )
        }

        val root = JSONObject().put("v", 1).put("app", "phone-agent")
            .put("settings", settings).put("skills", skills)

        if (includeChats) {
            val db = ChatDb.get(c)
            val chats = JSONArray()
            for (cv in db.convs()) {
                val msgs = JSONArray()
                for (m in db.messages(cv.id, 5000)) {
                    msgs.put(JSONObject().put("role", m.role).put("text", m.text))
                }
                chats.put(JSONObject().put("title", cv.title).put("messages", msgs))
            }
            root.put("chats", chats)
        }
        val plain = root.toString()
        return if (pass.isNullOrEmpty()) plain else Secure.encryptWithPass(plain, pass)
    }

    /** Success par summary text, galat passphrase/file par exception. */
    fun restore(c: Context, data: String, pass: String?): String {
        var json = data
        if (Secure.isPassEncrypted(json)) {
            if (pass.isNullOrEmpty()) throw IllegalArgumentException("Ye backup encrypted hai: passphrase chahiye")
            json = Secure.decryptWithPass(json, pass)
                ?: throw IllegalArgumentException("Passphrase galat hai ya file kharab hai")
        }
        val root = JSONObject(json)
        if (root.optString("app") != "phone-agent") throw IllegalArgumentException("Ye Phone Agent ka backup nahi hai")

        val e = Config.prefs(c).edit()
        val st = root.optJSONObject("settings")
        var nSettings = 0
        if (st != null) {
            for (k in INTS) if (st.has(k)) { e.putInt(k, st.optInt(k)); nSettings++ }
            for (k in STRS) if (st.has(k)) { e.putString(k, st.optString(k)); nSettings++ }
            for (k in BOOLS) if (st.has(k)) { e.putBoolean(k, st.optBoolean(k)); nSettings++ }
            for (k in FLOATS) if (st.has(k)) { e.putFloat(k, st.optDouble(k, 1.0).toFloat()); nSettings++ }
        }
        e.apply()

        val incoming = ArrayList<Skill>()
        val sa = root.optJSONArray("skills")
        if (sa != null) {
            for (i in 0 until sa.length()) {
                val o = sa.optJSONObject(i) ?: continue
                val steps = ArrayList<String>()
                val arr = o.optJSONArray("steps")
                if (arr != null) for (j in 0 until arr.length()) steps.add(arr.optString(j))
                incoming.add(Skill(0, o.optString("name"), o.optString("goal"), steps))
            }
        }
        val nSkills = SkillStore.merge(c, incoming)

        var nChats = 0
        val ca = root.optJSONArray("chats")
        if (ca != null) {
            val db = ChatDb.get(c)
            for (i in 0 until ca.length()) {
                val o = ca.optJSONObject(i) ?: continue
                val id = db.newConv(o.optString("title", "Imported chat"))
                val ms = o.optJSONArray("messages")
                if (ms != null) {
                    for (j in 0 until ms.length()) {
                        val m = ms.optJSONObject(j) ?: continue
                        db.addMsg(id, m.optString("role", "user"), m.optString("text"))
                    }
                }
                nChats++
            }
        }
        return "Settings: $nSettings, Skills (naye): $nSkills, Chats: $nChats restore hue."
    }
}
