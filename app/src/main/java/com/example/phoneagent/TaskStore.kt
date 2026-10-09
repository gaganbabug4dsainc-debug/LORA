package com.example.phoneagent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

class TFile(val name: String, val text: String, val images: List<String>)

class Task(
    val id: Long,
    val goal: String,
    val status: String,
    val step: Int,
    val model: String,
    val instructions: List<String>,
    val files: List<TFile>,
    val checkpoint: Int,
    val updated: Long
)

class PStep(val id: Long, val text: String, val enabled: Boolean, val ord: Double)
class HStep(val n: Int, val text: String, val status: String)
class HRow(val rowId: Long, val n: Int, val text: String, val status: String, val ts: Long)

/** Reusable skill. platform = app/website ka naam (jaise ChatGPT); steps = numbered lines; {naam} = badalne wali value. */
class Skill(
    val id: Long,
    val name: String,
    val goal: String,
    val steps: String,
    val platform: String,
    val notes: String,
    val version: Int,
    val updated: Long
)

/**
 * Model-independent Agent State. Goal, steps, pending steps, checkpoints, files, instructions
 * sab yahan (phone ke SQLite me) rehte hain, kisi model ke context me nahi. Isliye model badalne par
 * naya model isi state ko padhkar wahin se continue karta hai. Har action ke baad auto-save.
 * Status: running | waiting | paused | stopped | error | interrupted | completed
 */
object TaskStore {
    private var helper: Helper? = null

    private class Helper(ctx: Context) : SQLiteOpenHelper(ctx, "tasks.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, goal TEXT, status TEXT, step INTEGER, " +
                    "model TEXT, instructions TEXT, files TEXT, checkpoint INTEGER, created INTEGER, updated INTEGER)"
            )
            db.execSQL("CREATE TABLE hist(id INTEGER PRIMARY KEY AUTOINCREMENT, task INTEGER, n INTEGER, text TEXT, status TEXT, ts INTEGER)")
            db.execSQL("CREATE TABLE pend(id INTEGER PRIMARY KEY AUTOINCREMENT, task INTEGER, ord REAL, text TEXT, enabled INTEGER, status TEXT)")
            db.execSQL("CREATE TABLE cps(id INTEGER PRIMARY KEY AUTOINCREMENT, task INTEGER, step INTEGER, label TEXT, ts INTEGER)")
            db.execSQL(
                "CREATE TABLE skills(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, goal TEXT, steps TEXT, " +
                    "platform TEXT DEFAULT '', notes TEXT DEFAULT '', version INTEGER DEFAULT 1, ts INTEGER)"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            if (o < 2) {
                db.execSQL("ALTER TABLE skills ADD COLUMN platform TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE skills ADD COLUMN notes TEXT DEFAULT ''")
                db.execSQL("ALTER TABLE skills ADD COLUMN version INTEGER DEFAULT 1")
            }
        }
    }

    @Synchronized
    private fun db(ctx: Context): SQLiteDatabase {
        if (helper == null) helper = Helper(ctx.applicationContext)
        return helper!!.writableDatabase
    }

    private fun strList(s: String?): List<String> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map { a.getString(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseFiles(s: String?): List<TFile> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            val im = o.optJSONArray("images")
            TFile(
                o.optString("name"), o.optString("text"),
                if (im == null) emptyList() else (0 until im.length()).map { i -> im.getString(i) }
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    // ---------- tasks ----------

    fun create(ctx: Context, goal: String): Long {
        val now = System.currentTimeMillis()
        val v = ContentValues().apply {
            put("goal", goal); put("status", "running"); put("step", 0); put("model", "")
            put("instructions", "[]"); put("files", "[]"); put("checkpoint", 0)
            put("created", now); put("updated", now)
        }
        return db(ctx).insert("tasks", null, v)
    }

    private fun read(c: android.database.Cursor): Task = Task(
        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getInt(3), c.getString(4) ?: "",
        strList(c.getString(5)), parseFiles(c.getString(6)), c.getInt(7), c.getLong(8)
    )

    private const val COLS = "id, goal, status, step, model, instructions, files, checkpoint, updated"

    fun get(ctx: Context, id: Long): Task? {
        if (id <= 0) return null
        db(ctx).rawQuery("SELECT $COLS FROM tasks WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    fun latest(ctx: Context): Task? {
        db(ctx).rawQuery("SELECT $COLS FROM tasks ORDER BY updated DESC LIMIT 1", null).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    fun recent(ctx: Context, n: Int): List<Task> {
        val out = ArrayList<Task>()
        db(ctx).rawQuery(
            "SELECT $COLS FROM tasks WHERE status != 'discarded' ORDER BY updated DESC LIMIT ?", arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(read(c))
        }
        return out
    }

    /** Sabse naya task jo poora nahi hua (paused/stopped/error/interrupted/running). */
    fun unfinished(ctx: Context): Task? {
        db(ctx).rawQuery(
            "SELECT $COLS FROM tasks WHERE status NOT IN ('completed','discarded') ORDER BY updated DESC LIMIT 1", null
        ).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    /** App crash/band hone ke baad bacha hua task. */
    fun interrupted(ctx: Context): Task? {
        db(ctx).rawQuery(
            "SELECT $COLS FROM tasks WHERE status = 'interrupted' ORDER BY updated DESC LIMIT 1", null
        ).use { c ->
            return if (c.moveToFirst()) read(c) else null
        }
    }

    /** Service/app start par: jo 'running' dikh raha hai par loop nahi chal raha, wo interrupted hai. */
    fun recoverOnStart(ctx: Context) {
        if (AgentLoop.isRunning()) return
        val v = ContentValues().apply { put("status", "interrupted") }
        db(ctx).update("tasks", v, "status IN ('running','waiting')", null)
    }

    private fun upd(ctx: Context, id: Long, k: String, v: Any) {
        val cv = ContentValues()
        when (v) {
            is Int -> cv.put(k, v)
            is Long -> cv.put(k, v)
            else -> cv.put(k, v.toString())
        }
        cv.put("updated", System.currentTimeMillis())
        db(ctx).update("tasks", cv, "id = ?", arrayOf(id.toString()))
    }

    fun setStatus(ctx: Context, id: Long, s: String) = upd(ctx, id, "status", s)
    fun setStep(ctx: Context, id: Long, n: Int) = upd(ctx, id, "step", n)
    fun setModel(ctx: Context, id: Long, m: String) = upd(ctx, id, "model", m)
    fun setGoal(ctx: Context, id: Long, g: String) = upd(ctx, id, "goal", g)

    fun discard(ctx: Context, id: Long) = setStatus(ctx, id, "discarded")

    /** Restart: history saaf, pending dobara pending, step 0. */
    fun reset(ctx: Context, id: Long) {
        db(ctx).delete("hist", "task = ?", arrayOf(id.toString()))
        val v = ContentValues().apply { put("status", "pending") }
        db(ctx).update("pend", v, "task = ?", arrayOf(id.toString()))
        setStep(ctx, id, 0)
        upd(ctx, id, "checkpoint", 0)
    }

    fun addInstruction(ctx: Context, id: Long, text: String) {
        val t = get(ctx, id) ?: return
        val a = JSONArray(t.instructions.takeLast(20) + text)
        upd(ctx, id, "instructions", a.toString())
    }

    fun addFile(ctx: Context, id: Long, name: String, text: String, images: List<String>) {
        val t = get(ctx, id) ?: return
        val a = JSONArray()
        for (f in t.files) {
            a.put(JSONObject().put("name", f.name).put("text", f.text).put("images", JSONArray(f.images)))
        }
        a.put(JSONObject().put("name", name).put("text", text.take(8000)).put("images", JSONArray(images)))
        upd(ctx, id, "files", a.toString())
    }

    // ---------- history ----------

    fun addDone(ctx: Context, id: Long, n: Int, text: String, status: String, cap: Int = 300) {
        val v = ContentValues().apply {
            put("task", id); put("n", n); put("text", text.take(cap)); put("status", status)
            put("ts", System.currentTimeMillis())
        }
        db(ctx).insert("hist", null, v)
        setStep(ctx, id, n)
    }

    /** Aakhri `limit` steps, purane se naye. */
    fun history(ctx: Context, id: Long, limit: Int): List<HStep> {
        val out = ArrayList<HStep>()
        db(ctx).rawQuery(
            "SELECT n, text, status FROM hist WHERE task = ? ORDER BY id DESC LIMIT ?",
            arrayOf(id.toString(), limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(HStep(c.getInt(0), c.getString(1) ?: "", c.getString(2) ?: ""))
        }
        out.reverse()
        return out
    }

    // ---------- pending (user ke jode hue aage ke steps) ----------

    fun pending(ctx: Context, id: Long): List<PStep> {
        val out = ArrayList<PStep>()
        db(ctx).rawQuery(
            "SELECT id, text, enabled, ord FROM pend WHERE task = ? AND status = 'pending' ORDER BY ord",
            arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(PStep(c.getLong(0), c.getString(1) ?: "", c.getInt(2) == 1, c.getDouble(3)))
        }
        return out
    }

    fun peekPending(ctx: Context, id: Long): PStep? = pending(ctx, id).firstOrNull { it.enabled }

    fun addPending(ctx: Context, id: Long, text: String, front: Boolean) {
        val all = pending(ctx, id)
        val ord = if (all.isEmpty()) 1.0 else if (front) all.first().ord - 1.0 else all.last().ord + 1.0
        val v = ContentValues().apply {
            put("task", id); put("ord", ord); put("text", text); put("enabled", 1); put("status", "pending")
        }
        db(ctx).insert("pend", null, v)
    }

    fun completePending(ctx: Context, pid: Long, status: String) {
        val v = ContentValues().apply { put("status", status) }
        db(ctx).update("pend", v, "id = ?", arrayOf(pid.toString()))
    }

    fun editPending(ctx: Context, pid: Long, text: String) {
        val v = ContentValues().apply { put("text", text) }
        db(ctx).update("pend", v, "id = ?", arrayOf(pid.toString()))
    }

    fun setPendingEnabled(ctx: Context, pid: Long, on: Boolean) {
        val v = ContentValues().apply { put("enabled", if (on) 1 else 0) }
        db(ctx).update("pend", v, "id = ?", arrayOf(pid.toString()))
    }

    fun deletePending(ctx: Context, pid: Long) {
        db(ctx).delete("pend", "id = ?", arrayOf(pid.toString()))
    }

    fun duplicatePending(ctx: Context, id: Long, p: PStep) {
        val v = ContentValues().apply {
            put("task", id); put("ord", p.ord + 0.0001); put("text", p.text)
            put("enabled", if (p.enabled) 1 else 0); put("status", "pending")
        }
        db(ctx).insert("pend", null, v)
    }

    fun movePending(ctx: Context, id: Long, p: PStep, up: Boolean) {
        val all = pending(ctx, id)
        val i = all.indexOfFirst { it.id == p.id }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j < 0 || j >= all.size) return
        val a = all[i]
        val b = all[j]
        val va = ContentValues().apply { put("ord", b.ord) }
        val vb = ContentValues().apply { put("ord", a.ord) }
        db(ctx).update("pend", va, "id = ?", arrayOf(a.id.toString()))
        db(ctx).update("pend", vb, "id = ?", arrayOf(b.id.toString()))
    }

    // ---------- checkpoints ----------

    fun checkpoint(ctx: Context, id: Long, step: Int, label: String) {
        val v = ContentValues().apply {
            put("task", id); put("step", step); put("label", label); put("ts", System.currentTimeMillis())
        }
        db(ctx).insert("cps", null, v)
        upd(ctx, id, "checkpoint", step)
    }

    fun checkpointCount(ctx: Context, id: Long): Int {
        db(ctx).rawQuery("SELECT COUNT(*) FROM cps WHERE task = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ---------- history tool (copy / edit / delete) ----------

    fun historyRows(ctx: Context, id: Long): List<HRow> {
        val out = ArrayList<HRow>()
        db(ctx).rawQuery(
            "SELECT id, n, text, status, ts FROM hist WHERE task = ? ORDER BY id LIMIT 1000", arrayOf(id.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(HRow(c.getLong(0), c.getInt(1), c.getString(2) ?: "", c.getString(3) ?: "", c.getLong(4)))
        }
        return out
    }

    fun updateHist(ctx: Context, rowId: Long, text: String) {
        val v = ContentValues().apply { put("text", text.take(4000)) }
        db(ctx).update("hist", v, "id = ?", arrayOf(rowId.toString()))
    }

    fun deleteHist(ctx: Context, rowId: Long) {
        db(ctx).delete("hist", "id = ?", arrayOf(rowId.toString()))
    }

    fun setInstructions(ctx: Context, id: Long, list: List<String>) {
        upd(ctx, id, "instructions", JSONArray(list).toString())
    }

    /** Naya task: wahi goal + instructions + files + pending steps. History nayi (fresh run). Status 'stopped' taaki Resume dikhe. */
    fun duplicateTask(ctx: Context, id: Long): Long {
        val t = get(ctx, id) ?: return 0L
        val nid = create(ctx, t.goal)
        setInstructions(ctx, nid, t.instructions)
        val fa = JSONArray()
        for (f in t.files) {
            fa.put(JSONObject().put("name", f.name).put("text", f.text).put("images", JSONArray(f.images)))
        }
        upd(ctx, nid, "files", fa.toString())
        for (p in pending(ctx, id)) {
            val v = ContentValues().apply {
                put("task", nid); put("ord", p.ord); put("text", p.text)
                put("enabled", if (p.enabled) 1 else 0); put("status", "pending")
            }
            db(ctx).insert("pend", null, v)
        }
        setStatus(ctx, nid, "stopped")
        return nid
    }

    /** Task ko hamesha ke liye hatao (history, pending, checkpoints sab). */
    fun purgeTask(ctx: Context, id: Long) {
        val a = arrayOf(id.toString())
        db(ctx).delete("hist", "task = ?", a)
        db(ctx).delete("pend", "task = ?", a)
        db(ctx).delete("cps", "task = ?", a)
        db(ctx).delete("tasks", "id = ?", a)
    }

    /** Copy ke liye poora task text me. */
    fun taskText(ctx: Context, id: Long): String {
        val t = get(ctx, id) ?: return ""
        val sb = StringBuilder()
        sb.append("LoRA Task #${t.id}\nGoal: ${t.goal}\nStatus: ${t.status}, Step: ${t.step}, Checkpoint: ${t.checkpoint}\n")
        if (t.instructions.isNotEmpty()) {
            sb.append("Instructions:\n")
            for (i in t.instructions) sb.append("- ").append(i).append('\n')
        }
        sb.append("Steps:\n")
        for (h in historyRows(ctx, id)) sb.append("${h.n}. [${h.status}] ${h.text}\n")
        val p = pending(ctx, id)
        if (p.isNotEmpty()) {
            sb.append("Pending:\n")
            for (x in p) sb.append("- ").append(x.text).append(if (x.enabled) "" else " (disabled)").append('\n')
        }
        return sb.toString()
    }

    // ---------- skills ----------

    private const val SCOLS = "id, name, goal, steps, IFNULL(platform,''), IFNULL(notes,''), IFNULL(version,1), ts"

    private fun readSkill(c: android.database.Cursor) = Skill(
        c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
        c.getString(4) ?: "", c.getString(5) ?: "", c.getInt(6), c.getLong(7)
    )

    fun saveSkill(
        ctx: Context, name: String, goal: String, steps: String,
        platform: String = "", notes: String = ""
    ): Long {
        val v = ContentValues().apply {
            put("name", name.take(60)); put("goal", goal); put("steps", steps.take(6000))
            put("platform", platform.take(40)); put("notes", notes.take(2000)); put("version", 1)
            put("ts", System.currentTimeMillis())
        }
        return db(ctx).insert("skills", null, v)
    }

    /** Update: version +1. */
    fun updateSkill(ctx: Context, id: Long, name: String, goal: String, steps: String, platform: String, notes: String) {
        db(ctx).execSQL(
            "UPDATE skills SET name = ?, goal = ?, steps = ?, platform = ?, notes = ?, " +
                "version = IFNULL(version, 1) + 1, ts = ? WHERE id = ?",
            arrayOf<Any>(name.take(60), goal, steps.take(6000), platform.take(40), notes.take(2000), System.currentTimeMillis(), id)
        )
    }

    fun getSkill(ctx: Context, id: Long): Skill? {
        db(ctx).rawQuery("SELECT $SCOLS FROM skills WHERE id = ?", arrayOf(id.toString())).use { c ->
            return if (c.moveToFirst()) readSkill(c) else null
        }
    }

    fun skills(ctx: Context): List<Skill> {
        val out = ArrayList<Skill>()
        db(ctx).rawQuery("SELECT $SCOLS FROM skills ORDER BY ts DESC", null).use { c ->
            while (c.moveToNext()) out.add(readSkill(c))
        }
        return out
    }

    fun deleteSkill(ctx: Context, id: Long) {
        db(ctx).delete("skills", "id = ?", arrayOf(id.toString()))
    }

    /** Chat/Agent Chat ko task ki poori halat batane ke liye (user ko dobara samjhana na pade). */
    fun summary(ctx: Context, id: Long): String {
        val t = get(ctx, id) ?: return ""
        val sb = StringBuilder()
        sb.append("Task #${t.id}: ${t.goal}\n")
        sb.append("Status: ${t.status}, current step: ${t.step}, last checkpoint: step ${t.checkpoint}, model: ${ModelPicker.shortLabel(ctx)}\n")
        val h = history(ctx, id, 6)
        if (h.isNotEmpty()) sb.append("Recent steps:\n").append(h.joinToString("\n") { "  ${it.n}. ${it.text} [${it.status}]" }).append('\n')
        val p = pending(ctx, id)
        if (p.isNotEmpty()) sb.append("Pending user steps:\n").append(p.joinToString("\n") { "  - ${it.text}${if (it.enabled) "" else " (disabled)"}" }).append('\n')
        if (t.instructions.isNotEmpty()) sb.append("User instructions: ").append(t.instructions.takeLast(4).joinToString(" | ")).append('\n')
        if (t.files.isNotEmpty()) sb.append("Attached files: ").append(t.files.joinToString(", ") { it.name }).append('\n')
        return sb.toString()
    }
}
