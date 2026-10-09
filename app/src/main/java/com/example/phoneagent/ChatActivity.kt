package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ContentValues
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Offline chat: history, images aur files sab phone me. Model kabhi bhi badlo, baat wahin se chalti hai.
 * (Cloud model use karoge to sirf wahi message us provider ko jayega; local model par kuch bahar nahi jata.)
 */
class ChatActivity : Activity() {

    companion object {
        private const val REQ_PICK = 11
        private const val REQ_VOICE = 12
        private const val REQ_CAMERA = 13
        private val TEXT_EXT = setOf(
            "txt", "md", "csv", "json", "xml", "log", "kt", "java", "py", "js", "ts", "html", "css",
            "yaml", "yml", "ini", "sql", "sh", "c", "cpp", "h", "gradle", "kts", "toml", "properties"
        )
        private val TEXT_MIME = setOf(
            "application/json", "application/xml", "application/javascript", "application/x-sh"
        )
    }

    private lateinit var list: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var input: EditText
    private lateinit var sendBtn: TextView
    private lateinit var titleView: TextView
    private lateinit var modelChip: TextView
    private lateinit var ttsChip: TextView
    private lateinit var liveChip: TextView
    private lateinit var langChip: TextView
    private lateinit var statusView: TextView
    private lateinit var attachRow: LinearLayout
    private lateinit var modeView: TextView
    private lateinit var agentBar: LinearLayout
    private lateinit var agentInfo: TextView
    private var cameraUri: Uri? = null

    private val consentAsk: (String) -> Int = { askConsent(it) }
    private val stateListener: () -> Unit = {
        runOnUiThread {
            refreshAgentBar()
            refreshChips()
        }
    }

    private var conv = 0L
    private val pendingImages = ArrayList<String>()
    private val pendingFiles = ArrayList<Pair<String, String>>()
    private var live = false

    private val storeListener: (Long) -> Unit = { id ->
        if (id == 0L || id == conv) runOnUiThread { render() }
    }

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(8)
        val light = Color.parseColor("#E0E0E0")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        // ---- top bar ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        titleView = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.BLACK)
            maxLines = 1
            setPadding(dp(8), 0, dp(8), 0)
        }
        top.addView(Ui.chip(this, "☰ Chats") { showChats() })
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.chip(this, "⋮") { showMenu() })
        top.addView(Ui.chip(this, "+ Nayi") { newChat() }, marginLeft(dp(4)))

        modeView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.DKGRAY)
            setPadding(dp(16), dp(2), dp(16), 0)
        }

        // Agent Chat bar: chalte/adhure task ki halat + turant control (normal chat se bhi agent control hota hai)
        agentInfo = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.BLACK)
            maxLines = 3
        }
        agentBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = Ui.rounded(Color.parseColor("#E8F0FE"), dp(10))
            visibility = View.GONE
        }
        agentBar.addView(agentInfo, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        agentBar.addView(Ui.chip(this, "⏸") { AgentLoop.setPaused(true) }, marginLeft(dp(4)))
        agentBar.addView(Ui.chip(this, "▶") { resumeAgent() }, marginLeft(dp(4)))
        agentBar.addView(Ui.chip(this, "■", bg = Ui.RED) { AgentLoop.stop() }, marginLeft(dp(4)))

        // ---- chips row ----
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, 0)
        }
        modelChip = Ui.chip(this, "") {
            ModelPicker.show(this, false) { runOnUiThread { refreshChips() } }
        }
        ttsChip = Ui.chip(this, "") {
            Config.setTts(this, !Config.ttsOn(this))
            if (!Config.ttsOn(this)) Speaker.stop()
            refreshChips()
        }
        langChip = Ui.chip(this, "") {
            val i = Config.LANGS.indexOf(Config.voiceLang(this))
            Config.setVoiceLang(this, Config.LANGS[(i + 1) % Config.LANGS.size])
            refreshChips()
        }
        liveChip = Ui.chip(this, "") {
            if (!live) {
                live = true
                Config.setTts(this, true)
                refreshChips()
                listen()
            } else {
                live = false
                Speaker.stop()
                refreshChips()
            }
        }
        for (c in listOf(modelChip, ttsChip, langChip, liveChip)) {
            chips.addView(c, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }

        // ---- messages ----
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        scroll = ScrollView(this).apply { addView(list) }

        statusView = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(dp(12), 0, dp(12), 0)
        }

        attachRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, 0, pad, 0)
            visibility = View.GONE
        }

        // ---- input ----
        input = EditText(this).apply {
            hint = "Message likho..."
            textSize = 15f
            maxLines = 5
            setTextColor(Color.BLACK)
        }
        sendBtn = Ui.chip(this, "Bhejo", bg = Ui.BLUE, size = 14f) { send() }
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        inputRow.addView(Ui.chip(this, "📎", bg = light, fg = Color.BLACK, size = 18f) { pick() })
        inputRow.addView(input, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        inputRow.addView(Ui.chip(this, "🎤", bg = light, fg = Color.BLACK, size = 18f) {
            live = false
            listen()
        }, marginLeft(dp(4)))
        inputRow.addView(sendBtn, marginLeft(dp(4)))

        root.addView(top)
        root.addView(modeView)
        root.addView(chips)
        root.addView(agentBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(8), dp(6), dp(8), 0) })
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(statusView)
        root.addView(attachRow)
        root.addView(inputRow)
        setContentView(root)
    }

    private fun marginLeft(m: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { leftMargin = m }

    override fun onResume() {
        super.onResume()
        conv = ChatStore.ensureActive(this)
        ChatStore.addListener(storeListener)
        AgentState.addListener(stateListener)
        Privacy.activityAsk = consentAsk
        refreshChips()
        refreshAgentBar()
        refreshAttach()
        render()
        setBusy(ChatEngine.busy)
    }

    override fun onPause() {
        super.onPause()
        ChatStore.removeListener(storeListener)
        AgentState.removeListener(stateListener)
        if (Privacy.activityAsk === consentAsk) Privacy.activityAsk = null
    }

    override fun onDestroy() {
        super.onDestroy()
        Speaker.stop()
    }

    // ---------- UI state ----------

    private fun refreshChips() {
        modelChip.text = "⚙ ${ModelPicker.shortLabel(this)}"
        val tts = Config.ttsOn(this)
        ttsChip.text = if (tts) "🔊 ON" else "🔇 OFF"
        Ui.setChipColor(ttsChip, if (tts) Ui.GREEN else Ui.GREY)
        langChip.text = Config.voiceLang(this)
        val dm = when (Config.dataMode(this)) {
            "local" -> "Sirf Local"
            "allow" -> "Online allowed"
            else -> "Online se pehle poochho"
        }
        modeView.text = "${Privacy.activeLabel(this)}  •  Data: $dm"
        Ui.setChipColor(modelChip, if (Config.pin(this) != null) Ui.BLUE else Ui.GREY)
        liveChip.text = if (live) "🎙 Live ON" else "🎙 Live"
        Ui.setChipColor(liveChip, if (live) Ui.GREEN else Ui.GREY)
    }

    private fun setBusy(on: Boolean) {
        sendBtn.text = if (on) "■ Roko" else "Bhejo"
        Ui.setChipColor(sendBtn, if (on) Ui.RED else Ui.BLUE)
    }

    private fun refreshAttach() {
        attachRow.removeAllViews()
        val light = Color.parseColor("#E0E0E0")
        for (p in ArrayList(pendingImages)) {
            attachRow.addView(Ui.chip(this, "🖼 ✕", bg = light, fg = Color.BLACK) {
                pendingImages.remove(p)
                refreshAttach()
            }, marginLeft(dp(4)))
        }
        for (f in ArrayList(pendingFiles)) {
            attachRow.addView(Ui.chip(this, "📄 ${f.first.take(14)} ✕", bg = light, fg = Color.BLACK) {
                pendingFiles.remove(f)
                refreshAttach()
            }, marginLeft(dp(4)))
        }
        attachRow.visibility = if (attachRow.childCount == 0) View.GONE else View.VISIBLE
    }

    private fun render() {
        titleView.text = ChatStore.title(this, conv)
        list.removeAllViews()
        for (m in ChatStore.messages(this, conv, 200)) addBubble(m)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun thumb(path: String): Bitmap? = try {
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = 4 })
    } catch (e: Exception) {
        null
    }

    private fun addBubble(m: Msg) {
        val isUser = m.role == "user"
        val isErr = m.role == "error"
        val bg = when {
            isUser -> "#D2E3FC"
            isErr -> "#FCE8E6"
            else -> "#F1F3F4"
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = Ui.rounded(Color.parseColor(bg), dp(16))
        }
        for (p in m.images) {
            val bmp = thumb(p) ?: continue
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageBitmap(bmp)
            }
            box.addView(iv, LinearLayout.LayoutParams(dp(200), dp(200)).apply { bottomMargin = dp(6) })
        }
        if (m.files.isNotEmpty()) {
            box.addView(TextView(this).apply {
                text = "📄 " + m.files.joinToString(", ")
                textSize = 12f
                setTextColor(Color.DKGRAY)
            })
        }
        if (m.text.isNotBlank()) {
            box.addView(TextView(this).apply {
                text = m.text
                textSize = 15f
                setTextColor(if (isErr) Color.parseColor("#B3261E") else Color.BLACK)
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
                setTextIsSelectable(true)
            })
        }
        if (m.role == "assistant") {
            val light = Color.parseColor("#E0E0E0")
            val foot = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            foot.addView(TextView(this).apply {
                text = m.model
                textSize = 10f
                setTextColor(Color.GRAY)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(Ui.chip(this, "🔊", bg = light, fg = Color.BLACK) {
                Speaker.speak(this, m.text)
            })
            foot.addView(Ui.chip(this, "Copy", bg = light, fg = Color.BLACK) {
                copyText(m.text)
            }, marginLeft(dp(4)))
            box.addView(foot)
        }
        val row = LinearLayout(this).apply {
            gravity = if (isUser) Gravity.END else Gravity.START
            setPadding(0, dp(4), 0, dp(4))
        }
        row.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    private fun copyText(s: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("LoRA", s))
        toast("Copy ho gaya")
    }

    // ---------- send / voice ----------

    private fun send() {
        if (ChatEngine.busy) {
            ChatEngine.stop()
            return
        }
        val t = input.text.toString().trim()
        if (t.isEmpty() && pendingImages.isEmpty() && pendingFiles.isEmpty()) return
        if (Config.load(this).isEmpty()) {
            toast("Pehle App me provider ya local model jodo")
            return
        }
        val imgs = ArrayList(pendingImages)
        val files = ArrayList(pendingFiles)
        pendingImages.clear()
        pendingFiles.clear()
        input.setText("")
        refreshAttach()
        setBusy(true)
        ChatEngine.send(this, conv, t, imgs, files, { s -> runOnUiThread { statusView.text = s } }) { reply, _ ->
            runOnUiThread {
                setBusy(false)
                statusView.text = ""
                afterReply(reply)
            }
        }
    }

    private fun afterReply(reply: String?) {
        if (isFinishing) return
        if (reply == null) {
            live = false
        } else if (Config.ttsOn(this)) {
            Speaker.speak(this, reply) { if (live && !isFinishing) listen() }
        } else if (live) {
            listen()
        }
        refreshChips()
    }

    private fun listen() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.voiceLang(this))
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo...")
        try {
            startActivityForResult(i, REQ_VOICE)
        } catch (e: Exception) {
            live = false
            refreshChips()
            toast("Is phone me voice input (Google app) nahi mila")
        }
    }

    // ---------- attach ----------

    private fun pick() {
        AlertDialog.Builder(this)
            .setTitle("Attach")
            .setItems(arrayOf("Image / File (PDF, DOCX, XLSX, PPTX, TXT...)", "Camera se photo")) { _, i ->
                if (i == 0) pickFile() else camera()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun camera() {
        if (Build.VERSION.SDK_INT < 29) {
            toast("Camera attach Android 10+ par chalta hai. Gallery se photo chuno.")
            return
        }
        val cv = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lora_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LoRA")
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
        if (uri == null) {
            toast("Photo ke liye jagah nahi bani")
            return
        }
        cameraUri = uri
        try {
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), REQ_CAMERA)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            toast("Camera app nahi mila")
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try {
            startActivityForResult(i, REQ_PICK)
        } catch (e: Exception) {
            toast("File picker nahi khul paya")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VOICE -> {
                val text = if (resultCode == RESULT_OK) {
                    data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                } else null
                if (text.isNullOrBlank()) {
                    live = false
                    refreshChips()
                } else {
                    input.setText(text)
                    if (live) send() else input.setSelection(input.text.length)
                }
            }
            REQ_PICK -> if (resultCode == RESULT_OK && data != null) handlePicked(data)
            REQ_CAMERA -> {
                val u = cameraUri
                if (u != null) {
                    if (resultCode == RESULT_OK) thread(name = "attach-cam") { processUri(u) }
                    else contentResolver.delete(u, null, null)
                }
            }
        }
    }

    private fun handlePicked(data: Intent) {
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri)
        } else {
            data.data?.let { uris.add(it) }
        }
        thread(name = "attach") {
            for (u in uris) processUri(u)
        }
    }

    private fun isTextLike(mime: String, ext: String): Boolean =
        mime.startsWith("text/") || mime in TEXT_MIME || ext in TEXT_EXT

    private fun processUri(uri: Uri) {
        val name = displayName(uri)
        val mime = contentResolver.getType(uri) ?: ""
        val ext = name.substringAfterLast('.', "").lowercase()
        when {
            mime.startsWith("image/") -> {
                val path = saveImage(uri)
                if (path != null) runOnUiThread {
                    pendingImages.add(path)
                    refreshAttach()
                } else runOnUiThread { toast("Image padh nahi payi: $name") }
            }
            isTextLike(mime, ext) -> {
                val txt = readText(uri)
                if (txt != null) runOnUiThread {
                    pendingFiles.add(Pair(name, txt))
                    refreshAttach()
                } else runOnUiThread { toast("File padh nahi payi: $name") }
            }
            else -> {
                val r = Docs.extract(this, uri, name, mime)
                val txt = r.text
                if (txt != null) runOnUiThread {
                    pendingFiles.add(Pair(name, txt))
                    refreshAttach()
                } else if (r.images.isNotEmpty()) runOnUiThread {
                    pendingImages.addAll(r.images)
                    refreshAttach()
                    toast(r.note ?: "PDF pages jode")
                } else runOnUiThread { toast(r.note ?: "$name padh nahi payi") }
            }
        }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (i >= 0 && c.moveToFirst()) return c.getString(i) ?: "file"
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "file"
    }

    private fun readText(uri: Uri): String? {
        return try {
            contentResolver.openInputStream(uri)?.use { s ->
                val r = s.bufferedReader()
                val sb = StringBuilder()
                val buf = CharArray(4096)
                while (sb.length < 20000) {
                    val n = r.read(buf)
                    if (n < 0) break
                    sb.append(buf, 0, n)
                }
                val cut = sb.length >= 20000
                sb.toString().take(20000) + if (cut) "\n[...file lambi thi, aage ka hissa kaat diya]" else ""
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Image ko max 1280px me chhota karke JPEG me phone ke private folder me save karta hai. */
    private fun saveImage(uri: Uri): String? {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1280) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            val orientation = try {
                contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) {
                ExifInterface.ORIENTATION_NORMAL
            }
            val deg = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            val maxSide = maxOf(bmp.width, bmp.height)
            if (maxSide > 1280) {
                val s = 1280f / maxSide
                bmp = Bitmap.createScaledBitmap(
                    bmp, (bmp.width * s).toInt().coerceAtLeast(1), (bmp.height * s).toInt().coerceAtLeast(1), true
                )
            }
            if (deg != 0f) {
                val mx = Matrix().apply { postRotate(deg) }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, mx, true)
            }
            val f = File(ChatStore.mediaDir(this), "img_${System.currentTimeMillis()}_${(0..999).random()}.jpg")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            return f.absolutePath
        } catch (e: Exception) {
            return null
        }
    }

    // ---------- agent bar / consent ----------

    private fun refreshAgentBar() {
        val tk = TaskStore.unfinished(this)
        if (tk == null && !AgentLoop.isRunning()) {
            agentBar.visibility = View.GONE
            return
        }
        agentBar.visibility = View.VISIBLE
        val t = tk ?: TaskStore.get(this, AgentLoop.taskId)
        val status = if (AgentLoop.isRunning()) (if (AgentLoop.paused) "Paused" else "Running") else (t?.status ?: "")
        agentInfo.text = "🤖 Agent Chat · Task: ${t?.goal?.take(40) ?: "-"}\nStep ${t?.step ?: 0}/${AgentLoop.totalLabel(this)} · " +
            "${ModelPicker.shortLabel(this)} · $status"
    }

    private fun resumeAgent() {
        if (AgentLoop.isRunning()) {
            AgentLoop.setPaused(false)
            return
        }
        val svc = AgentAccessibilityService.instance
        val tk = TaskStore.unfinished(this)
        if (svc == null) toast("Pehle Accessibility me LoRA on karo")
        else if (tk == null) toast("Koi adhura task nahi")
        else AgentLoop.resumeTask(svc, tk.id, null)
    }

    /** Online model ko data bhejne se pehle permission (background thread se bulaya jata hai). */
    private fun askConsent(msg: String): Int {
        val latch = CountDownLatch(1)
        val res = AtomicInteger(2)
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("Online AI ko data bhejna?")
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton("Ab allow (30 min)") { _, _ -> res.set(0); latch.countDown() }
                .setNeutralButton("Is session") { _, _ -> res.set(1); latch.countDown() }
                .setNegativeButton("Mana") { _, _ -> res.set(2); latch.countDown() }
                .show()
        }
        latch.await(120, TimeUnit.SECONDS)
        return res.get()
    }

    // ---------- chats list / menu ----------

    private fun newChat() {
        val id = ChatStore.newConv(this)
        Config.setActiveChat(this, id)
        conv = id
        render()
    }

    private fun openConv(id: Long) {
        Config.setActiveChat(this, id)
        conv = id
        render()
    }

    private fun showChats() {
        val cs = ChatStore.convs(this)
        AlertDialog.Builder(this)
            .setTitle("Meri chats")
            .setItems(cs.map { it.title }.toTypedArray()) { _, i -> openConv(cs[i].id) }
            .setPositiveButton("+ Nayi chat") { _, _ -> newChat() }
            .setNegativeButton("Band", null)
            .show()
    }

    private fun showMenu() {
        val items = arrayOf("Naam badlo", "Is chat ko hatao", "Poori chat copy karo")
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> rename()
                    1 -> confirmDelete()
                    2 -> copyText(
                        ChatStore.messages(this, conv, 100000).joinToString("\n\n") {
                            (if (it.role == "user") "Tum: " else "AI: ") + it.text
                        }
                    )
                    else -> Unit
                }
            }
            .show()
    }

    private fun rename() {
        val et = EditText(this).apply {
            setText(ChatStore.title(this@ChatActivity, conv))
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Chat ka naam")
            .setView(et)
            .setPositiveButton("Save") { _, _ ->
                val t = et.text.toString().trim()
                if (t.isNotEmpty()) ChatStore.rename(this, conv, t)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("Ye chat hatani hai?")
            .setMessage("Is chat ke saare messages aur images phone se hamesha ke liye hat jayenge.")
            .setPositiveButton("Hatao") { _, _ ->
                ChatStore.deleteConv(this, conv)
                conv = ChatStore.ensureActive(this)
                render()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
