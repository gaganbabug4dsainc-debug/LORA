package com.example.phoneagent

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Jawab bolkar sunata hai (phone ka apna TTS engine). Speed/volume/voice/mode Config se. */
object Speaker {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private val waiting = ArrayList<() -> Unit>()

    @Volatile
    private var onDone: (() -> Unit)? = null

    /** Chat ke jawab: tab bolo jab TTS ON ho. */
    fun speakIfOn(ctx: Context, text: String) {
        if (Config.ttsOn(ctx)) speak(ctx, text)
    }

    /**
     * Agent ki ghatnayein. Mode: silent = kuch nahi, important = permission/error/pause/done/jawab,
     * every = har step bhi.
     */
    fun event(ctx: Context, text: String, important: Boolean) {
        when (Config.speakMode(ctx)) {
            "silent" -> return
            "important" -> if (!important) return
        }
        speak(ctx, text)
    }

    fun speak(ctx: Context, text: String, done: (() -> Unit)? = null) {
        val clean = clean(text)
        if (clean.isBlank()) {
            if (done != null) main.post { done() }
            return
        }
        val app = ctx.applicationContext
        main.post {
            onDone = done
            ensure(app) { doSpeak(app, clean) }
        }
    }

    fun stop() {
        main.post {
            onDone = null
            try {
                tts?.stop()
            } catch (_: Exception) {
            }
        }
    }

    /** Installed voices ke naam (bhasha ke hisaab se). Callback main thread par. */
    fun listVoices(ctx: Context, cb: (List<String>) -> Unit) {
        val app = ctx.applicationContext
        main.post {
            ensure(app) {
                val lang = Locale.forLanguageTag(Config.voiceLang(app)).language
                val names = try {
                    tts?.voices?.filter { it.locale.language == lang }?.map { it.name }?.sorted() ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }
                cb(names)
            }
        }
    }

    private fun finish() {
        main.post {
            val d = onDone
            onDone = null
            d?.invoke()
        }
    }

    private fun ensure(app: Context, then: () -> Unit) {
        if (ready && tts != null) {
            then()
            return
        }
        waiting.add(then)
        if (initializing) return
        initializing = true
        tts = TextToSpeech(app) { status ->
            main.post {
                initializing = false
                ready = status == TextToSpeech.SUCCESS
                if (ready) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) = finish()

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = finish()
                    })
                    val todo = ArrayList(waiting)
                    waiting.clear()
                    for (w in todo) w()
                } else {
                    waiting.clear()
                    finish()
                }
            }
        }
    }

    private fun doSpeak(app: Context, text: String) {
        val t = tts ?: return finish()
        val loc = Locale.forLanguageTag(Config.voiceLang(app))
        val r = t.setLanguage(loc)
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            t.setLanguage(Locale.getDefault())
        } else {
            // Chuni hui voice (agar is bhasha ki ho aur installed ho)
            val vn = Config.ttsVoice(app)
            if (vn != null) {
                try {
                    t.voices?.firstOrNull { it.name == vn }?.let { t.voice = it }
                } catch (_: Exception) {
                }
            }
        }
        t.setSpeechRate(Config.ttsRate(app).coerceIn(0.4f, 2.5f))
        val params = Bundle()
        params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, Config.ttsVolume(app).coerceIn(0.05f, 1f))
        val res = t.speak(text.take(1200), TextToSpeech.QUEUE_FLUSH, params, "lora")
        if (res != TextToSpeech.SUCCESS) finish()
    }

    /** Markdown ke symbols aur code blocks bolne layak nahi hote. */
    private fun clean(s: String): String = s
        .replace(Regex("(?s)```.*?```"), " code block ")
        .replace(Regex("[*_`#>|]+"), "")
        .replace(Regex("\\[(.*?)]\\(.*?\\)"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
}
