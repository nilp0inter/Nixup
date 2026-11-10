package dev.nixup.manager

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateUtils
import android.text.style.BackgroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SearchView
import android.widget.TextView
import androidx.annotation.RequiresApi
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The tabs' small pieces: headings and rows of their lists, search boxes over them, a dialog of text (searchable), a
 * dialog to pick from a long list, and times as people say them.
 */
object Ui {
    fun dp(context: Context, value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /**
     * Pads [view] clear of the system bars, and of the keyboard when [ime]. WindowInsets.Type arrived with
     * Android 11; before it, the window's own insets say the same. [consume] keeps them from going further.
     */
    fun padForSystemBars(view: View, insets: WindowInsets, pad: Int = 0, ime: Boolean = false, consume: Boolean = false): WindowInsets =
        if (Build.VERSION.SDK_INT >= 30) Api30.padForSystemBars(view, insets, pad, ime, consume)
        else {
            @Suppress("DEPRECATION")
            view.setPadding(
                pad + insets.systemWindowInsetLeft,
                pad + insets.systemWindowInsetTop,
                pad + insets.systemWindowInsetRight,
                pad + insets.systemWindowInsetBottom,
            )
            insets
        }

    @RequiresApi(30)
    private object Api30 {
        fun padForSystemBars(view: View, insets: WindowInsets, pad: Int, ime: Boolean, consume: Boolean): WindowInsets {
            var types = WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            if (ime) types = types or WindowInsets.Type.ime()
            val bars = insets.getInsets(types)
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom)
            return if (consume) WindowInsets.CONSUMED else insets
        }
    }

    /** A heading over a part of a list. */
    fun heading(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 16), 0, dp(context, 4))
    }

    /** A line of text in a list: why it is empty, or a note. */
    fun note(context: Context, text: String) = TextView(context).apply {
        this.text = text
        setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
        setTextIsSelectable(true)
        setPadding(0, dp(context, 8), 0, dp(context, 8))
    }

    /** A button of a row: borderless, as small as its text. */
    fun button(context: Context, text: String, click: () -> Unit) = Button(context, null, android.R.attr.borderlessButtonStyle).apply {
        this.text = text
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(context, 10), paddingTop, dp(context, 10), paddingBottom)
        setOnClickListener { click() }
    }

    /**
     * A row of a list: its title, what it says under it, and its buttons, beside them ([below]: under them, for more
     * than two), with a line under the row.
     */
    fun row(context: Context, title: String, subtitle: String, buttons: List<Button>, below: Boolean = false): View {
        val text = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                this.text = title
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Medium)
            })
            if (subtitle.isNotEmpty()) addView(TextView(context).apply {
                this.text = subtitle
                setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            })
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            buttons.forEach { addView(it) }
        }
        val row = LinearLayout(context).apply {
            orientation = if (below) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(context, 8), 0, if (below) 0 else dp(context, 8))
            if (below) {
                addView(text, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            } else {
                addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(actions)
            }
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(row)
            addView(View(context).apply {
                val divider = context.obtainStyledAttributes(intArrayOf(android.R.attr.listDivider))
                background = divider.getDrawable(0)
                divider.recycle()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1))
        }
    }

    // --- Searching.

    /** Text as a search compares it: lower case, without accents (ş is s, é is e), so either way of typing finds it. */
    private fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(MARKS, "").lowercase(Locale.ROOT)

    private val MARKS = Regex("\\p{Mn}+")

    /** The words of a search, as [folded] has them. */
    private fun words(query: String): List<String> = folded(query).split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** Whether [texts] have every word of [query] in them, whatever their case and accents; an empty one finds all. */
    fun matches(query: String, vararg texts: String?): Boolean {
        val words = words(query)
        if (words.isEmpty()) return true
        val haystack = folded(texts.filterNotNull().joinToString("\n"))
        return words.all { it in haystack }
    }

    /**
     * A search box (one of a tab's, or a dialog's): [changed] gets what is in it as it is typed, and again when it is
     * emptied with its ✕; the keyboard goes when the search is sent.
     */
    fun onSearch(box: SearchView, changed: (String) -> Unit) {
        box.setIconifiedByDefault(false)
        box.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(text: String): Boolean {
                changed(text.trim())
                return true
            }

            override fun onQueryTextSubmit(text: String): Boolean {
                box.context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(box.windowToken, 0)
                box.clearFocus()
                return true
            }
        })
    }

    /** A search box made here, for a dialog, with [hint] in it until something is typed. */
    fun searchBox(context: Context, hint: String, changed: (String) -> Unit) = SearchView(context).apply {
        queryHint = hint
        onSearch(this, changed)
    }

    /** What a list says when a search found nothing in it. */
    fun noMatch(context: Context, query: String) = note(context, context.getString(R.string.search_no_match, query))

    /**
     * A dialog to pick some of [items] (each its key, then what it shows), with a search box over them: what is typed
     * keeps the matching ones in sight, and what was picked stays picked while it is out of sight. [then] gets the keys
     * picked, in the items' order.
     */
    fun pickMany(activity: Activity, title: String, hint: String, items: List<Pair<String, String>>, positive: String, then: (List<String>) -> Unit) {
        val picked = mutableSetOf<String>()
        var shown = items
        val adapter = ArrayAdapter<String>(activity, android.R.layout.simple_list_item_multiple_choice, mutableListOf())
        val list = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            this.adapter = adapter
            setOnItemClickListener { _, _, i, _ -> shown[i].first.let { key -> if (isItemChecked(i)) picked += key else picked -= key } }
        }
        val count = TextView(activity).apply { setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small) }
        fun refill(query: String) {
            shown = items.filter { matches(query, it.second, it.first) }
            adapter.clear()
            adapter.addAll(shown.map { it.second })
            shown.forEachIndexed { i, item -> list.setItemChecked(i, item.first in picked) }
            count.text = if (shown.isEmpty()) activity.getString(R.string.search_no_match, query) else ""
            count.visibility = if (shown.isEmpty()) View.VISIBLE else View.GONE
        }
        val pad = dp(activity, 16)
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(searchBox(activity, hint) { refill(it) })
            addView(count)
            // As tall as it may be in the dialog, not the one line a list in a scrolling dialog gets.
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, activity.resources.displayMetrics.heightPixels / 2))
        }
        refill("")
        AlertDialog.Builder(activity).setTitle(title).setView(box)
            .setPositiveButton(positive) { _, _ -> then(items.map { it.first }.filter { it in picked }) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * A dialog of text (a log, a report): monospace and selectable, with its lines marked when [colored] (see
     * Logs.colored), a search box that keeps only the lines with what is typed in them (marked where it is), and a
     * button to copy it. What it gives back sets its text later (when it was read) and shows its end, where a log's
     * newest lines are.
     */
    fun textDialog(activity: Activity, title: String, text: String, colored: Boolean = false): (String) -> Unit {
        val pad = dp(activity, 20)
        var plain = text
        var query = ""
        val view = TextView(activity).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val found = TextView(activity).apply {
            setTextAppearance(android.R.style.TextAppearance_DeviceDefault_Small)
            setPadding(pad, 0, pad, 0)
            visibility = View.GONE
        }
        // Some height of its own, so the dialog keeps its size while a search narrows the lines.
        val scroll = ScrollView(activity).apply {
            addView(view)
            minimumHeight = activity.resources.displayMetrics.heightPixels * 2 / 5
        }
        fun show() {
            if (query.isEmpty()) {
                found.visibility = View.GONE
                view.text = if (colored) Logs.colored(activity, plain) else plain
                scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
                return
            }
            val lines = plain.lines()
            val kept = lines.filter { matches(query, it) }
            found.text = if (kept.isEmpty()) activity.getString(R.string.search_no_match, query)
            else activity.resources.getQuantityString(R.plurals.search_lines, kept.size, kept.size, lines.size)
            found.visibility = View.VISIBLE
            val joined = kept.joinToString("\n")
            view.text = marked(activity, if (colored) Logs.colored(activity, joined) else joined, query)
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(searchBox(activity, activity.getString(R.string.search_text)) { query = it; show() }.apply { setPadding(pad / 2, 0, pad / 2, 0) })
            addView(found)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        AlertDialog.Builder(activity).setTitle(title).setView(box).setPositiveButton(R.string.close, null)
            .setNeutralButton(R.string.copy) { _, _ ->
                activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(title, plain))
            }
            .show()
        return { said ->
            plain = said
            show()
        }
    }

    /** [text] with each word of [query] marked where it is (as typed, whatever its case). */
    private fun marked(context: Context, text: CharSequence, query: String): CharSequence {
        val out = SpannableStringBuilder(text)
        val color = context.getColor(R.color.search_match)
        for (word in query.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(text).forEach {
                out.setSpan(BackgroundColorSpan(color), it.range.first, it.range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /** A time, as "5 minutes ago" or "yesterday". */
    fun ago(time: Long): String = DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    /** A time, as 2026-11-03 10:20. */
    fun date(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(time))
}
