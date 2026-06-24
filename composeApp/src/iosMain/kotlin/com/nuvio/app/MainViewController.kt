package com.nuvio.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIColor

private val nuvioBackgroundColor = UIColor(red = 0.051, green = 0.051, blue = 0.051, alpha = 1.0)

fun MainViewController() = ComposeUIViewController {
    // Private-backend fork: resolve our FastAPI base URL (default + persisted override)
    // before content clients are built.
    com.nuvio.app.core.network.PrivateBackend.init()
    App()
}.apply {
    view.backgroundColor = nuvioBackgroundColor
}
