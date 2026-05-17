package com.nuvio.app.core.i18n

import com.nuvio.app.core.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

actual fun syncString(resource: StringResource, vararg args: Any): String =
    runBlocking {
        if (args.isEmpty()) {
            getString(resource)
        } else {
            getString(resource, *args)
        }
    }
