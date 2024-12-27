package eu.kanade.tachiyomi.animeextension.all.uniquestream.dto

import kotlinx.serialization.Serializable

@Serializable
data class AnimeListItem(
    val content_id: String,
    val image: String,
    val title: String,
    val type: String,
)

@Serializable
data class AnimeSeasons(
    val content_id: String,
    val title: String,
    val season_number: Int,
    val season_seq_number: Int,
    val display_number: String,
)

@Serializable
data class AnimeImage(
    val type: String,
    val url: String,
)

@Serializable
data class AnimeDetails(
    val content_id: String,
    val title: String,
    val description: String,
    val seasons: List<AnimeSeasons>,
    val images: List<AnimeImage>,
)

@Serializable
data class SearchListItem(
    val content_id: String,
    val title: String,
    val image: String,
    val type: String,
)

@Serializable
data class SearchResponse(
    val series: List<SearchListItem>,
    val movies: List<SearchListItem>?,
    val episodes: List<SearchListItem>?,
)

@Serializable
data class EpisodeItem(
    val content_id: String,
    val episode: String,
    val episode_number: Float,
    val is_clip: Boolean,
    val title: String,
)

@Serializable
data class DashData(
    val locale: String,
    val playlist: String,
    val hard_subs: List<DashData>?,
)

@Serializable
data class DashVersions(
    val dash: List<DashData>,
)

@Serializable
data class VideoData(
    val content_id: String,
    val dash_not_ready: Boolean?,
    val hls_not_ready: Boolean?,
    val title: String,
    val dash: DashData?,
    val versions: DashVersions?,
)

@Serializable
data class QueueDash(
    val status: String,
)
