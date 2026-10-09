package com.example.phoneagent

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

/** Voice settings, local model import, skills, backup/restore. */
class MoreActivity : Activity() {

    private lateinit var box: LinearLayout
    private var exportChats = false
    private var exportPass: String? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Boot.init(this)
        box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(14), dp(28), dp(14), dp(14))
        val sv = ScrollView(this)
        sv.addView(box)
        setContentView(sv)
        render()
    }

    private fun head(t: String) {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 17f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(0, dp(18), 0, dp(4))
        box.addView(tv)
    }

    private fun note(t: String) {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 12f
        box.addView(tv)
    }

    private fun btn(t: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = t
        b.setAllCaps(false)
        b.setOnClickListener { onClick() }
        box.addView(b)
    }

    private fun render() {
        box.removeAllViews()
        val title = TextView(this)
        title.text = "⚙️ Aur settings"
        title.textSize = 22f
        title.typeface = Typeface.DEFAULT_BOLD
        box.addView(title)
        renderVoice()
        renderLocal()
        renderSkills()
        renderBackup()
    }

    // ---------------- voice ----------------

    private fun renderVoice() {
        head("🔊 Voice")
        val modes = arrayOf("silent", "important", "every")
        val modeNames = arrayOf("Silent (awaaz band)", "Sirf zaruri (done/error/permission)", "Har step bolo")
        val cur = Config.voiceMode(this)
        btn("Bolna: " + modeNames[modes.indexOf(cur).coerceAtLeast(0)]) {
            AlertDialog.Builder(this).setTitle("Voice mode")
                .setItems(modeNames) { _, i ->
                    Config.setVoiceMode(this, modes[i])
                    render()
                }.show()
        }
        val rate = Config.getf(this, "tts_rate", 1.0f)
        btn("Speed: ${"%.1f".format(rate)}x") {
            val opts = arrayOf("0.7x", "0.85x", "1.0x", "1.2x", "1.5x")
            val vals = floatArrayOf(0.7f, 0.85f, 1.0f, 1.2f, 1.5f)
            AlertDialog.Builder(this).setTitle("Bolne ki speed")
                .setItems(opts) { _, i ->
                    Config.putf(this, "tts_rate", vals[i])
                    VoiceOut.applySettings(this)
                    render()
                }.show()
        }
        val vol = Config.getf(this, "tts_vol", 1.0f)
        btn("Volume: ${(vol * 100).toInt()}%") {
            val opts = arrayOf("25%", "50%", "75%", "100%")
            val vals = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f)
            AlertDialog.Builder(this).setTitle("Voice volume")
                .setItems(opts) { _, i ->
                    Config.putf(this, "tts_vol", vals[i])
                    render()
                }.show()
        }
        btn("Bolne ki bhasha: " + Config.gets(this, "tts_lang", "en-IN")) {
            val tags = arrayOf("en-IN", "hi-IN", "en-US")
            AlertDialog.Builder(this).setTitle("TTS language")
                .setItems(tags) { _, i ->
                    Config.puts(this, "tts_lang", tags[i])
                    Config.puts(this, "tts_voice", "")
                    VoiceOut.applySettings(this)
                    render()
                }.show()
        }
        btn("Voice chuno: " + Config.gets(this, "tts_voice", "").ifEmpty { "default" }) {
            thread(name = "voices") {
                val names = VoiceOut.voiceNames(this)
                runOnUiThread {
                    if (names.isEmpty()) {
                        toast("Voices abhi ready nahi. Thodi der baad dobara try karo ya phone ke TTS settings dekho.")
                    } else {
                        AlertDialog.Builder(this).setTitle("Voice")
                            .setItems(names.toTypedArray()) { _, i ->
                                Config.puts(this, "tts_voice", names[i])
                                VoiceOut.applySettings(this)
                                render()
                            }.show()
                    }
                }
            }
        }
        btn("▶ Test awaaz") { VoiceOut.test(this, "Namaste, main Phone Agent hu.") }
        btn("Sunne ki bhasha (mic): " + Config.gets(this, "stt_lang", "hi-IN")) {
            val tags = arrayOf("hi-IN", "en-IN", "en-US")
            AlertDialog.Builder(this).setTitle("STT language")
                .setItems(tags) { _, i ->
                    Config.puts(this, "stt_lang", tags[i])
                    render()
                }.show()
        }
        val has = VoiceIn.hasPermission(this)
        btn(if (has) "🎤 Mic permission: mili hui ✓" else "🎤 Mic permission do") {
            if (!has) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41)
        }
        note("Mic offline pe tabhi chalta hai jab phone me offline speech pack download ho. Warna Google ki online recognition use ho sakti hai.")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) render()
    }

    // ---------------- local model ----------------

    private fun renderLocal() {
        head("🟢 Local (offline) model")
        note(
            "MediaPipe .task / .litertlm file (jaise Gemma 3 1B / Qwen 2.5 0.5B) phone me se chuno. " +
                "File app ke andar copy hogi (kai sau MB, thoda time lagega). Snapdragon 695 par chhota (≤1B) model hi chalayen."
        )
        btn("📂 Local model file jodo") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 61)
        }
        val locals = Config.load(this).filter { it.type == "local" }
        for (p in locals) {
            val f = File(p.key)
            val mb = if (f.exists()) f.length() / (1024 * 1024) else 0
            btn("🗑 ${f.name} (${mb} MB) — hatao") {
                AlertDialog.Builder(this).setTitle("Hatayein?")
                    .setMessage("${f.name} app se delete hogi.")
                    .setPositiveButton("Delete") { _, _ ->
                        LocalLlm.release()
                        try {
                            f.delete()
                        } catch (_: Exception) {
                        }
                        Config.save(this, Config.load(this).filter { it.key != p.key })
                        render()
                    }
                    .setNegativeButton("Cancel", null).show()
            }
        }
        val ctx = Config.localCtx(this)
        btn("Local context (tokens): $ctx") {
            val opts = arrayOf("768", "1024", "1280", "2048", "3072")
            AlertDialog.Builder(this).setTitle("Local context (bada = zyada RAM, dheema)")
                .setItems(opts) { _, i ->
                    Config.setLocalCtx(this, opts[i].toInt())
                    LocalLlm.release()
                    render()
                }.show()
        }
    }

    private fun importModel(uri: Uri) {
        val name = Attach.displayName(this, uri)
        if (!(name.endsWith(".task") || name.endsWith(".litertlm") || name.endsWith(".bin"))) {
            toast("Ye model file nahi lagti (.task / .litertlm / .bin chahiye)")
            return
        }
        val dlg = AlertDialog.Builder(this).setTitle("Copy ho rahi hai...")
            .setMessage(name + "\nThoda wait karo, band mat karo.")
            .setCancelable(false).show()
        thread(name = "model-import") {
            var err: String? = null
            val dest = File(LocalLlm.modelsDir(this), name)
            try {
                val ins = contentResolver.openInputStream(uri) ?: throw IllegalStateException("file khul nahi")
                ins.use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
                val list = Config.load(this).toMutableList()
                if (list.none { it.type == "local" && it.key == dest.absolutePath }) {
                    list.add(Provider("local", dest.absolutePath, "", listOf(name)))
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
                if (err != null) toast("Import fail: $err (storage bhara ho sakta hai)") else {
                    toast("✓ Local model jud gaya. Ab model chunne me dikhega.")
                    render()
                }
            }
        }
    }

    // ---------------- skills ----------------

    private fun renderSkills() {
        head("🧩 Skills / Workflows")
        val list = SkillStore.load(this)
        if (list.isEmpty()) note("Abhi koi skill nahi. Task poora hone par 'Create Skill / Save Workflow' dabao.")
        for (s in list) {
            btn("▶ ${s.name} (${s.steps.size} steps)") {
                AlertDialog.Builder(this).setTitle(s.name)
                    .setMessage("Goal: ${s.goal}\n\n" + s.steps.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n"))
                    .setPositiveButton("Chalao") { _, _ ->
                        val svc = AgentAccessibilityService.instance
                        if (svc == null) toast("Pehle Accessibility me Phone Agent on karo") else {
                            AgentLoop.startNew(svc, s.goal, s)
                            toast("Skill shuru ho gayi")
                        }
                    }
                    .setNeutralButton("Delete") { _, _ ->
                        SkillStore.delete(this, s.id)
                        render()
                    }
                    .setNegativeButton("Band", null).show()
            }
        }
    }

    // ---------------- backup ----------------

    private fun renderBackup() {
        head("💾 Backup / Restore")
        note("Backup me settings, skills (aur chaho to chats) jaate hain. API keys kabhi nahi jaati.")
        btn("⬆ Backup banao (export)") {
            val ll = LinearLayout(this)
            ll.orientation = LinearLayout.VERTICAL
            ll.setPadding(dp(20), dp(8), dp(20), 0)
            val cb = CheckBox(this)
            cb.text = "Chat history bhi shamil karo"
            val pw = EditText(this)
            pw.hint = "Passphrase (optional, encrypt ke liye)"
            pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            ll.addView(cb)
            ll.addView(pw)
            AlertDialog.Builder(this).setTitle("Export").setView(ll)
                .setPositiveButton("Aage") { _, _ ->
                    exportChats = cb.isChecked
                    exportPass = pw.text.toString().ifEmpty { null }
                    val i = Intent(Intent.ACTION_CREATE_DOCUMENT)
                    i.addCategory(Intent.CATEGORY_OPENABLE)
                    i.type = "application/json"
                    i.putExtra(Intent.EXTRA_TITLE, "phone-agent-backup.json")
                    startActivityForResult(i, 62)
                }
                .setNegativeButton("Cancel", null).show()
        }
        btn("⬇ Backup restore karo (import)") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            i.addCategory(Intent.CATEGORY_OPENABLE)
            i.type = "*/*"
            startActivityForResult(i, 63)
        }
        btn("🗑 Saari chat history delete karo") {
            AlertDialog.Builder(this).setTitle("Pakka?")
                .setMessage("Saari chats is phone se hat jayengi.")
                .setPositiveButton("Delete") { _, _ ->
                    ChatDb.get(this).clearAll()
                    Config.prefs(this).edit().putLong("chat_conv", 0L).apply()
                    toast("Chat history saaf")
                }
                .setNegativeButton("Cancel", null).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            61 -> importModel(uri)
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
                val ll = LinearLayout(this)
                ll.setPadding(dp(20), dp(8), dp(20), 0)
                val pw = EditText(this)
                pw.hint = "Passphrase (agar lagayi thi)"
                pw.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                ll.addView(pw, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
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
                                    render()
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
}
