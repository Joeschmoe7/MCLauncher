package com.wmc.mediacenter

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * S33 — WAKE-FROM-SLEEP WATCHDOG.
 *
 * WHY THIS EXISTS. launcherx declares its HOME filter at priority=2, which
 * beats us in home resolution BEFORE preferred-activity records are even
 * consulted — verified on-device 2026-07-28: our preferred record was
 * present with mAlways=true and resolution still returned launcherx. So
 * whenever the system needs a home screen and our process is dead (it gets
 * killed during sleep on this 2GB box), launcherx appears and stays.
 * `set-home-activity` can never fix that; S31 covers cold boot only.
 *
 * THE MECHANISM. An accessibility service, the established launcher-manager
 * trick on Google TV (Projectivy does the same): the framework delivers a
 * window-state event when launcherx's home screen appears, and we
 * immediately start MainActivity over it. Crucially the framework also
 * KEEPS THIS SERVICE BOUND and re-binds it after our process is killed —
 * which is exactly the failure case, and why this works where a
 * runtime-registered SCREEN_ON receiver (dead process = deaf receiver)
 * cannot.
 *
 * SCOPE. home_watchdog_config.xml restricts events to
 * TYPE_WINDOW_STATE_CHANGED from the launcherx package only, with
 * canRetrieveWindowContent=false — the service can't read anything, it only
 * learns that a launcherx window appeared. We additionally match ONLY the
 * home screen class, so launcherx's legitimate non-home surfaces (Entity
 * pages from Google search results, the profile chooser, deep links) are
 * never hijacked.
 *
 * ESCAPE HATCH. The "Google TV Home" tile is a deliberate switch, so
 * MCLauncherApp.launchOtherHome() calls [HomeHandoff.beginDeliberateVisit]
 * first and the bounce is suppressed for a window. See [HomeHandoff].
 *
 * ENABLING (once per install, like the appops grants — survives reboots,
 * not uninstalls):
 *
 *   adb shell settings put secure enabled_accessibility_services \
 *       com.wmc.mediacenter/com.wmc.mediacenter.HomeWatchdogService
 *   adb shell settings put secure accessibility_enabled 1
 *
 * Or from the TV: Settings > System > Accessibility > MCLauncher home
 * watchdog. Disabled, the app behaves exactly as before this existed.
 */
class HomeWatchdogService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "watchdog connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // packageNames in the config already filters to launcherx; the class
        // check narrows to its actual home screen.
        if (event.className != LAUNCHERX_HOME_CLASS) return
        if (HomeHandoff.isDeliberateVisitActive()) {
            Log.i(TAG, "launcherx home appeared — suppressed (deliberate visit)")
            return
        }

        Log.i(TAG, "launcherx home appeared — bouncing back to MCLauncher")
        val launch = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(launch)
        } catch (t: Throwable) {
            // BAL-blocked or similar — S32's SYSTEM_ALERT_WINDOW grant is
            // what makes this reliably allowed; without it, log and stay put.
            Log.w(TAG, "bounce failed — $t")
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt — the service holds no state or feedback.
    }

    private companion object {
        const val TAG = "MCLauncherWatchdog"
        const val LAUNCHERX_HOME_CLASS =
            "com.google.android.apps.tv.launcherx.home.HomeActivity"
    }
}

/**
 * Tiny cross-component flag: the watchdog and the UI live in the same
 * process, so a volatile timestamp is enough to mark "the user deliberately
 * left for Google TV Home; don't drag them back".
 *
 * The window is time-boxed rather than open-ended so the watchdog always
 * re-arms itself — worst case a deliberate visit gets bounced after
 * [DELIBERATE_VISIT_WINDOW_MS], vs. the alternative failure of a stale flag
 * disabling the watchdog forever.
 */
object HomeHandoff {

    private const val DELIBERATE_VISIT_WINDOW_MS = 15 * 60 * 1000L

    @Volatile
    private var visitExpiresAt = 0L

    /** Called by the "Google TV Home" tile right before it fires the HOME intent. */
    fun beginDeliberateVisit() {
        visitExpiresAt = SystemClock.elapsedRealtime() + DELIBERATE_VISIT_WINDOW_MS
    }

    fun isDeliberateVisitActive(): Boolean =
        SystemClock.elapsedRealtime() < visitExpiresAt
}
