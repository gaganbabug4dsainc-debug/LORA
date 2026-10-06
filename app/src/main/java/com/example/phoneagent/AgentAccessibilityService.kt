package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Haath + aankhein: screen padhta hai aur tap/type/scroll karta hai. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private var stopButton: View? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AgentLoop.stop()
        return super.onUnbind(intent)
    }

    // ---------- Aankhein: screen -> compact text ----------

    fun readScreen(): String {
        val root = rootInActiveWindow ?: return "(screen unavailable)"
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("App: ${root.packageName}\n")
        walk(root, list, sb)
        nodes = list
        return sb.toString()
    }

    private fun walk(n: AccessibilityNodeInfo, list: MutableList<AccessibilityNodeInfo>, sb: StringBuilder) {
        if (list.size >= 120) return
        if (n.isVisibleToUser) {
            val text = if (n.isPassword) "(password field)" else (n.text?.toString()?.take(60) ?: "")
            val desc = n.contentDescription?.toString()?.take(60) ?: ""
            val interesting = n.isClickable || n.isEditable || n.isScrollable ||
                text.isNotBlank() || desc.isNotBlank()
            if (interesting) {
                val idx = list.size
                list.add(n)
                sb.append("[$idx] ${n.className?.toString()?.substringAfterLast('.')}")
                if (text.isNotBlank()) sb.append(" \"$text\"")
                if (desc.isNotBlank()) sb.append(" desc=\"$desc\"")
                if (n.isClickable) sb.append(" clickable")
                if (n.isEditable) sb.append(" editable")
                if (n.isScrollable) sb.append(" scrollable")
                sb.append('\n')
            }
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, list, sb)
        }
    }

    fun nodeLabel(i: Int): String {
        val n = nodes.getOrNull(i) ?: return ""
        return "${n.text ?: ""} ${n.contentDescription ?: ""}".trim()
    }

    fun isPasswordNode(i: Int): Boolean = nodes.getOrNull(i)?.isPassword == true

    // ---------- Haath: actions ----------

    fun click(i: Int): Boolean {
        val n = nodes.getOrNull(i) ?: return false
        var cur: AccessibilityNodeInfo? = n
        while (cur != null) {
            if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = cur.parent
        }
        val r = Rect()
        n.getBoundsInScreen(r)
        return tap(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun type(i: Int, text: String): Boolean {
        val n = nodes.getOrNull(i) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun scroll(direction: String): Boolean {
        val down = direction != "up"
        val target = nodes.firstOrNull { it.isScrollable }
        if (target != null) {
            val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            if (target.performAction(action)) return true
        }
        val w = resources.displayMetrics.widthPixels.toFloat()
        val h = resources.displayMetrics.heightPixels.toFloat()
        return if (down) swipe(w / 2, h * 0.7f, w / 2, h * 0.3f) else swipe(w / 2, h * 0.3f, w / 2, h * 0.7f)
    }

    fun back(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openApp(name: String): Boolean {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
            ?: return false
        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        return dispatchGesture(g, null, null)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        return dispatchGesture(g, null, null)
    }

    // ---------- Safety: confirmation dialog + STOP button ----------

    /** Risky action se pehle user se poochta hai. 60 sec me jawab na aaye to Deny. */
    fun confirm(message: String): Boolean {
        val latch = CountDownLatch(1)
        val allowed = AtomicBoolean(false)
        main.post {
            val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            val dialog = AlertDialog.Builder(ctx)
                .setTitle("Agent ko permission do?")
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton("Allow") { _, _ -> allowed.set(true); latch.countDown() }
                .setNegativeButton("Deny") { _, _ -> latch.countDown() }
                .create()
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialog.show()
        }
        latch.await(60, TimeUnit.SECONDS)
        return allowed.get()
    }

    fun showStopButton() {
        main.post {
            if (stopButton != null) return@post
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val b = Button(this).apply {
                text = "STOP AGENT"
                setOnClickListener { AgentLoop.stop() }
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                y = 200
            }
            wm.addView(b, lp)
            stopButton = b
        }
    }

    fun hideStopButton() {
        main.post {
            stopButton?.let {
                try {
                    (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
                } catch (_: Exception) {
                }
            }
            stopButton = null
        }
    }
}
