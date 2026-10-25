package com.blackhope01.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile

class VidMolyExtractor : ExtractorApi() {
    override val name = "VidMoly"
    override val mainUrl = "https://vidmoly.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val lang = url.substringAfterLast("#", "tr")
        val cleanUrl = url.substringBefore("#")
            .let { if (it.contains("/w/")) it.replaceFirst("/w/", "/embed-") + ".html" else it }

        val response = app.get(cleanUrl, referer = referer)
        val document = response.document

        val langLabel = when (lang) {
            "dublaj" -> " - Türkçe Dublaj"
            "altyazi" -> " - Türkçe Altyazılı"
            else -> ""
        }
        val displayName = "$name$langLabel"

        // Güncel VidMoly oynatıcısı sources: [{file: "..."}] scripti kullanır.
        val script = document.select("script")
            .firstOrNull { it.data().contains("sources:") }
            ?.data()
            ?: response.text

        FilmEkseniPlayerParser.subtitles(script).forEach { track ->
            subtitleCallback(newSubtitleFile(track.label, track.url))
        }

        val mediaFiles = FilmEkseniPlayerParser.mediaFiles(script)
        mediaFiles.forEach { mediaUrl ->
            val type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = displayName,
                    url = mediaUrl,
                    type = type,
                ) {
                    this.referer = referer ?: cleanUrl
                    this.quality = Qualities.Unknown.value
                },
            )
        }
    }
}
