package com.androvim.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Small helpers so every settings screen looks consistent without material libs. */
object Ui {

    const val BG = 0xFF10141A.toInt()
    const val CARD = 0xFF171E26.toInt()
    const val FG = 0xFFD5DAE0.toInt()
    const val MUTED = 0xFF8A97A5.toInt()
    const val ACCENT = 0xFF7FBF7F.toInt()
    const val DANGER = 0xFFE06C75.toInt()

    fun dp(v: Int, ctx: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics)
            .toInt()

    fun rounded(ctx: Context, color: Int, radiusDp: Float = 10f): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp.toInt(), ctx).toFloat()
        }

    fun label(ctx: Context, text: String, size: Float = 15f, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            setText(text)
            setTextColor(FG)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    fun mutedLabel(ctx: Context, text: String, size: Float = 12f): TextView =
        TextView(ctx).apply {
            setText(text)
            setTextColor(MUTED)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        }

    @SuppressLint("SetTextI18n")
    fun button(
        ctx: Context,
        text: String,
        color: Int = FG,
        onClick: () -> Unit,
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minWidth = dp(56, ctx)
        minHeight = dp(34, ctx)
        setPadding(dp(14, ctx), dp(6, ctx), dp(14, ctx), dp(6, ctx))
        background = rounded(ctx, 0xFF1F2933.toInt(), 8f)
        setOnClickListener { onClick() }
    }

    /** Vertical stack inside a scrolling container. */
    fun scrollPage(activity: android.app.Activity, title: String): Pair<LinearLayout, ScrollView> {
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16, activity), dp(12, activity), dp(16, activity), dp(24, activity))
        }
        val scroll = ScrollView(activity).apply { addView(content) }
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            addView(header(activity, title))
            addView(scroll, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
            ))
        }
        activity.setContentView(root)
        return content to scroll
    }

    fun header(ctx: Context, title: String): View =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14, ctx), dp(10, ctx), dp(14, ctx), dp(10, ctx))
            setBackgroundColor(0xFF161B22.toInt())
            elevation = dp(4, ctx).toFloat()
            addView(TextView(ctx).apply {
                text = "\u2630 "
                setTextColor(MUTED)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            })
            addView(label(ctx, title, 17f, bold = true))
        }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12, ctx), dp(10, ctx), dp(12, ctx), dp(10, ctx))
        background = rounded(ctx, CARD)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.bottomMargin = dp(8, ctx) }
    }
}
