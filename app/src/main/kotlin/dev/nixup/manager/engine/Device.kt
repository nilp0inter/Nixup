package dev.nixup.manager.engine

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.LocaleList
import android.os.Looper
import android.os.UserManager
import android.os.StatFs
import android.os.Vibrator
import android.system.Os
import android.view.Display
import java.io.File

/**
 * The phone: its Android version and ABIs, the apps on it and their settings, through Android's own commands
 * (pm, appops, cmd, settings, dumpsys) used as root. App settings are those of the main user (0).
 */
object Device {
    val sdk = Build.VERSION.SDK_INT
    val abis: List<String> = Build.SUPPORTED_ABIS.toList()

    /** The apps that can take over the updates of an app from F-Droid, and of one from its releases (Obtainium, as on GitHub and on F-Droid). */
    val FDROID = listOf("org.fdroid.fdroid", "org.fdroid.basic")
    val OBTAINIUM = listOf("dev.imranr.obtainium", "dev.imranr.obtainium.fdroid")

    /**
     * And of one from Google Play: the Play Store (or microG's companion in its place), which apps that check where they
     * came from look for, as Aurora Store's root installs name it; else Aurora Store, which can update them.
     */
    val PLAYSTORE = listOf("com.android.vending", "com.aurora.store", "com.aurora.store.nightly")

    /**
     * A system context made in this process, as root tools do: for the signing keys of apps and APKs, and the
     * network policy. Where that fails it is null: pm still refuses an update signed with another key.
     */
    private val context: Context? by lazy {
        try {
            if (Looper.getMainLooper() == null) @Suppress("DEPRECATION") Looper.prepareMainLooper()
            val activityThread = Class.forName("android.app.ActivityThread")
            val thread = activityThread.getMethod("systemMain").invoke(null)
            activityThread.getMethod("getSystemContext").invoke(thread) as Context
        } catch (e: Throwable) {
            Out.line("(no system context here: ${e.javaClass.simpleName}; signing keys are left to pm)")
            null
        }
    }

    private val packageManager: PackageManager? get() = context?.packageManager

    /** That system context, for what reads the phone through one itself (Google Play's description of it); null where there is none. */
    val systemContext: Context? get() = context

    /** Whether the phone's user was unlocked since it started (its credential-encrypted storage is there then). */
    fun unlocked(): Boolean = context?.getSystemService(UserManager::class.java)?.let { users -> runCatching { users.isUserUnlocked }.getOrNull() }
        ?: (property("sys.user.0.ce_available") == "true")

    class Installed(val versionCode: Long, val versionName: String, val signers: Set<String>)

    fun installed(id: String): Installed? {
        packageManager?.let { pm ->
            return try {
                val info = pm.getPackageInfo(id, PackageManager.GET_SIGNING_CERTIFICATES)
                Installed(info.longVersionCode, info.versionName ?: "", signers(info.signingInfo))
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }
        val code = versionCode(id) ?: return null
        return Installed(code, code.toString(), emptySet())
    }

    /** The installed version, asked of pm itself (no cache in between), or null when the app is not installed. */
    fun versionCode(id: String): Long? = run("pm", "list", "packages", "--show-versioncode", id).second
        .lineSequence().firstOrNull { it.startsWith("package:$id ") }
        ?.substringAfter("versionCode:")?.trim()?.toLongOrNull()

    /** The app's user id (its uid for the main user), or null when it is not installed. */
    fun uid(id: String): Int? = run("pm", "list", "packages", "-U", id).second
        .lineSequence().firstOrNull { it.startsWith("package:$id ") }
        ?.substringAfter("uid:")?.trim()?.substringBefore(',')?.toIntOrNull()

    /** What an APK file is: its package name, version and the SHA-256 of its signing certificates. */
    class Archive(val id: String, val versionCode: Long, val versionName: String, val signers: Set<String>)

    /** The APK's package, version and keys, or null when it cannot be read here. */
    fun archive(apk: File): Archive? {
        val pm = packageManager ?: return null
        // Android 13 can skip certificate collection without GET_SIGNATURES (b/243324820).
        // Still read SigningInfo below, including its signing-certificate history.
        @Suppress("DEPRECATION")
        val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return null
        return Archive(info.packageName, info.longVersionCode, info.versionName ?: "", signers(info.signingInfo))
    }

    /** The app's name, as the launcher shows it, or null when it is not installed (or cannot be read here). */
    fun label(id: String): String? = try {
        packageManager?.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(id, 0)).toString() }
    } catch (e: Exception) {
        null
    }

    /** The certificate the installed app is signed with now (after a key rotation, the newest), or null when it is not installed or cannot be read here. */
    fun currentCertificate(id: String): ByteArray? = try {
        val info = packageManager?.getPackageInfo(id, PackageManager.GET_SIGNING_CERTIFICATES)?.signingInfo
        when {
            info == null -> null
            info.hasMultipleSigners() -> info.apkContentsSigners?.firstOrNull()?.toByteArray()
            else -> info.signingCertificateHistory?.lastOrNull()?.toByteArray()
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Whether the phone has the shared library (an APK other apps use, like Chrome's Trichrome) at this version. */
    fun hasLibrary(name: String, version: Long): Boolean = try {
        packageManager?.getSharedLibraries(0)?.any { it.name == name && it.longVersion == version } ?: false
    } catch (e: Exception) {
        false
    }

    /** The current signers and, after a key rotation, the earlier ones: F-Droid's index may list either. */
    private fun signers(info: SigningInfo?): Set<String> {
        if (info == null) return emptySet()
        val all = mutableListOf<Signature>()
        info.apkContentsSigners?.let { all += it }
        if (!info.hasMultipleSigners()) info.signingCertificateHistory?.let { all += it }
        return all.map { Net.sha256(it.toByteArray()) }.toSet()
    }

    fun firstInstalled(ids: List<String>): String? = ids.firstOrNull { versionCode(it) != null }

    fun freeBytes(dir: File): Long = StatFs(dir.path).availableBytes

    /** Installs (or updates) an APK, streamed into pm; [installer] becomes its installer of record. Null when it worked, else what pm said. */
    fun install(apk: File, installer: String?): String? {
        val command = mutableListOf("pm", "install", "-r", "--user", "current", "-S", apk.length().toString())
        if (installer != null) command += listOf("-i", installer)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        apk.inputStream().use { input -> process.outputStream.use { input.copyTo(it) } }
        val said = verifying { process.inputStream.bufferedReader().readText().trim() }
        return if (process.waitFor() == 0 && said.contains("Success")) null else said.ifEmpty { "exit ${process.exitValue()}" }
    }

    /**
     * [wait] for pm to install; when that takes long because an app verifier (Google Play Protect) asks on the screen
     * whether to install the app, the build says so once: Android waits for an answer there, up to an hour.
     */
    private fun <T> verifying(wait: () -> T): T {
        val watch = Thread {
            try {
                Thread.sleep(30_000)
                val focus = run("dumpsys", "window").second.lineSequence().firstOrNull { "mCurrentFocus=" in it }.orEmpty()
                if ("com.android.vending/" in focus || "com.google.android.gms/" in focus) {
                    Out.line("  Google Play Protect asks on the screen whether to install it: the install waits for an answer there " +
                        "(android.settings.developer.verifyAdbApps = false lets Nixup's installs through without asking)")
                }
            } catch (e: InterruptedException) {
                // (It ended first.)
            }
        }.apply { isDaemon = true; start() }
        try {
            return wait()
        } finally {
            watch.interrupt()
        }
    }

    /**
     * Installs an app from its APKs (a base APK and its splits), each streamed into one pm session, as adb
     * install-multiple does; [installer] becomes its installer of record. Null when it worked, else what pm said.
     */
    fun installAll(apks: List<File>, installer: String? = null): String? {
        val create = mutableListOf("pm", "install-create", "-r", "--user", "current", "-S", apks.sumOf { it.length() }.toString())
        if (installer != null) create += listOf("-i", installer)
        val created = run(*create.toTypedArray()).second
        val session = Regex("""\[(\d+)]""").find(created)?.groupValues?.get(1) ?: return created.trim()
        for ((i, apk) in apks.withIndex()) {
            val process = ProcessBuilder("pm", "install-write", "-S", apk.length().toString(), session, "$i-${apk.name}", "-").redirectErrorStream(true).start()
            apk.inputStream().use { input -> process.outputStream.use { input.copyTo(it) } }
            val said = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor() != 0 || !said.contains("Success")) {
                run("pm", "install-abandon", session)
                return said.ifEmpty { "exit ${process.exitValue()}" }
            }
        }
        val (status, said) = verifying { run("pm", "install-commit", session) }
        return if (status == 0 && said.contains("Success")) null else said.trim()
    }

    /** Uninstalls an app but keeps its data, so installing it again brings the data back. */
    fun uninstallKeepingData(id: String): Boolean =
        run("pm", "uninstall", "-k", "--user", "current", id).let { (status, said) -> status == 0 && said.contains("Success") }

    // --- An app's permissions, state and settings.

    class Permission(val granted: Boolean, val userSet: Boolean, val userFixed: Boolean)

    /** What dumpsys knows of an installed app: the permissions it asks for, and for the main user its runtime permissions, enabled state and whether it is suspended. Null when it is not installed for the main user. */
    class Dump(val requested: Set<String>, val runtime: Map<String, Permission>, val enabled: Int, val suspended: Boolean)

    private val permissionLine = Regex("""([\w.]+): granted=(true|false)(?:, flags=\[ ?(.*?) ?])?.*""")

    fun dump(id: String): Dump? {
        val said = run("dumpsys", "package", id).second
        if (!said.contains("Package [$id]")) return null
        val requested = HashSet<String>()
        val runtime = HashMap<String, Permission>()
        var enabled = 0
        var suspended = false
        var installed = false
        var apk = true
        var user = -1
        var section: String? = null
        var sectionIndent = 0
        var inPackages = false
        for (raw in said.lineSequence()) {
            val text = raw.trim()
            if (text.isEmpty()) continue
            if (!inPackages) {
                inPackages = text == "Packages:"
                continue
            }
            if (text.startsWith("Hidden system packages:")) break
            val indent = raw.length - raw.trimStart().length
            if (section != null && indent <= sectionIndent) section = null
            when (section) {
                "requested" -> requested += text.substringBefore(':')
                "runtime" -> permissionLine.matchEntire(text)?.let { m ->
                    val flags = m.groupValues[3].split('|')
                    runtime[m.groupValues[1]] = Permission(m.groupValues[2] == "true", "USER_SET" in flags, "USER_FIXED" in flags)
                }
                else -> when {
                    text == "pkg=null" -> apk = false
                    text == "requested permissions:" -> { section = "requested"; sectionIndent = indent }
                    text == "runtime permissions:" && user == 0 -> { section = "runtime"; sectionIndent = indent }
                    text.startsWith("User ") -> {
                        user = text.removePrefix("User ").substringBefore(':').toIntOrNull() ?: -1
                        // Later sections (app links, ...) have "User 0:" lines too, without the package state.
                        if (user == 0 && text.contains(" installed=")) {
                            installed = Regex("""\binstalled=(true|false)""").find(text)?.groupValues?.get(1) == "true"
                            enabled = Regex("""\benabled=(\d+)""").find(text)?.groupValues?.get(1)?.toInt() ?: 0
                            suspended = Regex("""\bsuspended=(true|false)""").find(text)?.groupValues?.get(1) == "true"
                        }
                    }
                }
            }
        }
        // Uninstalled with its data kept, an app is still listed: not installed for the main user, or (One UI after a
        // restart) said to be installed with no APK left (pkg=null).
        return if (installed && apk) Dump(requested, runtime, enabled, suspended) else null
    }

    /** Grants or revokes a runtime permission, with the user-set and user-fixed flags of the state wanted. */
    fun setPermission(id: String, permission: String, granted: Boolean, userSet: Boolean, userFixed: Boolean): Boolean {
        // A fixed permission first comes loose, so the grant or revoke goes through.
        run("pm", "clear-permission-flags", "--user", "0", id, permission, "user-fixed")
        val ok = run("pm", if (granted) "grant" else "revoke", "--user", "0", id, permission).first == 0
        val set = listOfNotNull("user-set".takeIf { userSet }, "user-fixed".takeIf { userFixed })
        val clear = listOfNotNull("user-set".takeIf { !userSet }, "user-fixed".takeIf { !userFixed })
        if (set.isNotEmpty()) run("pm", "set-permission-flags", "--user", "0", id, permission, *set.toTypedArray())
        if (clear.isNotEmpty()) run("pm", "clear-permission-flags", "--user", "0", id, permission, *clear.toTypedArray())
        return ok
    }

    /** An app-op's mode for the app's uid and for the package ("default" where none is set), or null when this Android has no such op. */
    fun appOp(id: String, op: String): Pair<String, String>? {
        val said = run("appops", "get", id, op).second
        if (said.contains("Unknown operation") || said.contains("Error")) return null
        var uidMode = "default"
        var packageMode = "default"
        for (line in said.lineSequence()) {
            val text = line.trim()
            if (text.startsWith("Uid mode: $op:")) uidMode = mode(text.removePrefix("Uid mode: $op:"))
            else if (text.startsWith("$op:")) packageMode = mode(text.removePrefix("$op:"))
        }
        return uidMode to packageMode
    }

    private fun mode(text: String) = text.trim().substringBefore(';').trim()

    fun setAppOp(id: String, op: String, uidMode: String, packageMode: String): Boolean {
        val uid = run("appops", "set", "--uid", id, op, uidMode).first == 0
        return run("appops", "set", id, op, packageMode).first == 0 && uid
    }

    /** The apps exempt from battery optimization: by the user's choice, and by the system (which cannot be changed). */
    fun batteryExempt(): Pair<Set<String>, Set<String>> {
        val user = HashSet<String>()
        val system = HashSet<String>()
        for (line in run("cmd", "deviceidle", "whitelist").second.lineSequence()) {
            val parts = line.trim().split(',')
            if (parts.size < 2) continue
            when (parts[0]) {
                "user" -> user += parts[1]
                "system" -> system += parts[1]
            }
        }
        return user to system
    }

    fun setBatteryExempt(id: String, exempt: Boolean): Boolean =
        run("cmd", "deviceidle", "whitelist", (if (exempt) "+" else "-") + id).first == 0

    /** Android's network policy, for an app's uid policy bits (background data, Data Saver, LineageOS's network switches). */
    private val networkPolicy: Any? by lazy {
        try {
            context?.getSystemService("netpolicy")
        } catch (e: Throwable) {
            null
        }
    }

    /** A uid policy bit by its name in NetworkPolicyManager, or null when this system does not have it. */
    fun networkFlag(name: String): Int? = try {
        if (networkPolicy == null) null else Class.forName("android.net.NetworkPolicyManager").getField(name).getInt(null)
    } catch (e: Throwable) {
        null
    }

    fun uidPolicy(uid: Int): Int? = try {
        networkPolicy?.let { it.javaClass.getMethod("getUidPolicy", Int::class.javaPrimitiveType).invoke(it, uid) as Int }
    } catch (e: Throwable) {
        null
    }

    fun setUidPolicyFlag(uid: Int, flag: Int, on: Boolean): Boolean = try {
        val policy = networkPolicy ?: return false
        policy.javaClass.getMethod(if (on) "addUidPolicy" else "removeUidPolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(policy, uid, flag)
        true
    } catch (e: Throwable) {
        false
    }

    /** The app's languages as tags ("tr-TR,en-US"), empty when it follows the phone. */
    fun appLanguages(id: String): String = run("cmd", "locale", "get-app-locales", id, "--user", "0").second
        .substringAfter('[', "").substringBefore(']').split(',')
        .map { it.trim().replace('_', '-') }.filter { it.isNotEmpty() }.joinToString(",")

    fun setAppLanguages(id: String, tags: String): Boolean {
        val command = mutableListOf("cmd", "locale", "set-app-locales", id, "--user", "0")
        if (tags.isNotEmpty()) command += listOf("--locales", tags)
        return run(*command.toTypedArray()).first == 0
    }

    /** Whether the app opens the web links it supports, or null when it supports none. */
    fun opensLinks(id: String): Boolean? = Regex("""link handling allowed: (true|false)""", RegexOption.IGNORE_CASE)
        .find(run("pm", "get-app-links", "--user", "0", id).second)?.groupValues?.get(1)?.let { it == "true" }

    fun setOpensLinks(id: String, allowed: Boolean): Boolean =
        run("pm", "set-app-links-allowed", "--user", "0", "--package", id, allowed.toString()).first == 0

    fun roleHolders(role: String): List<String> = run("cmd", "role", "get-role-holders", "--user", "0", role).second
        .split(';', '\n').map { it.trim() }.filter { PACKAGE_NAME.matches(it) }

    /** Makes [id] the role's holder, or leaves the role with none when [id] is null. */
    fun setRoleHolder(role: String, id: String?): Boolean =
        if (id == null) run("cmd", "role", "clear-role-holders", "--user", "0", role).first == 0
        else run("cmd", "role", "add-role-holder", "--user", "0", role, id).first == 0

    /** The app's services for an intent action, as full component names (package/class). */
    fun services(id: String, action: String): List<String> =
        run("cmd", "package", "query-services", "--components", "--user", "0", "-a", action).second
            .lineSequence().map { it.trim() }.filter { it.startsWith("$id/") }.map(::fullComponent).toList()

    fun fullComponent(component: String): String {
        val name = component.substringBefore('/')
        val cls = component.substringAfter('/')
        return if (cls.startsWith(".")) "$name/$name$cls" else component
    }

    fun secureSetting(key: String): String = run("settings", "get", "secure", key).second.trim().let { if (it == "null") "" else it }

    fun putSecureSetting(key: String, value: String): Boolean = run("settings", "put", "secure", key, value).first == 0

    fun setNotificationListener(component: String, allowed: Boolean): Boolean =
        run("cmd", "notification", if (allowed) "allow_listener" else "disallow_listener", component).first == 0

    fun setEnabledState(id: String, state: Int): Boolean {
        val verb = when (state) {
            0 -> "default-state"
            1 -> "enable"
            2 -> "disable"
            3 -> "disable-user"
            else -> "disable-until-used"
        }
        return run("pm", verb, "--user", "0", id).first == 0
    }

    fun setSuspended(id: String, suspended: Boolean): Boolean =
        run("pm", if (suspended) "suspend" else "unsuspend", "--user", "0", id).first == 0

    /**
     * Makes a file written as root in an app's storage (/data/media/0/Android/data/<id>) the app's own, as if the
     * app had made it, so it can read and replace it: the app's uid, and the group, rw-rw---- and SELinux label of
     * [like], a file the app made there; without one, the folder's group and the app's SELinux categories (those
     * every app of today gets, levelFrom=all).
     */
    fun handOver(file: File, id: String, like: File?): Boolean {
        val uid = uid(id) ?: return false
        val folder = file.parentFile ?: return false
        return try {
            Os.chown(file.path, uid, Os.stat((like ?: folder).path).st_gid)
            Os.chmod(file.path, "660".toInt(8))
            val label = like?.let { selinuxLabel(it) }?.takeIf { it.startsWith("u:") } ?: appLabel(folder, uid)
            run("chcon", label, file.path).first == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun selinuxLabel(file: File) = run("stat", "-c", "%C", file.path).second.trim()

    private fun appLabel(folder: File, uid: Int): String {
        val type = selinuxLabel(folder).split(':').take(3).joinToString(":")
        val app = uid % 100000
        val user = uid / 100000
        return "$type:s0:c${app and 0xff},c${256 + (app shr 8 and 0xff)},c${512 + (user and 0xff)},c${768 + (user shr 8 and 0xff)}"
    }

    /**
     * Has Android's storage (vold, through the storage service) make an app's folder of the shared storage
     * (/storage/emulated/0/Android/data/<app>, .../media/<app>, .../obb/<app>) and what is in it as it makes them: its
     * owner, group, modes and storage-quota project, as Android's media provider has it done for files written past
     * FUSE. Where the storage is sdcardfs, vold leaves it (sdcardfs gives them by itself). False when it cannot be asked.
     */
    fun fixupAppDir(path: String): Boolean = try {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "mount") as android.os.IBinder
        val service = Class.forName("android.os.storage.IStorageManager\$Stub").getMethod("asInterface", android.os.IBinder::class.java).invoke(null, binder)
        service.javaClass.getMethod("fixupAppDir", String::class.java).invoke(service, path)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Lets an app off Android's stopped state, in which nothing starts it in the background (its jobs, syncs, alarms,
     * broadcasts) until it is opened: where force-stop and an install leave it. Android 15 and later keep a force-stopped
     * app there. False when it cannot be done here.
     */
    fun letOffStopped(id: String): Boolean = try {
        val packages = Class.forName("android.app.ActivityThread").getMethod("getPackageManager").invoke(null)
        packages.javaClass.getMethod("setPackageStoppedState", String::class.java, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .invoke(packages, id, false, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Ends the app's processes that are in the background (not as force-stop does: its jobs and alarms stay). */
    fun killInBackground(id: String) = run("am", "kill", id)

    /** How an app was used lately: whether it is on the screen now, and when it last left it (0: not lately). */
    class Use(val foreground: Boolean, val leftAt: Long)

    /** Each app's use since [since], from Android's usage stats; null when they cannot be read here. */
    fun usage(since: Long): Map<String, Use>? {
        val stats = context?.getSystemService(UsageStatsManager::class.java) ?: return null
        val events = try {
            stats.queryEvents(since, System.currentTimeMillis()) ?: return null
        } catch (e: Exception) {
            Out.line("(usage stats cannot be read here: ${e.javaClass.simpleName}; apps are backed up on their schedule)")
            return null
        }
        val last = HashMap<String, Pair<Boolean, Long>>()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> last[event.packageName] = true to (last[event.packageName]?.second ?: 0L)
                UsageEvents.Event.ACTIVITY_PAUSED, UsageEvents.Event.ACTIVITY_STOPPED -> last[event.packageName] = false to event.timeStamp
            }
        }
        return last.mapValues { (_, value) -> Use(value.first, value.second) }
    }

    /** The battery's level, in percent. */
    fun batteryLevel(): Int? = Regex("""level: (\d+)""").find(run("dumpsys", "battery").second)?.groupValues?.get(1)?.toIntOrNull()

    /** Whether the network Android uses now is unmetered (Wi-Fi, as a rule; not mobile data). */
    fun unmetered(): Boolean = try {
        val connectivity = context?.getSystemService(ConnectivityManager::class.java)
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
    } catch (e: Throwable) {
        false
    }

    /** Whether the phone is charging (or full, on a charger). */
    fun charging(): Boolean = Regex("""status: (\d+)""").find(run("dumpsys", "battery").second)?.groupValues?.get(1) in setOf("2", "5")

    // --- The phone's own settings.

    /** A settings table (system, secure, global) of the main user, as `settings list` gives it; a setting without a value is left out. */
    fun settings(table: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var last: String? = null
        for (line in run("settings", "--user", "0", "list", table).second.lineSequence()) {
            val setting = SETTING_LINE.matchEntire(line)
            if (setting != null) {
                last = setting.groupValues[1]
                values[last] = setting.groupValues[2]
            } else if (last != null && line.isNotEmpty()) {
                // A value with a line break in it.
                values[last] = values[last] + "\n" + line
            }
        }
        return values.filterValues { it != "null" }
    }

    /** Sets a setting of the main user, or deletes it ([value] null): Android then takes its own default. */
    fun putSetting(table: String, key: String, value: String?): Boolean =
        if (value == null) run("settings", "--user", "0", "delete", table, key).first == 0
        else run("settings", "--user", "0", "put", table, key, value).first == 0

    private val SETTING_LINE = Regex("([A-Za-z0-9_.:-]+)=(.*)")

    /** The display density set over the screen's own (wm density), or null when the screen has its own. */
    /**
     * The density set over the screen's own: what Android keeps of `wm density` (display_density_forced), else what wm
     * says is set over it. One UI keeps its screen resolution's own there (450 at FHD+, which wm shows as the screen's),
     * and that is what a reset must give back, not none.
     */
    fun density(): String? = secureSetting("display_density_forced").takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        ?: Regex("""Override density: (\d+)""").find(run("wm", "density").second)?.groupValues?.get(1)

    fun setDensity(dpi: String?): Boolean = run("wm", "density", dpi ?: "reset").first == 0

    /** The screen's own density, as wm says it (Physical density). */
    fun screenDensity(): String? = Regex("""Physical density: (\d+)""").find(run("wm", "density").second)?.groupValues?.get(1)

    /** The navigation modes this phone has (gestural, threebutton, twobutton: overlays of the system), and the one on. */
    fun navigationModes(): Map<String, Boolean> = run("cmd", "overlay", "list", "--user", "0", "android").second.lineSequence()
        .mapNotNull { NAVBAR_LINE.matchEntire(it.trim()) }
        .associate { it.groupValues[2] to (it.groupValues[1] == "x") }

    /**
     * The navigation mode on: its overlay of the system's (gestural, threebutton, twobutton), or "none" when the phone
     * has them but none is on, as One UI has it (its own navigation); null on a phone without them.
     */
    fun navigationMode(): String? = navigationModes().let { modes -> modes.entries.firstOrNull { it.value }?.key ?: if (modes.isEmpty()) null else "none" }

    /** Turns [mode]'s overlay on, and the others off; "none" turns them all off (the phone's own navigation again). */
    fun setNavigationMode(mode: String): Boolean = if (mode == "none") {
        navigationModes().filterValues { it }.keys.all { run("cmd", "overlay", "disable", "--user", "0", "com.android.internal.systemui.navbar.$it").first == 0 }
    } else {
        run("cmd", "overlay", "enable-exclusive", "--user", "0", "--category", "com.android.internal.systemui.navbar.$mode").first == 0
    }

    private val NAVBAR_LINE = Regex("""\[(.)] com\.android\.internal\.systemui\.navbar\.(gestural|threebutton|twobutton)""")

    /** The phone's languages in order, as language tags ("tr-TR,en-US"). */
    fun languages(): String = run("settings", "--user", "0", "get", "system", "system_locales").second.trim().let { if (it == "null") "" else it }

    /**
     * Sets the phone's languages, as Settings does: a new persistent configuration with these locales, set by the
     * user. Where that cannot be called, Android 16's `cmd locale` sets the first of them.
     */
    fun setLanguages(tags: String): Boolean {
        try {
            val config = Configuration()
            config.setLocales(LocaleList.forLanguageTags(tags))
            Configuration::class.java.getField("userSetLocale").setBoolean(config, true)
            val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)!!
            activityManager.javaClass.getMethod("updatePersistentConfiguration", Configuration::class.java).invoke(activityManager, config)
            return true
        } catch (e: Throwable) {
            return run("cmd", "locale", "set-device-locale", tags.substringBefore(',')).first == 0
        }
    }

    fun property(name: String): String = run("getprop", name).second.trim()

    /** Sets a system property; an empty value takes a persist. property out of what is kept over a restart. */
    fun setProperty(name: String, value: String): Boolean = run("setprop", name, value).first == 0

    fun timeZone(): String = property("persist.sys.timezone")

    fun setTimeZone(id: String): Boolean = run("cmd", "alarm", "set-timezone", id).first == 0

    /** Tells the clocks (the status bar's, the lock screen's) that the time format changed, as Settings does. */
    fun tellTimeFormat(twentyFour: Boolean) {
        run("am", "broadcast", "-a", "android.intent.action.TIME_SET", "-f", "0x01000000", "--ei", "android.intent.extra.TIME_PREF_24_HOUR_FORMAT", if (twentyFour) "1" else "0")
    }

    /** Whether Data Saver is on, or null when this phone does not say. */
    fun dataSaver(): Boolean? = Regex("""status: (enabled|disabled)""").find(run("cmd", "netpolicy", "get", "restrict-background").second)
        ?.groupValues?.get(1)?.let { it == "enabled" }

    fun setDataSaver(on: Boolean): Boolean = run("cmd", "netpolicy", "set", "restrict-background", on.toString()).first == 0

    /** Whether location is on for the main user, or null when this phone does not say. */
    fun location(): Boolean? = when (run("cmd", "location", "is-location-enabled", "--user", "0").second.trim()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    fun setLocation(on: Boolean): Boolean = run("cmd", "location", "set-location-enabled", on.toString(), "--user", "0").first == 0

    // --- LineageOS's own settings (content://lineagesettings), on ROMs based on LineageOS.

    /** Whether this ROM has LineageOS's settings. */
    val lineage: Boolean by lazy {
        !run("content", "query", "--uri", "content://lineagesettings/global", "--projection", "name").second.contains("Could not find provider")
    }

    /** A table of LineageOS's settings (system, secure, global); a setting without a value is left out. */
    fun lineageSettings(table: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        for (line in run("content", "query", "--uri", "content://lineagesettings/$table", "--projection", "name:value").second.lineSequence()) {
            val row = LINEAGE_ROW.matchEntire(line.trim()) ?: continue
            values[row.groupValues[1]] = row.groupValues[2]
        }
        return values.filterValues { it != "NULL" && it != "null" }
    }

    /**
     * Sets one of LineageOS's settings through its provider's own call (which checks the value). One is never deleted
     * ([value] null fails): LineageOS tells of a deletion with its whole table's address, which System UI and the
     * system server cannot take (on Android 16 both crash, and Android restarts).
     */
    fun putLineageSetting(table: String, key: String, value: String?): Boolean {
        if (value == null) return false
        val (status, said) = run("content", "call", "--uri", "content://lineagesettings", "--method", "PUT_$table", "--arg", key, "--extra", "value:s:$value")
        return status == 0 && !said.contains("Error") && !said.contains("Exception")
    }

    private val LINEAGE_ROW = Regex("""Row: \d+ name=(.*?), value=(.*)""")

    // --- The network.

    /**
     * The DNS servers of the networks Android uses for the internet, the default one first. Not a VPN's: Nixup runs as
     * root, and root's traffic does not go through a VPN app (its DNS server would not answer).
     */
    @Suppress("DEPRECATION")
    fun dnsServers(): List<String> = try {
        val connectivity = context?.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val default = connectivity.activeNetwork
        connectivity.allNetworks
            .filter { network ->
                connectivity.getNetworkCapabilities(network)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                } == true
            }
            .sortedBy { if (it == default) 0 else 1 }
            .flatMap { connectivity.getLinkProperties(it)?.dnsServers ?: emptyList() }
            .mapNotNull { it.hostAddress }
            .distinct()
    } catch (e: Throwable) {
        emptyList()
    }

    // --- The keyboard.

    /** The input methods (keyboards) the phone has, enabled or not. */
    fun inputMethods(): List<String> = run("ime", "list", "-a", "-s").second.lineSequence().map { it.trim() }.filter { it.contains('/') }.toList()

    /** Enables an input method and makes it the one to type with. */
    fun setKeyboard(id: String): Boolean = run("ime", "enable", id).first == 0 && run("ime", "set", id).first == 0

    // --- What the phone has, for the settings that need something.

    /** Android's own resources as this phone has them, with its maker's overlays (as Settings sees them). */
    private val frameworkResources: Resources? by lazy {
        try {
            packageManager?.getResourcesForApplication("android")
        } catch (e: Exception) {
            null
        }
    }

    private fun resource(name: String, type: String): Int? =
        frameworkResources?.getIdentifier(name, type, "android")?.takeIf { it != 0 }

    /** A config value of Android (config_…), or null when this Android has no such value. */
    fun configBool(name: String): Boolean? = resource(name, "bool")?.let { frameworkResources?.getBoolean(it) }

    fun configString(name: String): String? = resource(name, "string")?.let { frameworkResources?.getString(it) }

    fun configIntArray(name: String): IntArray? = resource(name, "array")?.let { frameworkResources?.getIntArray(it) }

    fun hasFeature(name: String): Boolean = packageManager?.hasSystemFeature(name) ?: false

    fun hasVibrator(): Boolean = try {
        context?.getSystemService(Vibrator::class.java)?.hasVibrator() ?: false
    } catch (e: Throwable) {
        false
    }

    fun hasSensor(type: Int): Boolean = try {
        (context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getDefaultSensor(type) != null
    } catch (e: Throwable) {
        false
    }

    /** The refresh rates the screen has. */
    fun refreshRates(): Set<Float> = try {
        (context?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)?.getDisplay(Display.DEFAULT_DISPLAY)
            ?.supportedModes?.map { it.refreshRate }?.toSet() ?: emptySet()
    } catch (e: Throwable) {
        emptySet()
    }

    fun hasService(name: String): Boolean = run("service", "check", name).second.contains(": found")

    // --- Apps that came with the phone.

    /** What Android knows of an app that may have come with the phone: installed for the main user, and core when Android needs it (a system user's app, or one that always runs). */
    class Preinstalled(val system: Boolean, val installed: Boolean, val core: String?)

    /** The app, also when it is uninstalled for the main user; null when the phone does not have it (or it cannot be read here). */
    fun preinstalled(id: String): Preinstalled? {
        val pm = packageManager ?: return null
        val info = try {
            pm.getApplicationInfo(id, PackageManager.MATCH_UNINSTALLED_PACKAGES)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        val core = when {
            info.uid % 100000 < 10000 -> "it runs as one of Android's own users"
            info.flags and ApplicationInfo.FLAG_PERSISTENT != 0 -> "it always runs"
            else -> null
        }
        return Preinstalled(info.flags and ApplicationInfo.FLAG_SYSTEM != 0, info.flags and ApplicationInfo.FLAG_INSTALLED != 0, core)
    }

    /** Uninstalls an app for the main user, keeping its data; one that came with the phone stays on it for install-existing. */
    fun uninstallForUser(id: String): Boolean =
        run("pm", "uninstall", "-k", "--user", "0", id).let { (status, said) -> status == 0 && said.contains("Success") }

    /** Installs again, for the main user, an app the phone still has (one that came with it), with the data it kept. */
    fun installExisting(id: String): Boolean =
        run("cmd", "package", "install-existing", "--user", "0", id).let { (status, said) -> status == 0 && said.contains("installed") }

    /** Runs one of Android's commands with no input (it would hand that to Android, which may not read /data/adb); its exit status and what it said. */
    fun run(vararg command: String): Pair<Int, String> {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        process.outputStream.close()
        val said = process.inputStream.bufferedReader().readText()
        return process.waitFor() to said
    }
}
