package com.nikyokki

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

open class VidRameExtractor : ExtractorApi() {
    override val name = "VidRame"
    override val mainUrl = "https://vidrame.pro"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"
    private val encodedFileCall = Regex(
        """EE\.dd\s*\(\s*["']([^"']+)["']\s*\)""",
        RegexOption.IGNORE_CASE,
    )

    private fun rot13(value: String): String = value.map { character ->
        when (character) {
            in 'a'..'m' -> (character.code + 13).toChar()
            in 'n'..'z' -> (character.code - 13).toChar()
            in 'A'..'M' -> (character.code + 13).toChar()
            in 'N'..'Z' -> (character.code - 13).toChar()
            else -> character
        }
    }.joinToString("")

    /** Decodes the observed reverse(ROT13(Base64URL(url))) `EE.dd(...)` value. */
    private fun decodeSourceFile(file: String): String? {
        val encoded = encodedFileCall.find(file)?.groupValues?.get(1)
        if (encoded == null) {
            val backslash = 92.toChar().toString()
            val raw = file.trim()
                .replace(backslash + "/", "/")
                .replace(backslash + "u0026", "&", ignoreCase = true)
            if (raw.contains("EE.dd", ignoreCase = true)) return null
            return raw.takeIf {
                it.startsWith("https://", ignoreCase = true) ||
                    it.startsWith("http://", ignoreCase = true) ||
                    it.startsWith("//") || it.startsWith("/")
            }
        }

        val base64 = encoded
            .replace("-", "+")
            .replace("_", "/")
            .let { value -> value + "=".repeat((4 - value.length % 4) % 4) }

        return runCatching {
            val obfuscatedUrl = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            rot13(obfuscatedUrl).reversed()
        }.getOrNull()
    }

    private fun sourceType(type: String, url: String): ExtractorLinkType {
        val normalizedType = type.lowercase()
        val path = url.substringBefore('?').substringBefore('#')
        return when {
            normalizedType.contains("dash") || path.endsWith(".mpd", ignoreCase = true) ->
                ExtractorLinkType.DASH
            normalizedType.contains("mp4") || path.endsWith(".mp4", ignoreCase = true) ->
                ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val document = app.get(
            url,
            headers = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.5",
            ),
            referer = referer,
        ).document

        val scripts = document.select("script")
            .map { it.data() }
            .filter { it.isNotBlank() }
        val sources = scripts.flatMap { VidRamePlayerParser.videoSources(it) }
            .distinctBy { it.file }
        val subtitles = scripts.flatMap { VidRamePlayerParser.subtitleTracks(it) }
            .distinctBy { it.file }

        subtitles.forEach { track ->
            val subtitleUrl = VidRamePlayerParser.resolveUrl(track.file, url) ?: return@forEach
            val label = track.label.takeIf { it.isNotBlank() }
                ?: track.language.takeIf { it.isNotBlank() }
                ?: "Altyazı"
            subtitleCallback.invoke(SubtitleFile(label, subtitleUrl))
        }

        if (sources.isEmpty()) {
            Log.d("VidEx", "No VidRame video source entries found")
            return
        }

        sources.forEach { source ->
            val decodedFile = decodeSourceFile(source.file) ?: return@forEach
            val videoUrl = VidRamePlayerParser.resolveUrl(decodedFile, url) ?: return@forEach
            val quality = source.label.takeIf { it.isNotBlank() }
                ?.let { getQualityFromName(it) }
                ?: Qualities.Unknown.value
            val linkName = source.label.takeIf { it.isNotBlank() }
                ?.let { "$name - $it" }
                ?: name

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = linkName,
                    url = videoUrl,
                    type = sourceType(source.type, videoUrl),
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = quality
                },
            )
        }
    }
}
