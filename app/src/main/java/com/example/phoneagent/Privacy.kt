package com.example.phoneagent

import android.content.Context

/**
 * Data kahan jayega, ye yahan tay hota hai.
 * Config.dataMode: "local" = sirf local model | "ask" = online se pehle poochho (default) | "allow" = online allowed.
 * Offline-first: Pool me local models pehle aate hain; online tabhi jab local na ho / fail ho, aur tab bhi permission ke baad.
 */
object Privacy {
    @Volatile
    var appCtx: Context? = null

    @Volatile
    var session = false

    @Volatile
    private var onceUntil = 0L

    /** Dialog dikhane wale: foreground Activity (chat) ya floating service. Return: 0 = 30 min ke liye, 1 = is session, 2 = mana. */
    @Volatile
    var activityAsk: ((String) -> Int)? = null

    @Volatile
    var serviceAsk: ((String) -> Int)? = null

    fun localOnly(): Boolean {
        val c = appCtx ?: return false
        return Config.dataMode(c) == "local"
    }

    /** Blocking (background thread se hi bulao). */
    fun allow(c: Cand): Boolean {
        if (c.provider.isOffline) return true
        val ctx = appCtx ?: return true
        return when (Config.dataMode(ctx)) {
            "allow" -> true
            "local" -> false
            else -> {
                if (session || System.currentTimeMillis() < onceUntil) return true
                val ask = activityAsk ?: serviceAsk ?: return false
                val msg = "Is request ke liye chuna hua data online AI model (${c.provider.type}: ${c.model.take(30)}) ko bheja jayega.\n\n" +
                    "(This request requires sending selected data to the online AI model.)"
                when (ask(msg)) {
                    0 -> {
                        onceUntil = System.currentTimeMillis() + 30 * 60_000L
                        true
                    }
                    1 -> {
                        session = true
                        true
                    }
                    else -> false
                }
            }
        }
    }

    /** Header ke liye: abhi kaunsa mode active hai. */
    fun activeLabel(ctx: Context): String {
        val provs = Config.load(ctx)
        if (provs.isEmpty()) return "⚪ Model nahi"
        val pin = Config.pin(ctx)
        val p = if (pin != null) provs.firstOrNull { pin.startsWith(Pool.pinId(it, "")) }
        else provs.firstOrNull { it.isOffline } ?: provs.firstOrNull()
        if (p == null) return "⚪ Model nahi"
        if (p.isOffline) return "🟢 Local / Offline"
        return if (Config.dataMode(ctx) == "local") "⚠ Online (blocked)" else "🌐 Online"
    }
}
