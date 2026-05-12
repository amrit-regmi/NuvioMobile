package com.nuvio.app.features.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private val desktopPlaybackPrewarmStarted = AtomicBoolean(false)

internal suspend fun prewarmDesktopPlaybackBackend() {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    if (!osName.contains("mac")) return
    if (!desktopPlaybackPrewarmStarted.compareAndSet(false, true)) return

    val bridge = withContext(Dispatchers.IO) {
        runCatching { MacOSMPVBridgeLib.INSTANCE }.getOrNull()
    } ?: return

    delay(1_500)

    withContext(Dispatchers.IO) {
        runCatching { bridge.nuvio_player_prewarm() }
    }
}
