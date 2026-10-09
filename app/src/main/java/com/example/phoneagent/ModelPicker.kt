package com.example.phoneagent

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Model Manager. App, chat aur floating window teeno yahi use karte hain.
 * Har model ke saath: Local/Online, Text/Vision, context size, available ya nahi, aur current.
 * Switch karne se task/chat ka state nahi badalta; naya model usi state se continue karta hai.
 * (Vision/context sirf provider ki list ya naam se pata chalta hai; "—" matlab pata nahi / nahi hai.
 *  Awaaz (voice) model nahi hoti: sunna/bolna phone ka apna engine karta hai.)
 */
object ModelPicker {
    private val main = Handler(Looper.getMainLooper())

    fun shortLabel(ctx: Context): String {
        val pin = Config.pin(ctx) ?: return "Auto"
        val m = pin.split('|', limit = 3).getOrNull(2) ?: return "Auto"
        return m.substringAfterLast('/').take(22)
    }

    private fun themed(ctx: Context) =
        ContextThemeWrapper(ctx, android.R.style.Theme_Material_Light_Dialog_Alert)

    private fun show(b: AlertDialog.Builder, overlay: Boolean) {
        val d = b.create()
        if (overlay) d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
    }

    private fun fmtCtx(n: Int): String = when {
        n <= 0 -> "—"
        n >= 1_000_000 -> "${n / 1_000_000}M"
        n >= 1000 -> "${n / 1000}k"
        else -> n.toString()
    }

    /** Task chal raha/adhura ho to pehle confirm: state safe rahegi. */
    private fun applyPin(ctx: Context, id: String?, overlay: Boolean, onChanged: () -> Unit) {
        val tk = if (AgentLoop.isRunning()) TaskStore.get(ctx, AgentLoop.taskId) else TaskStore.unfinished(ctx)
        val doIt = {
            Config.setPin(ctx, id)
            onChanged()
            Toast.makeText(ctx, if (id == null) "Auto mode" else "Model: ${shortLabel(ctx)}", Toast.LENGTH_SHORT).show()
        }
        if (tk == null) {
            doIt()
            return
        }
        val stepNow = if (AgentLoop.isRunning()) AgentLoop.step else tk.step
        show(
            AlertDialog.Builder(themed(ctx))
                .setTitle("Model badlein?")
                .setMessage("Model badalne se Task #${tk.id} ki state safe rahegi. Naya model Step ${stepNow + 1} se continue karega, Step 1 se nahi.")
                .setPositiveButton("Switch") { _, _ -> doIt() }
                .setNegativeButton("Cancel", null),
            overlay
        )
    }

    /** overlay=true jab floating window (service) se khol rahe ho. */
    fun show(ctx: Context, overlay: Boolean, onChanged: () -> Unit) {
        val provs = Config.load(ctx)
        if (provs.isEmpty()) {
            Toast.makeText(ctx, "Pehle koi provider ya local model jodo", Toast.LENGTH_LONG).show()
            return
        }
        Pool.refresh(ctx)
        Toast.makeText(ctx, "Models ki list la raha hu...", Toast.LENGTH_SHORT).show()
        thread(name = "model-list") {
            val labels = ArrayList<String>()
            val ids = ArrayList<String?>()
            val cur = Config.pin(ctx)
            labels.add((if (cur == null) "● " else "○ ") + "Auto\n   Local pehle, phir online (permission ke baad). Limit/error par agla model apne aap.")
            ids.add(null)
            for (p in provs) {
                val local = p.isOffline
                val listed = try {
                    if (p.type == "device") emptyList() else ModelLister.list(p)
                } catch (e: Exception) {
                    emptyList()
                }
                val use = (p.models + listed).distinct()
                for (m in use.take(200)) {
                    val id = Pool.pinId(p, m)
                    val meta = ModelLister.meta(p, m)
                    val avail = if (Pool.ready(Cand(p, m))) "Available" else "⚠ limit/busy"
                    labels.add(
                        (if (cur == id) "● " else "○ ") + (if (local) "🟢 " else "🌐 ") + m +
                            "\n   ${if (local) "Offline" else "Online"} · Text ✓ · Vision ${if (meta.vision) "✓" else "—"}" +
                            " · Context ${fmtCtx(meta.ctx)} · $avail"
                    )
                    ids.add(id)
                }
            }
            labels.add("✎ Model ka naam khud likho")
            ids.add("__custom__")
            main.post {
                val b = AlertDialog.Builder(themed(ctx))
                    .setTitle("Models (abhi: ${shortLabel(ctx)})")
                    .setItems(labels.toTypedArray()) { _, i ->
                        val id = ids[i]
                        if (id == "__custom__") typeModel(ctx, provs, overlay, onChanged)
                        else applyPin(ctx, id, overlay, onChanged)
                    }
                    .setNegativeButton("Band", null)
                show(b, overlay)
            }
        }
    }

    private fun typeModel(ctx: Context, provs: List<Provider>, overlay: Boolean, onChanged: () -> Unit) {
        fun askName(p: Provider) {
            val et = EditText(themed(ctx)).apply {
                hint = "model ka slug (jaise llama3.2:3b)"
                setSingleLine()
            }
            show(
                AlertDialog.Builder(themed(ctx))
                    .setTitle("${p.type} ka model")
                    .setView(et)
                    .setPositiveButton("Use karo") { _, _ ->
                        val m = et.text.toString().trim()
                        if (m.isNotEmpty()) applyPin(ctx, Pool.pinId(p, m), overlay, onChanged)
                    }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        }
        if (provs.size == 1) {
            askName(provs[0])
        } else {
            show(
                AlertDialog.Builder(themed(ctx))
                    .setTitle("Kis provider ka?")
                    .setItems(provs.map { it.label }.toTypedArray()) { _, i -> askName(provs[i]) }
                    .setNegativeButton("Cancel", null),
                overlay
            )
        }
    }
}
