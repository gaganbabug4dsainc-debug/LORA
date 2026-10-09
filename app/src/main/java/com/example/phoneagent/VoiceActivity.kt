package com.example.phoneagent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast

/** Floating window ke mic ke liye: Google ka bolne wala dialog kholta hai aur text wapas bhejta hai. */
object VoiceBridge {
    @Volatile
    var callback: ((String?) -> Unit)? = null

    fun deliver(text: String?) {
        val cb = callback
        callback = null
        cb?.invoke(text)
    }
}

class VoiceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Config.voiceLang(this))
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo...")
        try {
            startActivityForResult(i, 1)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "Is phone me voice input (Google app) nahi mila", Toast.LENGTH_LONG).show()
            finish()
            VoiceBridge.deliver(null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val text = if (resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        } else null
        finish()
        VoiceBridge.deliver(text)
    }
}
