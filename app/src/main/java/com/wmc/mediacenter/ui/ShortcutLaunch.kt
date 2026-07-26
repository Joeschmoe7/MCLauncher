package com.wmc.mediacenter.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.wmc.mediacenter.data.ShortcutConfig

/**
 * Fires a shortcut card's stored deep-link URI at its target app, e.g. a
 * "Movies" card opening Channels DVR straight to `channels://navigate/Movies`
 * instead of just launching the app to its default screen.
 *
 * Uses `setPackage` (not an explicit component) so the target app's own
 * manifest intent-filters resolve which activity handles it — same as how
 * the app would handle the link if it came from a browser or another app.
 * Falls back to a Toast instead of silently doing nothing if the target
 * app can't handle it (uninstalled, URI rejected, app doesn't support deep
 * links, etc.) — a shortcut is more failure-prone than a normal launch
 * since it depends on the target app's own URI scheme staying compatible.
 */
fun launchShortcut(context: Context, shortcut: ShortcutConfig) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(shortcut.uri))
                .setPackage(shortcut.targetPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open \"${shortcut.label}\" — is the app installed?", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open \"${shortcut.label}\"", Toast.LENGTH_SHORT).show()
    }
}
