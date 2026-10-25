package com.patron

import com.lagradost.api.Log
import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

/** Shared HLS handling for VidMody, direct media and embed-page M3U8 sources. */
internal object HDFilmDelisiHlsResolver {
    suspend fun emit(
        url: String,
        source: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        playlistText: String? = null,
    ) {
        val requestHeaders = headers.toMutableMap().apply {
            if (keys.none { it.equals("Referer", ignoreCase = true) }) {
                put("Referer", referer)
            }
        }
        val playlist = playlistText ?: try {
            app.get(url, headers = requestHeaders).text
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(source, "HLS manifest indirilemedi ($url): ${error.message}")
            ""
        }

        val hasValidPlaylist = playlist.contains("#EXTM3U")
        val hasAudioRenditions = hasValidPlaylist &&
            HDFilmDelisiPlayerParser.hasHlsAudioRenditions(playlist)
        val hasSubtitleRenditions = hasValidPlaylist &&
            HDFilmDelisiPlayerParser.hasHlsSubtitleRenditions(playlist)
        val audioFiles = ArrayList<AudioFile>()
        if (hasValidPlaylist) {
            HDFilmDelisiPlayerParser.hlsSubtitles(playlist, url)
                .filterNot { it.url.substringBefore('#').substringBefore('?').endsWith(".m3u8", ignoreCase = true) }
                .forEach { track ->
                val language = track.language?.takeIf { it.isNotBlank() } ?: track.label
                subtitleCallback(
                    newSubtitleFile(language, track.url) {
                        this.headers = requestHeaders
                    },
                )
            }
            HDFilmDelisiPlayerParser.hlsAudioTracks(playlist, url).forEach { track ->
                Log.d(source, "HLS ses parçası: ${track.label} (${track.language ?: "dil bilinmiyor"})")
                audioFiles += newAudioFile(track.url) {
                    this.headers = requestHeaders
                }
            }
        }

        // Keep the master whenever it declares alternate audio or subtitles;
        // child playlists can sever rendition-group associations from the manifest.
        // Audio URIs are additionally exposed through CloudStream's audioTracks.
        if (hasAudioRenditions || hasSubtitleRenditions) {
            callback(
                newExtractorLink(source, "$source HLS Master", url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = requestHeaders
                    this.audioTracks = audioFiles
                },
            )
            return
        }

        // Without alternate audio/subtitle renditions, expand quality variants; if
        // expansion fails, the original master is kept as a fallback.
        val expanded = try {
            M3u8Helper.generateM3u8(source, url, referer, headers = requestHeaders)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(source, "M3u8Helper çözemedi; master bağlantısı korunuyor: ${error.message}")
            emptyList()
        }

        if (expanded.isNotEmpty()) {
            expanded.forEach(callback)
        } else {
            callback(
                newExtractorLink(source, "$source HLS Master", url, ExtractorLinkType.M3U8) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = requestHeaders
                    this.audioTracks = audioFiles
                },
            )
        }
    }
}
