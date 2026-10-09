package com.example.phoneagent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Reusable skill / workflow: ek goal + pehle kaam aaye hue steps ki list.
 * Skill chalane par agent ko ye "reference" ki tarah milti hai (UI badal sakti hai, isliye hint hai, hukm nahi).
 */
class Skill(val id: Long, var name: String, var goal: String, val steps: MutableList<String>)

object SkillStore {
    private val lock = Any()

    private fun file(c: Context) = File(c.applicationContext.filesDir, "skills.json")

    fun load(c: Context): List<Skill> = synchronized(lock) {
        val f = file(c)
        if (!f.exists()) return emptyList()
        try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Skill>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val steps = ArrayList<String>()
                val sa = o.optJSONArray("steps")
                if (sa != null) for (j in 0 until sa.length()) steps.add(sa.optString(j))
                out.add(Skill(o.optLong("id"), o.optString("name"), o.optString("goal"), steps))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun writeAll(c: Context, list: List<Skill>) {
        val arr = JSONArray()
        for (s in list) {
            arr.put(
                JSONObject().put("id", s.id).put("name", s.name).put("goal", s.goal)
                    .put("steps", JSONArray(s.steps))
            )
        }
        file(c).writeText(arr.toString())
    }

    fun add(c: Context, name: String, goal: String, steps: List<String>): Skill = synchronized(lock) {
        val list = ArrayList(load(c))
        val s = Skill(System.currentTimeMillis(), name.take(60), goal, ArrayList(steps.take(60)))
        list.add(s)
        writeAll(c, list)
        s
    }

    fun delete(c: Context, id: Long) {
        synchronized(lock) {
            writeAll(c, load(c).filter { it.id != id })
        }
    }

    /** Backup import: naam+goal same ho to dobara nahi jodta. Kitne naye jude, wo lautata hai. */
    fun merge(c: Context, incoming: List<Skill>): Int = synchronized(lock) {
        val list = ArrayList(load(c))
        var added = 0
        for (s in incoming) {
            if (list.none { it.name == s.name && it.goal == s.goal }) {
                list.add(Skill(System.currentTimeMillis() + added, s.name, s.goal, ArrayList(s.steps)))
                added++
            }
        }
        if (added > 0) writeAll(c, list)
        added
    }

    /**
     * Chalte/poore hue task se skill banata hai: user ke queue steps + wo actions jo "ok" hue.
     */
    fun stepsFromTask(t: TaskData): List<String> {
        val out = ArrayList<String>()
        val queued = TaskStore.queueSnapshot(t).filter { !it.checkpoint }
        for (q in queued) out.add("USER STEP: ${q.text}")
        val ok = TaskStore.sync {
            t.history.filter { it.contains("-> ok") }.map { it.replace(Regex("^step \\d+: "), "") }
        }
        out.addAll(ok.takeLast(40))
        return out
    }
}
