package com.nikyokki

/**
 * URL patterns verified from the live HDFilmIzle catalog/detail pages:
 * series: /dizi/pluribus/
 * episode: /dizi/pluribus/sezon-1/bolum-1/
 */
internal object HDFilmIzleParser {
    fun isSeriesUrl(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#')
        return path.contains("/dizi/", ignoreCase = true)
    }

    fun parseEpisodeUrl(url: String): HDFilmIzleEpisode? {
        if (!isSeriesUrl(url)) return null
        val pathSegments = url.substringBefore('?').substringBefore('#')
            .split('/')
            .filter { it.isNotBlank() }
        val seasonSegment = pathSegments.firstOrNull { it.startsWith("sezon-", ignoreCase = true) }
            ?: return null
        val episodeSegment = pathSegments.firstOrNull { it.startsWith("bolum-", ignoreCase = true) }
            ?: return null
        val season = seasonSegment.substringAfter('-', "").toIntOrNull() ?: return null
        val episode = episodeSegment.substringAfter('-', "").toIntOrNull() ?: return null

        return HDFilmIzleEpisode(
            url = url,
            season = season,
            episode = episode,
            name = "$season. Sezon $episode. Bölüm",
        )
    }
}

internal data class HDFilmIzleEpisode(
    val url: String,
    val season: Int,
    val episode: Int,
    val name: String,
)
