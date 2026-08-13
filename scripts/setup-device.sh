#!/usr/bin/env bash
#
# MCLauncher one-time device setup.
#
# Installs the APK (if it can find one) and grants the permissions Android TV
# gives no UI for. Everything here is idempotent — re-running it is harmless.
#
#   ./scripts/setup-device.sh                  # device already attached
#   ./scripts/setup-device.sh 192.168.1.50     # connect over the network first
#
# Grants survive reboots but NOT an uninstall, so re-run after reinstalling.
#
set -uo pipefail

# Git Bash / MSYS rewrites POSIX-looking paths in `adb shell` arguments into
# Windows paths (/sdcard/... becomes C:/Program Files/Git/sdcard/...). Without
# this the mkdir below silently creates the wrong thing.
export MSYS_NO_PATHCONV=1

PACKAGE="com.wmc.mediacenter"
DREAM="$PACKAGE/.screensaver.PhotoWallDreamService"
PHOTO_DIR="/sdcard/MCLauncher/Screensaver"
APK="${APK:-app/build/outputs/apk/release/app-release.apk}"

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()   { printf '  \033[32mok\033[0m    %s\n' "$*"; }
warn() { printf '  \033[33mwarn\033[0m  %s\n' "$*"; }
fail() { printf '  \033[31mfail\033[0m  %s\n' "$*"; }

if ! command -v adb >/dev/null 2>&1; then
  fail "adb not found on PATH. Install Android platform-tools and try again."
  exit 1
fi

if [ $# -ge 1 ]; then
  say "Connecting to $1"
  case "$1" in
    *:*) TARGET="$1" ;;
    *)   TARGET="$1:5555" ;;
  esac
  adb connect "$TARGET" || { fail "could not connect to $TARGET"; exit 1; }
fi

if [ -z "$(adb devices | sed '1d' | grep -w device)" ]; then
  fail "No device attached. Pass an IP, or plug the box in and enable USB debugging."
  exit 1
fi

say "Installing"
if [ -f "$APK" ]; then
  if adb install -r "$APK" >/dev/null 2>&1; then
    ok "installed $APK"
  else
    fail "install failed — is a differently-signed build already on the box?"
    exit 1
  fi
else
  warn "no APK at $APK (set APK=/path/to.apk to install one)"
  if ! adb shell pm list packages | grep -q "$PACKAGE"; then
    fail "$PACKAGE is not installed and there is nothing to install. Stopping."
    exit 1
  fi
  ok "$PACKAGE already installed — granting permissions only"
fi

say "Granting permissions Android TV has no settings UI for"

# Cold-boot self-start. Without this the launcher cannot bring itself to the
# foreground after a reboot (background activity launch is blocked).
if adb shell appops set "$PACKAGE" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1; then
  ok "SYSTEM_ALERT_WINDOW  — take over Home after a reboot"
else
  fail "SYSTEM_ALERT_WINDOW could not be granted"
fi

# Backup/restore writes a fixed public path so backups survive an uninstall,
# and the screensaver reads its photo folder from /sdcard. Both need this.
if adb shell appops set "$PACKAGE" MANAGE_EXTERNAL_STORAGE allow >/dev/null 2>&1; then
  ok "MANAGE_EXTERNAL_STORAGE — backup/restore, and screensaver photos"
else
  fail "MANAGE_EXTERNAL_STORAGE could not be granted"
fi

# Optional. Lets the in-app screensaver toggle select itself as the system
# screensaver — the only way to do that at all on Google TV, whose Ambient
# mode picker never lists third-party screensavers.
if adb shell pm grant "$PACKAGE" android.permission.WRITE_SECURE_SETTINGS >/dev/null 2>&1; then
  ok "WRITE_SECURE_SETTINGS — optional; lets MCLauncher set its own screensaver"
else
  warn "WRITE_SECURE_SETTINGS could not be granted (optional)."
  warn "The screensaver still works, but you'll have to select it yourself."
fi

say "Making MCLauncher the home screen"
if adb shell cmd package set-home-activity "$PACKAGE/.MainActivity" >/dev/null 2>&1; then
  ok "Home button now opens MCLauncher"
else
  warn "could not set the home activity"
  warn "press Home on the TV and choose MCLauncher (Always) instead"
fi

# The Google TV launcher outranks any third-party app in the system's home
# selection, so after a wake-from-sleep that killed our process it reappears.
# This watchdog notices that and returns to MCLauncher. See INSTALL.md 6c.
say "Enabling the home watchdog"
WATCHDOG="$PACKAGE/$PACKAGE.HomeWatchdogService"
CURRENT="$(adb shell settings get secure enabled_accessibility_services 2>/dev/null | tr -d '\r\n')"
if [ "$CURRENT" = "$WATCHDOG" ] || case "$CURRENT" in *":$WATCHDOG"*|"$WATCHDOG:"*) true ;; *) false ;; esac; then
  ok "already enabled"
else
  # APPEND — never overwrite. This setting is one colon-separated list for the
  # whole device, so blindly `put`-ing our service into it switches off every
  # other accessibility service the user has, TalkBack included.
  if [ -z "$CURRENT" ] || [ "$CURRENT" = "null" ]; then
    UPDATED="$WATCHDOG"
  else
    UPDATED="$CURRENT:$WATCHDOG"
  fi
  if adb shell settings put secure enabled_accessibility_services "$UPDATED" >/dev/null 2>&1 &&
     adb shell settings put secure accessibility_enabled 1 >/dev/null 2>&1; then
    ok "watchdog on — returns to MCLauncher if Google TV Home takes over"
  else
    warn "could not enable the watchdog; turn it on under Settings > Accessibility"
  fi
fi

say "Creating the screensaver photo folder"
if adb shell mkdir -p "$PHOTO_DIR" >/dev/null 2>&1; then
  ok "$PHOTO_DIR"
  echo "        copy photos in with:  adb push yourphotos/*.jpg $PHOTO_DIR/"
else
  warn "could not create $PHOTO_DIR — make it yourself before using the screensaver"
fi

say "Done"
cat <<'NEXT'
  Next:
    1. Press Home on the TV and choose MCLauncher (Always).
    2. For the screensaver: MCLauncher > Settings > Photo wall screensaver > On.
       With WRITE_SECURE_SETTINGS granted above, that is the whole setup.

  Nothing here changed your screensaver — MCLauncher ships with it switched off.
NEXT
