package com.blackhope01.extractors

/** Parsed media references from the inline JWPlayer configuration used by EksenLoad. */
internal data class SubtitleTrack(
    val label: String,
    val url: String,
)

internal object FilmEkseniPlayerParser {
    private val masterPlaylistPattern = Regex(
        """["']?file["']?\s*:\s*["']([^"']+?\.m3u8[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val mediaFilePattern = Regex(
        """["']?file["']?\s*:\s*["']([^"']+\.(?:m3u8|mp4)(?:\?[^"']*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val objectPattern = Regex("""\{[^{}]*\}""")
    private val subtitleUrlPattern = Regex(
        """["']?file["']?\s*:\s*["']([^"']+\.vtt[^"']*)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val subtitleLabelPattern = Regex(
        """["']?label["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val iframePattern = Regex(
        """(?:data-)?src\s*=\s*["'](https?[^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    fun masterPlaylistUrl(source: String): String? = masterPlaylistPattern
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.normalizePlayerUrl()

    /** Tüm m3u8/mp4 dosya adreslerini tekilleştirerek döndürür. */
    fun mediaFiles(source: String): List<String> = mediaFilePattern
        .findAll(source)
        .mapNotNull { it.groupValues.getOrNull(1)?.normalizePlayerUrl()?.takeIf(String::isNotBlank) }
        .distinct()
        .toList()

    /** Bir HTML şablonundaki iframe/medya adreslerini (data-src veya src) çıkarır. */
    fun iframeSources(source: String): List<String> = iframePattern
        .findAll(source)
        .mapNotNull { it.groupValues.getOrNull(1)?.normalizePlayerUrl()?.takeIf(String::isNotBlank) }
        .distinct()
        .toList()

    fun subtitles(source: String): List<SubtitleTrack> = objectPattern.findAll(source)
        .mapNotNull { match ->
            val block = match.value
            val url = subtitleUrlPattern.find(block)?.groupValues?.getOrNull(1)
                ?.normalizePlayerUrl()
                ?: return@mapNotNull null
            val label = subtitleLabelPattern.find(block)?.groupValues?.getOrNull(1)
                ?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            SubtitleTrack(label, url)
        }
        .toList()

    private fun String.normalizePlayerUrl(): String = replace("\\/", "/")
        .replace("&amp;", "&")
}
