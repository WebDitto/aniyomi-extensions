package eu.kanade.tachiyomi.animeextension.all.uniquestream

import android.app.Application
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.AnimeDetails
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.AnimeListItem
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.DashData
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.EpisodeItem
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.QueueDash
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.SearchListItem
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.SearchResponse
import eu.kanade.tachiyomi.animeextension.all.uniquestream.dto.VideoData
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.parallelFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy

class UniqueStream : ConfigurableAnimeSource, AnimeHttpSource() {

    override val name = "UniqueStream"

    override val baseUrl = "https://anime.uniquestream.net"

    private val apiUrl = "$baseUrl/api/v1"

    override val lang = "all"

    override val supportsLatest = true

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val json: Json by injectLazy()

    override fun headersBuilder() = Headers.Builder().add("user-agent", AO_USER_AGENT)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) =
        GET("$apiUrl/videos/popular?page=$page&limit=10&type=all")

    override fun popularAnimeParse(response: Response): AnimesPage {
        val list = response.parseAs<List<AnimeListItem>>()
        val animes = list.map { it.toSAnime() }
        val hasNextPage = list.size == 10
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) =
        GET("$apiUrl/videos/new?page=$page&limit=10&type=all")

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList) =
        GET("$apiUrl/search?page=$page&query=$query&t=series&limit=10")

    override fun searchAnimeParse(response: Response): AnimesPage {
        val list = response.parseAs<SearchResponse>().series
        val animes = list.map { it.toSAnime() }
        val hasNextPage = list.size == 10
        return AnimesPage(animes, hasNextPage)
    }

    // =========================== Anime Details ============================
    override fun getAnimeUrl(anime: SAnime) =
        "$baseUrl/series/${anime.url}/${anime.title.slugify()}"

    override fun animeDetailsRequest(anime: SAnime) = GET("$apiUrl/series/${anime.url}")

    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val details = response.parseAs<AnimeDetails>()
        url = details.content_id
        title = details.title
        description = details.description
        thumbnail_url = details.images.firstOrNull { it.type == "poster_tall" }?.url
    }

    // ============================== Episodes ==============================
    override fun episodeListRequest(anime: SAnime) = animeDetailsRequest(anime)

    override fun episodeListParse(response: Response): List<SEpisode> {
        val anime = response.parseAs<AnimeDetails>()

        val seasons = anime.seasons

        val episodeList = mutableListOf<SEpisode>()

        seasons.forEach { season ->
            var nextPage = 1
            do {
                val request = GET(
                    "$apiUrl/season/${season.content_id}/episodes?page=$nextPage&limit=10&order_by=asc",
                )
                val episodes =
                    client.newCall(request).execute().parseAs<List<EpisodeItem>>()

                episodes.forEach { episode ->
                    val prefix =
                        if (season.display_number.isNotBlank()) "S${season.display_number} x " else ""
                    val number =
                        if (episode.episode.isNotBlank()) "${episode.episode} - " else ""

                    val fullName = "${prefix}${number}${episode.title}"

                    episodeList.add(
                        SEpisode.create().apply {
                            episode_number = episode.episode_number
                            name = fullName
                            url = episode.content_id
                        },
                    )
                }

                if (episodes.size == 10) nextPage += 1 else nextPage = -1
            } while (nextPage != -1)
        }

        return episodeList.reversed()
    }

    // ============================ Video Links =============================
    override fun videoListRequest(episode: SEpisode) =
        GET("$apiUrl/episode/${episode.url}/media/dash/ja-JP")

    override fun videoUrlParse(response: Response) = throw UnsupportedOperationException()

    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override fun videoListParse(response: Response): List<Video> {
        val videoData = response.parseAs<VideoData>()

        if (videoData.dash_not_ready == true) {
            val queue =
                client.newCall(
                    POST(
                        "$apiUrl/queue/episode/dash/${videoData.content_id}",
                    ),
                ).execute().parseAs<QueueDash>()

            if (queue.status != "ok") {
                return emptyList()
            }

            val response =
                client.newCall(
                    GET("$apiUrl/episode/${videoData.content_id}/media/dash/ja-JP"),
                ).execute()

            return videoListParse(response)
        }

        val dashs = mutableListOf<DashData>()

        if (videoData.dash != null) {
            dashs.add(videoData.dash)
        }
        videoData.versions?.dash?.forEach { dashs.add(it) }

        return dashs.parallelFlatMapBlocking { dash ->
            listOf(
                Video(dash.playlist, dash.locale, videoUrl = dash.playlist),
            )
        }
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_SUB_KEY
            title = PREF_SUB_TITLE
            entries = PREF_SUB_ENTRIES
            entryValues = PREF_SUB_VALUES
            setDefaultValue(PREF_SUB_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================
    private fun parseStatus(statusString: String?): Int {
        return when (statusString?.trim()) {
            "finished_airing" -> SAnime.COMPLETED
            else -> SAnime.ONGOING
        }
    }

    private fun AnimeListItem.toSAnime() = SAnime.create().apply {
        url = content_id
        this@apply.title = this@toSAnime.title
        thumbnail_url = image
    }

    private fun SearchListItem.toSAnime() = SAnime.create().apply {
        url = content_id
        this@apply.title = this@toSAnime.title
        thumbnail_url = image
    }

    private fun Map<String, String>.sortSubs(): List<Map.Entry<String, String>> {
        val sub = preferences.getString(PREF_SUB_KEY, PREF_SUB_DEFAULT)!!

        return entries.sortedWith(
            compareBy { it.key.contains(sub) },
        ).reversed()
    }

    private fun String.slugify(): String {
        return this.replace("""[^a-zA-Z0-9]+""".toRegex(), "-")
    }
}

const val AO_USER_AGENT = "Aniyomi/app (mobile)"
private const val PREF_SUB_KEY = "preferred_subLang"
private const val PREF_SUB_TITLE = "Preferred sub language"
const val PREF_SUB_DEFAULT = "en-US"
private val PREF_SUB_ENTRIES = arrayOf(
    "العربية", "Deutsch", "English", "Español (Spain)",
    "Español (Latin)", "Français", "Italiano",
    "Português (Brasil)", "Русский",
)
private val PREF_SUB_VALUES = arrayOf(
    "ar-ME", "de-DE", "en-US", "es-ES",
    "es-LA", "fr-FR", "it-IT",
    "pt-BR", "ru-RU",
)
