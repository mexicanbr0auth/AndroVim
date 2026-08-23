package com.androvim

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
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
import com.androvim.data.Prefs
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient

/**
 * Full-screen shell terminal with the bundled apt/dpkg bootstrap on PATH.
 * Users can run `apt update`, `apt install git`, etc. directly — no Termux.
 */
class TerminalActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    companion object {
        private const val LOG_TAG = "AndroVimTerm"
    }

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysBar
    private lateinit var overlay: TextView
    private var session: TerminalSession? = null
    private var ctrlHeld = false
    private var overlayTap: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()
        startSession()
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(0xFF10141A.toInt()) }
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
            override fun sendKey(sequence: String) {
                val bytes = sequence.toByteArray(Charsets.UTF_8)
                session?.write(bytes, 0, bytes.size)
            }
            override fun syncClipboard() {}
        })

        column.addView(terminalView)
        column.addView(extraKeys)

        overlay = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xEE10141A.toInt())
            visibility = View.GONE
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setTextIsSelectable(true)
            setOnClickListener { overlayTap?.invoke() }
        }

        root.addView(column)
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)

        val prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        terminalView.setTextSize(prefs.getInt(Prefs.TEXT_SIZE, 12))
        terminalView.setTerminalViewClient(this)
    }

    private fun showOverlay(message: String, onTap: () -> Unit) {
        overlay.text = message
        overlay.visibility = View.VISIBLE
        overlayTap = onTap
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        overlayTap = null
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun startSession() {
        try {
            session?.finishIfRunning()
            hideOverlay()

            val cwd = NvimRuntime.homeDir(this).absolutePath
            val shell = "/system/bin/sh"
            session = TerminalSession(
                shell,
                cwd,
                arrayOf(shell),
                NvimRuntime.buildEnvironment(this),
                null,
                this,
            )
            terminalView.attachSession(session!!)
            terminalView.requestFocus()
        } catch (t: Throwable) {
            Log.e(LOG_TAG, "falha ao iniciar shell", t)
            showOverlay("Falha ao iniciar o shell:\n\n$t\n\n• Toque para tentar novamente") { startSession() }
        }
    }

    // ---- TerminalViewClient ---------------------------------------------------------

    override fun onScale(scale: Float): Float = 1f

    override fun onSingleTapUp(e: MotionEvent?) {
        terminalView.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
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

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = false

    override fun onEmulatorSet() {
        terminalView.onScreenUpdated()
    }

    // ---- TerminalSessionClient ------------------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession?) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) {
        title = changedSession?.title ?: getString(R.string.menu_terminal)
    }

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        val status = finishedSession?.exitStatus ?: -1
        showOverlay("Sessão encerrada ($status).\n\n• Toque para reabrir") { startSession() }
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        text ?: return
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("AndroVim", text))
        } catch (_: Throwable) {
        }
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {}

    override fun onBell(session: TerminalSession?) {
        Toast.makeText(this, "bell", Toast.LENGTH_SHORT).show()
    }

    override fun onColorsChanged(session: TerminalSession?) {
        terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    override fun logError(tag: String?, message: String?) {}
    override fun logWarn(tag: String?, message: String?) {}
    override fun logInfo(tag: String?, message: String?) {}
    override fun logDebug(tag: String?, message: String?) {}
    override fun logVerbose(tag: String?, message: String?) {}

    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {}

    override fun logStackTrace(tag: String?, e: Exception?) {}
}
