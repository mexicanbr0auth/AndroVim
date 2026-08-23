package com.androvim.ui

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.androvim.data.ToolCatalog
import com.androvim.pkg.PkgManager
import com.androvim.ui.Ui.dp
import java.util.concurrent.atomic.AtomicBoolean

class ToolsActivity : Activity() {

    private lateinit var content: LinearLayout
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (c, _) = Ui.scrollPage(this, getString(com.androvim.R.string.tools_title))
        content = c

        content.addView(Ui.mutedLabel(
            this,
            "Pacotes do repositório Termux instalados dentro do AndroVim. " +
                "Ficam disponíveis no terminal (:!comando) e para os plugins.",
        ).apply { setPadding(0, 0, 0, dp(10, this@ToolsActivity)) })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!busy.get()) refresh()
    }

    private fun refresh() {
        content.removeViews(1, content.childCount - 1)
        for (tool in ToolCatalog.ALL) {
            content.addView(toolCard(tool))
        }
    }

    private fun toolCard(tool: com.androvim.data.ToolPackage): View {
        val card = Ui.card(this)
        card.addView(Ui.label(this, tool.label, 14f, bold = true))
        card.addView(Ui.mutedLabel(this, tool.description).also {
            it.setPadding(0, dp(2, this), 0, dp(8, this))
        })

        val statusLine = TextView(this).apply {
            setTextColor(Ui.MUTED)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
        }
        val installed = PkgManager.isInstalled(this, tool.pkgName)
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val installBtn = Ui.button(this, if (installed) "[instalado]" else "Instalar") {
            if (installed || !busy.compareAndSet(false, true)) return@button
            runInstall(tool.pkgName, statusLine) { refresh() }
        }
        btnRow.addView(installBtn)

        if (installed) {
            val removeBtn = Ui.button(this, "Remover", Ui.DANGER) {
                if (!busy.compareAndSet(false, true)) return@button
                statusLine.visibility = View.VISIBLE
                statusLine.text = "Removendo…"
                Thread {
                    var ok = false
                    try {
                        PkgManager.uninstall(this@ToolsActivity, tool.pkgName)
                        postStatus(statusLine, "Removido.")
                        ok = true
                    } catch (e: Exception) {
                        postStatus(statusLine, "Erro: ${e.message}")
                    } finally {
                        busy.set(false)
                        runOnUiThread { if (ok) refresh() }
                    }
                }.start()
            }
            (removeBtn.layoutParams as? LinearLayout.LayoutParams)
                ?.leftMargin = dp(6, this)
            btnRow.addView(removeBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }

        card.addView(btnRow)
        card.addView(statusLine)
        return card
    }

    private fun runInstall(pkgName: String, statusLine: TextView, done: () -> Unit) {
        statusLine.visibility = View.VISIBLE
        statusLine.text = "Baixando…"
        Thread {
            var ok = false
            try {
                PkgManager.install(this@ToolsActivity, listOf(tool.pkgName)) { msg ->
                    postStatus(statusLine, msg)
                }
                postStatus(statusLine, "Concluído ✓")
                ok = true
            } catch (e: Exception) {
                postStatus(statusLine, "Erro: ${e.message}")
            } finally {
                busy.set(false)
                // keep the message visible; rebuild cards on next resume/refresh
                runOnUiThread { if (ok) done() }
            }
        }.start()
    }

    private fun postStatus(view: TextView, msg: String) = runOnUiThread {
        view.text = msg
        view.setTextColor(if (msg.startsWith("Erro")) Ui.DANGER else Ui.MUTED)
    }
}
