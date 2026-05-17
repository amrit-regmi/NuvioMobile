package com.nuvio.app.core.auth

import com.nuvio.app.core.platform.WebKeyValueStorage

internal actual object AuthStorage {
    private const val namespace = "nuvio_auth"
    private const val anonymousUserIdKey = "anonymous_user_id"

    actual fun loadAnonymousUserId(): String? =
        WebKeyValueStorage.getString(namespace, anonymousUserIdKey)

    actual fun saveAnonymousUserId(userId: String) {
        WebKeyValueStorage.setString(namespace, anonymousUserIdKey, userId)
    }

    actual fun clearAnonymousUserId() {
        WebKeyValueStorage.remove(namespace, anonymousUserIdKey)
    }
}
