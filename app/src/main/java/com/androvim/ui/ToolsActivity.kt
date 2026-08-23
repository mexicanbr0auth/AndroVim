package com.androvim.ui

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.androvim.data.ToolCatalog
import com.androvim.pkg.PkgManager
import com.androvim.ui.Ui.dp
import java.util.concurrent.atomic.AtomicBoolean

class ToolsActivity : Activity() {

    private lateinit var content: LinearLayout
    private lateinit var console: TextView
    private lateinit var consoleScroll: ScrollView
    private val busy = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setPadding(dp(14, this@ToolsActivity), dp(10, this@ToolsActivity), dp(14, this@ToolsActivity), dp(10, this@ToolsActivity))
        }

        val title = TextView(this).apply {
            text = getString(com.androvim.R.string.tools_title)
            textSize = 18f
            setTextColor(Ui.FG)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4, this@ToolsActivity))
        }
        root.addView(title)

        root.addView(Ui.mutedLabel(
            this,
            "Pacotes do repositório Termux instalados dentro do AndroVim. " +
                "Ficam disponíveis no terminal (:!comando) e para os plugins.",
        ).apply { setPadding(0, 0, 0, dp(8, this@ToolsActivity)) })

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        topRow.addView(Ui.button(this, "\u21bb Atualizar catálogo") {
            if (!busy.compareAndSet(false, true)) return@button
            log("atualizando catálogo do repositório…")
            Thread {
                val t0 = System.currentTimeMillis()
                try {
                    val index = PkgManager.loadIndex(this@ToolsActivity, forceRefresh = true) { msg -> log(msg) }
                    log("catálogo pronto: ${index.size} pacotes em ${(System.currentTimeMillis() - t0) / 1000.0}s")
                } catch (e: Exception) {
                    log("ERRO ao atualizar catálogo: ${e.message}")
                } finally {
                    busy.set(false)
                }
            }.start()
        })
        root.addView(topRow)

        val cardsScroll = ScrollView(this).apply { layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f,
        ) }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8, this@ToolsActivity), 0, dp(8, this@ToolsActivity))
        }
        cardsScroll.addView(content)
        root.addView(cardsScroll)

        consoleScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(150, this@ToolsActivity),
            ).also { it.topMargin = dp(6, this@ToolsActivity) }
            setBackgroundColor(0xFF0B0E12.toInt())
            isFillViewport = true
        }
        console = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(0xFF9FE29F.toInt())
            setPadding(dp(8, this@ToolsActivity), dp(8, this@ToolsActivity), dp(8, this@ToolsActivity), dp(8, this@ToolsActivity))
            setTextIsSelectable(true)
        }
        consoleScroll.addView(console)
        root.addView(consoleScroll)

        // ---- command line ("mini terminal") -------------------------------------
        val cmdRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF0B0E12.toInt())
            setPadding(dp(6, this@ToolsActivity), dp(4, this@ToolsActivity), dp(6, this@ToolsActivity), dp(4, this@ToolsActivity))
        }
        val prompt = TextView(this).apply {
            text = "$"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(0xFF9FE29F.toInt())
            setPadding(dp(4, this@ToolsActivity), 0, dp(6, this@ToolsActivity), 0)
        }
        val input = android.widget.EditText(this).apply {
            hint = "instalar git · procurar python · ajuda"
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextColor(0xFFE6ECEF.toInt())
            setHintTextColor(0xFF5A6672.toInt())
            setBackgroundColor(0x00000000)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        }
        input.setOnEditorActionListener { _, _, _ -> submit(input); true }
        val sendBtn = Ui.button(this, "▶") { submit(input) }
        cmdRow.addView(prompt)
        cmdRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        cmdRow.addView(sendBtn)
        root.addView(cmdRow)

        setContentView(root)
        log("gerenciador pronto; toque em Instalar, use ↻ ou digite um comando")
        refresh()
    }

    private fun submit(input: android.widget.EditText) {
        val raw = input.text.toString().trim()
        if (raw.isEmpty()) return
        if (!busy.compareAndSet(false, true)) {
            log("ocupado — aguarde o comando atual terminar")
            return
        }
        input.setText("")
        log("$ ${raw.replace('\n', ' ')}")
        Thread { execCommand(raw) }.start()
    }

    private fun execCommand(raw: String) {
        val parts = raw.split(Regex("\\s+"))
        val cmd = parts[0].lowercase()
        val args = parts.drop(1)
        try {
            when (cmd) {
                "ajuda", "help" -> {
                    log("comandos:")
                    log("  instalar <pkg> [pkg…]   instala pacote(s) + dependências")
                    log("  remover <pkg>           remove pacote")
                    log("  atualizar               força atualização do catálogo")
                    log("  procurar <termo>        busca no repositório (máx 15)")
                    log("  lista [filtro]          lista instalados")
                    log("  limpar                  limpa este console")
                }
                "limpar", "clear" -> runOnUiThread { console.text = "" }
                "atualizar", "update" -> {
                    val t0 = System.currentTimeMillis()
                    val index = PkgManager.loadIndex(this, forceRefresh = true) { m -> log(m) }
                    log("catálogo pronto: ${index.size} pacotes em ${(System.currentTimeMillis() - t0) / 1000.0}s")
                }
                "procurar", "search" -> {
                    if (args.isEmpty()) { log("uso: procurar <termo>"); return }
                    val term = args.joinToString(" ").lowercase()
                    val index = PkgManager.loadIndex(this) { m -> log(m) }
                    index.values.filter {
                        it.name.contains(term) || it.description.lowercase().contains(term)
                    }.sortedBy { it.name }.take(15).forEach {
                        log("${it.name} (${it.version}) ${if (it.description.isNotBlank()) "— ${it.description.take(70)}" else ""}")
                    }
                    log("---")
                }
                "lista", "list" -> {
                    val filter = args.joinToString(" ").lowercase()
                    val installed = PkgManager.installedMap(this)
                    var n = 0
                    installed.keys.sorted().forEach { name ->
                        if (filter.isEmpty() || name.contains(filter)) {
                            val ver = installed[name]?.optString("version") ?: "?"
                            log("$name ($ver)")
                            n++
                        }
                    }
                    log("$n pacote(s) instalado(s)")
                }
                "remover", "remove" -> {
                    if (args.isEmpty()) { log("uso: remover <pkg>"); return }
                    for (name in args) {
                        log("removendo $name…")
                        PkgManager.uninstall(this, name)
                        log("$name removido ✓")
                    }
                    runOnUiThread { refresh() }
                }
                "instalar", "install", "pkg", "apt" -> {
                    if (args.isEmpty()) { log("uso: instalar <pkg> [pkg…]"); return }
                    PkgManager.install(this, args) { m -> log(m) }
                    log("concluído ✓")
                    runOnUiThread { refresh() }
                }
                else -> log("comando desconhecido '$cmd' — digite 'ajuda'")
            }
        } catch (e: Exception) {
            log("ERRO: ${e.message}")
        } finally {
            busy.set(false)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!busy.get()) refresh()
    }

    private fun log(msg: String) = runOnUiThread {
        console.append("$msg\n")
        if (console.length() > 24000) {
            console.text = console.text.substring(console.length() - 16000)
        }
        consoleScroll.post { consoleScroll.smoothScrollTo(0, console.height) }
    }

    private fun refresh() {
        content.removeViews(0, content.childCount)
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
            statusLine.visibility = View.VISIBLE
            log("== instalando ${tool.pkgName} ==")
            runInstall(tool.pkgName, statusLine) { refresh() }
        }
        btnRow.addView(installBtn)

        if (installed) {
            val removeBtn = Ui.button(this, "Remover", Ui.DANGER) {
                if (!busy.compareAndSet(false, true)) return@button
                statusLine.visibility = View.VISIBLE
                statusLine.text = "Removendo…"
                log("== removendo ${tool.pkgName} ==")
                Thread {
                    var ok = false
                    try {
                        PkgManager.uninstall(this@ToolsActivity, tool.pkgName)
                        postStatus(statusLine, "Removido.")
                        log("${tool.pkgName}: removido")
                        ok = true
                    } catch (e: Exception) {
                        postStatus(statusLine, "Erro: ${e.message}")
                        log("ERRO removendo ${tool.pkgName}: ${e.message}")
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
        statusLine.text = "Iniciando…"
        Thread {
            var ok = false
            try {
                PkgManager.install(this@ToolsActivity, listOf(pkgName)) { msg ->
                    postStatus(statusLine, msg)
                    log(msg)
                }
                postStatus(statusLine, "Concluído ✓")
                log("$pkgName: instalação concluída ✓")
                ok = true
            } catch (e: Exception) {
                postStatus(statusLine, "Erro: ${e.message}")
                log("ERRO instalando $pkgName: ${e.message}")
            } finally {
                busy.set(false)
                runOnUiThread { if (ok) done() }
            }
        }.start()
    }

    private fun postStatus(view: TextView, msg: String) = runOnUiThread {
        view.text = msg
        view.setTextColor(if (msg.startsWith("Erro")) Ui.DANGER else Ui.MUTED)
    }
}
