package com.example.phoneagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.AlertDialog
import android.content.Context
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
import android.os.SystemClock
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Haath + aankhein: screen padhta hai, tap/type/scroll karta hai.
 * Saath me floating UI: draggable status icon, hamesha-available Stop button aur
 * asli floating mini window (move/resize/collapse, position yaad rehti hai).
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: AgentAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    // ---- overlay views ----
    private var bubble: TextView? = null
    private var stopChip: TextView? = null
    private var panel: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelBody: LinearLayout? = null
    private var panelTitle: TextView? = null
    private var panelMeta: TextView? = null
    private var panelStatus: TextView? = null
    private var panelFeed: TextView? = null
    private var panelScroll: ScrollView? = null
    private var panelInput: EditText? = null
    private var panelPause: TextView? = null
    private var panelCollapseBtn: TextView? = null
    private var micChip: TextView? = null
    private var listening = false

    private var collapsed = false
    private var expandedHeight = 0
    private var typing = false
    private var typingPrevY = 0
    private var lastTapAt = 0L
    private var tapRunnable: Runnable? = null
    private var dialog: AlertDialog? = null

    private val logListener: (String) -> Unit = { line -> main.post { appendPanelLog(line) } }
    private val stateListener: () -> Unit = { main.post { refreshStatusUi() } }
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Config.KEY_BUBBLE) main.post { applyBubblePref() }
    }

    private val F_NOT_FOCUSABLE = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
    private val F_NOT_MODAL = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    private val F_NOT_TOUCHABLE = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
    private val F_WATCH_OUTSIDE = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH

    override fun onServiceConnected() {
        instance = this
        Boot.init(this)
        AgentLog.addListener(logListener)
        AgentState.addListener(stateListener)
        Config.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        applyBubblePref()
        val st = TaskStore.unfinished()
        if (st != null && !AgentLoop.isRunning()) {
            AgentLog.add("Agent: Adhoora task mila (Step ${st.step}): ${st.goal.take(60)}. Resume dabao.")
        }
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
    private fun screenW() = resources.displayMetrics.widthPixels
    private fun screenH() = resources.displayMetrics.heightPixels

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // ------------------------------------------------------------------
    // Aankhein: screen -> compact text
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Haath: actions
    // ------------------------------------------------------------------

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
        setFlag(panel, F_NOT_TOUCHABLE, on)
        setFlag(bubble, F_NOT_TOUCHABLE, on)
        setFlag(stopChip, F_NOT_TOUCHABLE, on)
    }

    private fun setFlag(v: View?, flag: Int, on: Boolean) {
        val view = v ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val nf = if (on) lp.flags or flag else lp.flags and flag.inv()
        if (nf != lp.flags) {
            lp.flags = nf
            try {
                wm().updateViewLayout(view, lp)
            } catch (_: Exception) {
            }
        }
    }

    // ------------------------------------------------------------------
    // Dialogs (permission, loop warning, model switch...)
    // ------------------------------------------------------------------

    /** Non-blocking: options ka button dabne par onResult(index) chalta hai. Fail ho to onResult(-1). */
    fun showChoices(title: String, message: String, options: List<String>, onResult: (Int) -> Unit) {
        main.post {
            try {
                val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                val b = AlertDialog.Builder(ctx).setTitle(title).setCancelable(false)
                if (options.size > 3) {
                    // 4+ options: message ke neeche button list
                    val box = LinearLayout(ctx)
                    box.orientation = LinearLayout.VERTICAL
                    box.setPadding(dp(20), dp(8), dp(20), dp(8))
                    val msg = TextView(ctx)
                    msg.text = message
                    msg.textSize = 14f
                    box.addView(msg)
                    val holder = arrayOfNulls<AlertDialog>(1)
                    for ((i, o) in options.withIndex()) {
                        val bt = Button(ctx)
                        bt.text = o
                        bt.setAllCaps(false)
                        bt.setOnClickListener {
                            holder[0]?.dismiss()
                            onResult(i)
                        }
                        box.addView(bt)
                    }
                    b.setView(box)
                    val d = b.create()
                    holder[0] = d
                    d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    d.show()
                    dialog = d
                } else {
                    b.setMessage(message)
                    b.setPositiveButton(options[0]) { _, _ -> onResult(0) }
                    if (options.size == 2) {
                        b.setNegativeButton(options[1]) { _, _ -> onResult(1) }
                    } else if (options.size == 3) {
                        b.setNeutralButton(options[1]) { _, _ -> onResult(1) }
                        b.setNegativeButton(options[2]) { _, _ -> onResult(2) }
                    }
                    val d = b.create()
                    d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    d.show()
                    dialog = d
                }
            } catch (e: Exception) {
                onResult(-1)
            }
        }
    }

    private fun dismissDialog() {
        try {
            dialog?.dismiss()
        } catch (_: Exception) {
        }
        dialog = null
    }

    /** Blocking (worker thread se): chuna hua index, ya timeout/Stop par -1. */
    fun choose(title: String, message: String, options: List<String>, timeoutSec: Long = 90): Int {
        val latch = CountDownLatch(1)
        val result = AtomicInteger(-1)
        showChoices(title, message, options) {
            result.set(it)
            latch.countDown()
        }
        var waited = 0L
        while (waited < timeoutSec * 1000L) {
            if (latch.await(250, TimeUnit.MILLISECONDS)) return result.get()
            if (AgentLoop.cancelRequested()) {
                main.post { dismissDialog() }
                return -1
            }
            waited += 250L
        }
        main.post { dismissDialog() }
        return -1
    }

    /** Risky action se pehle user se poochta hai. 60 sec me jawab na aaye to Deny. */
    fun confirm(message: String): Boolean =
        choose("Agent ko permission do?", message, listOf("Allow", "Deny"), 60) == 0

    /** Task poora hone par agent band nahi hota: user se poochta hai ki aage kya karna hai. */
    fun showCompletion(msg: String) {
        val body = (if (msg.isBlank()) "" else msg + "\n\n") + "Task completed. Aur kya karna hai?"
        showChoices(
            "Task completed",
            body,
            listOf("Continue", "Run Again", "Create Skill / Save Workflow", "Review", "Close")
        ) { c ->
            when (c) {
                0 -> {
                    showPanel(true)
                    AgentLog.add("Agent: Task poora hua. Aur kya karna hai? Neeche likho.")
                }
                1 -> AgentLoop.restart(this)
                2 -> saveSkillFromTask(msg)
                3 -> reviewTask(msg)
                else -> {}
            }
        }
    }

    private fun saveSkillFromTask(msg: String) {
        val st = TaskStore.current
        if (st == null) {
            Toast.makeText(this, "Skill ke liye koi task nahi hai", Toast.LENGTH_LONG).show()
            return
        }
        promptText("Skill ka naam", st.goal.take(40)) { name ->
            SkillStore.add(this, name, st.goal, SkillStore.stepsFromTask(st))
            AgentLog.add("Agent: Skill '$name' save ho gayi. More → Skills me se dobara chala sakte ho.")
            VoiceOut.say("Skill save ho gayi.", true)
        }
    }

    /** Poore hue task ka summary dikhata hai, phir wahi completion dialog wapas aata hai. */
    private fun reviewTask(msg: String) {
        val st = TaskStore.current
        val text = if (st == null) "(koi task nahi)" else {
            val hist = TaskStore.sync { st.history.takeLast(30) }
            "Goal: ${st.goal}\nSteps: ${st.step}\n\n" + hist.joinToString("\n")
        }
        showChoices("Review", text.take(2500), listOf("Back")) { showCompletion(msg) }
    }

    private fun promptText(title: String, initial: String, onOk: (String) -> Unit) {
        main.post {
            try {
                val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                val input = EditText(ctx).apply {
                    setText(initial)
                    inputType = InputType.TYPE_CLASS_TEXT
                    setSelection(initial.length)
                }
                val d = AlertDialog.Builder(ctx).setTitle(title).setView(input)
                    .setPositiveButton("OK") { _, _ ->
                        val t = input.text.toString().trim()
                        if (t.isNotEmpty()) onOk(t)
                    }
                    .setNegativeButton("Cancel", null)
                    .create()
                d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                d.window?.clearFlags(
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
                )
                d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
                d.show()
            } catch (_: Exception) {
            }
        }
    }

    /** Floating panel se model badalna. Task state same rehta hai (reset nahi). */
    private fun showModelDialog() {
        thread(name = "model-list") {
            val providers = Config.load(this)
            if (providers.isEmpty()) {
                main.post { Toast.makeText(this, "Pehle app me provider jodo", Toast.LENGTH_LONG).show() }
                return@thread
            }
            Pool.set(providers)
            val cands = Pool.candidates()
            main.post {
                if (cands.isEmpty()) {
                    Toast.makeText(this, "Koi model nahi mila", Toast.LENGTH_LONG).show()
                    return@post
                }
                val cur = Config.pinned(this)
                val items = ArrayList<String>()
                items.add((if (cur == null) "● " else "○ ") + "Auto (pehla available)")
                for (c in cands) {
                    items.add(
                        (if (c.id == cur) "● " else "○ ") + c.label + "  " + c.caps()
                    )
                }
                try {
                    val ctx = ContextThemeWrapper(this, android.R.style.Theme_Material_Light_Dialog_Alert)
                    val d = AlertDialog.Builder(ctx).setTitle("Model chuno")
                        .setItems(items.toTypedArray()) { _, which ->
                            val chosen: Cand? = if (which == 0) null else cands[which - 1]
                            switchModelWithConfirm(chosen)
                        }
                        .setNegativeButton("Cancel", null)
                        .create()
                    d.window?.setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                    d.show()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun switchModelWithConfirm(chosen: Cand?) {
        if (AgentLoop.isRunning()) {
            val step = (TaskStore.current?.step ?: 0) + 1
            showChoices(
                "Model badlein?",
                "Model badalne se task state same rahega. Naya model Step $step se continue karega.",
                listOf("Switch", "Cancel")
            ) { r ->
                if (r == 0) AgentLoop.applyModelSwitch(this, chosen)
            }
        } else {
            AgentLoop.applyModelSwitch(this, chosen)
        }
    }

    // ------------------------------------------------------------------
    // Floating helpers
    // ------------------------------------------------------------------

    private fun overlayLp(w: Int, h: Int, flags: Int): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

    /** handle ko pakadkar v (window) ko drag karta hai. Bina hile touch = onTap. */
    private fun attachDrag(
        v: View,
        lp: WindowManager.LayoutParams,
        handle: View,
        onTap: () -> Unit,
        onSave: () -> Unit
    ) {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false
        handle.setOnTouchListener { _, e ->
            when (e.action) {
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
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        lp.x = (sx + dx).coerceIn(0, max(0, screenW() - v.width))
                        lp.y = (sy + dy).coerceIn(0, max(0, screenH() - v.height))
                        try {
                            wm().updateViewLayout(v, lp)
                        } catch (_: Exception) {
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (moved) onSave() else onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun chip(label: String, bg: Int = Color.parseColor("#3C4043"), onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(8), dp(6), dp(8))
            background = rounded(bg, dp(8))
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        for (v in views) {
            val p = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            p.setMargins(dp(2), dp(3), dp(2), 0)
            r.addView(v, p)
        }
        return r
    }

    // ------------------------------------------------------------------
    // Hamesha-available Stop button (draggable)
    // ------------------------------------------------------------------

    fun showStopButton() {
        main.post {
            if (stopChip != null) return@post
            val size = dp(46)
            val tv = TextView(this).apply {
                text = "■"
                setTextColor(Color.WHITE)
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = circle(Color.parseColor("#D93025"))
                alpha = 0.95f
            }
            val lp = overlayLp(size, size, F_NOT_FOCUSABLE or F_NOT_MODAL)
            lp.x = Config.geti(this, "sx", screenW() - size - dp(6))
            lp.y = Config.geti(this, "sy", dp(200))
            attachDrag(
                tv, lp, tv,
                onTap = { AgentLoop.stop() },
                onSave = {
                    Config.puti(this, "sx", lp.x)
                    Config.puti(this, "sy", lp.y)
                }
            )
            try {
                wm().addView(tv, lp)
                stopChip = tv
            } catch (_: Exception) {
            }
        }
    }

    fun hideStopButton() {
        main.post { removeStopNow() }
    }

    private fun removeStopNow() {
        stopChip?.let {
            try {
                wm().removeView(it)
            } catch (_: Exception) {
            }
        }
        stopChip = null
    }

    // ------------------------------------------------------------------
    // Floating status icon (bubble)
    // ------------------------------------------------------------------

    private fun statusColor(): Int = when (AgentState.status) {
        Status.IDLE -> Color.parseColor("#5F6368")
        Status.RUNNING -> Color.parseColor("#1E8E3E")
        Status.WAITING -> Color.parseColor("#F9AB00")
        Status.PAUSED -> Color.parseColor("#1A73E8")
        Status.ERROR -> Color.parseColor("#D93025")
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
        val size = dp(52)
        val tv = TextView(this).apply {
            text = "AI"
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = circle(statusColor())
            alpha = 0.92f
        }
        val lp = overlayLp(size, size, F_NOT_FOCUSABLE or F_NOT_MODAL)
        lp.x = Config.geti(this, "bx", 0)
        lp.y = Config.geti(this, "by", dp(300))

        attachDrag(
            tv, lp, tv,
            onTap = {
                // Single tap: mini panel. Double tap: Agent Chat (input focus ke saath).
                val now = SystemClock.uptimeMillis()
                if (now - lastTapAt < 320L) {
                    tapRunnable?.let { main.removeCallbacks(it) }
                    lastTapAt = 0L
                    showPanel(true)
                } else {
                    lastTapAt = now
                    val r = Runnable { showPanel(false) }
                    tapRunnable = r
                    main.postDelayed(r, 330L)
                }
            },
            onSave = {
                Config.puti(this, "bx", lp.x)
                Config.puti(this, "by", lp.y)
            }
        )
        try {
            wm().addView(tv, lp)
            bubble = tv
            refreshStatusUi()
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

    // ------------------------------------------------------------------
    // Floating mini window
    // ------------------------------------------------------------------

    fun openPanel() {
        main.post { showPanel(true) }
    }

    private fun showPanel(focusInput: Boolean) {
        if (panel != null) {
            if (focusInput) enterTyping()
            return
        }
        val pad = dp(8)
        val w = Config.geti(this, "pw", dp(300)).coerceIn(dp(240), screenW())
        val h = Config.geti(this, "ph", dp(360)).coerceIn(dp(200), screenH())
        collapsed = Config.getb(this, "pcol", false)
        expandedHeight = h

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)
        root.background = rounded(Color.parseColor("#F2202124"), dp(16))

        // ---- header (drag handle) ----
        val header = LinearLayout(this)
        header.orientation = LinearLayout.HORIZONTAL
        header.gravity = Gravity.CENTER_VERTICAL
        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            text = "🤖 Agent"
        }
        val collapseBtn = chip(if (collapsed) "▢" else "▁") { toggleCollapse() }
        val closeBtn = chip("✕") { hidePanel() }
        val voiceBtn = chip(voiceIcon()) { }
        voiceBtn.setOnClickListener { voiceBtn.text = cycleVoiceMode() }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(voiceBtn)
        header.addView(collapseBtn)
        header.addView(closeBtn)

        // ---- body ----
        val body = LinearLayout(this)
        body.orientation = LinearLayout.VERTICAL

        val meta = TextView(this).apply {
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 11f
            maxLines = 2
        }
        val status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            maxLines = 2
        }
        val feed = TextView(this).apply {
            setTextColor(Color.parseColor("#E8EAED"))
            textSize = 11.5f
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val scroll = ScrollView(this)
        scroll.addView(feed)

        val pauseChip = chip("⏸ Pause") { togglePause() }
        val skipChip = chip("⏭ Skip") { AgentLoop.skip() }
        val nextChip = chip("▷ Next") { AgentLoop.next() }
        val stopBtn = chip("■ Stop", Color.parseColor("#B3261E")) { AgentLoop.stop() }
        val cpChip = chip("⚑ CP") { AgentLoop.manualCheckpoint() }
        val retryChip = chip("↻ Retry") { AgentLoop.retry() }
        val addChip = chip("＋ Step") {
            promptText("Naya step (agla chalega)", "") { AgentLoop.addStep(it, true) }
        }
        val modelChip = chip("Model") { showModelDialog() }
        val mic = chip("🎤 Voice") { toggleMic() }
        val backChip = chip("↩ Back") { AgentLoop.goBack() }
        val chatChip = chip("💬 Chat") { openActivity(ChatActivity::class.java) }
        val appChip = chip("⛶ App") { openActivity(MainActivity::class.java) }

        val input = EditText(this).apply {
            hint = "Agent ko likho / naya goal"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            textSize = 13f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT
        }
        input.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_DOWN) enterTyping()
            false
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendFromPanel()
                true
            } else {
                false
            }
        }
        val sendChip = chip("Bhejo", Color.parseColor("#1A73E8")) { sendFromPanel() }
        val inputRow = LinearLayout(this)
        inputRow.orientation = LinearLayout.HORIZONTAL
        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(sendChip)

        val grip = TextView(this).apply {
            text = "◢"
            setTextColor(Color.parseColor("#9AA0A6"))
            textSize = 16f
            gravity = Gravity.END
            setPadding(dp(16), dp(4), dp(4), 0)
        }

        body.addView(meta)
        body.addView(status)
        body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.addView(row(pauseChip, skipChip, nextChip, stopBtn))
        body.addView(row(cpChip, retryChip, addChip, modelChip))
        body.addView(row(mic, backChip, chatChip, appChip))
        body.addView(inputRow)
        body.addView(grip, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        root.addView(header)
        root.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        body.visibility = if (collapsed) View.GONE else View.VISIBLE

        val lp = overlayLp(w, if (collapsed) ViewGroup.LayoutParams.WRAP_CONTENT else h,
            F_NOT_FOCUSABLE or F_NOT_MODAL or F_WATCH_OUTSIDE)
        lp.x = Config.geti(this, "px", (screenW() - w) / 2).coerceIn(0, max(0, screenW() - w))
        lp.y = Config.geti(this, "py", dp(120)).coerceIn(0, max(0, screenH() - dp(60)))
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING

        attachDrag(
            root, lp, header,
            onTap = {},
            onSave = {
                Config.puti(this, "px", lp.x)
                Config.puti(this, "py", lp.y)
            }
        )

        // Resize grip: neeche-daayein kone se kheencho
        var rw = 0
        var rh = 0
        var rtx = 0f
        var rty = 0f
        grip.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    rw = lp.width
                    rh = lp.height
                    rtx = e.rawX
                    rty = e.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.width = (rw + (e.rawX - rtx).toInt()).coerceIn(dp(240), screenW())
                    lp.height = (rh + (e.rawY - rty).toInt()).coerceIn(dp(200), screenH())
                    try {
                        wm().updateViewLayout(root, lp)
                    } catch (_: Exception) {
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    expandedHeight = lp.height
                    Config.puti(this, "pw", lp.width)
                    Config.puti(this, "ph", lp.height)
                    true
                }
                else -> false
            }
        }

        // Panel ke bahar touch: keyboard band, focus app ko wapas
        root.setOnTouchListener { _, e ->
            if (e.action == MotionEvent.ACTION_OUTSIDE && typing) leaveTyping()
            false
        }

        try {
            wm().addView(root, lp)
        } catch (_: Exception) {
            return
        }
        bubble?.visibility = View.GONE
        panel = root
        panelLp = lp
        panelBody = body
        panelTitle = title
        panelMeta = meta
        panelStatus = status
        panelFeed = feed
        panelScroll = scroll
        panelInput = input
        panelPause = pauseChip
        panelCollapseBtn = collapseBtn
        micChip = mic
        feed.text = AgentLog.lines.takeLast(60).joinToString("\n")
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        refreshStatusUi()
        if (focusInput && !collapsed) enterTyping()
    }

    private fun togglePause() {
        when {
            !AgentLoop.isRunning() -> AgentLoop.resume()
            AgentLoop.isPaused() -> AgentLoop.resume()
            else -> AgentLoop.pause()
        }
    }

    private fun toggleCollapse() {
        val p = panel ?: return
        val lp = panelLp ?: return
        leaveTyping()
        collapsed = !collapsed
        panelBody?.visibility = if (collapsed) View.GONE else View.VISIBLE
        lp.height = if (collapsed) ViewGroup.LayoutParams.WRAP_CONTENT else max(expandedHeight, dp(200))
        panelCollapseBtn?.text = if (collapsed) "▢" else "▁"
        Config.putb(this, "pcol", collapsed)
        try {
            wm().updateViewLayout(p, lp)
        } catch (_: Exception) {
        }
    }

    private fun hidePanel() {
        leaveTyping()
        removePanelNow()
        bubble?.visibility = View.VISIBLE
    }

    // ---- voice ----

    private fun voiceIcon(): String = when (Config.voiceMode(this)) {
        "silent" -> "🔇"
        "every" -> "🔊"
        else -> "🔔"
    }

    /** silent -> important -> every -> silent. Naya icon lautata hai. */
    private fun cycleVoiceMode(): String {
        val next = when (Config.voiceMode(this)) {
            "silent" -> "important"
            "important" -> "every"
            else -> "silent"
        }
        Config.setVoiceMode(this, next)
        if (next == "silent") VoiceOut.stop()
        val label = when (next) {
            "silent" -> "Voice band (silent)"
            "every" -> "Voice: har step bolunga"
            else -> "Voice: sirf zaroori baatein"
        }
        AgentLog.add("Agent: $label")
        Toast.makeText(this, label, Toast.LENGTH_SHORT).show()
        return voiceIcon()
    }

    private fun setListening(on: Boolean) {
        listening = on
        micChip?.text = if (on) "⏹ Sun raha" else "🎤 Voice"
        if (!on) refreshStatusUi()
    }

    private fun toggleMic() {
        if (listening) {
            VoiceIn.stop()
            setListening(false)
            return
        }
        if (!VoiceIn.hasPermission(this)) {
            openVoiceActivity(permissionOnly = true)
            return
        }
        if (!VoiceIn.available(this)) {
            Toast.makeText(this, "Is phone par speech recognizer nahi mila", Toast.LENGTH_LONG).show()
            return
        }
        VoiceOut.stop()
        setListening(true)
        VoiceIn.listen(
            this,
            cbPartial = { t -> panelStatus?.text = "🎤 $t" },
            cbFinal = { t ->
                setListening(false)
                AgentChat.handle(t)
            },
            cbError = { code, msg ->
                setListening(false)
                if (code == 3 || code == 5 || code == 9 || code == -1) {
                    // Android background se mic rok sakta hai: foreground (transparent) activity se suno
                    AgentLog.add("Voice: background me mic nahi chala ($msg). Foreground se sun raha hu...")
                    openVoiceActivity(permissionOnly = false)
                } else {
                    AgentLog.add("Voice: $msg")
                }
            }
        )
    }

    private fun openVoiceActivity(permissionOnly: Boolean) {
        val i = Intent(this, VoiceActivity::class.java)
        i.putExtra("permission_only", permissionOnly)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (e: Exception) {
            Toast.makeText(this, "Voice screen nahi khul payi", Toast.LENGTH_LONG).show()
        }
    }

    private fun openActivity(cls: Class<*>) {
        val i = Intent(this, cls)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(i)
        } catch (_: Exception) {
        }
    }

    private fun removePanelNow() {
        typing = false
        if (listening) {
            VoiceIn.stop()
            listening = false
        }
        micChip = null
        panel?.let {
            try {
                wm().removeView(it)
            } catch (_: Exception) {
            }
        }
        panel = null
        panelLp = null
        panelBody = null
        panelTitle = null
        panelMeta = null
        panelStatus = null
        panelFeed = null
        panelScroll = null
        panelInput = null
        panelPause = null
        panelCollapseBtn = null
    }

    // ---- keyboard: panel normally focus nahi leta (neeche ka app chalta rehta hai) ----

    private fun enterTyping() {
        val p = panel ?: return
        val lp = panelLp ?: return
        if (!typing) {
            typing = true
            typingPrevY = lp.y
            lp.y = min(lp.y, dp(30))
        }
        lp.flags = lp.flags and F_NOT_FOCUSABLE.inv()
        try {
            wm().updateViewLayout(p, lp)
        } catch (_: Exception) {
        }
        main.postDelayed({
            val input = panelInput
            if (input != null) {
                input.requestFocus()
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }, 120L)
    }

    private fun leaveTyping() {
        if (!typing) return
        typing = false
        val p = panel
        val lp = panelLp
        val input = panelInput
        if (input != null) {
            input.clearFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input.windowToken, 0)
        }
        if (p != null && lp != null) {
            lp.flags = lp.flags or F_NOT_FOCUSABLE
            lp.y = typingPrevY
            try {
                wm().updateViewLayout(p, lp)
            } catch (_: Exception) {
            }
        }
    }

    private fun sendFromPanel() {
        val t = panelInput?.text?.toString()?.trim().orEmpty()
        if (t.isEmpty()) return
        panelInput?.setText("")
        leaveTyping()
        AgentChat.handle(t)
    }

    private fun appendPanelLog(line: String) {
        val feed = panelFeed ?: return
        feed.append(line + "\n")
        if (feed.length() > 12000) feed.text = feed.text.toString().takeLast(8000)
        panelScroll?.post { panelScroll?.fullScroll(View.FOCUS_DOWN) }
    }

    private fun refreshStatusUi() {
        val b = bubble
        if (b != null) {
            (b.background as? GradientDrawable)?.setColor(statusColor())
            b.text = if (AgentLoop.isRunning()) AgentState.step.toString() else "AI"
        }
        panelTitle?.text = "🤖 ${AgentState.stepText()} · ${AgentState.statusLabel()}"
        val model = if (AgentState.model.isEmpty()) "-" else AgentState.model
        panelMeta?.text = "${AgentState.modeLabel()} · $model\nCheckpoint: ${AgentState.checkpoint.ifEmpty { "-" }}"
        panelStatus?.text = AgentState.detail
        panelPause?.text = when {
            AgentLoop.isRunning() && !AgentLoop.isPaused() -> "⏸ Pause"
            else -> "▶ Resume"
        }
    }
}
