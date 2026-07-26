package com.wmc.mediacenter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.wmc.mediacenter.ui.MediaCenterApp
import com.wmc.mediacenter.ui.theme.MediaCenterTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Back-button behavior (no-op on Home, dismiss menu / return to Home
        // otherwise) is handled by BackHandler composables inside
        // MediaCenterApp, since it now depends on which screen/menu is open.
        setContent {
            MediaCenterTheme {
                MediaCenterApp(viewModel = viewModel)
            }
        }
    }
}
