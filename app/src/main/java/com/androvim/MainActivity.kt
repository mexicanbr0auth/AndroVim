package com.androvim

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var rootLayout: FrameLayout
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysBar
    private lateinit var overlay: TextView
    private lateinit var prefs: SharedPreferences

    private var session: TerminalSession? = null
    private var ctrlHeld = false
    private var overlayTap: (() -> Unit)? = null

    private val clipboardManager: ClipboardManager
        get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        prefs = getPreferences(Context.MODE_PRIVATE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()

        val lastCrash = readLastCrash()
        if (lastCrash != null) {
            showOverlay(
                "O AndroVim fechou inesperadamente da última vez.\n\n" +
                    lastCrash.take(3500) +
                    "\n\n• Toque para iniciar mesmo assim\n• Segure para compartilhar este log",
            ) {
                clearCrashLog()
                beginStartup()
            }
        } else {
            beginStartup()
        }
    }

    // ---- Startup -------------------------------------------------------------

    private fun beginStartup() {
        Log.i(LOG_TAG, "preparando runtime do Neovim…")
        Thread {
            val error: Throwable? = try {
                NvimRuntime.prepare(this)
                null
            } catch (t: Throwable) {
                t
            }
            runOnUiThread {
                if (error == null) {
                    Log.i(LOG_TAG, "runtime pronto; iniciando sessão")
                    syncClipboardSafe()
                    startSession()
                } else {
                    Log.e(LOG_TAG, "prepare() falhou", error)
                    showOverlay(
                        "Falha ao preparar o Neovim:\n\n$error\n\n" +
                            Log.getStackTraceString(error).take(3000) +
                            "\n\n• Toque para tentar novamente\n• Segure para compartilhar o log",
                    ) { beginStartup() }
                }
            }
        }.start()
    }

    private fun startSession() {
        try {
            session?.finishIfRunning()
            hideOverlay()

            val nvim = NvimRuntime.nvimPath(this)
            val cwd = NvimRuntime.homeDir(this).absolutePath
            session = TerminalSession(
                nvim,
                cwd,
                arrayOf(nvim),
                NvimRuntime.buildEnvironment(this),
                null,
                this,
            )
            terminalView.attachSession(session!!)
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "falha ao iniciar sessão", t)
            showOverlay(
                "Falha ao iniciar o Neovim:\n\n$t\n\n" +
                    "• Toque para tentar novamente\n• Segure para compartilhar o log",
            ) { startSession() }
        }
    }

    // ---- UI ------------------------------------------------------------------

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        rootLayout = FrameLayout(this).apply { setBackgroundColor(BG_COLOR) }

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        terminalView = TerminalView(this, null).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f,
            )
            isFocusableInTouchMode = true
        }

        extraKeys = ExtraKeysBar(this, object : ExtraKeysBar.Actions {
            override fun sendKey(sequence: String) = writeToPty(sequence)
            override fun syncClipboard() {
                if (syncClipboardSafe()) {
                    toast(getString(R.string.clipboard_synced))
                } else {
                    toast(getString(R.string.clipboard_empty))
                }
            }
        })

        column.addView(terminalView)
        column.addView(extraKeys)
        rootLayout.addView(column)

        overlay = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xEE10141A.toInt())
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
            setOnClickListener { overlayTap?.invoke() }
            setOnLongClickListener {
                shareDiagnostics()
                true
            }
        }
        rootLayout.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(rootLayout)

        terminalView.setTextSize(prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE))
        terminalView.setTerminalViewClient(this)
    }

    private fun showOverlay(message: String, onTap: () -> Unit) {
        runOnUiThread {
            overlay.text = message
            overlayTap = onTap
            overlay.visibility = View.VISIBLE
        }
    }

    private fun hideOverlay() {
        overlayTap = null
        overlay.visibility = View.GONE
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun writeToPty(data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    /** Volume Down acts as a holdable Ctrl key, Volume Up sends PgUp. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> ctrlHeld = true
                    KeyEvent.ACTION_UP -> ctrlHeld = false
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    writeToPty("\u001b[5~")
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Keep the Neovim process alive; just move the app to the background.
        moveTaskToBack(true)
    }

    override fun onResume() {
        super.onResume()
        syncClipboardSafe()
    }

    // ---- Clipboard -----------------------------------------------------------

    private fun currentClipText(): String? = try {
        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()
    } catch (_: Throwable) {
        null
    }

    private fun syncClipboardSafe(): Boolean =
        try {
            NvimRuntime.syncPasteFile(currentClipText(), this)
        } catch (_: Throwable) {
            false
        }

    // ---- Crash reporting -----------------------------------------------------

    private fun crashFile(): File =
        File(getExternalFilesDir(null) ?: filesDir, "crash.txt")

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                crashFile().writeText(
                    buildString {
                        appendLine("AndroVim ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("Android ${Build.VERSION.RELEASE} (${Build.VERSION.SDK_INT})")
                        appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString(", ")}")
                        appendLine("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL}")
                        appendLine("Thread: ${thread.name}")
                        appendLine(
                            "Hora: ${
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                            }"
                        )
                        appendLine()
                        appendLine(Log.getStackTraceString(throwable))
                    },
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun readLastCrash(): String? = try {
        crashFile().takeIf { it.length() > 0 }?.readText()
    } catch (_: Throwable) {
        null
    }

    private fun clearCrashLog() {
        try {
            crashFile().delete()
        } catch (_: Throwable) {
        }
    }

    private fun shareDiagnostics() {
        try {
            val body = buildString {
                appendLine(overlay.text)
                appendLine()
                appendLine("---")
                appendLine("log salvo em: ${crashFile().absolutePath}")
            }
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "AndroVim crash log")
                        putExtra(Intent.EXTRA_TEXT, body)
                    },
                    "Compartilhar log",
                ),
            )
        } catch (_: Throwable) {
        }
    }

    // ---- TerminalViewClient --------------------------------------------------

    override fun onScale(scale: Float): Float {
        val current = prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE)
        val next = (current * scale).toInt().coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        if (next != current) {
            terminalView.setTextSize(next)
            prefs.edit().putInt(KEY_TEXT_SIZE, next).apply()
        }
        return 1f
    }

    override fun onSingleTapUp(e: MotionEvent?) {
        terminalView.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false

    override fun shouldEnforceCharBasedInput(): Boolean = true

    override fun shouldUseCtrlSpaceWorkaround(): Boolean = true

    override fun isTerminalViewSelected(): Boolean = true

    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false

    override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false

    override fun onLongPress(event: MotionEvent?): Boolean = false

    override fun readControlKey(): Boolean = ctrlHeld || extraKeys.ctrlActive

    override fun readAltKey(): Boolean = extraKeys.altActive

    override fun readShiftKey(): Boolean = false

    override fun readFnKey(): Boolean = false

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean =
        false

    override fun onEmulatorSet() {
        terminalView.onScreenUpdated()
    }

    // ---- TerminalSessionClient -----------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession?) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) {
        title = changedSession?.title ?: getString(R.string.app_name)
    }

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        val status = finishedSession?.exitStatus ?: -1
        showOverlay(
            getString(R.string.session_finished, status) +
                "\n\n• Toque para reiniciar\n• Segure para compartilhar um log",
        ) { startSession() }
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        text ?: return
        try {
            clipboardManager.setPrimaryClip(ClipData.newPlainText("AndroVim", text))
        } catch (_: Throwable) {
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        syncClipboardSafe()
    }

    override fun onBell(session: TerminalSession?) {}

    override fun onColorsChanged(session: TerminalSession?) {
        terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // ---- Logging --------------------------------------------------------------

    override fun logError(tag: String?, message: String?) = log(Log.ERROR, tag, message)
    override fun logWarn(tag: String?, message: String?) = log(Log.WARN, tag, message)
    override fun logInfo(tag: String?, message: String?) = log(Log.INFO, tag, message)
    override fun logDebug(tag: String?, message: String?) = log(Log.DEBUG, tag, message)
    override fun logVerbose(tag: String?, message: String?) = log(Log.VERBOSE, tag, message)

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
        Log.e(LOG_TAG, "$tag: $message", e)
    }

    override fun logStackTrace(tag: String?, e: Exception?) {
        Log.e(LOG_TAG, tag, e)
    }

    private fun log(priority: Int, tag: String?, message: String?) {
        Log.println(priority, LOG_TAG, "$tag: $message")
    }

    companion object {
        private const val LOG_TAG = "AndroVim"
        private const val KEY_TEXT_SIZE = "text_size"
        private const val DEFAULT_TEXT_SIZE = 12
        private const val MIN_TEXT_SIZE = 6
        private const val MAX_TEXT_SIZE = 32
        private val BG_COLOR = 0xFF0F1216.toInt()
    }
}
