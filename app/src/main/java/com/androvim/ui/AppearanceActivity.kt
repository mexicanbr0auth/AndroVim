package com.androvim.ui

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.SeekBar
import com.androvim.data.ColorSchemes
import com.androvim.data.Prefs
import com.androvim.ui.Ui.dp

class AppearanceActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)

        val (content, _) = Ui.scrollPage(this, getString(com.androvim.R.string.appearance_title))

        // ---- tamanho da fonte --------------------------------------------------
        content.addView(Ui.label(this, "Tamanho da fonte", 15f, bold = true))
        content.addView(Ui.mutedLabel(this, "Aplicado ao reabrir o terminal"))
        val sizeLabel = Ui.label(this, "", 14f)
        val seek = SeekBar(this).apply { max = 24 - 8 }
        seek.progress = prefs.getInt(Prefs.TEXT_SIZE, 12) - 8
        sizeLabel.text = "  ${seek.progress + 8} sp"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, value: Int, fromUser: Boolean) {
                sizeLabel.text = "  ${value + 8} sp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                prefs.edit().putInt(Prefs.TEXT_SIZE, (sb?.progress ?: 4) + 8).apply()
            }
        })
        content.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(seek, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(sizeLabel)
        })

        content.addView(spacer())

        // ---- fonte -------------------------------------------------------------
        content.addView(Ui.label(this, "Fonte", 15f, bold = true))
        val families = listOf(
            "monospace" to "Monospace",
            "sans-serif-monospace" to "Sans Mono",
            "serif-monospace" to "Serif Mono",
        )
        content.addView(rowOfButtons(
            families,
            prefs.getString(Prefs.FONT_FAMILY, "monospace") ?: "monospace",
        ) { selected ->
            prefs.edit().putString(Prefs.FONT_FAMILY, selected).apply()
        })

        content.addView(spacer())

        // ---- esquema de cores ----------------------------------------------------
        content.addView(Ui.label(this, "Esquema de cores", 15f, bold = true))
        for (scheme in ColorSchemes.ALL) {
            content.addView(schemeCard(scheme.id, scheme.label, scheme.palette))
        }

        content.addView(spacer())

        // ---- teclas extras -------------------------------------------------------
        content.addView(Ui.label(this, "Teclas extras (CTRL/ESC/TAB)", 15f, bold = true))
        val modes = listOf(
            Prefs.EXTRA_KEYS_AUTO to "Auto",
            Prefs.EXTRA_KEYS_SHOW to "Mostrar",
            Prefs.EXTRA_KEYS_HIDE to "Ocultar",
        )
        content.addView(rowOfButtons(
            modes,
            prefs.getString(Prefs.EXTRA_KEYS_MODE, Prefs.EXTRA_KEYS_AUTO) ?: Prefs.EXTRA_KEYS_AUTO,
        ) { selected ->
            prefs.edit().putString(Prefs.EXTRA_KEYS_MODE, selected).apply()
        })

        // ---- manter tela ligada ---------------------------------------------------
        val keepOn = CheckBox(this).apply {
            text = "Manter a tela ligada"
            setTextColor(Ui.FG)
            isChecked = prefs.getBoolean(Prefs.KEEP_SCREEN_ON, false)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean(Prefs.KEEP_SCREEN_ON, checked).apply()
            }
        }
        content.addView(spacer())
        content.addView(keepOn)
    }

    private fun rowOfButtons(
        options: List<Pair<String, String>>,
        current: String,
        onSelect: (String) -> Unit,
    ): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        options.forEach { (value, label) ->
            row.addView(Ui.button(this, if (value == current) "[$label]" else label) {
                onSelect(value)
                recreate() // refresh selection highlight
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also {
                it.rightMargin = dp(6, this)
            })
        }
        return row
    }

    private fun schemeCard(id: String, name: String, palette: IntArray): View {
        val card = Ui.card(this)
        card.setOnClickListener {
            prefs.edit().putString(Prefs.COLOR_SCHEME, id).apply()
            recreate()
        }
        val selected = prefs.getString(Prefs.COLOR_SCHEME, "default") == id
        if (selected) card.background = Ui.rounded(this, 0xFF22303C.toInt())
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(Ui.label(this, name, 14f, bold = true))
        if (selected) header.addView(Ui.mutedLabel(this, "   ✓ selecionado"))
        card.addView(header)
        val swatches = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        palette.take(16).forEach { c ->
            swatches.addView(View(this).apply {
                setBackgroundColor(c)
            }, LinearLayout.LayoutParams(dp(18, this), dp(18, this)).also {
                it.rightMargin = dp(3, this)
            })
        }
        card.addView(swatches)
        return card
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(16, this@AppearanceActivity))
    }
}
