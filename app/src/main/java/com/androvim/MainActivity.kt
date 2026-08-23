package com.androvim

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
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

class MainActivity : Activity(), TerminalSessionClient, TerminalViewClient {

    private lateinit var rootLayout: FrameLayout
    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysBar
    private lateinit var exitOverlay: TextView
    private lateinit var prefs: SharedPreferences

    private var session: TerminalSession? = null
    private var ctrlHeld = false

    private val clipboardManager: ClipboardManager
        get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getPreferences(Context.MODE_PRIVATE)
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        buildUi()

        Thread {
            NvimRuntime.prepare(this)
            runOnUiThread {
                syncClipboardFile()
                startSession()
            }
        }.start()
    }

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
                if (NvimRuntime.syncPasteFile(currentClipText(), this@MainActivity)) {
                    toast(getString(R.string.clipboard_synced))
                } else {
                    toast(getString(R.string.clipboard_empty))
                }
            }
        })

        column.addView(terminalView)
        column.addView(extraKeys)
        rootLayout.addView(column)

        exitOverlay = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xDD000000.toInt())
            visibility = View.GONE
            setOnClickListener { startSession() }
        }
        rootLayout.addView(
            exitOverlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(rootLayout)

        terminalView.setTextSize(prefs.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE))
        terminalView.setTerminalViewClient(this)
    }

    private fun startSession() {
        session?.finishIfRunning()
        exitOverlay.visibility = View.GONE

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
    }

    private fun writeToPty(data: String) {
        val bytes = data.toByteArray(Charsets.UTF_8)
        session?.write(bytes, 0, bytes.size)
    }

    private fun currentClipText(): String? =
        clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()

    private fun syncClipboardFile(): Boolean =
        NvimRuntime.syncPasteFile(currentClipText(), this)

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
        syncClipboardFile()
    }

    // ---- TerminalViewClient -------------------------------------------------

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

    // ---- TerminalSessionClient ----------------------------------------------

    override fun onTextChanged(changedSession: TerminalSession?) {
        terminalView.onScreenUpdated()
    }

    override fun onTitleChanged(changedSession: TerminalSession?) {
        title = changedSession?.title ?: getString(R.string.app_name)
    }

    override fun onSessionFinished(finishedSession: TerminalSession?) {
        val status = finishedSession?.exitStatus ?: -1
        runOnUiThread {
            exitOverlay.text = getString(R.string.session_finished, status)
            exitOverlay.visibility = View.VISIBLE
        }
    }

    override fun onCopyTextToClipboard(session: TerminalSession?, text: String?) {
        text ?: return
        clipboardManager.setPrimaryClip(ClipData.newPlainText("AndroVim", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        syncClipboardFile()
    }

    override fun onBell(session: TerminalSession?) {}

    override fun onColorsChanged(session: TerminalSession?) {
        terminalView.onScreenUpdated()
    }

    override fun onTerminalCursorStateChange(state: Boolean) {}

    override fun getTerminalCursorStyle(): Int = TerminalEmulator.DEFAULT_TERMINAL_CURSOR_STYLE

    // ---- Logging ------------------------------------------------------------

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
