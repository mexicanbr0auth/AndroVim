package com.androvim.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.androvim.NvimRuntime
import com.androvim.R
import com.androvim.core.GitRunner
import com.androvim.ui.Ui.dp
import java.io.File

class ProjectsActivity : Activity() {

    companion object {
        const val EXTRA_CD_PATH = "cd_path"
        private const val REQ_IMPORT_TREE = 41
    }

    private lateinit var content: LinearLayout
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val (c, _) = Ui.scrollPage(this, getString(R.string.projects_title))
        content = c
        content.addView(Ui.mutedLabel(
            this,
            "Abrir um projeto executa :cd para a pasta dele dentro do nvim aberto.",
        ).apply { setPadding(0, 0, 0, dp(10)) })

        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!busy.get()) refresh()
    }

    private fun refresh() {
        content.removeViews(1, content.childCount - 1)

        // ---- ações ---------------------------------------------------------------
        val actions = Ui.card(this)
        actions.addView(Ui.button(this, "+ Novo projeto") { showNewProjectDialog() })
        addToRow(actions, "Importar pasta…") { pickFolderToImport() }
        addToRow(actions, "Clonar do GitHub") { showCloneDialog() }
        content.addView(actions)

        // ---- lista de projetos ----------------------------------------------------
        content.addView(Ui.label(this, "Seus projetos", 15f, bold = true).also {
            it.setPadding(0, dp(6), 0, dp(8))
        })
        val projects = NvimRuntime.projectsDir(this)
            .listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()

        if (projects.isEmpty()) {
            content.addView(Ui.mutedLabel(this, "Nenhum projeto ainda — crie, importe ou clone um acima."))
        } else {
            for (dir in projects) content.addView(projectCard(dir))
        }
    }

    private fun projectCard(dir: File): View {
        val card = Ui.card(this)
        card.addView(Ui.label(this, dir.name, 14f, bold = true))
        val fileCount = dir.walkTopDown().maxDepth(3).count { it.isFile }
        card.addView(Ui.mutedLabel(this, "${dir.absolutePath} • $fileCount arquivos"))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Ui.button(this, "Abrir", Ui.ACCENT) { finishWithCd(dir.absolutePath) })
        val delBtn = Ui.button(this, "Excluir", Ui.DANGER) { confirmDelete(dir) }
        row.addView(delBtn)
        (delBtn.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
        card.addView(row)
        return card
    }

    private fun confirmDelete(dir: File) {
        AlertDialog.Builder(this)
            .setTitle("Excluir ${dir.name}?")
            .setMessage("Todos os arquivos do projeto serão apagados. Esta ação não pode ser desfeita.")
            .setPositiveButton("Excluir") { _, _ ->
                Thread {
                    dir.deleteRecursively()
                    runOnUiThread { refresh() }
                }.start()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun finishWithCd(path: String) {
        intent = Intent().putExtra(EXTRA_CD_PATH, path)
        setResult(RESULT_OK, intent)
        finish()
    }

    // ---- novo projeto -------------------------------------------------------------

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = "nome-do-projeto"
            setTextColor(Ui.FG)
            setHintTextColor(Ui.MUTED)
            setSingleLine()
        }
        val templates = arrayOf("Vazio", "Python", "Node.js", "C")
        var selectedTemplate = 0
        AlertDialog.Builder(this)
            .setTitle("Novo projeto")
            .setView(input)
            .setSingleChoiceItems(templates, 0) { _, which -> selectedTemplate = which }
            .setPositiveButton("Criar") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) createFromTemplate(name, templates[selectedTemplate])
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun createFromTemplate(name: String, template: String) {
        Thread {
            try {
                val dir = File(NvimRuntime.projectsDir(this), name)
                if (dir.exists()) throw Exception("já existe um projeto chamado '$name'")
                when (template) {
                    "Python" -> {
                        dir.mkdirs()
                        File(dir, "main.py").writeText("def main():\n    print(\"olá\")\n\n\nif __name__ == \"__main__\":\n    main()\n")
                        File(dir, ".gitignore").writeText("__pycache__/\n*.pyc\nvenv/\n")
                    }
                    "Node.js" -> {
                        dir.mkdirs()
                        File(dir, "index.js").writeText("console.log(\"olá\");\n")
                        File(dir, "package.json").writeText(
                            "{\n  \"name\": \"$name\",\n  \"version\": \"0.1.0\",\n" +
                                "  \"main\": \"index.js\",\n  \"scripts\": { \"start\": \"node index.js\" }\n}\n",
                        )
                    }
                    "C" -> {
                        dir.mkdirs()
                        File(dir, "main.c").writeText(
                            "#include <stdio.h>\n\nint main(void) {\n    printf(\"olá\\n\");\n    return 0;\n}\n",
                        )
                        File(dir, "Makefile").writeText("all:\n\tcc main.c -o app\n\nclean:\n\trm -f app\n")
                    }
                    else -> dir.mkdirs()
                }
                runOnUiThread { finishWithCd(dir.absolutePath) }
            } catch (e: Exception) {
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@ProjectsActivity, "Erro: ${e.message}", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    // ---- importar pasta (SAF) -------------------------------------------------------

    private fun pickFolderToImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_IMPORT_TREE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT_TREE || resultCode != RESULT_OK) return
        val treeUri = data?.data ?: return
        importTree(treeUri)
    }

    private fun importTree(treeUri: Uri) {
        if (!busy.compareAndSet(false, true)) return
        val name = guessName(treeUri)
        val target = File(NvimRuntime.projectsDir(this), name)
        Thread {
            try {
                if (target.exists()) throw Exception("já existe um projeto chamado '$name'")
                target.mkdirs()
                copyDocumentTree(treeUri, target)
                finishOnUiThread(target.absolutePath)
            } catch (e: Exception) {
                target.deleteRecursively()
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@ProjectsActivity, "Erro: ${e.message}", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                busy.set(false)
            }
        }.start()
    }

    private fun finishOnUiThread(path: String) = runOnUiThread {
        intent = Intent().putExtra(EXTRA_CD_PATH, path)
        setResult(RESULT_OK, intent)
        finish()
    }

    /** "content://...tree/primary:Projetos/foo" -> "foo" */
    private fun guessName(uri: Uri): String =
        uri.lastPathSegment?.substringAfter(':')?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() && !it.contains("..") } ?: "projeto-importado"

    private fun copyDocumentTree(treeUri: Uri, dest: File) {
        val resolver = contentResolver
        fun walk(docUri: Uri, into: File) {
            val childrenUri = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                treeUri, android.provider.DocumentsContract.getDocumentId(docUri),
            )
            resolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow(
                        android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID))
                    val name = cursor.getString(cursor.getColumnIndexOrThrow(
                        android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME))
                    val isDir = cursor.getInt(cursor.getColumnIndexOrThrow(
                        android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)) ==
                        android.provider.DocumentsContract.Document.MIME_TYPE_DIR
                    val child = android.provider.DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                    if (isDir) {
                        val sub = File(into, name); sub.mkdirs(); walk(child, sub)
                    } else {
                        try {
                            resolver.openInputStream(child)?.use { input ->
                                File(into, name).outputStream().use { input.copyTo(it) }
                            }
                        } catch (_: Exception) { /* skip unreadable file */ }
                    }
                }
            } ?: throw Exception("não foi possível ler a pasta selecionada")
        }
        walk(treeUri, dest)
    }

    // ---- clonar do github --------------------------------------------------------------

    private fun showCloneDialog() {
        val input = EditText(this).apply {
            hint = "usuario/repo ou URL https"
            setTextColor(Ui.FG)
            setHintTextColor(Ui.MUTED)
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("Clonar do GitHub")
            .setView(input)
            .setPositiveButton("Clonar") { _, _ ->
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) cloneRepo(value)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun cloneRepo(value: String) {
        if (!busy.compareAndSet(false, true)) return
        Thread {
            var target: File? = null
            try {
                var url = value.removeSuffix("/")
                if (Regex("^[\\w.-]+/[\\w.-]+$").matches(url)) url = "https://github.com/$url.git"
                if (!url.startsWith("http")) throw Exception("use 'usuario/repo' ou uma URL https")
                val name = url.trimEnd('/').removeSuffix(".git").substringAfterLast('/')
                if (name.isBlank()) throw Exception("não consegui deduzir o nome do repo")
                target = File(NvimRuntime.projectsDir(this), name)
                if (target.exists()) throw Exception("já existe um projeto chamado '$name'")
                GitRunner.cloneInto(this, url, target)
                finishOnUiThread(target.absolutePath)
            } catch (e: Exception) {
                target?.deleteRecursively()
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@ProjectsActivity, "Erro: ${e.message}", android.widget.Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                busy.set(false)
            }
        }.start()
    }

    // ---- helpers -------------------------------------------------------------------------

    private fun addToRow(row: LinearLayout, label: String, onClick: () -> Unit) {
        val btn = Ui.button(this, label, onClick)
        row.addView(btn)
        (btn.layoutParams as? LinearLayout.LayoutParams)?.leftMargin = dp(6)
    }
}
