package com.nuvio.app.features.streams

/**
 * Renders a list of ISO-639-1 language codes as plain uppercase tags ("EN · ES")
 * for the stream-info rows. Kept as text (not flags) so it renders identically on
 * Android and iOS without depending on emoji/flag fonts. Empty when none.
 */
fun List<String>.toLangTags(): String =
    asSequence()
        .map { it.trim().substringBefore('-') }
        .filter { it.isNotBlank() }
        .map { it.uppercase() }
        .distinct()
        .joinToString(" · ")
