package com.nuvio.app.features.details.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.features.streams.CatalogPrewarmService
import com.nuvio.app.features.streams.DebridDownloadManager
import kotlin.math.roundToInt

/**
 * Persistent circular "Download to TorBox" button for the detail action row, reading live state
 * directly from [DebridDownloadManager] (no params threaded through the deep composable tree).
 * Mirrors NuvioTV's HeroSection download button visual states:
 *   - idle           → download icon; click starts the download.
 *   - downloading/preparing/queued → a [CircularProgressIndicator] ring with a tiny label BELOW
 *     it ("NN%" / "Queued" / nothing); click opens the status dialog.
 *   - ready/done     → DownloadDone checkmark; click opens the status dialog (showing the done state).
 *
 * This composable IS the persistent button (returns Unit) — pass it directly into
 * [DetailActionButtons]'s `downloadButton` slot. If [videoId] is null it renders nothing.
 *
 * If another title is already downloading, [onRequestConfirmCancel] is invoked with that title so
 * the caller can show [DownloadInProgressDialog]; otherwise the download starts.
 *
 * @param type     "movie" | "series"
 * @param videoId  "tt123" (movie) or "tt123:S:E" (series episode); null → renders nothing
 * @param title    human-readable title for the active-download registration + confirm dialog
 * @param isTablet sizes the button to match the play/secondary buttons
 */
@Composable
fun DownloadActionButton(
    type: String,
    videoId: String?,
    title: String,
    isTablet: Boolean,
    onRequestConfirmCancel: (activeTitle: String) -> Unit,
) {
    val progress by DebridDownloadManager.progress.collectAsStateWithLifecycle()
    val activePrepare by DebridDownloadManager.activePrepare.collectAsStateWithLifecycle()
    val cacheHints by CatalogPrewarmService.cacheHints.collectAsStateWithLifecycle()
    if (videoId == null) return

    val isThisItem = DebridDownloadManager.isActiveFor(type, videoId)
    // Visibility gate (TV parity): only offer Download when the details-open prewarm reported this
    // title as NOT cached (so there is genuinely something to download), OR a download for it is
    // already active (keep the progress ring visible). Hidden while the cache state is still
    // unknown (key absent) or when a cached/playable stream exists (hint == true) — e.g. "The Beach".
    val notCached = cacheHints[CatalogPrewarmService.cacheHintKey(type, videoId)] == false
    if (!isThisItem && !notCached) return

    val buttonSize: Dp = if (isTablet) 56.dp else 52.dp
    val thisProgress = progress?.takeIf { isThisItem }

    val ready = thisProgress?.ready == true
    val queued = thisProgress?.queued == true
    val preparing = thisProgress?.isPreparing == true
    val active = ready || queued || preparing

    var showStatusDialog by remember { mutableStateOf(false) }

    val onClick: () -> Unit = {
        if (active) {
            showStatusDialog = true
        } else {
            val other = activePrepare
            if (other != null && !DebridDownloadManager.isActiveFor(type, videoId)) {
                onRequestConfirmCancel(other.title)
            } else {
                DebridDownloadManager.start(type, videoId, title)
            }
        }
    }

    // Leading spacer lives here (not in DetailActionButtons) so it vanishes with the button when
    // this title is already cached and the button gates itself off.
    Spacer(modifier = Modifier.width(12.dp))

    // Active state echoes the TV "active" look (filled onBackground/background); idle matches the
    // secondary icon-button idiom (surfaceVariant / onSurface).
    Surface(
        modifier = Modifier.size(buttonSize),
        shape = CircleShape,
        color = if (active) {
            MaterialTheme.colorScheme.onBackground
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)
        },
        contentColor = if (active) {
            MaterialTheme.colorScheme.background
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Box(
            modifier = Modifier
                .size(buttonSize)
                .clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ready -> {
                    Icon(
                        imageVector = Icons.Default.DownloadDone,
                        contentDescription = "Downloaded",
                        modifier = Modifier.size(21.dp),
                    )
                }
                active -> {
                    // Ring on top, tiny label below it (never centered inside the ring).
                    val percent = thisProgress?.percent
                    val determinate = !queued && percent != null && percent > 0f
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (determinate) {
                            CircularProgressIndicator(
                                progress = (percent!! / 100f).coerceIn(0f, 1f),
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.5.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        val label = when {
                            queued -> "Queued"
                            percent != null && percent > 0f -> "${percent.roundToInt()}%"
                            else -> null
                        }
                        if (label != null) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
                else -> {
                    Icon(
                        imageVector = Icons.Rounded.Download,
                        contentDescription = "Download",
                        modifier = Modifier.size(21.dp),
                    )
                }
            }
        }
    }

    if (showStatusDialog && thisProgress != null) {
        DownloadStatusDialog(
            progress = thisProgress,
            onDismiss = { showStatusDialog = false },
        )
    }
}

/**
 * Status dialog shown when tapping the active download ring (mirrors NuvioTV HeroSection). Surfaces
 * progress / ETA / speed / seeds for the in-flight download.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DownloadStatusDialog(
    progress: DebridDownloadManager.DownloadProgress,
    onDismiss: () -> Unit,
) {
    val queued = progress.queued
    val percent = progress.percent
    val eta = progress.etaMinutes
    val speed = progress.speedMbps
    val seeds = progress.seedCount

    val lines = buildList {
        if (queued) {
            val pos = progress.queuePosition
            add(
                if (pos != null && pos > 0) {
                    "Queued — waiting for a free download slot (position $pos in queue)."
                } else {
                    "Queued — waiting for a free download slot."
                },
            )
        }
        if (!queued && percent != null && percent > 0f) {
            add("Progress: ${percent.roundToInt()}%")
        }
        if (eta != null && eta > 0) {
            add("ETA: ~$eta min")
        }
        if (speed != null && speed > 0.0) {
            add("Speed: ${formatOneDecimal(speed)} MB/s")
        }
        if (seeds != null && seeds > 0) {
            add("Seeds: $seeds")
        }
        if (isEmpty() && !queued) {
            add("Preparing download…")
        }
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = if (queued) "Waiting for a Free Slot" else "Download in Progress",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

/** KMP-safe 1-decimal formatter (no java.util / String.format in commonMain). */
private fun formatOneDecimal(value: Double): String {
    val scaled = (value * 10).roundToInt()
    val whole = scaled / 10
    val frac = if (scaled < 0) -scaled % 10 else scaled % 10
    return "$whole.$frac"
}

/**
 * The "Download in progress" cancel-confirm dialog (mirrors NuvioTV's strings). Shown when the user
 * taps Download on a title while a DIFFERENT title is already downloading.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun DownloadInProgressDialog(
    activeTitle: String?,
    onConfirmCancelAndStartNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Download in progress",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Start,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = activeTitle
                        ?.let { "\"$it\" is still downloading. Starting a new download will cancel it." }
                        ?: "Another title is still downloading. Starting a new download will cancel it.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                    ) {
                        Text("No, keep current download")
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Button(
                        onClick = onConfirmCancelAndStartNew,
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text("Yes, cancel and start new")
                    }
                }
            }
        }
    }
}
