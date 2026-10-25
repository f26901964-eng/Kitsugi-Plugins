package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.newSubtitleFile

open class EksenLoadExtractor : ExtractorApi() {
    override val name = "Eksenload"
    override val mainUrl = "https://eksenload.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val lang = url.substringAfterLast("#", "tr")
        val cleanUrl = url.substringBefore("#")
        val source = app.get(cleanUrl, referer = referer).text

        val languageLabel = when (lang) {
            "dublaj" -> "Türkçe Dublaj"
            "altyazi" -> "Türkçe Altyazılı"
            else -> "Türkçe Dublaj"
        }

        FilmEkseniPlayerParser.subtitles(source).forEach { track ->
            subtitleCallback(newSubtitleFile(track.label, track.url))
        }

        val mediaFiles = FilmEkseniPlayerParser.mediaFiles(source)
        if (mediaFiles.isEmpty()) {
            throw ErrorLoadingException("EksenLoad oynatıcı dosyası bulunamadı")
        }

        mediaFiles.forEach { mediaUrl ->
            val type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            Log.d(name, "medya >> $mediaUrl")
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name - $languageLabel",
                    url = mediaUrl,
                    type = type,
                ) {
                    this.referer = cleanUrl
                    this.quality = Qualities.Unknown.value
                },
            )
        }
    }
}
