package com.nuvio.app.core.build

enum class TrailerPlaybackMode {
    IN_APP,
    EXTERNAL,
}

expect object AppFeaturePolicy {
    val pluginsEnabled: Boolean
    val supportersContributorsPageEnabled: Boolean
    val accountDeletionEnabled: Boolean
    val personalMediaAddonCopyEnabled: Boolean
    val p2pEnabled: Boolean
    val trailerPlaybackMode: TrailerPlaybackMode
    val heroTrailerPlaybackSupported: Boolean
    val inAppUpdaterEnabled: Boolean
    val imdbRatingLogoEnabled: Boolean
    val debugBackendSwitcherEnabled: Boolean
    // TorBox-only product model: Trakt is not offered. Gates all Trakt runtime paths
    // (auth, scrobble, library source, watch progress, list picker). See TraktAuthRepository.
    val traktEnabled: Boolean
}
