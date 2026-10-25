package com.nikyokki

import org.jsoup.nodes.Document
import java.net.URI

/** Extracts lazy and ordinary player iframe URLs from a film/episode page. */
internal object HDFilmIzlePlayerParser {
    fun iframeUrls(document: Document, pageUrl: String): List<String> =
        document.select("iframe[src], iframe[data-src]")
            .mapNotNull { iframe ->
                listOf(iframe.attr("data-src"), iframe.attr("src"))
                    .firstNotNullOfOrNull { candidate -> resolveUrl(candidate, pageUrl) }
            }
            .filterNot(::isTrailerEmbed)
            .distinct()

    private fun resolveUrl(rawUrl: String?, baseUrl: String): String? {
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

    private fun isTrailerEmbed(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtube-nocookie.com" || host.endsWith(".youtube-nocookie.com") ||
            host == "youtu.be"
    }
}
