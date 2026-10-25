package com.blackhope01.extractors

/** Parsed media references from the inline JWPlayer configuration used by EksenLoad. */
internal data class SubtitleTrack(
    val label: String,
    val url: String,
)

internal object FilmEkseniPlayerParser {
    private val masterPlaylistPattern = Regex(
        """["']?file["']?\s*:\s*["']([^"']+master\.m3u8[^"']*)["']""",
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

    fun masterPlaylistUrl(source: String): String? = masterPlaylistPattern
        .find(source)
        ?.groupValues
        ?.getOrNull(1)
        ?.normalizePlayerUrl()

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
