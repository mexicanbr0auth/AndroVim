package com.androvim.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.androvim.R
import com.androvim.core.GitRunner
import com.androvim.core.PluginOps
import com.androvim.data.PluginCatalog
import com.androvim.ui.Ui.dp
import java.util.concurrent.atomic.AtomicBoolean

class PluginsActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var gitBanner: LinearLayout
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (c, _) = Ui.scrollPage(this, getString(R.string.plugins_title))
        content = c

        content.addView(Ui.mutedLabel(
            this,
            "Plugins são clonados em ~/.local/share/nvim/site/pack/androvim/start e " +
                "configurados automaticamente via androvim.lua. Reinicie o nvim após instalar.",
        ).apply { setPadding(0, 0, 0, dp(10, this)) })

        gitBanner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = Ui.rounded(this@PluginsActivity, 0xFF3A2A20.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).also { it.bottomMargin = dp(8) }
        }
        content.addView(gitBanner)
        updateGitBanner()

        // ---- instalação manual ---------------------------------------------------
        val manualCard = Ui.card(this)
        manualCard.addView(Ui.label(this, "Instalar por URL", 14f, bold = true))
        manualCard.addView(Ui.mutedLabel(this, "\"usuario/repo\" do GitHub ou URL https completa"))
        val input = EditText(this).apply {
            hint = "ex.: folke/zen-mode.nvim"
            setTextColor(Ui.FG)
            setHintTextColor(Ui.MUTED)
            background = Ui.rounded(this@PluginsActivity, 0xFF0D1117.toInt(), 6f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            (layoutParams as? LinearLayout.LayoutParams)?.setMargins(0, dp(6), 0, dp(6))
        }
        manualCard.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).also { it.setMargins(0, dp(6), 0, dp(6)) })
        val manualStatus = TextView(this).apply {
            setTextColor(Ui.MUTED)
            visibility = View.GONE
        }
        manualCard.addView(Ui.button(this, "Clonar e instalar") {
            val value = input.text.toString().trim()
            if (value.isEmpty() || !busy.compareAndSet(false, true)) return@Ui.button
            manualStatus.visibility = View.VISIBLE
            manualStatus.setTextColor(Ui.MUTED)
            manualStatus.text = "Clonando…"
            Thread {
                try {
                    val msg = PluginOps.installManual(this@PluginsActivity, value) { m ->
                        runOnUiThread { manualStatus.text = m }
                    }
                    runOnUiThread {
                        manualStatus.setTextColor(Ui.ACCENT)
                        manualStatus.text = "✓ $msg — reinicie o nvim"
                        input.setText("")
                        refresh()
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        manualStatus.setTextColor(Ui.DANGER)
                        manualStatus.text = "Erro: ${e.message}"
                    }
                } finally {
                    busy.set(false)
                }
            }.start()
        })
        manualCard.addView(manualStatus)
        content.addView(manualCard)

        refresh()
    }

    private fun updateGitBanner() {
        val hasGit = GitRunner.available(this)
        gitBanner.visibility = if (hasGit) View.GONE else View.VISIBLE
        if (!hasGit) {
            gitBanner.removeAllViews()
            gitBanner.addView(Ui.label(this, "⚠ O git não está instalado.", 14f, bold = true).also {
                it.setTextColor(0xFFF0C674.toInt())
            })
            gitBanner.addView(Ui.mutedLabel(this, "Necessário para instalar plugins."))
            gitBanner.addView(Ui.button(this, "Instalar o git agora") {
                startActivity(android.content.Intent(this@PluginsActivity, ToolsActivity::class.java))
            }.also {
                (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8)
            })
        }
    }

    override fun onResume() {
        super.onResume()
        updateGitBanner()
        if (!busy.get()) refresh()
    }

    private fun refresh() {
        content.removeViews(content.childCount - catalogCount(), catalogCount())
        for (plugin in PluginCatalog.ALL) {
            content.addView(pluginCard(plugin))
        }
    }

    private fun catalogCount(): Int = PluginCatalog.ALL.size

    private fun pluginCard(plugin: PluginCatalog.NvimPlugin): View {
        val card = Ui.card(this)
        val headerRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        headerRow.addView(Ui.label(this, plugin.label, 14f, bold = true))

        val installed = PluginOps.isInstalled(this, plugin.id)
        if (installed) {
            headerRow.addView(Ui.mutedLabel(this, "   ✓ instalado").also {
                it.setTextColor(Ui.ACCENT)
            })
        }
        card.addView(headerRow)
        card.addView(Ui.mutedLabel(this, plugin.description).also {
            it.setPadding(0, dp(2), 0, dp(4))
        })
        if (plugin.dependsOn.isNotEmpty()) {
            card.addView(Ui.mutedLabel(
                this,
                "Depende de: ${plugin.dependsOn.joinToString(", ")}",
            ).apply { setPadding(0, 0, 0, dp(2)) })
        }

        val statusLine = TextView(this).apply {
            setTextColor(Ui.MUTED)
            visibility = View.GONE
        }

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Info") { showInfo(plugin) })
        if (installed) {
            addToRow(row, "Remover", Ui.DANGER) {
                if (!busy.compareAndSet(false, true)) return@addToRow
                statusLine.visibility = View.VISIBLE
                statusLine.text = "Removendo…"
                Thread {
                    var ok = false
                    try {
                        PluginOps.remove(this@PluginsActivity, dirNameOf(plugin.id))
                        ok = true
                    } catch (e: Exception) {
                        showStatus(statusLine, "Erro: ${e.message}", Ui.DANGER)
                    } finally {
                        busy.set(false)
                        runOnUiThread { if (ok) refresh() }
                    }
                }.start()
            }
        } else {
            addToRow(row, "Instalar", Ui.ACCENT) {
                if (!busy.compareAndSet(false, true)) return@addToRow
                statusLine.visibility = View.VISIBLE
                statusLine.text = "Preparando…"
                Thread {
                    var ok = false
                    try {
                        val msg = PluginOps.installCatalogPlugin(this@PluginsActivity, plugin.id) { m ->
                            showStatus(statusLine, m, Ui.MUTED)
                        }
                        ok = true
                        showStatus(statusLine, "✓ $msg", Ui.ACCENT)
                    } catch (e: Exception) {
                        showStatus(statusLine, "Erro: ${e.message}", Ui.DANGER)
                    } finally {
                        busy.set(false)
                        runOnUiThread { if (ok) refresh() }
                    }
                }.start()
            }
        }
        card.addView(row)
        card.addView(statusLine)
        return card
    }

    private fun addToRow(row: LinearLayout, label: String, color: Int, onClick: () -> Unit) {
        val btn = Ui.button(this, label, color, onClick)
        row.addView(btn)
        (btn.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
    }

    private fun showStatus(view: TextView, msg: String, color: Int) = runOnUiThread {
        view.setTextColor(color)
        view.text = msg
    }

    private fun dirNameOf(id: String): String =
        PluginOps.installed(this).firstOrNull { it.id == id }?.dirName ?: id

    private fun showInfo(plugin: PluginCatalog.NvimPlugin) {
        val deps = if (plugin.dependsOn.isEmpty()) ""
        else "\n\nDependências: ${plugin.dependsOn.joinToString(", ")}"
        val config = plugin.configLua?.let { "\n\n--- Config aplicada ---\n$it" } ?: ""
        AlertDialog.Builder(this)
            .setTitle(plugin.label)
            .setMessage("${plugin.repo}\n\n${plugin.description}$deps$config")
            .setPositiveButton("Fechar", null)
            .show()
    }
}
