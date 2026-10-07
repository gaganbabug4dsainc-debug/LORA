package com.example.phoneagent

import android.app.Activity
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
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var providersBox: LinearLayout

    private val logListener: (String) -> Unit = { line ->
        runOnUiThread { logView.append(line + "\n") }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(16)

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Phone Agent v0.2"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }

        // ---- Providers ----
        val provLabel = TextView(this).apply {
            text = "Providers (upar wala pehle chalta hai; limit/error aaye to agla apne aap)"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, pad, 0, 0)
        }
        providersBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val keyField = EditText(this).apply {
            hint = "API key (Gemini AIza... / OpenRouter sk-or-... / Groq gsk_... / custom)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val modelsField = EditText(this).apply {
            hint = "Model(s), comma se. Khali = auto (Gemini/OpenRouter)"
        }
        val baseField = EditText(this).apply {
            hint = "Base URL (sirf custom provider ke liye)"
        }
        val addBtn = Button(this).apply {
            text = "+ Provider jodo"
            setOnClickListener {
                val key = keyField.text.toString().trim()
                if (key.isEmpty()) {
                    toast("API key daalo")
                    return@setOnClickListener
                }
                val type = Config.detectType(key)
                val base = if (type == "custom") baseField.text.toString().trim()
                else Config.presetBase(type)
                val models = Config.splitModels(modelsField.text.toString())
                if (type == "custom" && base.isEmpty()) {
                    toast("Is key ke liye Base URL chahiye")
                    return@setOnClickListener
                }
                if ((type == "groq" || type == "custom") && models.isEmpty()) {
                    toast("Is provider ke liye model ka naam do")
                    return@setOnClickListener
                }
                if (models.any { it.contains(' ') }) {
                    toast("Model ka slug likho (jaise vendor/model-name), display name nahi")
                    return@setOnClickListener
                }
                val list = Config.load(this@MainActivity).filter { it.key != key } +
                    Provider(type, key, base, models)
                Config.save(this@MainActivity, list)
                keyField.setText("")
                modelsField.setText("")
                baseField.setText("")
                refreshProviders()
                toast("Jod diya: $type")
            }
        }

        // ---- Goal + controls ----
        val goal = EditText(this).apply {
            hint = "Goal ya (agent chal raha ho to) message"
            minLines = 2
        }
        val btnAccess = Button(this).apply {
            text = "1. Accessibility settings kholo"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        val btnStart = Button(this).apply {
            text = "2. Agent start / message bhejo"
            setOnClickListener {
                val svc = AgentAccessibilityService.instance
                if (svc == null) {
                    toast("Pehle Accessibility me Phone Agent ko on karo")
                    return@setOnClickListener
                }
                val g = goal.text.toString().trim()
                if (g.isEmpty()) {
                    toast("Goal likho")
                    return@setOnClickListener
                }
                if (Config.load(this@MainActivity).isEmpty()) {
                    toast("Pehle upar koi provider jodo")
                    return@setOnClickListener
                }
                AgentLoop.submit(svc, g)
            }
        }
        val btnStop = Button(this).apply {
            text = "Stop"
            setOnClickListener { AgentLoop.stop() }
        }
        val bubbleSwitch = Switch(this).apply {
            text = "Floating icon dikhao"
            isChecked = Config.bubbleEnabled(this@MainActivity)
            setOnCheckedChangeListener { _, on -> Config.setBubble(this@MainActivity, on) }
        }

        logView = TextView(this).apply {
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        page.addView(title)
        page.addView(provLabel)
        page.addView(providersBox)
        page.addView(keyField)
        page.addView(modelsField)
        page.addView(baseField)
        page.addView(addBtn)
        page.addView(goal)
        page.addView(btnAccess)
        page.addView(btnStart)
        page.addView(btnStop)
        page.addView(bubbleSwitch)
        page.addView(logView)

        setContentView(
            ScrollView(this).apply {
                addView(
                    page,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        )
        refreshProviders()
    }

    private fun refreshProviders() {
        providersBox.removeAllViews()
        val list = Config.load(this)
        if (list.isEmpty()) {
            providersBox.addView(TextView(this).apply { text = "(abhi koi provider nahi)" })
            return
        }
        for ((i, p) in list.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val models = if (p.models.isEmpty()) "auto" else p.models.joinToString(", ")
            val info = TextView(this).apply {
                text = "${i + 1}. ${p.type}  ..${p.key.takeLast(4)}\n    $models"
                textSize = 13f
            }
            val up = Button(this).apply {
                text = "↑"
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    if (i > 0) {
                        val m = list.toMutableList()
                        val t = m[i]
                        m[i] = m[i - 1]
                        m[i - 1] = t
                        Config.save(this@MainActivity, m)
                        refreshProviders()
                    }
                }
            }
            val del = Button(this).apply {
                text = "Hatao"
                minWidth = 0
                minimumWidth = 0
                setOnClickListener {
                    Config.save(this@MainActivity, list.filterIndexed { idx, _ -> idx != i })
                    refreshProviders()
                }
            }
            row.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(up)
            row.addView(del)
            providersBox.addView(row)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshProviders()
        logView.text = AgentLog.lines.joinToString("\n")
        AgentLog.addListener(logListener)
    }

    override fun onPause() {
        super.onPause()
        AgentLog.removeListener(logListener)
    }
}
