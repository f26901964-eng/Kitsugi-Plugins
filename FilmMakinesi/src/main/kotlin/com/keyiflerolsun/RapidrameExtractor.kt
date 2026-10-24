package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
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
                ),
                interceptor = FilmMakinesiHttp.interceptor
            ).text

            // ! TANI: embed sayfasının ham içeriği + bulunan av() yükü.
            Log.d("FLMM_TANI", "[rapidrame $url] " + page.replace("\r", " ").replace("\n", " ").take(900))

            val encoded = Regex("""av\(['"]([^'"]+)['"]\)""").find(page)?.groupValues?.get(1)
            Log.d("FLMM_TANI", "[rapidrame $url] av() yuku: " + (encoded ?: "<yok>"))
            // /rplayer/ sayfaları her zaman av('...') kullanmıyor; kaynak bazen
            // eval(function(p,a,c,k,e,d)) ile paketleniyor. Önce gerçek player
            // formatını çözen saf Kotlin decoder'ı çalıştır.
            val packedDecoded = RplayerDecoder.extractStreamUrl(page)
            Log.d("FLMM_TANI", "[rapidrame $url] packed kaynak: ${packedDecoded ?: "<yok>"}")
            val decoded = packedDecoded
                ?: encoded?.let(::decodeRapidUrl)
                ?: Regex("""https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
                    .find(page)?.value
            Log.d("FLMM_TANI", "[rapidrame $url] cozulen adres: ${decoded ?: "<yok>"}")

            val tracks = (
                RplayerDecoder.extractTracks(page) + RplayerDecoder.extractTrackTags(page, url)
                ).distinctBy { it.file }
            tracks.forEach { track ->
                val subtitleUrl = runCatching { java.net.URI(url).resolve(track.file).toString() }.getOrNull()
                    ?: return@forEach
                val label = track.label.ifBlank { track.language.ifBlank { "Türkçe" } }
                subtitleCallback(newSubtitleFile(label, subtitleUrl))
            }

            if (decoded.isNullOrBlank()) {
                Log.e("Rapidrame", "JWPlayer kaynak URL'si bulunamadı: $url")
                return
            }

            val playerOrigin = runCatching {
                val uri = java.net.URI(url)
                "${uri.scheme}://${uri.host}"
            }.getOrNull() ?: mainUrl

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = decoded,
                    type = if (decoded.substringBefore('?').endsWith(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                ) {
                    // HLS segment is protected by the rplayer-page Referer;
                    // Origin remains the embedding site's origin. Keep the full
                    // signed master URL (including query token) unchanged.
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to url,
                        "Origin" to playerOrigin
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
