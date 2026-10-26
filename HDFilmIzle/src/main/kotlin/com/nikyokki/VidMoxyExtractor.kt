package com.nikyokki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Vidmoxy oynatıcısı (vidmoxy.net). Kaynaklar EE.dd ile karıştırılır;
 * ayrıştırma [HDFilmIzlePlayerParser.vidmoxySources] içinde yapılır.
 */
open class VidMoxyExtractor : ExtractorApi() {
    override val name = "Vidmoxy"
    override val mainUrl = "https://vidmoxy.net"
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
