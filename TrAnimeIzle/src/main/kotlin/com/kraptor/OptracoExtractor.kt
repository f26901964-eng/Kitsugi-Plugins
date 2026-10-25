package com.kraptor

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * OpTraco oynatıcı çözücü (DEX'teki OptracoExtractor'ın yeniden yazımı).
 *
 * iframe HTML'indeki `file: "..."` alanını çıkarır; göreliyse mainUrl'e ekler.
 */
class OptracoExtractor : ExtractorApi() {

    override val name = "OpTraco"
    override val mainUrl = "https://optraco.top"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val text = app.get(url).text ?: return
            var videoUrl = text.substringAfter("file: \"", "").substringBefore("\"")
            if (!videoUrl.startsWith("http")) {
                videoUrl = mainUrl + videoUrl
            }
            if (videoUrl.isBlank()) {
                Log.e(name, "OpTraco: video adresi bulunamadı")
                return
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = url
                    this.headers = mapOf("Referer" to url)
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(name, "OpTraco extraction error: ${e.message}")
        }
    }
}
