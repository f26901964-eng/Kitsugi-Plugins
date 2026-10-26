package com.nikyokki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink

/**
 * Vidrame oynatıcısı (vidrame.pro/vr/...). Çözme ve ağ akışının tamamı
 * [HDFilmIzlePlayerResolver] içinde toplanmıştır; bu sınıf yalnızca uygulamanın
 * genel loadExtractor() yönlendirmesini HDFilmİzle çözücüsüne bağlar.
 */
open class VidRameExtractor : ExtractorApi() {
    override val name = "Vidrame"
    override val mainUrl = "https://vidrame.pro"
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
