package com.wmc.mediacenter.screensaver

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * S36 — the on/off switch behind MCLauncher Settings' "Photo wall
 * screensaver" row.
 *
 * WHY COMPONENT ENABLE/DISABLE AND NOT A PLAIN PREFERENCE. An app cannot
 * choose which screensaver Android actually runs — that lives behind
 * WRITE_SECURE_SETTINGS and belongs to the system's own picker (Settings >
 * System > Ambient mode on this box; see SettingsScreen's "Set as screen
 * saver" row, which is the most we can do toward turning it ON). What an
 * app CAN always do is enable or disable its own components, and a disabled
 * DreamService drops out of that picker entirely — if it happened to be the
 * selected one, the system falls back to its own default.
 *
 * That makes this a real off switch rather than a cosmetic preference: with
 * it off, MCLauncher stops offering a screensaver at all and whatever else
 * the user picked takes over. A preference the dream merely READ would be
 * strictly worse — the dream would still be listed and still be selectable,
 * and "off" would mean it starts and then immediately dismisses itself,
 * which looks like a crash and can leave the system retrying.
 *
 * DONT_KILL_APP is not optional here: without it the framework kills our
 * process to apply the change, which would tear the launcher down
 * underneath the user at the exact moment they toggled the row.
 */
object ScreensaverAvailability {

    /**
     * COMPONENT_ENABLED_STATE_DEFAULT means "whatever the manifest says",
     * and S36 made the manifest declare this service `enabled="false"` so a
     * fresh install offers nothing and disturbs nothing. So only an
     * EXPLICIT enable counts as on — DEFAULT is off here, which is the
     * opposite of the usual reading and the reason this isn't a one-liner
     * inequality against DISABLED.
     */
    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(componentOf(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Safe to call when already in the requested state — the framework no-ops. */
    fun setEnabled(context: Context, enabled: Boolean) {
        try {
            context.packageManager.setComponentEnabledSetting(
                componentOf(context),
                if (enabled) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                } else {
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                },
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            // Never let a settings toggle take the launcher down; the stored
            // preference stays authoritative and gets reapplied next start.
            Log.w(TAG, "Could not set screensaver component enabled=$enabled", e)
        }
    }

    private fun componentOf(context: Context) =
        ComponentName(context.applicationContext, PhotoWallDreamService::class.java)

    private const val TAG = "MCLauncherScreensaver"
}
