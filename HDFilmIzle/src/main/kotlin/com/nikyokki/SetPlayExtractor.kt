package com.nikyokki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * SetPlay "köprü" oynatıcısı (setplay.shop / stfplay.php). SPG.cerceve XOR
 * katmanını geçip FastPlay manifestine veya doğrudan STF_KOPRU manifestine
 * ulaşır. Akış [HDFilmIzlePlayerResolver] içindedir.
 */
open class SetPlayExtractor : ExtractorApi() {
    override val name = "SetPlay"
    override val mainUrl = "https://setplay.shop"
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
