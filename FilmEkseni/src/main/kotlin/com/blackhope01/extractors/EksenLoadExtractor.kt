package com.blackhope01.extractors

import android.util.Log
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

open class EksenLoadExtractor : ExtractorApi() {
    override val name = "Eksenload"
    override val mainUrl = "https://eksenload.top"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val langTag = url.substringAfterLast('#', "tr")
        val cleanUrl = url.substringBefore('#')
        val pageReferer = referer?.takeIf(String::isNotBlank) ?: cleanUrl
        val requestHeaders = mapOf("User-Agent" to userAgent, "Referer" to pageReferer)
        val source = try {
            app.get(cleanUrl, headers = requestHeaders).text
        } catch (error: CancellationException) {
            throw error
        }

        val languageLabel = when (langTag) {
            "dublaj" -> "Türkçe Dublaj"
            "altyazi" -> "Türkçe Altyazılı"
            else -> "Türkçe"
        }
        val seenSubtitles = HashSet<String>()
        FilmEkseniPlayerParser.subtitles(source).forEach { track ->
            val trackUrl = FilmEkseniPlayerParser.resolveUrl(cleanUrl, track.url)
            if (seenSubtitles.add(trackUrl)) {
                val label = track.language?.takeIf(String::isNotBlank) ?: track.label
                subtitleCallback(
                    newSubtitleFile(label, trackUrl) {
                        this.headers = requestHeaders
                    },
                )
            }
        }

        val seenLinks = HashSet<String>()
        var found = false
        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank() && seenLinks.add(link.url)) {
                found = true
                if (link.headers.isNullOrEmpty()) link.headers = requestHeaders
                callback(link)
            }
        }

        val mediaFiles = FilmEkseniPlayerParser.mediaFiles(source)
        mediaFiles.forEach { rawUrl ->
            val mediaUrl = FilmEkseniPlayerParser.resolveUrl(cleanUrl, rawUrl)
            if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                FilmEkseniHlsResolver.emit(
                    url = mediaUrl,
                    source = name,
                    referer = cleanUrl,
                    headers = requestHeaders,
                    subtitleCallback = subtitleCallback,
                    callback = emitLink,
                )
            } else {
                Log.d(name, "medya >> $mediaUrl")
                val type = if (mediaUrl.contains(".mpd", ignoreCase = true)) {
                    ExtractorLinkType.DASH
                } else {
                    ExtractorLinkType.VIDEO
                }
                emitLink(
                    newExtractorLink(name, "$name - $languageLabel", mediaUrl, type) {
                        this.referer = cleanUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = requestHeaders
                    },
                )
            }
        }

        FilmEkseniPlayerParser.iframeSources(source).forEach { rawFrame ->
            val frameUrl = FilmEkseniPlayerParser.resolveUrl(cleanUrl, rawFrame)
            if (seenLinks.add("iframe:$frameUrl")) {
                try {
                    loadExtractor(frameUrl, cleanUrl, subtitleCallback) { link ->
                        emitLink(link)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(name, "İç iframe çözülemedi ($frameUrl): ${error.message}")
                }
            }
        }

        if (!found) throw ErrorLoadingException("EksenLoad oynatıcı kaynağı çözülemedi")
    }
}
