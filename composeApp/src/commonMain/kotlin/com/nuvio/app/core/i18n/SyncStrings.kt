package com.nuvio.app.core.i18n

import org.jetbrains.compose.resources.StringResource

expect fun syncString(resource: StringResource, vararg args: Any): String

fun syncStringOrFallback(
    resource: StringResource,
    fallback: String,
    vararg args: Any,
): String = runCatching {
    syncString(resource, *args)
}.getOrElse {
    formatSyncString(fallback, *args)
}

internal fun formatSyncString(template: String, vararg args: Any): String {
    val indexed = indexedFormatRegex.replace(template) { match ->
        val index = match.groupValues[1].toIntOrNull()?.minus(1)
        args.getOrNull(index ?: -1)?.toString().orEmpty()
    }
    var sequentialIndex = 0
    return sequentialFormatRegex.replace(indexed) {
        args.getOrNull(sequentialIndex++)?.toString().orEmpty()
    }
}

private val indexedFormatRegex = Regex("%(\\d+)\\$[ds]")
private val sequentialFormatRegex = Regex("%[ds]")
