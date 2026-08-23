package com.androvim

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

private const val TAG = "AndroVim"

object NvimRuntime {

    const val NVIM_LIB = "libnvim.so"

    fun homeDir(context: Context): File = File(context.filesDir, "home").apply { mkdirs() }

    fun runtimeDir(context: Context): File = File(context.filesDir, "runtime")

    fun terminfoDir(context: Context): File = File(context.filesDir, "usr/share/terminfo")

    fun nvimPath(context: Context): String =
        File(context.applicationInfo.nativeLibraryDir, NVIM_LIB).absolutePath

    fun pasteFile(context: Context): File =
        File(homeDir(context), ".androvim-paste")

    /** Extract bundled Neovim runtime from APK assets into the app data dir (idempotent). */
    fun prepare(context: Context) {
        val version = readAssetVersion(context)
        val marker = File(context.filesDir, ".runtime-extracted")
        if (!marker.exists() || marker.readText().trim() != version) {
            Log.i(TAG, "extraindo runtime $version…")
            runtimeDir(context).deleteRecursively()
            terminfoDir(context).deleteRecursively()
            copyAssetDir(context, "runtime", runtimeDir(context))
            try {
                copyAssetDir(context, "terminfo", terminfoDir(context))
            } catch (_: Exception) {
            }
            ensureUserDirs(context)
            marker.writeText(version)
            Log.i(TAG, "runtime extraído")
        }
        ensureUserDirs(context)
        linkTreeSitterParsers(context)
        writeDefaultInit(context)
        Log.i(TAG, "prepare() concluído")
    }

    /** Write the Android clipboard to a file that nvim's clipboard provider can read. */
    fun syncPasteFile(text: String?, context: Context): Boolean {
        if (text.isNullOrEmpty()) return false
        return try {
            pasteFile(context).writeText(if (text.endsWith("\n")) text else text + "\n")
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun readAssetVersion(context: Context): String = try {
        context.assets.open("runtime/androvim-version").use {
            it.readBytes().decodeToString()
        }.trim()
    } catch (_: Exception) {
        "unknown"
    }

    private fun ensureUserDirs(context: Context) {
        val home = homeDir(context)
        listOf(
            ".config/nvim",
            ".local/share",
            ".cache",
        ).forEach { File(home, it).mkdirs() }
        context.cacheDir.mkdirs()
    }

    /**
     * Tree-sitter parsers are shipped as regular libs in nativeLibraryDir
     * (libtree-sitter-<lang>.so); expose them where nvim expects to find them:
     * <runtimepath>/parser/<lang>.so
     */
    private fun linkTreeSitterParsers(context: Context) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val libs = File(nativeDir).listFiles { _, n ->
            (n.startsWith("libtree-sitter-") || n.startsWith("libtree_sitter_")) && n.endsWith(".so")
        } ?: return
        val parserDir = runtimeDir(context).resolve("parser").apply { mkdirs() }
        for (lib in libs) {
            var lang = if (lib.name.startsWith("libtree_sitter_")) {
                lib.name.removePrefix("libtree_sitter_")
            } else {
                lib.name.removePrefix("libtree-sitter-")
            }.removeSuffix(".so").replace("-", "_")
            if (lang.isEmpty()) continue
            val link = File(parserDir, "$lang.so")
            try {
                // delete() works on files and dangling symlinks alike (unlink)
                link.delete()
                Os.symlink(lib.absolutePath, link.absolutePath)
            } catch (_: Exception) {
            }
        }
    }

    /** Termux's nvim shim preloads LuaJIT so dlopen'ed plugin modules can bind. */
    private fun luajitLibPath(context: Context): String? {
        val dir = context.applicationInfo.nativeLibraryDir
        val match = File(dir).listFiles { _, n ->
            n.startsWith("libluajit") && n.endsWith(".so")
        }?.firstOrNull() ?: return null
        return File(dir, match.name).absolutePath
    }

    private fun writeDefaultInit(context: Context) {
        val init = File(homeDir(context), ".config/nvim/init.lua")
        if (init.exists()) return
        init.parentFile?.mkdirs()
        init.writeText(DEFAULT_INIT)
    }

    private fun copyAssetDir(context: Context, name: String, target: File) {
        val assets = context.assets
        val entries = assets.list(name) ?: return
        target.mkdirs()
        if (entries.isEmpty()) {
            assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        for (entry in entries) {
            copyAssetDir(context, "$name/$entry", File(target, entry))
        }
    }

    fun buildEnvironment(context: Context): Array<String> {
        val nativeLib = context.applicationInfo.nativeLibraryDir
        val home = homeDir(context)
        val env = mutableListOf(
            "HOME=${home.absolutePath}",
            "PATH=$nativeLib:/system/bin:/system/xbin",
            "TERM=xterm-256color",
            "COLORTERM=truecolor",
            "LANG=C.UTF-8",
            "TMPDIR=${context.cacheDir.absolutePath}",
            "SHELL=/system/bin/sh",
            "VIMRUNTIME=${runtimeDir(context).absolutePath}",
            "TERMINFO=${terminfoDir(context).absolutePath}",
            "XDG_CONFIG_HOME=${home.absolutePath}/.config",
            "XDG_DATA_HOME=${home.absolutePath}/.local/share",
            "XDG_CACHE_HOME=${home.absolutePath}/.cache",
            "XDG_CONFIG_DIRS=${context.filesDir.absolutePath}/etc/xdg",
            "XDG_DATA_DIRS=${context.filesDir.absolutePath}/share",
            "LUA_CPATH=$nativeLib/?.so;;",
            "ANDROVIM_PASTE_FILE=${pasteFile(context).absolutePath}",
        )
        luajitLibPath(context)?.let { env.add("LD_PRELOAD=$it") }
        return env.toTypedArray()
    }

    private val DEFAULT_INIT = """
        -- AndroVim default configuration (safe to edit or delete)
        vim.g.mapleader = " "
        vim.g.loaded_python3_provider = 0
        vim.g.loaded_perl_provider = 0
        vim.g.loaded_ruby_provider = 0

        local opt = vim.opt
        opt.number = true
        opt.termguicolors = true
        opt.expandtab = true
        opt.shiftwidth = 4
        opt.softtabstop = 4
        opt.smartindent = true
        opt.undofile = true
        opt.ignorecase = true
        opt.smartcase = true
        opt.clipboard = "unnamedplus"

        -- Clipboard integration with Android:
        --   yank ("+y / "*y) goes out via OSC 52 and lands on the Android
        --   clipboard; paste reads the file AndroVim keeps in sync.
        vim.g.clipboard = {
          name = "AndroVimClipboard",
          copy = {
            ["+"] = require("vim.ui.clipboard.osc52").copy("+"),
            ["*"] = require("vim.ui.clipboard.osc52").copy("*"),
          },
          paste = {
            ["+"] = function()
              local f = vim.env.ANDROVIM_PASTE_FILE
              if not f or not vim.uv.fs_stat(f) then return "" end
              local ok, lines = pcall(vim.fn.readfile, f)
              if not ok or not lines then return "" end
              return lines
            end,
            ["*"] = function()
              local f = vim.env.ANDROVIM_PASTE_FILE
              if not f or not vim.uv.fs_stat(f) then return "" end
              local ok, lines = pcall(vim.fn.readfile, f)
              if not ok or not lines then return "" end
              return lines
            end,
          },
        }

        vim.cmd.colorscheme("default")
    """.trimIndent()
}
