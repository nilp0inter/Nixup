package dev.nixup.manager

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.nixup.manager.terminal.TerminalActivity
import org.json.JSONObject
import java.util.Locale

/**
 * Five tabs, and a menu with the terminal (see [TerminalActivity]), the Google Play account (see [GoogleSignInActivity]),
 * the notifications' settings, freeing up space, what the phone supports, and about.
 * Build: the config to build (see [ConfigSource]): a file picked wherever the user keeps it, a new one made from the
 * module's default, a git repository cloned onto the phone (pulled, cloned again: the module's git.sh), or a flake's
 * address typed in; built with the module's build.sh (as root), or only tried (a dry run), or built with everything at
 * its newest (upgrade); its folder opened; and the generations, to go back to one.
 * Updates: the config's apps that have a newer version, checked and installed with the module's update.sh, found by
 * name with its search box. Backups: see [BackupsPane]. Services: see [ServicesPane]. Logs: see [LogsPane].
 */
class MainActivity : Activity() {
    private lateinit var runner: Runner
    private lateinit var tabs: List<Pair<Button, View>>
    private lateinit var backups: BackupsPane
    private lateinit var services: ServicesPane
    private lateinit var logs: LogsPane
    private lateinit var gitPanel: View
    private lateinit var gitStatus: TextView
    private lateinit var gitButtons: List<Button>
    /** The clone the config comes from (git.sh status), when it comes from one. */
    private var clone: JSONObject? = null
    private lateinit var buildButtons: List<Button>
    private lateinit var source: TextView
    private lateinit var lastBuild: TextView
    private lateinit var configButtons: List<Button>
    private lateinit var openFolder: Button
    private lateinit var output: TextView
    private lateinit var scroll: ScrollView
    private lateinit var updatesStatus: TextView
    private lateinit var checkUpdates: Button
    private lateinit var updateAll: Button
    private lateinit var updatesList: LinearLayout
    private lateinit var updatesOutput: TextView
    private lateinit var updatesScroll: ScrollView
    private lateinit var nixupUpdate: View
    private lateinit var nixupUpdateText: TextView
    private lateinit var nixupUpdateInstall: Button
    private var tab = 0
    private var currentSource: String? = null
    private var builtOn: String? = null
    /** The last check's results (updates.json), and what the Updates tab's search box has. */
    private var updatesSaved = ""
    private var updatesQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        runner = Runner(this) { setRunning(it) }
        output = findViewById(R.id.output)
        scroll = findViewById(R.id.scroll)
        updatesStatus = findViewById(R.id.updates_status)
        checkUpdates = findViewById(R.id.check_updates)
        updateAll = findViewById(R.id.update_all)
        updatesList = findViewById(R.id.updates_list)
        updatesOutput = findViewById(R.id.updates_output)
        updatesScroll = findViewById(R.id.updates_scroll)
        nixupUpdate = findViewById(R.id.nixup_update)
        nixupUpdateText = findViewById(R.id.nixup_update_text)
        nixupUpdateInstall = findViewById(R.id.nixup_update_install)
        tabs = listOf(
            findViewById<Button>(R.id.tab_build) to findViewById(R.id.build_pane),
            findViewById<Button>(R.id.tab_updates) to findViewById(R.id.updates_pane),
            findViewById<Button>(R.id.tab_backups) to findViewById(R.id.backups_pane),
            findViewById<Button>(R.id.tab_services) to findViewById(R.id.services_pane),
            findViewById<Button>(R.id.tab_logs) to findViewById(R.id.logs_pane),
        )
        tabs.forEachIndexed { i, (button, _) -> button.setOnClickListener { select(i) } }
        buildButtons = listOf(
            findViewById<Button>(R.id.build).apply { setOnClickListener { build() } },
            findViewById<Button>(R.id.dry_run).apply { setOnClickListener { buildScript("dry", R.string.dry_running) } },
            findViewById<Button>(R.id.upgrade).apply { setOnClickListener { upgrade() } },
            findViewById<Button>(R.id.generations).apply { setOnClickListener { generations() } },
        )
        source = findViewById(R.id.source)
        lastBuild = findViewById(R.id.last_build)
        openFolder = findViewById(R.id.open_folder)
        openFolder.setOnClickListener { openFolder() }
        configButtons = listOf(
            findViewById<Button>(R.id.pick_file).apply { setOnClickListener { pickFile() } },
            findViewById<Button>(R.id.new_config).apply { setOnClickListener { newConfig() } },
            findViewById<Button>(R.id.git_source).apply { setOnClickListener { gitDialog() } },
            findViewById<Button>(R.id.type_source).apply { setOnClickListener { typeSource() } },
        )
        gitPanel = findViewById(R.id.git_panel)
        gitStatus = findViewById(R.id.git_status)
        gitButtons = listOf(
            findViewById<Button>(R.id.git_pull).apply { setOnClickListener { gitScript("pull", R.string.git_pulling) } },
            findViewById<Button>(R.id.git_pull_build).apply { setOnClickListener { pullAndBuild() } },
            findViewById<Button>(R.id.git_reclone).apply { setOnClickListener { reclone() } },
        )
        backups = BackupsPane(this, findViewById(R.id.backups_pane), runner)
        services = ServicesPane(this, findViewById(R.id.services_pane), runner)
        logs = LogsPane(this, findViewById(R.id.logs_pane), runner)
        loadSource()
        checkUpdates.setOnClickListener {
            runner.run(Root.MODULE + "sh \$d/update.sh app check 2>&1", getString(R.string.checking), updatesOutput, updatesScroll) { loadUpdates() }
        }
        updateAll.setOnClickListener { update(emptyList()) }
        Ui.onSearch(findViewById(R.id.updates_search)) {
            updatesQuery = it
            if (updatesSaved.isNotEmpty()) showUpdates(updatesSaved)
        }
        padForSystemBars(findViewById(R.id.root))

        Notifications.channels(this)
        // Magisk gives root to the phone owner's apps: in an app clone, work profile or other user, su is refused
        // without asking (and the jobs would only fail).
        if (android.os.Process.myUid() / 100_000 != 0) {
            AlertDialog.Builder(this)
                .setTitle(R.string.not_owner_title)
                .setMessage(R.string.not_owner_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        } else {
            UpdateJob.schedule(this)
            BackupJob.schedule(this)
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (savedInstanceState != null) select(savedInstanceState.getInt(STATE_TAB)) else showTab(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showTab(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_TAB, tab)
    }

    override fun onDestroy() {
        runner.close()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        visible = true
        if (tab == 4) logs.load()
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    /** A notification opens the tab it is about: Updates, or Logs. */
    private fun showTab(intent: Intent?) = select(
        when (intent?.getStringExtra(EXTRA_TAB)) {
            TAB_UPDATES -> 1
            TAB_LOGS -> 4
            else -> 0
        },
    )

    private fun select(index: Int) {
        tab = index
        // A search box of the tab left keeps no keyboard open over the next one.
        currentFocus?.let { focused ->
            getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(focused.windowToken, 0)
            focused.clearFocus()
        }
        tabs.forEachIndexed { i, (button, pane) ->
            pane.visibility = if (i == index) View.VISIBLE else View.GONE
            button.setTypeface(null, if (i == index) Typeface.BOLD else Typeface.NORMAL)
            button.alpha = if (i == index) 1f else 0.6f
        }
        when (index) {
            1 -> loadUpdates()
            2 -> backups.load()
            3 -> services.load()
            4 -> logs.load()
        }
    }

    private fun setRunning(on: Boolean) {
        buildButtons.forEach { it.isEnabled = !on }
        configButtons.forEach { it.isEnabled = !on }
        gitButtons.forEach { it.isEnabled = !on }
        checkUpdates.isEnabled = !on
        updateAll.isEnabled = !on
        nixupUpdateInstall.isEnabled = !on
        for (i in 0 until updatesList.childCount) updatesList.getChildAt(i).findViewWithTag<Button>(UPDATE_BUTTON)?.isEnabled = !on
        backups.setRunning(on)
        services.setRunning(on)
    }

    // --- The menu.

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_terminal -> startActivity(Intent(this, TerminalActivity::class.java))
            R.id.menu_play_account -> playAccount()
            R.id.menu_specialisations -> specialisations()
            R.id.menu_notifications -> startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName),
            )
            R.id.menu_free_space -> AlertDialog.Builder(this)
                .setTitle(R.string.free_space_title)
                .setMessage(R.string.free_space_message)
                .setPositiveButton(R.string.free_space) { _, _ -> buildScript("gc", R.string.freeing_space) }
                .setNegativeButton(R.string.cancel, null)
                .show()
            R.id.menu_support -> {
                val show = Ui.textDialog(this, getString(R.string.menu_support), getString(R.string.reading))
                runner.read(Root.ENGINE + "support 2>&1") { exit, said -> show(if (exit == null) getString(R.string.no_root) else said) }
            }
            R.id.menu_about -> about()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /** The app's version and the module's, and where Nixup's source is. */
    private fun about() {
        val app = packageManager.getPackageInfo(packageName, 0).versionName
        runner.read(Root.MODULE + "sed -n 's/^version=//p' \$d/module.prop 2>/dev/null") { _, said ->
            AlertDialog.Builder(this)
                .setIcon(R.mipmap.ic_launcher)
                .setTitle(R.string.app_name)
                .setMessage(getString(R.string.about_message, app, said.trim().ifEmpty { getString(R.string.module_missing) }))
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.about_source) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SOURCE)))
                    } catch (e: ActivityNotFoundException) {
                        toast(SOURCE)
                    }
                }
                .show()
        }
    }

    // --- The config: kept by the module in /data/adb/nixup/source (see ConfigSource).

    /** The config a build reads (as the module's config_source finds it, so a phone set up before it could be picked shows its own), and the last build's. */
    private fun loadSource() {
        runner.read(
            Root.MODULE + "if [ -f \$d/common.sh ]; then MODDIR=\$d; . \$d/common.sh > /dev/null 2>&1; s=\$(config_source); " +
                "else s=\$(head -n 1 ${ConfigSource.FILE} 2>/dev/null); fi; echo \"source=\$s\"; " +
                "g=\$(cat /data/adb/nixup/generations/last 2>/dev/null); echo \"generation=\$g\"; " +
                "[ -n \"\$g\" ] && echo \"built=\$(head -n 1 /data/adb/nixup/generations/\$g/source 2>/dev/null)\" && " +
                "echo \"on=\$(head -n 1 /data/adb/nixup/generations/\$g/built-on 2>/dev/null)\"; " +
                "[ -f \$d/git.sh ] && echo \"git=\$(sh \$d/git.sh app status --json 2>/dev/null | tail -n 1)\"",
        ) { _, said ->
            val values = said.lineSequence().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.substring(0, it) to line.substring(it + 1) } }.toMap()
            showSource(values["source"].orEmpty(), values["generation"].orEmpty(), values["built"].orEmpty(), values["on"].orEmpty())
            showClone(values["git"]?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.optBoolean("clone") })
        }
    }

    /** The clone, when the config comes from one: its repository, branch, commit and local changes, and its buttons. */
    private fun showClone(json: JSONObject?) {
        clone = json
        val inIt = json != null && currentSource?.startsWith(json.optString("path") + "/") == true
        gitPanel.visibility = if (inIt) View.VISIBLE else View.GONE
        if (json == null || !inIt) return
        val url = json.optString("url").removePrefix("https://")
        val commit = listOf(json.optString("commit"), json.optString("subject")).filter { it.isNotEmpty() }.joinToString(" ").ifEmpty { getString(R.string.unknown) }
        var line = getString(R.string.git_status, url, json.optString("branch").ifEmpty { getString(R.string.unknown) }, commit)
        val changes = json.optInt("changes")
        if (changes > 0) line = resources.getQuantityString(R.plurals.git_status_changes, changes, line, changes)
        json.optLong("pulled").takeIf { it > 0 }?.let { line = getString(R.string.git_status_pulled, line, Ui.ago(it * 1000)) }
        gitStatus.text = line
    }

    /** Git: a repository to clone onto the phone, whose config is then the one built (the module's git.sh). */
    private fun gitDialog() {
        val pad = Ui.dp(this, 20)
        fun field(hint: Int, value: String = "", secret: Boolean = false) = EditText(this).apply {
            setSingleLine()
            this.hint = getString(hint)
            setText(value)
            if (secret) {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                // A password field's font is monospace otherwise.
                typeface = Typeface.DEFAULT
            }
        }
        val now = clone
        val repository = field(R.string.git_repository_hint, now?.optString("url").orEmpty())
        val branch = field(R.string.git_branch_hint, now?.optString("branch").orEmpty())
        val dir = field(R.string.git_dir_hint, now?.optString("dir").orEmpty())
        val name = field(R.string.git_name_hint, now?.optString("name").orEmpty())
        val token = field(R.string.git_token_hint, secret = true)
        val submodules = CheckBox(this).apply {
            setText(R.string.git_submodules)
            isChecked = now?.optBoolean("submodules") == true
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            listOf(repository, branch, dir, name, token, submodules).forEach { addView(it) }
            addView(Ui.note(this@MainActivity, getString(R.string.git_note)))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.git_title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.git_clone) { _, _ ->
                val values = listOf(repository, branch, dir, name).map { it.text.toString().trim() }
                cloneRepository(values[0], values[1], values[2], values[3], token.text.toString().trim(), submodules.isChecked)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cloneRepository(url: String, branch: String, dir: String, name: String, token: String, submodules: Boolean) {
        if (!GIT_ADDRESS.matches(url) || listOf(branch, dir, name).any { !GIT_WORD.matches(it) } || (token.isNotEmpty() && !TOKEN.matches(token))) {
            return toast(getString(R.string.git_invalid))
        }
        val clone = {
            val args = listOf("clone", url) + listOfNotNull(
                branch.takeIf { it.isNotEmpty() }?.let { "--branch=$it" }, dir.takeIf { it.isNotEmpty() }?.let { "--dir=$it" },
                name.takeIf { it.isNotEmpty() }?.let { "--name=$it" }, "--submodules".takeIf { submodules },
            )
            gitScript(args.joinToString(" ") { ConfigSource.quote(it) }, R.string.git_cloning, url)
        }
        val start = {
            if (token.isEmpty()) clone()
            else saveToken(hostOf(url), token) { clone() }
        }
        val old = this.clone
        if (old == null) return start()
        AlertDialog.Builder(this)
            .setTitle(R.string.git_replace_title)
            .setMessage(getString(R.string.git_replace_message, old.optString("url").removePrefix("https://")))
            .setPositiveButton(R.string.git_clone) { _, _ -> start() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The site of a repository's address (github.com for you/phone), whose token it is. */
    private fun hostOf(url: String): String = when {
        url.startsWith("github:") || !url.contains(':') -> "github.com"
        url.startsWith("gitlab:") -> "gitlab.com"
        url.contains("://") -> url.substringAfter("://").substringBefore('/').substringAfter('@')
        else -> url.substringAfter('@').substringBefore(':')
    }

    /**
     * A private repository's token, in Nix's access-tokens (only root reads it), where git.sh and Nix take it: it goes
     * to root through its input, never in a command.
     */
    private fun saveToken(host: String, token: String, then: () -> Unit) {
        val file = "/data/adb/nixup/secrets/nix-access-tokens"
        runner.read(
            "umask 077; mkdir -p /data/adb/nixup/secrets && IFS= read -r t && " +
                "{ grep -v '^${host.replace(".", "\\.")}=' $file 2>/dev/null; printf '%s=%s\\n' ${ConfigSource.quote(host)} \"\$t\"; } > $file.new && mv -f $file.new $file",
            input = token + "\n",
        ) { exit, _ -> if (exit == 0) then() else toast(getString(R.string.git_token_failed)) }
    }

    /** Runs the module's git.sh with [args] in the Build tab, then shows the config again. */
    private fun gitScript(args: String, starting: Int, vararg formatArgs: Any) {
        select(0)
        runner.run(Root.MODULE + "sh \$d/git.sh app $args 2>&1", getString(starting, *formatArgs), output, scroll) { loadSource() }
    }

    private fun pullAndBuild() {
        select(0)
        runner.run(Root.MODULE + "sh \$d/git.sh app pull 2>&1 && sh \$d/build.sh app switch 2>&1", getString(R.string.git_pulling), output, scroll) { loadSource() }
    }

    private fun reclone() {
        val url = clone?.optString("url") ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.git_reclone_title)
            .setMessage(getString(R.string.git_reclone_message, url.removePrefix("https://")))
            .setPositiveButton(R.string.git_reclone) { _, _ -> gitScript("reclone", R.string.git_cloning, url) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showSource(picked: String, generation: String, built: String, on: String) {
        currentSource = picked.ifEmpty { null }
        builtOn = on.ifEmpty { null }
        val storage = getString(R.string.phone_storage)
        source.text = when {
            picked.isEmpty() -> getString(R.string.source_none)
            !ConfigSource.isFile(picked) -> getString(R.string.source_address, picked)
            picked.substringBefore('#').endsWith("/flake.nix") -> getString(R.string.source_flake, ConfigSource.shown(picked, storage))
            else -> getString(R.string.source_file, ConfigSource.shown(picked, storage))
        }
        lastBuild.text = when {
            generation.isEmpty() || generation == "0" -> getString(R.string.no_build_yet)
            built.isEmpty() -> getString(R.string.last_build_unknown, generation)
            on.isNotEmpty() -> getString(R.string.last_build_on, generation, built, on)
            else -> getString(R.string.last_build, generation, ConfigSource.shown(built, storage))
        }
        openFolder.isEnabled = currentSource?.let { ConfigSource.folderDocument(it) } != null
    }

    /** Runs the module's build.sh with [args] in the Build tab, then shows the last build again. */
    private fun buildScript(args: String, starting: Int, vararg formatArgs: Any) {
        select(0)
        runner.run(Root.MODULE + "sh \$d/build.sh app $args 2>&1", getString(starting, *formatArgs), output, scroll) { loadSource() }
    }

    /**
     * Build: the picked config, built on the phone. When the last build came from a PC (nixup-rebuild), whose config
     * the phone may not have, it can instead be applied again as it is.
     */
    private fun build() {
        val pc = builtOn ?: return buildScript("switch", R.string.building)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.built_elsewhere_title, pc))
            .setMessage(R.string.built_elsewhere_message)
            .setPositiveButton(R.string.apply_again) { _, _ -> buildScript("again", R.string.building) }
            .setNeutralButton(R.string.build_here) { _, _ -> buildScript("switch", R.string.building) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Upgrade: a build with the app lists, releases and a flake's inputs at their newest (build.sh update). */
    private fun upgrade() {
        AlertDialog.Builder(this)
            .setTitle(R.string.upgrade_title)
            .setMessage(R.string.upgrade_message)
            .setPositiveButton(R.string.upgrade) { _, _ -> buildScript("update", R.string.upgrading) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** A generation: its number, when it was made, what from and on which PC, and its label and the config's revision (nix/version.nix). */
    private class Generation(val number: String, val time: Long, val source: String, val builtOn: String, val label: String = "", val revision: String = "")

    /** The generations, newest first; one picked can be tried (a dry run) or switched to (build.sh generation). */
    private fun generations() {
        runner.read(GENERATIONS) { exit, said ->
            if (exit == null) return@read toast(getString(R.string.no_root))
            val current = said.lineSequence().firstOrNull { it.startsWith("last=") }?.removePrefix("last=")?.trim()
            val all = said.lineSequence().map { it.split('\t') }.filter { it.size >= 4 && it[0].isNotEmpty() && it[0].all(Char::isDigit) }
                .map { Generation(it[0], (it[1].toLongOrNull() ?: 0L) * 1000, it[2], it[3], it.getOrElse(4) { "" }, it.getOrElse(5) { "" }) }.toList()
            if (all.isEmpty()) return@read toast(getString(R.string.no_generations))
            val items = all.map { g ->
                getString(R.string.generation_item, g.number, when (g.time) { 0L -> getString(R.string.unknown); else -> Ui.date(g.time) },
                    if (g.number == current) getString(R.string.generation_current) else "", from(g))
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.generations)
                .setItems(items.toTypedArray()) { _, i -> generation(all[i]) }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    private fun from(g: Generation): String {
        val source = ConfigSource.shown(g.source, getString(R.string.phone_storage)).ifEmpty { getString(R.string.unknown) }
        return if (g.builtOn.isNotEmpty()) getString(R.string.generation_from_pc, source, g.builtOn) else source
    }

    /** Its label and the config's revision, as the dialog's last lines (none for a generation made before they were kept). */
    private fun version(g: Generation): String =
        (if (g.label.isNotEmpty()) "\n\n" + getString(R.string.generation_label, g.label) else "") +
            (if (g.revision.isNotEmpty()) "\n" + getString(R.string.generation_revision, g.revision) else "")

    private fun generation(g: Generation) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.generation_title, g.number))
            .setMessage(getString(R.string.generation_message, if (g.time == 0L) getString(R.string.unknown) else Ui.date(g.time), from(g)) + version(g))
            .setPositiveButton(R.string.switch_to) { _, _ -> buildScript("generation ${g.number}", R.string.switching, g.number) }
            .setNeutralButton(R.string.dry_run) { _, _ -> buildScript("generation ${g.number} dry", R.string.generation_dry, g.number) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * The last build's specialisations (specialisation.<name> in the config) and the one in use; the one picked, or
     * the config itself, is applied without building again (build.sh again --specialisation, as a generation's).
     */
    private fun specialisations() {
        runner.read(SPECIALISATIONS) { exit, said ->
            if (exit == null) return@read toast(getString(R.string.no_root))
            val inUse = said.lineSequence().firstOrNull { it.startsWith("in-use=") }?.removePrefix("in-use=")?.trim().orEmpty()
            val names = runCatching {
                JSONObject(said.substringAfter("$SPLIT\n", "")).optJSONObject("specialisation")?.keys()?.asSequence()?.toList()
            }.getOrNull().orEmpty().filter { SPECIALISATION_NAME.matches(it) }.sorted()
            if (names.isEmpty()) return@read toast(getString(R.string.no_specialisations))
            val choices = listOf("") + names
            val items = choices.map { name ->
                (if (name.isEmpty()) getString(R.string.specialisation_none) else name) +
                    if (name == inUse) getString(R.string.generation_current) else ""
            }
            AlertDialog.Builder(this)
                .setTitle(R.string.specialisations)
                .setItems(items.toTypedArray()) { _, i ->
                    val name = choices[i]
                    when {
                        name == inUse -> Unit
                        name.isEmpty() -> buildScript("again --no-specialisation", R.string.specialisation_leaving)
                        else -> buildScript("again --specialisation $name", R.string.specialisation_switching, name)
                    }
                }
                .setNegativeButton(R.string.close, null)
                .show()
        }
    }

    /** Pick file: the system's file picker, for a configuration.nix or a flake.nix wherever it is. */
    private fun pickFile() = startForResult(
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
        PICK_FILE,
    )

    /** New: the system's file picker makes a configuration.nix where the user says, with the module's default config in it. */
    private fun newConfig() = startForResult(
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            // octet-stream: the picker keeps the name as it is (text/plain would add .txt).
            .setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, "configuration.nix"),
        NEW_CONFIG,
    )

    /** A folder to back up, from the system's folder picker (for the Backups tab). */
    fun pickFolder() = startForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER)

    private fun startForResult(intent: Intent, request: Int) = try {
        startActivityForResult(intent, request)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(this, R.string.no_folder_app, Toast.LENGTH_LONG).show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        if (requestCode == PLAY_SIGN_IN) return playSignedIn(data)
        val uri = data.data ?: return
        when (requestCode) {
            PICK_FILE -> savePicked(uri)
            NEW_CONFIG -> writeNew(uri)
            PICK_FOLDER -> backups.folderPicked(uri)
        }
    }

    /** A picked document as the path root reads it at, kept as the config. */
    private fun savePicked(uri: Uri) {
        val path = ConfigSource.pathOf(uri)
        val command = if (path == null) ConfigSource.pathCommand(uri) else null
        if (path != null || command == null) return checkPicked(path)
        runner.read(command) { _, said -> checkPicked(ConfigSource.pathIn(said)) }
    }

    private fun checkPicked(path: String?) = when {
        path == null -> toast(getString(R.string.not_a_phone_file))
        !path.endsWith(".nix") -> toast(getString(R.string.not_nix))
        else -> saveSource(path)
    }

    /** The module's default config, written into the new document, which then is the config. */
    private fun writeNew(uri: Uri) {
        runner.read(Root.MODULE + "cat \$d/config/configuration.nix") { exit, template ->
            if (exit != 0 || template.isEmpty()) return@read toast(getString(R.string.no_template))
            runner.background {
                val written = try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(template.toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
                runOnUiThread { if (written) savePicked(uri) else toast(getString(R.string.not_written)) }
            }
        }
    }

    /** Type it: a flake's address (github:you/phone), or a file's path, either with #name; what is there now to edit. */
    private fun typeSource() {
        val field = EditText(this).apply {
            setSingleLine()
            hint = getString(R.string.type_source_hint)
            setText(currentSource.orEmpty())
        }
        val pad = Ui.dp(this, 20)
        val box = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(field)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.type_source_title)
            .setView(box)
            .setPositiveButton(R.string.ok) { _, _ -> saveSource(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Keeps [picked] as the config, once root can read a file of it; the module's build reads it from then on. */
    private fun saveSource(picked: String) {
        if (!ConfigSource.isValid(picked)) {
            toast(getString(R.string.source_invalid))
            return
        }
        val where = picked.substringBefore('#')
        val check = if (ConfigSource.isFile(picked)) {
            val card = if (where.startsWith("/storage/") && !where.startsWith("/storage/emulated/")) "/mnt/media_rw/" + where.removePrefix("/storage/") else where
            "{ [ -f ${ConfigSource.quote(where)} ] || [ -f ${ConfigSource.quote(card)} ]; } || exit 3; "
        } else {
            ""
        }
        runner.read(
            check + "mkdir -p /data/adb/nixup && printf '%s\\n' ${ConfigSource.quote(picked)} > ${ConfigSource.FILE} && rm -f /data/adb/nixup/build-from",
        ) { exit, _ ->
            when (exit) {
                0 -> loadSource()
                3 -> toast(getString(R.string.not_readable, where))
                else -> toast(getString(R.string.no_root))
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    // --- The Google Play account, which android.packages.playstore's apps come with (see GoogleSignInActivity).

    /** Who is signed in to Google Play on the phone (the module keeps the account where root alone reads it), with Sign in, its token, and Sign out. */
    private fun playAccount() {
        runner.read("head -n 1 $PLAY_ACCOUNT 2>/dev/null") { exit, said ->
            if (exit == null) return@read toast(getString(R.string.no_root))
            val email = said.trim().takeIf { it.contains('@') }
            val dialog = AlertDialog.Builder(this).setTitle(R.string.menu_play_account).setNegativeButton(R.string.close, null)
            if (email == null) {
                dialog.setMessage(R.string.play_account_none).setPositiveButton(R.string.play_sign_in) { _, _ -> signInToPlay() }
            } else {
                dialog.setMessage(getString(R.string.play_account_signed_in, email))
                    .setPositiveButton(R.string.play_show_token) { _, _ -> readPlayToken(getString(R.string.play_token_title)) }
                    .setNeutralButton(R.string.play_sign_out) { _, _ -> signOutOfPlay(email) }
            }
            dialog.show()
        }
    }

    private fun signInToPlay() = startForResult(Intent(this, GoogleSignInActivity::class.java), PLAY_SIGN_IN)

    /**
     * The sign-in page's token goes to the engine through its input (never in a command), which keeps the account's
     * lasting one; signed in, that one is shown, for a config on a PC.
     */
    private fun playSignedIn(data: Intent) {
        val email = data.getStringExtra(GoogleSignInActivity.EXTRA_EMAIL).orEmpty()
        val token = data.getStringExtra(GoogleSignInActivity.EXTRA_TOKEN) ?: return
        val waiting = Toast.makeText(this, R.string.play_signing_in, Toast.LENGTH_LONG).apply { show() }
        runner.read(Root.ENGINE + "playstore-login 2>&1", input = "$email\n$token\n") { exit, said ->
            waiting.cancel()
            if (exit == 0) return@read readPlayToken(Runner.lastLine(said).removePrefix("✔ "))
            val text = if (exit == null) getString(R.string.no_root) else getString(R.string.play_sign_in_failed, Runner.lastLine(said))
            AlertDialog.Builder(this).setTitle(R.string.menu_play_account).setMessage(text).setPositiveButton(R.string.close, null).show()
        }
    }

    /** The account the phone keeps (its email address, then its token, which root alone reads), shown under [title]. */
    private fun readPlayToken(title: String) {
        runner.read("cat $PLAY_ACCOUNT") { exit, said ->
            val lines = said.lines().map { it.trim() }
            if (exit != 0 || lines.size < 2 || !lines[0].contains('@') || lines[1].isEmpty()) {
                return@read toast(getString(R.string.play_token_unreadable, if (exit == null) getString(R.string.no_root) else Runner.lastLine(said)))
            }
            showPlayToken(title, lines[0], lines[1])
        }
    }

    /**
     * The account's token, for a config on a PC (android.playstore.tokenFile, which each build puts on the phone), with the
     * lines that use it: copied (marked private, so Android's clipboard preview hides it) or saved to Download.
     */
    private fun showPlayToken(title: String, email: String, token: String) {
        val pad = Ui.dp(this, 20)
        fun code(text: String) = TextView(this).apply {
            this.text = text
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
            addView(Ui.note(this@MainActivity, getString(R.string.play_token_message, email)))
            addView(code(token))
            addView(Ui.note(this@MainActivity, getString(R.string.play_token_config)))
            addView(code("android.playstore.account = \"$email\";\nandroid.playstore.tokenFile = ./$PLAY_TOKEN_FILE;"))
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton(R.string.copy) { _, _ ->
                val clip = ClipData.newPlainText(getString(R.string.play_token_title), token)
                clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
                getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                toast(getString(R.string.play_token_copied))
            }
            .setNeutralButton(R.string.play_token_save) { _, _ -> savePlayToken(token) }
            .setNegativeButton(R.string.close, null)
            .show()
    }

    /** The token in Download/google-play.token (Android numbers the name when one is there already). */
    private fun savePlayToken(token: String) {
        if (Build.VERSION.SDK_INT < 29) {
            toast(getString(R.string.play_token_save_android_9))
            return
        }
        runner.background {
            val saved = try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, PLAY_TOKEN_FILE)
                    // octet-stream: the name stays as it is (text/plain would add .txt).
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no place in Download")
                contentResolver.openOutputStream(uri, "w")?.use { it.write("$token\n".toByteArray()) } ?: throw IllegalStateException("cannot write it")
                contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: PLAY_TOKEN_FILE
            } catch (e: Exception) {
                runOnUiThread { toast(getString(R.string.play_token_not_saved, e.message ?: e.javaClass.simpleName)) }
                return@background
            }
            runOnUiThread { toast(getString(R.string.play_token_saved, saved)) }
        }
    }

    private fun signOutOfPlay(email: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.play_sign_out_title)
            .setMessage(getString(R.string.play_sign_out_message, email))
            .setPositiveButton(R.string.play_sign_out) { _, _ ->
                runner.read(Root.ENGINE + "playstore-logout 2>&1") { exit, said ->
                    toast(if (exit == null) getString(R.string.no_root) else Runner.lastLine(said).removePrefix("✔ "))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // --- Updates.

    private fun update(ids: List<String>) {
        // The names come from updates.json; only package names go into the command.
        val apps = ids.filter { PACKAGE_NAME.matches(it) }.joinToString(" ")
        runner.run(Root.MODULE + "sh \$d/update.sh app now $apps 2>&1", getString(R.string.updating), updatesOutput, updatesScroll) { loadUpdates() }
    }

    /** The last check's results (updates.json, and nixup-update.json for Nixup itself, kept by the module), read as root. */
    private fun loadUpdates() {
        runner.read("cat /data/adb/nixup/updates.json 2>/dev/null; echo; echo $SPLIT; cat /data/adb/nixup/nixup-update.json 2>/dev/null") { _, said ->
            showUpdates(said.substringBefore(SPLIT).trim())
            showNixupUpdate(said.substringAfter(SPLIT, "").trim())
        }
    }

    /** A newer Nixup, with its Install button: asked first, and installed with the module's update.sh (self-install). */
    private fun showNixupUpdate(saved: String) {
        val json = runCatching { JSONObject(saved) }.getOrNull()
        val latest = json?.optJSONObject("latest")
        if (json == null || latest == null || !json.optBoolean("newer")) {
            nixupUpdate.visibility = View.GONE
            return
        }
        val name = latest.optString("version")
        nixupUpdateText.text = getString(R.string.nixup_update, name, json.optString("version"))
        nixupUpdate.visibility = View.VISIBLE
        nixupUpdateInstall.isEnabled = !runner.running
        nixupUpdateInstall.setOnClickListener {
            val dialog = AlertDialog.Builder(this)
                .setTitle(getString(R.string.nixup_install_title, name))
                .setMessage(R.string.nixup_install_message)
                .setPositiveButton(R.string.nixup_install) { _, _ ->
                    runner.run(Root.MODULE + "sh \$d/update.sh app self-install 2>&1", getString(R.string.nixup_installing, name), updatesOutput, updatesScroll) { loadUpdates() }
                }
                .setNegativeButton(R.string.cancel, null)
            latest.optString("changelog").takeIf { it.startsWith("https://") }?.let { url ->
                dialog.setNeutralButton(R.string.nixup_whats_new) { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: ActivityNotFoundException) {
                        toast(url)
                    }
                }
            }
            dialog.show()
        }
    }

    /** The updates of the last check, those with what the search box has (an app's name, package or repository). */
    private fun showUpdates(saved: String) {
        updatesSaved = saved
        updatesList.removeAllViews()
        val json = try {
            JSONObject(saved)
        } catch (e: Exception) {
            updatesStatus.setText(R.string.updates_not_checked)
            updateAll.isEnabled = false
            return
        }
        val checked = Ui.date(json.optLong("checked"))
        val auto = json.optJSONObject("autoUpdate")
        val repos = auto?.keys()?.asSequence()?.joinToString(", ") { repo ->
            getString(R.string.auto_repo, repo, getString(if (auto.optBoolean(repo)) R.string.auto_on else R.string.auto_off))
        } ?: ""
        updatesStatus.text = getString(R.string.updates_status, checked, repos)
        val updates = json.optJSONArray("updates")
        if (updates == null || updates.length() == 0) {
            updatesList.addView(TextView(this).apply { setText(R.string.up_to_date) })
            updateAll.isEnabled = false
            return
        }
        val found = (0 until updates.length()).map { updates.getJSONObject(it) }.filter { update ->
            Ui.matches(updatesQuery, update.optString("name"), update.optString("id"), update.optString("repo"), update.optString("from"), update.optString("to"))
        }
        if (found.isEmpty()) updatesList.addView(Ui.noMatch(this, updatesQuery))
        for (update in found) {
            val id = update.getString("id")
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            row.addView(
                TextView(this).apply {
                    text = getString(
                        R.string.update_line, update.getString("name"), update.getString("from"), update.getString("to"),
                        size(update.getLong("size")), update.optString("repo"),
                    )
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            row.addView(
                Button(this).apply {
                    setText(R.string.update)
                    tag = UPDATE_BUTTON
                    isEnabled = !runner.running
                    setOnClickListener { update(listOf(id)) }
                },
            )
            updatesList.addView(row)
        }
        updateAll.isEnabled = !runner.running
    }

    private fun size(bytes: Long): String = when {
        bytes < 0 -> getString(R.string.size_unknown)
        bytes >= 1L shl 20 -> String.format(Locale.ROOT, "%.1f MiB", bytes / 1048576.0)
        else -> "${(bytes + 1023) / 1024} KiB"
    }

    // --- The config's folder.

    /** Opens the folder of the picked config in the app the user picks: the Files app or another file manager. */
    private fun openFolder() {
        val folder = currentSource?.let { ConfigSource.folderDocument(it) } ?: return
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(folder, DocumentsContract.Document.MIME_TYPE_DIR)
        if (view.resolveActivity(packageManager) == null) {
            Toast.makeText(this, R.string.no_folder_app, Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent.createChooser(view, getString(R.string.open_folder_with)))
    }

    /** The app draws behind the status and navigation bars (edge to edge), so its content keeps clear of them. */
    private fun padForSystemBars(root: View) {
        val pad = root.paddingLeft
        root.setOnApplyWindowInsetsListener { view, insets -> Ui.padForSystemBars(view, insets, pad) }
    }

    companion object {
        const val EXTRA_TAB = "tab"
        const val TAB_UPDATES = "updates"
        const val TAB_LOGS = "logs"

        /** Whether the app is on the screen: what the user started in it, and watches, needs no notification. */
        @Volatile
        var visible = false

        /** A repository's address, a branch, folder or configuration name, and a token, as git.sh takes them. */
        private val GIT_ADDRESS = Regex("[A-Za-z0-9][A-Za-z0-9+._~:@/-]*")
        private val GIT_WORD = Regex("[A-Za-z0-9._/-]*")
        private val TOKEN = Regex("[A-Za-z0-9_.-]+")
        private const val STATE_TAB = "tab"
        private const val UPDATE_BUTTON = "update"
        private const val PICK_FILE = 1
        private const val NEW_CONFIG = 2
        private const val PICK_FOLDER = 3
        private const val PLAY_SIGN_IN = 4
        /** The Google Play account signed in on the phone: its email address, then its token (the engine's playstore-login writes it). */
        private const val PLAY_ACCOUNT = "/data/adb/nixup/secrets/google-play"
        /** The name its token is saved under, and the one the config's lines name (android.playstore.tokenFile). */
        private const val PLAY_TOKEN_FILE = "google-play.token"
        private const val SOURCE = "https://github.com/by-architect/Nixup"
        /** Between the two files loadUpdates reads. */
        private const val SPLIT = "--nixup-update--"
        private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        /** A specialisation's name, as nix/specialisation.nix allows it (it goes into build.sh's command). */
        private val SPECIALISATION_NAME = Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")

        /**
         * The specialisation in use, then the last build's whole profile (with its specialisations); system.json only
         * while it goes with profile.json (a module without specialisations writes profile.json alone: common.sh's
         * system_current).
         */
        private const val SPECIALISATIONS = "d=/data/adb/nixup; if [ -f \$d/system.json ] && ! [ \$d/profile.json -nt \$d/system.json ]; then " +
            "echo \"in-use=\$(cat \$d/specialisation 2>/dev/null)\"; echo '$SPLIT'; cat \$d/system.json; " +
            "else echo in-use=; echo '$SPLIT'; cat \$d/profile.json 2>/dev/null; fi"

        /** Each generation, newest first: its number, when it was made, what from and on which PC; and the current one. */
        private const val GENERATIONS = "g=/data/adb/nixup/generations; echo \"last=\$(cat \$g/last 2>/dev/null)\"; " +
            "for n in \$(ls \$g 2>/dev/null | grep -E '^[0-9]+\$' | sort -rn); do " +
            "printf '%s\\t%s\\t%s\\t%s\\t%s\\t%s\\n' \"\$n\" \"\$(stat -c %Y \$g/\$n/profile.json 2>/dev/null)\" " +
            "\"\$(head -n 1 \$g/\$n/source 2>/dev/null)\" \"\$(head -n 1 \$g/\$n/built-on 2>/dev/null)\" " +
            "\"\$(sed -n 's/^label=//p' \$g/\$n/version 2>/dev/null)\" \"\$(sed -n 's/^configurationRevision=//p' \$g/\$n/version 2>/dev/null)\"; done"
    }
}
