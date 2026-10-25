package com.kraptor

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

/**
 * Hdvid oynatıcı çözücü (DEX'teki HdvidExtractor'ın yeniden yazımı).
 *
 * iframe HTML'indeki `sources: [{file:"..."` alanını regex ile çıkarır.
 */
class HdvidExtractor : ExtractorApi() {

    override val name = "Hdvid"
    override val mainUrl = "https://hdvid.tv"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // v27: zaman asimi (10 sn) — ölü host (522) akisi bogmasin
            val text = app.get(url, timeout = 10).text ?: return
            val file = Regex(SOURCES_REGEX).find(text)?.groupValues?.getOrNull(1)
            if (file.isNullOrBlank()) {
                Log.e(name, "Hdvid: video adresi bulunamadı")
                return
            }
            val videoUrl = file.replace("http://", "https://")
            val host = try {
                URI(videoUrl).host
            } catch (_: Exception) {
                null
            }
            val linkReferer = referer?.takeIf { it.isNotBlank() }
                ?: host?.let { "https://$it/" } ?: url
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = linkReferer
                    // v27: UA eklendi (CDN'ler yalniz referer ile 403 dönüyor)
                    this.headers = mapOf(
                        "Referer" to linkReferer,
                        "User-Agent" to BotKontrolKt.UA_FIREFOX
                    )
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(name, "Hdvid extraction error: ${e.message}")
        }
    }

    companion object {
        // DEX'teki regex ile birebir aynı: sources:\s*\[{file:"([^"]+)"
        private const val SOURCES_REGEX = """sources:\s*\[{file:"([^"]+)""""
    }
}
