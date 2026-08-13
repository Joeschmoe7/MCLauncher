package com.wmc.mediacenter.screensaver

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * S36 — actually selects (or deselects) the photo wall as the system's
 * screensaver, when the app has been granted WRITE_SECURE_SETTINGS over adb.
 *
 * WHY THIS EXISTS. [ScreensaverAvailability] can only make the dream
 * *available*; choosing which screensaver runs is the system's call. On plain
 * Android TV the user can finish the job in Settings > Device Preferences >
 * Screen saver. On Google TV they cannot: that build's Ambient mode "Source"
 * list only ever shows Google's own options and never third-party dreams, no
 * matter how correctly registered (NOTES.md section 9). The underlying
 * mechanism still works, though — `DreamManagerService` reads
 * `Settings.Secure.screensaver_components`, and writing it directly is
 * confirmed to launch our dream. That write needs WRITE_SECURE_SETTINGS.
 *
 * WRITE_SECURE_SETTINGS is signature|privileged|**development**, and it is
 * that last flag that lets `adb shell pm grant` hand it to a normal sideloaded
 * app. So this is opt-in by construction: installing MCLauncher can never
 * acquire it, and without the grant every method here degrades to "no".
 *
 * Everything is best-effort and never throws — a SecurityException just means
 * the grant is missing, which is the normal case, and the caller falls back to
 * showing the manual adb commands.
 */
object ScreensaverSelection {

    /** True once `pm grant ... WRITE_SECURE_SETTINGS` has been run for this install. */
    fun canSelect(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    /** True when the system's current screensaver is our dream. */
    fun isSelected(context: Context): Boolean =
        currentComponents(context)?.contains(flattenedComponent(context)) == true

    /**
     * Outcome of an attempted [select]. [previousComponents] is whatever the
     * system was pointing at beforehand — worth persisting, so [deselect] can
     * hand the slot back to it rather than just clearing it. Null previous is
     * a normal value (nothing was selected), NOT an error, which is why
     * success is reported separately.
     */
    data class SelectionResult(val selected: Boolean, val previousComponents: String?)

    /** Points the system at our dream and makes sure screensavers are switched on. */
    fun select(context: Context): SelectionResult {
        if (!canSelect(context)) return SelectionResult(selected = false, previousComponents = null)
        return try {
            // Never record OURSELVES as the thing to hand back to. The value
            // can already point at us — a leftover from a previous enable, or
            // from someone setting it by hand — and remembering that would
            // make [deselect] "restore" a pointer to the dream it has just
            // withdrawn, leaving the system aimed at a disabled component.
            val previous = currentComponents(context)
                ?.takeIf { !it.contains(flattenedComponent(context)) }
            Settings.Secure.putString(
                context.contentResolver,
                SCREENSAVER_COMPONENTS,
                flattenedComponent(context)
            )
            // Selecting a dream while screensavers are switched off system-wide
            // would look exactly like the feature being broken.
            Settings.Secure.putInt(context.contentResolver, SCREENSAVER_ENABLED, 1)
            SelectionResult(selected = true, previousComponents = previous)
        } catch (e: Exception) {
            Log.w(TAG, "Could not select the photo wall as screensaver", e)
            SelectionResult(selected = false, previousComponents = null)
        }
    }

    /**
     * Hands the screensaver slot back, restoring [previous] when we have it.
     *
     * Only acts if WE are the current selection — if the user has since chosen
     * something else by other means, stomping on it would be worse than doing
     * nothing. A null/blank [previous] clears the value so the system falls
     * back to its own default rather than being left pointing at a dream we
     * have just disabled.
     */
    fun deselect(context: Context, previous: String?) {
        if (!canSelect(context) || !isSelected(context)) return
        try {
            Settings.Secure.putString(
                context.contentResolver,
                SCREENSAVER_COMPONENTS,
                previous?.takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not release the screensaver selection", e)
        }
    }

    /** The exact one-liner the UI and README tell people to run. Kept here so they cannot drift. */
    fun grantCommand(context: Context): String =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private fun currentComponents(context: Context): String? = try {
        Settings.Secure.getString(context.contentResolver, SCREENSAVER_COMPONENTS)
    } catch (e: Exception) {
        null
    }

    private fun flattenedComponent(context: Context): String =
        ComponentName(context.applicationContext, PhotoWallDreamService::class.java)
            .flattenToString()

    private const val SCREENSAVER_COMPONENTS = "screensaver_components"
    private const val SCREENSAVER_ENABLED = "screensaver_enabled"
    private const val TAG = "MCLauncherScreensaver"
}
