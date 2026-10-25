package com.kraptor

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
            // v27: zaman asimi (10 sn) — ölü host akisi bogmasin
            val text = app.get(url, timeout = 10).text ?: return
            val rawFile = text.substringAfter("file: \"", "").substringBefore("\"")
            // v27: BOS KONTROL ONE ALINDI — v26'da göreli ekleme önce
            // yapiliyordu, bos sonuc "https://optraco.top" adli sahte bir
            // "video" olarak oynaticiya gidiyordu (oynatilamaz, bos ekran).
            if (rawFile.isBlank()) {
                Log.e(name, "OpTraco: video adresi bulunamadı")
                return
            }
            val videoUrl = if (rawFile.startsWith("http")) rawFile else mainUrl + rawFile
            // v27: referer = cagiranin (bölüm sayfası) adresi; iframe'in kendi
            // adresi degil. CDN'ler bos/yanlis referer'da 403 dönüyor.
            val linkReferer = referer?.takeIf { it.isNotBlank() } ?: url
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = linkReferer
                    this.headers = mapOf(
                        "Referer" to linkReferer,
                        "User-Agent" to BotKontrolKt.UA_FIREFOX
                    )
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(name, "OpTraco extraction error: ${e.message}")
        }
    }
}
