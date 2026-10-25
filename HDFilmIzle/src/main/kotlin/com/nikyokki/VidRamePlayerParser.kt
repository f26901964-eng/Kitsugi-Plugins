package com.nikyokki

import java.net.URI

/** Parses the observed VidRame `sources` and `configs.tracks` script assignments. */
internal object VidRamePlayerParser {
    data class VideoSource(
        val file: String,
        val type: String = "",
        val label: String = "",
    )

    data class SubtitleTrack(
        val file: String,
        val label: String = "",
        val kind: String = "",
        val language: String = "",
    )

    private val sourcesArray = Regex("""\bsources\s*:\s*\[""", RegexOption.IGNORE_CASE)
    private val tracksArray = Regex("""\bconfigs\s*\.\s*tracks\s*=\s*\[""", RegexOption.IGNORE_CASE)
    private val objectPattern = Regex("""\{[^{}]*}""", RegexOption.DOT_MATCHES_ALL)
    private val encodedFileProperty = Regex(
        """(?:[\"']?file[\"']?)\s*:\s*(EE\.dd\s*\(\s*[\"']([^\"']+)[\"']\s*\))""",
        RegexOption.IGNORE_CASE,
    )
    private val quotedFileProperty = Regex(
        """(?:[\"']?file[\"']?)\s*:\s*[\"']((?:\\.|[^\"'])*?)[\"']""",
        RegexOption.IGNORE_CASE,
    )

    fun videoSources(script: String): List<VideoSource> {
        val sources = extractArray(script, sourcesArray) ?: return emptyList()
        return objectPattern.findAll(sources)
            .mapNotNull { objectMatch ->
                val sourceObject = objectMatch.value
                val file = fileValue(sourceObject)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                VideoSource(
                    file = normalizeJsValue(file),
                    type = stringProperty(sourceObject, "type").orEmpty(),
                    label = stringProperty(sourceObject, "label").orEmpty(),
                )
            }
            .distinctBy { it.file }
    }

    /** Missing or malformed `configs.tracks` is non-fatal; video sources still parse. */
    fun subtitleTracks(script: String): List<SubtitleTrack> {
        val tracks = extractArray(script, tracksArray) ?: return emptyList()
        return objectPattern.findAll(tracks)
            .mapNotNull { objectMatch ->
                val trackObject = objectMatch.value
                val file = fileValue(trackObject)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val kind = stringProperty(trackObject, "kind").orEmpty()
                val cleanPath = file.substringBefore('?').substringBefore('#')
                val extension = cleanPath.substringAfterLast('.', "")
                val hasSubtitleExtension = extension.equals("vtt", ignoreCase = true) ||
                    extension.equals("srt", ignoreCase = true)
                val isSubtitleKind = kind.equals("captions", ignoreCase = true) ||
                    kind.equals("caption", ignoreCase = true) ||
                    kind.equals("subtitles", ignoreCase = true)

                if (kind.isNotBlank() && !isSubtitleKind) return@mapNotNull null
                if (!isSubtitleKind && !hasSubtitleExtension) return@mapNotNull null

                SubtitleTrack(
                    file = normalizeJsValue(file),
                    label = stringProperty(trackObject, "label").orEmpty(),
                    kind = kind,
                    language = stringProperty(trackObject, "language")
                        ?: stringProperty(trackObject, "lang")
                        ?: stringProperty(trackObject, "srclang").orEmpty(),
                )
            }
            .distinctBy { it.file }
    }

    fun resolveUrl(rawUrl: String?, baseUrl: String): String? {
        val raw = rawUrl?.let(::normalizeJsValue)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
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

    private fun extractArray(script: String, marker: Regex): String? {
        val match = marker.find(script) ?: return null
        val start = script.indexOf('[', match.range.first)
        if (start < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until script.length) {
            val character = script[index]
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
                    if (depth == 0) return script.substring(start + 1, index)
                }
            }
        }
        return null
    }

    private fun fileValue(sourceObject: String): String? =
        encodedFileProperty.find(sourceObject)?.groupValues?.get(1)
            ?: quotedFileProperty.find(sourceObject)?.groupValues?.get(1)

    private fun stringProperty(sourceObject: String, property: String): String? {
        val name = Regex.escape(property)
        val pattern = Regex(
            """(?:[\"']?$name[\"']?)\s*:\s*[\"']((?:\\.|[^\"'])*?)[\"']""",
            RegexOption.IGNORE_CASE,
        )
        return pattern.find(sourceObject)?.groupValues?.get(1)?.let(::normalizeJsValue)
    }

    private fun normalizeJsValue(value: String): String {
        val backslash = 92.toChar().toString()
        return value
            .replace(backslash + "/", "/")
            .replace(backslash + "u0026", "&", ignoreCase = true)
            .replace(backslash + "x26", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
    }
}
