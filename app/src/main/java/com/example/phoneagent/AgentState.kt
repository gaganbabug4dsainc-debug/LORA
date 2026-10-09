package com.example.phoneagent

import java.util.concurrent.CopyOnWriteArrayList

/** Agent ka live log. Kai jagah (app screen, floating panel) ek saath sun sakti hain. */
object AgentLog {
    val lines = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()

    fun add(s: String) {
        lines.add(s)
        while (lines.size > 200) lines.removeAt(0)
        for (l in listeners) l(s)
    }

    fun addListener(l: (String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (String) -> Unit) {
        listeners.remove(l)
    }
}

enum class Status { IDLE, RUNNING, WAITING, PAUSED, ERROR }

/** Floating icon ka rang aur panel ka status isi se aata hai. */
object AgentState {
    @Volatile
    var status: Status = Status.IDLE

    @Volatile
    var detail: String = "Ready"

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun update(s: Status, d: String) {
        status = s
        detail = d
        for (l in listeners) l()
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}

/** Task poora hone par floating window ko batane ke liye. */
object AgentEvents {
    private val listeners = CopyOnWriteArrayList<(Long, String) -> Unit>()

    fun addListener(l: (Long, String) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (Long, String) -> Unit) {
        listeners.remove(l)
    }

    fun completed(id: Long, message: String) {
        for (l in listeners) l(id, message)
    }
}
