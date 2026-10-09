package com.example.phoneagent

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView

/**
 * UI helpers + LoRA ka design system (rang, card, button, pill, tile...).
 * App screens aur floating panel dono yahi use karte hain. Purane helpers (chip, rounded, circle) jaise the waise hain.
 */
object Ui {
    fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    fun rounded(color: Int, radius: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius.toFloat()
    }

    fun circle(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    // ---- floating panel ke purane rang ----
    val GREY = Color.parseColor("#3C4043")
    val GREEN = Color.parseColor("#1E8E3E")
    val RED = Color.parseColor("#D93025")
    val BLUE = Color.parseColor("#1A73E8")

    // ---- app design system ----
    val BG = Color.parseColor("#F4F5FA")
    val SURFACE = Color.WHITE
    val PRIMARY = Color.parseColor("#4F46E5")
    val PRIMARY_SOFT = Color.parseColor("#E9E8FF")
    val TEXT = Color.parseColor("#1B1D2A")
    val MUTED = Color.parseColor("#6B7085")
    val LINE = Color.parseColor("#E3E5EF")
    val OK = Color.parseColor("#1E8E3E")
    val OK_SOFT = Color.parseColor("#E3F4E8")
    val WARN = Color.parseColor("#B26A00")
    val WARN_SOFT = Color.parseColor("#FFF1D6")
    val ERR = Color.parseColor("#D93025")
    val ERR_SOFT = Color.parseColor("#FDE7E5")
    val INFO_SOFT = Color.parseColor("#E5EEFD")

    fun chip(
        ctx: Context,
        label: String,
        bg: Int = GREY,
        fg: Int = Color.WHITE,
        size: Float = 12f,
        onClick: () -> Unit
    ): TextView = TextView(ctx).apply {
        text = label
        textSize = size
        setTextColor(fg)
        gravity = Gravity.CENTER
        setPadding(dp(ctx, 8), dp(ctx, 7), dp(ctx, 8), dp(ctx, 7))
        background = rounded(bg, dp(ctx, 14))
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        setOnClickListener { onClick() }
    }

    fun setChipColor(chip: TextView?, color: Int) {
        (chip?.background as? GradientDrawable)?.setColor(color)
    }

    // ---------- layout helpers ----------

    fun lp(ctx: Context, top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0, weight: Float = 0f): LinearLayout.LayoutParams {
        val p = if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
        else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        p.setMargins(dp(ctx, left), dp(ctx, top), dp(ctx, right), dp(ctx, bottom))
        return p
    }

    fun vbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    fun hbox(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** Safed rounded card (halki shadow ke saath). */
    fun card(ctx: Context, tint: Int = SURFACE, pad: Int = 14): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(tint, dp(ctx, 16))
        setPadding(dp(ctx, pad), dp(ctx, pad), dp(ctx, pad), dp(ctx, pad))
        elevation = dp(ctx, 2).toFloat()
    }

    fun text(ctx: Context, t: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    fun title(ctx: Context, t: String): TextView = text(ctx, t, 24f, TEXT, true)

    fun section(ctx: Context, t: String): TextView = text(ctx, t, 13f, MUTED, true).apply {
        setPadding(dp(ctx, 4), dp(ctx, 18), 0, dp(ctx, 6))
        letterSpacing = 0.05f
    }

    fun note(ctx: Context, t: String): TextView = text(ctx, t, 12f, MUTED).apply {
        setPadding(0, dp(ctx, 4), 0, dp(ctx, 2))
    }

    /** Chhota rangeen label (status, badge). */
    fun pill(ctx: Context, t: String, bg: Int, fg: Int): TextView = TextView(ctx).apply {
        text = t
        textSize = 11.5f
        setTextColor(fg)
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(ctx, 10), dp(ctx, 4), dp(ctx, 10), dp(ctx, 4))
        background = rounded(bg, dp(ctx, 12))
        maxLines = 1
    }

    fun restyle(pill: TextView, t: String, bg: Int, fg: Int) {
        pill.text = t
        pill.setTextColor(fg)
        (pill.background as? GradientDrawable)?.setColor(bg)
    }

    /** kind: primary | tonal | outline | danger */
    fun button(ctx: Context, label: String, kind: String = "primary", onClick: () -> Unit): TextView {
        val bg: Int
        val fg: Int
        when (kind) {
            "primary" -> { bg = PRIMARY; fg = Color.WHITE }
            "danger" -> { bg = ERR; fg = Color.WHITE }
            "outline" -> { bg = SURFACE; fg = PRIMARY }
            else -> { bg = PRIMARY_SOFT; fg = PRIMARY }
        }
        return TextView(ctx).apply {
            text = label
            textSize = 13.5f
            setTextColor(fg)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            background = if (kind == "outline") GradientDrawable().apply {
                setColor(bg)
                cornerRadius = dp(ctx, 12).toFloat()
                setStroke(dp(ctx, 1), PRIMARY)
            } else rounded(bg, dp(ctx, 12))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    /** Dashboard ka bada tile: emoji + naam + chhota subtitle. */
    fun tile(ctx: Context, emoji: String, name: String, sub: String, onClick: () -> Unit): LinearLayout =
        card(ctx, SURFACE, 12).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            isClickable = true
            setOnClickListener { onClick() }
            addView(text(ctx, emoji, 26f).apply { gravity = Gravity.CENTER })
            addView(text(ctx, name, 13.5f, TEXT, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(ctx, 4), 0, 0)
            })
            addView(text(ctx, sub, 11f, MUTED).apply { gravity = Gravity.CENTER })
        }

    /** Title + subtitle + Switch ek row me. */
    fun switchRow(ctx: Context, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): LinearLayout =
        hbox(ctx).apply {
            setPadding(0, dp(ctx, 8), 0, dp(ctx, 8))
            val col = vbox(ctx)
            col.addView(text(ctx, title, 14f, TEXT, true))
            if (sub.isNotEmpty()) col.addView(text(ctx, sub, 12f, MUTED))
            addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(Switch(ctx).apply {
                isChecked = checked
                setOnCheckedChangeListener { _, on -> onChange(on) }
            })
        }

    /** Tap karne par khulne wala (accordion) card. content pehle se bana hua view. */
    fun collapsible(ctx: Context, emoji: String, name: String, hint: String, content: View, open: Boolean = false): LinearLayout =
        card(ctx).apply {
            val arrow = text(ctx, if (open) "▾" else "▸", 16f, MUTED)
            val head = hbox(ctx)
            val col = vbox(ctx)
            col.addView(text(ctx, "$emoji  $name", 15.5f, TEXT, true))
            if (hint.isNotEmpty()) col.addView(text(ctx, hint, 12f, MUTED))
            head.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            head.addView(arrow)
            content.visibility = if (open) View.VISIBLE else View.GONE
            head.isClickable = true
            head.setOnClickListener {
                val show = content.visibility != View.VISIBLE
                content.visibility = if (show) View.VISIBLE else View.GONE
                arrow.text = if (show) "▾" else "▸"
            }
            addView(head)
            addView(content, lp(ctx, top = 8))
        }

    /** Progress bar: fraction 0..1. */
    fun bar(ctx: Context, fraction: Float, color: Int = PRIMARY): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = rounded(LINE, dp(ctx, 4))
        val f = fraction.coerceIn(0f, 1f)
        val fill = View(ctx).apply { background = rounded(color, dp(ctx, 4)) }
        addView(fill, LinearLayout.LayoutParams(0, dp(ctx, 8), if (f <= 0f) 0.0001f else f))
        addView(View(ctx), LinearLayout.LayoutParams(0, dp(ctx, 8), if (f >= 1f) 0.0001f else 1f - f))
    }

    /** Equal-width buttons ki ek row. */
    fun row(ctx: Context, vararg views: View, gap: Int = 6): LinearLayout = hbox(ctx).apply {
        for ((i, v) in views.withIndex()) addView(v, lp(ctx, weight = 1f, left = if (i == 0) 0 else gap))
    }
}
