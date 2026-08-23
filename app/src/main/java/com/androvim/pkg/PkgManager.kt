package com.androvim.pkg

import android.content.Context
import android.os.Build
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import org.tukaani.xz.XZInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/**
 * On-device package manager: resolves dependency closures from the Termux APT
 * repository, downloads and verifies .deb archives and extracts them into the
 * app-private prefix (filesDir/usr). Works because the app targets SDK 28,
 * which keeps exec() allowed inside app-writable storage.
 */
object PkgManager {

    const val REPO = "https://packages.termux.dev/apt/termux-main"
    private const val UA = "Mozilla/5.0 (Linux; Android) AndroVim/0.2"
    private const val TERMUX_PREFIX_MARK = "data/data/com.termux/files/"

    class PkgInfo(
        val name: String,
        val version: String,
        val filename: String,
        val sha256: String,
        val size: Long,
        val depends: List<String>,
    )

    // ---- Paths ---------------------------------------------------------------

    fun prefix(context: Context): File = File(context.filesDir, "usr")
    fun binDir(context: Context): File = File(prefix(context), "bin")
    fun cacheDir(context: Context): File = File(context.filesDir, "pkg-cache").apply { mkdirs() }
    fun registryFile(context: Context): File = File(cacheDir(context), "installed.json")

    fun arch(): String = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64"
        "armeabi-v7a" -> "arm"
        "x86_64" -> "x86_64"
        else -> "i686"
    }

    private fun indexFile(context: Context) =
        File(cacheDir(context), "Packages-${arch()}")

    // ---- Registry ------------------------------------------------------------

    @Synchronized
    fun installedMap(context: Context): MutableMap<String, JSONObject> {
        val map = mutableMapOf<String, JSONObject>()
        val f = registryFile(context)
        if (f.exists()) {
            runCatching {
                val obj = JSONObject(f.readText()).getJSONObject("installed")
                for (key in obj.keys()) map[key] = obj.getJSONObject(key)
            }
        }
        return map
    }

    @Synchronized
    private fun saveRegistry(context: Context, map: Map<String, JSONObject>) {
        val root = JSONObject().put("installed", JSONObject(map as Map<*, *>))
        registryFile(context).writeText(root.toString())
    }

    fun isInstalled(context: Context, name: String): Boolean =
        installedMap(context).containsKey(name)

    fun installedVersion(context: Context, name: String): String? =
        installedMap(context)[name]?.optString("version")

    // ---- Index ----------------------------------------------------------------

    @Synchronized
    fun loadIndex(context: Context, forceRefresh: Boolean = false): Map<String, PkgInfo> {
        val f = indexFile(context)
        val stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > 24 * 3600_000L
        if (stale || forceRefresh) {
            val text = httpGet("$REPO/dists/stable/main/binary-${arch()}/Packages")
            f.writeText(text)
        }
        return parsePackages(f.readText())
    }

    private fun parsePackages(text: String): Map<String, PkgInfo> {
        val out = mutableMapOf<String, PkgInfo>()
        val stanza = mutableMapOf<String, String>()
        fun flush() {
            val name = stanza["Package"] ?: return
            out[name] = PkgInfo(
                name = name,
                version = stanza["Version"] ?: "?",
                filename = stanza["Filename"] ?: return,
                sha256 = stanza["SHA256"] ?: "",
                size = stanza["Size"]?.toLongOrNull() ?: 0L,
                depends = stanza["Depends"]
                    ?.split(',')
                    ?.map { it.trim().substringBefore('(').substringBefore('|').trim() }
                    ?.filter { it.isNotEmpty() }
                    ?: emptyList(),
            )
            stanza.clear()
        }
        for (line in text.lines()) {
            if (line.isBlank()) {
                flush()
                continue
            }
            val idx = line.indexOf(':')
            if (idx > 0) stanza[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        flush()
        return out
    }

    // ---- Install / Remove -------------------------------------------------------

    @Synchronized
    fun install(context: Context, roots: List<String>, progress: (String) -> Unit): String {
        ensureLayout(context)
        cacheDirStatic = cacheDir(context) // downloadDeb() runs before extractDeb()
        val index = loadIndex(context)
        val resolved = mutableListOf<PkgInfo>()
        val seen = mutableSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (!seen.add(n)) continue
            val info = index[n] ?: throw Exception("pacote '$n' não encontrado no repositório")
            resolved.add(info)
            queue.addAll(info.depends.filter { it !in seen })
        }

        val already = installedMap(context)
        val todo = resolved.filter { !already.containsKey(it.name) }
        progress("Resolvido: ${resolved.size} pacotes, ${todo.size} novos")

        val newFiles = mutableMapOf<String, MutableList<String>>()
        for ((i, info) in todo.withIndex()) {
            progress("Baixando ${info.name} ${info.version} (${i + 1}/${todo.size})")
            val deb = downloadDeb(info)
            progress("Extraindo ${info.name}")
            val files = extractDeb(deb, context)
            deb.delete()
            newFiles[info.name] = files.toMutableList()
        }

        // register everything that was newly installed
        for (info in resolved) {
            if (!already.containsKey(info.name)) {
                already[info.name] = JSONObject()
                    .put("version", info.version)
                    .put("files", JSONArray(newFiles[info.name] ?: mutableListOf<String>()))
            }
        }
        saveRegistry(context, already)
        markExecutableTree(binDir(context))
        markExecutableTree(File(prefix(context), "libexec"))
        progress("Concluído ✔")
        return "${todo.size} pacote(s) instalado(s)"
    }

    /** Alias kept for readability at call sites. */
    @Synchronized
    fun uninstall(context: Context, name: String) = remove(context, name)

    @Synchronized
    fun remove(context: Context, name: String) {
        val reg = installedMap(context)
        val entry = reg.remove(name) ?: throw Exception("'$name' não está instalado")
        val prefixPath = prefix(context).absolutePath + "/"
        val dirs = sortedSetOf<String>()
        val files = entry.optJSONArray("files")
        if (files != null) {
            for (i in 0 until files.length()) {
                val file = File(files.getString(i))
                file.delete()
                var parent = file.parentFile
                while (parent != null && parent.absolutePath.startsWith(prefixPath)) {
                    dirs.add(parent.absolutePath)
                    parent = parent.parentFile
                }
            }
        }
        // prune now-empty dirs deepest-first
        for (d in dirs.toList().sortedByDescending { it.length }) {
            File(d).takeIf { it.isDirectory && it.list()?.isEmpty() == true }?.delete()
        }
        saveRegistry(context, reg)
    }

    private fun ensureLayout(context: Context) {
        for (d in listOf("bin", "lib", "etc", "tmp", "share", "libexec")) {
            File(prefix(context), d).mkdirs()
        }
    }

    private fun markExecutableTree(dir: File?) {
        if (dir == null || !dir.isDirectory) return
        dir.walkTopDown().filter { it.isFile }.forEach {
            runCatching { Os.chmod(it.absolutePath, 493 /* 0755 */) }
        }
    }

    // ---- Download ---------------------------------------------------------------

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}: $url")
        return conn.inputStream.use { it.readBytes().decodeToString() }
    }

    private fun downloadTo(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.setRequestProperty("User-Agent", UA)
        if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}: $url")
        conn.inputStream.use { input ->
            FileOutputStream(dest).use { output -> input.copyTo(output) }
        }
    }

    private fun downloadDeb(info: PkgInfo): File {
        val dest = File(cacheDirStatic ?: throw IllegalStateException(), info.filename.substringAfterLast('/'))
        if (dest.exists() && dest.length() == info.size) return dest
        downloadTo("$REPO/${info.filename}", dest)
        if (info.sha256.isNotEmpty()) {
            val digest = MessageDigest.getInstance("SHA-256").digest(dest.readBytes())
            val hex = digest.joinToString("") { "%02x".format(it) }
            if (!hex.equals(info.sha256, ignoreCase = true)) {
                dest.delete()
                throw Exception("checksum inválido para ${info.name}")
            }
        }
        return dest
    }

    // cache dir needs a Context; stash it during install()
    @Volatile
    private var cacheDirStatic: File? = null

    // ---- Extraction: ar + compressed tar -----------------------------------------

    private fun extractDeb(deb: File, context: Context): List<String> {
        cacheDirStatic = cacheDir(context)
        val root = prefix(context)
        val created = mutableListOf<String>()

        FileInputStream(deb).use { fin ->
            checkArHeader(fin)
            var member = nextArMember(fin)
            while (member != null) {
                val (name, size) = member
                if (name.startsWith("data.tar")) {
                    extractCompressedTar(fin, size, root, created)
                    return created
                }
                skipFully(fin, size + (size % 2))
                member = nextArMember(fin)
            }
        }
        throw Exception("deb sem data.tar: ${deb.name}")
    }

    private fun checkArHeader(fin: InputStream) {
        val magic = ByteArray(8)
        if (readFully(fin, magic) != 8 || String(magic) != "!<arch>\n") {
            throw Exception("não é um arquivo .deb válido")
        }
    }

    private fun nextArMember(fin: InputStream): Pair<String, Long>? {
        val header = ByteArray(60)
        if (readFully(fin, header) != 60) return null
        if (header.all { it == '\n'.code.toByte() } || String(header, 0, 8).startsWith("\n")) return null
        val name = String(header, 0, 16).trim()
        val size = String(header, 48, 10).trim().toLongOrNull() ?: return null
        return name to size
    }

    private fun extractCompressedTar(fin: InputStream, size: Long, root: File, created: MutableList<String>) {
        val raw = ByteArray(size.toInt())
        if (readFully(fin, raw) != size.toInt()) throw Exception("data.tar truncado")

        val bis = ByteArrayInputStream(raw)
        val decompressed: InputStream = when {
            raw.size > 6 && raw[0] == 0xFD.toByte() && raw[1] == '7'.code.toByte() &&
                String(raw, 2, 4) == "zXZ" -> XZInputStream(bis)
            raw.size > 2 && raw[0] == 0x1F.toByte() && raw[1] == 0x8B.toByte() -> GZIPInputStream(bis)
            else -> throw Exception("compressão de data.tar não suportada (instale via repo atualizado)")
        }

        TarReader(decompressed).use { reader ->
            var current = reader.next()
            while (current != null) {
                val entry = current
                when (entry.type) {
                    TarReader.TYPE_FILE -> {
                        val rel = normalize(entry.name)
                        if (rel == null) {
                            reader.skipData(entry)
                            current = reader.next()
                            continue
                        }
                        val f = File(root, rel)
                        f.parentFile?.mkdirs()
                        if (f.exists() || f.isSymlink()) f.delete()
                        FileOutputStream(f).use { copyN(reader.raw, it, entry.size) }
                        reader.skipPadding(entry.size)
                        created.add(f.absolutePath)
                    }
                    TarReader.TYPE_SYMLINK -> {
                        skipData(reader, entry)
                        val rel = normalize(entry.name) ?: continue
                        val f = File(root, rel)
                        f.parentFile?.mkdirs()
                        if (f.exists() || f.isSymlink()) f.delete()
                        runCatching { Os.symlink(entry.link, f.absolutePath) }
                    }
                    TarReader.TYPE_HARDLINK -> {
                        skipData(reader, entry)
                        val relSrc = normalize(entry.link)
                        val relDst = normalize(entry.name)
                        if (relSrc != null && relDst != null) {
                            val src = File(root, relSrc)
                            val dst = File(root, relDst)
                            dst.parentFile?.mkdirs()
                            if (dst.exists() || dst.isSymlink()) dst.delete()
                            runCatching { Os.link(src.absolutePath, dst.absolutePath) }
                                .onFailure { src.copyTo(dst, overwrite = true) }
                            created.add(dst.absolutePath)
                        }
                    }
                    TarReader.TYPE_DIR -> {
                        skipData(reader, entry)
                        val rel = normalize(entry.name)
                        if (rel != null) File(root, rel).mkdirs()
                    }
                    else -> skipData(reader, entry)
                }
                current = reader.next()
            }
        }
    }

    private fun skipData(reader: TarReader, entry: TarReader.Entry) = reader.skipData(entry)

    /** Returns a safe relative path under our prefix, or null to skip foreign paths. */
    private fun normalize(rawName: String): String? {
        var name = rawName.removePrefix("./")
        val idx = name.indexOf(TERMUX_PREFIX_MARK)
        if (idx >= 0) name = name.substring(idx + TERMUX_PREFIX_MARK.length)
        if (name.isEmpty() || name.startsWith("/") ||
            name.contains("..") || !(name.startsWith("usr/") || name == "usr")
        ) return null
        return name
    }

    private fun File.isSymlink(): Boolean =
        runCatching { Os.readlink(absolutePath).isNotEmpty() }.getOrDefault(false)

    private fun readFully(fin: InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = fin.read(buf, off, buf.size - off)
            if (r == -1) break
            off += r
        }
        return off
    }

    private fun skipFully(fin: InputStream, count: Long) {
        var remaining = count
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val r = fin.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r == -1) break
            remaining -= r
        }
    }

    private fun copyN(input: InputStream, out: java.io.OutputStream, count: Long) {
        val buf = ByteArray(16384)
        var remaining = count
        while (remaining > 0) {
            val r = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r == -1) break
            out.write(buf, 0, r)
            remaining -= r
        }
    }
}

/** Minimal USTAR/GNU tar reader over a decompressed stream. */
class TarReader(val raw: InputStream) : AutoCloseable {

    data class Entry(val name: String, val link: String, val size: Long, val type: Byte)

    override fun close() = raw.close()

    fun next(): Entry? {
        var pendingName: String? = null
        while (true) {
            val header = ByteArray(512)
            if (readInto(header) != 512) return null
            if (header.all { it.toInt() == 0 }) return null

            var name = cstr(header, 0, 100)
            val size = octal(header, 124, 12) ?: 0L
            val type = header[156]
            val link = cstr(header, 157, 100)
            val magic = String(header, 257, 6).trimEnd('\u0000', ' ')
            if (magic == "ustar") {
                val prefix = cstr(header, 345, 155)
                if (prefix.isNotEmpty() && type == TYPE_FILE) name = "$prefix/$name"
            }

            when (type) {
                'L'.code.toByte() -> { // GNU long name: applies to the next entry
                    val data = ByteArray(size.toInt().coerceAtMost(1 shl 20))
                    readInto(data)
                    skipPadding(size)
                    pendingName = String(data).trimEnd('\u0000')
                }
                else -> {
                    pendingName?.let { name = it }
                    return Entry(name, link, size, type)
                }
            }
        }
    }

    fun skipData(e: Entry) {
        var remaining = e.size
        val buf = ByteArray(16384)
        while (remaining > 0) {
            val r = raw.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
            if (r <= 0) break
            remaining -= r
        }
        skipPadding(e.size)
    }

    /** Skip the zero padding that rounds `size` up to a 512-byte block. */
    fun skipPadding(size: Long) {
        var pad = ((size + 511) / 512 * 512) - size
        val buf = ByteArray(512)
        while (pad > 0) {
            val r = raw.read(buf, 0, minOf(buf.size.toLong(), pad).toInt())
            if (r <= 0) break
            pad -= r
        }
    }

    private fun readInto(buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val r = raw.read(buf, off, buf.size - off)
            if (r == -1) break
            off += r
        }
        return off
    }

    companion object {
        const val TYPE_FILE: Byte = '0'.code.toByte()
        const val TYPE_SYMLINK: Byte = '2'.code.toByte()
        const val TYPE_HARDLINK: Byte = '1'.code.toByte()
        const val TYPE_DIR: Byte = '5'.code.toByte()

        private fun cstr(buf: ByteArray, off: Int, len: Int): String {
            var end = off
            val max = off + len
            while (end < max && buf[end].toInt() != 0) end++
            return String(buf, off, end - off).trim()
        }

        private fun octal(buf: ByteArray, off: Int, len: Int): Long? =
            String(buf, off, len).trim('\u0000', ' ', ':').toLongOrNull(8)
    }
}
