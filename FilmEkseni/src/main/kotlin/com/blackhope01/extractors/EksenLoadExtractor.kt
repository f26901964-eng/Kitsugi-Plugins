package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
        val playlistUrl = FilmEkseniPlayerParser.masterPlaylistUrl(source)
            ?: throw ErrorLoadingException("EksenLoad master.m3u8 link not found")

        FilmEkseniPlayerParser.subtitles(source).forEach { track ->
            subtitleCallback(SubtitleFile(track.label, track.url))
        }

        Log.d(name, "m3u8 >> $playlistUrl")
        callback(
            newExtractorLink(
                source = name,
                name = "$name - $languageLabel",
                url = playlistUrl,
            ) {
                this.referer = cleanUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
