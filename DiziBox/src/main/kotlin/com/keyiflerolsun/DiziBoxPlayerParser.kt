package com.keyiflerolsun

import com.fasterxml.jackson.databind.ObjectMapper
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
    data class HlsVariant(val url: String, val height: Int)
    data class DwlsResult(val title: String, val url: String, val thumbnail: String)
    data class EpisodeAnchor(val url: String, val season: Int, val episode: Int)

    private val mapper = ObjectMapper()

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

        // JWPlayer yapısı hiç çözülemezse düz medya/sheila/master.txt izlerini topla.
        if (sources.isEmpty()) {
            fallbackMediaUrls(html).forEach { sources += MediaSource(it) }
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

    // ────────── 2026 canlı zincirinden doğrulanmış ek ayrıştırıcılar ──────────

    /**
     * dbx.molystream `sheila` uç noktası HLS master listesini düz metin olarak
     * döndürür. Listedeki HER `#EXT-X-STREAM-INF` varyantı gerçek çözünürlüğüyle
     * (RESOLUTION=WxH) ayrı bir kaynak olarak üretilir; çözünürlük yoksa 0 döner.
     */
    fun hlsVariants(body: String, baseUrl: String): List<HlsVariant> {
        if (!isHlsManifest(body)) return emptyList()
        val ordered = LinkedHashMap<String, Int>()
        var pendingHeight = 0
        body.replace("\uFEFF", "").lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ->
                    pendingHeight = Regex("""RESOLUTION\s*=\s*\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.startsWith("#") || line.isBlank() -> Unit
                else -> {
                    resolveUrl(line, baseUrl)?.let { ordered.putIfAbsent(it, pendingHeight) }
                    pendingHeight = 0
                }
            }
        }
        return ordered.map { HlsVariant(it.key, it.value) }
    }

    /** Eski URL'ler `/eser/...` veya arşiv kutusuna taşındıysa hedef sayfayı verir. */
    fun archiveBoxLink(document: Document, pageUrl: String): String? =
        document.selectFirst("div#archive-box a.archive-title")
            ?.attr("href")
            ?.let { resolveUrl(it, pageUrl) }

    /**
     * Canlı tema aramayı `admin-ajax.php?action=dwls_search` XHR JSON'u ile yapar:
     * `{ results: [ { post_title, permalink, attachment_thumbnail } ] }`.
     */
    fun dwlsSearchResults(json: String): List<DwlsResult> {
        val root = runCatching { mapper.readTree(json) }.getOrNull() ?: return emptyList()
        val results = root.path("results")
        if (!results.isArray) return emptyList()
        return results.mapNotNull { node ->
            val title = node.path("post_title").asText("").trim()
            val permalink = node.path("permalink").asText("").trim()
            if (title.isBlank() || permalink.isBlank()) return@mapNotNull null
            DwlsResult(
                title = title,
                url = permalink.replace("\\/", "/"),
                thumbnail = node.path("attachment_thumbnail").asText("").replace("\\/", "/"),
            )
        }
    }

    /** dwls arama küçük resimleri 50x50 gelir; kartlarda kullanılan 200x290 sürümüne çevirir. */
    fun upscaleThumbnail(url: String): String {
        if (url.isBlank()) return url
        return Regex("""(?i)/\d{2,4}x\d{2,4}/""").replace(url) { match ->
            if (match.value.contains("200x290", ignoreCase = true)) match.value else "/200x290/"
        }
    }

    /**
     * Bölüm listesi `article.grid-box` dışındaki temalarda da çalışsın diye
     * `-sezon-N-bolum-M(-izle)` kalıbına uyan tüm çapaları toplar.
     */
    fun episodeAnchors(document: Document): List<EpisodeAnchor> {
        val slug = Regex("""-sezon-(\d+)-bolum-(\d+)(?:-izle)?/?""", RegexOption.IGNORE_CASE)
        val seen = LinkedHashMap<String, EpisodeAnchor>()
        document.select("a[href]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            val match = slug.find(href) ?: return@forEach
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
            seen.putIfAbsent(href, EpisodeAnchor(href, season, episode))
        }
        return seen.values.toList()
    }

    /**
     * king/moly/haydi sarmalayıcıları `wmode=opaque` olmadan boş/karartılmış sayfa
     * döndürebiliyor. Parametre yoksa ekler, varsa URL'ye dokunmaz.
     */
    fun withWOpaque(url: String): String {
        if (!Regex("""(?:king|moly|haydi)\.php""", RegexOption.IGNORE_CASE).containsMatchIn(url)) return url
        if (url.contains("wmode=", ignoreCase = true)) return url
        val separator = if (url.contains("?")) "&" else "?"
        return url + separator + "wmode=opaque"
    }

    /** JWPlayer yapılandırması çözülemediğinde açık medya/sheila URL'lerini tarar. */
    fun fallbackMediaUrls(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex(
            """["']?(https?:\\?/\\?/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']?""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { out.add(it.groupValues[1].replace("\\/", "/")) }
        Regex(
            """["']?(https?:\\?/\\?/[^"'\s<>]*(?:master\.txt|/embed/sheila/)[^"'\s<>]*)["']?""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { out.add(it.groupValues[1].replace("\\/", "/")) }
        return out.toList()
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
        }.filterNot { it.contains("\${") || it.contains("{{") }.distinct()

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
