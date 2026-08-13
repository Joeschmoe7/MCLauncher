# MCLauncher one-time device setup (Windows PowerShell).
#
# Installs the APK (if it can find one) and grants the permissions Android TV
# gives no UI for. Everything here is idempotent — re-running it is harmless.
#
#   .\scripts\setup-device.ps1                 # device already attached
#   .\scripts\setup-device.ps1 192.168.1.50    # connect over the network first
#
# Grants survive reboots but NOT an uninstall, so re-run after reinstalling.

param(
    [string]$DeviceIp = "",
    [string]$Apk = "app\build\outputs\apk\release\app-release.apk"
)

$Package  = "com.wmc.mediacenter"
$PhotoDir = "/sdcard/MCLauncher/Screensaver"

function Say  ($m) { Write-Host ""; Write-Host $m -ForegroundColor White }
function Ok   ($m) { Write-Host "  ok    $m" -ForegroundColor Green }
function Warn ($m) { Write-Host "  warn  $m" -ForegroundColor Yellow }
function Fail ($m) { Write-Host "  fail  $m" -ForegroundColor Red }

$adb = (Get-Command adb -ErrorAction SilentlyContinue)
if ($null -eq $adb) {
    $sdkAdb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
    if (Test-Path $sdkAdb) {
        $adbPath = $sdkAdb
    } else {
        Fail "adb not found on PATH or in the default SDK location."
        exit 1
    }
} else {
    $adbPath = $adb.Source
}

if ($DeviceIp -ne "") {
    if ($DeviceIp -notmatch ":") { $DeviceIp = "$DeviceIp:5555" }
    Say "Connecting to $DeviceIp"
    & $adbPath connect $DeviceIp | Out-Null
}

$devices = & $adbPath devices | Select-Object -Skip 1 | Where-Object { $_ -match "\sdevice$" }
if (-not $devices) {
    Fail "No device attached. Pass an IP, or plug the box in and enable USB debugging."
    exit 1
}

Say "Installing"
if (Test-Path $Apk) {
    & $adbPath install -r $Apk | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Ok "installed $Apk"
    } else {
        Fail "install failed — is a differently-signed build already on the box?"
        exit 1
    }
} else {
    Warn "no APK at $Apk (pass -Apk <path> to install one)"
    $installed = & $adbPath shell pm list packages | Select-String $Package
    if (-not $installed) {
        Fail "$Package is not installed and there is nothing to install. Stopping."
        exit 1
    }
    Ok "$Package already installed — granting permissions only"
}

Say "Granting permissions Android TV has no settings UI for"

# Cold-boot self-start. Without this the launcher cannot bring itself to the
# foreground after a reboot (background activity launch is blocked).
& $adbPath shell appops set $Package SYSTEM_ALERT_WINDOW allow | Out-Null
if ($LASTEXITCODE -eq 0) { Ok "SYSTEM_ALERT_WINDOW  — take over Home after a reboot" }
else { Fail "SYSTEM_ALERT_WINDOW could not be granted" }

# Backup/restore writes a fixed public path so backups survive an uninstall,
# and the screensaver reads its photo folder from /sdcard. Both need this.
& $adbPath shell appops set $Package MANAGE_EXTERNAL_STORAGE allow | Out-Null
if ($LASTEXITCODE -eq 0) { Ok "MANAGE_EXTERNAL_STORAGE — backup/restore, and screensaver photos" }
else { Fail "MANAGE_EXTERNAL_STORAGE could not be granted" }

# Optional. Lets the in-app screensaver toggle select itself as the system
# screensaver — the only way to do that at all on Google TV, whose Ambient
# mode picker never lists third-party screensavers.
& $adbPath shell pm grant $Package android.permission.WRITE_SECURE_SETTINGS | Out-Null
if ($LASTEXITCODE -eq 0) {
    Ok "WRITE_SECURE_SETTINGS — optional; lets MCLauncher set its own screensaver"
} else {
    Warn "WRITE_SECURE_SETTINGS could not be granted (optional)."
    Warn "The screensaver still works, but you'll have to select it yourself."
}

Say "Making MCLauncher the home screen"
& $adbPath shell cmd package set-home-activity "$Package/.MainActivity" | Out-Null
if ($LASTEXITCODE -eq 0) {
    Ok "Home button now opens MCLauncher"
} else {
    Warn "could not set the home activity"
    Warn "press Home on the TV and choose MCLauncher (Always) instead"
}

# The Google TV launcher outranks any third-party app in the system's home
# selection, so after a wake-from-sleep that killed our process it reappears.
# This watchdog notices that and returns to MCLauncher. See INSTALL.md 6c.
Say "Enabling the home watchdog"
$watchdog = "$Package/$Package.HomeWatchdogService"
$current = (& $adbPath shell settings get secure enabled_accessibility_services) -join "" -replace "`r|`n", ""
if ($current -split ":" -contains $watchdog) {
    Ok "already enabled"
} else {
    # APPEND — never overwrite. This setting is one colon-separated list for
    # the whole device, so blindly writing our service into it switches off
    # every other accessibility service the user has, TalkBack included.
    if ([string]::IsNullOrWhiteSpace($current) -or $current -eq "null") {
        $updated = $watchdog
    } else {
        $updated = "$current`:$watchdog"
    }
    & $adbPath shell settings put secure enabled_accessibility_services $updated | Out-Null
    & $adbPath shell settings put secure accessibility_enabled 1 | Out-Null
    if ($LASTEXITCODE -eq 0) {
        Ok "watchdog on — returns to MCLauncher if Google TV Home takes over"
    } else {
        Warn "could not enable the watchdog; turn it on under Settings > Accessibility"
    }
}

Say "Creating the screensaver photo folder"
& $adbPath shell mkdir -p $PhotoDir | Out-Null
if ($LASTEXITCODE -eq 0) {
    Ok $PhotoDir
    Write-Host "        copy photos in with:  adb push yourphotos\*.jpg $PhotoDir/"
} else {
    Warn "could not create $PhotoDir — make it yourself before using the screensaver"
}

Say "Done"
Write-Host @"
  Next:
    1. Press Home on the TV and choose MCLauncher (Always).
    2. For the screensaver: MCLauncher > Settings > Photo wall screensaver > On.
       With WRITE_SECURE_SETTINGS granted above, that is the whole setup.

  Nothing here changed your screensaver — MCLauncher ships with it switched off.
"@
