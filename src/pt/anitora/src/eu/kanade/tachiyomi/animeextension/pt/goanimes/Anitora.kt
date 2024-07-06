package eu.kanade.tachiyomi.animeextension.pt.anitora

import eu.kanade.tachiyomi.animeextension.pt.anitora.extractors.TkPlayerExtractor
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.multisrc.dooplay.DooPlay
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import eu.kanade.tachiyomi.util.parallelCatchingFlatMapBlocking
import okhttp3.FormBody
import okhttp3.Response
import org.jsoup.nodes.Element

class Anitora : DooPlay(
    "pt-BR",
    "Anitora",
    "https://anitora.ru",
) {

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/")
        .add("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7,fr;q=0.6,zh-CN;q=0.5,zh-TW;q=0.4,zh;q=0.3")

    // ============================== Popular ===============================
    override fun popularAnimeSelector() = "div.sidebar.right article"

    override fun popularAnimeRequest(page: Int) = GET("$baseUrl/a/", headers)

    // =============================== Latest ===============================
    override fun latestUpdatesNextPageSelector() = "div.pagination > a.arrow_pag > i#nextpagination"

    override val latestUpdatesPath = "e"

    // ============================== Episodes ==============================

    // ============================ Video Links =============================
    override val prefQualityValues = arrayOf("240p", "360p", "480p", "720p", "1080p")
    override val prefQualityEntries = prefQualityValues

    override fun videoListParse(response: Response): List<Video> {
        val playerUrls = response.asJsoup()
            .select("ul#playeroptionsul li:not([id=player-option-trailer])")
            .map(::getPlayerUrl)

        return playerUrls.parallelCatchingFlatMapBlocking(::getPlayerVideos)
    }

    private val tkPlayerExtractor by lazy { TkPlayerExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private fun getPlayerVideos(url: String): List<Video> {
        return when {
            "tkplayer" in url -> tkPlayerExtractor.videosFromUrl(url, "")
            else -> emptyList()
        }
    }

    private fun getPlayerUrl(player: Element): String {
        val body = FormBody.Builder()
            .add("action", "doo_player_ajax")
            .add("post", player.attr("data-post"))
            .add("nume", player.attr("data-nume"))
            .add("type", player.attr("data-type"))
            .build()

        return client.newCall(POST("$baseUrl/wp-admin/admin-ajax.php", headers, body))
            .execute()
            .let { response ->
                response
                    .body.string()
                    .substringAfter("\"embed_url\":\"")
                    .substringBefore("\"")
                    .replace("\\", "")
            }
    }

    // ============================= Utilities ==============================
    override val animeMenuSelector = "div.pag_episodes div.item a[href] i.fa-list-ul"

    override fun List<Video>.sort(): List<Video> {
        val quality = preferences.getString(videoSortPrefKey, videoSortPrefDefault)!!
        return sortedWith(
            compareBy(
                { it.quality.contains(quality) },
                { Regex("""(\d+)p""").find(it.quality)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
