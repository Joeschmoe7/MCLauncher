package com.wmc.mediacenter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wmc.mediacenter.data.ShortcutConfig

/**
 * Fires a shortcut card's stored deep link at its target app, e.g. a
 * "Movies" card opening Channels DVR straight to `channels://navigate/Movies`
 * instead of just launching the app to its default screen.
 *
 * Two ways a target app can expect to be addressed, both supported here:
 *  - A URI ([ShortcutConfig.uri]), set as the Intent's data — what apps with
 *    their own scheme (`channels://...`) expect.
 *  - Plain Intent extras ([ShortcutConfig.stringExtras] /
 *    [ShortcutConfig.booleanExtras]) —
 *    what Jellyfin's Android TV app expects (`ItemId` + `ItemIsUserView`),
 *    since it has no URI scheme of its own. Left with no data set at all in
 *    this case: Jellyfin's StartupActivity intent-filter declares no <data>
 *    element, so a filled-in Uri would fail to match it via setPackage.
 *
 * Uses `setPackage` (not an explicit component) so the target app's own
 * manifest intent-filters resolve which activity handles it — same as how
 * the app would handle the link if it came from a browser or another app.
 * Falls back to a Toast instead of silently doing nothing if the target
 * app can't handle it (uninstalled, URI rejected, app doesn't support deep
 * links, etc.) — a shortcut is more failure-prone than a normal launch
 * since it depends on the target app's own deep-link contract staying
 * compatible.
 */
fun launchShortcut(context: Context, shortcut: ShortcutConfig) {
    try {
        val intent = Intent(Intent.ACTION_VIEW)
            .setPackage(shortcut.targetPackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        shortcut.uri?.takeIf { it.isNotBlank() }?.let { intent.data = Uri.parse(it) }
        for ((key, value) in shortcut.stringExtras) intent.putExtra(key, value)
        for ((key, value) in shortcut.booleanExtras) intent.putExtra(key, value)
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open \"${shortcut.label}\" — is the app installed?", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open \"${shortcut.label}\"", Toast.LENGTH_SHORT).show()
    }
}
