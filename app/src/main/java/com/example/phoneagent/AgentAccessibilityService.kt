package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Path
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
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Haath + aankhein: screen padhta hai, tap/type/scroll karta hai, aur floating icon / stop / mini window dikhata hai. */
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
    private var panelSub: TextView? = null
    private var panelLog: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelInput: EditText? = null
    private var panelParts: List<View> = emptyList() // collapse karne par chhupne wale hisse
    private var completionBar: LinearLayout? = null
    private var chipMode: TextView? = null
    private var chipModel: TextView? = null
    private var chipIndicator: TextView? = null
    private var chipLive: TextView? = null
    private var chipPause: TextView? = null
    private var chipResume: TextView? = null
    private var chipManual: TextView? = null
    private var chipLimit: TextView? = null

    private var collapsed = false
    private var maximized = false
    private var savedRect = IntArray(4)
    private var lastCompletedId = 0L
    private var lastTap = 0L
    private var tapRunnable: Runnable? = null

    @Volatile
    private var continueLast = false

    // Windows ki jagah yaad rakhte hain, taaki dobara khulne par wahin dikhein
    private var bubbleX = 0
    private var bubbleY = -1
    private var stopX = -1
    private var stopY = -1
    private var panelX = -1
    private var panelY = -1
    private var panelW = 0
    private var panelH = 0

    @Volatile
    private var liveMode = false
    private val dialogRef = AtomicReference<AlertDialog?>(null)

    private val logListener: (String) -> Unit = { line -> main.post { appendPanelLog(line) } }
    private val stateListener: () -> Unit = { main.post { refreshStatusUi() } }
    private val completedListener: (Long, String) -> Unit = { id, msg -> main.post { onTaskCompleted(id, msg) } }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Config.KEY_BUBBLE) main.post { applyBubblePref() }
    }

    override fun onServiceConnected() {
        instance = this
        AgentLog.addListener(logListener)
        AgentState.addListener(stateListener)
        AgentEvents.addListener(completedListener)
        Privacy.serviceAsk = { msg -> serviceConsent(msg) }
        TaskStore.recoverOnStart(this)
        Config.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        Pool.refresh(this)
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
        liveMode = false
        AgentLoop.stop()
        ChatEngine.stop()
        Speaker.stop()
        AgentLog.removeListener(logListener)
        AgentState.removeListener(stateListener)
        AgentEvents.removeListener(completedListener)
        Privacy.serviceAsk = null
        try {
            Config.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        } catch (_: Exception) {
        }
        removePanelNow()
        removeBubbleNow()
        removeStopNow()
    }

    private fun wm() = getSystemService(WINDOW_SERVICE) as WindowManager
    private fun dp(v: Int) = Ui.dp(this, v)
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

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
        val w = screenW().toFloat()
        val h = screenH().toFloat()
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

    // ---------- Dialogs (permission / step control) ----------

    /**
     * Overlay dialog dikhata hai aur jawab ka intezaar karta hai.
     * Return: 0 = pehla button, 1 = beech wala, 2 = aakhri button (timeout / STOP par bhi 2).
     */
    private fun choiceDialog(
        title: String,
        message: String,
        pos: String,
        neu: String?,
        neg: String,
        timeoutSec: Int
    ): Int {
        val latch = CountDownLatch(1)
        val choice = AtomicInteger(2)
        main.post {
            val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
            val b = AlertDialog.Builder(ctx)
                .setTitle(title)
                .setMessage(message)
                .setCancelable(false)
                .setPositiveButton(pos) { _, _ -> choice.set(0); latch.countDown() }
                .setNegativeButton(neg) { _, _ -> choice.set(2); latch.countDown() }
            if (neu != null) b.setNeutralButton(neu) { _, _ -> choice.set(1); latch.countDown() }
            val d = b.create()
            d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            dialogRef.set(d)
            d.show()
        }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (!latch.await(300, TimeUnit.MILLISECONDS)) {
            if (AgentLoop.cancelled() || System.currentTimeMillis() > deadline) break
        }
        main.post {
            try {
                dialogRef.getAndSet(null)?.dismiss()
            } catch (_: Exception) {
            }
        }
        return choice.get()
    }

    /** Risky action se pehle user se poochta hai. 60 sec me jawab na aaye to Deny. */
    fun confirm(message: String): Boolean =
        choiceDialog("Agent ko permission do?", message, "Allow", null, "Deny", 60) == 0

    /** Loop ka shak: 0 = continue, 1 = plan badlo, 2 = stop (2 min me jawab na aaye to stop). */
    fun loopChoice(message: String): Int =
        choiceDialog("Agent loop me phans sakta hai", message, "Continue", "Plan badlo", "Stop", 120)

    /** Online model ko data bhejne ki permission: 0 = 30 min, 1 = is session, 2 = mana. */
    private fun serviceConsent(message: String): Int =
        choiceDialog("Online AI ko data bhejna?", message, "Ab allow (30 min)", "Is session", "Mana", 120)

    /** Manual step mode: 0 = chalao, 1 = skip, 2 = roko. 10 min me jawab na aaye to roko. */
    fun stepChoice(message: String): Int =
        choiceDialog("Agla step", message, "Chalao", "Skip", "Roko", 600)

    // ---------- Draggable helper ----------

    private fun overlayParams(w: Int, h: Int, focusable: Boolean): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!focusable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        return WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }
    }

    /** `handle` ko pakad kar `target` window ko ungli se kahin bhi le jao. Bina hile tap = onTap. */
    private fun makeDraggable(
        handle: View,
        target: View,
        lp: WindowManager.LayoutParams,
        onTap: (() -> Unit)?,
        onMoved: (Int, Int) -> Unit
    ) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = lp.x
                    sy = lp.y
                    tx = e.rawX
                    ty = e.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) moved = true
                    if (moved) {
                        val w = if (target.width > 0) target.width else lp.width.coerceAtLeast(0)
                        val h = if (target.height > 0) target.height else lp.height.coerceAtLeast(0)
                        lp.x = (sx + dx).coerceIn(0, (screenW() - w).coerceAtLeast(0))
                        lp.y = (sy + dy).coerceIn(0, (screenH() - h).coerceAtLeast(0))
                        try {
                            wm().updateViewLayout(target, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) onTap?.invoke()
                    onMoved(lp.x, lp.y)
                    true
                }
                else -> true
            }
        }
    }

    // ---------- STOP: floating gol button (icon jaisa, kahin bhi drag karo) ----------

    fun showStopButton() {
        main.post {
            // Agent chalu hua: agar user ne icon "Close" kiya tha to wapas dikhao
            if (Config.bubbleEnabled(this) && bubble == null && panel == null) showBubble()
            if (stopButton != null) return@post
            val tv = TextView(this).apply {
                text = "■"
                setTextColor(Color.WHITE)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = Ui.circle(Ui.RED)
                alpha = 0.95f
            }
            val size = dp(50)
            val lp = overlayParams(size, size, false).apply {
                x = if (stopX >= 0) stopX else screenW() - size - dp(8)
                y = if (stopY >= 0) stopY else dp(180)
            }
            makeDraggable(tv, tv, lp, { AgentLoop.stop() }) { x, y ->
                stopX = x
                stopY = y
            }
            try {
                wm().addView(tv, lp)
                stopButton = tv
            } catch (_: Exception) {
            }
        }
    }

    /** App se: floating icon wapas dikhao (Close ke baad). */
    fun showIcon() {
        main.post { if (Config.bubbleEnabled(this) && panel == null) showBubble() }
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
        Status.IDLE -> Color.parseColor("#9AA0A6")
        Status.RUNNING -> Color.parseColor("#1E8E3E")
        Status.WAITING -> Color.parseColor("#F9AB00")
        Status.PAUSED -> Color.parseColor("#1A73E8")
        Status.ERROR -> Color.parseColor("#D93025")
    }

    private fun statusLabel(): String = when (AgentState.status) {
        Status.IDLE -> "⚪ Idle"
        Status.RUNNING -> "🟢 Running"
        Status.WAITING -> "🟡 Waiting"
        Status.PAUSED -> "🔵 Paused"
        Status.ERROR -> "🔴 Error"
    }

    private fun applyBubblePref() {
        if (Config.bubbleEnabled(this)) {
            if (panel == null) showBubble()
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
            background = Ui.circle(statusColor())
            alpha = 0.92f
        }
        val size = dp(52)
        val lp = overlayParams(size, size, false).apply {
            x = bubbleX
            y = if (bubbleY >= 0) bubbleY else dp(300)
        }
        // Drag = kahin bhi le jao | tap = mini panel | double tap = Agent Chat
        makeDraggable(tv, tv, lp, { onBubbleTap() }) { x, y ->
            bubbleX = x
            bubbleY = y
        }
        try {
            wm().addView(tv, lp)
            bubble = tv
        } catch (_: Exception) {
        }
    }

    private fun onBubbleTap() {
        val now = System.currentTimeMillis()
        if (now - lastTap < 320) {
            tapRunnable?.let { main.removeCallbacks(it) }
            tapRunnable = null
            lastTap = 0
            Config.setPanelMode(this, "agent")
            showPanel()
            return
        }
        lastTap = now
        val r = Runnable { showPanel() }
        tapRunnable = r
        main.postDelayed(r, 330)
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

    // ---------- Dialog helpers (overlay) ----------

    private fun themedCtx() = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)

    private fun showDlg(b: AlertDialog.Builder) {
        val d = b.create()
        d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        d.show()
    }

    private fun chip(label: String, size: Float = 12f, bg: Int = Ui.GREY, onClick: () -> Unit): TextView =
        Ui.chip(this, label, bg = bg, size = size, onClick = onClick)

    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun marginLp(left: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = left }

    private fun addChip(row: LinearLayout, chip: TextView) {
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        row.addView(chip, lp)
    }

    private fun logLine(s: String) = AgentLog.add(s)

    // ---------- Mini window (panel) ----------

    private fun showPanel() {
        if (panel != null) return
        bubble?.visibility = View.GONE
        val pad = dp(8)
        collapsed = false
        maximized = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Ui.rounded(Color.parseColor("#F2202124"), dp(16))
        }

        // Header: yahan se pakad kar window kahin bhi le jao
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val grip = TextView(this).apply {
            text = "⠿"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 18f
            setPadding(0, 0, dp(8), 0)
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
        }
        val sub = TextView(this).apply {
            setTextColor(Color.parseColor("#BDC1C6"))
            textSize = 10.5f
            maxLines = 2
        }
        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        titleCol.addView(status)
        titleCol.addView(sub)
        header.addView(grip)
        header.addView(titleCol, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(chip("▾", 13f) { toggleCollapse() }, marginLp(dp(3)))
        header.addView(chip("⤢", 13f) { toggleMaximize() }, marginLp(dp(3)))
        header.addView(chip("—", 13f) { hidePanel() }, marginLp(dp(3)))
        header.addView(chip("✕", 13f) { closePanel() }, marginLp(dp(3)))

        // Row A: mode / model / local-online / live
        val rowA = row()
        val cMode = chip("") {
            val next = if (Config.panelMode(this) == "chat") "agent" else "chat"
            Config.setPanelMode(this, next)
            if (next == "agent") liveMode = false
            refreshStatusUi()
        }
        val cModel = chip("") { ModelPicker.show(this, true) { main.post { refreshStatusUi() } } }
        val cInd = chip("") { dataModeDialog() }
        val cLive = chip("") {
            liveMode = !liveMode
            if (liveMode) {
                Config.setTts(this, true)
                Config.setPanelMode(this, "chat")
                refreshStatusUi()
                startVoice()
            } else {
                Speaker.stop()
                refreshStatusUi()
            }
        }
        addChip(rowA, cMode)
        addChip(rowA, cModel)
        addChip(rowA, cInd)
        addChip(rowA, cLive)

        // Task poora hone par: Continue / Run again / Skill / Review / Close
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = Ui.rounded(Color.parseColor("#1E3A2B"), dp(10))
            visibility = View.GONE
        }

        val log = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 12f
            setPadding(pad, pad, pad, pad)
        }
        val scroll = ScrollView(this).apply { addView(log) }

        // Row B: execution control
        val rowB = row()
        val cPause = chip("⏸ Pause", 11f) {
            if (AgentLoop.isRunning()) AgentLoop.setPaused(true) else logLine("Agent chal nahi raha.")
        }
        val cResume = chip("▶ Resume", 11f) {
            if (AgentLoop.isRunning()) AgentLoop.setPaused(false) else resumeUnfinished()
        }
        addChip(rowB, cPause)
        addChip(rowB, cResume)
        addChip(rowB, chip("Skip", 11f) { AgentLoop.skip() })
        addChip(rowB, chip("Next", 11f) { AgentLoop.next() })
        addChip(rowB, chip("Retry", 11f) { AgentLoop.retry() })

        // Row C: stop / steps / checkpoint / limit
        val rowC = row()
        val cManual = chip("✋", 11f) {
            Config.setManualStep(this, !Config.manualStep(this))
            refreshStatusUi()
        }
        val cLimit = chip("", 11f) {
            if (Config.unlimited(this)) {
                Config.setUnlimited(this, false)
                Config.setMaxSteps(this, Config.STEP_CHOICES.first())
            } else {
                val next = Config.STEP_CHOICES.firstOrNull { it > Config.maxSteps(this) }
                if (next == null) Config.setUnlimited(this, true) else Config.setMaxSteps(this, next)
            }
            refreshStatusUi()
        }
        addChip(rowC, chip("■ STOP", 11f, Ui.RED) {
            liveMode = false
            Speaker.stop()
            ChatEngine.stop()
            AgentLoop.stop()
            refreshStatusUi()
        })
        addChip(rowC, chip("+ Step", 11f) { addStepDialog() })
        addChip(rowC, chip("📋 Steps", 11f) { showStepsEditor() })
        addChip(rowC, chip("⚑", 11f) {
            if (AgentLoop.isRunning()) AgentLoop.checkpointNow(this, "manual") else logLine("Agent chal nahi raha.")
        })
        addChip(rowC, cLimit)
        addChip(rowC, cManual)

        // Input row
        val input = EditText(this).apply {
            hint = "Goal / message / command likho"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 3
            // Keyboard tabhi aata hai jab window focusable ho; tap par focusable karte hain
            setOnTouchListener { v, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    setPanelFocusable(true)
                    main.postDelayed({
                        v.requestFocus()
                        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                            .showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                    }, 150)
                }
                false
            }
        }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(chip("🎤", 16f) {
            hideKeyboard()
            startVoice()
        }, marginLp(dp(4)))
        inputRow.addView(chip("Bhejo", 12f, Ui.BLUE) { sendFromPanel() }, marginLp(dp(4)))

        // Row D: app / chat kholo + resize handle
        val rowD = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val resize = TextView(this).apply {
            text = "◢"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 22f
            gravity = Gravity.END
            setPadding(dp(16), 0, dp(4), 0)
        }
        addChip(rowD, chip("App", 11f) { openActivity(MainActivity::class.java) })
        addChip(rowD, chip("💬", 11f) { openActivity(ChatActivity::class.java) })
        addChip(rowD, chip("🧩 Skills", 11f) { openActivity(SkillsActivity::class.java) })
        addChip(rowD, chip("🕘", 11f) { openActivity(HistoryActivity::class.java) })
        addChip(rowD, chip("⟲ Restart", 11f) {
            showDlg(
                AlertDialog.Builder(themedCtx()).setTitle("Restart?")
                    .setMessage("Task Step 1 se dobara shuru hoga.")
                    .setPositiveButton("Restart") { _, _ -> AgentLoop.restart(this) }
                    .setNegativeButton("Cancel", null)
            )
        })
        rowD.addView(resize, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        root.addView(header)
        root.addView(rowA)
        root.addView(bar)
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(rowB)
        root.addView(rowC)
        root.addView(inputRow)
        root.addView(rowD)

        if (panelW <= 0) panelW = (screenW() * 0.92f).toInt()
        if (panelH <= 0) panelH = dp(460)
        val lp = overlayParams(panelW, panelH, false).apply {
            x = if (panelX >= 0) panelX else ((screenW() - panelW) / 2).coerceAtLeast(0)
            y = if (panelY >= 0) panelY else dp(60)
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }

        makeDraggable(header, root, lp, null) { x, y ->
            if (!maximized) {
                panelX = x
                panelY = y
            }
        }

        // Resize handle: neeche-daayein kone se khicho
        var sw = 0
        var sh = 0
        var tx = 0f
        var ty = 0f
        resize.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sw = lp.width
                    sh = lp.height
                    tx = e.rawX
                    ty = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val maxW = (screenW() - lp.x).coerceAtLeast(dp(260))
                    val maxH = (screenH() - lp.y).coerceAtLeast(dp(240))
                    lp.width = (sw + (e.rawX - tx)).toInt().coerceIn(dp(260), maxW)
                    lp.height = (sh + (e.rawY - ty)).toInt().coerceIn(dp(240), maxH)
                    if (!maximized) {
                        panelW = lp.width
                        panelH = lp.height
                    }
                    try {
                        wm().updateViewLayout(root, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> true
            }
        }

        try {
            wm().addView(root, lp)
        } catch (_: Exception) {
            bubble?.visibility = View.VISIBLE
            return
        }
        panel = root
        panelStatus = status
        panelSub = sub
        panelLog = log
        panelScroll = scroll
        panelInput = input
        panelParts = listOf(rowA, scroll, rowC, inputRow, rowD)
        completionBar = bar
        chipMode = cMode
        chipModel = cModel
        chipIndicator = cInd
        chipLive = cLive
        chipPause = cPause
        chipResume = cResume
        chipManual = cManual
        chipLimit = cLimit
        log.text = AgentLog.lines.takeLast(60).joinToString("\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        refreshStatusUi()
    }

    private fun openActivity(cls: Class<*>) {
        hidePanel()
        startActivity(Intent(this, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun toggleCollapse() {
        collapsed = !collapsed
        for (v in panelParts) v.visibility = if (collapsed) View.GONE else View.VISIBLE
        val v = panel ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.height = if (collapsed) WindowManager.LayoutParams.WRAP_CONTENT
        else if (maximized) (screenH() * 0.75f).toInt() else panelH
        try {
            wm().updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun toggleMaximize() {
        val v = panel ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        if (!maximized) {
            savedRect = intArrayOf(lp.x, lp.y, lp.width, lp.height)
            lp.x = dp(8)
            lp.y = dp(40)
            lp.width = screenW() - dp(16)
            lp.height = (screenH() * 0.75f).toInt()
            maximized = true
        } else {
            lp.x = savedRect[0]
            lp.y = savedRect[1]
            lp.width = savedRect[2]
            lp.height = savedRect[3]
            maximized = false
        }
        collapsed = false
        for (p in panelParts) p.visibility = View.VISIBLE
        try {
            wm().updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    // ---------- Panel: focus / keyboard ----------

    private fun setPanelFocusable(on: Boolean) {
        val v = panel ?: return
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        lp.flags = if (on) lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        try {
            wm().updateViewLayout(v, lp)
        } catch (_: Exception) {
        }
    }

    private fun hideKeyboard() {
        panelInput?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        setPanelFocusable(false)
    }

    // ---------- Panel: send / agent chat / chat / voice ----------

    private fun sendFromPanel() {
        val t = panelInput?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        panelInput?.setText("")
        hideKeyboard()
        sendText(t)
    }

    private fun sendText(t: String) {
        if (Config.panelMode(this) == "chat") chatFromPanel(t) else agentChat(t)
    }

    private fun looksLikeQuestion(t: String): Boolean {
        val s = t.trim().lowercase()
        return s.endsWith("?") ||
            Regex("^(what|why|how|when|where|which|kya|kyun|kyu|kaise|kab|kahan|kaun|batao|bata)\\b").containsMatchIn(s)
    }

    private fun reply(text: String) {
        logLine("AI: $text")
        Speaker.event(applicationContext, text, true)
    }

    /** Agent Chat: chalte task se baat. Commands local; sawal ka jawab task ki state dekhkar; baaki instruction. */
    private fun agentChat(t: String) {
        val app = applicationContext
        val local = AgentCommands.handle(app, t)
        if (local != null) {
            logLine("Tum: $t")
            reply(local)
            return
        }
        val running = AgentLoop.isRunning()
        if (looksLikeQuestion(t) && (running || TaskStore.unfinished(app) != null)) {
            logLine("Tum: $t")
            askAboutTask(t)
            return
        }
        if (running) {
            AgentLoop.submit(this, t)
            reply("Theek hai, instruction jod diya.")
            return
        }
        if (continueLast && lastCompletedId > 0) {
            continueLast = false
            logLine("Tum: $t")
            AgentLoop.resumeTask(this, lastCompletedId, t)
            return
        }
        AgentLoop.start(this, t, null)
    }

    private fun askAboutTask(t: String) {
        val app = applicationContext
        if (ChatEngine.busy) {
            logLine("Pehla jawab abhi aa raha hai.")
            return
        }
        val conv = ChatStore.agentConv(app)
        ChatEngine.send(app, conv, t, emptyList(), emptyList(), { logLine(it) }) { r, err ->
            if (r != null) {
                logLine("AI: $r")
                Speaker.event(app, r, true)
            } else {
                logLine("Error: $err")
            }
        }
    }

    private fun chatFromPanel(t: String) {
        if (ChatEngine.busy) {
            logLine("Pehla jawab abhi aa raha hai. Ruko ya STOP dabao.")
            return
        }
        if (Config.load(this).isEmpty()) {
            logLine("Koi provider nahi hai. App me API key ya local model jodo.")
            return
        }
        val app = applicationContext
        val conv = ChatStore.ensureActive(app)
        logLine("Tum: $t")
        ChatEngine.send(app, conv, t, emptyList(), emptyList(), { logLine(it) }) { reply, err ->
            if (reply != null) {
                logLine("AI: $reply")
                if (Config.ttsOn(app)) {
                    Speaker.speak(app, reply) { if (liveMode) startVoice() }
                } else if (liveMode) {
                    main.post { startVoice() }
                }
            } else {
                logLine("Error: $err")
            }
        }
    }

    /** Google ka bolne wala dialog kholta hai; natija onVoiceResult me aata hai. */
    private fun startVoice() {
        main.post {
            VoiceBridge.callback = { text -> main.post { onVoiceResult(text) } }
            try {
                startActivity(Intent(this, VoiceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                VoiceBridge.callback = null
                logLine("Voice input nahi khul paya: ${e.message}")
                liveMode = false
                refreshStatusUi()
            }
        }
    }

    private fun onVoiceResult(text: String?) {
        if (text.isNullOrBlank()) {
            liveMode = false // bola nahi / cancel -> live band
            refreshStatusUi()
            return
        }
        sendText(text)
    }

    private fun resumeUnfinished() {
        val tk = TaskStore.unfinished(this)
        if (tk == null) logLine("Koi adhura task nahi.") else AgentLoop.resumeTask(this, tk.id, null)
    }

    // ---------- Panel: Data Processing ----------

    private fun dataModeDialog() {
        val names = arrayOf(
            "🟢 Sirf Local (kuch bhi online nahi jayega)",
            "❓ Online se pehle poochho (default)",
            "🌐 Online allowed"
        )
        val keys = arrayOf("local", "ask", "allow")
        showDlg(
            AlertDialog.Builder(themedCtx())
                .setTitle("Data Processing")
                .setSingleChoiceItems(names, keys.indexOf(Config.dataMode(this))) { d, i ->
                    Config.setDataMode(this, keys[i])
                    Privacy.session = false
                    d.dismiss()
                    refreshStatusUi()
                }
                .setNegativeButton("Band", null)
        )
    }

    // ---------- Steps: add / edit / delete / move / checkpoint ----------

    private fun editorTaskId(): Long =
        if (AgentLoop.taskId > 0) AgentLoop.taskId else (TaskStore.unfinished(this)?.id ?: TaskStore.latest(this)?.id ?: 0L)

    fun addStepDialog(taskId: Long = 0L) {
        val id = if (taskId > 0) taskId else editorTaskId()
        if (id <= 0) {
            logLine("Pehle koi task chalao, phir step jodo.")
            return
        }
        val et = EditText(themedCtx()).apply {
            hint = "Naya step (jaise: Take a screenshot)"
            setSingleLine()
        }
        showDlg(
            AlertDialog.Builder(themedCtx())
                .setTitle("+ Step jodo")
                .setView(et)
                .setPositiveButton("Agla step banao") { _, _ -> addStepText(id, et, true) }
                .setNeutralButton("Aakhir me") { _, _ -> addStepText(id, et, false) }
                .setNegativeButton("Cancel", null)
        )
    }

    private fun addStepText(id: Long, et: EditText, front: Boolean) {
        val t = et.text.toString().trim()
        if (t.isEmpty()) return
        TaskStore.addPending(applicationContext, id, t, front)
        logLine("Step jodha: $t")
    }

    /** Completed steps sirf dikhte hain; aage ke (pending) steps edit/delete/move/duplicate/disable/checkpoint ho sakte hain. */
    fun showStepsEditor(taskId: Long = 0L) {
        val app = applicationContext
        val id = if (taskId > 0) taskId else editorTaskId()
        if (id <= 0) {
            logLine("Koi task nahi hai.")
            return
        }
        val labels = ArrayList<String>()
        val acts = ArrayList<(() -> Unit)?>()
        for (h in TaskStore.history(app, id, 6)) {
            labels.add("✓ ${h.n}. ${h.text.take(48)} [${h.status}]")
            acts.add(null)
        }
        for ((i, p) in TaskStore.pending(app, id).withIndex()) {
            val tag = when {
                p.text.startsWith("#CHECKPOINT") -> "⚑ "
                !p.enabled -> "(off) "
                else -> ""
            }
            labels.add("⏳ ${i + 1}. $tag${p.text.removePrefix("#CHECKPOINT").trim().take(56)}")
            acts.add { pendingActions(id, p) }
        }
        labels.add("+ Step jodo")
        acts.add { addStepDialog(id) }
        labels.add("⚑ Abhi checkpoint banao")
        acts.add {
            if (AgentLoop.isRunning()) AgentLoop.checkpointNow(app, "manual") else logLine("Agent chal nahi raha.")
        }
        val t = TaskStore.get(app, id)
        showDlg(
            AlertDialog.Builder(themedCtx())
                .setTitle("Task #$id · Step ${t?.step ?: 0}/${AgentLoop.totalLabel(app)}")
                .setItems(labels.toTypedArray()) { _, i -> acts[i]?.invoke() }
                .setNegativeButton("Band", null)
        )
    }

    private fun pendingActions(id: Long, p: PStep) {
        val app = applicationContext
        val items = arrayOf(
            "✎ Edit", "🗑 Delete", "↑ Upar", "↓ Neeche", "⧉ Duplicate",
            if (p.enabled) "⛔ Disable" else "✅ Enable", "⚑ Checkpoint banao"
        )
        showDlg(
            AlertDialog.Builder(themedCtx())
                .setTitle(p.text.removePrefix("#CHECKPOINT").trim().take(40))
                .setItems(items) { _, i ->
                    when (i) {
                        0 -> editStepDialog(id, p)
                        1 -> TaskStore.deletePending(app, p.id)
                        2 -> TaskStore.movePending(app, id, p, true)
                        3 -> TaskStore.movePending(app, id, p, false)
                        4 -> TaskStore.duplicatePending(app, id, p)
                        5 -> TaskStore.setPendingEnabled(app, p.id, !p.enabled)
                        6 -> TaskStore.editPending(app, p.id, "#CHECKPOINT " + p.text.removePrefix("#CHECKPOINT").trim())
                    }
                    if (i != 0) main.post { showStepsEditor(id) }
                }
                .setNegativeButton("Band", null)
        )
    }

    private fun editStepDialog(id: Long, p: PStep) {
        val et = EditText(themedCtx()).apply {
            setText(p.text.removePrefix("#CHECKPOINT").trim())
            setSingleLine()
        }
        showDlg(
            AlertDialog.Builder(themedCtx())
                .setTitle("Step edit")
                .setView(et)
                .setPositiveButton("Save") { _, _ ->
                    val t = et.text.toString().trim()
                    if (t.isNotEmpty()) TaskStore.editPending(applicationContext, p.id, t)
                    main.post { showStepsEditor(id) }
                }
                .setNegativeButton("Cancel", null)
        )
    }

    // ---------- Task completion: Continue / Run again / Skill / Review / Close ----------

    private fun onTaskCompleted(id: Long, msg: String) {
        lastCompletedId = id
        continueLast = false
        if (panel == null) showPanel()
        val bar = completionBar ?: return
        bar.removeAllViews()
        bar.visibility = View.VISIBLE
        bar.addView(TextView(this).apply {
            text = "✅ Task completed: ${msg.take(80)}\nWhat would you like me to do next?"
            textSize = 12f
            setTextColor(Color.WHITE)
        })
        val r = row()
        addChip(r, chip("Continue", 11f) {
            continueLast = true
            logLine("Isi task me aage kya karun? Instruction likho ya bolo.")
            completionBar?.visibility = View.GONE
        })
        addChip(r, chip("Run again", 11f) {
            val g = TaskStore.get(applicationContext, id)?.goal
            completionBar?.visibility = View.GONE
            if (g != null) AgentLoop.start(this, g, null)
        })
        addChip(r, chip("Skill", 11f) { saveSkill(id) })
        addChip(r, chip("Copy", 11f) {
            val full = TaskStore.historyRows(applicationContext, id).lastOrNull { it.text.startsWith("done:") }
                ?.text?.removePrefix("done:")?.trim() ?: msg
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                .setPrimaryClip(android.content.ClipData.newPlainText("LoRA", full))
            logLine("Jawab copy ho gaya.")
        })
        addChip(r, chip("Review", 11f) { reviewTask(id) })
        addChip(r, chip("Close", 11f) { completionBar?.visibility = View.GONE })
        bar.addView(r)
    }

    private fun saveSkill(id: Long) {
        val app = applicationContext
        val t = TaskStore.get(app, id) ?: return
        val steps = SkillAI.stepsFromTask(app, id)
        val plat = Platforms.detect(t.goal + " " + steps.joinToString(" "))
        TaskStore.saveSkill(app, t.goal.take(40), t.goal, SkillAI.numbered(steps), plat, "Task #$id se bani")
        logLine("Skill save ho gayi: ${t.goal.take(40)}. 🧩 Skills me edit / AI se saaf karo / merge kar sakte ho.")
    }

    private fun reviewTask(id: Long) {
        val app = applicationContext
        val h = TaskStore.history(app, id, 40).joinToString("\n") { "${it.n}. ${it.text} [${it.status}]" }
        showDlg(
            AlertDialog.Builder(themedCtx())
                .setTitle("Review: Task #$id")
                .setMessage(h.ifBlank { "(koi step nahi)" })
                .setPositiveButton("Band", null)
        )
    }

    // ---------- Panel: hide / close / refresh ----------

    /** Minimize: wapas icon. */
    private fun hidePanel() {
        hideKeyboard()
        removePanelNow()
        if (Config.bubbleEnabled(this)) {
            showBubble()
            bubble?.visibility = View.VISIBLE
        }
    }

    /** Close: panel aur icon dono hatao (agent dobara start ho ya App se icon on karo tab wapas). Stop button rehta hai. */
    private fun closePanel() {
        hideKeyboard()
        removePanelNow()
        removeBubbleNow()
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
        panelSub = null
        panelLog = null
        panelScroll = null
        panelInput = null
        panelParts = emptyList()
        completionBar = null
        chipMode = null
        chipModel = null
        chipIndicator = null
        chipLive = null
        chipPause = null
        chipResume = null
        chipManual = null
        chipLimit = null
    }

    private fun appendPanelLog(line: String) {
        val log = panelLog ?: return
        log.append(line + "\n")
        if (log.length() > 12000) log.text = log.text.toString().takeLast(8000)
        panelScroll?.post { panelScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatusUi() {
        (bubble?.background as? GradientDrawable)?.setColor(statusColor())
        if (panel == null) return
        val chat = Config.panelMode(this) == "chat"
        val running = AgentLoop.isRunning()
        val tk = if (AgentLoop.taskId > 0) TaskStore.get(this, AgentLoop.taskId) else TaskStore.unfinished(this)
        val stepNow = if (running) AgentLoop.step else (tk?.step ?: 0)
        panelStatus?.text = (if (chat) "💬 Chat · " else "🤖 Agent Chat · ") + "${statusLabel()}: ${AgentState.detail}"
        panelSub?.text = "Task: ${tk?.goal?.take(26) ?: "-"} · Step $stepNow/${AgentLoop.totalLabel(this)} · ${ModelPicker.shortLabel(this)}"

        chipMode?.text = if (chat) "💬 Chat" else "🤖 Agent"
        Ui.setChipColor(chipMode, if (chat) Ui.BLUE else Ui.GREEN)
        chipModel?.text = "⚙ ${ModelPicker.shortLabel(this)}"
        chipIndicator?.text = Privacy.activeLabel(this).replace(" / Offline", "")
        chipLive?.text = if (liveMode) "🎙 Live ON" else "🎙 Live"
        Ui.setChipColor(chipLive, if (liveMode) Ui.GREEN else Ui.GREY)
        chipPause?.alpha = if (running && !AgentLoop.paused) 1f else 0.45f
        Ui.setChipColor(chipPause, if (AgentLoop.paused) Ui.BLUE else Ui.GREY)
        chipResume?.alpha = if (AgentLoop.paused || !running) 1f else 0.45f
        Ui.setChipColor(chipManual, if (Config.manualStep(this)) Ui.GREEN else Ui.GREY)
        val unl = Config.unlimited(this)
        chipLimit?.text = if (unl) "∞" else Config.maxSteps(this).toString()
        Ui.setChipColor(chipLimit, if (unl) Ui.GREEN else Ui.GREY)
    }
}
