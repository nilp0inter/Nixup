package dev.nixup.manager.engine

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * The config's services: its systemd.services units (Nixup's own services, and NixOS's service modules, make them; see
 * nix/units.nix), run as systemd runs them on NixOS. Each runs in Nixup's view of the phone ([VIEW]): the phone as it
 * is, with /nix (Nixup's store, where the build fetched the units' packages), Nixup's /etc (see [Etc]), /var and /run
 * (the units' state, in /data/adb/nixup/var and /data/adb/nixup/run) and /tmp (one for all, its own with PrivateTmp); as root, or as its user
 * (serviceConfig.User, DynamicUser), through setpriv, with its folders its own ([prepareDirectories]). A simple unit
 * runs under a shell loop ([SUPERVISOR]) that starts it again as its Restart says; a oneshot runs to its end, and what
 * it says is shown. Like the settings, they are planned and checked first ([plan]) and changed after ([apply]): the
 * units that left the config or changed are stopped, then the ones to start start, in order (after, before, wants,
 * requires). At boot ([start]) the units the last build left start again; when the module goes ([removeAll]) they all
 * stop. A unit's state (/var/lib) stays when it leaves the config, as on NixOS; the module's removal takes it. Its timers
 * (systemd.timers, startAt) start a unit at their times ([Timers], [fire]); its sockets (systemd.sockets) are opened
 * for it and handed over as systemd hands them ([listen]).
 */
class Units(private val data: File) {
    private val dir = File(data, "units")
    private val view = File(dir, "view.sh")
    private val background = File(dir, "background.sh")
    private val varDir = File(data, "var")
    private val runDir = File(data, "run")
    /** The services of Nixup before units (its own Tailscale and Syncthing code), moved over at the next build. */
    private val legacy = File(data, "services")

    private var wanted: Map<String, JSONObject> = emptyMap()
    /** The units startAll could not start, the last time. */
    private var broken: List<String> = emptyList()
    /** At boot, and while a unit is tried again after it (NIXUP_RETRY): a failure is kept, not told, until the last try. */
    private var quietFailures = System.getenv("NIXUP_RETRY") == "1"
    private var toStop: List<String> = emptyList()
    private var toStart: List<String> = emptyList()
    private var leaving: List<String> = emptyList()
    /** Changed units that start so only with their next start (restartIfChanged = false): their files, written now. */
    private var toRefresh: List<String> = emptyList()
    /** Units nothing starts with the build (a timer's, or one to start by hand) that are new or changed: written, not started. */
    private var toWrite: List<String> = emptyList()
    private val timers = Timers(data)
    private val paths = Paths(data, ::pathOf)
    /** The units the timers and path units start, each with what starts it ("its timer foo"): they start then. */
    private var timerUnits: Map<String, List<String>> = emptyMap()

    /** Whether the plan changes anything. */
    val changes get() = toStop.isNotEmpty() || toStart.isNotEmpty() || leaving.isNotEmpty() || toRefresh.isNotEmpty() ||
        toWrite.isNotEmpty() || timers.changes || paths.changes || legacy.exists()

    /**
     * [secrets]: the files of the secrets the build changes; a unit that names one runs again, as with a changed unit.
     * [timerSpecs], [pathSpecs]: the profile's timers and path units (android.timers, android.paths).
     */
    fun plan(units: JSONObject?, problems: MutableList<String>, secrets: List<String> = emptyList(), timerSpecs: JSONObject? = null, pathSpecs: JSONObject? = null) {
        wanted = units?.let { json -> json.keys().asSequence().associateWith { json.getJSONObject(it) } } ?: emptyMap()
        timers.plan(timerSpecs, wanted.keys, problems)
        paths.plan(pathSpecs, wanted.keys, problems)
        timerUnits = startedBy(timers.unitsStarted(), paths.unitsStarted())
        val had = applied()
        val active = active(wanted)
        for ((name, unit) in wanted) {
            val env = environment(name, unit)
            for (step in steps(unit)) {
                val command = step.optString("command").ifEmpty { null } ?: continue
                runCatching { parse(name, unit, command).words(env) }.onFailure { problems += "systemd.services.$name: ${it.message}" }
            }
        }
        leaving = had.keys.filter { it !in wanted }
        // Changed: its unit, or the scripts the engine makes of it (as NixOS restarts a unit whose unit file changed).
        // Or a secret file it reads (a new auth key): the unit is the same, what it reads is not.
        // Or Nixup's view itself (a newer Nixup's, with another /home, ...): a unit running in the one before starts
        // again in the new one (a oneshot that ran is left).
        val viewChanged = view.isFile && runCatching { view.readText() }.getOrNull() != viewText()
        val edited = wanted.keys.filter { name ->
            had[name]?.let { it.toString() != wanted.getValue(name).toString() || !written(name, wanted.getValue(name)) } == true ||
                (had[name] != null && reads(wanted.getValue(name), secrets)) ||
                (viewChanged && had[name] != null && !oneshot(wanted.getValue(name)) && running(name))
        }
        // One that says so (restartIfChanged = false) keeps running as it is: its next start is the new one.
        toRefresh = edited.filter { name -> !wanted.getValue(name).optBoolean("restartIfChanged", true) && running(name) }
        val changed = edited - toRefresh.toSet()
        val start = mutableListOf<String>()
        for (name in active) {
            val unit = wanted.getValue(name)
            when {
                had[name] == null || name in changed -> start += name
                name in toRefresh -> {}
                oneshot(unit) -> if (status(name) != OK && !waiting(name)) start += name
                !running(name) && !waiting(name) -> start += name
            }
        }
        // A oneshot runs again after a unit it wants (or requires) starts, as its settings may be that unit's.
        do {
            val more = active.filter { name -> name !in start && oneshot(wanted.getValue(name)) && needs(wanted.getValue(name)).any { it in start } }
            start += more
        } while (more.isNotEmpty())
        toStart = order(start, wanted)
        // A oneshot that stays active after its run (RemainAfterExit) is stopped too, by its ExecStop. A unit its timer
        // started is left running, unless it changed.
        toStop = order((had.keys.filter { it in wanted && it !in active && it !in timerUnits } + changed)
            .filter { supervisorPid(it) != null || remains(it, had[it]) }.distinct(), had).reversed()
        toWrite = wanted.keys.filter { it !in active && (had[it] == null || it in edited) }
        report(had, active, changed)
        timers.report()
        paths.report()
    }

    /** The units timers and path units start, each with what starts it. */
    private fun startedBy(byTimers: Map<String, List<String>>, byPaths: Map<String, List<String>>): Map<String, List<String>> =
        (byTimers.keys + byPaths.keys).associateWith { unit ->
            byTimers[unit].orEmpty().map { "its timer $it" } + byPaths[unit].orEmpty().map { "its path unit $it" }
        }

    /** Whether [unit] names one of [files] (JSON writes / as \/). */
    private fun reads(unit: JSONObject, files: List<String>): Boolean {
        if (files.isEmpty()) return false
        val text = unit.toString().replace("\\/", "/")
        return files.any { text.contains(it) }
    }

    private fun report(had: Map<String, JSONObject>, active: Set<String>, changed: List<String>) {
        if (wanted.isEmpty() && had.isEmpty() && !legacy.exists()) return
        Out.line("Services:")
        if (legacy.exists()) Out.line("  the ones of the earlier Nixup: to move over to units (Tailscale keeps its login)")
        for (name in (wanted.keys + had.keys).sorted()) {
            val unit = wanted[name] ?: had.getValue(name)
            val once = oneshot(unit)
            val what = when {
                name in leaving -> if (supervisorPid(name) != null) "to stop (it left the config)" else "to remove (it left the config)"
                name !in active && name in timerUnits -> "started by ${timerUnits.getValue(name).joinToString(", ")}" + when {
                    name in toStop -> "; to stop now (changed)"
                    !once && running(name) -> "; running"
                    else -> ""
                }
                name !in active -> if (supervisorPid(name) != null) "to stop (nothing wants it now)" else "not started (nothing wants it)"
                name in toStart && had[name] == null -> if (once) "to run" else "to start"
                name in toStart && name in changed -> if (once) "to run again (changed)" else "to start again (changed)"
                name in toStart -> if (once) "to run again" else if (supervisorPid(name) != null) "to start again (it keeps stopping)" else "to start (it was not running)"
                name in toRefresh -> "changed: the change comes with its next start (restartIfChanged = false)"
                waiting(name) -> "waiting for its storage (${mounts(unit).firstOrNull { !onStorage(it) } ?: "it is there now"})"
                once && status(name) == SKIPPED -> "not run (its condition is not met)"
                once -> "done"
                else -> "running"
            }
            Out.line("  $name: $what")
        }
    }

    /**
     * Carries the plan out, after [prepare] wrote the view, /etc and tmpfiles' folders for [android] (the profile's
     * android part), and the activation scripts ran (see Activation: written again here, /etc would lose what they
     * wrote in it); the number of units that did not start.
     */
    @Suppress("UNUSED_PARAMETER")
    fun apply(android: JSONObject? = null): Int {
        moveLegacy()
        if (toStart.isEmpty() && toStop.isEmpty() && leaving.isEmpty() && toRefresh.isEmpty() && toWrite.isEmpty() && !timers.changes && !paths.changes) return 0
        toStop.forEach { stop(it) }
        for (name in leaving) {
            stop(name)
            cleanUp(name)
            deleteTree(File(dir, name))
            Out.line("✔ $name: removed (it left the config)")
        }
        for (name in toRefresh) {
            val unit = wanted.getValue(name)
            val unitDir = File(dir, name)
            writeScripts(name, unit, unitDir)
            File(unitDir, "spec.json").writeText(unit.toString())
            Out.line("✔ $name: changed; the change comes with its next start")
        }
        // What nothing starts now is there for its timer, or a start by hand.
        for (name in toWrite) {
            val unit = wanted.getValue(name)
            val unitDir = File(dir, name).apply { mkdirs() }
            writeScripts(name, unit, unitDir)
            File(unitDir, "spec.json").writeText(unit.toString())
        }
        val failed = startAll(toStart, wanted)
        // The timers and path units last, as the units they start are there now.
        return failed + timers.apply() + paths.apply()
    }

    /**
     * At boot: the activation scripts of the profile in place (system.activationScripts, as NixOS runs them at each
     * start: see Activation), then the units the last build left, started again in order.
     */
    fun start(): Int {
        if (legacy.exists()) Out.line("✘ The services of the earlier Nixup start again with the next build, as units")
        val had = applied()
        val android = current()
        val activation = Activation(data)
        if (had.isEmpty() && !activation.hasScript(android)) {
            // The jobs' timers (system.autoUpgrade, ...) start again without services too.
            timers.start(emptySet())
            return 0
        }
        // /run is empty after a restart, as on Linux.
        deleteTree(runDir)
        writeView()
        Etc.write(data)
        Tmpfiles.apply(data, android?.optJSONArray("tmpfiles"))
        // With /run fresh and /etc in place, before any service starts, as on NixOS.
        activation.boot(android)
        if (had.isEmpty()) {
            timers.start(emptySet())
            return 0
        }
        val names = order(active(had), had)
        quietFailures = true
        val count = startAll(names, had)
        // In the journal (the Logs tab); one that did not start is tried again, and told about if it never does.
        Notify.event(
            "service", if (count == 0) "ok" else "warn",
            if (count == 0) "Services started at boot" else "$count of ${names.size} services did not start at boot: tried again in a while",
            if (count == 0) names.joinToString(", ") else broken.joinToString(", "), quiet = true,
        )
        broken.forEach { retryLater(it) }
        watchNetwork()
        timers.start(had.keys)
        paths.start(had.keys)
        return count
    }

    /**
     * A unit that did not start at boot, tried again after 30 seconds, a minute and a half and 4 minutes: what it needs
     * may come only after the boot (an SD card that mounts later, the network), as systemd's units wait for theirs.
     * Told about when the last try fails too.
     */
    private fun retryLater(name: String) {
        val module = Notify.module ?: return
        val script = "for wait in 30 90 240; do sleep \$wait; sh \"\$1/units.sh\" boot start \"\$2\" > /dev/null 2>&1 && exit 0; done; " +
            "sh \"\$1/notify.sh\" service error \"\$2 did not start\" \"It failed at boot and 3 times after; its log: /data/adb/nixup/units/\$2/log\" \"service:\$2\""
        runCatching {
            ProcessBuilder(busybox(), "setsid", "sh", "-c", script, "nixup-retry", module.path, name)
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { environment()["NIXUP_RETRY"] = "1"; environment().remove("NIXUP_RUN") }.start()
        }
    }

    /** The module is being removed: every unit stops, and what they kept goes with the module's data. */
    fun removeAll(): Int {
        moveLegacy()
        unwatchNetwork()
        timers.removeAll()
        paths.removeAll()
        val had = applied()
        order(had.keys, had).reversed().forEach { stop(it) }
        deleteTree(dir)
        deleteTree(runDir)
        if (had.isNotEmpty()) Out.line("✔ Services: stopped")
        return 0
    }

    /**
     * Each unit the last build left, and how it is, as systemctl says: a simple one running, restarting (its loop
     * waits to start it again) or stopped; a oneshot done, failed, or not run (nothing wants it, or its condition is not
     * met). Also since when (its start, or its run), and its log.
     */
    fun list(): JSONObject {
        val had = applied()
        val active = active(had)
        val started = timers.unitsStartedNow()
        val byPaths = paths.unitsStartedNow()
        val units = JSONArray()
        for (name in had.keys.sorted()) {
            val unit = had.getValue(name)
            val once = oneshot(unit)
            val state = when {
                waiting(name) -> "waiting"
                once -> when (status(name)) { OK -> "done"; SKIPPED -> "skipped"; "" -> "not run"; WAITING -> "waiting"; else -> "failed" }
                running(name) && File(dir, "$name/listening").isFile -> "listening"
                running(name) -> "running"
                supervisorPid(name) != null -> "restarting"
                File(dir, "$name/skipped").isFile -> "skipped"
                else -> "stopped"
            }
            val since = File(dir, if (once) "$name/status" else if (state == "waiting") "$name/waiting" else "$name/main.pid").lastModified()
            val entry = JSONObject().put("name", name).put("description", unit.optString("description"))
                .put("type", if (once) "oneshot" else "simple").put("state", state).put("wanted", name in active)
                .put("since", since).put("log", File(dir, "$name/log").path)
            // Started by a timer: which, and when next.
            started[name]?.let { names ->
                entry.put("timer", names.joinToString(", "))
                names.mapNotNull { timers.nextTime(it) }.minOrNull()?.let { entry.put("next", it) }
            }
            byPaths[name]?.let { entry.put("path", it.joinToString(", ")) }
            units.put(entry)
        }
        return JSONObject().put("units", units).put("timers", timers.list()).put("paths", paths.list())
    }

    /**
     * Timer [timer]'s time (see Timers): its unit starts, as systemd's timer starts it, once a build running now has
     * ended (it changes the units); a simple unit running already is left as it is. A timer with a job runs it instead.
     */
    fun fire(timer: String): Int {
        val spec = timers.spec(timer) ?: throw Stop("there is no timer $timer in the last build", 2)
        spec.optJSONObject("job")?.let { return runJob(timer, it) }
        return fireUnit(spec.optString("unit"))
    }

    /**
     * A job's time (system.autoUpgrade's nixos-upgrade: see Timers): one of the module's scripts runs (build.sh
     * update, ...), on its own, as root, so the timer's loop goes on while it does; once a build running now has
     * ended. When the phone's conditions for it do not hold (charging, an unmetered
     * network, the battery not low), this time is skipped, as systemd skips a unit whose condition is not met, and the
     * next one is waited for.
     */
    private fun runJob(timer: String, job: JSONObject): Int {
        val what = job.optString("what").ifEmpty { timer }
        waitForBuild()
        val charging = Device.charging()
        val why = when {
            job.optBoolean("charging") && !charging -> "the phone is not charging"
            job.optBoolean("unmetered") && !Device.unmetered() -> "the phone is not on an unmetered network (Wi-Fi)"
            !charging && (Device.batteryLevel() ?: 100) < LOW_BATTERY -> "its battery is low"
            else -> null
        }
        if (why != null) {
            Out.line("$timer: $what skipped this time: $why")
            return 0
        }
        val module = Notify.module ?: throw Stop("$timer: the module's folder is not known (the engine runs from it), so $what cannot start", 1)
        val script = File(module, job.optString("script"))
        if (job.optString("script").let { it.isEmpty() || it.contains('/') } || !script.isFile) throw Stop("$timer: the module has no script ${job.optString("script")}", 1)
        val launcher = busybox()?.let { listOf(it, "setsid") } ?: listOf("/system/bin/setsid")
        val command = launcher + listOf("sh", script.path, "timer") + job.strings("args")
        return runCatching {
            ProcessBuilder(command)
                .directory(module).redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { listOf("NIXUP_RUN", "NIXUP_KIND", "NIXUP_QUIET", "NIXUP_SOURCE", "NIXUP_PROFILE").forEach { environment().remove(it) } }
                .start()
            Out.line("$timer: $what started (its run is in the Logs tab)")
            0
        }.getOrElse {
            Out.line("✘ $timer: $what did not start: ${it.message}")
            1
        }
    }

    /** Path unit [path]'s paths say so (see Paths): its unit starts, as with a timer's. */
    fun firePath(path: String): Int = fireUnit(paths.spec(path)?.optString("unit") ?: throw Stop("there is no path unit $path in the last build", 2))

    /**
     * A path unit's path [value] with its specifiers filled in as the unit [name] it starts has them (see [specifier]),
     * as systemd fills in a path unit's: a user's own unit's %h, %t, %S, ... are in its user's home and /run/user/<uid>.
     * Null when its user is not known yet.
     */
    private fun pathOf(name: String, value: String): String? {
        if ('%' !in value) return value
        val unit = runCatching { JSONObject(File(dir, "$name/spec.json").readText()) }.getOrDefault(JSONObject())
        val account = unit.string("user")?.let { Etc.accounts(data).users[it] ?: return null }
        val text = StringBuilder()
        for (part in specified(name, unit, value)) {
            when (part) {
                is Part.Text -> text.append(part.text)
                is Part.Variable -> text.append(
                    when (part.name) {
                        "HOME" -> account?.home
                        "nixup_uid" -> account?.uid?.toString()
                        "nixup_gid" -> account?.gid?.toString()
                        else -> null
                    } ?: return null,
                )
            }
        }
        return text.toString()
    }

    private fun fireUnit(name: String): Int {
        waitForBuild()
        val unit = applied()[name] ?: throw Stop("its unit $name is not in the last build", 2)
        if (waiting(name) || (!oneshot(unit) && running(name))) {
            Out.line("$name is running already")
            return 0
        }
        // As at boot: the view, and Nixup's /etc with the network's DNS servers of now.
        writeView()
        Etc.write(data)
        Tmpfiles.apply(data, current()?.optJSONArray("tmpfiles"))
        return if (start(name, unit)) 0 else 1
    }

    /** Waits while a build or update runs, for an hour at most. */
    private fun waitForBuild() {
        val until = System.currentTimeMillis() + 3_600_000
        while (System.currentTimeMillis() < until) {
            val free = runCatching {
                RandomAccessFile(File(data, "build.lock"), "rw").use { file -> file.channel.tryLock()?.also { it.release() } != null }
            }.getOrDefault(true)
            if (free) return
            Thread.sleep(5000)
        }
    }

    /**
     * systemctl's start, stop and restart of one unit of the last build, by hand: until the next build or restart,
     * which start the units as the config says. A oneshot starts by running again; it has nothing to stop.
     */
    fun control(action: String, name: String): Int {
        val had = applied()
        val unit = had[name] ?: throw Stop("there is no service $name in the last build (${had.keys.sorted().joinToString(", ").ifEmpty { "it has none" }})")
        val once = oneshot(unit)
        return when (action) {
            "stop" -> {
                when {
                    once && remains(name, unit) -> {
                        stop(name)
                        Out.line("✔ $name: stopped (until the next build or restart)")
                    }
                    once -> Out.line("$name runs once, to its end: there is nothing to stop")
                    supervisorPid(name) == null -> Out.line("$name is not running")
                    else -> {
                        stop(name)
                        Out.line("✔ $name: stopped (until the next build or restart)")
                        Notify.event("service", "info", "Stopped $name", "By hand, until the next build or restart", "service:$name", quiet = true)
                    }
                }
                0
            }
            "start", "restart" -> {
                if (action == "start" && !once && running(name)) {
                    Out.line("$name is running already")
                    return 0
                }
                // As at boot: the view, and Nixup's /etc with the network's DNS servers of now.
                writeView()
                Etc.write(data)
                Tmpfiles.apply(data, current()?.optJSONArray("tmpfiles"))
                val ok = start(name, unit)
                val done = if (once) "Ran $name" else if (action == "restart") "Restarted $name" else "Started $name"
                if (ok) Notify.event("service", "info", done, when {
                    System.getenv("NIXUP_WAITED") == "1" -> "Its storage is there now"
                    System.getenv("NIXUP_RETRY") == "1" -> "Tried again: it did not start at boot"
                    else -> "By hand"
                }, "service:$name", quiet = true)
                if (ok) 0 else 1
            }
            "retry" -> {
                retryLater(name)
                Out.line("$name: tried again after 30 seconds, a minute and a half and 4 minutes, until it starts")
                0
            }
            else -> throw Stop("no such thing to do with a service: $action (start, stop, restart, retry)")
        }
    }

    private fun startAll(names: List<String>, specs: Map<String, JSONObject>): Int {
        val failed = mutableSetOf<String>()
        for (name in names) {
            val unit = specs.getValue(name)
            val missing = unit.strings("requires").filter { it in failed }
            val ok = if (missing.isNotEmpty()) {
                Out.line("✘ $name: not started, as ${missing.joinToString(", ")} did not start")
                false
            } else {
                try {
                    start(name, unit)
                } catch (e: Exception) {
                    Out.line("✘ $name: ${e.message}")
                    false
                }
            }
            if (!ok) failed += name
        }
        broken = failed.toList()
        return failed.size
    }

    // --- One unit.

    private fun start(name: String, unit: JSONObject): Boolean {
        val unitDir = File(dir, name).apply { mkdirs() }
        if (supervisorPid(name) != null || remains(name, unit)) stop(name)
        // Started anew: a failure told before is the past, and so is a condition not met.
        File(unitDir, "failing").delete()
        File(unitDir, "skipped").delete()
        val accounts = Etc.accounts(data)
        val account = unit.string("user")?.let { user ->
            accounts.users[user] ?: run {
                Out.line("✘ $name: its user $user is not in Nixup's /etc/passwd (nix/users.nix makes it)")
                return false
            }
        }
        val gid = unit.string("group")?.let { accounts.groups[it] } ?: account?.gid
        prepareDirectories(name, unit, account?.uid, gid, account?.home)
        writeScripts(name, unit, unitDir)
        File(unitDir, "spec.json").writeText(unit.toString())
        // The storage it needs (unitConfig.RequiresMountsFor), one path a line, for its loop.
        val mounts = mounts(unit)
        File(unitDir, "mounts").let { if (mounts.isEmpty()) it.delete() else it.writeText(mounts.joinToString("") { path -> "$path\n" }) }
        return if (oneshot(unit)) runOnce(name, unitDir, unit) else launch(name, unit, unitDir)
    }

    /**
     * Its folders, as systemd makes them (StateDirectory in /var/lib, CacheDirectory in /var/cache, LogsDirectory in
     * /var/log, RuntimeDirectory in /run, ConfigurationDirectory in /etc; a user's own unit's in its home, [home], and
     * its /run/user/<uid>, as its systemd --user makes them), with their modes (0755 unless *Mode says), owned by its
     * user, and what is in them too when they were someone else's. RuntimeDirectory starts empty. A DynamicUser's state,
     * cache and logs are also there as /var/lib/private/<name>, where systemd keeps them (and some of NixOS's modules
     * look).
     */
    private fun prepareDirectories(name: String, unit: JSONObject, uid: Int?, gid: Int?, home: String?) {
        val modes = unit.optJSONObject("directoryModes")
        val mine = unit.optBoolean("userManager")
        for ((kind, key, base) in directories(unit, uid, home)) {
            val mode = modes?.string(kind)?.toIntOrNull(8) ?: "755".toInt(8)
            for (relative in unit.strings(key)) {
                val path = File(base, relative)
                if (kind == "Runtime") remove(path)
                // A user's own: the folders on the way that are not there yet are its own too (its /run/user/<uid> only
                // it may open, as systemd makes it; /run/user is root's).
                if (mine && kind == "Runtime") {
                    base.parentFile?.mkdirs()
                    makeOwned(base, base, uid, gid, "700".toInt(8))
                } else if (mine) {
                    path.parentFile?.let { makeOwned(Etc.outside(data, home ?: File(data, "home").path), it, uid, gid, "755".toInt(8)) }
                }
                path.mkdirs()
                runCatching {
                    // (A link there, as a user may make one in its own home, is not taken: chown and chmod would change
                    // what it points to.)
                    if (android.system.OsConstants.S_ISLNK(Os.lstat(path.path).st_mode)) throw IllegalStateException("it is a link, not a folder: left as it is")
                    if (uid != null) {
                        val stat = Os.lstat(path.path)
                        if (stat.st_uid != uid || stat.st_gid != (gid ?: uid)) {
                            Files.walk(path.toPath()).use { all -> all.forEach { Os.lchown(it.toString(), uid, gid ?: uid) } }
                        }
                    }
                    Os.chmod(path.path, mode)
                }.onFailure { Out.line("✘ $name: its folder ${path.path}: ${it.message}") }
                if (unit.optBoolean("dynamic") && kind in listOf("State", "Cache", "Logs")) privateLink(base, relative)
            }
        }
    }

    /** systemd's /var/lib/private/<name> (and the cache's and logs'), as a link to the folder itself. */
    private fun privateLink(base: File, relative: String) {
        val private = File(base, "private").apply { mkdirs() }
        runCatching { Os.chmod(private.path, "711".toInt(8)) }
        val link = File(private, relative)
        if (link.exists() || Files.isSymbolicLink(link.toPath())) return
        link.parentFile?.mkdirs()
        // Relative, so it leads there in the view and outside of it alike.
        runCatching { Os.symlink("../".repeat(relative.count { it == '/' } + 1) + relative, link.path) }
    }

    /**
     * [dir] and the folders on the way to it below [top] that are not there yet, made with [mode], [uid]'s (a user's own
     * unit's folders, in its home).
     */
    private fun makeOwned(top: File, dir: File, uid: Int?, gid: Int?, mode: Int) {
        if (dir.isDirectory) return
        dir.parentFile?.takeIf { it.path.startsWith(top.path + "/") }?.let { makeOwned(top, it, uid, gid, mode) }
        if (!dir.mkdir()) return
        runCatching {
            if (uid != null) Os.chown(dir.path, uid, gid ?: uid)
            Os.chmod(dir.path, mode)
        }
    }

    /**
     * The kinds of a unit's folders: its key in serviceConfig (a profile's list) and where they are; a user's own unit's
     * ([unit]'s userManager) in its [home] and its /run/user/<[uid]> (root's: /data/adb/nixup/home, /run/user/0).
     */
    private fun directories(unit: JSONObject? = null, uid: Int? = null, home: String? = null): List<Triple<String, String, File>> {
        if (unit?.optBoolean("userManager") != true) return listOf(
            Triple("State", "stateDirectories", File(varDir, "lib")),
            Triple("Cache", "cacheDirectories", File(varDir, "cache")),
            Triple("Logs", "logsDirectories", File(varDir, "log")),
            Triple("Runtime", "runtimeDirectories", runDir),
            Triple("Configuration", "configurationDirectories", File(data, "etc")),
        )
        val own = Etc.outside(data, home ?: File(data, "home").path)
        return listOf(
            Triple("State", "stateDirectories", File(own, ".local/state")),
            Triple("Cache", "cacheDirectories", File(own, ".cache")),
            Triple("Logs", "logsDirectories", File(own, ".local/state/log")),
            Triple("Runtime", "runtimeDirectories", File(runDir, "user/${uid ?: 0}")),
            Triple("Configuration", "configurationDirectories", File(own, ".config")),
        )
    }

    /** A simple (or forking) unit, under its loop; told about at once if it stops at once (a wrong command, a missing file). */
    private fun launch(name: String, unit: JSONObject, unitDir: File): Boolean {
        val busybox = busybox()
        val log = File(unitDir, "log")
        val devNull = File("/dev/null")
        // One loop for it, however many start it at once (its timer, a build, units.sh): the others find it there.
        RandomAccessFile(File(unitDir, ".launch.lock"), "rw").use { lockFile ->
            val lock = lockFile.channel.lock()
            try {
                if (supervisorPid(name) != null) {
                    Out.line("✔ $name: running")
                    return true
                }
                File(unitDir, "run.conf").writeText(runConf(unit, unitDir))
                File(unitDir, "ready").delete()
                File(unitDir, "unready").delete()
                // A loop that was killed leaves its program's pid here: a new one must not be taken for it.
                File(unitDir, "main.pid").delete()
                File(unitDir, "listening").delete()
                if (!background.isFile) writeView()
                // Below the apps on the screen (see BACKGROUND), its loop and so all it starts.
                ProcessBuilder(
                    busybox, "sh", background.path, busybox, nice(unit),
                    busybox, "setsid", "sh", "-c", SUPERVISOR, MARK + name, busybox, view.path, log.path, unitDir.path, name,
                ).directory(unitDir).redirectInput(devNull).redirectOutput(devNull).redirectError(devNull).apply { unitEnvironment(environment()) }.start()
                // Its loop's pid file comes a moment after it starts: until then another start would not see it.
                var tries = 0
                while (supervisorPid(name) == null && tries++ < 30) Thread.sleep(100)
            } finally {
                lock.release()
            }
        }
        // A forking one has started once its start has ended and its service is found; a notify one once it says so.
        if (unit.optString("type") == "forking" || unit.optBoolean("notify")) {
            // (Longer than its loop waits, so a start that took too long is seen ended, not running.)
            val until = System.currentTimeMillis() + 1000L * (timeout(unit, "timeoutStart", 90).coerceAtMost(120) + 15)
            while (System.currentTimeMillis() < until && !File(unitDir, "ready").isFile && !File(unitDir, "unready").isFile && supervisorPid(name) != null) Thread.sleep(300)
        }
        if (File(unitDir, "unready").isFile) {
            Out.line("✘ $name: it did not say it was ready (READY=1) in ${timeout(unit, "timeoutStart", 90).coerceAtMost(600)} s; its log: ${log.path}")
            if (notifies(unit)) Notify.event("service", "error", "$name did not start", "It did not say it was ready", "service:$name", quiet = quietFailures)
            return false
        }
        // The view is made on the phone's storage, which takes a moment (a slow eMMC): wait for its program's pid,
        // not just its loop's, before deciding it is not there.
        val startUntil = System.currentTimeMillis() + 1000L * timeout(unit, "timeoutStart", 90).coerceAtMost(120)
        while (System.currentTimeMillis() < startUntil && supervisorPid(name) != null && pid(File(unitDir, "main.pid")) == null) Thread.sleep(250)
        Thread.sleep(1500)
        // One started at its first connection: until its sockets are open and waiting.
        if (unit.optBoolean("lazy")) {
            var tries = 0
            while (!File(unitDir, "listening").isFile && supervisorPid(name) != null && tries++ < 20) Thread.sleep(250)
        }
        val main = pid(File(unitDir, "main.pid"))
        if (running(name) && main != null && alive(main)) {
            Out.line(if (File(unitDir, "listening").isFile) "✔ $name: listening (it starts at its first connection)" else "✔ $name: running")
            return true
        }
        if (waiting(name)) {
            Out.line("◌ $name: waiting for ${mounts(unit).firstOrNull { !onStorage(it) } ?: "its storage"} (${STORAGE_WHY}); it starts once that is there")
            return true
        }
        if (File(unitDir, "skipped").isFile) {
            Out.line("◌ $name: not started: ${runCatching { File(unitDir, "skipped").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "its condition is not met"}")
            return true
        }
        Out.line("✘ $name: it stopped at once; the end of its log (${log.path}):")
        val end = tail(log)
        end.forEach { Out.line("    $it") }
        if (notifies(unit)) Notify.event("service", "error", "$name did not start", end.lastOrNull { it.isNotBlank() }.orEmpty(), "service:$name", quiet = quietFailures)
        return false
    }

    /** What the loop of a unit does (see SUPERVISOR), as shell variables it reads. */
    private fun runConf(unit: JSONObject, unitDir: File): String {
        val optional = { file: String -> File(unitDir, file).takeIf { it.isFile }?.path.orEmpty() }
        // Its loop tells the app when it stops by itself, and when it runs again (unless its notifications are off).
        val notify = Notify.module?.takeIf { notifies(unit) }?.let { File(it, "notify.sh").path }.orEmpty()
        val values = linkedMapOf(
            "restart" to unit.optString("restart", "no"),
            "delay" to seconds(unit.optString("restartSec", "0.1")),
            "start" to File(unitDir, "start.sh").path,
            "post" to optional("post.sh"),
            "stop" to optional("stop.sh"),
            "stoppost" to optional("stoppost.sh"),
            "notify" to notify,
            "success" to unit.ints("successExitStatus").joinToString(" "),
            "force" to unit.ints("restartForceExitStatus").joinToString(" "),
            "prevent" to unit.ints("restartPreventExitStatus").joinToString(" "),
            "type" to unit.optString("type", "simple"),
            // Its PIDFile as it is outside the view, where the loop runs.
            "pidfile" to (unit.string("pidFile")?.let { Etc.outside(data, it).path } ?: ""),
            "killmode" to unit.optString("killMode", "control-group"),
            "killsignal" to signal(unit.optString("killSignal", "SIGTERM")),
            "sigkill" to if (unit.optBoolean("sendSigkill", true)) "1" else "",
            "timeout" to timeout(unit, "timeoutStop", 10).coerceAtMost(300).toString(),
            "timeout_start" to timeout(unit, "timeoutStart", 90).coerceAtMost(600).toString(),
            // Type = notify: what it tells its socket goes there (see notifyStart), and WatchdogSec. (Not a second
            // "notify": that is the app's notify.sh, above, which the loop tells the app with.)
            "notifylog" to if (unit.optBoolean("notify")) File(runDir, "nixup-notify/${notifyName(unitDir.name)}.log").path else "",
            "watchdog" to if (unit.optBoolean("notify")) unit.string("watchdog")?.let { seconds(it).toDouble().toInt().coerceAtLeast(1).toString() }.orEmpty() else "",
            // PrivateTmp: a /tmp of its own in its view (VIEW), not everyone's.
            "privatetmp" to if (unit.optBoolean("privateTmp")) "1" else "",
        )
        return "# The loop's settings for this unit (written by the engine; see Units.kt).\n" +
            values.entries.joinToString("") { (key, value) -> "$key=${quote(value)}\n" }
    }

    /** A time span of [unit] (TimeoutStopSec, ...) in whole seconds; [default] when it has none, and for "infinity". */
    private fun timeout(unit: JSONObject, key: String, default: Int): Int {
        val text = unit.string(key) ?: return default
        if (text == "infinity" || text == "0") return default * 10
        return seconds(text).toDouble().toInt().coerceAtLeast(1)
    }

    /**
     * What a unit's processes get besides their own environment: the module's folder (NIXUP_MODULE: its engine, for
     * services.snapclient's sound) and its notify.sh (NIXUP_NOTIFY: `sh "$NIXUP_NOTIFY" <kind> <level> <title>
     * [text]` tells the app something, as a notification). Not the run that started it: its own events are its own.
     */
    private fun unitEnvironment(env: MutableMap<String, String>) = cleanEnvironment(env)

    private fun notifies(unit: JSONObject) = unit.optBoolean("notifications", true)

    /** Its nice (serviceConfig.Nice; 10, below the apps, by default). */
    private fun nice(unit: JSONObject) = unit.optInt("nice", 10).coerceIn(-20, 19).toString()

    /**
     * A oneshot, to its end (or its TimeoutStartSec; 10 minutes by default); what it says is shown (and kept in its log).
     * One whose storage is not there yet runs once it is, from a waiter of its own: as a unit waiting, not a failure. One
     * whose condition is not met has not run, which is no failure either.
     */
    private fun runOnce(name: String, unitDir: File, unit: JSONObject): Boolean {
        val log = File(unitDir, "log")
        mounts(unit).firstOrNull { !onStorage(it) }?.let { missing ->
            File(unitDir, "status").writeText(WAITING)
            if (!waiting(name)) waitThenRun(name, unitDir)
            log.appendText("${Out.date(System.currentTimeMillis())} $name: waiting for $missing ($STORAGE_WHY)\n")
            Out.line("◌ $name: waiting for $missing ($STORAGE_WHY); it runs once that is there")
            return true
        }
        val seconds = timeout(unit, "timeoutStart", ONESHOT_MINUTES * 60).toLong()
        val (done, code) = inView(name, unit, File(unitDir, "start.sh"), log, seconds, show = true)
        val skipped = File(unitDir, "skipped").isFile
        val ok = done && (code == 0 || code in unit.ints("successExitStatus"))
        File(unitDir, "status").writeText(if (skipped) SKIPPED else if (ok) OK else "failed")
        // ExecStopPost, as the unit is done (one that stays active, RemainAfterExit, runs it when it stops).
        if (!unit.optBoolean("remainAfterExit")) File(unitDir, "stoppost.sh").takeIf { it.isFile }?.let { inView(name, unit, it, log, 600) }
        when {
            skipped -> Out.line("◌ $name: not run: ${runCatching { File(unitDir, "skipped").readText().trim() }.getOrNull()?.ifEmpty { null } ?: "its condition is not met"}")
            ok -> Out.line("✔ $name: done")
            else -> {
                val why = if (done) "failed ($code)" else if (seconds % 60 == 0L) "did not end in ${seconds / 60} minutes" else "did not end in $seconds seconds"
                Out.line("✘ $name: $why; its log: ${log.path}")
                if (notifies(unit)) Notify.event("service", "error", "$name $why", unit.optString("description"), "service:$name", quiet = quietFailures)
            }
        }
        return ok || skipped
    }

    /**
     * Runs [script] of [name] in the view, below the apps, its output in [log] (and shown, with [show]); whether it ended
     * within [seconds], and its exit status.
     */
    private fun inView(name: String, unit: JSONObject, script: File, log: File, seconds: Long, show: Boolean = false): Pair<Boolean, Int> {
        if (!background.isFile) writeView()
        // In a session of its own (its number, the group's, in .run.pid), so that a run that does not end is ended
        // whole: its shell and what it started.
        val group = File(script.parentFile, ".run.pid").apply { delete() }
        val process = ProcessBuilder(busybox(), "setsid", "/system/bin/sh", "-c", "echo \$\$ > \"\$0\"; exec \"\$@\"", group.path,
            busybox(), "sh", background.path, busybox(), nice(unit), busybox(), "sh", view.path, script.path)
            .directory(script.parentFile).redirectErrorStream(true).redirectInput(File("/dev/null"))
            .apply { unitEnvironment(environment()); environment()["NIXUP_PRIVATE_TMP"] = if (unit.optBoolean("privateTmp")) "1" else "" }.start()
        val reader = Thread {
            runCatching {
                log.appendText("${Out.date(System.currentTimeMillis())} $name\n")
                process.inputStream.bufferedReader().forEachLine { line ->
                    if (show) Out.line(line)
                    log.appendText(line + "\n")
                }
            }
        }.apply { start() }
        val done = process.waitFor(seconds, TimeUnit.SECONDS)
        if (!done) {
            runCatching { group.readText().trim().toInt() }.getOrNull()?.let { runCatching { Os.kill(-it, android.system.OsConstants.SIGKILL) } }
            process.destroyForcibly()
        }
        group.delete()
        reader.join(2000)
        return done to (if (done) process.exitValue() else -1)
    }

    /** Runs the oneshot [name] (units.sh start) once its storage (unitDir/mounts) is all there, from a process of its own. */
    private fun waitThenRun(name: String, unitDir: File) {
        val module = Notify.module ?: return
        val script = ON_STORAGE + "\n" + """
            echo ${'$'}${'$'} > "${'$'}2/waiter.pid"
            until all_there "${'$'}2/mounts"; do sleep 5; done
            rm -f "${'$'}2/waiter.pid"
            NIXUP_WAITED=1 exec sh "${'$'}1/units.sh" boot start "${'$'}3"
        """.trimIndent()
        runCatching {
            ProcessBuilder(busybox(), "setsid", "sh", "-c", script, "nixup-wait:$name", module.path, unitDir.path, name)
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { environment()["b"] = busybox(); environment().remove("NIXUP_RUN") }.start()
        }
    }

    /**
     * Stops a unit: a simple one's loop ends it as its KillMode, KillSignal and TimeoutStopSec say, after its ExecStop
     * and before its ExecStopPost; a oneshot that stays active (RemainAfterExit) runs its ExecStop and ExecStopPost. Its
     * runtime folders go, as systemd's do (unless RuntimeDirectoryPreserve says yes).
     */
    private fun stop(name: String) {
        val unitDir = File(dir, name)
        val spec = runCatching { JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull()
        // A oneshot's waiter for its storage, too.
        pid(File(unitDir, "waiter.pid"))?.takeIf { cmdline(it).contains("nixup-wait:$name") }?.let { Device.run("kill", "-TERM", "$it") }
        File(unitDir, "waiter.pid").delete()
        if (spec != null && remains(name, spec)) {
            val log = File(unitDir, "log")
            File(unitDir, "stop.sh").takeIf { it.isFile }?.let { inView(name, spec, it, log, 600) }
            File(unitDir, "stoppost.sh").takeIf { it.isFile }?.let { inView(name, spec, it, log, 600) }
            File(unitDir, "status").delete()
        }
        val supervisor = supervisorPid(name)
        if (supervisor != null) {
            Device.run("kill", "-TERM", "$supervisor")
            // Its loop waits for its ExecStop and then its processes (TimeoutStopSec each), and its ExecStopPost.
            val seconds = 10 + 2 * (spec?.let { timeout(it, "timeoutStop", 10).coerceAtMost(300) } ?: 10)
            var tries = 0
            while (supervisorPid(name) != null && tries++ < seconds * 5) Thread.sleep(200)
            supervisorPid(name)?.let { Device.run("kill", "-KILL", "$it") }
        }
        // (Only a process of its view: a main.pid its loop left may be another process's pid by now.)
        pid(File(unitDir, "main.pid"))?.takeIf { alive(it) && ofView(it, unitDir) }?.let { Device.run("kill", "-KILL", "$it") }
        // And the holder of its view's namespace (see scripts), should its loop not have ended it.
        pid(File(unitDir, "holder"))?.takeIf { alive(it) && ofView(it, unitDir) }?.let { Device.run("kill", "-KILL", "$it") }
        File(unitDir, "holder").delete()
        File(unitDir, "supervisor.pid").delete()
        File(unitDir, "main.pid").delete()
        if (spec?.optString("runtimeDirectoryPreserve") !in listOf("yes", "true", "1", "restart")) {
            // A user's own unit's are in its /run/user/<uid> (one whose user is gone keeps them).
            val base = when {
                spec == null || !spec.optBoolean("userManager") -> runDir
                spec.string("user") == null -> File(runDir, "user/0")
                else -> Etc.accounts(data).users[spec.getString("user")]?.uid?.let { File(runDir, "user/$it") }
            }
            if (base != null) spec?.strings("runtimeDirectories")?.forEach { remove(File(base, it)) }
        }
    }

    /**
     * What a unit runs once it leaves the config, as its last step (its cleanup.sh, as its user: Home Manager's unit of a
     * user takes the user's links out of its home), told and kept in its log, which goes with it.
     */
    private fun cleanUp(name: String) {
        val unitDir = File(dir, name)
        val script = File(unitDir, "cleanup.sh").takeIf { it.isFile } ?: return
        val spec = runCatching { JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull() ?: return
        // A user that left the config with it cannot run it (/etc/passwd is the new build's already): its home is left
        // as it is, as NixOS leaves a removed user's home.
        spec.string("user")?.let { user ->
            if (user !in Etc.accounts(data).users) {
                Out.line("$name: its user $user left the config too, so its home is left as it is")
                return
            }
        }
        val (done, code) = inView(name, spec, script, File(unitDir, "log"), 600, show = true)
        if (!done || code != 0) Out.line("✘ $name: what it does once it leaves the config did not end well (${if (done) code else "it took too long"})")
    }

    /** Whether [unit] is a oneshot that ran and stays active (RemainAfterExit), with something to stop it. */
    private fun remains(name: String, unit: JSONObject?): Boolean =
        unit != null && oneshot(unit) && unit.optBoolean("remainAfterExit") && status(name) == OK &&
            (File(dir, "$name/stop.sh").isFile || File(dir, "$name/stoppost.sh").isFile)

    /** Removes [file] and what is in it, a link without what it leads to. */
    private fun remove(file: File) = deleteTree(file)

    private fun writeScripts(name: String, unit: JSONObject, unitDir: File) {
        unitDir.listFiles().orEmpty().filter { it.name.endsWith(".sh") }.forEach { it.delete() }
        for ((file, text) in scripts(name, unit, unitDir)) {
            File(unitDir, file).writeText(text)
            // Run as a program by socat, for each connection (see listen).
            if (file == "conn.sh") runCatching { Os.chmod(File(unitDir, file).path, "700".toInt(8)) }
        }
    }

    /** Whether its scripts on the phone are the ones the engine makes of [unit] now. */
    private fun written(name: String, unit: JSONObject): Boolean {
        val unitDir = File(dir, name)
        val wantedFiles = scripts(name, unit, unitDir)
        val there = unitDir.listFiles().orEmpty().filter { it.name.endsWith(".sh") }.map { it.name }.toSet()
        return there == wantedFiles.keys && wantedFiles.all { (file, text) -> runCatching { File(unitDir, file).readText() == text }.getOrDefault(false) }
    }

    /**
     * Its scripts, by file name: start.sh (its conditions, ExecCondition, ExecStartPre, then ExecStart; a oneshot's
     * ExecStartPost too), post.sh, stop.sh (ExecStop), stoppost.sh (ExecStopPost) and cleanup.sh (what it runs once it
     * leaves the config: Home Manager's unit of a user takes its links out). Each begins as root, in the view
     * ([head]); its steps run as its user (through setpriv), but for those marked + or ! (and with PermissionsStartOnly,
     * all but its ExecStart), which run as root, as systemd runs them.
     */
    private fun scripts(name: String, unit: JSONObject, unitDir: File): Map<String, String> {
        val files = linkedMapOf<String, String>()
        val env = environment(name, unit)
        val shell = unit.optString("shell")
        val user = unit.string("user")
        val rootSteps = unit.optBoolean("rootSteps")
        // The fd its user reads a script from: after its sockets' (see listen).
        val fd = 3 + unit.strings("sockets").size
        var scripts = 0
        fun line(step: JSONObject, how: String, root: Boolean = false): String {
            val (ignore, wrapped) = if (step.has("script")) {
                val asUser = user != null && !root
                val file = "script-${scripts++}.sh"
                files[file] = step.getString("script")
                val args = step.string("args")?.let { parse(name, unit, "/ $it", arguments = true).shell(env, runtime(unit)).drop(1) }.orEmpty()
                // A user cannot read Nixup's folders: its script comes open, as its fd 3 (or the first after its sockets).
                false to if (asUser) "${setpriv(unit)} -- " + (listOf(quote(shell), "'-e'", "/dev/fd/$fd") + args).joinToString(" ") + " $fd< ${quote(File(unitDir, file).path)}"
                else (listOf(quote(shell), "'-e'", quote(File(unitDir, file).path)) + args).joinToString(" ")
            } else {
                val parsed = parse(name, unit, step.getString("command"))
                val all = parsed.shell(env, runtime(unit))
                // @: the program gets its second word as its argv[0], as systemd gives it (bash's exec -a does).
                val words = (if (parsed.argv0) listOf(quote(shell), "'-c'", quote("exec -a \"\$0\" \"\$@\""), all[1], all[0]) + all.drop(2) else all).joinToString(" ")
                parsed.ignore to if (user != null && !root && !parsed.privileged) "${setpriv(unit)} -- $words" else words
            }
            return when {
                how == "exec" -> listen(name, unit, unitDir, wrapped, files)
                how == "condition" -> "$wrapped || { c=${'$'}?; if [ ${'$'}c -ge 1 ] && [ ${'$'}c -le 254 ]; then echo \"$name: its ExecCondition said no (${'$'}c)\" > ${quote(File(unitDir, "skipped").path)}; exit 0; fi; exit ${'$'}c; }"
                ignore || how == "stop" -> "$wrapped || true"
                else -> "$wrapped || exit \$?"
            }
        }
        val pre = unit.objects("pre").map { line(it, "run", rootSteps) }
        val condition = unit.objects("condition").map { line(it, "condition", rootSteps) }
        val start = unit.objects("start")
        val post = unit.objects("post").map { line(it, "run", rootSteps) }
        val stop = unit.objects("stop").map { line(it, "stop", rootSteps) }
        val stopPost = unit.objects("stopPost").map { line(it, "stop", rootSteps) }
        val cleanup = unit.objects("cleanup").map { line(it, "stop", rootSteps) }
        val checks = checks(name, unit, unitDir)
        if (oneshot(unit)) {
            files["start.sh"] = head(name, unit, env) + checks + (condition + pre + start.map { line(it, "run") } + post).joinToString("") { "$it\n" }
        } else {
            // Its view's mount namespace, by which its loop knows its processes (KillMode).
            // And a process that keeps that namespace (and so its number, which the kernel gives the next one at once)
            // until its loop has ended what is left of it: else its loop could take a new view's processes for its own.
            val mark = "/system/bin/readlink /proc/self/ns/mnt > ${quote(File(unitDir, "ns").path)}\n" +
                "( /system/bin/sleep 2147483647 < /dev/null > /dev/null 2>&1 & echo ${'$'}! > ${quote(File(unitDir, "holder").path)} )\n"
            files["start.sh"] = head(name, unit, env) + checks + mark +
                (condition + pre + listOfNotNull(notifyStart(unit, unitDir)) + line(start.single(), "exec")).joinToString("") { "$it\n" }
            if (post.isNotEmpty()) files["post.sh"] = head(name, unit, env) + post.joinToString("") { "$it\n" }
        }
        if (stop.isNotEmpty()) files["stop.sh"] = head(name, unit, env) + stop.joinToString("") { "$it\n" }
        if (stopPost.isNotEmpty()) files["stoppost.sh"] = head(name, unit, env) + stopPost.joinToString("") { "$it\n" }
        if (cleanup.isNotEmpty()) files["cleanup.sh"] = head(name, unit, env) + cleanup.joinToString("") { "$it\n" }
        return files
    }

    /**
     * The start of a simple unit's program ([wrapped]: as its user, through setpriv), and of one with sockets
     * (systemd.sockets) first systemfd's, which opens them as root and hands them over as systemd does (as fds 3 and
     * on, with LISTEN_FDS and LISTEN_PID, and their names as LISTEN_FDNAMES): each execs the next, so the process stays
     * the one started. A unix socket's file is made anew, readable and writable by all as systemd makes it (the program
     * gets the unit's umask back), in a folder made with its DirectoryMode when it is not there; a user's own unit's
     * (userManager: its %t/gnupg/S.gpg-agent, ...) are its user's, as its systemd --user makes them. With
     * StandardInput = "socket" the first socket is its input too.
     */
    private fun listen(name: String, unit: JSONObject, unitDir: File, wrapped: String, files: MutableMap<String, String>): String {
        val specs = unit.strings("sockets")
        if (specs.isEmpty()) return "exec $wrapped"
        val env = environment(name, unit)
        // A socket's spec or path as a shell word, its specifiers filled in (one of its user's, when it runs).
        val word = { text: String -> parse(name, unit, "/ $text", arguments = true).shell(env, runtime(unit)).drop(1).joinToString(" ") }
        val owned = unit.optBoolean("userManager") && unit.string("user") != null
        val modes = unit.strings("socketDirectoryModes")
        val unixPaths = specs.withIndex().filter { it.value.startsWith("unix::") }.map { (i, spec) -> i to spec.removePrefix("unix::") }
        val prepare = unixPaths.joinToString("") { (i, path) ->
            val folder = word(path.substringBeforeLast('/', "/").ifEmpty { "/" })
            val mode = modes.getOrNull(i)?.takeIf { Regex("[0-7]{3,4}").matches(it) } ?: "0755"
            val own = if (owned) " && /system/bin/chown \"${'$'}nixup_uid:${'$'}nixup_gid\" $folder" else ""
            "{ [ -d $folder ] || { /system/bin/mkdir -p $folder$own && /system/bin/chmod $mode $folder; }; } && { [ ! -S ${word(path)} ] || /system/bin/rm -f ${word(path)}; } || exit 1\n"
        } + (if (owned && unixPaths.isNotEmpty()) "export nixup_uid nixup_gid\n" else "") +
            // Each socket's name, as systemd hands them over (its FileDescriptorName, else its socket unit's).
            unit.strings("socketNames").takeIf { it.size == specs.size && !unit.optBoolean("accept") && it.none { n -> ':' in n } }
                ?.let { "export LISTEN_FDNAMES=${quote(it.joinToString(":"))}\n" }.orEmpty()
        val program = if (unit.optBoolean("accept")) {
            // Accept = true: socat takes each connection (in a process of its own) and runs conn.sh with it as its
            // input and output, as systemd runs an instance for each: as StandardInput = "socket" has it, or as fd 3
            // (LISTEN_FDS=1) for one that takes its socket as systemd hands sockets over.
            // (3<&0 on the exec itself: mksh closes an fd that `exec 3<&0` alone opens when it runs a program.)
            // (Its user looked up again: socat runs it as a program of its own, without start.sh's shell variables.)
            files["conn.sh"] = "#!/system/bin/sh\n# A connection to $name (Accept = true), for its template's program (written by the engine; see Units.kt).\n" +
                lookup(name, unit) +
                if (unit.optBoolean("stdinSocket")) "exec 3<&-\nexec $wrapped\n" else "export LISTEN_FDS=1 LISTEN_PID=${'$'}${'$'}\nexec $wrapped 3<&0\n"
            "${quote(unit.getString("socat"))} 'ACCEPT-FD:3,fork' ${quote("EXEC:${File(unitDir, "conn.sh").path},nofork")}"
        } else {
            wrapped
        }
        val input = if (unit.optBoolean("stdinSocket") && !unit.optBoolean("accept")) " <&3" else ""
        // Started at its first connection: until one waits on a socket (its queue, in /proc/net), only the sockets are
        // there. A unix socket's queue does not show: one with any starts at once.
        val wait = if (unit.optBoolean("lazy") && specs.none { it.startsWith("unix::") }) {
            val fds = (3 until 3 + specs.size).joinToString(" ")
            val listening = quote(File(unitDir, "listening").path)
            ": > $listening; while :; do for f in $fds; do i=${'$'}(/system/bin/readlink /proc/${'$'}${'$'}/fd/${'$'}f | /system/bin/tr -dc 0-9); " +
                "${quote(busybox())} awk -v i=\"${'$'}i\" '${'$'}10 == i && ${'$'}5 !~ /:00000000${'$'}/ { f = 1 } END { exit !f }' /proc/net/tcp /proc/net/tcp6 /proc/net/udp /proc/net/udp6 2> /dev/null && break 2; " +
                "done; /system/bin/sleep 0.5; done; /system/bin/rm -f $listening; "
        } else {
            ""
        }
        // A user's own unix sockets become its own, once systemfd has made them (still as root).
        val chown = if (owned && unixPaths.isNotEmpty()) "/system/bin/chown \"\$nixup_uid:\$nixup_gid\" ${unixPaths.joinToString(" ") { word(it.second) }} || exit 1; unset nixup_uid nixup_gid; " else ""
        val restore = quote("umask \"\$1\"; shift; $chown${wait}exec \"\$@\"$input")
        return prepare + "nixup_umask=${'$'}(umask)\numask 0111\n" +
            "exec ${quote(unit.getString("systemfd"))} '-q' '-b' '4096' ${specs.joinToString(" ") { "'-s' ${word(it)}" }} '--' " +
            "/system/bin/sh -c $restore nixup-socket \"${'$'}nixup_umask\" $program"
    }

    /**
     * Type = notify: a socket of its own (NOTIFY_SOCKET, in /run/nixup-notify), where socat takes what it says
     * (READY=1, WATCHDOG=1, ...) and keeps it for its loop, which waits for READY=1 and, with WatchdogSec
     * (WATCHDOG_USEC), for a WATCHDOG=1 each time, as systemd does. Started in its view, so it ends with it.
     */
    private fun notifyStart(unit: JSONObject, unitDir: File): String? {
        if (!unit.optBoolean("notify")) return null
        val socket = "/run/nixup-notify/${notifyName(unitDir.name)}"
        val watchdog = unit.string("watchdog")?.let { (seconds(it).toDouble() * 1_000_000).toLong() }
        // The listener of its run before, if it still runs: only that process (its pid may be another's by now).
        val before = "p=${'$'}(/system/bin/cat ${quote("$socket.pid")} 2> /dev/null) && case \"${'$'}(/system/bin/cat /proc/${'$'}p/cmdline 2> /dev/null | /system/bin/tr '\\0' ' ')\" in " +
            "*${quote("UNIX-RECVFROM:$socket,")}*) kill ${'$'}p 2> /dev/null ;; esac"
        // (Its folder reachable by all, whatever the unit's UMask: a service user sends to its socket in it.)
        return "/system/bin/mkdir -p /run/nixup-notify && /system/bin/chmod 0755 /run/nixup-notify && { $before; /system/bin/rm -f ${quote(socket)}; }\n" +
            "${quote(unit.getString("socat"))} -u ${quote("UNIX-RECVFROM:$socket,fork")} ${quote("OPEN:$socket.log,creat,append")} < /dev/null > /dev/null 2>&1 &\n" +
            "echo ${'$'}! > ${quote("$socket.pid")}; i=0; while [ ! -S ${quote(socket)} ] && [ ${'$'}i -lt 50 ]; do /system/bin/sleep 0.1; i=${'$'}((i + 1)); done; /system/bin/chmod 0666 ${quote(socket)}\n" +
            "export NOTIFY_SOCKET=${quote(socket)}" + (watchdog?.let { " WATCHDOG_USEC=$it" } ?: "")
    }

    /** A unit's name as a file name of /run/nixup-notify (socat takes no : or , in it). */
    private fun notifyName(name: String) = name.replace(Regex("[^A-Za-z0-9_.@-]"), "_")

    /** setpriv's words for running a step as its user: its uid and gid, its groups, its ambient capabilities. */
    private fun setpriv(unit: JSONObject): String {
        val user = unit.getString("user")
        val words = mutableListOf(quote(unit.getString("setpriv")), quote("--reuid=$user"))
        // Its Group, else its user's own group (as /etc/passwd has it: looked up by the head).
        words += unit.string("group")?.let { quote("--regid=$it") } ?: "\"--regid=${'$'}nixup_gid\""
        words += "'--init-groups'"
        val caps = unit.strings("ambientCapabilities").filter { !it.startsWith("~") }
            .map { "+" + it.lowercase().removePrefix("cap_") }
        if (caps.isNotEmpty()) words += listOf(quote("--inh-caps=${caps.joinToString(",")}"), quote("--ambient-caps=${caps.joinToString(",")}"))
        if (unit.optBoolean("noNewPrivileges")) words += "'--no-new-privs'"
        return words.joinToString(" ")
    }

    /** Its user's (and Group's) uid, gid, home and shell, looked up in Nixup's /etc/passwd and /etc/group (nixup_*). */
    private fun lookup(name: String, unit: JSONObject): String = buildString {
        val user = unit.string("user") ?: return@buildString
        append("while IFS=: read -r nixup_name nixup_x nixup_uid nixup_gid nixup_gecos nixup_home nixup_shell; do [ \"${'$'}nixup_name\" = ${quote(user)} ] && break; done < /etc/passwd\n")
        append("[ \"${'$'}nixup_name\" = ${quote(user)} ] || { echo \"$name: its user $user is not in Nixup's /etc/passwd\" >&2; exit 1; }\n")
        unit.string("group")?.let { group ->
            append("while IFS=: read -r nixup_name nixup_x nixup_gid nixup_members; do [ \"${'$'}nixup_name\" = ${quote(group)} ] && break; done < /etc/group\n")
            append("[ \"${'$'}nixup_name\" = ${quote(group)} ] || { echo \"$name: its group $group is not in Nixup's /etc/group\" >&2; exit 1; }\n")
        }
    }

    /** Whether its commands take variables only known when it runs (its EnvironmentFile's, its user's HOME, ...). */
    private fun runtime(unit: JSONObject) = unit.has("environmentFiles") || unit.has("user")

    /**
     * What each of its scripts does first, as root in the view: its environment, its user (looked up in Nixup's
     * /etc/passwd: HOME, USER, ...), the shared storage for a user of Android's storage groups ([STORAGE]), BindPaths,
     * its WorkingDirectory, UMask, Limit*, OOMScoreAdjust, EnvironmentFile, UnsetEnvironment and credentials
     * (LoadCredential, SetCredential: in a folder of its own only it may read, in its view's memory).
     */
    private fun head(name: String, unit: JSONObject, env: Map<String, String>): String = buildString {
        append("# $name, as Nixup runs it (the config's systemd.services.$name): ${unit.optString("description")}\n")
        if (env.isNotEmpty()) append("export ").append(env.entries.joinToString(" ") { (key, value) -> "$key=${quote(value)}" }).append('\n')
        val user = unit.string("user")
        if (user != null) {
            append(lookup(name, unit))
            // (What its Environment sets stays, as systemd's does: NixOS's couchdb gives HOME its database's folder.)
            val own = listOf("USER" to quote(user), "LOGNAME" to quote(user), "HOME" to "\"${'$'}nixup_home\"", "SHELL" to "\"${'$'}nixup_shell\"")
                .filter { (key, _) -> key !in env && unit.optJSONObject("environment")?.has(key) != true }
            if (own.isNotEmpty()) append("export ").append(own.joinToString(" ") { (key, value) -> "$key=$value" }).append('\n')
        }
        // Root's home for a unit of root's, which systemd would leave unset: what Nixup's units always had (they got it
        // from the build that started them, with more that is now left out).
        if (user == null && "HOME" !in env && unit.optJSONObject("environment")?.has("HOME") != true) {
            append("export HOME=${quote(File(data, "home").path)}\n")
        }
        // Its variables whose specifiers are only known now (%h, %U, %G of its user).
        runtimeEnvironment(name, unit).takeIf { it.isNotEmpty() }?.let { vars ->
            append("export ").append(vars.entries.joinToString(" ") { (key, value) -> "$key=$value" }).append('\n')
        }
        // Busybox's mount (Android's toybox cannot remount a bind read-only).
        if (unit.has("binds") || unit.has("credentials") || unit.optBoolean("storage")) append("nixup_b=${quote(busybox())}\n")
        // The mounts first, as systemd makes them before it goes into the unit's WorkingDirectory.
        if (unit.optBoolean("storage")) append(STORAGE)
        unit.optJSONArray("binds")?.let { binds ->
            for (i in 0 until binds.length()) {
                val bind = binds.getJSONObject(i)
                val from = quote(bind.getString("from"))
                val to = quote(bind.getString("to"))
                val missing = if (bind.optBoolean("optional")) ":" else "{ echo \"$name: ${bind.getString("from")} is not there (BindPaths)\" >&2; exit 1; }"
                append("if [ -e $from ]; then if [ -d $from ]; then /system/bin/mkdir -p $to; else /system/bin/mkdir -p ${quote(File(bind.getString("to")).parent ?: "/")}; [ -e $to ] || : > $to; fi; ")
                append("\"${'$'}nixup_b\" mount --bind $from $to || exit 1")
                if (bind.optBoolean("readOnly")) append("; \"${'$'}nixup_b\" mount -o remount,bind,ro $to || exit 1")
                append("; else $missing; fi\n")
            }
        }
        val workingDirectory = unit.string("workingDirectory")
        val optional = workingDirectory?.startsWith("-") == true
        val directory = workingDirectory?.removePrefix("-")
        val cd = when (directory) {
            null -> quote("/")
            "~" -> if (user != null) "\"${'$'}HOME\"" else quote(File(data, "home").path)
            else -> quote(directory)
        }
        append(if (optional) "cd $cd 2> /dev/null || cd /\n" else "cd $cd || exit 1\n")
        if (runtime(unit)) append("set -f\n")
        unit.optJSONArray("environmentFiles")?.let { files ->
            append(ENVIRONMENT_FILE)
            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                append("nixup_environment ${quote(file.getString("path"))} ${if (file.optBoolean("optional")) "optional" else "needed"} || exit 1\n")
            }
        }
        unit.strings("unsetEnvironment").map { it.substringBefore('=') }.filter { NAME.matches(it) }.takeIf { it.isNotEmpty() }
            ?.let { append("unset ${it.joinToString(" ")}\n") }
        unit.optJSONArray("credentials")?.let { credentials ->
            val folder = quote(credentialsDirectory(name))
            append("/system/bin/mkdir -p $folder && /system/bin/chmod 0755 /run/credentials && \"${'$'}nixup_b\" mount -t tmpfs -o mode=0700,nosuid,nodev,noexec,size=4m nixup-credentials $folder || exit 1\n")
            for (i in 0 until credentials.length()) {
                val credential = credentials.getJSONObject(i)
                val id = credential.getString("id")
                val into = quote("${credentialsDirectory(name)}/$id")
                val tool = unit.string("credsTool")?.let { quote(it) }
                val undecrypted = "{ echo \"$name: its credential $id could not be decrypted (systemd-creds encrypt makes it, on this phone)\" >&2; exit 1; }"
                when {
                    credential.optBoolean("encrypted") && tool != null && credential.has("text") ->
                        append("printf '%s' ${quote(credential.getString("text"))} | $tool decrypt ${quote("--name=$id")} - $into || $undecrypted\n")
                    credential.optBoolean("encrypted") && tool != null && credential.string("path") != null ->
                        append("$tool decrypt ${quote("--name=$id")} ${quote(credential.getString("path"))} $into || $undecrypted\n")
                    credential.has("text") -> append("printf '%s' ${quote(credential.getString("text"))} > $into || exit 1\n")
                    credential.string("path") != null -> {
                        val from = quote(credential.getString("path"))
                        append("if [ -d $from ]; then ( set +f; for f in $from/*; do [ -f \"${'$'}f\" ] && /system/bin/cp \"${'$'}f\" ${quote("${credentialsDirectory(name)}/${id}_")}\"${'$'}{f##*/}\"; done ); ")
                        append("else /system/bin/cp $from $into || { echo \"$name: its credential $id (${credential.getString("path")}) could not be read\" >&2; exit 1; }; fi\n")
                    }
                    // An id alone: the file of that name in a credentials store, as systemd looks for it.
                    else -> append("for f in /etc/credstore/${quote(id)} /run/credstore/${quote(id)}; do [ -f \"${'$'}f\" ] && /system/bin/cp \"${'$'}f\" $into && break; done; [ -f $into ] || { echo \"$name: no credential $id in /etc/credstore or /run/credstore\" >&2; exit 1; }\n")
                }
            }
            append("/system/bin/chmod -R 0400 $folder && /system/bin/chmod 0500 $folder || exit 1\n")
            // Its user's (and its Group's, which the lookup above left in nixup_gid).
            if (user != null) append("/system/bin/chown -R \"${'$'}nixup_uid:${'$'}nixup_gid\" $folder || exit 1\n")
        }
        // What it runs with: after the root part above, so its umask is not that part's.
        unit.string("umask")?.let { append("umask ${quote(it)}\n") }
        unit.optJSONObject("limits")?.let { limits -> limits.keys().asSequence().sorted().forEach { key -> append(ulimit(key, limits.getString(key))) } }
        if (unit.has("oomScoreAdjust")) append("echo ${unit.optInt("oomScoreAdjust")} > /proc/self/oom_score_adj\n")
    }

    /** Its conditions (unitConfig's Condition and Assert keys), as shell tests: one not met ends it, as not run. */
    private fun checks(name: String, unit: JSONObject, unitDir: File): String {
        val list = unit.optJSONArray("checks") ?: return ""
        val skipped = quote(File(unitDir, "skipped").path)
        return buildString {
            var triggered = false
            for (i in 0 until list.length()) {
                val check = list.getJSONObject(i)
                val key = check.getString("key")
                var value = check.getString("value")
                val trigger = value.startsWith("|").also { if (it) value = value.drop(1) }
                val negate = value.startsWith("!").also { if (it) value = value.drop(1) }
                val kind = key.removePrefix("Condition").removePrefix("Assert")
                val path = quote(value)
                val test = when (kind) {
                    "PathExists" -> "[ -e $path ]"
                    "PathExistsGlob" -> "( set +f; for f in $value; do [ -e \"${'$'}f\" ] && exit 0; done; exit 1 )"
                    "PathIsDirectory" -> "[ -d $path ]"
                    "PathIsSymbolicLink" -> "[ -L $path ]"
                    "PathIsMountPoint" -> "/system/bin/mountpoint -q $path"
                    "PathIsReadWrite" -> "[ -w $path ]"
                    "DirectoryNotEmpty" -> "[ -d $path ] && [ -n \"${'$'}(/system/bin/ls -A $path)\" ]"
                    "FileNotEmpty" -> "[ -s $path ]"
                    "FileIsExecutable" -> "[ -f $path ] && [ -x $path ]"
                    // A phone runs on no machine of another's, on arm64.
                    "Virtualization" -> if (value in listOf("no", "false", "0")) "true" else "false"
                    "Architecture" -> if (value == "arm64") "true" else "false"
                    "KernelCommandLine" -> "/system/bin/grep -qw -- $path /proc/cmdline"
                    else -> "true"
                }
                val met = if (negate) "! { $test; }" else "{ $test; }"
                val said = "$key=${check.getString("value")}"
                when {
                    key.startsWith("Assert") -> append("$met || { echo \"$name: $said failed\" >&2; exit 1; }\n")
                    trigger -> {
                        if (!triggered) append("nixup_any=\n")
                        triggered = true
                        append("$met && nixup_any=1\n")
                    }
                    else -> append("$met || { echo \"$said is not met\" > $skipped; echo \"$name: $said is not met: not started\"; exit 0; }\n")
                }
            }
            if (triggered) append("[ -n \"${'$'}nixup_any\" ] || { echo \"none of its conditions is met\" > $skipped; echo \"$name: none of its conditions is met: not started\"; exit 0; }\n")
        }
    }

    /**
     * Its environment as the unit gives it, with systemd's STATE_DIRECTORY, RUNTIME_DIRECTORY, CACHE_DIRECTORY,
     * LOGS_DIRECTORY, CONFIGURATION_DIRECTORY (a user's own unit's in its home and its /run/user/<uid>, as specifiers)
     * and CREDENTIALS_DIRECTORY; its specifiers not filled in yet.
     */
    private fun unitVariables(name: String, unit: JSONObject): Map<String, String> {
        val env = linkedMapOf<String, String>()
        unit.optJSONObject("environment")?.let { json -> json.keys().forEach { env[it] = json.getString(it) } }
        val mine = unit.optBoolean("userManager")
        for ((variable, key, base) in listOf(
            Triple("STATE_DIRECTORY", "stateDirectories", if (mine) "%S" else "/var/lib"),
            Triple("RUNTIME_DIRECTORY", "runtimeDirectories", if (mine) "%t" else "/run"),
            Triple("CACHE_DIRECTORY", "cacheDirectories", if (mine) "%C" else "/var/cache"),
            Triple("LOGS_DIRECTORY", "logsDirectories", if (mine) "%L" else "/var/log"),
            Triple("CONFIGURATION_DIRECTORY", "configurationDirectories", if (mine) "%E" else "/etc"),
        )) {
            unit.strings(key).takeIf { it.isNotEmpty() }?.let { env[variable] = it.joinToString(":") { dir -> "$base/$dir" } }
        }
        if (unit.has("credentials")) env["CREDENTIALS_DIRECTORY"] = credentialsDirectory(name)
        return env
    }

    /** Its environment ([unitVariables]), with the specifiers in its values (%S, %N, %d, ...) filled in, as systemd does; those only known when it runs are runtimeEnvironment's. */
    private fun environment(name: String, unit: JSONObject): Map<String, String> =
        unitVariables(name, unit).mapValues { (_, value) -> specified(name, unit, value) }.filterValues { parts -> parts.none { it is Part.Variable } }
            .mapValues { (_, parts) -> parts.joinToString("") { (it as Part.Text).text } }

    /** Its variables with specifiers only known when it runs (its user's HOME, uid, gid), as shell words. */
    private fun runtimeEnvironment(name: String, unit: JSONObject): Map<String, String> {
        val env = unitVariables(name, unit)
        return env.mapValues { (_, value) -> specified(name, unit, value) }.filterValues { parts -> parts.any { it is Part.Variable } }
            .mapValues { (_, parts) ->
                "\"" + parts.joinToString("") { part ->
                    when (part) {
                        is Part.Text -> part.text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")
                        is Part.Variable -> "${'$'}{${part.name}}"
                    }
                } + "\""
            }
    }

    /**
     * [value] with its specifiers filled in: text, and the variables of those only known when it runs (a specifier's
     * $HOME, $nixup_uid: see [specifier]).
     */
    private fun specified(name: String, unit: JSONObject, value: String): List<Part> {
        if ('%' !in value) return listOf(Part.Text(value))
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 1 < value.length) {
                val letter = value[i + 1]
                // One not on the phone stays as it is.
                val filled = runCatching { specifier(name, unit, letter) }.getOrElse { "%$letter" }
                var at = 0
                for (match in SPECIFIED_VARIABLE.findAll(filled)) {
                    text.append(filled, at, match.range.first)
                    if (text.isNotEmpty()) parts += Part.Text(text.toString()).also { text.clear() }
                    parts += Part.Variable(match.groupValues[1], split = false)
                    at = match.range.last + 1
                }
                text.append(filled, at, filled.length)
                i += 2
            } else {
                text.append(c)
                i++
            }
        }
        if (text.isNotEmpty() || parts.isEmpty()) parts += Part.Text(text.toString())
        return parts
    }

    private fun credentialsDirectory(name: String) = "/run/credentials/$name.service"

    /** A Limit* as the shell's ulimit sets it ("soft:hard" both); one it has not (RTTIME), left. */
    private fun ulimit(key: String, value: String): String {
        val (flag, unit) = LIMITS[key] ?: return ""
        fun amount(text: String): String {
            if (text == "infinity") return "unlimited"
            if (unit == 1L) return text.toLongOrNull()?.toString() ?: text
            val match = Regex("([0-9]+)([KMGTP]?)").matchEntire(text.trim()) ?: return text
            val factor = when (match.groupValues[2]) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; "T" -> 1L shl 40; "P" -> 1L shl 50; else -> 1L }
            return (match.groupValues[1].toLong() * factor / unit).toString()
        }
        val soft = amount(value.substringBefore(':'))
        val hard = amount(value.substringAfter(':', value.substringBefore(':')))
        return "ulimit -H $flag $hard 2> /dev/null; ulimit -S $flag $soft 2> /dev/null\n"
    }

    // --- What is there.

    /** The units the last build left, by name. */
    private fun applied(): Map<String, JSONObject> = dir.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { unitDir ->
        runCatching { unitDir.name to JSONObject(File(unitDir, "spec.json").readText()) }.getOrNull()
    }.toMap()

    /** The android part of the profile in place, if there is one. */
    private fun current(): JSONObject? = runCatching { JSONObject(File(data, "profile.json").readText()).optJSONObject("android") }.getOrNull()

    /** Whether a simple unit runs: its loop, and its program (a loop between failed starts is not running). */
    private fun running(name: String) = supervisorPid(name) != null && pid(File(dir, "$name/main.pid"))?.let { alive(it) } == true

    private fun status(name: String) = runCatching { File(dir, "$name/status").readText().trim() }.getOrDefault("")

    /** Whether it waits for its storage (unitConfig.RequiresMountsFor): a simple unit's loop, or a oneshot's waiter. */
    private fun waiting(name: String): Boolean {
        val unitDir = File(dir, name)
        return if (File(unitDir, "waiting").isFile) supervisorPid(name) != null
        else status(name) == WAITING && pid(File(unitDir, "waiter.pid"))?.let { cmdline(it).contains("nixup-wait:$name") } == true
    }

    /** The phone's storage it needs (unitConfig.RequiresMountsFor). */
    private fun mounts(unit: JSONObject) = unit.strings("mounts")

    /**
     * Whether [path] is on the phone's storage now: the nearest part of it that is there is not RAM, as the bare folder
     * an SD card (or the storage Android mounts once the phone is first unlocked) is mounted on is.
     */
    private fun onStorage(path: String): Boolean {
        var there = File(path)
        while (!there.exists()) there = there.parentFile ?: return false
        val type = Device.run("stat", "-f", "-c", "%T", there.path)
        return type.first == 0 && type.second.trim().let { it.isNotEmpty() && it != "tmpfs" }
    }

    /**
     * Its loop's process, while it runs: its working folder is the unit's, which /proc keeps no matter how long
     * the loop's script is (Android 9's cmdline stops at 4 KiB, before the mark its arguments carry).
     */
    private fun supervisorPid(name: String): Int? {
        val folder = File(dir, name)
        return pid(File(folder, "supervisor.pid"))?.takeIf { alive(it) && cwd(it) == folder.canonicalPath }
    }

    private fun cwd(pid: Int) = runCatching { File("/proc/$pid/cwd").canonicalPath }.getOrNull()

    private fun pid(file: File): Int? = runCatching { file.readText().trim().toInt() }.getOrNull()

    private fun alive(pid: Int) = File("/proc/$pid").exists()

    /** Whether process [pid] runs in the view of the unit of [unitDir] (its mount namespace, which the unit's ns names). */
    private fun ofView(pid: Int, unitDir: File): Boolean {
        val ns = runCatching { File(unitDir, "ns").readText().filter { it.isDigit() }.toLong() }.getOrNull() ?: return false
        return runCatching { Os.stat("/proc/$pid/ns/mnt").st_ino == ns }.getOrDefault(false)
    }

    private fun cmdline(pid: Int) = runCatching { File("/proc/$pid/cmdline").readText().replace('\u0000', ' ') }.getOrDefault("")

    private fun tail(file: File) = runCatching { file.readLines().takeLast(8) }.getOrDefault(emptyList())

    /** The units to run: those wanted by multi-user.target, and those they want or require, and so on. */
    private fun active(specs: Map<String, JSONObject>): Set<String> {
        val active = specs.filterValues { it.optBoolean("wanted") }.keys.toMutableSet()
        do {
            val more = active.flatMap { needs(specs.getValue(it)) }.filter { it in specs && it !in active }
            active += more
        } while (more.isNotEmpty())
        return active
    }

    private fun needs(unit: JSONObject) = (unit.strings("wants") + unit.strings("requires")).distinct()

    /** [names] in the order they start: each after the ones it is after, and before the ones it is before. */
    private fun order(names: Collection<String>, specs: Map<String, JSONObject>): List<String> {
        val set = names.toSet()
        val first = set.associateWith { mutableSetOf<String>() }
        for (name in set) {
            val unit = specs[name] ?: continue
            unit.strings("after").filter { it in set }.forEach { first.getValue(name) += it }
            unit.strings("before").filter { it in set }.forEach { first.getValue(it) += name }
        }
        val done = linkedSetOf<String>()
        val visiting = mutableSetOf<String>()
        fun visit(name: String) {
            // A cycle is broken where it closes, as systemd breaks one.
            if (name in done || !visiting.add(name)) return
            first.getValue(name).sorted().forEach { visit(it) }
            visiting -= name
            done += name
        }
        set.sorted().forEach { visit(it) }
        return done.toList()
    }

    // --- The view and the loop.

    /** The view alone (see VIEW), for what runs in it without a build (backups). */
    private fun viewText() = VIEW.replace("@BUSYBOX@", busybox()).replace("@DATA@", data.path)

    fun writeView() {
        dir.mkdirs()
        view.writeText(viewText())
        background.writeText(BACKGROUND)
        // As on NixOS: /var/run and /var/lock are /run's (relative links: the same inside the view and outside of it),
        // and /var/tmp is everyone's.
        runCatching {
            File(runDir, "lock").mkdirs()
            for ((name, target) in listOf("run" to "../run", "lock" to "../run/lock")) {
                val link = File(varDir, name)
                if (!link.exists() && !Files.isSymbolicLink(link.toPath())) {
                    varDir.mkdirs()
                    Os.symlink(target, link.path)
                }
            }
            val tmp = File(varDir, "tmp")
            if (!tmp.isDirectory) {
                tmp.mkdirs()
                Os.chmod(tmp.path, "1777".toInt(8))
            }
            // Where systemd-creds keeps the phone's own key, for encrypted credentials (LoadCredentialEncrypted).
            File(varDir, "lib/systemd").mkdirs()
        }
    }

    /**
     * The view and Nixup's /etc, with the shell of [android] (the profile's android part) as root's, for nixup-shell,
     * the app's terminal and ssh sessions, and the folders of systemd.tmpfiles; written by each build, also one that
     * starts no unit.
     */
    fun prepare(android: JSONObject?) {
        writeView()
        Etc.write(data, android)
        Tmpfiles.apply(data, android?.optJSONArray("tmpfiles"))
        if ((android?.optJSONObject("units")?.length() ?: 0) > 0) watchNetwork() else unwatchNetwork()
    }

    /**
     * Keeps Nixup's /etc/resolv.conf on the phone's network while there are services: a loop of its own
     * ([NETWORK_WATCH]) looks at the phone's way out (its route) every 20 seconds, and when it changes (another Wi-Fi,
     * mobile data) has the engine write the network's DNS servers again (dns-update). Before, the services kept the
     * servers of the network of the last build or start, which other networks may not answer.
     */
    private fun watchNetwork() {
        val module = Notify.module ?: return
        val pidFile = File(dir, "network.pid")
        if (pid(pidFile)?.takeIf { cmdline(it).contains(NETWORK_MARK) } != null) return
        dir.mkdirs()
        runCatching {
            ProcessBuilder(busybox(), "setsid", "sh", "-c", NETWORK_WATCH, NETWORK_MARK, File(module, "app.apk").path, pidFile.path)
                .redirectInput(File("/dev/null")).redirectOutput(File("/dev/null")).redirectError(File("/dev/null"))
                .apply { listOf("NIXUP_RUN", "NIXUP_KIND", "NIXUP_QUIET").forEach { environment().remove(it) } }.start()
        }
    }

    private fun unwatchNetwork() {
        val pidFile = File(dir, "network.pid")
        pid(pidFile)?.takeIf { cmdline(it).contains(NETWORK_MARK) }?.let { Device.run("kill", "-TERM", "$it") }
        pidFile.delete()
    }

    /** The services Nixup ran before units: stopped, Tailscale's and Syncthing's state moved to theirs, and gone. */
    private fun moveLegacy() {
        if (!legacy.isDirectory) return
        for (service in legacy.listFiles().orEmpty().filter { it.isDirectory }) {
            pid(File(service, "supervisor.pid"))?.let { Device.run("kill", "-TERM", "$it") }
            Thread.sleep(1000)
            File("/proc").listFiles().orEmpty().mapNotNull { it.name.toIntOrNull() }
                .filter { cmdline(it).startsWith(service.path + "/") }
                .forEach { Device.run("kill", "-KILL", "$it") }
        }
        for ((from, to) in listOf("tailscale/state" to "lib/tailscale", "syncthing/home" to "lib/syncthing")) {
            val old = File(legacy, from)
            val new = File(varDir, to)
            if (old.isDirectory && !new.exists()) {
                new.parentFile?.mkdirs()
                old.renameTo(new)
            }
        }
        deleteTree(legacy)
        Out.line("✔ Services: the earlier Nixup's moved over to units")
    }

    /** A command line of a unit, as systemd takes it, with what it needs to become shell words (see [parse]). */
    /** [argv0]: its second word is the name the program gets as its argv[0] (systemd's @ prefix). */
    private class Command(val ignore: Boolean, val privileged: Boolean, private val words: List<List<Part>>, val argv0: Boolean = false) {
        /** Its words, with its variables' values from [env]; one with none, empty. */
        fun words(env: Map<String, String>): List<String> = words.flatMap { parts ->
            val whole = parts.singleOrNull() as? Part.Variable
            if (whole != null && whole.split) (env[whole.name] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }
            else listOf(parts.joinToString("") { part -> when (part) { is Part.Text -> part.text; is Part.Variable -> env[part.name] ?: "" } })
        }

        /**
         * Its words for the shell: as [words], quoted; with [runtime], a variable [env] has not got is left to the shell,
         * which knows it when the unit runs (from its EnvironmentFile, its user's HOME), as systemd does.
         */
        fun shell(env: Map<String, String>, runtime: Boolean): List<String> = words.flatMap { parts ->
            val whole = (parts.singleOrNull() as? Part.Variable)?.takeIf { it.split }
            val unknown = runtime && parts.any { it is Part.Variable && it.name !in env }
            when {
                !unknown && whole != null -> (env[whole.name] ?: "").split(Regex("\\s+")).filter { it.isNotEmpty() }.map { quote(it) }
                !unknown -> listOf(quote(parts.joinToString("") { part -> when (part) { is Part.Text -> part.text; is Part.Variable -> env[part.name] ?: "" } }))
                // $NAME alone splits into words (its script has globs off: set -f).
                whole != null -> listOf("${'$'}{${whole.name}}")
                else -> listOf("\"" + parts.joinToString("") { part ->
                    when (part) {
                        is Part.Text -> inQuotes(part.text)
                        is Part.Variable -> env[part.name]?.let { inQuotes(it) } ?: "${'$'}{${part.name}}"
                    }
                } + "\"")
            }
        }

        /** [text] as it stays itself in the shell's double quotes. */
        private fun inQuotes(text: String) = text.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")
    }

    private sealed class Part {
        class Text(val text: String) : Part()
        /** $NAME as a word of its own ([split]: its value's words), or ${NAME}, or $NAME in a word. */
        class Variable(val name: String, val split: Boolean) : Part()
    }

    /** [line] of unit [name] as systemd takes it (see the companion's [parse]), with its specifiers. */
    private fun parse(name: String, unit: JSONObject, line: String, arguments: Boolean = false): Command =
        parse(line, arguments) { letter -> specifier(name, unit, letter) }

    /**
     * systemd's specifiers a command may have, and their values in the view; a user's own unit's (userManager: systemd.user,
     * Home Manager's) as its systemd --user has them: %t its /run/user/<uid>, %S, %C, %L and %E in its home. Those of its
     * user ($HOME, $nixup_uid) the shell knows when the unit runs.
     */
    private fun specifier(name: String, unit: JSONObject, letter: Char): String {
        val user = unit.string("user")
        val instance = name.substringAfter('@', "")
        val mine = unit.optBoolean("userManager")
        val home = if (user != null) "${'$'}{HOME}" else File(data, "home").path
        return when (letter) {
            '%' -> "%"
            'n' -> "$name.service"
            'N' -> name
            'p' -> name.substringBefore('@')
            'P' -> name.substringBefore('@')
            'i', 'I' -> instance
            'j', 'J' -> name.substringBefore('@').substringAfterLast('-')
            't' -> if (!mine) "/run" else if (user != null) "/run/user/${'$'}{nixup_uid}" else "/run/user/0"
            'S' -> if (mine) "$home/.local/state" else "/var/lib"
            'C' -> if (mine) "$home/.cache" else "/var/cache"
            'L' -> if (mine) "$home/.local/state/log" else "/var/log"
            'E' -> if (mine) "$home/.config" else "/etc"
            'T', 'V' -> "/tmp"
            'd' -> credentialsDirectory(name)
            'u' -> user ?: "root"
            'g' -> unit.string("group") ?: user ?: "root"
            'h' -> if (user != null) "${'$'}{HOME}" else File(data, "home").path
            'U' -> if (user != null) "${'$'}{nixup_uid}" else "0"
            'G' -> if (user != null) "${'$'}{nixup_gid}" else "0"
            'a' -> "arm64"
            'v' -> System.getProperty("os.version").orEmpty()
            else -> throw IllegalArgumentException("its command has %$letter, a systemd specifier not on the phone (%n, %N, %p, %i, %I, %t, %S, %C, %L, %E, %T, %d, %u, %g, %h, %U, %G, %a, %v and %% are)")
        }
    }

    companion object {
        /** The battery level (percent) below which a job's time is skipped, unless the phone charges. */
        private const val LOW_BATTERY = 15

        private const val OK = "ok"
        private const val SKIPPED = "skipped"
        private const val WAITING = "waiting"
        private const val STORAGE_WHY = "the phone's storage: an SD card, or what Android mounts once the phone is first unlocked"

        /** systemd's Limit* keys as the phone's shell (mksh) sets them: its flag, and the bytes of its unit (1: a count). */
        private val LIMITS = mapOf(
            "CPU" to ("-t" to 1L), "FSIZE" to ("-f" to 512L), "DATA" to ("-d" to 1024L), "STACK" to ("-s" to 1024L),
            "CORE" to ("-c" to 512L), "RSS" to ("-m" to 1024L), "NOFILE" to ("-n" to 1L), "AS" to ("-v" to 1024L),
            "NPROC" to ("-p" to 1L), "MEMLOCK" to ("-l" to 1024L), "LOCKS" to ("-x" to 1L), "SIGPENDING" to ("-i" to 1L),
            "MSGQUEUE" to ("-q" to 1L), "NICE" to ("-e" to 1L), "RTPRIO" to ("-r" to 1L),
        )

        private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

        /**
         * EnvironmentFile, as systemd reads one: NAME=value lines (a value in quotes, or not; "export " before it taken),
         * without blank lines and comments (# or ;). A file marked optional (- in the unit) may be missing.
         */
        private val ENVIRONMENT_FILE = """
            nixup_environment() {
              [ -r "${'$'}1" ] || { [ "${'$'}2" = optional ] && return 0; echo "${'$'}1: there is no such file (EnvironmentFile)" >&2; return 1; }
              while IFS= read -r nixup_line || [ -n "${'$'}nixup_line" ]; do
                while :; do case ${'$'}nixup_line in ' '* | '	'*) nixup_line=${'$'}{nixup_line#?} ;; *) break ;; esac; done
                case ${'$'}nixup_line in '' | '#'* | ';'*) continue ;; esac
                nixup_line=${'$'}{nixup_line#export }
                case ${'$'}nixup_line in [A-Za-z_]*=*) ;; *) continue ;; esac
                nixup_key=${'$'}{nixup_line%%=*}
                nixup_value=${'$'}{nixup_line#*=}
                case ${'$'}nixup_key in *[!A-Za-z0-9_]*) continue ;; esac
                case ${'$'}nixup_value in
                  \"*\") nixup_value=${'$'}{nixup_value#\"}; nixup_value=${'$'}{nixup_value%\"} ;;
                  \'*\') nixup_value=${'$'}{nixup_value#\'}; nixup_value=${'$'}{nixup_value%\'} ;;
                esac
                export "${'$'}nixup_key=${'$'}nixup_value"
              done < "${'$'}1"
            }
        """.trimIndent() + "\n"

        /**
         * Shell functions on the phone's storage, with busybox as ${'$'}b: on_storage ROOT PATH (the nearest part of PATH
         * that is there, under ROOT ("": the phone's own), is not RAM) and all_there FILE (each path in FILE is).
         */
        private val ON_STORAGE = """
            on_storage() {
              d=${'$'}2
              while [ "${'$'}d" != / ] && [ ! -e "${'$'}1${'$'}d" ]; do d=${'$'}{d%/*}; [ -n "${'$'}d" ] || d=/; done
              t=${'$'}("${'$'}b" stat -f -c %T "${'$'}1${'$'}d" 2> /dev/null) && [ -n "${'$'}t" ] && [ "${'$'}t" != tmpfs ]
            }
            all_there() { [ -f "${'$'}1" ] || return 0; while read -r p; do [ -z "${'$'}p" ] || on_storage "" "${'$'}p" || return 1; done < "${'$'}1"; return 0; }
        """.trimIndent()
        private const val ONESHOT_MINUTES = 10
        private const val MARK = "nixup-unit:"
        private const val NETWORK_MARK = "nixup-network"

        /** The variables Android's init gives every process besides ANDROID_* (its commands, am and cmd, need them). */
        private val ANDROID_ENVIRONMENT = setOf(
            "BOOTCLASSPATH", "DEX2OATBOOTCLASSPATH", "SYSTEMSERVERCLASSPATH", "STANDALONE_SYSTEMSERVER_JARS",
            "ASEC_MOUNTPOINT", "EXTERNAL_STORAGE", "DOWNLOAD_CACHE",
        )

        /**
         * The services' network watch (see watchNetwork): the phone's routes to the internet (IPv4 and IPv6) every 20
         * seconds; when they change, the engine's dns-update then and three times more (a new network's DNS servers may
         * come a little after it). Its start counts as a change: at boot the network may come only after Nixup's /etc
         * was written, which then kept the DNS servers it had. Arguments: the module's app.apk, its pid file.
         */
        private val NETWORK_WATCH = """
            apk=${'$'}1 pidfile=${'$'}2
            echo ${'$'}${'$'} > "${'$'}pidfile"
            engine() { CLASSPATH="${'$'}apk" /system/bin/app_process /system/bin dev.nixup.manager.engine.Main "${'$'}@" > /dev/null 2>&1 < /dev/null; }
            route() { /system/bin/ip route get 8.8.8.8 2> /dev/null; /system/bin/ip -6 route get 2001:4860:4860::8888 2> /dev/null; }
            last= again=0
            while :; do
              sleep 20 & wait ${'$'}!
              now=${'$'}(route)
              if [ "${'$'}now" != "${'$'}last" ]; then last=${'$'}now again=4; fi
              if [ ${'$'}again -gt 0 ]; then again=${'$'}((again - 1)); engine dns-update; fi
            done
        """.trimIndent()
        private val BUSYBOXES = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox")

        /** The root solution's busybox, which runs the services (and the timers' loops). */
        fun busybox() = BUSYBOXES.firstOrNull { File(it).canExecute() }
            ?: throw Stop("there is no busybox (Magisk's or KernelSU's) to run the services with", 1)

        /**
         * A clean start, as systemd gives its services (and the activation scripts, see Activation): Android's own
         * variables (init's, which its commands need) and PATH, nothing of whoever started the engine (its HOSTNAME,
         * TMPDIR, TERM, CLASSPATH, an adb session's ...).
         */
        fun cleanEnvironment(env: MutableMap<String, String>) {
            val keep = env.filterKeys { key ->
                key == "PATH" || key in ANDROID_ENVIRONMENT || (key.startsWith("ANDROID_") && !key.startsWith("ANDROID_SOCKET_"))
            }
            env.clear()
            env.putAll(keep)
            // Where the names of time zones are, as NixOS's units have it (see Etc).
            env["TZDIR"] = "/etc/zoneinfo"
            Notify.module?.let {
                env["NIXUP_MODULE"] = it.path
                env["NIXUP_NOTIFY"] = File(it, "notify.sh").path
            }
        }

        /**
         * The shared storage in a unit's view, for a user of Android's storage groups (media_rw, sdcard_rw,
         * external_storage: see nix/units.nix): the phone's /storage lets no user in but the apps' (it is Android's
         * FUSE), so the view gets a /storage of its own with what Android's has, the real folders under it as Android
         * keeps them (the phone's own storage, /data/media/0, and each SD card in /mnt/media_rw), which media_rw may use;
         * /sdcard leads there too. As root, before the unit's own steps.
         */
        private val STORAGE = """
            "${'$'}nixup_b" mount -t tmpfs -o mode=0755,nosuid,nodev nixup-storage /storage && /system/bin/mkdir -p /storage/emulated/0 /storage/self && "${'$'}nixup_b" mount --bind /data/media/0 /storage/emulated/0 && /system/bin/ln -s /storage/emulated/0 /storage/self/primary || exit 1
            ( set +f; for c in /mnt/media_rw/*; do [ -d "${'$'}c" ] || continue; /system/bin/mkdir -p "/storage/${'$'}{c##*/}" && "${'$'}nixup_b" mount --bind "${'$'}c" "/storage/${'$'}{c##*/}"; done )
        """.trimIndent() + "\n"

        /**
         * Runs a unit's script ($1) as root in Nixup's view of the phone: a root of its own, in a mount namespace of
         * its own, with Android's own folders as they are, /nix (Nixup's store), Nixup's /etc, /var and /run (the
         * units' state, which stays), /home (the normal users' homes, Nixup's users folder) and /tmp (one for all, as
         * on NixOS: Nixup's run/tmp; its own with PrivateTmp, NIXUP_PRIVATE_TMP);
         * Android's time zone is Nixup's /etc/localtime (see Etc), else TZ, its offset from UTC ("<+0300>-03:00").
         * /proc/sys/kernel/modprobe and poweroff_cmd are empty there: Nix's garbage collector reads them with a read
         * Android's 4.x kernels may refuse ("Out of memory"), which stops it.
         * Each step execs the next, so the script's own process is the one started.
         */
        private val VIEW = """
            # Nixup's view of the phone, for its services (written by the engine; see Units.kt).
            exec @BUSYBOX@ unshare -m --propagation private sh -c '
              b=${'$'}0 data=${'$'}1 script=${'$'}2
              set -e
              "${'$'}b" mount --make-rprivate /
              v=/dev/nixup-view-${'$'}${'$'}
              mkdir -p "${'$'}v"
              "${'$'}b" mount -t tmpfs -o mode=755 nixup "${'$'}v"
              for e in /*; do
                n=${'$'}{e#/}
                case ${'$'}n in nix | etc | var | run | home | tmp) continue ;; esac
                if [ -L "${'$'}e" ]; then ln -s "${'$'}(readlink "${'$'}e")" "${'$'}v/${'$'}n"
                elif [ -d "${'$'}e" ]; then mkdir -p "${'$'}v/${'$'}n"; "${'$'}b" mount --rbind "${'$'}e" "${'$'}v/${'$'}n"
                elif [ -f "${'$'}e" ]; then : > "${'$'}v/${'$'}n"; "${'$'}b" mount --bind "${'$'}e" "${'$'}v/${'$'}n"
                fi
              done
              # /dev holds the view itself: its copy inside the view goes.
              "${'$'}b" umount -l "${'$'}v${'$'}v" 2> /dev/null || true
              for d in nix:${'$'}data/nix/nix etc:${'$'}data/etc var:${'$'}data/var run:${'$'}data/run home:${'$'}data/users; do
                mkdir -p "${'$'}v/${'$'}{d%%:*}" "${'$'}{d#*:}"
                "${'$'}b" mount --bind "${'$'}{d#*:}" "${'$'}v/${'$'}{d%%:*}"
              done
              # /tmp: one for every unit and every session, as NixOS has it (a tmux server of a user and its sessions):
              # run/tmp in the data folder, emptied at each start with /run. A unit with PrivateTmp gets one of its own.
              # (No apostrophe in these lines: this is all one quoted argument of sh -c.)
              mkdir -p "${'$'}v/tmp"
              if [ -n "${'$'}NIXUP_PRIVATE_TMP" ]; then "${'$'}b" mount -t tmpfs -o mode=1777 tmp "${'$'}v/tmp"
              else mkdir -p "${'$'}data/run/tmp" && chmod 1777 "${'$'}data/run/tmp" && "${'$'}b" mount --bind "${'$'}data/run/tmp" "${'$'}v/tmp"
              fi
              # Kernel files the garbage collector of Nix reads (for store paths in them), of which an Android kernel
              # may refuse a big read (Out of memory), which stops it: empty ones here, as containers have them.
              for k in modprobe fbsplash poweroff_cmd; do
                [ -e "${'$'}v/proc/sys/kernel/${'$'}k" ] && "${'$'}b" mount --bind /dev/null "${'$'}v/proc/sys/kernel/${'$'}k" 2> /dev/null || true
              done
              if [ -z "${'$'}TZ" ] && [ ! -s "${'$'}data/etc/localtime" ]; then
                off=${'$'}(/system/bin/date +%z 2> /dev/null)
                case ${'$'}off in
                  [+-][0-9][0-9][0-9][0-9])
                    hm=${'$'}{off#?}
                    case ${'$'}off in +*) sign=- ;; *) sign=+ ;; esac
                    export TZ="<${'$'}off>${'$'}sign${'$'}{hm%??}:${'$'}{hm#??}" ;;
                esac
              fi
              exec "${'$'}b" chroot "${'$'}v" /system/bin/sh "${'$'}script"
            ' @BUSYBOX@ @DATA@ "${'$'}1"
        """.trimIndent() + "\n"

        /**
         * Runs a command below the apps on the screen: in Android's system-background cpuset (its background cores),
         * at the nice given (absolute: busybox's renice without -n), with the lowest share of the storage's time of
         * the normal ones. What it starts keeps all three. Arguments: busybox, nice, then the command.
         */
        private val BACKGROUND = """
            # Runs a command below the apps on the screen (written by the engine; see Units.kt).
            b=${'$'}1 nice=${'$'}2
            shift 2
            for c in /dev/cpuset/system-background/cgroup.procs /dev/cpuset/system-background/tasks; do
              [ -w "${'$'}c" ] && echo ${'$'}${'$'} > "${'$'}c" 2> /dev/null && break
            done
            "${'$'}b" renice "${'$'}nice" -p ${'$'}${'$'} > /dev/null 2>&1
            /system/bin/ionice -c 2 -n 7 -p ${'$'}${'$'} > /dev/null 2>&1
            exec "${'$'}@"
        """.trimIndent() + "\n"

        /**
         * Keeps a simple (or forking) unit running: its start.sh in the view, its post.sh once it has started, and again
         * as its Restart says (after RestartSec), with its ExecStopPost (stoppost.sh) after each run; a TERM runs its
         * stop.sh (ExecStop, ended whole past TimeoutStopSec), ends it as its KillMode, KillSignal and TimeoutStopSec say (control-group: all of its
         * processes, which its view's mount namespace tells, as systemd's cgroup does), then runs its stoppost.sh. A
         * forking unit's service is the process its PIDFile names, else the one left in its view after its start ends.
         * Its log is set aside past 1 MB. With notify.sh, it tells the app when the unit stops by itself (once, until it
         * has run again for a minute) and when it runs again.
         * With unitConfig.RequiresMountsFor (the unit folder's mounts) it waits for that storage before each start, and
         * while it runs stops it when the storage goes away and starts it again in a fresh view when it came after its
         * start: neither is a failure. A run whose condition is not met (its start.sh leaves "skipped") ends the loop, as
         * no failure. A clean end is exit status 0, SIGHUP, SIGINT, SIGTERM or SIGPIPE, or one of SuccessExitStatus;
         * RestartPreventExitStatus's codes keep it stopped, RestartForceExitStatus's start it again.
         * Arguments: busybox, view.sh, log, unit folder, name; the rest is in the unit folder's run.conf.
         */
        private val SUPERVISOR = """
            b=${'$'}1 view=${'$'}2 log=${'$'}3 unit=${'$'}4 name=${'$'}5
            . "${'$'}unit/run.conf"
            export NIXUP_PRIVATE_TMP=${'$'}privatetmp
            echo ${'$'}${'$'} > "${'$'}unit/supervisor.pid"
            child= watcher= main=
            tell() { [ -n "${'$'}notify" ] && "${'$'}b" sh "${'$'}notify" service "${'$'}@" "service:${'$'}name" < /dev/null > /dev/null 2>&1; }
            inview() { "${'$'}b" sh "${'$'}view" "${'$'}1" >> "${'$'}log" 2>&1 < /dev/null; }
            # Its ExecStop, at most TimeoutStopSec, as systemd waits for it: in a session of its own, ended whole after.
            stopview() {
              "${'$'}b" setsid "${'$'}b" sh "${'$'}view" "${'$'}1" >> "${'$'}log" 2>&1 < /dev/null &
              stopper=${'$'}! i=0
              while kill -0 ${'$'}stopper 2> /dev/null && [ ${'$'}i -lt ${'$'}((timeout * 5)) ]; do sleep 0.2; i=${'$'}((i + 1)); done
              if kill -0 ${'$'}stopper 2> /dev/null; then
                echo "${'$'}name: its ExecStop did not end in ${'$'}timeout seconds" >> "${'$'}log"
                kill -9 -${'$'}stopper 2> /dev/null; kill -9 ${'$'}stopper 2> /dev/null
              fi
              wait ${'$'}stopper 2> /dev/null
            }
            # Its processes: those in the mount namespace of the view its start made.
            # (One stat for all: the namespace's number is its file's inode. A readlink for each of a phone's ~900
            # processes took half a minute.)
            # (Its holder, which keeps the namespace and its number while the loop looks, is not one of them.)
            procs() {
              [ -f "${'$'}unit/ns" ] || return 0
              ino=${'$'}(tr -dc 0-9 < "${'$'}unit/ns")
              h=${'$'}(cat "${'$'}unit/holder" 2> /dev/null)
              "${'$'}b" stat -L -c '%i %n' /proc/[0-9]*/ns/mnt 2> /dev/null | while read -r i n; do [ "${'$'}i" = "${'$'}ino" ] && { n=${'$'}{n#/proc/}; n=${'$'}{n%%/*}; [ "${'$'}n" = "${'$'}h" ] || echo "${'$'}n"; }; done
              return 0
            }
            # Whether process ${'$'}1 is in its view (a main the loop found, not its own child, may be gone and its pid another's).
            ours() { [ -n "${'$'}1" ] && [ "${'$'}("${'$'}b" stat -L -c %i /proc/${'$'}1/ns/mnt 2> /dev/null)" = "${'$'}(tr -dc 0-9 < "${'$'}unit/ns" 2> /dev/null)" ]; }
            killmain() { [ -n "${'$'}main" ] && { [ "${'$'}main" = "${'$'}child" ] || ours "${'$'}main"; } && kill -"${'$'}1" "${'$'}main" 2> /dev/null; }
            # The view's holder goes last, once what was left of the unit has ended.
            holder_end() { h=${'$'}(cat "${'$'}unit/holder" 2> /dev/null) && ours "${'$'}h" && kill -9 "${'$'}h" 2> /dev/null; rm -f "${'$'}unit/holder"; }
            alive() { { [ -n "${'$'}main" ] && kill -0 "${'$'}main" 2> /dev/null; } || { [ "${'$'}killmode" = control-group ] && [ -n "${'$'}(procs)" ]; }; }
            # Ends it as KillMode says, with KillSignal; what still runs after TimeoutStopSec gets SIGKILL (unless SendSIGKILL is no).
            end() {
              case ${'$'}killmode in
                none) ;;
                process | mixed) killmain "${'$'}killsignal" ;;
                *) killmain "${'$'}killsignal"; for p in ${'$'}(procs); do kill -"${'$'}killsignal" "${'$'}p" 2> /dev/null; done ;;
              esac
              i=0
              while [ "${'$'}killmode" != none ] && alive && [ ${'$'}i -lt ${'$'}((timeout * 5)) ]; do sleep 0.2; i=${'$'}((i + 1)); done
              if [ -n "${'$'}sigkill" ] && [ "${'$'}killmode" != none ]; then
                killmain 9
                [ "${'$'}killmode" = process ] || for p in ${'$'}(procs); do kill -9 "${'$'}p" 2> /dev/null; done
              fi
              return 0
            }
            stopping() {
              [ -n "${'$'}watcher" ] && kill ${'$'}watcher 2> /dev/null
              [ -n "${'$'}stop" ] && [ -n "${'$'}main" ] && stopview "${'$'}stop"
              [ -n "${'$'}main" ] && end
              [ -n "${'$'}child" ] && wait ${'$'}child 2> /dev/null
              holder_end
              [ -n "${'$'}stoppost" ] && inview "${'$'}stoppost"
              rm -f "${'$'}unit/supervisor.pid" "${'$'}unit/main.pid" "${'$'}unit/waiting" "${'$'}unit/fresh" "${'$'}unit/ns" "${'$'}unit/ready" "${'$'}unit/listening"
              exit 0
            }
            trap stopping TERM INT
            @ON_STORAGE@
            # The first of its storage (unitConfig.RequiresMountsFor) the phone has not got now; the first the phone has got and
            # the view of process ${'$'}1 has not (mounted after it started, which a view does not take).
            missing() { [ -f "${'$'}unit/mounts" ] || return 1; while read -r p; do [ -z "${'$'}p" ] || on_storage "" "${'$'}p" || { echo "${'$'}p"; return 0; }; done < "${'$'}unit/mounts"; return 1; }
            stale() { [ -f "${'$'}unit/mounts" ] || return 1; while read -r p; do [ -n "${'$'}p" ] && on_storage "" "${'$'}p" && ! on_storage "/proc/${'$'}1/root" "${'$'}p" && { echo "${'$'}p"; return 0; }; done < "${'$'}unit/mounts"; return 1; }
            has() { case " ${'$'}2 " in *" ${'$'}1 "*) return 0 ;; esac; return 1; }
            while :; do
              [ -f "${'$'}log" ] && [ "${'$'}(stat -c %s "${'$'}log")" -gt 1048576 ] && mv -f "${'$'}log" "${'$'}log.old"
              # Its storage first: it waits for it, which is no failure.
              if p=${'$'}(missing); then
                echo "${'$'}(date '+%F %T') ${'$'}name: waiting for ${'$'}p (the phone's storage: an SD card, or what Android mounts once the phone is first unlocked)" >> "${'$'}log"
                : > "${'$'}unit/waiting"
                while missing > /dev/null; do sleep 5 & wait ${'$'}!; done
                rm -f "${'$'}unit/waiting"
              fi
              echo "${'$'}(date '+%F %T') ${'$'}name: starting" >> "${'$'}log"
              rm -f "${'$'}unit/skipped" "${'$'}unit/ns" "${'$'}unit/watchdog-fired" "${'$'}unit/listening" "${'$'}unit/unready"
              [ -n "${'$'}notifylog" ] && rm -f "${'$'}notifylog"
              "${'$'}b" sh "${'$'}view" "${'$'}start" >> "${'$'}log" 2>&1 < /dev/null &
              child=${'$'}!
              main=${'$'}child
              echo ${'$'}child > "${'$'}unit/main.pid"
              code=
              if [ "${'$'}type" = forking ]; then
                # Its start forks the service and ends: the service is the process its PIDFile names, else the one left in
                # its view.
                wait ${'$'}child
                code=${'$'}?
                child= main=
                if [ ${'$'}code -eq 0 ] && [ ! -f "${'$'}unit/skipped" ]; then
                  i=0
                  while [ -z "${'$'}main" ] && [ ${'$'}i -lt ${'$'}((timeout_start * 5)) ]; do
                    if [ -n "${'$'}pidfile" ]; then
                      main=${'$'}(tr -dc 0-9 < "${'$'}pidfile" 2> /dev/null)
                      [ -n "${'$'}main" ] && ! kill -0 "${'$'}main" 2> /dev/null && main=
                    else
                      main=${'$'}(procs | head -n 1)
                    fi
                    [ -n "${'$'}main" ] || { sleep 0.2; i=${'$'}((i + 1)); }
                  done
                  if [ -n "${'$'}main" ]; then echo ${'$'}main > "${'$'}unit/main.pid"; code=; else echo "${'$'}(date '+%F %T') ${'$'}name: its service was not found after its start (PIDFile: ${'$'}{pidfile:-none})" >> "${'$'}log"; code=1; fi
                fi
                : > "${'$'}unit/ready"
              fi
              # Type = notify (it has a notifylog): started once it says READY=1 (TimeoutStartSec), then watched for
              # WATCHDOG=1 (WatchdogSec).
              unready= dog=
              if [ -z "${'$'}code" ] && [ -n "${'$'}notifylog" ]; then
                i=0
                until grep -q READY=1 "${'$'}notifylog" 2> /dev/null; do
                  kill -0 ${'$'}child 2> /dev/null || break
                  if [ ${'$'}i -ge ${'$'}((timeout_start * 5)) ]; then
                    echo "${'$'}(date '+%F %T') ${'$'}name: it did not say it was ready (READY=1) in ${'$'}timeout_start s: ended" >> "${'$'}log"
                    unready=1; : > "${'$'}unit/unready"; end; break
                  fi
                  sleep 0.2; i=${'$'}((i + 1))
                done
                [ -n "${'$'}unready" ] || : > "${'$'}unit/ready"
                if [ -z "${'$'}unready" ] && [ -n "${'$'}watchdog" ]; then
                  (
                    last=${'$'}(date +%s) seen=0
                    while kill -0 ${'$'}main 2> /dev/null; do
                      sleep 1
                      n=${'$'}(grep -o WATCHDOG=1 "${'$'}notifylog" 2> /dev/null | wc -l)
                      now=${'$'}(date +%s)
                      if [ "${'$'}n" -gt "${'$'}seen" ]; then seen=${'$'}n last=${'$'}now
                      elif [ ${'$'}((now - last)) -ge "${'$'}watchdog" ]; then
                        echo "${'$'}(date '+%F %T') ${'$'}name: no WATCHDOG=1 in ${'$'}watchdog s: ended (SIGABRT)" >> "${'$'}log"
                        : > "${'$'}unit/watchdog-fired"
                        kill -ABRT ${'$'}main 2> /dev/null
                        exit 0
                      fi
                    done
                  ) < /dev/null > /dev/null 2>&1 &
                  dog=${'$'}!
                fi
              fi
              if [ -z "${'$'}code" ]; then
                if [ -f "${'$'}unit/failing" ]; then
                  # (Only while this run is still the unit's: once it ends, its pid may be another process's.)
                  ( sleep 60; [ "${'$'}(cat "${'$'}unit/main.pid" 2> /dev/null)" = "${'$'}main" ] && kill -0 ${'$'}main 2> /dev/null && rm "${'$'}unit/failing" 2> /dev/null && tell ok "${'$'}name runs again" "It had stopped by itself" ) < /dev/null > /dev/null 2>&1 &
                fi
                [ -n "${'$'}post" ] && [ ! -f "${'$'}unit/skipped" ] && [ -z "${'$'}unready" ] && inview "${'$'}post"
                # While it runs, its storage is looked at every 15 s: one that went away stops it (to wait for it again), one
                # that came after its start starts it again in a fresh view.
                rm -f "${'$'}unit/fresh"
                if [ -f "${'$'}unit/mounts" ]; then
                  (
                    while sleep 15; do
                      kill -0 ${'$'}main 2> /dev/null || exit 0
                      if p=${'$'}(missing); then why="${'$'}p went away: waiting for it again"
                      elif p=${'$'}(stale ${'$'}main); then why="${'$'}p was mounted after it started: starting it again in a fresh view"
                      else continue; fi
                      echo "${'$'}why" > "${'$'}unit/fresh"
                      [ -n "${'$'}stop" ] && "${'$'}b" sh "${'$'}view" "${'$'}stop" >> "${'$'}log" 2>&1 < /dev/null
                      kill ${'$'}main 2> /dev/null
                      exit 0
                    done
                  ) < /dev/null > /dev/null 2>&1 &
                  watcher=${'$'}!
                fi
                if [ -n "${'$'}child" ]; then
                  wait ${'$'}child
                  code=${'$'}?
                else
                  # A forking unit's service is not this loop's child: it is watched, and its end is a failure (its status
                  # is its parent's to know).
                  while kill -0 "${'$'}main" 2> /dev/null; do sleep 1 & wait ${'$'}!; done
                  code=1
                fi
                [ -n "${'$'}watcher" ] && kill ${'$'}watcher 2> /dev/null
                [ -n "${'$'}dog" ] && kill ${'$'}dog 2> /dev/null
              fi
              [ -n "${'$'}unready" ] && code=1
              # What is left of it ends with it (KillMode control-group), as systemd ends a service's processes.
              [ "${'$'}killmode" = control-group ] && [ -n "${'$'}(procs)" ] && { main=; end; }
              holder_end
              child= watcher= main=
              rm -f "${'$'}unit/main.pid"
              [ -n "${'$'}stoppost" ] && inview "${'$'}stoppost"
              if [ -f "${'$'}unit/fresh" ]; then
                # Stopped for its storage: no failure, and it starts again (or waits) whatever Restart says.
                echo "${'$'}(date '+%F %T') ${'$'}name: ${'$'}(cat "${'$'}unit/fresh")" >> "${'$'}log"
                rm -f "${'$'}unit/fresh"
                continue
              fi
              if [ -f "${'$'}unit/skipped" ]; then
                echo "${'$'}(date '+%F %T') ${'$'}name: not started: ${'$'}(cat "${'$'}unit/skipped")" >> "${'$'}log"
                break
              fi
              echo "${'$'}(date '+%F %T') ${'$'}name: stopped (${'$'}code)" >> "${'$'}log"
              # A clean end: 0, SIGHUP, SIGINT, SIGPIPE or SIGTERM, or SuccessExitStatus; a signal else is abnormal.
              clean=
              case ${'$'}code in 0 | 129 | 130 | 141 | 143) clean=1 ;; esac
              has ${'$'}code "${'$'}success" && clean=1
              abnormal=
              [ -z "${'$'}clean" ] && [ ${'$'}code -gt 128 ] && abnormal=1
              again=
              case ${'$'}restart in
                always) again=1 ;;
                on-success) [ -n "${'$'}clean" ] && again=1 ;;
                on-failure) [ -z "${'$'}clean" ] && again=1 ;;
                on-abnormal | on-abort) [ -n "${'$'}abnormal" ] && again=1 ;;
                on-watchdog) [ -f "${'$'}unit/watchdog-fired" ] && again=1 ;;
              esac
              has ${'$'}code "${'$'}prevent" && again=
              has ${'$'}code "${'$'}force" && again=1
              # Told once: a failure, or a unit kept running (always) that stopped by itself.
              if { [ -z "${'$'}clean" ] || { [ "${'$'}restart" = always ] && ! has ${'$'}code "${'$'}success"; }; } && ! has ${'$'}code "${'$'}force" && [ ! -f "${'$'}unit/failing" ]; then
                : > "${'$'}unit/failing"
                if [ -n "${'$'}again" ]; then tell error "${'$'}name stopped" "Exit status ${'$'}code; it starts again in ${'$'}{delay%.000} s"
                else tell error "${'$'}name stopped" "Exit status ${'$'}code"; fi
              fi
              [ -n "${'$'}again" ] || break
              sleep "${'$'}delay" &
              wait ${'$'}!
            done
            rm -f "${'$'}unit/supervisor.pid" "${'$'}unit/main.pid" "${'$'}unit/waiting" "${'$'}unit/ns"
        """.trimIndent().replace("@ON_STORAGE@", ON_STORAGE)

        /** A signal's name or number (KillSignal: "SIGTERM", "TERM", 15) as kill takes it. */
        private fun signal(text: String): String = text.removePrefix("SIG").ifEmpty { "TERM" }

        /**
         * A command line as systemd takes it: its prefixes (- leaves a failure be; + ! run it as root, as units of a
         * user run their privileged steps; !! needs nothing, as the phone has ambient capabilities; : leaves $ as it is;
         * @ is not on the phone), specifiers (%n, ..., from [specifier]), quotes and backslashes, and $NAME / ${'$'}{NAME}
         * ($NAME alone splits into words, as systemd does; $$ is $). With [arguments], its first word may be anything.
         */
        private fun parse(line: String, arguments: Boolean, specifier: (Char) -> String): Command {
            // A backslash at a line's end continues the line, as systemd reads a unit file (NixOS's modules write long
            // ExecStart lines so: prometheus's).
            var rest = line.replace(Regex("\\\\\r?\n"), " ").trimStart()
            var ignore = false
            var expand = true
            var privileged = false
            var argv0 = false
            while (rest.isNotEmpty() && rest[0] in "-+!:@") {
                when (rest[0]) {
                    '-' -> ignore = true
                    ':' -> expand = false
                    '+' -> privileged = true
                    '!' -> if (rest.startsWith("!!")) rest = rest.substring(1) else privileged = true
                    '@' -> argv0 = true
                }
                rest = rest.substring(1)
            }
            // A specifier's value only known when the unit runs (its user's HOME, uid: ${'$'}{NAME}) is marked, so it stays
            // the shell's in quotes too, as systemd fills specifiers in everywhere.
            val specified = buildString {
                var i = 0
                while (i < rest.length) {
                    if (rest[i] == '%' && i + 1 < rest.length) {
                        append(SPECIFIED_VARIABLE.replace(specifier(rest[i + 1])) { "\u0001" + it.groupValues[1] + "\u0002" })
                        i += 2
                    } else {
                        append(rest[i++])
                    }
                }
            }
            val words = mutableListOf<List<Part>>()
            var i = 0
            val s = specified
            while (i < s.length) {
                while (i < s.length && s[i].isWhitespace()) i++
                if (i >= s.length) break
                // $NAME as a word of its own is its value's words.
                val whole = if (expand) WHOLE_VARIABLE.at(s, i) else null
                if (whole != null) {
                    words += listOf(Part.Variable(whole.groupValues[1], split = true))
                    i = whole.range.last + 1
                    continue
                }
                val parts = mutableListOf<Part>()
                val text = StringBuilder()
                var quote: Char? = null
                while (i < s.length && (quote != null || !s[i].isWhitespace())) {
                    val c = s[i]
                    when {
                        c == '\u0001' -> {
                            val end = s.indexOf('\u0002', i)
                            if (text.isNotEmpty()) parts += Part.Text(text.toString()).also { text.clear() }
                            parts += Part.Variable(s.substring(i + 1, end), split = false)
                            i = end
                        }
                        quote == null && (c == '"' || c == '\'') -> quote = c
                        quote != null && c == quote -> quote = null
                        c == '\\' && i + 1 < s.length && quote != '\'' -> {
                            i++
                            text.append(
                                when (s[i]) {
                                    'n' -> '\n'
                                    't' -> '\t'
                                    else -> s[i]
                                },
                            )
                        }
                        expand && c == '$' && quote != '\'' && i + 1 < s.length && s[i + 1] == '$' -> {
                            text.append('$')
                            i++
                        }
                        expand && c == '$' && quote != '\'' -> {
                            val match = VARIABLE.at(s, i)
                            if (match != null) {
                                if (text.isNotEmpty()) parts += Part.Text(text.toString()).also { text.clear() }
                                parts += Part.Variable(match.groupValues[1].ifEmpty { match.groupValues[2] }, split = false)
                                i = match.range.last
                            } else {
                                text.append(c)
                            }
                        }
                        else -> text.append(c)
                    }
                    i++
                }
                if (quote != null) throw IllegalArgumentException("its command has a quote that does not end: $line")
                if (text.isNotEmpty() || parts.isEmpty()) parts += Part.Text(text.toString())
                words += parts
            }
            if (words.isEmpty()) throw IllegalArgumentException("its command is empty")
            val first = words[0].singleOrNull() as? Part.Text
            if (!arguments && first != null && !first.text.startsWith("/")) {
                throw IllegalArgumentException("its command is not a full path, as systemd needs: ${first.text}")
            }
            if (argv0 && words.size < 2) throw IllegalArgumentException("its command starts with @, so its second word is the program's name: it has none")
            return Command(ignore, privileged, words, argv0)
        }

        /** A systemd time span (RestartSec: "10", "5s", "100ms", "1min 30s") in seconds, as sleep takes it. */
        fun seconds(span: String): String {
            val units = mapOf("us" to 0.000001, "ms" to 0.001, "s" to 1.0, "sec" to 1.0, "m" to 60.0, "min" to 60.0, "h" to 3600.0, "hr" to 3600.0)
            var total = 0.0
            for (part in Regex("([0-9.]+)\\s*([a-z]*)").findAll(span)) {
                total += (part.groupValues[1].toDoubleOrNull() ?: 0.0) * (units[part.groupValues[2].ifEmpty { "s" }] ?: 1.0)
            }
            return String.format(java.util.Locale.ROOT, "%.3f", total.coerceAtLeast(0.1))
        }

        private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

        private val WHOLE_VARIABLE = Regex("\\$([A-Za-z_][A-Za-z0-9_]*)(?=\\s|$)")
        /** A variable in a specifier's value ([specifier]: its user's HOME and uid, as the shell has them). */
        private val SPECIFIED_VARIABLE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}")
        private val VARIABLE = Regex("\\$\\{([A-Za-z_][A-Za-z0-9_]*)\\}|\\$([A-Za-z_][A-Za-z0-9_]*)")

        /** A match of this pattern that starts right at [index] of [text], if there is one. */
        private fun Regex.at(text: String, index: Int) = find(text, index)?.takeIf { it.range.first == index }

        private fun oneshot(unit: JSONObject) = unit.optString("type") == "oneshot"

        private fun steps(unit: JSONObject) = listOf("condition", "pre", "start", "post", "stop", "stopPost").flatMap { unit.objects(it) }

        private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } } ?: emptyList()
    }
}
