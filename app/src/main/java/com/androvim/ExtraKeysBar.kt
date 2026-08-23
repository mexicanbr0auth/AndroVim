package com.androvim

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class ExtraKeysBar(
    context: Context,
    private val actions: Actions,
) : HorizontalScrollView(context) {

    interface Actions {
        fun sendKey(sequence: String)
        fun syncClipboard()
    }

    var ctrlActive = false
        private set
    var altActive = false
        private set

    private val ctrlButton by lazy { buttons.first { it.text == "CTRL" } }
    private val altButton by lazy { buttons.first { it.text == "ALT" } }
    private val buttons = mutableListOf<TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
        }
        addButton(row, "ESC") { actions.sendKey("\u001b") }
        addButton(row, "TAB") { actions.sendKey("\t") }
        addButton(row, "CTRL") { toggleCtrl() }
        addButton(row, "ALT") { toggleAlt() }
        addButton(row, "\u2191") { actions.sendKey("\u001b[A") }
        addButton(row, "\u2193") { actions.sendKey("\u001b[B") }
        addButton(row, "\u2190") { actions.sendKey("\u001b[D") }
        addButton(row, "\u2192") { actions.sendKey("\u001b[C") }
        addButton(row, "HOME") { actions.sendKey("\u001b[H") }
        addButton(row, "END") { actions.sendKey("\u001b[F") }
        addButton(row, "PGUP") { actions.sendKey("\u001b[5~") }
        addButton(row, "PGDN") { actions.sendKey("\u001b[6~") }
        addButton(row, "|") { actions.sendKey("|") }
        addButton(row, "PASTE") { actions.syncClipboard() }
        addView(row)
    }

    private fun toggleCtrl() {
        ctrlActive = !ctrlActive
        ctrlButton.setTextColor(if (ctrlActive) Color.parseColor("#98C379") else KEY_FG)
    }

    private fun toggleAlt() {
        altActive = !altActive
        altButton.setTextColor(if (altActive) Color.parseColor("#98C379") else KEY_FG)
    }

    private fun addButton(row: LinearLayout, label: String, onClick: () -> Unit) {
        val tv = TextView(context).apply {
            text = label
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(KEY_FG)
            gravity = Gravity.CENTER
            minWidth = dp(42)
            maxHeight = dp(34)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.parseColor(KEY_BG_HEX))
                cornerRadius = dp(7).toFloat()
            }
            setOnClickListener { onClick() }
        }
        buttons.add(tv)
        row.addView(tv)
        row.addView(TextView(context).apply { minWidth = dp(3) })
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val KEY_BG_HEX = "#1B2229"
        private val KEY_FG = Color.parseColor("#B8C4D0")
    }
}
