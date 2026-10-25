package com.keyiflerolsun

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Parsing helpers for DiziBox episode/player markup and its MolyStream embeds.
 * The live episode page itself is not available to the build environment, so
 * selectors and payload parsing stay deliberately limited to observed markup.
 */
internal object DiziBoxPlayerParser {
    data class EncryptedPayload(val ciphertext: String, val password: String)
    data class MediaSource(
        val url: String,
        val type: String = "",
        val kind: String = "",
        val label: String = "",
    )
    data class SubtitleTrack(
        val url: String,
        val label: String,
        val kind: String = "",
        val language: String = "",
    )

    private val aesDecryptCall = Regex(
        """CryptoJS\s*\.\s*AES\s*\.\s*decrypt\s*\(\s*(?:"([^"]+)"|'([^']+)')\s*,\s*(?:"([^"]*)"|'([^']*)')\s*\)""",
        RegexOption.IGNORE_CASE,
    )
    private val fileProperty = Regex(
        """(?:[\"']file[\"']|\bfile)\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val typeProperty = Regex(
        """(?:[\"']type[\"']|\btype)\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val kindProperty = Regex(
        """(?:[\"']kind[\"']|\bkind)\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val labelProperty = Regex(
        """(?:[\"']label[\"']|\blabel)\s*:\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE,
    )
    private val languageProperty = Regex(
        """(?:[\"'](?:srclang|language|lang)[\"']|\b(?:srclang|language|lang)\b)\s*:\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE,
    )
    private val objectPattern = Regex("""\{[^{}]*}""")
    private val sourcesArray = Regex("""\bsources\s*:\s*\[""", RegexOption.IGNORE_CASE)
    private val knownPlayerPath = Regex(
        """/(?:player/)?(?:[a-z0-9_-]+/)?(?:king|moly|haydi|player)\.php$|/embed/""",
        RegexOption.IGNORE_CASE,
    )

    /** Player iframe URLs in the episode's current #video-area. */
    fun playerIframes(document: Document, pageUrl: String): List<String> =
        iframeUrls(document.select("#video-area iframe[src], #video-area iframe[data-src]"), pageUrl)

    /** Nested frames inside a player wrapper (for example King -> MolyStream). */
    fun nestedIframes(document: Document, pageUrl: String): List<String> =
        iframeUrls(document.select("iframe[src], iframe[data-src]"), pageUrl)

    /** Alternate player pages exposed by the current selector and the legacy toolbar. */
    fun alternatePlayerPages(document: Document, pageUrl: String): List<String> {
        val options = document.select(
            "select.woca-linkpages-dd option[value], " +
                "select.woca-linkpages-dd option[href], " +
                "div.video-toolbar option[value], " +
                "div.video-toolbar option[href]",
        )
        return options.mapNotNull { option ->
            val raw = option.attr("value").ifBlank { option.attr("href") }
            resolveUrl(raw, pageUrl)
        }.distinct()
    }

    fun encryptedPayload(html: String): EncryptedPayload? {
        val match = aesDecryptCall.find(html) ?: return null
        val ciphertext = match.groupValues[1].ifBlank { match.groupValues[2] }
        val password = match.groupValues[3].ifBlank { match.groupValues[4] }
        if (ciphertext.isBlank() || password.isBlank()) return null
        return EncryptedPayload(ciphertext, password)
    }

    /** Reads JWPlayer's file/type/kind/label properties and plain HTML5 source tags. */
    fun mediaSources(html: String): List<MediaSource> {
        val sources = mutableListOf<MediaSource>()
        val declaredSources = extractArray(html, sourcesArray)
        val sourceObjects = declaredSources?.let { objectPattern.findAll(it) } ?: objectPattern.findAll(html)

        sourceObjects.forEach { objectMatch ->
            val source = mediaSourceFromObject(objectMatch.value) ?: return@forEach
            sources += source
        }

        // Some player pages expose a literal <video>/<source> rather than JWPlayer config.
        val document = org.jsoup.Jsoup.parse(html)
        document.select("video[src], video source[src], source[src]").forEach { element ->
            val url = element.attr("src").trim()
            if (url.isNotBlank()) {
                sources += MediaSource(
                    url = url,
                    type = element.attr("type").trim(),
                    label = element.attr("label").trim(),
                )
            }
        }

        if (sources.isEmpty() && declaredSources == null) {
            fileProperty.findAll(html).forEach { match ->
                val url = normalizeJsLiteral(match.groupValues[1].ifBlank { match.groupValues[2] })
                if (url.isNotBlank()) sources += MediaSource(url)
            }
        }

        return sources
            .filterNot { source ->
                source.kind.equals("captions", ignoreCase = true) ||
                    source.kind.equals("subtitles", ignoreCase = true)
            }
            .distinctBy { it.url }
    }

    /** Extracts subtitle/caption tracks from player objects without treating video sources as tracks. */
    fun subtitleTracks(html: String): List<SubtitleTrack> =
        objectPattern.findAll(html)
            .mapNotNull { objectMatch -> subtitleTrackFromObject(objectMatch.value) }
            .distinctBy { it.url }
            .toList()

    fun isHls(source: MediaSource): Boolean =
        source.type.contains("mpegurl", ignoreCase = true) ||
            source.type.contains("hls", ignoreCase = true) ||
            source.url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
            source.url.contains("/embed/sheila/", ignoreCase = true)

    fun isMp4(source: MediaSource): Boolean =
        source.type.contains("mp4", ignoreCase = true) ||
            source.url.substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)

    /**
     * The independent MolyStream resolver distinguishes the player page
     * `/embed/{id}` from its stream endpoint `/embed/sheila/{id}`. Do not
     * rewrite a received URL before requesting it: the `sheila` route may
     * itself return a playlist. This companion URL is only a fallback when
     * the received `sheila` route returns HTML with no media configuration.
     */
    fun molyStreamPlayerPageUrl(url: String): String? {
        if (!isMolyStreamStreamUrl(url)) return null
        return url.replaceFirst(Regex("""/embed/sheila/""", RegexOption.IGNORE_CASE), "/embed/")
    }

    fun isMolyStreamStreamUrl(url: String): Boolean {
        if (!isMolyStreamHost(url)) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return Regex("""/embed/sheila/[^/]+/?$""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    fun isHlsManifest(body: String): Boolean {
        val header = body.removePrefix("\uFEFF").trimStart()
        return header.startsWith("#EXTM3U", ignoreCase = true) &&
            (header.length == 7 || header[7].isWhitespace())
    }

    /** MolyStream has been observed returning a plain HTTPS URL from its stream route. */
    fun plainHttpsTarget(body: String): String? {
        val candidate = body.removePrefix("\uFEFF").trim()
        if (!candidate.startsWith("https://", ignoreCase = true)) return null
        if (candidate.any { it.isWhitespace() || it.code < 0x20 || it == 0x27.toChar() || it == 0x22.toChar() }) {
            return null
        }
        return candidate.takeIf { resolveUrl(it, it) != null }
    }

    fun isMolyStreamHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return Regex("""(?:^|\.)molystream\.(?:org|net)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(host)
    }

    fun isSupportedPlayerUrl(url: String, siteUrl: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (!knownPlayerPath.containsMatchIn(path)) return false

        val host = normalizedHost(url) ?: return false
        val siteHost = normalizedHost(siteUrl) ?: return false
        val sameSite = host == siteHost || host.removePrefix("www.") == siteHost.removePrefix("www.")
        return sameSite || (isMolyStreamHost(url) && path.contains("/embed/", ignoreCase = true))
    }

    fun resolveUrl(rawUrl: String?, baseUrl: String): String? {
        val raw = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw == "#" || raw.equals("about:blank", ignoreCase = true) ||
            raw.startsWith("javascript:", ignoreCase = true) || raw.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }

        val resolved = runCatching {
            URI(baseUrl).resolve(raw.replace(" ", "%20")).toASCIIString()
        }.getOrNull() ?: return null
        if (!resolved.startsWith("https://", ignoreCase = true) &&
            !resolved.startsWith("http://", ignoreCase = true)
        ) return null
        return resolved
    }

    private fun extractArray(source: String, marker: Regex): String? {
        val match = marker.find(source) ?: return null
        val start = source.indexOf('[', match.range.first)
        if (start < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until source.length) {
            val character = source[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == quote) {
                    quote = null
                }
                continue
            }

            when (character) {
                '\'', '"', '`' -> quote = character
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start + 1, index)
                }
            }
        }
        return null
    }

    private fun iframeUrls(elements: List<Element>, pageUrl: String): List<String> =
        elements.mapNotNull { iframe ->
            val candidates = listOf(iframe.attr("src"), iframe.attr("data-src"))
            candidates.firstNotNullOfOrNull { candidate -> resolveUrl(candidate, pageUrl) }
        }.distinct()

    private fun mediaSourceFromObject(sourceObject: String): MediaSource? {
        val fileMatch = fileProperty.find(sourceObject) ?: return null
        val url = normalizeJsLiteral(fileMatch.groupValues[1].ifBlank { fileMatch.groupValues[2] })
        if (url.isBlank()) return null

        // Keep the source's explicit kind; a URL suffix is not a substitute for it.
        val kind = propertyValue(kindProperty, sourceObject).orEmpty()
        val type = propertyValue(typeProperty, sourceObject).orEmpty()
        val label = propertyValue(labelProperty, sourceObject).orEmpty()
        return MediaSource(url, type, kind, label)
    }

    private fun subtitleTrackFromObject(sourceObject: String): SubtitleTrack? {
        val fileMatch = fileProperty.find(sourceObject) ?: return null
        val url = normalizeJsLiteral(fileMatch.groupValues[1].ifBlank { fileMatch.groupValues[2] })
        if (url.isBlank()) return null

        val kind = propertyValue(kindProperty, sourceObject).orEmpty()
        val isSubtitleKind = kind.equals("captions", ignoreCase = true) ||
            kind.equals("subtitles", ignoreCase = true)
        val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        val isSubtitleFile = extension.equals("vtt", ignoreCase = true) ||
            extension.equals("srt", ignoreCase = true)
        if (kind.isNotBlank() && !isSubtitleKind) return null
        if (!isSubtitleKind && !isSubtitleFile) return null

        val language = propertyValue(languageProperty, sourceObject).orEmpty()
        val label = propertyValue(labelProperty, sourceObject)
            ?.takeIf { it.isNotBlank() }
            ?: language.takeIf { it.isNotBlank() }
            ?: "Altyazı"
        return SubtitleTrack(url, label, kind, language)
    }

    private fun normalizeJsLiteral(value: String): String {
        val backslash = 92.toChar().toString()
        return value
            .replace(backslash + "/", "/")
            .replace(backslash + "u0026", "&", ignoreCase = true)
            .replace(backslash + "x26", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
    }

    private fun propertyValue(regex: Regex, source: String): String? {
        val match = regex.find(source) ?: return null
        return match.groupValues[1].ifBlank { match.groupValues[2] }
    }

    private fun normalizedHost(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()
}
