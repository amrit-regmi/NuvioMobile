package com.nuvio.app.core.i18n

import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_play
import nuvio.composeapp.generated.resources.action_play_episode
import nuvio.composeapp.generated.resources.action_resume
import nuvio.composeapp.generated.resources.action_resume_episode
import nuvio.composeapp.generated.resources.compose_player_episode_code_episode_only
import nuvio.composeapp.generated.resources.compose_player_episode_code_full
import nuvio.composeapp.generated.resources.continue_watching_up_next
import nuvio.composeapp.generated.resources.continue_watching_up_next_episode
import nuvio.composeapp.generated.resources.date_month_april
import nuvio.composeapp.generated.resources.date_month_august
import nuvio.composeapp.generated.resources.date_month_december
import nuvio.composeapp.generated.resources.date_month_february
import nuvio.composeapp.generated.resources.date_month_january
import nuvio.composeapp.generated.resources.date_month_july
import nuvio.composeapp.generated.resources.date_month_june
import nuvio.composeapp.generated.resources.date_month_march
import nuvio.composeapp.generated.resources.date_month_may
import nuvio.composeapp.generated.resources.date_month_november
import nuvio.composeapp.generated.resources.date_month_october
import nuvio.composeapp.generated.resources.date_month_september
import nuvio.composeapp.generated.resources.date_month_short_apr
import nuvio.composeapp.generated.resources.date_month_short_aug
import nuvio.composeapp.generated.resources.date_month_short_dec
import nuvio.composeapp.generated.resources.date_month_short_feb
import nuvio.composeapp.generated.resources.date_month_short_jan
import nuvio.composeapp.generated.resources.date_month_short_jul
import nuvio.composeapp.generated.resources.date_month_short_jun
import nuvio.composeapp.generated.resources.date_month_short_mar
import nuvio.composeapp.generated.resources.date_month_short_may
import nuvio.composeapp.generated.resources.date_month_short_nov
import nuvio.composeapp.generated.resources.date_month_short_oct
import nuvio.composeapp.generated.resources.date_month_short_sep
import nuvio.composeapp.generated.resources.media_anime
import nuvio.composeapp.generated.resources.media_channels
import nuvio.composeapp.generated.resources.media_movie
import nuvio.composeapp.generated.resources.media_movies
import nuvio.composeapp.generated.resources.media_series
import nuvio.composeapp.generated.resources.media_tv
import nuvio.composeapp.generated.resources.unit_bytes_b
import nuvio.composeapp.generated.resources.unit_bytes_gb
import nuvio.composeapp.generated.resources.unit_bytes_kb
import nuvio.composeapp.generated.resources.unit_bytes_mb
import org.jetbrains.compose.resources.StringResource

fun localizedMediaTypeLabel(type: String): String {
    val fallback = type.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    return when (type.trim().lowercase()) {
        "movie" -> resourceString(Res.string.media_movies, "Movies")
        "series" -> resourceString(Res.string.media_series, "Series")
        "anime" -> resourceString(Res.string.media_anime, "Anime")
        "channel" -> resourceString(Res.string.media_channels, "Channels")
        "tv" -> resourceString(Res.string.media_tv, "TV")
        else -> fallback
    }
}

fun localizedMovieTypeLabel(): String = resourceString(Res.string.media_movie, "Movie")

fun localizedSeasonEpisodeCode(seasonNumber: Int?, episodeNumber: Int?): String? =
    when {
        seasonNumber != null && episodeNumber != null ->
            resourceString(Res.string.compose_player_episode_code_full, "S${seasonNumber}E${episodeNumber}", seasonNumber, episodeNumber)
        episodeNumber != null ->
            resourceString(Res.string.compose_player_episode_code_episode_only, "E${episodeNumber}", episodeNumber)
        else -> null
    }

fun localizedPlayLabel(seasonNumber: Int?, episodeNumber: Int?): String {
    val episodeCode = localizedSeasonEpisodeCode(seasonNumber, episodeNumber)
    return if (episodeCode != null) {
        resourceString(Res.string.action_play_episode, "Play $episodeCode", episodeCode)
    } else {
        resourceString(Res.string.action_play, "Play")
    }
}

fun localizedResumeLabel(seasonNumber: Int?, episodeNumber: Int?): String {
    val episodeCode = localizedSeasonEpisodeCode(seasonNumber, episodeNumber)
    return if (episodeCode != null) {
        resourceString(Res.string.action_resume_episode, "Resume $episodeCode", episodeCode)
    } else {
        resourceString(Res.string.action_resume, "Resume")
    }
}

fun localizedUpNextLabel(seasonNumber: Int?, episodeNumber: Int?): String =
    if (seasonNumber != null && episodeNumber != null) {
        resourceString(Res.string.continue_watching_up_next_episode, "Up Next • S${seasonNumber}E${episodeNumber}", seasonNumber, episodeNumber)
    } else {
        resourceString(Res.string.continue_watching_up_next, "Up Next")
    }

fun localizedMonthName(month: Int): String =
    when (month) {
        1 -> resourceString(Res.string.date_month_january, "January")
        2 -> resourceString(Res.string.date_month_february, "February")
        3 -> resourceString(Res.string.date_month_march, "March")
        4 -> resourceString(Res.string.date_month_april, "April")
        5 -> resourceString(Res.string.date_month_may, "May")
        6 -> resourceString(Res.string.date_month_june, "June")
        7 -> resourceString(Res.string.date_month_july, "July")
        8 -> resourceString(Res.string.date_month_august, "August")
        9 -> resourceString(Res.string.date_month_september, "September")
        10 -> resourceString(Res.string.date_month_october, "October")
        11 -> resourceString(Res.string.date_month_november, "November")
        12 -> resourceString(Res.string.date_month_december, "December")
        else -> month.toString()
    }

fun localizedShortMonthName(month: Int): String =
    when (month) {
        1 -> resourceString(Res.string.date_month_short_jan, "Jan")
        2 -> resourceString(Res.string.date_month_short_feb, "Feb")
        3 -> resourceString(Res.string.date_month_short_mar, "Mar")
        4 -> resourceString(Res.string.date_month_short_apr, "Apr")
        5 -> resourceString(Res.string.date_month_short_may, "May")
        6 -> resourceString(Res.string.date_month_short_jun, "Jun")
        7 -> resourceString(Res.string.date_month_short_jul, "Jul")
        8 -> resourceString(Res.string.date_month_short_aug, "Aug")
        9 -> resourceString(Res.string.date_month_short_sep, "Sep")
        10 -> resourceString(Res.string.date_month_short_oct, "Oct")
        11 -> resourceString(Res.string.date_month_short_nov, "Nov")
        12 -> resourceString(Res.string.date_month_short_dec, "Dec")
        else -> month.toString()
    }

fun localizedByteUnit(unit: String): String =
    when (unit) {
        "GB" -> resourceString(Res.string.unit_bytes_gb, "GB")
        "MB" -> resourceString(Res.string.unit_bytes_mb, "MB")
        "KB" -> resourceString(Res.string.unit_bytes_kb, "KB")
        else -> resourceString(Res.string.unit_bytes_b, "B")
    }

private fun resourceString(
    resource: StringResource,
    fallback: String,
    vararg args: Any,
): String = syncStringOrFallback(resource, fallback, *args)
