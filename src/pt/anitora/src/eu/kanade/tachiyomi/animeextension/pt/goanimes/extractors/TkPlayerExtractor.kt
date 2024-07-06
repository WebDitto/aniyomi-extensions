package eu.kanade.tachiyomi.animeextension.pt.anitora.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.lib.hunterdecoder.HunterDecoder
import eu.kanade.tachiyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.OkHttpClient

class TkPlayerExtractor(private val client: OkHttpClient, private val headers: Headers) {
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    fun videosFromUrl(url: String, name: String): List<Video> {
        val body = client.newCall(GET(url, headers))
            .execute()
            .body
            .string()

        val decoded = HunterDecoder.decode(body)
            ?: return emptyList()

        val masterPlaylistUrl = decoded.substringAfter("sources:")
            .substringAfter("file\":\"")
            .substringBefore('"')
            .replace("\\", "")

        return playlistUtils.extractFromHls(
            masterPlaylistUrl,
            referer = url,
            videoNameGen = { "Anitora - $it" },
        )
    }
}
