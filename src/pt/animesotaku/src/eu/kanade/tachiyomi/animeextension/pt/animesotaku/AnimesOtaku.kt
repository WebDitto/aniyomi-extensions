package eu.kanade.tachiyomi.animeextension.pt.animesotaku

import android.app.Application
import android.util.Base64
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.SearchRequestDto
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.SearchResponseDto
import eu.kanade.tachiyomi.animeextension.pt.animesotaku.dto.SingleDto
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.lib.bloggerextractor.BloggerExtractor
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import eu.kanade.tachiyomi.util.parseAs
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import java.text.SimpleDateFormat
import java.util.Locale

class AnimesOtaku : AnimeHttpSource(), ConfigurableAnimeSource {

    override val name = "Animes Otaku"

    override val baseUrl = "https://www.animesotaku.cc"

    override val lang = "pt"

    override val supportsLatest = true

    private val json: Json by injectLazy()

    private val preferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", baseUrl)

    // ============================== Popular ===============================
    override fun popularAnimeRequest(page: Int) = searchOrderBy("total_kiranime_views", page)

    override fun popularAnimeParse(response: Response): AnimesPage {
        val results = response.parseAs<SearchResponseDto>()
        val doc = Jsoup.parseBodyFragment(results.data)
        val animes = doc.select("div.w-full:has(div.kira-anime)").map {
            SAnime.create().apply {
                thumbnail_url = it.selectFirst("img")?.attr("src")
                with(it.selectFirst("h3 > a")!!) {
                    title = text()
                    setUrlWithoutDomain(attr("href"))
                }
            }
        }

        val page = response.request.url.queryParameter("page")?.toIntOrNull() ?: 1
        val hasNextPage = page < results.pages
        return AnimesPage(animes, hasNextPage)
    }

    // =============================== Latest ===============================
    override fun latestUpdatesRequest(page: Int) = searchOrderBy("kiranime_anime_updated", page)

    override fun latestUpdatesParse(response: Response) = popularAnimeParse(response)

    // =============================== Search ===============================
    override suspend fun getSearchAnime(
        page: Int,
        query: String,
        filters: AnimeFilterList,
    ): AnimesPage {
        return if (query.startsWith(PREFIX_SEARCH)) { // URL intent handler
            val id = query.removePrefix(PREFIX_SEARCH)
            client.newCall(GET("$baseUrl/anime/$id"))
                .awaitSuccess()
                .use(::searchAnimeByIdParse)
        } else {
            super.getSearchAnime(page, query, filters)
        }
    }

    private fun searchAnimeByIdParse(response: Response): AnimesPage {
        val details = animeDetailsParse(response).apply {
            setUrlWithoutDomain(response.request.url.toString())
            initialized = true
        }

        return AnimesPage(listOf(details), false)
    }

    override fun getFilterList() = AnimesOtakuFilters.FILTER_LIST

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimesOtakuFilters.getSearchParameters(filters)
        val (meta, orderBy) = when (params.orderBy) {
            "date", "title" -> Pair(null, params.orderBy)
            else -> Pair(params.orderBy, "meta_value_num")
        }

        val single = SingleDto(
            paged = page,
            key = meta,
            order = params.order,
            orderBy = orderBy,
            season = params.season.ifEmpty { null },
            year = params.year.ifEmpty { null },
        )

        val taxonomies = with(params) {
            listOf(genres, status, producers, studios, types).filter {
                it.terms.isNotEmpty()
            }
        }

        val requestDto = SearchRequestDto(single, query, query, taxonomies)
        val requestData = json.encodeToString(requestDto)
        return searchRequest(requestData, page)
    }

    override fun searchAnimeParse(response: Response) = popularAnimeParse(response)

    private fun searchOrderBy(order: String, page: Int): Request {
        val body = """
            {
              "keyword": "",
              "query": "",
              "single": {
                "paged": $page,
                "orderby": "meta_value_num",
                "meta_key": "$order",
                "order": "desc"
              },
              "tax": []
            }
        """.trimIndent()
        return searchRequest(body, page)
    }

    private fun searchRequest(data: String, page: Int): Request {
        val body = data.toRequestBody("application/json".toMediaType())
        return POST(
            "$baseUrl/wp-json/kiranime/v1/anime/advancedsearch?_locale=user&page=$page",
            headers,
            body,
        )
    }

    // =========================== Anime Details ============================
    override fun animeDetailsParse(response: Response) = SAnime.create().apply {
        val document = response.asJsoup()

        setUrlWithoutDomain(document.location())
        thumbnail_url = document.selectFirst("div.anime-image img")?.attr("src")
        title =
            document.selectFirst("h1 span.show.anime")!!.text()!!.replace(" Assistir Online", "")
        genre =
            document.select("span.leading-6 a[class~=border-opacity-30]").joinToString { it.text() }
        description = document.selectFirst("div[data-synopsis]")?.text()
        author = document.selectFirst("span.leading-6 a[href*=\"producer\"]:first-child")?.text()
        artist = document.selectFirst("span.leading-6 a[href*=\"studio\"]:first-child")?.text()
    }

    // ============================== Episodes ==============================
    override fun episodeListParse(response: Response): List<SEpisode> {
        return getRealDoc(response.asJsoup())
            .select(episodeListSelector())
            .map(::episodeFromElement)
    }

    fun episodeListSelector(): String = "div[data-current-slider=\"episode-list\"] a"

    fun episodeFromElement(element: Element) = SEpisode.create().apply {
        setUrlWithoutDomain(element.attr("href"))
        name = element.selectFirst("span.font-semibold")!!.text().trim()
        episode_number = name.substringAfterLast(" ").toFloatOrNull() ?: 0F
    }

    // ============================ Video Links =============================
    private val bloggerExtractor by lazy { BloggerExtractor(client) }

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val players = document.select("div.player-selection span[data-embed-id]")
        return players.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private fun getPlayerVideos(player: Element): List<Video> {
        val url = player.attr("data-embed-id")
            ?.substringAfter(":")
            ?.let { String(Base64.decode(it, Base64.DEFAULT)) }
            ?: return emptyList()

        return when {
            "blogger.com" in url -> bloggerExtractor.videosFromUrl(url, headers)
            "proxycdn.vip" in url -> {
                val mp4Url = client.newCall(GET(url, headers)).execute()
                    .asJsoup()
                    .selectFirst("video")
                    ?.attr("src")
                    ?.let {
                        if (it.startsWith("//")) {
                            return@let "https:$it"
                        }
                        it
                    }
                    ?: return emptyList()
                listOf(
                    Video(mp4Url, "Proxy CDN", mp4Url),
                )
            }

            else -> null
        } ?: emptyList()
    }

    // ============================== Settings ==============================
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = PREF_QUALITY_TITLE
            entries = PREF_QUALITY_ENTRIES
            entryValues = PREF_QUALITY_VALUES
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"

            setOnPreferenceChangeListener { _, newValue ->
                val selected = newValue as String
                val index = findIndexOfValue(selected)
                val entry = entryValues[index] as String
                preferences.edit().putString(key, entry).commit()
            }
        }.also(screen::addPreference)

        MultiSelectListPreference(screen.context).apply {
            key = PREF_HOSTS_SELECTION_KEY
            title = PREF_HOSTS_SELECTION_TITLE
            entries = PREF_HOSTS_SELECTION_ENTRIES
            entryValues = PREF_HOSTS_SELECTION_ENTRIES
            setDefaultValue(PREF_HOSTS_SELECTION_DEFAULT)

            setOnPreferenceChangeListener { _, newValue ->
                @Suppress("UNCHECKED_CAST")
                preferences.edit().putStringSet(key, newValue as Set<String>).commit()
            }
        }.also(screen::addPreference)
    }

    // ============================= Utilities ==============================

    private fun getRealDoc(document: Document): Document {
        val menu = document.selectFirst("div.spr.i-lista")
        if (menu != null) {
            val originalUrl = menu.parent()!!.attr("href")
            val response = client.newCall(GET(originalUrl, headers)).execute()
            return response.asJsoup()
        }

        return document
    }

    private fun String.toDate(): Long {
        return runCatching { DATE_FORMATTER.parse(trim())?.time }
            .getOrNull() ?: 0L
    }

    private val qualityRegex by lazy { Regex("""(\d+)p""") }
    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!

        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { qualityRegex.find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),

        ).reversed()
    }

    companion object {
        private val DATE_FORMATTER by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ENGLISH)
        }

        const val PREFIX_SEARCH = "id:"

        private val SUPPORTED_PLAYERS = setOf(
            "doodstream.com",
            "G.Drive",
            "Moon",
            "ok.ru",
            "S.Tape",
            "Sibnet",
            "Streamlare",
            "UQload",
            "Voe",
            "vudeo",
        )

        private const val PREF_QUALITY_KEY = "pref_quality_key"
        private const val PREF_QUALITY_TITLE = "Preferred quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = arrayOf("1080p", "720p", "480p", "360p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PREF_HOSTS_SELECTION_KEY = "pref_hosts_selection"
        private const val PREF_HOSTS_SELECTION_TITLE = "Disable/enable video hosts"
        private val PREF_HOSTS_SELECTION_ENTRIES = SUPPORTED_PLAYERS.toTypedArray()
        private val PREF_HOSTS_SELECTION_DEFAULT = SUPPORTED_PLAYERS
    }
}
