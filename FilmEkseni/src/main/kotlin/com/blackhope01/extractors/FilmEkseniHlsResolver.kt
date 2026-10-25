package com.blackhope01.extractors

import android.util.Log
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

/** Shared HLS handling for inline sources, EksenLoad, and VidMoly. */
internal object FilmEkseniHlsResolver {
    suspend fun emit(
        url: String,
        source: String,
        referer: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        displayName: String = source,
    ) {
        val requestHeaders = headers.toMutableMap().apply {
            if (keys.none { it.equals("Referer", ignoreCase = true) }) put("Referer", referer)
        }
        val manifest = try {
            app.get(url, headers = requestHeaders).text
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("FilmEkseni", "HLS manifest alınamadı ($url): ${error.message}")
            ""
        }

        val hasManifest = manifest.contains("#EXTM3U")
        val audioFiles = ArrayList<AudioFile>()
        if (hasManifest) {
            FilmEkseniPlayerParser.hlsSubtitles(manifest, url)
                .filterNot { it.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) }
                .forEach { track ->
                    val lang = track.language?.takeIf(String::isNotBlank) ?: track.label
                    subtitleCallback(
                        newSubtitleFile(lang, track.url) {
                            this.headers = requestHeaders
                        },
                    )
                }
            FilmEkseniPlayerParser.hlsAudioTracks(manifest, url).forEach { track ->
                Log.d("FilmEkseni", "HLS audio rendition: ${track.label} (${track.language ?: "unknown"})")
                audioFiles += newAudioFile(track.url) {
                    this.headers = requestHeaders
                }
            }
        }

        // Keep the master intact whenever it declares alternate audio or subtitle
        // renditions: variant-only links can lose those relationships. Audio URIs
        // are also exposed through CloudStream's separate audio-track metadata.
        val hasRenditions = hasManifest && (
            FilmEkseniPlayerParser.hasHlsAudioRenditions(manifest) ||
                FilmEkseniPlayerParser.hasHlsSubtitleRenditions(manifest)
            )
        if (hasRenditions) {
            callback(masterLink(url, source, displayName, referer, requestHeaders, audioFiles))
            return
        }

        val expanded = try {
            M3u8Helper.generateM3u8(source, url, referer, headers = requestHeaders, name = displayName)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("FilmEkseni", "M3u8Helper çözemedi; master korunuyor: ${error.message}")
            emptyList()
        }

        if (expanded.isEmpty()) callback(masterLink(url, source, displayName, referer, requestHeaders, audioFiles))
        else expanded.forEach(callback)
    }

    private suspend fun masterLink(
        url: String,
        source: String,
        displayName: String,
        referer: String,
        headers: Map<String, String>,
        audioTracks: List<AudioFile>,
    ): ExtractorLink = newExtractorLink(source, "$displayName HLS Master", url, ExtractorLinkType.M3U8) {
        this.referer = referer
        this.quality = Qualities.Unknown.value
        this.headers = headers
        this.audioTracks = audioTracks
    }
}
