package com.patron

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CancellationException

/**
 * Güncel VidMody akışı (2026):
 *   1. player.vidmody.com/<id> sayfası indirilir
 *   2. Sayfadaki ilk script içinden `var id = '...'` alınır
 *   3. https://vidmody.com/vs/<id> bir HLS master playlist döndürür;
 *      master içindeki SUBTITLES/AUDIO medya grupları ayrıca işlenir.
 *
 * Eski şifreli (hex + key) oynatıcı hala kullanılıyorsa eski çözücü yedek
 * olarak devreye girer.
 */
class VidMody : ExtractorApi() {
    override val name = "VidMody"
    override val mainUrl = "https://player.vidmody.com"
    override val requiresReferer = true

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val cleanUrl = url.substringBefore("#")
        val safeReferer = referer?.substringBefore("#")?.ifBlank { null } ?: cleanUrl
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to safeReferer,
        )

        val playerHtml = try {
            app.get(cleanUrl, headers = headers).text
        } catch (e: Exception) {
            Log.e(name, "VidMody player sayfası indirilemedi: ${e.message}")
            throw e
        }

        val streamId = HDFilmDelisiPlayerParser.extractVarId(playerHtml)
        val masterUrl = streamId?.takeIf { it.isNotBlank() }?.let { "https://vidmody.com/vs/$it" }
        val masterHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to cleanUrl,
            "Origin" to "https://vidmody.com",
        )
        if (masterUrl != null) {
            Log.d(name, "VidMody master playlist: $masterUrl")
            val masterPlaylist = try {
                app.get(masterUrl, headers = masterHeaders).text
            } catch (error: CancellationException) {
                throw error
            } catch (e: Exception) {
                Log.e(name, "VidMody master playlist indirilemedi: ${e.message}")
                ""
            }

            if (masterPlaylist.contains("#EXTM3U")) {
                HDFilmDelisiHlsResolver.emit(
                    url = masterUrl,
                    source = name,
                    referer = cleanUrl,
                    headers = masterHeaders,
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                    playlistText = masterPlaylist,
                )
                return
            }
        }

        // Eski oynatıcı: önce sayfa içi/şifreli kaynakları dene. Eğer bunlar yoksa
        // ve yeni biçimde bir ID vardıysa, master URL'yi son bir kez HLS olarak sun.
        val legacyResolved = resolveLegacyPlayer(playerHtml, cleanUrl, safeReferer, subtitleCallback, callback)
        if (!legacyResolved && masterUrl != null) {
            HDFilmDelisiHlsResolver.emit(
                url = masterUrl,
                source = name,
                referer = cleanUrl,
                headers = masterHeaders,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        }
    }

    private suspend fun resolveLegacyPlayer(
        playerBody: String,
        cleanUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val directFile = HDFilmDelisiPlayerParser.mediaFileFromScript(playerBody)
        if (directFile != null) {
            Log.d(name, "VidMody doğrudan dosya (legacy): $directFile")
            if (directFile.contains(".m3u8", ignoreCase = true)) {
                HDFilmDelisiHlsResolver.emit(
                    url = directFile,
                    source = name,
                    referer = referer,
                    headers = mapOf("User-Agent" to userAgent, "Referer" to referer),
                    subtitleCallback = subtitleCallback,
                    callback = callback,
                )
            } else {
                callback(
                    newExtractorLink(name, name, directFile, ExtractorLinkType.VIDEO) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("User-Agent" to userAgent, "Referer" to referer)
                    },
                )
            }
            return true
        }

        val jsRegex = Regex("""(?:decrypt|\))\s*\(\s*["']([0-9a-fA-F]+)["']\s*,\s*(\d+)\s*\)""")
        val jsMatch = jsRegex.find(playerBody) ?: run {
            Log.e(name, "VidMody: ne `var id` ne de şifreli blok bulundu")
            return false
        }

        val hexString = jsMatch.groupValues[1]
        val key = jsMatch.groupValues[2].toIntOrNull() ?: return false
        val decryptedBlock = decrypt(hexString, key)
        val masterUrl = HDFilmDelisiPlayerParser.mediaFileFromScript(decryptedBlock) ?: return false

        val masterHeaders = mapOf(
            "User-Agent" to userAgent,
            "Referer" to cleanUrl,
            "Origin" to "https://vidmody.com",
        )
        if (masterUrl.contains(".m3u8", ignoreCase = true)) {
            HDFilmDelisiHlsResolver.emit(
                url = masterUrl,
                source = name,
                referer = cleanUrl,
                headers = masterHeaders,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        } else {
            callback(
                newExtractorLink(name, name, masterUrl, ExtractorLinkType.VIDEO) {
                    this.referer = cleanUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = masterHeaders
                },
            )
        }
        return true
    }

    private fun decrypt(hex: String, key: Int): String {
        return try {
            val sb = StringBuilder()
            for (i in hex.indices step 2) {
                sb.append(hex.substring(i, i + 2).toInt(16).toChar())
            }
            val decoded = Base64.decode(sb.reverse().toString(), 0)
            val result = StringBuilder()
            for (b in decoded) {
                val unsignedByte = b.toInt() and 0xFF
                result.append((unsignedByte - key).toChar())
            }
            val rawString = result.toString()
            Regex("""\\x([0-9a-fA-F]{2})""").replace(rawString) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
        } catch (e: Exception) {
            ""
        }
    }
}
