package com.androvim.core

import android.content.Context
import com.androvim.NvimRuntime
import java.io.File

/** Thin wrapper around the runtime-installed git binary. */
object GitRunner {

    fun available(context: Context): Boolean = resolveGit(context) != null

    fun resolveGit(context: Context): File? =
        listOf(
            File(NvimRuntime.prefixDir(context), "bin/git"),
            File("/system/bin/git"),
        ).firstOrNull { it.exists() && it.canExecute() }

    /** Clone `url` (depth 1) into `target`. Throws Exception with a readable message. */
    fun cloneInto(context: Context, url: String, target: File) {
        val git = resolveGit(context)
            ?: throw Exception("o git não está instalado — abra Ferramentas e instale-o primeiro")
        val pb = ProcessBuilder(
            listOf(git.absolutePath, "clone", "--depth", "1", "--quiet", url, target.absolutePath),
        ).redirectErrorStream(true)
        pb.environment().putAll(NvimRuntime.environmentMap(context))
        val proc = pb.start()
        val output = proc.inputStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0 || !target.isDirectory) {
            target.deleteRecursively()
            throw Exception("git clone falhou ($code): ${output.take(400)}")
        }
    }
}
