package com.androvim.core

import android.content.Context
import android.util.Log
import com.androvim.NvimRuntime
import com.androvim.data.PluginCatalog
import java.io.File

/** Installs/removes Neovim plugins into a packpack-style directory and
 *  regenerates ~/.config/nvim/androvim.lua so everything auto-configures. */
object PluginOps {

    data class Installed(val id: String, val dirName: String)

    fun installed(context: Context): List<Installed> =
        NvimRuntime.pluginPackDir(context)
            .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { Installed(markerToId(it) ?: it.name.lowercase(), it.name) }
            ?.sortedBy { it.id }
            ?: emptyList()

    fun isInstalled(context: Context, id: String): Boolean =
        installed(context).any { it.id == id }

    fun repoUrl(plugin: com.androvim.data.NvimPlugin): String =
        if (plugin.repo.contains("://")) plugin.repo
        else "https://github.com/${plugin.repo}.git"

    /** Install a catalog plugin (with its dependencies). Returns summary. */
    fun installCatalogPlugin(
        context: Context,
        id: String,
        progress: (String) -> Unit,
    ): String {
        val plugin = PluginCatalog.byId(id) ?: throw Exception("plugin desconhecido: $id")
        val order = mutableListOf(plugin)
        for (depId in plugin.dependsOn.reversed()) {
            PluginCatalog.byId(depId)?.let { dep ->
                if (!isInstalled(context, depId)) order.add(0, dep)
            }
        }
        for (p in order.distinctBy { it.id }) {
            if (!isInstalled(context, p.id)) {
                progress("Clonando ${p.label}…")
                clone(context, repoUrl(p), p.label.lowercase().replace(" ", "-"), p.id)
            }
        }
        regenerateConfig(context)
        return "${order.size} plugin(s) instalado(s): ${order.joinToString(", ") { it.label }}"
    }

    /** Manual install from "user/repo" or a full https URL. */
    fun installManual(
        context: Context,
        input: String,
        progress: (String) -> Unit,
    ): String {
        var url = input.trim().removeSuffix("/")
        if (url.matches(Regex("^[\\w.-]+/[\\w.-]+$"))) {
            url = "https://github.com/$url"
        }
        if (!url.startsWith("http")) throw Exception("use 'usuario/repo' ou uma URL https")
        val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git")
        if (name.isEmpty()) throw Exception("não consegui deduzir o nome do repositório")
        progress("Clonando $name…")
        clone(context, url.removeSuffix(".git") + ".git", name, null)
        regenerateConfig(context)
        return "$name instalado"
    }

    fun remove(context: Context, dirName: String) {
        val dir = File(NvimRuntime.pluginPackDir(context), dirName)
        if (!dir.deleteRecursively()) throw Exception("não foi possível remover $dirName")
        regenerateConfig(context)
    }

    // ---- internals -------------------------------------------------------------

    private fun clone(context: Context, url: String, dirName: String, markerId: String?) {
        val target = File(NvimRuntime.pluginPackDir(context), dirName)
        if (target.exists()) {
            if (markerId == null) throw Exception("$dirName já está instalado")
            return
        }
        GitRunner.cloneInto(context, url.removeSuffix(".git") + ".git", target)
        markerId?.let { File(target, ".androvim-plugin-id").writeText("$it\n") }
    }

    private fun markerToId(dir: File): String? =
        runCatching { File(dir, ".androvim-plugin-id").readText().trim() }.getOrNull()

    /** Rewrite androvim.lua with config snippets of every installed plugin. */
    private fun regenerateConfig(context: Context) {
        val file = NvimRuntime.managedConfigFile(context)
        file.parentFile?.mkdirs()
        val sb = StringBuilder()
        sb.appendLine("-- Gerado automaticamente pelo AndroVim — não edite à mão.")
        sb.appendLine("-- Reinstale/remova plugins pela aba Plugins para atualizar este arquivo.")
        for (inst in installed(context)) {
            val plugin = PluginCatalog.byId(inst.id)
            sb.appendLine()
            sb.appendLine("-- ${plugin?.label ?: inst.dirName}")
            sb.appendLine("do -- ${inst.dirName}")
            when {
                plugin?.configLua != null -> sb.appendLine(indentLua(plugin.configLua))
                plugin != null -> sb.appendLine("-- sem configuração necessária")
                else ->
                    sb.appendLine(
                        "-- Plugin manual: adicione aqui seu require/setup se necessário.\n" +
                            "-- Ex.: require(\"${inst.dirName}\").setup({})",
                    )
            }
            sb.appendLine("end")
        }
        file.writeText(sb.toString())
        Log.i("AndroVim", "androvim.lua regenerado (${installed(context).size} plugins)")
    }

    private fun indentLua(snippet: String): String =
        snippet.lines().joinToString("\n") { if (it.isBlank()) "" else "  $it" }
}
