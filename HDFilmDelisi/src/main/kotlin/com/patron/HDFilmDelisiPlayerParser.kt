package com.patron

import java.net.URI

/**
 * hdfilmdelisi.one bir Next.js (React Server Components) sitesidir. Video verileri
 * iki farklı yerde durur:
 *
 *  1. RSC flight payload içinde kaçış karakterli JSON olarak:
 *       \"videoUrl\":\"https://player.vidmody.com/...\"
 *  2. JSON-LD VideoObject bloğunda (yine flight içinde kaçık):
 *       \"contentUrl\":\"https://hdfilmdelisi.one/vids/...mp4\"
 *       \"embedUrl\":\"https://hdfilmdelisi.one/embed/...\"
 *
 * Bu nesne yalnızca String/Regex tabanlı çözümleme yapar; ağ çağrısı içermez ve
 * tamamen birim testleriyle doğrulanabilir.
 */
internal data class PlayerVideoRef(
    val url: String,
    val kind: PlayerVideoRef.Kind,
) {
    enum class Kind { VIDMODY, DIRECT_MEDIA, EMBED }
}

internal data class HlsSubtitleTrack(val label: String, val url: String)

internal data class HlsAudioTrack(val label: String, val url: String)

internal object HDFilmDelisiPlayerParser {

    private val vidModyUrlPattern = Regex(
        """https?:\\?/\\?/(?:player\.)?vidmody\.com[^\s"'<>]+""",
        RegexOption.IGNORE_CASE,
    )
    private val contentUrlPattern = Regex(
        """\\?"?contentUrl\\?"?\s*:\s*\\?"(https?:[^"\s]+\.(?:mp4|m3u8)(?:\?[^"\s]*)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val directVidsPattern = Regex(
        """(https?:\\?/\\?/[^\s"'<>]*?\\?/vids\\?/[^\s"'<>]+\.(?:mp4|m3u8)(?:\?[^\s"'<>]*)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val embedUrlPattern = Regex(
        """(https?:\\?/\\?/[^\s"'<>]*?\\?/embed\\?/[^\s"'<>]+)""",
        RegexOption.IGNORE_CASE,
    )
    private val scriptFilePattern = Regex(
        """["']?file["']?\s*:\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val varIdPattern = Regex(
        """(?:var|let|const)\s+id\s*=\s*["']([^"']+)["']""",
    )
    private val inlineIdPattern = Regex(
        """\bid\s*:\s*["']([^"']+)["']""",
    )
    private val mediaLinePattern = Regex("""#EXT-X-MEDIA:[^\r\n]+""")
    private val attrPatternCache = HashMap<String, Regex>()

    /**
     * Sayfa HTML'inden sırayla VidMody player, doğrudan medya ve embed
     * referanslarını çıkarır. Tekrar eden URL'ler korunmaz ve sıralama sabittir.
     */
    fun playerRefs(html: String): List<PlayerVideoRef> {
        val ordered = LinkedHashMap<String, PlayerVideoRef>()

        vidModyUrlPattern.findAll(html).forEach { match ->
            val url = normalizeUrl(match.value)
            if (url.isNotBlank()) ordered.putIfAbsent(url, PlayerVideoRef(url, PlayerVideoRef.Kind.VIDMODY))
        }

        contentUrlPattern.findAll(html).forEach { match ->
            val url = normalizeUrl(match.groupValues[1])
            if (url.isNotBlank()) ordered.putIfAbsent(url, PlayerVideoRef(url, PlayerVideoRef.Kind.DIRECT_MEDIA))
        }
        directVidsPattern.findAll(html).forEach { match ->
            val url = normalizeUrl(match.groupValues[1])
            if (url.isNotBlank()) ordered.putIfAbsent(url, PlayerVideoRef(url, PlayerVideoRef.Kind.DIRECT_MEDIA))
        }

        embedUrlPattern.findAll(html).forEach { match ->
            val url = normalizeUrl(match.groupValues[1])
            if (url.isNotBlank()) ordered.putIfAbsent(url, PlayerVideoRef(url, PlayerVideoRef.Kind.EMBED))
        }

        return ordered.values.toList()
    }

    /** player.vidmody.com sayfasındaki `var id = '...'` değerini çözer. */
    fun extractVarId(playerHtml: String): String? =
        varIdPattern.find(playerHtml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
            ?: inlineIdPattern.find(playerHtml)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

    /** Embed/player script bloğundaki ilk mp4/m3u8 dosya adresini çıkarır. */
    fun mediaFileFromScript(html: String): String? =
        scriptFilePattern.find(html)?.groupValues?.getOrNull(1)?.let(::normalizeUrl)

    /** HLS master playlistindeki tüm altyazı medya gruplarını çıkarır. */
    fun hlsSubtitles(masterPlaylist: String, baseUrl: String): List<HlsSubtitleTrack> =
        mediaLinePattern.findAll(masterPlaylist)
            .map { it.value }
            .filter { line -> line.contains("TYPE=SUBTITLES", ignoreCase = true) }
            .mapNotNull { line ->
                val label = attr(line, "NAME") ?: return@mapNotNull null
                val uri = attr(line, "URI") ?: return@mapNotNull null
                HlsSubtitleTrack(label, resolveUrl(baseUrl, normalizeUrl(uri)))
            }
            .toList()

    /** HLS master playlistindeki tüm ses medya gruplarını çıkarır. */
    fun hlsAudioTracks(masterPlaylist: String, baseUrl: String): List<HlsAudioTrack> =
        mediaLinePattern.findAll(masterPlaylist)
            .map { it.value }
            .filter { line -> line.contains("TYPE=AUDIO", ignoreCase = true) }
            .mapNotNull { line ->
                val label = attr(line, "NAME") ?: return@mapNotNull null
                val uri = attr(line, "URI") ?: return@mapNotNull null
                HlsAudioTrack(label, resolveUrl(baseUrl, normalizeUrl(uri)))
            }
            .toList()

    /** Göreceli bir HLS varlık adresini mutlak hale getirir. */
    fun resolveUrl(baseUrl: String, reference: String): String {
        if (reference.startsWith("http://") || reference.startsWith("https://")) return reference
        return try {
            URI(baseUrl).resolve(reference).toASCIIString()
        } catch (e: Exception) {
            val root = Regex("""(https?://[^/]+)""").find(baseUrl)?.groupValues?.get(1).orEmpty()
            "$root/${reference.removePrefix("/")}"
        }
    }

    /** RSC/JSON kaçış karakterlerini temizler (`\/`, `\"`, `\u002F`). */
    fun normalizeUrl(value: String): String = value
        .replace("\\u002F", "/")
        .replace("\\u002f", "/")
        .replace("\\/", "/")
        .replace("\\\"", "\"")
        .replace("\\", "")
        .trim()

    private fun attr(line: String, key: String): String? {
        val regex = attrPatternCache.getOrPut(key) { Regex("""$key="([^"]+)"""") }
        return regex.find(line)?.groupValues?.getOrNull(1)
    }
}
