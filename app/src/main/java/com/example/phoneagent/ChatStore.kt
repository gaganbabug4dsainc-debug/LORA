package com.example.phoneagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Ek message. role: user | assistant | error. images = phone me saved file paths. */
class Msg(
    val id: Long,
    val conv: Long,
    val role: String,
    val text: String,
    val images: List<String>,
    val files: List<String>,
    val model: String,
    val extra: String,
    val ts: Long
)

class Conv(val id: Long, val title: String, val updated: Long)

/**
 * Chat ka offline storage: sab kuch app ke private folder (SQLite + files) me.
 * Kahin upload nahi hota; sirf jab tum cloud model se baat karte ho tab wo message us provider ko jata hai.
 */
object ChatStore {
    const val DEFAULT_TITLE = "Nayi chat"

    private var helper: Helper? = null
    private val listeners = CopyOnWriteArrayList<(Long) -> Unit>()

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "chat.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE convs(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, updated INTEGER)")
            db.execSQL(
                "CREATE TABLE msgs(id INTEGER PRIMARY KEY AUTOINCREMENT, conv INTEGER, role TEXT, " +
                    "text TEXT, images TEXT, files TEXT, model TEXT, extra TEXT, ts INTEGER)"
            )
            db.execSQL("CREATE INDEX msgs_conv ON msgs(conv, id)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    }

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        if (helper == null) helper = Helper(ctx.applicationContext)
        return helper!!.writableDatabase
    }

    fun mediaDir(ctx: Context): File {
        val d = File(ctx.filesDir, "chat_media")
        if (!d.exists()) d.mkdirs()
        return d
    }

    fun addListener(l: (Long) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Long) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyChanged(conv: Long) {
        for (l in listeners) l(conv)
    }

    private fun arr(s: String?): List<String> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- conversations ----------

    fun newConv(ctx: Context, title: String = DEFAULT_TITLE): Long {
        val v = ContentValues().apply {
            put("title", title)
            put("updated", System.currentTimeMillis())
        }
        val id = db(ctx).insert("convs", null, v)
        notifyChanged(0L)
        return id
    }

    fun convs(ctx: Context): List<Conv> {
        val out = ArrayList<Conv>()
        db(ctx).rawQuery("SELECT id, title, updated FROM convs ORDER BY updated DESC", null).use { c ->
            while (c.moveToNext()) out.add(Conv(c.getLong(0), c.getString(1) ?: DEFAULT_TITLE, c.getLong(2)))
        }
        return out
    }

    fun convExists(ctx: Context, id: Long): Boolean {
        if (id <= 0L) return false
        db(ctx).rawQuery("SELECT 1 FROM convs WHERE id = ?", arrayOf(id.toString())).use { c ->
            return c.moveToFirst()
        }
    }

    fun title(ctx: Context, id: Long): String {
        db(ctx).rawQuery("SELECT title FROM convs WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) c.getString(0) ?: DEFAULT_TITLE else DEFAULT_TITLE
        }
    }

    fun rename(ctx: Context, id: Long, title: String) {
        val v = ContentValues().apply { put("title", title.take(60)) }
        db(ctx).update("convs", v, "id = ?", arrayOf(id.toString()))
        notifyChanged(0L)
    }

    /** Active chat (app aur floating panel dono yahi use karte hain, isliye kaam wahin se chalta hai). */
    @Synchronized
    fun ensureActive(ctx: Context): Long {
        val cur = Config.activeChat(ctx)
        if (convExists(ctx, cur)) return cur
        val first = convs(ctx).firstOrNull()?.id ?: newConv(ctx)
        Config.setActiveChat(ctx, first)
        return first
    }

    /** Floating "Agent Chat" ki alag conversation (normal chats se alag). */
    @Synchronized
    fun agentConv(ctx: Context): Long {
        val title = "🤖 Agent chat"
        db(ctx).rawQuery("SELECT id FROM convs WHERE title = ? LIMIT 1", arrayOf(title)).use { c ->
            if (c.moveToFirst()) return c.getLong(0)
        }
        return newConv(ctx, title)
    }

    fun deleteConv(ctx: Context, id: Long) {
        for (m in messages(ctx, id, 100000)) for (p in m.images) {
            try {
                File(p).delete()
            } catch (_: Exception) {
            }
        }
        db(ctx).delete("msgs", "conv = ?", arrayOf(id.toString()))
        db(ctx).delete("convs", "id = ?", arrayOf(id.toString()))
        notifyChanged(0L)
    }

    // ---------- messages ----------

    fun add(
        ctx: Context,
        conv: Long,
        role: String,
        text: String,
        images: List<String>,
        files: List<String>,
        model: String,
        extra: String
    ): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("conv", conv)
            put("role", role)
            put("text", SecureStore.enc(text))
            put("images", JSONArray(images).toString())
            put("files", JSONArray(files).toString())
            put("model", model)
            put("extra", extra)
            put("ts", now)
        }
        val id = db(ctx).insert("msgs", null, v)
        val cv = ContentValues().apply { put("updated", now) }
        db(ctx).update("convs", cv, "id = ?", arrayOf(conv.toString()))
        if (role == "user" && text.isNotBlank() && title(ctx, conv) == DEFAULT_TITLE) {
            rename(ctx, conv, text.replace('\n', ' ').trim().take(40))
        }
        notifyChanged(conv)
        return id
    }

    /** Purane se naye order me, aakhri `limit` messages. */
    fun messages(ctx: Context, conv: Long, limit: Int): List<Msg> {
        val out = ArrayList<Msg>()
        db(ctx).rawQuery(
            "SELECT id, conv, role, text, images, files, model, extra, ts FROM msgs " +
                "WHERE conv = ? ORDER BY id DESC LIMIT ?",
            arrayOf(conv.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Msg(
                        c.getLong(0), c.getLong(1), c.getString(2) ?: "user", SecureStore.dec(c.getString(3) ?: ""),
                        arr(c.getString(4)), arr(c.getString(5)), c.getString(6) ?: "",
                        c.getString(7) ?: "", c.getLong(8)
                    )
                )
            }
        }
        out.reverse()
        return out
    }
}
