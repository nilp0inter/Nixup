# Runs once while Magisk installs the module (sourced by Magisk's installer): makes
# Nix runnable, installs the app, and starts the phone off with the default profile
# (which manages nothing). The config is the user's, wherever they keep it: the app's
# Config picks it, and Nixup makes no folder for it.
[ "$API" -ge 28 ] || abort "! Needs Android 9 or newer (this phone has API $API)."
[ "$ARCH" = arm64 ] || abort "! Needs a 64-bit ARM phone (this one is $ARCH): Nix is built for it."
# What it makes is root's to write (Magisk's own umask is 0; see common.sh).
umask 022

# Nixup was called Seraph, and Andronix before that. An install of either is taken over,
# so what it kept goes on: the values to give back (original/), the apps it installed,
# the generations, the indexes, the secrets and the homes. Its module goes without its
# uninstall script running (that would give every setting back and remove its apps:
# Nixup keeps managing them), and so does its app. Before common.sh, which makes Nixup's
# own data folder.
if [ -d /data/adb/seraph ] && [ ! -L /data/adb/seraph ] && [ ! -e /data/adb/nixup ]; then
  # A link is left where it was: configs name files in it (secrets: /data/adb/seraph/secrets/...),
  # and the services still running from it go on until the restart.
  mv /data/adb/seraph /data/adb/nixup && ln -s nixup /data/adb/seraph && ui_print "- Took over Seraph's data"
fi
if [ -d /data/adb/andronix ] && [ ! -e /data/adb/nixup ]; then
  mv /data/adb/andronix /data/adb/nixup && ui_print "- Took over Andronix's data"
fi
# Seraph's config stays where it is (the app's Config picked it, and Nixup reads it from there);
# Andronix's was in a folder of its own, which becomes Nixup's.
if [ -d /sdcard/Andronix ] && [ ! -e /sdcard/Nixup ]; then
  mv /sdcard/Andronix /sdcard/Nixup
  for name in phone.nix phone.nix.old; do
    [ -f "/sdcard/Nixup/$name" ] && mv "/sdcard/Nixup/$name" "/sdcard/Nixup/configuration${name#phone}"
  done
  ui_print "- Took over the config: /sdcard/Nixup/configuration.nix"
fi
for old in seraph andronix; do
  for dir in /data/adb/modules/$old /data/adb/modules_update/$old; do
    [ -d "$dir" ] && rm -rf "$dir" && ui_print "- Removed the old module ($dir)"
  done
  rm -f /data/adb/service.d/$old-restore.sh
done
pm uninstall dev.seraph.manager < /dev/null > /dev/null 2>&1 \
  && ui_print "- Removed the Seraph app: allow the Nixup app root when it asks"
pm uninstall dev.andronix.manager < /dev/null > /dev/null 2>&1 && ui_print "- Removed the Andronix app"

set_perm "$MODPATH/bin/nix" 0 0 0755
# What tells the app about events, for the services too ($NIXUP_NOTIFY).
set_perm "$MODPATH/notify.sh" 0 0 0755
# nixup-shell, nixup-rebuild, nixup-backup, nixup-version (and nixos-version, its NixOS name), in /system/bin once
# Magisk mounts the module (from the next start).
for name in nixup-shell nixup-rebuild nixup-backup nixup-version nixos-version; do
  set_perm "$MODPATH/system/bin/$name" 0 0 0755
done
# Nix's other commands are nix itself by another name (it acts as the one it is run by): the module's scripts run
# nix-instantiate and nix-store, and nix-channel finds nix-env next to it (the shells' are in /etc/nixup/bin).
for name in nix-build nix-channel nix-collect-garbage nix-copy-closure nix-daemon nix-env nix-hash nix-instantiate \
  nix-prefetch-url nix-shell nix-store; do
  ln -sf nix "$MODPATH/bin/$name"
done

# Streamed in, so the installer does not need to read the module's folder.
ui_print "- Installing the app"
# For the phone's owner only: Magisk gives root to the owner's apps (its default), and an app clone,
# work profile or other user would only be refused it, without asking.
install_app() { pm install -r --user 0 -S "$(stat -c %s "$MODPATH/app.apk")" < "$MODPATH/app.apk" 2>&1; }
said=$(install_app)
case "$said" in
  *Success*) ;;
  *INSTALL_FAILED_UPDATE_INCOMPATIBLE* | *INSTALL_FAILED_VERSION_DOWNGRADE* | *"signatures do not match"*)
    # The app there is signed with another key (a build of one's own, or a release after one), or
    # is newer: it goes first. It keeps nothing of its own (Nixup's data is in /data/adb/nixup);
    # the root solution asks once more to let it use root, as for any app installed anew.
    ui_print "- The app there is signed with another key, or is newer: replacing it"
    pm uninstall dev.nixup.manager < /dev/null > /dev/null 2>&1
    said=$(install_app)
    ui_print "- Nixup's app is a new app to the root solution now: allow it root again when it asks" ;;
esac
case "$said" in
  *Success*) ;;
  *) ui_print "! The app did not install ($said): install app.apk from the module's folder by hand." ;;
esac
# Out of the phone's other users (an app clone's, a work profile's), where an earlier install put it.
for user in $(pm list users 2> /dev/null | sed -n 's/.*UserInfo{\([0-9]*\):.*/\1/p'); do
  [ "$user" = 0 ] && continue
  pm list packages --user "$user" dev.nixup.manager 2> /dev/null | grep -qx 'package:dev.nixup.manager' || continue
  pm uninstall --user "$user" dev.nixup.manager < /dev/null > /dev/null 2>&1 \
    && ui_print "- Removed the app from user $user (an app clone or work profile): root is the owner's"
done
# Its notifications say how installs and updates go.
pm grant dev.nixup.manager android.permission.POST_NOTIFICATIONS > /dev/null 2>&1

# A profile built on the phone stays; only the first time does the default one go in.
MODDIR=$MODPATH
BY=install
. "$MODPATH/common.sh"
[ -f "$PROFILE" ] || cp -f "$MODPATH/profile.json" "$PROFILE"
source=$(config_source)
if [ -n "$source" ]; then
  ui_print "- Config: $source (the app's Build applies it)"
else
  ui_print "- No config yet: in the Nixup app, Config picks your configuration.nix or flake.nix (New makes one)"
fi
# The app schedules its update check (a new app has none yet).
tell_app schedule
