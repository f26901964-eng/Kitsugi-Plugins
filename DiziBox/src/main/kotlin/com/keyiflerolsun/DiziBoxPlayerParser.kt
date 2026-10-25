package com.keyiflerolsun

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Parsing helpers for the current DiziBox player markup. The site uses
 * #video-area iframes and a .woca-linkpages-dd selector for alternate player
 * pages; older releases also used .video-toolbar options.
 */
internal object DiziBoxPlayerParser {
    data class EncryptedPayload(val ciphertext: String, val password: String)
    data class MediaSource(val url: String, val type: String = "", val kind: String = "")

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
    private val objectPattern = Regex("""\{[^{}]*}""")
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

    /** Reads JWPlayer's file/type properties and plain HTML5 source tags. */
    fun mediaSources(html: String): List<MediaSource> {
        val sources = mutableListOf<MediaSource>()

        objectPattern.findAll(html).forEach { objectMatch ->
            val source = mediaSourceFromObject(objectMatch.value) ?: return@forEach
            sources += source
        }

        // Some player pages expose a literal <video>/<source> rather than JWPlayer config.
        val document = org.jsoup.Jsoup.parse(html)
        document.select("video[src], video source[src], source[src]").forEach { element ->
            val url = element.attr("src").trim()
            if (url.isNotBlank()) {
                sources += MediaSource(url, element.attr("type").trim())
            }
        }

        if (sources.isEmpty()) {
            val type = propertyValue(typeProperty, html).orEmpty()
            val kind = propertyValue(kindProperty, html).orEmpty()
            fileProperty.findAll(html).forEach { match ->
                val url = normalizeJsLiteral(match.groupValues[1].ifBlank { match.groupValues[2] })
                if (url.isNotBlank()) sources += MediaSource(url, type, kind)
            }
        }

        return sources
            .filterNot { source ->
                source.kind.equals("captions", ignoreCase = true) ||
                    source.kind.equals("subtitles", ignoreCase = true) ||
                    source.url.substringBefore('?').substringBefore('#')
                        .endsWith(".vtt", ignoreCase = true) ||
                    source.url.substringBefore('?').substringBefore('#')
                        .endsWith(".srt", ignoreCase = true)
            }
            .distinctBy { it.url }
    }

    fun isHls(source: MediaSource): Boolean =
        source.type.contains("mpegurl", ignoreCase = true) ||
            source.type.contains("hls", ignoreCase = true) ||
            source.url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
            source.url.contains("/embed/sheila/", ignoreCase = true)

    fun isMp4(source: MediaSource): Boolean =
        source.type.contains("mp4", ignoreCase = true) ||
            source.url.substringBefore('?').substringBefore('#').endsWith(".mp4", ignoreCase = true)

    fun isSupportedPlayerUrl(url: String, siteUrl: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (!knownPlayerPath.containsMatchIn(path)) return false

        val host = normalizedHost(url) ?: return false
        val siteHost = normalizedHost(siteUrl) ?: return false
        val sameSite = host == siteHost || host.removePrefix("www.") == siteHost.removePrefix("www.")
        val molyStream = Regex("""(?:^|\.)molystream\.(?:org|net)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(host)
        return sameSite || (molyStream && path.contains("/embed/", ignoreCase = true))
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

    private fun iframeUrls(elements: List<Element>, pageUrl: String): List<String> =
        elements.mapNotNull { iframe ->
            val candidates = listOf(iframe.attr("src"), iframe.attr("data-src"))
            candidates.firstNotNullOfOrNull { candidate -> resolveUrl(candidate, pageUrl) }
        }.distinct()

    private fun mediaSourceFromObject(sourceObject: String): MediaSource? {
        val fileMatch = fileProperty.find(sourceObject) ?: return null
        val url = normalizeJsLiteral(fileMatch.groupValues[1].ifBlank { fileMatch.groupValues[2] })
        if (url.isBlank()) return null

        val kind = propertyValue(kindProperty, sourceObject).orEmpty()
        if (kind.equals("captions", ignoreCase = true) || kind.equals("subtitles", ignoreCase = true)) {
            return null
        }
        val type = propertyValue(typeProperty, sourceObject).orEmpty()
        return MediaSource(url, type, kind)
    }

    private fun normalizeJsLiteral(value: String): String {
        val backslash = 92.toChar().toString()
        return value
            .replace(backslash + "/", "/")
            .replace(backslash + "u0026", "&", ignoreCase = true)
            .replace(backslash + "x26", "&", ignoreCase = true)
    }

    private fun propertyValue(regex: Regex, source: String): String? {
        val match = regex.find(source) ?: return null
        return match.groupValues[1].ifBlank { match.groupValues[2] }
    }

    private fun normalizedHost(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()
}
