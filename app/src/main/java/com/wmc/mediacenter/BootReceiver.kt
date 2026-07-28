package com.wmc.mediacenter

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * S31 — SELF-START AFTER COLD BOOT.
 *
 * WHY THIS EXISTS. On the onn box the system does not hand Home to us at cold
 * boot, even though every setting that governs that choice says it should:
 *
 *   - `cmd role get-role-holders android.app.role.HOME` -> com.wmc.mediacenter
 *   - `dumpsys package` Preferred Activities, User 0, CATEGORY_HOME
 *     -> com.wmc.mediacenter/.MainActivity, mAlways=true
 *   - AndroidManifest declares HOME + DEFAULT, exported, singleTask,
 *     stateNotNeeded
 *
 * and the boot log still shows exactly two home starts: FallbackHome while
 * user 0 is locked, then launcherx 357ms after RUNNING_UNLOCKED. Our process
 * is never started at all — no crash, nothing in the crash buffer.
 *
 * The likely reason we lose is PRIORITY. `resolve-activity` reports
 * launcherx's HOME filter at `priority=2`; Android clamps android:priority to
 * 0 for anything that isn't a privileged system app, so we cannot declare a
 * competing value and cannot win that comparison. This is inference, not
 * something proven from the framework source — but it fits every observation,
 * and it means no manifest change can fix it.
 *
 * So: stop asking the system to choose us, and just start ourselves once boot
 * finishes. The cost is a brief flash of the stock launcher before we appear.
 *
 * CAVEAT WORTH KNOWING. Android 10+ restricts background activity launches,
 * and a broadcast receiver starting an activity is precisely that pattern. It
 * may be blocked. Blocks are usually SILENT — logged by ActivityTaskManager
 * rather than thrown — so the catch below will often not fire even on failure.
 * To check:
 *
 *     adb logcat -d | findstr /i "MCLauncherBoot"
 *     adb logcat -d | findstr /i "Background activity"
 *
 * Holding the HOME role may exempt us from that restriction. That is the whole
 * bet, and one boot settles it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.i(TAG, "BOOT_COMPLETED received; starting MainActivity")

        val launch = Intent(context, MainActivity::class.java).apply {
            // NEW_TASK is mandatory from a receiver context — there is no
            // activity task to inherit. CLEAR_TOP keeps us from stacking a
            // second instance on top of a singleTask activity that may
            // already exist.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        try {
            context.startActivity(launch)
            Log.i(TAG, "startActivity dispatched (not proof it was allowed)")
        } catch (t: Throwable) {
            Log.w(TAG, "startActivity threw — $t")
        }
    }

    companion object {
        private const val TAG = "MCLauncherBoot"
    }
}
