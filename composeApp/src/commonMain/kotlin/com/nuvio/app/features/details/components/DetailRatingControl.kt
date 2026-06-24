package com.nuvio.app.features.details.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.RatingService
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_rating_action_rate
import nuvio.composeapp.generated.resources.settings_rating_clear
import nuvio.composeapp.generated.resources.settings_rating_dialog_title
import nuvio.composeapp.generated.resources.settings_rating_title
import org.jetbrains.compose.resources.stringResource

/**
 * The rate-a-title control on the details screen. Loads the existing 1–10 rating from OUR
 * backend on open and submits the user's selection (which feeds reco bootstrapping).
 * Mirrors NuvioTV's HeroSection rating area + star picker.
 */
@Composable
fun DetailRatingControl(
    meta: MetaDetails,
    isTablet: Boolean,
) {
    val scope = rememberCoroutineScope()
    var tmdbId by remember(meta.id) { mutableStateOf<Int?>(null) }
    var currentRating by remember(meta.id) { mutableStateOf<Int?>(null) }
    var loaded by remember(meta.id) { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(meta.id, meta.type) {
        val resolved = RatingService.resolveTmdbId(meta.id, meta.type)
        tmdbId = resolved
        if (resolved != null) {
            currentRating = RatingService.fetchRating(resolved, meta.type)
        }
        loaded = true
    }

    // Only show the control once we can target a tmdb id (the backend requires it).
    if (!loaded || tmdbId == null) return

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(Res.string.settings_rating_title),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.clickable { showPicker = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            val rating = currentRating
            if (rating != null) {
                Icon(
                    imageVector = Icons.Rounded.Star,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isTablet) 22.dp else 20.dp),
                )
                Text(
                    text = "$rating/10",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.StarBorder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(if (isTablet) 22.dp else 20.dp),
                )
                Text(
                    text = stringResource(Res.string.settings_rating_action_rate),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    if (showPicker) {
        RatingPickerDialog(
            currentRating = currentRating,
            onSelect = { stars ->
                showPicker = false
                val id = tmdbId ?: return@RatingPickerDialog
                currentRating = stars
                scope.launch { RatingService.submitRating(id, meta.type, stars) }
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RatingPickerDialog(
    currentRating: Int?,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_rating_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    (1..10).forEach { star ->
                        val filled = currentRating != null && star <= currentRating
                        Icon(
                            imageVector = if (filled) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                            contentDescription = "$star",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .size(28.dp)
                                .clickable { onSelect(star) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                }
            }
        }
    }
}
