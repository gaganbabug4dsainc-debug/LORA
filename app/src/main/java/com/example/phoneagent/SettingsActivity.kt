package com.example.phoneagent

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/**
 * Saari settings ek jagah, sections me (tap karke kholo): Agent & steps, Privacy, Voice, Models,
 * Offline models (phone ke andar + Ollama server), Backup, Floating icon.
 */
class SettingsActivity : Activity() {

    private lateinit var page: LinearLayout
    private lateinit var providersBox: LinearLayout
    private lateinit var deviceBox: LinearLayout
    private var openKey = ""
    private var exportChats = false
    private var exportPass: String? = null

    private fun dp(v: Int) = Ui.dp(this, v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Pool.refresh(this)
        page = Ui.vbox(this)
        page.setPadding(dp(16), dp(28), dp(16), dp(24))
        val sv = ScrollView(this)
        sv.setBackgroundColor(Ui.BG)
        sv.addView(page, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(sv)
        build()
    }

    override fun onResume() {
        super.onResume()
        refreshProviders()
        refreshDevice()
    }

    override fun onBackPressed() {
        finish()
    }

    private fun build() {
        page.removeAllViews()
        val top = Ui.hbox(this)
        top.addView(Ui.title(this, "⚙ Settings"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(Ui.button(this, "✕ Band", "tonal") { finish() })
        page.addView(top)
        page.addView(Ui.note(this, "Section par tap karo. Badlav turant save hote hain."))

        add("agent", "🤖", "Agent aur steps", "Step limit, manual step, checkpoint", agentSection())
        add("privacy", "🔒", "Privacy / Data Processing", dataLabel(), privacySection())
        add("voice", "🔊", "Awaaz (Voice)", "Bolna, speed, volume, bhasha", voiceSection())
        add("models", "🧠", "Models aur Providers", "Cloud API keys, Model Manager", modelsSection())
        add("offline", "📴", "Offline models", "Phone ke andar ya Ollama server", offlineSection())
        add("backup", "💾", "Backup / Restore", "Settings + skills (keys kabhi nahi)", backupSection())
        add("float", "🫧", "Floating icon aur setup", "Accessibility, icon", floatSection())
        add("about", "ℹ️", "Log aur jaankari", "Version 0.6", aboutSection())
    }

    private fun add(key: String, emoji: String, name: String, hint: String, content: View) {
        page.addView(Ui.collapsible(this, emoji, name, hint, content, openKey == key), Ui.lp(this, top = 10))
    }

    private fun dataLabel(): String = when (Config.dataMode(this)) {
        "local" -> "Abhi: sirf Local"
        "allow" -> "Abhi: Online allowed"
        else -> "Abhi: Online se pehle poochho"
    }

    /** "Title: current ▾" jaisi tap-to-choose row. */
    private fun pickRow(title: String, current: String, options: List<String>, onPick: (Int) -> Unit): View {
        val r = Ui.hbox(this)
        r.setPadding(0, dp(10), 0, dp(10))
        r.addView(Ui.text(this, title, 14f, Ui.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        r.addView(Ui.pill(this, "$current ▾", Ui.PRIMARY_SOFT, Ui.PRIMARY))
        r.isClickable = true
        r.setOnClickListener {
            AlertDialog.Builder(this).setTitle(title)
                .setItems(options.toTypedArray()) { _, i -> onPick(i) }
                .setNegativeButton("Band", null).show()
        }
        return r
    }

    private fun rebuild(stay: String) {
        openKey = stay
        build()
    }

    // ---------------- agent ----------------

    private fun agentSection(): View {
        val box = Ui.vbox(this)
        val stepsField = EditText(this).apply {
            hint = "Custom steps"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Config.maxSteps(this@SettingsActivity).toString())
            isEnabled = !Config.unlimited(this@SettingsActivity)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val n = s?.toString()?.toIntOrNull() ?: return
                    if (n > 0) Config.setMaxSteps(this@SettingsActivity, n)
                }
            })
        }
        box.addView(Ui.switchRow(this, "♾ Unlimited steps", "Loop se bachne ki safety on rehti hai", Config.unlimited(this)) { on ->
            Config.setUnlimited(this, on)
            stepsField.isEnabled = !on
        })
        val quick = Ui.hbox(this)
        for ((i, n) in Config.STEP_CHOICES.withIndex()) {
            quick.addView(Ui.button(this, "$n", "tonal") {
                Config.setUnlimited(this, false)
                Config.setMaxSteps(this, n)
                stepsField.isEnabled = true
                stepsField.setText(n.toString())
            }, Ui.lp(this, weight = 1f, left = if (i == 0) 0 else 4))
        }
        box.addView(Ui.note(this, "Step limit (tap = limit set, unlimited band)"))
        box.addView(quick)
        box.addView(stepsField)
        box.addView(Ui.switchRow(this, "✋ Manual step", "Har step se pehle poochho (Chalao / Skip / Roko)", Config.manualStep(this)) {
            Config.setManualStep(this, it)
        })
        val cp = EditText(this).apply {
            hint = "Auto checkpoint har N step par (0 = band)"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(Config.autoCheckpoint(this@SettingsActivity).toString())
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val n = s?.toString()?.toIntOrNull() ?: return
                    Config.setAutoCheckpoint(this@SettingsActivity, n)
                }
            })
        }
        box.addView(cp)
        box.addView(Ui.note(this, "Risky action, app badalne aur model badalne se pehle checkpoint apne aap bhi banta hai."))
        return box
    }

    // ---------------- privacy ----------------

    private fun privacySection(): View {
        val box = Ui.vbox(this)
        val keys = listOf("local", "ask", "allow")
        val labels = listOf(
            "🟢 Sirf Local (kuch online nahi jayega)",
            "❓ Online se pehle poochho (default)",
            "🌐 Online allowed"
        )
        val cur = keys.indexOf(Config.dataMode(this)).coerceAtLeast(0)
        for ((i, l) in labels.withIndex()) {
            val sel = i == cur
            val row = Ui.text(this, (if (sel) "●  " else "○  ") + l, 14f, if (sel) Ui.PRIMARY else Ui.TEXT, sel).apply {
                setPadding(dp(4), dp(10), dp(4), dp(10))
                isClickable = true
                setOnClickListener {
                    Config.setDataMode(this@SettingsActivity, keys[i])
                    Privacy.session = false
                    rebuild("privacy")
                }
            }
            box.addView(row)
        }
        box.addView(Ui.note(this, "Local model hamesha pehle use hota hai. Chat history, images, files aur tasks phone me hi rehte hain; chat text encrypted hai. API keys Android Keystore se encrypted."))
        return box
    }

    // ---------------- voice ----------------

    private fun voiceSection(): View {
        val box = Ui.vbox(this)
        box.addView(Ui.switchRow(this, "Chat ke jawab bolkar sunao", "TTS", Config.ttsOn(this)) { Config.setTts(this, it) })
        val modes = listOf("silent", "important", "every")
        val modeLabels = listOf("🔇 Silent", "❗ Sirf zaruri", "🗣 Har step")
        box.addView(pickRow("Agent kab bole", modeLabels[modes.indexOf(Config.speakMode(this)).coerceAtLeast(0)], modeLabels) {
            Config.setSpeakMode(this, modes[it])
            rebuild("voice")
        })
        box.addView(Ui.note(this, "Speech speed"))
        box.addView(SeekBar(this).apply {
            max = 15
            progress = ((Config.ttsRate(this@SettingsActivity) - 0.5f) / 0.1f).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) Config.setTtsRate(this@SettingsActivity, 0.5f + p * 0.1f)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        box.addView(Ui.note(this, "Volume"))
        box.addView(SeekBar(this).apply {
            max = 9
            progress = ((Config.ttsVolume(this@SettingsActivity) * 10f).toInt() - 1).coerceIn(0, 9)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    if (fromUser) Config.setTtsVolume(this@SettingsActivity, (p + 1) / 10f)
                }

                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        })
        box.addView(pickRow("Bhasha (bolna + sunna)", Config.voiceLang(this), Config.LANGS) {
            Config.setVoiceLang(this, Config.LANGS[it])
            rebuild("voice")
        })
        val b = Ui.hbox(this)
        b.addView(Ui.button(this, "🎙 Awaaz chuno", "tonal") { pickVoice() }, Ui.lp(this, weight = 1f))
        b.addView(Ui.button(this, "▶ Test", "tonal") {
            Speaker.speak(this, "Namaste, main LoRA hu. Ye meri awaaz ka test hai.")
        }, Ui.lp(this, weight = 1f, left = 6))
        box.addView(b, Ui.lp(this, top = 6))
        return box
    }

    private fun pickVoice() {
        Speaker.listVoices(this) { names ->
            val all = listOf("(Default voice)") + names
            AlertDialog.Builder(this)
                .setTitle("Awaaz chuno (${Config.voiceLang(this)})")
                .setItems(all.toTypedArray()) { _, i ->
                    Config.setTtsVoice(this, if (i == 0) null else names[i - 1])
                    Speaker.speak(this, "Ye meri awaaz hai.")
                }
                .setNegativeButton("Band", null)
                .show()
        }
    }

    // ---------------- models / providers ----------------

    private fun modelsSection(): View {
        val box = Ui.vbox(this)
        box.addView(Ui.button(this, "Model Manager kholo  (abhi: ${ModelPicker.shortLabel(this)})", "tonal") {
            ModelPicker.show(this, false) { runOnUiThread { rebuild("models") } }
        })
        box.addView(Ui.note(this, "Beech me model badlo to task wahin se continue hota hai."))
        box.addView(Ui.text(this, "Providers (limit/error par agla apne aap)", 13f, Ui.MUTED, true), Ui.lp(this, top = 12))
        providersBox = Ui.vbox(this)
        box.addView(providersBox)

        val keyField = EditText(this).apply {
            hint = "API key (Gemini AIza... / OpenRouter sk-or-... / Groq gsk_... / custom)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelsField = EditText(this).apply { hint = "Model(s), comma se. Khali = auto (Gemini/OpenRouter)" }
        val baseField = EditText(this).apply { hint = "Base URL (sirf custom provider ke liye)" }
        box.addView(keyField)
        box.addView(modelsField)
        box.addView(baseField)
        box.addView(Ui.button(this, "+ Cloud provider jodo", "primary") {
            val key = keyField.text.toString().trim()
            if (key.isEmpty()) {
                toast("API key daalo")
                return@button
            }
            val type = Config.detectType(key)
            val base = if (type == "custom") baseField.text.toString().trim() else Config.presetBase(type)
            val models = Config.splitModels(modelsField.text.toString())
            if (type == "custom" && base.isEmpty()) {
                toast("Is key ke liye Base URL chahiye")
                return@button
            }
            if ((type == "groq" || type == "custom") && models.isEmpty()) {
                toast("Is provider ke liye model ka naam do")
                return@button
            }
            if (models.any { it.contains(' ') }) {
                toast("Model ka slug likho (jaise vendor/model-name), display name nahi")
                return@button
            }
            val list = Config.load(this).filter { it.key != key } + Provider(type, key, base, models)
            Config.save(this, list)
            keyField.setText("")
            modelsField.setText("")
            baseField.setText("")
            refreshProviders()
            toast("Jod diya: $type")
        }, Ui.lp(this, top = 6))
        refreshProviders()
        return box
    }

    private fun refreshProviders() {
        if (!::providersBox.isInitialized) return
        providersBox.removeAllViews()
        val list = Config.load(this)
        if (list.isEmpty()) {
            providersBox.addView(Ui.note(this, "(abhi koi provider nahi)"))
            return
        }
        for ((i, p) in list.withIndex()) {
            val row = Ui.hbox(this)
            row.setPadding(0, dp(6), 0, dp(6))
            val models = if (p.models.isEmpty()) "auto" else p.models.joinToString(", ")
            val col = Ui.vbox(this)
            col.addView(Ui.text(this, "${i + 1}. ${if (p.isOffline) "🟢" else "🌐"} ${p.label}", 14f, Ui.TEXT, true))
            col.addView(Ui.text(this, models, 12f, Ui.MUTED))
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(Ui.chip(this, "↑", Ui.PRIMARY, android.graphics.Color.WHITE, 13f) {
                if (i > 0) {
                    val m = list.toMutableList()
                    val t = m[i]
                    m[i] = m[i - 1]
                    m[i - 1] = t
                    Config.save(this, m)
                    refreshProviders()
                }
            }, Ui.lp(this, right = 6).also { it.width = ViewGroup.LayoutParams.WRAP_CONTENT })
            row.addView(Ui.chip(this, "Hatao", Ui.ERR, android.graphics.Color.WHITE, 12f) {
                removeProvider(list, i)
            })
            providersBox.addView(row)
        }
    }

    private fun removeProvider(list: List<Provider>, i: Int) {
        val p = list[i]
        val pin = Config.pin(this)
        if (pin != null && pin.startsWith(Pool.pinId(p, ""))) Config.setPin(this, null)
        if (p.type == "device") {
            DeviceLlm.release()
            try {
                File(p.key).delete()
            } catch (_: Exception) {
            }
        }
        Config.save(this, list.filterIndexed { idx, _ -> idx != i })
        refreshProviders()
        refreshDevice()
    }

    // ---------------- offline models ----------------

    private fun offlineSection(): View {
        val box = Ui.vbox(this)
        box.addView(Ui.text(this, "📱 Phone ke andar ka model (MediaPipe)", 14f, Ui.TEXT, true))
        box.addView(Ui.note(this, "Gemma / Qwen ki .task ya .litertlm file (chhota, 1B se kam) phone se chuno. File app ke andar copy hogi. Internet, quota kuch nahi lagta. Snapdragon 695 par dheema ho sakta hai; agent ke liye kabhi-kabhi galat jawab bhi."))
        deviceBox = Ui.vbox(this)
        box.addView(deviceBox)
        box.addView(Ui.button(this, "📂 Model file jodo", "primary") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 61)
        }, Ui.lp(this, top = 6))
        val ctx = Config.deviceCtx(this)
        box.addView(pickRow("Context (tokens)", "$ctx", listOf("768", "1024", "1280", "2048", "3072")) {
            Config.setDeviceCtx(this, listOf(768, 1024, 1280, 2048, 3072)[it])
            DeviceLlm.release()
            rebuild("offline")
        })
        box.addView(Ui.note(this, "Bada context = zyada RAM aur dheema."))

        box.addView(Ui.text(this, "🖥 Ollama / llama.cpp server (Termux ya PC)", 14f, Ui.TEXT, true), Ui.lp(this, top = 14))
        val localBase = EditText(this).apply {
            hint = "URL: http://127.0.0.1:11434/v1  (ya https://tumhara-server/v1)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val localModel = EditText(this).apply { hint = "Model(s), comma se (jaise llama3.2:3b, qwen2.5:1.5b)" }
        val localKey = EditText(this).apply {
            hint = "API key (zarurat ho to, warna khali)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(localBase)
        box.addView(localModel)
        box.addView(localKey)
        box.addView(Ui.button(this, "+ Server model jodo", "tonal") {
            val base = localBase.text.toString().trim().trimEnd('/')
            val models = Config.splitModels(localModel.text.toString())
            val key = localKey.text.toString().trim()
            if (!(base.startsWith("http://") || base.startsWith("https://"))) {
                toast("URL http:// ya https:// se shuru karo")
                return@button
            }
            val host = Uri.parse(base).host.orEmpty()
            if (base.startsWith("http://") && host !in listOf("localhost", "127.0.0.1", "10.0.2.2")) {
                toast("http:// sirf localhost / 127.0.0.1 par chalta hai. Dusre server ke liye https:// URL do")
                return@button
            }
            if (models.isEmpty()) {
                toast("Model ka naam do")
                return@button
            }
            if (models.any { it.contains(' ') }) {
                toast("Model ka slug likho, display name nahi")
                return@button
            }
            val list = Config.load(this).filter { !(it.type == "local" && it.baseUrl == base) } +
                Provider("local", key, base, models)
            Config.save(this, list)
            localBase.setText("")
            localModel.setText("")
            localKey.setText("")
            refreshProviders()
            toast("Server model jod diya")
        }, Ui.lp(this, top = 6))
        refreshDevice()
        return box
    }

    private fun refreshDevice() {
        if (!::deviceBox.isInitialized) return
        deviceBox.removeAllViews()
        val devs = Config.load(this).filter { it.type == "device" }
        if (devs.isEmpty()) {
            deviceBox.addView(Ui.note(this, "(abhi phone ke andar koi model nahi)"))
            return
        }
        for (p in devs) {
            val f = File(p.key)
            val mb = if (f.exists()) f.length() / (1024 * 1024) else 0
            val row = Ui.hbox(this)
            row.setPadding(0, dp(6), 0, dp(6))
            row.addView(
                Ui.text(this, "🟢 ${f.name}\n${mb} MB" + if (f.exists()) "" else " · file nahi mili", 13f, Ui.TEXT),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            )
            row.addView(Ui.chip(this, "Hatao", Ui.ERR, android.graphics.Color.WHITE, 12f) {
                AlertDialog.Builder(this).setTitle("Hatayein?")
                    .setMessage("${f.name} app se delete ho jayegi.")
                    .setPositiveButton("Delete") { _, _ ->
                        val all = Config.load(this)
                        val idx = all.indexOfFirst { it.type == "device" && it.key == p.key }
                        if (idx >= 0) removeProvider(all, idx)
                    }
                    .setNegativeButton("Cancel", null).show()
            })
            deviceBox.addView(row)
        }
    }

    private fun nameOf(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) {
                    val n = it.getString(0)
                    if (!n.isNullOrBlank()) return n
                }
            }
        } catch (_: Exception) {
        }
        return uri.lastPathSegment ?: "model.task"
    }

    private fun importDevice(uri: Uri) {
        val name = nameOf(uri)
        if (!(name.endsWith(".task") || name.endsWith(".litertlm") || name.endsWith(".bin"))) {
            toast("Ye model file nahi lagti (.task / .litertlm / .bin chahiye)")
            return
        }
        val dlg = AlertDialog.Builder(this).setTitle("Copy ho rahi hai...")
            .setMessage("$name\nBand mat karo, kai sau MB ho sakti hai.")
            .setCancelable(false).show()
        thread(name = "device-import") {
            var err: String? = null
            val dest = File(DeviceLlm.modelsDir(this), name)
            try {
                val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("file khul nahi")
                ins.use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                val list = Config.load(this).toMutableList()
                if (list.none { it.type == "device" && it.key == dest.absolutePath }) {
                    list.add(Provider("device", dest.absolutePath, "", listOf(name)))
                    Config.save(this, list)
                }
            } catch (e: Throwable) {
                err = e.message ?: e.javaClass.simpleName
                try {
                    dest.delete()
                } catch (_: Exception) {
                }
            }
            runOnUiThread {
                dlg.dismiss()
                if (err != null) toast("Import fail: $err (storage bhara ho sakta hai)")
                else {
                    toast("✓ Model jud gaya. Model Manager me dikhega.")
                    refreshDevice()
                    refreshProviders()
                }
            }
        }
    }

    // ---------------- backup ----------------

    private fun backupSection(): View {
        val box = Ui.vbox(this)
        box.addView(Ui.note(this, "Backup me settings, skills (aur chaho to chat text) jaate hain. API keys, images aur files nahi. Passphrase doge to file encrypted hogi."))
        box.addView(Ui.button(this, "⬆ Backup banao", "primary") {
            val ll = Ui.vbox(this)
            ll.setPadding(dp(20), dp(8), dp(20), 0)
            val cb = CheckBox(this).apply { text = "Chat history bhi shamil karo" }
            val pw = EditText(this).apply {
                hint = "Passphrase (optional)"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            ll.addView(cb)
            ll.addView(pw)
            AlertDialog.Builder(this).setTitle("Backup").setView(ll)
                .setPositiveButton("Aage") { _, _ ->
                    exportChats = cb.isChecked
                    exportPass = pw.text.toString().ifEmpty { null }
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "application/json"
                    i.putExtra(Intent.EXTRA_TITLE, "lora-backup.json")
                    startActivityForResult(i, 62)
                }
                .setNegativeButton("Cancel", null).show()
        })
        box.addView(Ui.button(this, "⬇ Backup restore karo", "tonal") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 63)
        }, Ui.lp(this, top = 6))
        return box
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            61 -> importDevice(uri)
            62 -> thread(name = "backup-out") {
                try {
                    val s = Backup.build(this, exportChats, exportPass)
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(s.toByteArray(Charsets.UTF_8)) }
                    runOnUiThread { toast("✓ Backup save ho gaya") }
                } catch (e: Exception) {
                    runOnUiThread { toast("Backup fail: ${e.message}") }
                }
            }
            63 -> {
                val ll = Ui.vbox(this)
                ll.setPadding(dp(20), dp(8), dp(20), 0)
                val pw = EditText(this).apply {
                    hint = "Passphrase (agar lagayi thi)"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                ll.addView(pw)
                AlertDialog.Builder(this).setTitle("Restore").setView(ll)
                    .setPositiveButton("Restore") { _, _ ->
                        val pass = pw.text.toString().ifEmpty { null }
                        thread(name = "backup-in") {
                            try {
                                val text = contentResolver.openInputStream(uri)?.use {
                                    String(it.readBytes(), Charsets.UTF_8)
                                } ?: throw IllegalStateException("file khul nahi")
                                val msg = Backup.restore(this, text, pass)
                                runOnUiThread {
                                    toast(msg)
                                    build()
                                }
                            } catch (e: Exception) {
                                runOnUiThread { toast("Restore fail: ${e.message}") }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    // ---------------- floating / setup ----------------

    private fun floatSection(): View {
        val box = Ui.vbox(this)
        val on = AgentAccessibilityService.instance != null
        box.addView(
            Ui.pill(this, if (on) "✓ Accessibility on hai" else "✗ Accessibility band hai", if (on) Ui.OK_SOFT else Ui.ERR_SOFT, if (on) Ui.OK else Ui.ERR)
        )
        box.addView(Ui.button(this, "Accessibility settings kholo", "tonal") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }, Ui.lp(this, top = 8))
        box.addView(Ui.switchRow(this, "Floating icon dikhao", "Screen par kahin bhi, drag kar sakte ho", Config.bubbleEnabled(this)) {
            Config.setBubble(this, it)
        })
        box.addView(Ui.button(this, "Floating icon wapas dikhao (Close ke baad)", "tonal") {
            AgentAccessibilityService.instance?.showIcon()
        })
        box.addView(Ui.note(this, "Android 13+: App info → ⋮ → \"Allow restricted settings\", phir Accessibility me LoRA on."))
        return box
    }

    // ---------------- about ----------------

    private fun aboutSection(): View {
        val box = Ui.vbox(this)
        box.addView(Ui.text(this, "LoRA v0.6 — phone chalane wala agent, offline chat, voice, skills.", 13f, Ui.TEXT))
        box.addView(Ui.button(this, "📋 Log copy karo", "tonal") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("LoRA log", AgentLog.lines.joinToString("\n")))
            toast("Log copy ho gaya")
        }, Ui.lp(this, top = 8))
        box.addView(Ui.button(this, "🗑 Saari chat history delete karo", "danger") {
            AlertDialog.Builder(this).setTitle("Pakka?")
                .setMessage("Saari chats is phone se hat jayengi.")
                .setPositiveButton("Delete") { _, _ ->
                    for (c in ChatStore.convs(this)) ChatStore.deleteConv(this, c.id)
                    Config.setActiveChat(this, 0L)
                    toast("Chat history saaf")
                }
                .setNegativeButton("Cancel", null).show()
        }, Ui.lp(this, top = 6))
        return box
    }
}
