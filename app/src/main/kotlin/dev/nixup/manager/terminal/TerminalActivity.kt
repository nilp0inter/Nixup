package dev.nixup.manager.terminal

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalColors
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import dev.nixup.manager.R
import dev.nixup.manager.Root
import dev.nixup.manager.Ui
import org.json.JSONObject
import java.io.File
import java.util.Properties

/**
 * The terminal: root's shell in Nixup's view of the phone (environment.systemPackages on PATH, as an ssh session has
 * it), in sessions [TerminalService] keeps running while it is not on screen. Built on Termux's terminal view: a tap
 * brings the keyboard, a long press selects text (to copy, or paste), two fingers change the text size, and a row of
 * keys gives what a phone's keyboard lacks (Esc, Tab, Ctrl, Alt, the arrows, ...). A session that ended closes with
 * Enter; the last one closing closes the terminal. Its font and colors are the config's (terminal.font,
 * terminal.colors, as Nix-on-Droid has them), taken each time it comes on screen.
 */
class TerminalActivity : Activity(), ServiceConnection {
    private lateinit var view: TerminalView
    private lateinit var tabs: LinearLayout
    private lateinit var ctrlKey: TextView
    private lateinit var altKey: TextView
    private var service: TerminalService? = null
    private var ctrl = false
    private var alt = false
    /** Whether the config's colors are in TerminalColors already (sessions made since have them). */
    private var lookLoaded = false
    private val prefs by lazy { getSharedPreferences("terminal", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_terminal)
        view = findViewById(R.id.terminal_view)
        tabs = findViewById(R.id.terminal_sessions)
        view.setTerminalViewClient(viewClient)
        // The text size first: it makes the view's renderer (monospace), which the typeface needs.
        view.setTextSize(prefs.getInt(TEXT_SIZE, defaultTextSize()))
        addKeys(findViewById(R.id.terminal_keys))
        findViewById<LinearLayout>(R.id.terminal_root).setOnApplyWindowInsetsListener { root, insets ->
            Ui.padForSystemBars(root, insets, ime = true, consume = true)
        }
    }

    override fun onStart() {
        super.onStart()
        val intent = Intent(this, TerminalService::class.java)
        startForegroundService(intent)
        bindService(intent, this, BIND_AUTO_CREATE)
        loadLook()
    }

    override fun onStop() {
        service?.ui = null
        service = null
        unbindService(this)
        super.onStop()
    }

    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
        val terminal = (binder as TerminalService.Local).service
        service = terminal
        terminal.ui = sessionClient
        // Sessions kept from before take the config's colors too.
        if (lookLoaded) terminal.sessions.forEach { it.emulator?.mColors?.reset() }
        show(terminal.current?.takeIf { it in terminal.sessions } ?: terminal.sessions.lastOrNull() ?: terminal.newSession())
    }

    override fun onServiceDisconnected(name: ComponentName) {
        service = null
    }

    /** Shows [session] on the screen, and the sessions above it. */
    private fun show(session: TerminalSession) {
        service?.current = session
        view.attachSession(session)
        view.onScreenUpdated()
        view.requestFocus()
        updateTabs()
    }

    /** Closes [session]: the one before it comes on screen, or, with none left, the terminal closes. */
    private fun close(session: TerminalSession) {
        val terminal = service ?: return
        val index = terminal.sessions.indexOf(session)
        terminal.remove(session)
        val next = terminal.sessions.getOrNull((index - 1).coerceAtLeast(0))
        if (next == null) finish() else show(next)
    }

    private fun updateTabs() {
        val terminal = service ?: return
        tabs.removeAllViews()
        terminal.sessions.forEachIndexed { i, session ->
            val title = session.title?.takeIf { it.isNotBlank() }
            tabs.addView(button("${i + 1}${title?.let { ": ${it.take(24)}" } ?: ""}${if (session.isRunning) "" else " ✕"}") { show(session) }.apply {
                alpha = if (session == view.currentSession) 1f else 0.55f
                setOnLongClickListener {
                    AlertDialog.Builder(this@TerminalActivity)
                        .setMessage(getString(R.string.terminal_close, i + 1))
                        .setPositiveButton(R.string.terminal_close_yes) { _, _ -> close(session) }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                    true
                }
            })
        }
        tabs.addView(button("+") { service?.let { show(it.newSession()) } })
    }

    /** The keys a phone's keyboard lacks; Ctrl and Alt hold for the next key. */
    private fun addKeys(row: LinearLayout) {
        fun key(label: String, keyCode: Int) = row.addView(button(label) { sendKey(keyCode) })
        fun char(text: String) = row.addView(button(text) { view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, text.codePointAt(0), false, false) })
        key("ESC", KeyEvent.KEYCODE_ESCAPE)
        key("TAB", KeyEvent.KEYCODE_TAB)
        ctrlKey = button("CTRL") { setCtrl(!ctrl) }.also { row.addView(it) }
        altKey = button("ALT") { setAlt(!alt) }.also { row.addView(it) }
        key("←", KeyEvent.KEYCODE_DPAD_LEFT)
        key("↓", KeyEvent.KEYCODE_DPAD_DOWN)
        key("↑", KeyEvent.KEYCODE_DPAD_UP)
        key("→", KeyEvent.KEYCODE_DPAD_RIGHT)
        key("HOME", KeyEvent.KEYCODE_MOVE_HOME)
        key("END", KeyEvent.KEYCODE_MOVE_END)
        key("PGUP", KeyEvent.KEYCODE_PAGE_UP)
        key("PGDN", KeyEvent.KEYCODE_PAGE_DOWN)
        listOf("-", "/", "|", "~", ":", "*", "&").forEach { char(it) }
        setCtrl(false)
        setAlt(false)
    }

    private fun sendKey(keyCode: Int) {
        if (view.currentSession == null) return
        var mod = 0
        if (ctrl) mod = mod or KeyHandler.KEYMOD_CTRL
        if (alt) mod = mod or KeyHandler.KEYMOD_ALT
        setCtrl(false)
        setAlt(false)
        view.handleKeyCode(keyCode, mod)
    }

    private fun setCtrl(on: Boolean) {
        ctrl = on
        ctrlKey.setTextColor(if (on) Color.rgb(0x6C, 0xC6, 0xFF) else Color.WHITE)
    }

    private fun setAlt(on: Boolean) {
        alt = on
        altKey.setTextColor(if (on) Color.rgb(0x6C, 0xC6, 0xFF) else Color.WHITE)
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        gravity = Gravity.CENTER
        minWidth = px(44)
        setPadding(px(10), px(10), px(10), px(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun px(dp: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics).toInt()

    private fun defaultTextSize() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, DEFAULT_TEXT_SP, resources.displayMetrics).toInt()

    private fun changeTextSize(bigger: Boolean) {
        val size = (prefs.getInt(TEXT_SIZE, defaultTextSize()) + if (bigger) 2 else -2).coerceIn(px(6), px(40))
        prefs.edit().putInt(TEXT_SIZE, size).apply()
        view.setTextSize(size)
    }

    // --- The terminal view's questions and what it was given.

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeTextSize(scale > 1f)
                return 1f
            }
            return scale
        }

        override fun onSingleTapUp(e: MotionEvent?) {
            view.requestFocus()
            getSystemService(InputMethodManager::class.java).showSoftInput(view, 0)
        }

        override fun shouldBackButtonBeMappedToEscape() = false
        override fun shouldEnforceCharBasedInput() = false
        override fun shouldUseCtrlSpaceWorkaround() = false
        override fun isTerminalViewSelected() = true
        override fun copyModeChanged(copyMode: Boolean) {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            // A session that ended closes with Enter.
            if (session != null && !session.isRunning && keyCode == KeyEvent.KEYCODE_ENTER) {
                close(session)
                return true
            }
            return false
        }

        override fun onKeyUp(keyCode: Int, e: KeyEvent?) = false
        override fun onLongPress(event: MotionEvent?) = false

        override fun readControlKey(): Boolean = ctrl.also { if (it) setCtrl(false) }
        override fun readAltKey(): Boolean = alt.also { if (it) setAlt(false) }
        override fun readShiftKey() = false
        override fun readFnKey() = false

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?) = false
        override fun onEmulatorSet() {}

        override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    // --- Its font and colors, from the config.

    /**
     * The config's font and colors, as the engine wrote them for the terminal (/data/adb/nixup/etc/nixup/terminal.json,
     * which only root reads); the font, a file in Nixup's store, is copied into the app once for each path.
     */
    private fun loadLook() = Thread {
        val text = Root.bytes("cat $LOOK")?.toString(Charsets.UTF_8)
        val look = text?.let { runCatching { JSONObject(it) }.getOrNull() }
        val colors = Properties()
        look?.optJSONObject("colors")?.let { json -> json.keys().forEach { colors.setProperty(it, json.getString(it)) } }
        val font = look?.optString("font")?.takeIf { it.startsWith("/nix/store/") }?.let { fontFile(it) }
        runOnUiThread { if (!isDestroyed) applyLook(colors, font) }
    }.start()

    /** The font at [path] in Nixup's store, as a file of the app's; null when it cannot be had. */
    private fun fontFile(path: String): File? {
        val file = File(filesDir, "terminal-font")
        if (file.isFile && prefs.getString(FONT, null) == path) return file
        val bytes = Root.bytes("cat '${STORE}${path.replace("'", "'\\''")}'")?.takeIf { it.isNotEmpty() } ?: return null
        file.writeBytes(bytes)
        prefs.edit().putString(FONT, path).apply()
        return file
    }

    /** [colors] over Termux's own (none: Termux's), in every session, and [font] (none: Android's monospace). */
    private fun applyLook(colors: Properties, font: File?) {
        runCatching { TerminalColors.COLOR_SCHEME.updateWith(colors) }.onFailure { Log.w("Terminal", "terminal.colors: ${it.message}") }
        service?.sessions?.forEach { it.emulator?.mColors?.reset() }
        lookLoaded = true
        view.setTypeface(font?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() } ?: Typeface.MONOSPACE)
        view.onScreenUpdated()
        view.invalidate()
    }

    // --- What the sessions say, passed on by the service while the terminal is on screen.

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            if (changedSession == view.currentSession) view.onScreenUpdated()
        }

        override fun onTitleChanged(changedSession: TerminalSession) = updateTabs()

        override fun onSessionFinished(finishedSession: TerminalSession) {
            updateTabs()
            if (finishedSession == view.currentSession) view.onScreenUpdated()
        }

        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.terminal), text ?: ""))
        }

        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            val text = getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(this@TerminalActivity)?.toString()
            if (!text.isNullOrEmpty()) session?.emulator?.paste(text)
        }

        override fun onBell(session: TerminalSession) {}

        override fun onColorsChanged(session: TerminalSession) {
            if (session == view.currentSession) view.invalidate()
        }

        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) {}
        override fun getTerminalCursorStyle(): Int? = null

        override fun logError(tag: String?, message: String?) { Log.e(tag, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Log.e(tag, message, e) }
        override fun logStackTrace(tag: String?, e: Exception?) { Log.e(tag, "", e) }
    }

    companion object {
        private const val TEXT_SIZE = "textSize"
        private const val FONT = "font"
        private const val DEFAULT_TEXT_SP = 13f
        /** The terminal's look, as the engine writes it (Etc.kt), and where Nixup's store is outside its view. */
        private const val LOOK = "/data/adb/nixup/etc/nixup/terminal.json"
        private const val STORE = "/data/adb/nixup/nix"
    }
}
