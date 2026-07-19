package com.nuvio.app.features.streams.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import com.nuvio.app.core.device.DeviceCapabilities
import com.nuvio.app.features.streams.StreamItem

/**
 * Wrapping row of bundled "Elite-Badges" logo images for a single STREAM PICKER row, each amber
 * "↓ target" pill trailing a badge whose advertised capability exceeds THIS device.
 *
 * Placed AFTER the audio/subtitle language line in the stream card; renders nothing when there are
 * zero matches. Display-only — the backend already device-cap-filters the stream list.
 */
private val AmberOutline = Color(0xFFEBA840)
private val AmberFill = Color(0xFF3A2B10)
private val BadgeHeight = 20.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StreamPickerBadgeRow(
    stream: StreamItem,
    modifier: Modifier = Modifier,
) {
    // Read the shared device-cap snapshot at match time. Downgrade pills are display-only; if caps
    // settle after first paint the row recomposes with the stream key on the next selection pass.
    val badges by produceState(initialValue = emptyList<MatchedBadge>(), stream) {
        val device = DeviceCapabilities.current
        value = runCatching { BadgeEngine.matchedBadges(stream, device) }.getOrDefault(emptyList())
    }

    if (badges.isEmpty()) return

    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        badges.forEach { matched ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                BadgeLogo(matched.rule.asset, matched.rule.name)
                val target = matched.downgradeTarget
                if (target != null) {
                    Spacer(modifier = Modifier.width(3.dp))
                    DowngradePill(target)
                }
            }
        }
    }
}

@Composable
private fun BadgeLogo(asset: String, name: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, asset) {
        value = BadgeEngine.image(asset)
    }
    val image = bitmap ?: return
    Image(
        bitmap = image,
        contentDescription = name,
        modifier = Modifier.height(BadgeHeight),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
    )
}

@Composable
private fun DowngradePill(target: String) {
    val shape = remember { RoundedCornerShape(6.dp) }
    Row(
        modifier = Modifier
            .clip(shape)
            .background(AmberFill)
            .border(1.dp, AmberOutline, shape)
            .padding(horizontal = 5.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = "↓ $target",
            color = AmberOutline,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
