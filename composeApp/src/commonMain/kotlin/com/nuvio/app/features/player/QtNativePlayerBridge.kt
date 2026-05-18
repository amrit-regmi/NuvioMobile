package com.nuvio.app.features.player

internal expect object QtNativePlayerBridge {
    fun isAvailable(): Boolean
    fun publishContext(contextJson: String): Boolean
    fun consumeAction(): String?
}
