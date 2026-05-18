package com.nuvio.app.features.player

internal actual object QtNativePlayerBridge {
    actual fun isAvailable(): Boolean = false
    actual fun publishContext(contextJson: String): Boolean = false
    actual fun consumeAction(): String? = null
}
