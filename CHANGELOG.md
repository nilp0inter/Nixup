# Changelog

What changed in each release of Nixup. The newest comes first. While a release is being made its
changes are under "Unreleased": the release build (scripts/build-magisk-release.sh) names that
section after the version, and its text is what the Magisk app shows as the update's changelog.

## Unreleased

- Run on Android 9: the module's API gate and the app's minimum are API 28; the window insets and the
  home-role lookup fall back to the pre-Android-10 APIs, the settings tables are read without the user
  argument Android 9 ignores, units wait for their program's pid before being called stopped, and saving
  the Google Play token to Download says it needs Android 10 instead of failing.

## 0.1.6 (2026-11-08)

- Request APK certificate collection on affected Android 13 builds, which otherwise return no signing
  information and reject valid downloads with an empty signer in the error. Signing-key checks remain enabled.
- Read default-app role holders through Android's role service instead of an unavailable shell
  getter, fixing false activation failures for default apps on Android 13.

## 0.1.5 (2026-11-07)

- DAVx⁵'s accounts in the config: `services.davx5` (Nixup's own; NixOS has no DAVx⁵, an Android app). Each build
  sets them up: an account the phone has not got opens DAVx⁵'s login screen on the phone, already filled in with the
  server, the user name and the password (`passwordFile`, sent to the phone root-only like every secret), so a tap on
  Login and one on Finish add it; with `automatic = true` it is added with no taps (the build asks the server
  for the calendars and address books itself, writes them into DAVx⁵'s database, adds the account as DAVx⁵ and starts
  its first sync), on DAVx⁵ 4.5.17 to 4.5.21, whose database Nixup knows; any other DAVx⁵ gets the login screen.
  An account there gets the config's user name and password at each build. `syncCollections` (`all` by
  default, or `none`) is DAVx⁵'s own setting of what it selects for syncing as it finds it, given back when
  `services.davx5` leaves the config. `install = "fdroid"` installs DAVx⁵ too; it gets the contacts, calendar and
  notifications permissions, unrestricted battery use, and is never paused for being unused (which takes an app's
  permissions away).
- Backups keep an app's accounts too (the new `accounts` part, on by default): the accounts it adds to Android's
  settings, with their passwords, user data, auth tokens, which apps see them and their sync settings (which of its
  sync adapters run, automatically, how often). Android keeps them outside the app's folders and deletes them with
  the app, so a restored DAVx⁵ had its data but no account to sync with. A restore adds the missing ones again as the
  app itself, with their sync settings: Android lets only an account type's own app add its accounts, and shows an
  account's sync settings only to a caller that can see it. One that is on the phone stays as it is. The Backups tab
  offers them among the parts to restore. Backups made before this have none: such an app is signed in again by hand
  once.
- Backups keep which runtime permissions an app was granted, and a restore grants them again (none is taken away; the
  config's own permissions are set again by the next build).
- A restore takes the app's files out of the backup into a folder of their own first, as Neo Backup does, and only
  then puts them in place of the app's: a restore that fails, or a restart in the middle, leaves the app's data as it
  was (before, its data was emptied first). A restore a restart broke off is picked up again after it.
- A restored app is let off Android's stopped state, where the restore and an install leave it: Android 15 and later
  start none of its syncs or jobs until it is opened, so a restored DAVx⁵ did not sync by itself.
- After an app is installed from a backup, its data waits (up to 30 s) for Android to have made the app's folders:
  before, it went in at once, and Android could replace the folder under it (a whole Termux restore ended in
  thousands of "no such file or directory" errors).
- An app's device data (`deviceData`) is restored on phones whose kernel refuses moving files there from Nixup's own
  folder (another encryption key): it is copied instead.
- A restore no longer relabels what links in an app's data or a folder point to: `chcon` followed them (Termux's
  `storage/shared` points at the whole shared storage).
- Files restored into Android/data, Android/media and Android/obb get their owner and storage quota from Android's own
  storage service, where the phone's storage is FUSE.
- The link to an app's native libraries that older Androids put in its data is left out of backups and never
  replaced by a restore, as Neo Backup does.

## 0.1.2 (2026-11-07)

- `nixup-rebuild` on the phone, as `nixos-rebuild` on NixOS (as root, in adb's shell, the app's terminal or ssh):
  `nixup-rebuild switch` builds the config the app picked, `dry-activate` says what would change, `switch --upgrade`
  takes everything at its newest, `switch --rollback` goes back a generation, `list-generations` lists them;
  `--file` and `--flake` build another config this once, `--specialisation` picks one.
- `nixup-config create`, on a PC: a phone's config made from what the phone has now (its settings, its apps and the
  repository each comes from, the apps it came with that are removed or disabled, battery and default apps), as
  `nixos-generate-config` does for a NixOS machine, checked against Nixup's options before it is written.
- On a PC, `nixup-rebuild` lists the phones adb sees (over USB and the network) and asks which one, and can connect
  one over the network (pairing with Wireless debugging when the phone asks); with no terminal, it takes the only one.
- `nixup-version` is the system's version command now; `nixos-version` stays, the same command, for tools written
  for NixOS.
- A dry run takes another config (`build.sh dry --source=…`): before, it dry-ran the picked config instead.
- A build from a PC over ssh (`nixup-rebuild --target-host <phone>`, as a Matrix-style flake does) works: from Nixup's
  sshd the engine got none of Android's runtime variables, ran nothing and still "succeeded", so the build was kept
  as a generation without its packages, `/etc` or services (the shells then said "set-environment: No such file"),
  and the store's roots were emptied for the next clean-up to take what the phone used. The scripts now take those
  variables from Android itself, a build stops when the engine did not run, and the roots stay as they were then.
  A phone built that way builds again once to put it right.
- One `/tmp` for every service and every session, as on NixOS: a user's tmux server started by a service is the one
  their ssh sessions reach. `serviceConfig.PrivateTmp = true` (and `DynamicUser`) gives a service its own.

## 0.1.1 (2026-11-07)

- Password logins over SSH work: with `services.openssh.settings.PasswordAuthentication = true` sshd checks a
  user's password through PAM, as on NixOS (`UsePAM` is on by itself then; pam_unix reads the view's `/etc/shadow`).
  Without PAM, nixpkgs' OpenSSH could only check DES passwords, so no NixOS-style hash ever matched.
- Users' passwords, as on NixOS: `users.users.<name>.hashedPassword`, `password` (hashed on the phone),
  `hashedPasswordFile` (a file from the config's folder, put on the phone readable by root alone, or one already
  there), `initialPassword` and `initialHashedPassword`, in the view's `/etc/shadow`, which sshd takes for a
  password login; `expires` (no logins from that day on). Also `enable = false` (the user is left out),
  `linger = false` (its own units do not run), `subUidRanges`, `subGidRanges` and `autoSubUidGidRange`
  (`/etc/subuid`, `/etc/subgid`), `users.defaultUserHome` and `users.manageLingering`, with NixOS's checks and
  warnings.
- A new phone builds again: today's nixos-unstable dropped a NixOS module Nixup imported (`security/dhparams.nix`),
  which stopped every first build; Nixup now takes only the modules the nixpkgs has, and evaluates a
  configuration.nix with that nixpkgs' own lib (Home Manager no longer warns of a mismatch).
- Safer by default: Nixup's folders and its `/etc` were writable by every user of its view (Magisk starts modules
  with umask 0), so a service's user or a build user could change what root runs; they are root's alone now, and
  an older install's are put right at the next start.
- On Samsung phones (One UI): density and navigation go back to the phone's own when they leave the config (its
  screen resolution's density, its own navigation); settings One UI resets at each start are put back at each
  start; Quick Settings tiles it refuses are told, not shown as done; a Samsung preload that cannot be brought back
  is told as such.
- A build in which a service, a setting or an activation script failed is still the phone's build (its services
  start at boot, its generation is kept), as NixOS's switch does, and ends failed with what did not work. A profile
  in a format the module does not know (sent by a PC with a newer Nixup) is refused before anything changes.
- The app's New and Pick file work in Downloads; an install that waits on Google Play Protect's question says so,
  and `android.settings.developer.verifyAdbApps = false` is set before the installs.
- Builds, backups and updates keep the phone awake while they run (a timer's at night no longer stops when the
  phone sleeps), and two of them at once no longer mix their files; a broken-off build is picked up after a restart
  as before, and Free up space waits for a running build.
- Removing Nixup waits for the phone's unlock and tries again at the next start when something did not go back.
  Installing it again with the same config works at once: the apps the removal took off (their data kept) no longer
  stop the first build with "this system has no such switch"; their settings wait until the build installs them.
- Restoring an app's backup no longer follows the links in its data: restoring Termux could empty the whole shared
  storage (its `storage/shared` link). Nixup never follows a link when it empties a folder (a user's in `/run`
  either), nor when it gives a user's folders their owner and mode.
- `unitConfig`'s `After`, `Wants`, `Requires` and the other order keys work as on NixOS, `ConditionUser` is
  decided when the config is built, and sockets, timers, paths and targets take `restartTriggers` and NixOS's other
  unit options; `users.groups` has NixOS's `wheel`, `users`, `disk`, `tty` and `dialout`.
- A path unit can watch a path under a specifier, as systemd's: `PathChanged = "%h/inbox"` in a Home Manager or
  `systemd.user` unit is in its user's home (`%t`, `%S`, `%C`, `%L`, `%E` too).
- A config whose profile is past 128 KiB (a few specialisations, or Home Manager users with many units) builds on
  the phone: it stopped with "Argument list too long".
- A service's `ExecStop` that hangs is ended after `TimeoutStopSec`, as systemd ends it, with what it started.
- Removing a user together with its Home Manager config is told plainly (its home is left as it is, as NixOS
  leaves it), not as a failure.
- Users that only Nixup knows (a `uid` of their own) log in without "bad uid" and get their `XDG_RUNTIME_DIR`.
- A user's own shell package (`users.users.<name>.shell = pkgs.xonsh`) is fetched: sshd refused such a user ("shell
  … does not exist") unless the same package was in `environment.systemPackages`.
- NixOS's `programs.*` for the shells, as nixpkgs' own modules: `programs.tmux`, `git`, `neovim`, `vim`, `htop`,
  `direnv`, `starship`, `zoxide`, `fzf`, `bat`, `nix-index`, `yazi`, `ssh`, `mosh`, `nushell` and others, with
  `environment.corePackages` and `environment.defaultPackages`; nano and command-not-found stay off on a phone.
- NixOS's `networking.hostFiles`, `networking.stevenblack` (nixpkgs' own module: StevenBlack's ad and tracker
  blocklist, with `block` and `whitelist`) and `networking.proxy` (`default`, `httpProxy`, `httpsProxy`,
  `ftpProxy`, `rsyncProxy`, `allProxy`, `noProxy`): the files' lines go into the `/etc/hosts` of Nixup's
  services and shells (Android's apps keep Android's), and the proxy into every shell session, Nix's daemon and
  the phone's own builds' downloads.
- The shells as on NixOS, with nixpkgs' own modules: `environment.shellAliases`, `shellInit`, `loginShellInit`,
  `interactiveShellInit`, `localBinInPath`, `homeBinInPath`, `shells` and `profiles`, and `programs.bash`,
  `programs.zsh`, `programs.fish` and `programs.xonsh` with their options and plugins (bash's completion, zsh's
  autosuggestions and syntax highlighting, oh-my-zsh, ...). With Nix packages, `/etc/profile`, `/etc/bashrc`,
  `/etc/zshrc`, `/etc/fish` and `/etc/set-environment` are NixOS's own, the prompt is NixOS's (with the phone's name),
  and PATH is made of the profiles, as on NixOS: the user's own, `/etc/profiles/per-user/<user>`, root's, and
  `/run/current-system/sw` (`system.path`), with Android's commands after them. `users.defaultUserShell` is the
  users' shell (bash, as `programs.bash` makes it), and a user whose shell is zsh, fish or xonsh needs its
  `programs.<shell>.enable`, as on NixOS. Each user that logs in gets a `/run/user/<uid>` (`XDG_RUNTIME_DIR`).
  Without Nix packages the phone's shell stays Android's sh, with `environment.variables` and `extraInit`.

- The system's version, as on NixOS: each generation keeps its label (`system.nixos.label`, with
  `system.nixos.tags` and the nixpkgs version) and the config's commit (`system.configurationRevision`), which
  the app's Generations shows; `system.copySystemConfiguration = true;` keeps a copy of the configuration.nix
  with each generation. `nixos-version` tells them (`--revision`, `--configuration-revision`, `--json`),
  `/etc/os-release` and `/etc/lsb-release` are in Nixup's view, and `system.build.toplevel` is the profile.
- Scripts, as on NixOS: `system.activationScripts` run as root with each build and each start of the phone, in
  Nixup's view, before the services start (in the order their `deps` say; a dry run runs those with
  `supportsDryActivation`, with `NIXOS_ACTION=dry-activate`); `system.preSwitchChecks` run before a build changes
  anything, and one that fails stops it (`NIXOS_NO_CHECK=1` leaves them out); `system.userActivationScripts` run
  as each normal user when they change and at each start of the phone, through NixOS's `nixos-activation` unit. A script's `undo` (Nixup's own) runs once when it
  leaves the config and when Nixup is removed, so the phone gets back what it changed. NixOS's modules that have
  activation scripts build now.
- The phone builds its config by itself, as NixOS's `system.autoUpgrade`: at `dates` (with `randomizedDelaySec`,
  `fixedRandomDelay` and `persistent`) it builds again with everything at its newest (`upgrade = false`: as locked),
  the picked config (a git clone gets its new commits first) or `flake`, with `channel` for a configuration.nix
  alone, keeping the specialisation in use, and frees up space after (`runGarbageCollection`). Only on Wi-Fi unless
  `android.autoUpgrade.onlyOnUnmetered = false`, only while charging with `android.autoUpgrade.onlyWhileCharging`,
  and never on a low battery.
- `nix.buildMachines` with `nix.distributedBuilds`, as on NixOS: a package cache.nixos.org has not got (an overlay's,
  an unfree one) is built on those machines over ssh and sent back, where the build stopped at it before; the
  shells' builds go to them too (`/etc/nix/machines`).
- systemd timers' `FixedRandomDelay` works (it was taken and did nothing).
- Seraph is now called Nixup: the app (`dev.nixup.manager`), the Magisk module (`nixup`), its data
  (`/data/adb/nixup`), `nixup-rebuild`, `nixup-play-login`, `nixup-shell`, `nixup-backup`, and the flake
  (`github:by-architect/Nixup`, `nixup.lib.nixupSystem`, `nixupConfigurations`). Installing Nixup takes a
  Seraph install over, as Seraph took Andronix's: its data moves (with a link left at `/data/adb/seraph`, so
  paths a config names there still work), its module and app go, and its config stays where it is. Allow the
  Nixup app root when it asks. A flake made for Seraph (`seraph.lib.seraphSystem`, `seraphConfigurations`)
  and `androidApp = "dev.seraph.manager"` build as they are.
- Nix in the phone's shells (the app's terminal, ssh, `su -c nixup-shell`), as on NixOS: `nix shell nixpkgs#htop`,
  `nix run`, `nix profile add`, `nix-shell -p`, `nix-env -iA nixpkgs.htop`, `nix-build`, `nix-collect-garbage` and
  the rest, with the module's own Nix (nothing to download) and Nixup's store. `nixpkgs` there is the config's own
  (the registry's and `<nixpkgs>`, NixOS's `nixpkgs.flake.setFlakeRegistry` and `setNixPath`): the phone keeps it,
  and `nixup-rebuild` sends it once when the phone has not got it. What a user installs is on their PATH.
- NixOS's `nix.*` options do what they do on NixOS: `nix.enable`, `nix.package`, `nix.settings` and `nix.extraOptions`
  (`/etc/nix/nix.conf`; flakes on, the sandbox off, as Android's kernels lack its namespaces), `nix.registry`,
  `nix.nixPath`, `nix.channel.enable`, `nix.nrBuildUsers` (builds run as `nixbld` users with uids from 400 to 999),
  `nix.gc` and `nix.optimise` at their times. Nix's daemon runs for the other users that log in. `nix.sshServe` and
  `nix.checkConfig` are there too, so a NixOS module that sets them builds.
- A shell another program starts (`nix shell`'s, tmux's) keeps its PATH and gets the same prompt: `SHELL` is the shell
  itself, as on NixOS, and `/etc/bashrc` has the prompt.
- Free up space runs in Nixup's view, where the users' own Nix profiles are roots it keeps.
- Fixed: Nix's garbage collector (Free up space, `nix-collect-garbage`) could stop with "read of 65536 bytes: Out of
  memory" on an Android 4.x kernel, which refuses a big read of `/proc/sys/kernel/modprobe` and `poweroff_cmd`: those
  are empty in Nixup's view, as containers have them.
- `nixup-rebuild` sends what it sends of Nix's store compressed, to a module that takes it so.
- Home Manager, as on NixOS: import its NixOS module (a flake's `home-manager.nixosModules.home-manager`, or
  `<home-manager/nixos>` in a configuration.nix alone, which the phone then fetches as it fetches nixpkgs) and
  `home-manager.users.<name>` has all of Home Manager's options. Each user's Home Manager runs as the user at each
  build that changes it and at the phone's start (`home-manager-<name>` in the Services tab), linking its files into
  its home; the services it makes (every `services.*` module's, `systemd.user.services` of one's own) run as the
  user's own (`user-<name>-<service>`); its packages are the user's profile. Taking a user out takes Home Manager's
  links out of its home again. No desktop session or D-Bus session bus on a phone: what needs one is not started, or
  fails; the manual page, the desktop's file types and the locales but UTF-8's are off by default.
- A user's packages (`users.users.<name>.packages`) are its own profile now, `/etc/profiles/per-user/<name>`, on its
  `PATH` before `environment.systemPackages`, as on NixOS; before, they were on everyone's `PATH`. A user's own shell
  (`users.users.<name>.shell`, bash or zsh) gets the same `PATH`, time zone and `environment.extraInit` as Nixup's
  login shell (`/etc/profile`, `/etc/zshenv`).
- Users' own units (`systemd.user`) run as each user's `systemd --user` would: `%t` is its `/run/user/<uid>`, `%S`,
  `%C`, `%E` and `%L` are in its home, and so are its `StateDirectory`, `CacheDirectory`, `LogsDirectory` and
  `ConfigurationDirectory` (`RuntimeDirectory` in `/run/user/<uid>`); its sockets and their folders are the user's,
  and the user's profile is on their `PATH`. A unix socket's path may start with a specifier (`%t/foo.sock`).
- Sockets are handed over with their names (`FileDescriptorName`, else their socket unit's, as `LISTEN_FDNAMES`), and
  a unix socket's folder is made with its `DirectoryMode`.
- The build's messages name a user's unit where the config gives it (`home-manager.users.<name>.systemd.user...`).
- Fixed: a specifier only known when a unit runs (its user's `%h`, `%U`, `%G`, a user's unit's `%t`) stayed empty in a
  command's single quotes (`bash -c 'echo %t'`); it is filled in there too, as systemd fills specifiers in everywhere.
- Specialisations, as on NixOS: `specialisation.<name>.configuration` makes other versions of the config (a
  "work" or "travel" one) with each build. ⋮ → Specialisations switches to one, or back to the config itself,
  without building again; `build.sh` and `nixup-rebuild` take `--specialisation <name>`. The phone keeps the
  one picked, across restarts too, until a build picks another, and each generation remembers its own.
- NixOS's own service modules run on the phone as they are, from your nixpkgs: `services.ntfy-sh.enable = true;`
  runs nixpkgs' module, with its options, its user and its settings file, as on NixOS. The 67 Nixup has tried
  (AdGuard Home, Caddy, cron, Forgejo, Gitea, Gotify, Grafana, harmonia, Jellyfin, Navidrome, nginx, ntfy, Redis,
  Sonarr, Tor, Transmission, Uptime Kuma, Vaultwarden, ...) are listed in nix/nixos.nix (PostgreSQL is not: it needs
  shared memory Android's kernel has not got);
  another can be added as on NixOS, `imports = [ "${modulesPath}/services/…" ];`.
- Services can run as their own user (`User`, `Group`, `DynamicUser`, as NixOS's modules give them): a user with
  no uid gets one from 400 to 999, which the phone keeps. Android lets those on the network; one in the
  `media_rw` group (`serviceConfig.SupplementaryGroups = [ "media_rw" ];`) gets the shared storage too (your
  files and SD cards, as `/storage` and `/sdcard`), in its own view of the phone.
- Timers, as on NixOS: a service's `startAt`, and `systemd.timers` with `OnCalendar` (systemd's calendar
  events: "daily", "Mon..Fri 07:30", "*:0/15", with a time zone or the phone's), `OnBootSec`, `OnActiveSec`,
  `OnUnitActiveSec`, `OnUnitInactiveSec`, `Persistent` (a time missed while the phone was off comes at its
  start) and `RandomizedDelaySec`. The Services tab shows when each service a timer starts runs next.
- Sockets, as on NixOS (`systemd.sockets`, TCP, UDP and unix): the phone opens them and hands them to their
  service as systemd does, so nixpkgs' modules that use them run as they are (harmonia, a binary cache of the
  phone's store, is on the list), and a port below 1024 can be a service user's. A service nothing else wants
  starts at its first connection (the Services tab says "listening" until then); with `Accept = true` each
  connection goes to a run of its own of the socket's template service.
- Templates (`foo@`) and their instances (`foo@bar`, `%i`), path units (`systemd.paths`), units in packages
  (`systemd.packages`: static-web-server's runs as nixpkgs' module makes it) and users' own units
  (`systemd.user.services`, `timers`, `sockets`, `paths`: each normal user's, run as that user), as on NixOS.
- `Type = "notify"`: a service counts as started once it says `READY=1`, and with `WatchdogSec` it is ended and
  started again (`Restart = "on-watchdog"`) when it stops saying `WATCHDOG=1`.
- `LoadCredentialEncrypted` and `SetCredentialEncrypted`, as `systemd-creds encrypt` makes them on the phone
  (with its own key); Nixup's `/etc` has a `machine-id`, as NixOS's has.
- A unit's `Environment` values have their specifiers filled in (`%S`, `%N`, `%d`, `%h`, ...), as systemd does.
- A module that adds `boot.kernelModules` (nginx's `tls`) builds, with a warning: the phone loads no modules.
- Fixed: stopping a service took up to minutes on a phone with many processes (each of them was looked at with
  a command of its own); one look at all of them takes a moment now.
- Fixed: a build did not wait for a forking service to have started before it said so (it looked before the
  service's loop was there); a notify service that never says it is ready is told as not started.
- Fixed: services kept the DNS servers of the network of the last build or start, which another network (mobile
  data, another Wi-Fi) may not answer: Nixup's /etc/resolv.conf now follows the phone's network.
- Fixed: services started with whatever environment the build or start came with (its TMPDIR, HOSTNAME, TERM, an
  adb session's ...); they start clean now, as systemd starts them, with Android's own variables and root's HOME
  for root's units.
- Fixed: a notify service that restarted could kill another process (one with the pid its listener had before).
- Fixed: a service's stop or restart could end another service's processes: Android gives a new view the number a
  view that just ended had, and the stopped service's loop took the new one's processes for its own. Each view
  now keeps its number while its loop looks.
- Fixed: the "runs again" notice could come for a service stopped in the minute after it had stopped by itself,
  once another process had its old process number.
- Fixed: the app was not told when a service stopped by itself, or when it ran again.
- A unit's own Environment wins over its user's HOME and USER (NixOS's couchdb), /etc/hosts has the phone's own
  name, an empty LoadCredential is taken as systemd takes it (ttyd), an empty environment.etc file is written
  (homepage-dashboard), a command's lines continued with a backslash are one (prometheus), and a user's socket
  with Accept = true runs its connections as that user.
- Nixup's /etc has every time zone of Android's data in /etc/zoneinfo, as NixOS has, with /etc/localtime a link to
  the phone's (programs know its name by it: .NET's Kavita needs it) and TZDIR set for the services.
- A command's @ prefix (another name for the program, as systemd takes it: NixOS's Apache) works.
- networking.hostName not set is "localhost" for NixOS's modules that read a name (Apache's ServerName); the phone
  keeps its own name, as before.
- Services and the jobs cron starts have the phone's time zone in Nixup's `/etc/localtime`, out of Android's
  time zone data, so summer time comes too; cron's jobs ran in UTC before.
- `system.path` (cron's `PATH`) is made by Nix itself, without Perl.
- A service nothing starts at boot (one a timer starts, or one to start by hand) is in the Services tab after
  each build, ready to start.
- `BindPaths` are mounted before the service goes into its `WorkingDirectory`, as systemd does.
- More of systemd's unit settings: `EnvironmentFile`, `LoadCredential` and `SetCredential`, the cache, logs and
  configuration folders, `BindPaths`, conditions and `ExecCondition`, `Type = "forking"` with `PIDFile`,
  `KillMode`, `TimeoutStopSec`, `ExecStopPost` (and `preStop`, `postStop`), every `Restart` value, `UMask` and
  `Limit…`, targets of `systemd.targets`. What only hardens a service on NixOS (`ProtectSystem`, ...) is taken
  and does nothing.
- `environment.etc`, `systemd.tmpfiles`, `networking.hosts`, `networking.nameservers` and
  `environment.variables`, as on NixOS, in Nixup's view of the phone.
- The phone builds the small files a config makes (a service's settings file, a script) from what
  cache.nixos.org has, and a build on a PC sends their recipes along; a package itself it still never builds.
  YAML, JSON and TOML files are written without Python.
- sshd stops alone when a build restarts it, as on NixOS: SSH sessions, and a build run through one, stay.
- Fixed: a build started over adb or ssh through Nixup's own Tailscale stopped halfway when it restarted
  Tailscale (the session went, and the build with it): a build now goes on to its end, with all of it in
  its log, when the session it came from is cut.
- Nixup's view has `/var/run`, `/var/lock` (as `/run`'s) and `/var/tmp`, as NixOS has them; cron needs them.
- After this update every running service starts again once.
- A module that tells NixOS from a phone by `options ? networking.firewall` now finds that option on the phone
  too (NixOS's modules need it): check `options ? android` instead.
- Services that need the phone's storage say so with `unitConfig.RequiresMountsFor`, as on NixOS: they
  start once it is mounted (an SD card, or what Android mounts once the phone is first unlocked), waiting
  quietly till then instead of failing; one whose storage came after its start starts again in a fresh
  view, and one whose storage goes away stops to wait for it. The Services tab shows "waiting".
- `serviceConfig.SuccessExitStatus` and `RestartForceExitStatus`, as on NixOS: Syncthing restarting itself
  (exit 3) is no longer told as a failure.
- `services.syncthing` waits for its folders' storage, so it no longer fails at every start before the
  phone is unlocked.
- Fixed: an automatic backup at the phone's first unlock failed ("the repository cannot be opened"): it
  checked that the SD card's folder was there, which Android makes before it mounts the card. It now
  checks the card is mounted, and waits for the next check when it is not.
- `users.users.<name>.androidApp`: a user with the uid of an app, looked up on the phone at each build (an app
  installed again gets another one). `androidApp = "dev.nixup.manager"` is Nixup's own: an SSH login that
  may use su as Nixup may, with no other app (no Termux).
- A normal user's home is `/home/<name>` (Nixup's own folder), made by the build and there from the phone's
  start on, before it is first unlocked.
- `terminal.font` and `terminal.colors`, as Nix-on-Droid has them: the app's terminal takes its font (a file
  of a Nix package) and colors from the config each time it opens.
- Services get Android's time zone, so the dates their programs write are the phone's local time.
- Fixed: the phone took four minutes longer to start, also after the module was removed. Android relabels every
  file of /data/adb at each start, and Nixup's Nix store has hundreds of thousands; now each start runs
  restorecon on Nixup's folder, which leaves the mark that makes Android skip it from then on.
- A newer Nixup whose services' view changed starts the running services again in the new one (sshd saw
  no /home until it did).
- Apps from Google Play: `android.packages.playstore = [ "com.whatsapp" ];` installs and updates
  them as Aurora Store would download them, with the split APKs made for the phone; no Play Store
  or GApps needed. A Google account signs in once in the app (⋮ → Google Play account), which then
  shows its token to copy or save, or `android.playstore.tokenFile` gives the token, which
  `nixup-play-login` makes on a PC. A build that lists such apps stops first when no account is
  signed in, or Google refuses its token, and says to sign in.
- Cloning a git repository has a "Clone its submodules too" box (off by default); pulls and builds
  then include them, and a private submodule uses the same token.
- The app is installed for the phone's owner only, and removed from app clones and work profiles,
  where Magisk refuses root without asking; opened there, it says so.
- Search: the Updates, Backups, Services and Logs tabs have a search box that keeps the rows with
  what is typed in them (any case, with or without accents: "sifre" finds "şifre"), each log has
  one that keeps the lines with it, and Back up… → Apps finds an app by its name or package.
- Fixed: picking up broken-off runs could start a second build next to one still running, which
  deleted its new profile, so the build applied but the phone kept the old profile. A run still
  going is now left alone, a second build, update or backup waits for the first, and each run has
  its own temporary profile.
- Fixed: a flake with no `nixupConfigurations` now says so instead of failing with no reason.
- Tailscale says when its auth key is refused, instead of an empty login link.
- Fixed: a service runs again when a secret file it reads changes (a new Tailscale auth key, an
  ssh host key); before, only a change to the service itself restarted it, so a new key waited
  for a restart.
- Fixed: restoring an app that is no longer on the phone failed with "not installed" when its
  backup held only its data (as with `android.backup.defaults.apk = false`). Such an app now comes
  back whole: its APK first, from that backup or another backup of it, then its data; when no backup
  has its APK, the restore says "app not found". `nixup-backup restore <app>` takes the newest
  backup with data, not a newer one that holds only the APK.
- Fixed: pulling a config cloned with its submodules failed ("not our ref") when a commit on another
  branch named a submodule commit its server no longer has. A pull now brings the submodules only to
  the commits the pulled branch names.

## 0.1.0 (2026-11-03)

The first public release.

- A Magisk module and an app that make an Android phone match a Nix config, as NixOS does: a
  `configuration.nix`, or a `flake.nix` with `nixupConfigurations`, kept wherever you like or
  cloned from a git repository, built on the phone or on a PC (`nixup-rebuild`, over adb or ssh).
- Apps from F-Droid repositories and from their developers' releases (GitHub, GitLab, Codeberg),
  installed and kept up to date, with notifications; apps that came with the phone removed.
- Each app's settings (permissions, battery, network, special access, language, defaults) and the
  phone's own settings (Android's, LineageOS's), each given back when it leaves the config.
- Generations, as NixOS has them: dry runs, upgrades, and going back to an earlier one.
- Nix packages on the phone from cache.nixos.org, `environment.systemPackages` and a terminal with
  them, and services with NixOS's names: `systemd.services`, `services.tailscale`,
  `services.syncthing`, `services.openssh`, `services.snapserver` and `services.snapclient`, plus
  `android.adb` and `users.users`.
- Backups of apps (APK and data) and folders into a restic repository, made by the phone itself,
  checked before they start, and restored by hand from the app (or by a build, when turned on).
- Notifications for builds, updates, backups, services and Tailscale's connection, a Logs tab, and
  work that a restart broke off picked up again.
- Updates of Nixup itself: from the Magisk app, or from Nixup's Updates tab.
