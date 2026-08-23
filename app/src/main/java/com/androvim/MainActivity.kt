package com.androvim

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.system.Os
import android.content.SharedPreferences
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.drawerlayout.widget.DrawerLayout
import com.androvim.data.ColorScheme
import com.androvim.data.Prefs
import com.androvim.ui.AppearanceActivity
import com.androvim.ui.PluginsActivity
import com.androvim.ui.ProjectsActivity
import com.androvim.ui.ToolsActivity
import com.androvim.ui.Ui
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

    companion object {
        private const val LOG_TAG = "AndroVim"
        private const val MIN_TEXT_SIZE = 6
        private const val MAX_TEXT_SIZE = 32
        private val BG_COLOR = 0xFF10141A.toInt()
        private const val REQ_PROJECTS = 42
    }

    private lateinit var terminalView: TerminalView
    private lateinit var extraKeys: ExtraKeysBar
    private lateinit var overlay: TextView
    private lateinit var prefs: SharedPreferences

    private var session: TerminalSession? = null
    private var ctrlHeld = false
    private var overlayTap: (() -> Unit)? = null

    private val clipboardManager: ClipboardManager
        get() = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    // ---- hardware keyboard / mouse detection ---------------------------------

    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = updateExtraKeysVisibility()
        override fun onInputDeviceRemoved(deviceId: Int) = updateExtraKeysVisibility()
        override fun onInputDeviceChanged(deviceId: Int) = updateExtraKeysVisibility()
    }

    private fun hasHardwareInput(): Boolean {
        for (id in InputDevice.getDeviceIds()) {
            val device = try {
                InputDevice.getDevice(id)
            } catch (_: Exception) {
                null
            } ?: continue
            if (device.isVirtual) continue
            val s = device.sources
            val keyboard = s and InputDevice.SOURCE_KEYBOARD != 0 &&
                device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
            val pointing = s and (InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_TRACKBALL) != 0
            if (keyboard || pointing) return true
        }
        return false
    }

    /** extra keys mode: auto hides when a physical keyboard/mouse is present */
    private fun updateExtraKeysVisibility() {
        if (!this::extraKeys.isInitialized) return
        val mode = prefs.getString(Prefs.EXTRA_KEYS_MODE, Prefs.EXTRA_KEYS_AUTO)
        val visible = when (mode) {
            Prefs.EXTRA_KEYS_SHOW -> true
            Prefs.EXTRA_KEYS_HIDE -> false
            else -> !hasHardwareInput()
        }
        extraKeys.visibility = if (visible) View.VISIBLE else View.GONE
    }

    // ---- lifecycle -------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashHandler()
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        migrateLegacyPrefs()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildUi()

        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        im.registerInputDeviceListener(inputListener, null)

        applyAppearance()

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

    /** v0.1.x kept text_size in the activity-local prefs file. */
    private fun migrateLegacyPrefs() {
        if (prefs.contains(Prefs.TEXT_SIZE)) return
        val legacy = getPreferences(MODE_PRIVATE).getInt("text_size", -1)
        if (legacy > 0) {
            prefs.edit().putInt(Prefs.TEXT_SIZE, legacy.coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)).apply()
        }
    }

    override fun onResume() {
        super.onResume()
        applyAppearance()
        updateExtraKeysVisibility()
        syncClipboardSafe()
    }

    override fun onDestroy() {
        super.onDestroy()
        val im = getSystemService(Context.INPUT_SERVICE) as InputManager
        im.unregisterInputDeviceListener(inputListener)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Keep the Neovim process alive; just move the app to the background.
        moveTaskToBack(true)
    }

    // ---- Startup ---------------------------------------------------------------

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


    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetTextI18n")
    private fun buildUi() {
        val rootLayout = FrameLayout(this).apply { setBackgroundColor(BG_COLOR) }

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

        // floating menu button (top-right, over the terminal)
        val menuButton = TextView(this).apply {
            text = "\u22EE"
            textSize = 20f
            setTextColor(0xFFD5DAE0.toInt())
            setPadding(dp(14), dp(6), dp(14), dp(10))
            background = Ui.rounded(this@MainActivity, 0x66101418.toInt(), 12f)
        }
        menuButton.setOnClickListener { anchor ->
            val popup = PopupMenu(this@MainActivity, anchor as View)
            popup.menu.add(getString(R.string.menu_appearance)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, AppearanceActivity::class.java)); true
            }
            popup.menu.add(getString(R.string.menu_plugins)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, PluginsActivity::class.java)); true
            }
            popup.menu.add(getString(R.string.menu_tools)).setOnMenuItemClickListener {
                startActivity(Intent(this@MainActivity, ToolsActivity::class.java)); true
            }
            popup.menu.add(getString(R.string.menu_projects)).setOnMenuItemClickListener {
                startActivityForResult(Intent(this@MainActivity, ProjectsActivity::class.java), REQ_PROJECTS); true
            }
            popup.show()
        }

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

        rootLayout.addView(column)
        rootLayout.addView(
            menuButton,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.TOP,
            ),
        )
        rootLayout.addView(
            overlay,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(rootLayout)

        terminalView.setTextSize(prefs.getInt(Prefs.TEXT_SIZE, 12))
        terminalView.setTerminalViewClient(this)
    }

    // ---- appearance ------------------------------------------------------------------

    private fun applyAppearance() {
        if (!this::terminalView.isInitialized) return
        terminalView.setTextSize(prefs.getInt(Prefs.TEXT_SIZE, 12).coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE))
        val family = prefs.getString(Prefs.FONT_FAMILY, "monospace") ?: "monospace"
        terminalView.setTypeface(Typeface.create(family, Typeface.NORMAL))
        terminalView.keepScreenOn = prefs.getBoolean(Prefs.KEEP_SCREEN_ON, false)
        applyColorScheme()
    }

    private fun applyColorScheme() {
        val emulator = terminalView.mEmulator ?: return
        ColorScheme.applyTo(emulator, ColorScheme.byId(prefs.getString(Prefs.COLOR_SCHEME, "default") ?: "default"))
        terminalView.onScreenUpdated()
    }

    // ---- project handoff -----------------------------------------------------------

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTS && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(ProjectsActivity.EXTRA_CD_PATH) ?: return
            val escaped = path.replace(" ", "\\ ")
            writeToPty("\u001B") // leave any mode we're in
            writeToPty(":cd $escaped\r")
            toast("nvim :cd ${File(path).name}")
        }
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

    // ---- Clipboard -------------------------------------------------------------------

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

    // ---- Crash reporting ---------------------------------------------------------------

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

    // ---- TerminalViewClient ------------------------------------------------------------

    override fun onScale(scale: Float): Float {
        val current = prefs.getInt(Prefs.TEXT_SIZE, 12)
        val next = (current * scale).toInt().coerceIn(MIN_TEXT_SIZE, MAX_TEXT_SIZE)
        if (next != current) {
            terminalView.setTextSize(next)
            prefs.edit().putInt(Prefs.TEXT_SIZE, next).apply()
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
        applyColorScheme()
        terminalView.onScreenUpdated()
    }

    // ---- TerminalSessionClient ----------------------------------------------------------

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

    // ---- Logging -------------------------------------------------------------------------

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
}
