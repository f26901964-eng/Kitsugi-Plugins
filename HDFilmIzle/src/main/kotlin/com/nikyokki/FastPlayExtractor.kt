package com.nikyokki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * FastPlay oynatıcısı (fastplay.*). /manifests/ HLS listesini, X-Sp imzasını
 * ve altyazı bloğunu [HDFilmIzlePlayerResolver] aracılığıyla çözer.
 */
open class FastPlayExtractor : ExtractorApi() {
    override val name = "FastPlay"
    override val mainUrl = "https://fastplay.mom"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        HDFilmIzlePlayerResolver.resolve(
            HDFilmIzlePlayerParser.PageFrame(url),
            referer ?: "https://www.hdfilmizle.to/",
            subtitleCallback,
            callback,
        )
    }
}
