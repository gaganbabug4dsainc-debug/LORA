package com.example.phoneagent

import java.util.concurrent.CopyOnWriteArrayList

/** Agent ka live log + chat. Kai jagah (app screen, floating panel) ek saath sun sakti hain. */
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

/** UI ke liye live status: floating icon ka rang, panel ki header, dashboard. */
object AgentState {
    @Volatile
    var status: Status = Status.IDLE

    @Volatile
    var detail: String = "Ready"

    @Volatile
    var step: Int = 0

    /** 0 = unlimited */
    @Volatile
    var limit: Int = 0

    @Volatile
    var model: String = ""

    @Volatile
    var online: Boolean = true

    @Volatile
    var goal: String = ""

    @Volatile
    var checkpoint: String = ""

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private fun fire() {
        for (l in listeners) l()
    }

    fun update(s: Status, d: String) {
        status = s
        detail = d
        fire()
    }

    fun meta(step: Int, limit: Int, goal: String, checkpoint: String) {
        this.step = step
        this.limit = limit
        this.goal = goal
        this.checkpoint = checkpoint
        fire()
    }

    fun setModel(label: String, online: Boolean) {
        model = label
        this.online = online
        fire()
    }

    fun stepText(): String = "Step $step / ${if (limit == 0) "∞" else limit.toString()}"

    fun statusLabel(): String = when (status) {
        Status.IDLE -> "Idle"
        Status.RUNNING -> "Running"
        Status.WAITING -> "Waiting"
        Status.PAUSED -> "Paused"
        Status.ERROR -> "Error"
    }

    fun modeLabel(): String = if (online) "🌐 Online" else "🟢 Local / Offline"

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
