package com.nuvio.app.features.details.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.StarHalf
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.RatingService
import com.nuvio.app.features.details.TraktReaction
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_rating_action_rate
import nuvio.composeapp.generated.resources.settings_rating_dialog_title
import nuvio.composeapp.generated.resources.settings_rating_save_failed
import nuvio.composeapp.generated.resources.settings_rating_saved
import nuvio.composeapp.generated.resources.settings_rating_submit
import nuvio.composeapp.generated.resources.settings_rating_title
import nuvio.composeapp.generated.resources.trakt_rating_dislike
import nuvio.composeapp.generated.resources.trakt_rating_like
import nuvio.composeapp.generated.resources.trakt_rating_love
import org.jetbrains.compose.resources.stringResource

// Reaction colors mirror NuvioTV's HeroSection rating area.
private val DislikeColor = Color(0xFFE53935)
private val LikeColor = Color(0xFF43A047)
private val LoveColor = Color(0xFFE91E63)

/** Maps a 1–10 star rating to its reaction bucket (DISLIKE 1-4 / LIKE 5-7 / LOVE 8-10). */
private fun reactionForRating(rating: Int): TraktReaction = when {
    rating <= 4 -> TraktReaction.DISLIKE
    rating <= 7 -> TraktReaction.LIKE
    else -> TraktReaction.LOVE
}

private fun reactionColor(reaction: TraktReaction): Color = when (reaction) {
    TraktReaction.DISLIKE -> DislikeColor
    TraktReaction.LIKE -> LikeColor
    TraktReaction.LOVE -> LoveColor
}

private fun reactionIcon(reaction: TraktReaction): ImageVector = when (reaction) {
    TraktReaction.DISLIKE -> Icons.Filled.ThumbDown
    TraktReaction.LIKE -> Icons.Filled.ThumbUp
    TraktReaction.LOVE -> Icons.Filled.Favorite
}

/**
 * The rate-a-title control on the details screen. Loads the existing 1–10 rating from OUR
 * backend on open and submits the user's selection (which feeds reco bootstrapping).
 * Mirrors NuvioTV's HeroSection reaction model + star picker, adapted for touch:
 *  - collapsed state shows an icon reflecting the current reaction (or an outline star),
 *  - tapping opens a picker where the user picks a reaction, fine-tunes 1–10 stars, then
 *    explicitly Submits (no D-pad dwell). Update is pessimistic: prior value is kept until
 *    the POST succeeds, then we show a "Rating saved" toast (or "Couldn't save rating").
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
    var isSubmitting by remember { mutableStateOf(false) }

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
            modifier = Modifier.clickable(enabled = !isSubmitting) { showPicker = true },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val rating = currentRating
            val iconSize = if (isTablet) 22.dp else 20.dp
            if (rating != null) {
                val reaction = reactionForRating(rating)
                Icon(
                    imageVector = reactionIcon(reaction),
                    contentDescription = null,
                    tint = reactionColor(reaction),
                    modifier = Modifier.size(iconSize),
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(iconSize),
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
        val savedText = stringResource(Res.string.settings_rating_saved)
        val failedText = stringResource(Res.string.settings_rating_save_failed)
        RatingPickerDialog(
            currentRating = currentRating,
            isSubmitting = isSubmitting,
            onSubmit = { stars ->
                val id = tmdbId ?: return@RatingPickerDialog
                isSubmitting = true
                scope.launch {
                    // Pessimistic: keep prior value until the POST confirms success.
                    val ok = RatingService.submitRating(id, meta.type, stars)
                    isSubmitting = false
                    if (ok) {
                        currentRating = stars
                        showPicker = false
                        NuvioToastController.show(savedText)
                    } else {
                        NuvioToastController.show(failedText)
                    }
                }
            },
            onDismiss = { if (!isSubmitting) showPicker = false },
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun RatingPickerDialog(
    currentRating: Int?,
    isSubmitting: Boolean,
    onSubmit: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    // Seed the picker from the existing rating, else from the picked reaction's default.
    var selectedReaction by remember {
        mutableStateOf(currentRating?.let { reactionForRating(it) } ?: TraktReaction.LIKE)
    }
    var selectedStars by remember {
        mutableStateOf(currentRating ?: TraktReaction.LIKE.defaultRating)
    }

    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(Res.string.settings_rating_dialog_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Step 1: pick a reaction. Selecting one snaps stars to that bucket's default.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ReactionChip(
                        reaction = TraktReaction.DISLIKE,
                        label = stringResource(Res.string.trakt_rating_dislike),
                        selected = selectedReaction == TraktReaction.DISLIKE,
                        onClick = {
                            selectedReaction = TraktReaction.DISLIKE
                            selectedStars = TraktReaction.DISLIKE.defaultRating
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ReactionChip(
                        reaction = TraktReaction.LIKE,
                        label = stringResource(Res.string.trakt_rating_like),
                        selected = selectedReaction == TraktReaction.LIKE,
                        onClick = {
                            selectedReaction = TraktReaction.LIKE
                            selectedStars = TraktReaction.LIKE.defaultRating
                        },
                        modifier = Modifier.weight(1f),
                    )
                    ReactionChip(
                        reaction = TraktReaction.LOVE,
                        label = stringResource(Res.string.trakt_rating_love),
                        selected = selectedReaction == TraktReaction.LOVE,
                        onClick = {
                            selectedReaction = TraktReaction.LOVE
                            selectedStars = TraktReaction.LOVE.defaultRating
                        },
                        modifier = Modifier.weight(1f),
                    )
                }

                // Step 2: fine-tune the exact 1–10 score. Rendered as 5 stars (0.5 each),
                // so each star is two taps (left half = odd score, full = even score).
                StarPicker(
                    stars = selectedStars,
                    tint = reactionColor(selectedReaction),
                    onPick = { value ->
                        selectedStars = value
                        selectedReaction = reactionForRating(value)
                    },
                )

                Text(
                    text = "$selectedStars/10",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSubmitting) {
                        Text(stringResource(Res.string.action_cancel))
                    }
                    Button(
                        onClick = { onSubmit(selectedStars) },
                        enabled = !isSubmitting,
                    ) {
                        if (isSubmitting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        } else {
                            Text(stringResource(Res.string.settings_rating_submit))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReactionChip(
    reaction: TraktReaction,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = reactionColor(reaction)
    val container = if (selected) accent else MaterialTheme.colorScheme.surfaceVariant
    val content = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .background(container, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = reactionIcon(reaction),
            contentDescription = label,
            tint = content,
            modifier = Modifier.size(24.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
        )
    }
}

/**
 * 5-star visual selector mapping to a 1–10 score (each star = 2 points). Tapping the left
 * half of a star selects the odd score (half star), the right half the even score (full star).
 */
@Composable
private fun StarPicker(
    stars: Int,
    tint: Color,
    onPick: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        (1..5).forEach { starIndex ->
            val fullThreshold = starIndex * 2          // even score that fully fills this star
            val halfThreshold = fullThreshold - 1      // odd score that half-fills this star
            val icon = when {
                stars >= fullThreshold -> Icons.Rounded.Star
                stars >= halfThreshold -> Icons.Rounded.StarHalf
                else -> Icons.Rounded.StarBorder
            }
            // The icon fills the box; two transparent clickable halves sit on top of it.
            Box(
                modifier = Modifier.size(36.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "$fullThreshold",
                    tint = tint,
                    modifier = Modifier.size(34.dp),
                )
                Row(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onPick(halfThreshold) },
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clickable { onPick(fullThreshold) },
                    )
                }
            }
        }
    }
}
