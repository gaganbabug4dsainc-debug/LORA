package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/** Haath + aankhein: screen padhta hai, tap/type/scroll karta hai, aur floating icon/panel dikhata hai. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    private var stopButton: View? = null
    private var bubble: TextView? = null
    private var panel: View? = null
    private var panelStatus: TextView? = null
    private var panelLog: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelInput: EditText? = null

    private val logListener: (String) -> Unit = { line -> main.post { appendPanelLog(line) } }
    private val stateListener: () -> Unit = { main.post { refreshStatusUi() } }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Config.KEY_BUBBLE) main.post { applyBubblePref() }
    }

    override fun onServiceConnected() {
        instance = this
        AgentLog.addListener(logListener)
        AgentState.addListener(stateListener)
        Config.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        applyBubblePref()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        instance = null
        AgentLoop.stop()
        AgentLog.removeListener(logListener)
        AgentState.removeListener(stateListener)
        try {
            Config.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        removePanelNow()
        removeBubbleNow()
        removeStopNow()
    }

    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // ---------- Aankhein: screen -> compact text ----------

    /** Hamare apne overlay ko chhodkar asli app ki window dhundhta hai. */
    private fun targetRoot(): AccessibilityNodeInfo? {
        try {
            val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            val w = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused } ?: apps.firstOrNull()
            val r = w?.root
            if (r != null) return r
        } catch (_: Exception) {
        }
        return rootInActiveWindow
    }

    fun readScreen(): String {
        val root = targetRoot() ?: return "(screen unavailable)"
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
        return dispatchPassThrough(g)
    }

    private fun swipe(x1: Float, y1: Float, x2: Float, y2: Float): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)).build()
        return dispatchPassThrough(g)
    }

    /** Gesture ke dauran hamare overlays touch ko rokte nahi (pass-through). */
    private fun dispatchPassThrough(g: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        main.post {
            setPassThrough(true)
            latch.countDown()
        }
        latch.await(500, TimeUnit.MILLISECONDS)
        Thread.sleep(80)
        val ok = dispatchGesture(g, null, null)
        main.postDelayed({ setPassThrough(false) }, 700)
        return ok
    }

    private fun setPassThrough(on: Boolean) {
        for (v in listOfNotNull(panel, bubble, stopButton)) {
            val lp = v.layoutParams as? WindowManager.LayoutParams ?: continue
            lp.flags = if (on) lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            try {
                wm().updateViewLayout(v, lp)
            } catch (_: Exception) {
            }
        }
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
                y = dp(80)
            }
            try {
                wm().addView(b, lp)
                stopButton = b
            } catch (_: Exception) {
            }
        }
    }

    fun hideStopButton() {
        main.post { removeStopNow() }
    }

    private fun removeStopNow() {
        stopButton?.let {
            try {
                wm().removeView(it)
            } catch (_: Exception) {
            }
        }
        stopButton = null
    }

    // ---------- Floating status icon (bubble) ----------

    private fun statusColor(): Int = when (AgentState.status) {
        Status.IDLE -> Color.parseColor("#5F6368")
        Status.RUNNING -> Color.parseColor("#1E8E3E")
        Status.WAITING -> Color.parseColor("#F9AB00")
        Status.ERROR -> Color.parseColor("#D93025")
    }

    private fun statusLabel(): String = when (AgentState.status) {
        Status.IDLE -> "Ready"
        Status.RUNNING -> "Chal raha hai"
        Status.WAITING -> "Wait"
        Status.ERROR -> "Error"
    }

    private fun applyBubblePref() {
        if (Config.bubbleEnabled(this)) {
            showBubble()
        } else {
            removePanelNow()
            removeBubbleNow()
        }
    }

    private fun showBubble() {
        if (bubble != null) return
        val tv = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = circle(statusColor())
            alpha = 0.92f
        }
        val size = dp(52)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = dp(300)
        }

        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        tv.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x
                    startY = lp.y
                    touchX = e.rawX
                    touchY = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = startX + dx
                        lp.y = startY + dy
                        try {
                            wm().updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) showPanel()
                    true
                }
                else -> false
            }
        }

        try {
            wm().addView(tv, lp)
            bubble = tv
        } catch (_: Exception) {
        }
    }

    private fun removeBubbleNow() {
        bubble?.let {
            try {
                wm().removeView(it)
            } catch (_: Exception) {
            }
        }
        bubble = null
    }

    // ---------- Mini window (panel): live status + log + message bhejna ----------

    private fun showPanel() {
        if (panel != null) return
        bubble?.visibility = View.GONE
        val pad = dp(10)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = rounded(Color.parseColor("#F0202124"), dp(16))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        val minimize = Button(this).apply {
            text = "—"
            minWidth = 0
            minimumWidth = 0
            setOnClickListener { hidePanel() }
        }
        header.addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(minimize)

        val log = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 12f
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(log) }

        val input = EditText(this).apply {
            hint = "Agent ko batao / naya goal"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 3
        }
        val send = Button(this).apply {
            text = "Bhejo"
            setOnClickListener { sendFromPanel() }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(send)

        val stop = Button(this).apply {
            text = "STOP"
            setTextColor(Color.RED)
            setOnClickListener { AgentLoop.stop() }
        }
        val openApp = Button(this).apply {
            text = "App kholo"
            setOnClickListener {
                startActivity(
                    Intent(this@AgentAccessibilityService, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bottom.addView(stop, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bottom.addView(openApp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        root.addView(header)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(inputRow)
        root.addView(bottom)

        val lp = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(),
            dp(380),
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(40)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        }

        try {
            wm().addView(root, lp)
        } catch (_: Exception) {
            bubble?.visibility = View.VISIBLE
            return
        }
        panel = root
        panelStatus = status
        panelLog = log
        panelScroll = scroll
        panelInput = input
        log.text = AgentLog.lines.takeLast(60).joinToString("\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        refreshStatusUi()
    }

    private fun sendFromPanel() {
        val t = panelInput?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        panelInput?.setText("")
        AgentLoop.submit(this, t)
    }

    private fun hidePanel() {
        removePanelNow()
        bubble?.visibility = View.VISIBLE
    }

    private fun removePanelNow() {
        panel?.let {
            try {
                wm().removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        panelStatus = null
        panelLog = null
        panelScroll = null
        panelInput = null
    }

    private fun appendPanelLog(line: String) {
        val log = panelLog ?: return
        log.append(line + "\n")
        if (log.length() > 12000) log.text = log.text.toString().takeLast(8000)
        panelScroll?.post { panelScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatusUi() {
        (bubble?.background as? GradientDrawable)?.setColor(statusColor())
        panelStatus?.text = "${statusLabel()}: ${AgentState.detail}"
    }
}
