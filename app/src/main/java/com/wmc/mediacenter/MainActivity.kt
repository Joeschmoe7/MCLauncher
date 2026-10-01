package com.wmc.mediacenter

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.wmc.mediacenter.ui.MCLauncherApp
import com.wmc.mediacenter.ui.theme.MCLauncherTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Back-button behavior (no-op on Home, dismiss menu / return to Home
        // otherwise) is handled by BackHandler composables inside
        // MCLauncherApp, since it now depends on which screen/menu is open.
        setContent {
            MCLauncherTheme {
                MCLauncherApp(viewModel = viewModel)
            }
        }
    }

    /**
     * True once we've been STOPPED (fully hidden behind another app) since the
     * last onResume. Android always pauses an activity before delivering a new
     * intent, so "were we resumed?" can't tell the two cases apart — but a
     * Home press while we're on screen pauses without stopping us, and coming
     * back from another app means we were stopped first.
     */
    private var hiddenSinceResume = false

    /**
     * singleTask: a Home press while we already exist arrives here. Only when
     * we were already on screen does it mean "take me to the top of Home" —
     * closing any screen or menu and focusing the first row, like real WMC.
     * Returning from another app (or the S33 watchdog bouncing us back) also
     * lands here; that keeps the user where they left off.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!hiddenSinceResume && intent.hasCategory(Intent.CATEGORY_HOME)) {
            viewModel.onHomePressedWhileVisible()
        }
    }

    override fun onStop() {
        super.onStop()
        hiddenSinceResume = true
    }

    override fun onResume() {
        super.onResume()
        hiddenSinceResume = false
        // Picks up artwork dropped into /sdcard/MCLauncher/Artwork while we
        // were in the background. One directory listing when nothing changed.
        viewModel.refreshIfArtworkChanged()
    }
}
