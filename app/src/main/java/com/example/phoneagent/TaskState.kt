package com.example.phoneagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** User ka add kiya hua pending step. checkpoint=true ho to wo sirf ek checkpoint marker hai. */
class QueuedStep(
    val id: Long,
    var text: String,
    var enabled: Boolean = true,
    var checkpoint: Boolean = false
)

class Checkpoint(val step: Int, val note: String, val time: Long)

/**
 * Model-independent task state. Task kisi ek model ki conversation me nahi, yaha rehta hai,
 * isliye model badalne par bhi koi bhi model isse padhkar wahin se aage badh sakta hai.
 */
class TaskData(val id: Long, var goal: String) {
    var step = 0

    /** RUNNING | PAUSED | STOPPED | DONE | ERROR */
    var status = "RUNNING"
    var lastModel = ""
    var onlineOk = false
    var updatedAt = System.currentTimeMillis()
    val history = ArrayList<String>()
    val notes = ArrayList<String>()
    val errors = ArrayList<String>()
    val queue = ArrayList<QueuedStep>()
    val checkpoints = ArrayList<Checkpoint>()

    /** Task ko di gayi files: naam -> nikala hua text (sirf local storage me). */
    val files = LinkedHashMap<String, String>()

    /** Agle LLM call ke saath jaane wali images (filesDir/task_files ke paths). */
    val images = ArrayList<String>()

    /** Agent ke yaad rakhe hue variables ("remember" action). */
    val vars = LinkedHashMap<String, String>()

    /** Is task ko kis saved skill se shuru kiya gaya. */
    var skill = ""
    val skillSteps = ArrayList<String>()
}

/** Task ko disk par (filesDir/task.json) har meaningful action ke baad save karta hai. */
object TaskStore {
    private val lock = Any()
    private var dir: File? = null
    private var nextQ = System.currentTimeMillis()

    @Volatile
    var current: TaskData? = null

    fun init(c: Context) {
        synchronized(lock) {
            if (dir == null) {
                dir = c.applicationContext.filesDir
                current = readLocked()
            }
        }
    }

    fun <T> sync(f: () -> T): T = synchronized(lock) { f() }

    fun create(goal: String): TaskData = synchronized(lock) {
        val t = TaskData(System.currentTimeMillis(), goal)
        current = t
        writeLocked()
        t
    }

    /** Wo task jo poora nahi hua (crash, stop, error ya pause ke baad). */
    fun unfinished(): TaskData? = current?.takeIf { it.status != "DONE" }

    fun clear() {
        synchronized(lock) {
            current = null
            writeLocked()
        }
    }

    fun save() {
        synchronized(lock) {
            current?.updatedAt = System.currentTimeMillis()
            writeLocked()
        }
    }

    // ---- history / notes / errors / checkpoints ----

    fun addHistory(t: TaskData, line: String) {
        synchronized(lock) {
            t.history.add(line)
            while (t.history.size > 400) t.history.removeAt(0)
        }
    }

    fun addNote(t: TaskData, note: String) {
        synchronized(lock) {
            t.notes.add(note)
            while (t.notes.size > 50) t.notes.removeAt(0)
            writeLocked()
        }
    }

    fun addError(t: TaskData, e: String) {
        synchronized(lock) {
            t.errors.add(e.take(200))
            while (t.errors.size > 30) t.errors.removeAt(0)
        }
    }

    fun addCheckpoint(t: TaskData, note: String) {
        synchronized(lock) {
            addCheckpointLocked(t, note)
            writeLocked()
        }
    }

    private fun addCheckpointLocked(t: TaskData, note: String) {
        t.checkpoints.add(Checkpoint(t.step, note, System.currentTimeMillis()))
        while (t.checkpoints.size > 30) t.checkpoints.removeAt(0)
        AgentState.checkpoint = "Step ${t.step} ($note)"
    }

    fun lastCheckpointText(t: TaskData): String = synchronized(lock) {
        val c = t.checkpoints.lastOrNull()
        if (c == null) "(koi nahi)" else "Step ${c.step} (${c.note})"
    }

    // ---- files / images / variables / skill ----

    fun attachText(t: TaskData, name: String, text: String) {
        synchronized(lock) {
            t.files[name] = text.take(20_000)
            while (t.files.size > 5) t.files.remove(t.files.keys.first())
            writeLocked()
        }
    }

    fun attachImage(t: TaskData, path: String) {
        synchronized(lock) {
            t.images.add(path)
            while (t.images.size > 2) t.images.removeAt(0)
            writeLocked()
        }
    }

    /** Pending images nikalta hai (ek hi baar bhejni hain). */
    fun takeImages(t: TaskData): List<String> = synchronized(lock) {
        val out = ArrayList(t.images)
        t.images.clear()
        if (out.isNotEmpty()) writeLocked()
        out
    }

    fun setVar(t: TaskData, key: String, value: String) {
        synchronized(lock) {
            t.vars[key.take(40)] = value.take(300)
            while (t.vars.size > 30) t.vars.remove(t.vars.keys.first())
            writeLocked()
        }
    }

    fun filesSnapshot(t: TaskData): Map<String, String> = synchronized(lock) { LinkedHashMap(t.files) }
    fun varsSnapshot(t: TaskData): Map<String, String> = synchronized(lock) { LinkedHashMap(t.vars) }

    fun setSkill(t: TaskData, name: String, steps: List<String>) {
        synchronized(lock) {
            t.skill = name
            t.skillSteps.clear()
            t.skillSteps.addAll(steps.take(60))
            writeLocked()
        }
    }

    // ---- queue (user ke add kiye hue steps) ----

    fun queueSnapshot(t: TaskData): List<QueuedStep> = synchronized(lock) {
        t.queue.map { QueuedStep(it.id, it.text, it.enabled, it.checkpoint) }
    }

    fun addStep(t: TaskData, text: String, front: Boolean) {
        synchronized(lock) {
            val q = QueuedStep(++nextQ, text)
            if (front) t.queue.add(0, q) else t.queue.add(q)
            writeLocked()
        }
    }

    fun editStep(t: TaskData, id: Long, text: String) {
        synchronized(lock) {
            t.queue.firstOrNull { it.id == id }?.text = text
            writeLocked()
        }
    }

    fun deleteStep(t: TaskData, id: Long) {
        synchronized(lock) {
            t.queue.removeAll { it.id == id }
            writeLocked()
        }
    }

    fun moveStep(t: TaskData, id: Long, delta: Int) {
        synchronized(lock) {
            val i = t.queue.indexOfFirst { it.id == id }
            val j = i + delta
            if (i >= 0 && j >= 0 && j < t.queue.size) {
                val tmp = t.queue[i]
                t.queue[i] = t.queue[j]
                t.queue[j] = tmp
                writeLocked()
            }
        }
    }

    fun duplicateStep(t: TaskData, id: Long) {
        synchronized(lock) {
            val i = t.queue.indexOfFirst { it.id == id }
            if (i >= 0) {
                val s = t.queue[i]
                t.queue.add(i + 1, QueuedStep(++nextQ, s.text, s.enabled, s.checkpoint))
                writeLocked()
            }
        }
    }

    fun toggleStep(t: TaskData, id: Long) {
        synchronized(lock) {
            t.queue.firstOrNull { it.id == id }?.let { it.enabled = !it.enabled }
            writeLocked()
        }
    }

    fun toCheckpoint(t: TaskData, id: Long) {
        synchronized(lock) {
            t.queue.firstOrNull { it.id == id }?.checkpoint = true
            writeLocked()
        }
    }

    /** Queue ke aage ke checkpoint markers ko checkpoint bana kar hata deta hai. */
    fun consumeCheckpointMarkers(t: TaskData) {
        synchronized(lock) {
            var changed = false
            while (true) {
                val head = t.queue.firstOrNull { it.enabled } ?: break
                if (!head.checkpoint) break
                addCheckpointLocked(t, "marker: ${head.text.take(30)}")
                t.queue.remove(head)
                changed = true
            }
            if (changed) writeLocked()
        }
    }

    /** Queue ka pehla enabled (non-checkpoint) step poora ho gaya: hata do. */
    fun popFirstStep(t: TaskData) {
        synchronized(lock) {
            val head = t.queue.firstOrNull { it.enabled && !it.checkpoint }
            if (head != null) {
                t.queue.remove(head)
                writeLocked()
            }
        }
    }

    // ---- disk ----

    private fun writeLocked() {
        val d = dir ?: return
        val f = File(d, "task.json")
        val t = current
        if (t == null) {
            f.delete()
            return
        }
        try {
            val tmp = File(d, "task.json.tmp")
            tmp.writeText(toJson(t).toString())
            if (!tmp.renameTo(f)) {
                f.delete()
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
        }
    }

    private fun readLocked(): TaskData? {
        val d = dir ?: return null
        val f = File(d, "task.json")
        if (!f.exists()) return null
        return try {
            fromJson(JSONObject(f.readText()))
        } catch (_: Exception) {
            null
        }
    }

    private fun strings(a: JSONArray?, into: MutableList<String>) {
        if (a == null) return
        for (i in 0 until a.length()) into.add(a.optString(i))
    }

    private fun toJson(t: TaskData): JSONObject {
        val q = JSONArray()
        for (s in t.queue) {
            q.put(
                JSONObject().put("id", s.id).put("text", s.text)
                    .put("enabled", s.enabled).put("cp", s.checkpoint)
            )
        }
        val cps = JSONArray()
        for (c in t.checkpoints) {
            cps.put(JSONObject().put("step", c.step).put("note", c.note).put("time", c.time))
        }
        return JSONObject()
            .put("id", t.id)
            .put("goal", t.goal)
            .put("step", t.step)
            .put("status", t.status)
            .put("lastModel", t.lastModel)
            .put("onlineOk", t.onlineOk)
            .put("updatedAt", t.updatedAt)
            .put("history", JSONArray(t.history))
            .put("notes", JSONArray(t.notes))
            .put("errors", JSONArray(t.errors))
            .put("queue", q)
            .put("checkpoints", cps)
            .put("files", JSONObject(t.files as Map<*, *>))
            .put("images", JSONArray(t.images))
            .put("vars", JSONObject(t.vars as Map<*, *>))
            .put("skill", t.skill)
            .put("skillSteps", JSONArray(t.skillSteps))
    }

    private fun fromJson(o: JSONObject): TaskData {
        val t = TaskData(o.optLong("id", System.currentTimeMillis()), o.optString("goal"))
        t.step = o.optInt("step", 0)
        t.status = o.optString("status", "STOPPED")
        t.lastModel = o.optString("lastModel", "")
        t.onlineOk = o.optBoolean("onlineOk", false)
        t.updatedAt = o.optLong("updatedAt", 0L)
        strings(o.optJSONArray("history"), t.history)
        strings(o.optJSONArray("notes"), t.notes)
        strings(o.optJSONArray("errors"), t.errors)
        val q = o.optJSONArray("queue")
        if (q != null) {
            for (i in 0 until q.length()) {
                val s = q.optJSONObject(i) ?: continue
                t.queue.add(
                    QueuedStep(
                        s.optLong("id", ++nextQ), s.optString("text"),
                        s.optBoolean("enabled", true), s.optBoolean("cp", false)
                    )
                )
            }
        }
        val c = o.optJSONArray("checkpoints")
        if (c != null) {
            for (i in 0 until c.length()) {
                val s = c.optJSONObject(i) ?: continue
                t.checkpoints.add(Checkpoint(s.optInt("step"), s.optString("note"), s.optLong("time")))
            }
        }
        o.optJSONObject("files")?.let { f ->
            for (k in f.keys()) t.files[k] = f.optString(k)
        }
        strings(o.optJSONArray("images"), t.images)
        o.optJSONObject("vars")?.let { v ->
            for (k in v.keys()) t.vars[k] = v.optString(k)
        }
        t.skill = o.optString("skill", "")
        strings(o.optJSONArray("skillSteps"), t.skillSteps)
        return t
    }
}
