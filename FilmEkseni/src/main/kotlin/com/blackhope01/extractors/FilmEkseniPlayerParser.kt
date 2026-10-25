package com.blackhope01.extractors

import java.net.URI
import java.util.Locale

/** Parsed subtitle rendition from an inline player config or an HLS master playlist. */
internal data class SubtitleTrack(
    val label: String,
    val url: String,
    val language: String? = null,
)

/** Parsed alternate audio rendition; the master playlist remains the playable source. */
internal data class AudioTrack(
    val label: String,
    val url: String,
    val language: String? = null,
)

internal object FilmEkseniPlayerParser {
    private val mediaFilePattern = Regex(
        """["']?(?:file|src|url|contentUrl|videoUrl)["']?\s*:\s*["']([^"'<>]+?\.(?:m3u8|mpd|mp4|m4v|webm|mov|mkv)(?:[?#][^"'<>]*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val htmlMediaPattern = Regex(
        """(?:src|href)\s*=\s*["']([^"'<>]+?\.(?:m3u8|mpd|mp4|m4v|webm|mov|mkv)(?:[?#][^"'<>]*)?)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val iframePattern = Regex(
        """<iframe\b[^>]*(?:data-src|src)\s*=\s*["']([^"']+)["'][^>]*>""",
        RegexOption.IGNORE_CASE,
    )
    private val fieldFilePattern = Regex(
        """["']?(?:file|src|url)["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val labelPattern = Regex(
        """["']?(?:label|name)["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val languagePattern = Regex(
        """["']?(?:srclang|language|lang)["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val kindPattern = Regex(
        """["']?kind["']?\s*:\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )
    private val subtitleExtensionPattern = Regex(
        """\.(?:vtt|srt|ass|ssa|ttml|dfxp)(?:[?#]|$)""",
        RegexOption.IGNORE_CASE,
    )
    private val mediaLinePattern = Regex("""#EXT-X-MEDIA:[^\r\n]+""")
    private val hlsAttributePattern = Regex(
        """(?:^|,)\s*([A-Z0-9-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^,]*))""",
        RegexOption.IGNORE_CASE,
    )

    fun masterPlaylistUrl(source: String): String? = mediaFiles(source)
        .firstOrNull { it.contains(".m3u8", ignoreCase = true) }

    /** Collect player media references from JSON, JS, or embedded HTML. */
    fun mediaFiles(source: String): List<String> = sequence {
        yieldAll(mediaFilePattern.findAll(source).map { it.groupValues[1] })
        yieldAll(htmlMediaPattern.findAll(source).map { it.groupValues[1] })
    }
        .map(::normalizePlayerUrl)
        .filter(String::isNotBlank)
        .distinct()
        .toList()

    /** Extract iframe URLs from a decoded player template, including data-src. */
    fun iframeSources(source: String): List<String> = iframePattern.findAll(source)
        .mapNotNull { match ->
            match.groupValues.getOrNull(1)?.let(::normalizePlayerUrl)?.takeIf(String::isNotBlank)
        }
        .distinct()
        .toList()

    /** Extract external subtitle tracks without restricting their language. */
    fun subtitles(source: String): List<SubtitleTrack> = objectBlocks(source)
        // A parent config may contain a source .m3u8 and a nested subtitle kind;
        // inspect leaf track objects only so those fields cannot be cross-paired.
        .filter(::isLeafObject)
        .mapNotNull { block ->
            val rawUrl = fieldFilePattern.find(block)?.groupValues?.getOrNull(1)
                ?: return@mapNotNull null
            val url = normalizePlayerUrl(rawUrl)
            if (url.isBlank()) return@mapNotNull null

            val kind = kindPattern.find(block)?.groupValues?.getOrNull(1).orEmpty()
            if (!subtitleExtensionPattern.containsMatchIn(url) &&
                !kind.equals("captions", ignoreCase = true) &&
                !kind.equals("subtitles", ignoreCase = true)
            ) return@mapNotNull null

            val language = languagePattern.find(block)?.groupValues?.getOrNull(1)
                ?.let(::normalizePlayerUrl)?.takeIf(String::isNotBlank)
            val label = labelPattern.find(block)?.groupValues?.getOrNull(1)
                ?.let(::normalizePlayerUrl)?.takeIf(String::isNotBlank)
                ?: language
                ?: "Subtitle"
            SubtitleTrack(label, url, language)
        }
        .distinctBy { it.url }
        .toList()

    fun hasHlsAudioRenditions(masterPlaylist: String): Boolean = mediaLinePattern.findAll(masterPlaylist)
        .map { hlsAttributes(it.value)["TYPE"] }
        .any { it.equals("AUDIO", ignoreCase = true) }

    fun hasHlsSubtitleRenditions(masterPlaylist: String): Boolean = mediaLinePattern.findAll(masterPlaylist)
        .map { hlsAttributes(it.value)["TYPE"] }
        .any { it.equals("SUBTITLES", ignoreCase = true) }

    fun hlsAudioTracks(masterPlaylist: String, baseUrl: String): List<AudioTrack> =
        mediaLinePattern.findAll(masterPlaylist)
            .map { hlsAttributes(it.value) }
            .mapNotNull { attributes ->
                if (!attributes["TYPE"].equals("AUDIO", ignoreCase = true)) return@mapNotNull null
                val uri = attributes["URI"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val language = attributes["LANGUAGE"]?.takeIf(String::isNotBlank)
                val label = attributes["NAME"]?.takeIf(String::isNotBlank) ?: language ?: "Audio"
                AudioTrack(label, resolveUrl(baseUrl, normalizePlayerUrl(uri)), language)
            }
            .distinctBy { it.url }
            .toList()

    fun hlsSubtitles(masterPlaylist: String, baseUrl: String): List<SubtitleTrack> =
        mediaLinePattern.findAll(masterPlaylist)
            .map { hlsAttributes(it.value) }
            .mapNotNull { attributes ->
                if (!attributes["TYPE"].equals("SUBTITLES", ignoreCase = true)) return@mapNotNull null
                val uri = attributes["URI"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val language = attributes["LANGUAGE"]?.takeIf(String::isNotBlank)
                val label = attributes["NAME"]?.takeIf(String::isNotBlank) ?: language ?: "Subtitle"
                SubtitleTrack(label, resolveUrl(baseUrl, normalizePlayerUrl(uri)), language)
            }
            .distinctBy { it.url }
            .toList()

    /** Resolve absolute, protocol-relative, root-relative, and page-relative URLs. */
    fun resolveUrl(baseUrl: String, reference: String): String {
        val cleanReference = normalizePlayerUrl(reference)
        if (cleanReference.startsWith("https://", ignoreCase = true) ||
            cleanReference.startsWith("http://", ignoreCase = true)
        ) return cleanReference
        return runCatching { URI(baseUrl).resolve(cleanReference).toASCIIString() }
            .getOrElse { cleanReference }
    }

    private fun hlsAttributes(line: String): Map<String, String> =
        hlsAttributePattern.findAll(line.substringAfter(':', ""))
            .mapNotNull { match ->
                val key = match.groupValues[1].uppercase(Locale.ROOT)
                val value = sequenceOf(
                    match.groupValues.getOrNull(2),
                    match.groupValues.getOrNull(3),
                    match.groupValues.getOrNull(4),
                ).filterNotNull().firstOrNull { it.isNotBlank() }?.trim()
                    ?: return@mapNotNull null
                key to value
            }
            .toMap()

    /** Ignore parent config objects when they contain separate nested source/track objects. */
    private fun isLeafObject(block: String): Boolean {
        var quote: Char? = null
        var escaped = false
        for (index in 1 until block.lastIndex) {
            val char = block[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                continue
            }
            when (char) {
                '\'', '"', '`' -> quote = char
                '{' -> return false
            }
        }
        return true
    }

    /** Return every balanced object, including nested track objects in JS config. */
    private fun objectBlocks(source: String): Sequence<String> = sequence {
        val starts = ArrayDeque<Int>()
        var quote: Char? = null
        var escaped = false
        for (index in source.indices) {
            val char = source[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == quote) {
                    quote = null
                }
                continue
            }

            when (char) {
                '\'', '"', '`' -> quote = char
                '{' -> starts.addLast(index)
                '}' -> if (starts.isNotEmpty()) {
                    val start = starts.removeLast()
                    yield(source.substring(start, index + 1))
                }
            }
        }
    }

    fun normalizePlayerUrl(value: String): String = value
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\\\/", "/")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\\"", "\"")
        .replace("\\", "")
        .trim()
        .trim('"', '\'')
}
