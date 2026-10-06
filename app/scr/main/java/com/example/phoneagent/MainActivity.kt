package com.example.phoneagent

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("agent", Context.MODE_PRIVATE)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Phone Agent v0.1"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }

        val apiKey = EditText(this).apply {
            hint = "Gemini API key"
            setText(prefs.getString("key", ""))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val model = EditText(this).apply {
            hint = "Model"
            setText(prefs.getString("model", "gemini-2.5-flash"))
        }
        val goal = EditText(this).apply {
            hint = "Goal, jaise: Clock app kholo aur 7 baje ka alarm lagao"
            minLines = 2
        }

        val btnAccess = Button(this).apply {
            text = "1. Accessibility settings kholo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnStart = Button(this).apply {
            text = "2. Agent start"
            setOnClickListener {
                val svc = AgentAccessibilityService.instance
                if (svc == null) {
                    Toast.makeText(
                        this@MainActivity,
                        "Pehle Accessibility me Phone Agent ko on karo",
                        Toast.LENGTH_LONG
                    ).show()
                    return@setOnClickListener
                }
                val key = apiKey.text.toString().trim()
                val g = goal.text.toString().trim()
                if (key.isEmpty() || g.isEmpty()) {
                    Toast.makeText(this@MainActivity, "API key aur goal bharo", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                prefs.edit().putString("key", key).putString("model", model.text.toString().trim()).apply()
                AgentLoop.start(svc, key, model.text.toString().trim(), g)
            }
        }
        val btnStop = Button(this).apply {
            text = "Stop"
            setOnClickListener { AgentLoop.stop() }
        }

        logView = TextView(this).apply { textSize = 13f }
        val scroll = ScrollView(this).apply { addView(logView) }

        root.addView(title)
        root.addView(apiKey)
        root.addView(model)
        root.addView(goal)
        root.addView(btnAccess)
        root.addView(btnStart)
        root.addView(btnStop)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        logView.text = AgentLog.lines.joinToString("\n")
        AgentLog.listener = { line ->
            runOnUiThread { logView.append(line + "\n") }
        }
    }

    override fun onPause() {
        super.onPause()
        AgentLog.listener = null
    }
}
