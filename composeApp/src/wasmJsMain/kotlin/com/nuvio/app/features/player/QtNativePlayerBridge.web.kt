package com.nuvio.app.features.player

import com.nuvio.app.core.platform.consumeQtNativePlayerAction
import com.nuvio.app.core.platform.isQtNativePlayerHost
import com.nuvio.app.core.platform.setQtNativePlayerContext

internal actual object QtNativePlayerBridge {
    actual fun isAvailable(): Boolean =
        isQtNativePlayerHost()

    actual fun publishContext(contextJson: String): Boolean =
        setQtNativePlayerContext(contextJson)

    actual fun consumeAction(): String? =
        consumeQtNativePlayerAction().takeIf { it.isNotBlank() }
}
