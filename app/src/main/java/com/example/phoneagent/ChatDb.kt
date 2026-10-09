package com.example.phoneagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class ChatMsg(val id: Long, val conv: Long, val role: String, val text: String, val meta: String, val ts: Long)
class Conv(val id: Long, val title: String, val updated: Long)

/**
 * Normal Chat ki local history (SQLite, sirf is phone par). Message text Keystore se encrypted rehta hai.
 * role: user | assistant | system(notice)
 */
class ChatDb private constructor(c: Context) : SQLiteOpenHelper(c.applicationContext, "chat.db", null, 1) {

    companion object {
        @Volatile
        private var inst: ChatDb? = null

        fun get(c: Context): ChatDb =
            inst ?: synchronized(this) { inst ?: ChatDb(c).also { inst = it } }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE conv (id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, updated INTEGER)")
        db.execSQL(
            "CREATE TABLE msg (id INTEGER PRIMARY KEY AUTOINCREMENT, conv INTEGER, role TEXT, " +
                "text TEXT, meta TEXT, ts INTEGER)"
        )
        db.execSQL("CREATE INDEX msg_conv ON msg(conv)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun newConv(title: String): Long {
        val v = ContentValues()
        v.put("title", title.take(60))
        v.put("updated", System.currentTimeMillis())
        return writableDatabase.insert("conv", null, v)
    }

    fun addMsg(conv: Long, role: String, text: String, meta: String = ""): Long {
        val v = ContentValues()
        v.put("conv", conv)
        v.put("role", role)
        v.put("text", Secure.encrypt(text))
        v.put("meta", meta)
        v.put("ts", System.currentTimeMillis())
        val id = writableDatabase.insert("msg", null, v)
        val u = ContentValues()
        u.put("updated", System.currentTimeMillis())
        writableDatabase.update("conv", u, "id=?", arrayOf(conv.toString()))
        return id
    }

    fun messages(conv: Long, limit: Int = 200): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        readableDatabase.rawQuery(
            "SELECT id, conv, role, text, meta, ts FROM msg WHERE conv=? ORDER BY id DESC LIMIT ?",
            arrayOf(conv.toString(), limit.toString())
        ).use { cur ->
            while (cur.moveToNext()) {
                out.add(
                    ChatMsg(
                        cur.getLong(0), cur.getLong(1), cur.getString(2),
                        Secure.decrypt(cur.getString(3) ?: ""), cur.getString(4) ?: "", cur.getLong(5)
                    )
                )
            }
        }
        out.reverse()
        return out
    }

    fun convs(): List<Conv> {
        val out = ArrayList<Conv>()
        readableDatabase.rawQuery("SELECT id, title, updated FROM conv ORDER BY updated DESC", null)
            .use { cur ->
                while (cur.moveToNext()) out.add(Conv(cur.getLong(0), cur.getString(1) ?: "", cur.getLong(2)))
            }
        return out
    }

    fun renameConv(id: Long, title: String) {
        val v = ContentValues()
        v.put("title", title.take(60))
        writableDatabase.update("conv", v, "id=?", arrayOf(id.toString()))
    }

    fun deleteConv(id: Long) {
        writableDatabase.delete("msg", "conv=?", arrayOf(id.toString()))
        writableDatabase.delete("conv", "id=?", arrayOf(id.toString()))
    }

    fun clearAll() {
        writableDatabase.delete("msg", null, null)
        writableDatabase.delete("conv", null, null)
    }
}
