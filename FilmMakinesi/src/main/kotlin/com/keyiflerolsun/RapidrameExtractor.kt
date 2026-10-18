package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/** Extracts the JWPlayer `av('...')` source used by RapidVid/Rapidrame embeds. */
class Rapidrame : ExtractorApi() {
    override val name = "Rapidrame"
    override val mainUrl = "https://rapidvid.net"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to (referer ?: "$mainUrl/")
                )
            ).text

            // ! TANI: embed sayfasının ham içeriği + bulunan av() yükü.
            Log.d("FLMM_TANI", "[rapidrame $url] " + page.replace("\r", " ").replace("\n", " ").take(900))

            val encoded = Regex("""av\(['"]([^'"]+)['"]\)""").find(page)?.groupValues?.get(1)
            Log.d("FLMM_TANI", "[rapidrame $url] av() yuku: " + (encoded ?: "<yok>"))
            val decoded = encoded?.let(::decodeRapidUrl)
                ?: Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .find(page)?.value
            Log.d("FLMM_TANI", "[rapidrame $url] cozulen adres: ${decoded ?: "<yok>"}")
            if (decoded.isNullOrBlank()) {
                Log.e("Rapidrame", "JWPlayer kaynak URL'si bulunamadı: $url")
                return
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = decoded,
                    type = if (decoded.substringBefore('?').endsWith(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to (referer ?: "$mainUrl/")
                    )
                }
            )
        } catch (e: Exception) {
            Log.e("Rapidrame", "Kaynak çözümlenemedi: ${e.message}")
        }
    }

    private fun decodeRapidUrl(encoded: String): String? = try {
        fun decodeBase64(value: String): String {
            var padded = value
            while (padded.length % 4 != 0) padded += "="
            val bytes = runCatching { Base64.decode(padded, Base64.DEFAULT) }
                .getOrElse { Base64.decode(padded, Base64.URL_SAFE) }
            return String(bytes, Charsets.ISO_8859_1)
        }

        val first = decodeBase64(encoded.reversed())
        val key = "K9L"
        val shifted = buildString {
            first.forEachIndexed { index, char ->
                append((char.code - (key[index % key.length].code % 5 + 1)).toChar())
            }
        }
        decodeBase64(shifted).replace("\\/", "/").trim().takeIf {
            (it.startsWith("http://") || it.startsWith("https://")) &&
                runCatching { java.net.URI(it).host }.getOrNull() != null
        }
    } catch (e: Exception) {
        Log.w("Rapidrame", "av() decode failed: ${e.message}")
        null
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
