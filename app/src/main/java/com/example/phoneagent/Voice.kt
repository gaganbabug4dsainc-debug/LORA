package com.example.phoneagent

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.widget.Toast
import java.util.Locale

/**
 * Agent ki awaaz (Text-to-Speech). Modes: silent | important (sirf zaroori) | every (har step).
 * Speed, volume, language aur voice Settings me badal sakte ho.
 */
object VoiceOut {
    @Volatile
    var appCtx: Context? = null

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    private var pendingText: String? = null
    private var pendingQueue = false
    private val main = Handler(Looper.getMainLooper())

    private fun ensure(c: Context) {
        if (tts != null) return
        val app = c.applicationContext
        main.post {
            if (tts != null) return@post
            tts = TextToSpeech(app) { status ->
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    applySettings(app)
                    val p = pendingText
                    if (p != null) {
                        pendingText = null
                        speakNow(app, p, pendingQueue)
                    }
                }
            }
        }
    }

    /** Settings badalne ke baad dobara lagao. */
    fun applySettings(c: Context) {
        val t = tts ?: return
        if (!ready) return
        try {
            t.setSpeechRate(Config.getf(c, "tts_rate", 1f))
            val loc = Locale.forLanguageTag(Config.gets(c, "tts_lang", "en-IN"))
            val r = t.setLanguage(loc)
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                t.setLanguage(Locale.US)
            }
            val name = Config.gets(c, "tts_voice", "")
            if (name.isNotEmpty()) {
                t.voices?.firstOrNull { it.name == name }?.let { t.setVoice(it) }
            }
        } catch (_: Exception) {
        }
    }

    /** Mode ke hisaab se bolta hai. important=false wale sirf "every" mode me bolte hain. */
    fun say(text: String, important: Boolean) {
        val c = appCtx ?: return
        val mode = Config.voiceMode(c)
        if (mode == "silent") return
        if (mode == "important" && !important) return
        speakNow(c, text, important)
    }

    /** Mode ignore karke (Test voice button ke liye). */
    fun test(c: Context, text: String) {
        ensure(c)
        speakNow(c, text, false)
    }

    private fun speakNow(c: Context, raw: String, queue: Boolean) {
        val clean = raw.replace(Regex("[^\\p{L}\\p{N}\\s.,!?:;'()/%-]"), " ")
            .replace(Regex("\\s+"), " ").trim().take(220)
        if (clean.isEmpty()) return
        ensure(c)
        val t = tts
        if (t == null || !ready) {
            pendingText = clean
            pendingQueue = queue
            return
        }
        try {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Config.getf(c, "tts_vol", 1f))
            t.speak(
                clean,
                if (queue) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
                params,
                "pa-${System.nanoTime()}"
            )
        } catch (_: Exception) {
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    /** Available voices ke naam (Hindi/English). Engine ready na ho to khali. */
    fun voiceNames(c: Context): List<String> {
        ensure(c)
        if (!ready) return emptyList()
        return try {
            tts?.voices?.filter { it.locale.language == "en" || it.locale.language == "hi" }
                ?.map { it.name }?.sorted() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isReady(): Boolean = ready
    fun warmUp(c: Context) = ensure(c)
}

/** User ki awaaz (Speech-to-Text). Android ka SpeechRecognizer use hota hai. */
object VoiceIn {
    private var rec: SpeechRecognizer? = null
    private val main = Handler(Looper.getMainLooper())

    fun available(c: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(c)

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun errorText(code: Int): String = when (code) {
        1 -> "network timeout"
        2 -> "network error"
        3 -> "audio error (mic busy ya background me allowed nahi)"
        4 -> "server error"
        5 -> "client error"
        6 -> "kuch bola nahi gaya"
        7 -> "samajh nahi aaya"
        8 -> "recognizer busy"
        9 -> "mic permission nahi hai"
        10 -> "bahut zyada requests"
        12, 13 -> "is bhasha ka voice recognition available nahi"
        else -> "error $code"
    }

    /** Hamesha main thread par chalta hai. Callbacks bhi main thread par aate hain. */
    fun listen(
        c: Context,
        cbPartial: (String) -> Unit,
        cbFinal: (String) -> Unit,
        cbError: (Int, String) -> Unit
    ) {
        val app = c.applicationContext
        main.post {
            try {
                stopNow()
                val r = SpeechRecognizer.createSpeechRecognizer(app)
                rec = r
                r.setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}

                    override fun onError(error: Int) {
                        stopNow()
                        cbError(error, errorText(error))
                    }

                    override fun onResults(results: Bundle?) {
                        val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        stopNow()
                        if (t.isBlank()) cbError(7, errorText(7)) else cbFinal(t)
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val t = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull().orEmpty()
                        if (t.isNotBlank()) cbPartial(t)
                    }
                })
                val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.gets(app, "stt_lang", "hi-IN"))
                i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                r.startListening(i)
            } catch (e: Exception) {
                stopNow()
                cbError(-1, e.message ?: "voice start nahi hua")
            }
        }
    }

    fun stop() {
        main.post { stopNow() }
    }

    private fun stopNow() {
        val r = rec ?: return
        rec = null
        try {
            r.stopListening()
            r.cancel()
            r.destroy()
        } catch (_: Exception) {
        }
    }
}

/**
 * Transparent activity: mic permission maangti hai, aur zarurat par foreground me sunti hai
 * (Android background se mic block kar sakta hai). Result seedha AgentChat ko jata hai.
 */
class VoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Boot.init(this)
        VoiceOut.stop()
        if (!VoiceIn.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
            return
        }
        startListen()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (!ok) {
            Toast.makeText(this, "Mic permission nahi mili. Settings → Apps → Phone Agent → Permissions.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        if (intent.getBooleanExtra("permission_only", false)) {
            Toast.makeText(this, "Mic permission mil gayi. Ab 🎤 dabao.", Toast.LENGTH_LONG).show()
            finish()
        } else {
            startListen()
        }
    }

    private fun startListen() {
        Toast.makeText(this, "🎤 Bolo...", Toast.LENGTH_SHORT).show()
        VoiceIn.listen(
            this,
            cbPartial = {},
            cbFinal = { text ->
                AgentChat.handle(text)
                finish()
            },
            cbError = { _, msg ->
                Toast.makeText(this, "Voice: $msg", Toast.LENGTH_LONG).show()
                finish()
            }
        )
    }

    override fun onDestroy() {
        VoiceIn.stop()
        super.onDestroy()
    }
}
