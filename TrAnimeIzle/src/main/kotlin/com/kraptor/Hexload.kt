package com.kraptor

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * Hexload oynatıcı çözücü (DEX'teki com.byayzen.Hexload'un yeniden yazımı).
 *
 * iframe URL'inden id'yi çıkarır, siteye `POST /download` (op=download3)
 * gönderip JSON'dan video URL'sini alır.
 */
class Hexload : ExtractorApi() {

    override val name = "Hexload"
    override val mainUrl = "https://hexload.com"
    override val requiresReferer = true

    data class Result(
        @JsonProperty("file_name") val fileName: String,
        @JsonProperty("url") val url: String
    )

    data class Response(
        @JsonProperty("result") val result: Result?,
        @JsonProperty("status") val status: Int?,
        @JsonProperty("msg") val msg: String?
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            Log.d(name, "Hexload extraction started: $url")
            // v17: hem çıplak id hem "?id=<id>" içeren tam URL desteklenir.
            val id = if (url.contains("id=")) {
                url.substringAfterLast("id=").substringBefore("&").substringBefore("/")
            } else {
                url.substringAfterLast("/", "")
            }
            Log.d(name, "Extracted ID: $id")

            val response = app.post(
                url = "$mainUrl/download",
                data = mapOf(
                    "op" to "download3",
                    "id" to id,
                    "ajax" to "1",
                    "method_free" to "1",
                    "dataType" to "json"
                ),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Referer" to url,
                    "Origin" to mainUrl,
                    "User-Agent" to UA_HEXLOAD
                )
            )
            val parsed = response.text
                ?.let { response.parser?.parseSafe(it, Response::class) }
            val videoUrl = parsed?.result?.url
            if (videoUrl == null) {
                Log.d(name, "Hexload extraction failed: Video URL is null")
                return
            }
            Log.d(name, "Hexload Video URL found: $videoUrl")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = url
                    this.headers = mapOf(
                        "Referer" to url,
                        "Origin" to mainUrl,
                        "User-Agent" to UA_HEXLOAD
                    )
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e(name, "Hexload extraction error: ${e.message}")
        }
    }

    companion object {
        private const val UA_HEXLOAD =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
