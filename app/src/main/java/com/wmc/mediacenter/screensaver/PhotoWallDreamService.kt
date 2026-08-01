package com.wmc.mediacenter.screensaver

import android.service.dreams.DreamService
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.wmc.mediacenter.data.SettingsRepository
import com.wmc.mediacenter.ui.PhotoWallScreensaver
import com.wmc.mediacenter.ui.theme.MCLauncherTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * S35 — WMC-STYLE PHOTO WALL SCREENSAVER, as a real Android TV screensaver
 * (Daydream), not an in-app overlay. Fires system-wide after idle time from
 * anywhere — matches WMC fidelity (its screensaver was OS-wide too) and
 * shows up in Settings > Device Preferences > Screen saver next to Netflix's,
 * Google Photos', etc. Chosen over an in-app-only overlay deliberately.
 *
 * WHY A DreamService AND NOT AN ACTIVITY. This is the standard Android TV
 * screensaver mechanism (`android.service.dreams.DreamService`) — a bound
 * Service the system gives a Window to draw into, not an Activity. This app
 * has never hosted Compose outside `ComponentActivity.setContent{}`
 * (MainActivity), so the plumbing below — a hand-rolled LifecycleOwner /
 * ViewModelStoreOwner / SavedStateRegistryOwner wired onto a raw ComposeView
 * — is new. It's the documented, current pattern for hosting Compose in any
 * non-Activity/non-Fragment host: Compose genuinely requires a
 * ViewTreeLifecycleOwner and ViewTreeSavedStateRegistryOwner reachable from
 * the view or AndroidComposeView throws on attach. There is no AndroidX
 * helper that does this automatically for an arbitrary View — this is not a
 * shortcut being skipped, it's still the only way.
 *
 * WINDOW TIMING. `onAttachedToWindow()`, not `onCreate()`, is the earliest
 * point `setContentView()`/`getWindow()` are valid — DreamService has no
 * window during onCreate, unlike an Activity. Screen dimensions are NOT read
 * here for the same reason (not settled yet); PhotoWallScreensaver gets them
 * from Compose itself via BoxWithConstraints once composition starts.
 *
 * `isInteractive = false` + `isFullscreen = true`: the framework intercepts
 * all touch/key input at the window level and calls wakeUp() itself before
 * any of it reaches Compose — this is what makes "any input dismisses the
 * screensaver" work with zero custom key handling. (If an in-dream
 * affordance is ever wanted — tap to pause, long-press for a menu — this
 * would need to flip to true and call wakeUp() manually instead.)
 *
 * REGISTRATION FAILS SILENTLY. If the manifest entry, the meta-data XML, or
 * this class name don't line up exactly, the dream simply never appears in
 * the system's Screen Saver picker — no crash, no logcat error pointing at
 * it. That's why this file exists on its own as a bare skeleton FIRST: get
 * it showing a plain black screen and confirmed selectable/activatable in
 * Settings before investing in PhotoWallScreensaver's actual rendering.
 */
class PhotoWallDreamService :
    DreamService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore = ViewModelStore()

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performAttach()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.i(TAG, "onAttachedToWindow")

        isInteractive = false
        isFullscreen = true

        lifecycleRegistry.currentState = Lifecycle.State.STARTED

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@PhotoWallDreamService)
            setViewTreeViewModelStoreOwner(this@PhotoWallDreamService)
            setViewTreeSavedStateRegistryOwner(this@PhotoWallDreamService)
            // No Activity/Fragment pooling scenario here — tie disposal
            // directly to this lifecycle reaching DESTROYED rather than
            // relying on the default detach/reattach heuristics.
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@PhotoWallDreamService)
            )
            setContent { ScreensaverContent(resolveFolderPath()) }
        }
        setContentView(composeView)
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.i(TAG, "onDreamingStarted")
        // The window fade-in transition happens between onAttachedToWindow
        // and here — PhotoWallScreensaver's shot-scheduling loop keys off
        // RESUMED so the first pan doesn't start mid-motion before the dream
        // is actually visible.
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        Log.i(TAG, "onDreamingStopped")
        teardown()
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        teardown()
        super.onDetachedFromWindow()
    }

    /** Safe to call more than once — exact callback ordering/duplication between onDreamingStopped and onDetachedFromWindow isn't guaranteed. */
    private fun teardown() {
        if (lifecycleRegistry.currentState == Lifecycle.State.DESTROYED) return
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /**
     * One-shot synchronous read at attach time — this Service is a separate
     * system component from MainActivity/MainViewModel (same relationship
     * BootReceiver/HomeWatchdogService already have to the rest of the app),
     * so it reads the setting through its own SettingsRepository instance
     * rather than sharing state. `runBlocking` is deliberate here: DataStore
     * Preferences reads are backed by an in-memory cache after the first
     * disk hit, this is a small file, and attach is a one-time event, not a
     * hot path — acceptable to block briefly rather than adding loading
     * states to a screensaver that's about to scan a folder anyway.
     */
    private fun resolveFolderPath(): String = runBlocking {
        val configured = SettingsRepository(applicationContext).settingsFlow.first().screensaverFolderPath
        if (configured.isNullOrBlank()) ScreensaverPhotoRepository.DEFAULT_FOLDER_PATH else configured
    }

    private companion object {
        const val TAG = "MCLauncherScreensaver"
    }
}

/** Thin wrapper so the theme is applied once, in one place, for whatever PhotoWallScreensaver renders — including its own "no photos found" message. */
@Composable
private fun ScreensaverContent(folderPath: String) {
    MCLauncherTheme {
        PhotoWallScreensaver(folderPath)
    }
}
