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

private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

class CloseLoad : ExtractorApi() {
    private fun originOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // ! Referer, çağıranın verdiği host değil embed adresinin kendi host'u.
        // ! closeload.filmmakinesi.to ölünce başka bir closeload host'u da çalışsın.
        val origin = originOf(url)
            ?: referer?.let { originOf(it) }?.takeIf { it.startsWith("http") }
            ?: mainUrl
        val headers2 = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$origin/",
            "Origin" to origin
        )

        try {
            val response = app.get(url, referer = origin, headers = headers2)
            val html = response.text

            // 1. JS Deşifre Algoritmasını Dene
            var realUrl = decryptNative(html)

            // 2. Fallback Mekanizması: Eğer JS şifre çözücü başarısız olursa JSON-LD bloğundaki şifresiz contentUrl'i ara
            if (realUrl.isNullOrBlank()) {
                Log.w("Kekik_${this.name}", "Native deşifre başarısız, Fallback JSON-LD aranıyor...")
                val ldJsonMatch = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(html)
                realUrl = ldJsonMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

            // 3. Fallback: master.txt veya .m3u8 doğrudan akış linki ara
            if (realUrl.isNullOrBlank()) {
                val txtMatch = """https?://[^\s"']+/master\.txt[^\s"']*""".toRegex().find(html)?.value
                    ?: """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex().find(html)?.value
                realUrl = txtMatch
            }

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                // ! playmix.uno artık master.txt'yi oynatıcıya HAM vermiyor: liste
                // ! açılıp içindeki gerçek .m3u8 adresi seçiliyor. Origin embed
                // ! sunucusuna sabit; eksik kalınca uygulama onu playmix.uno yapıyor
                // ! ve yayın 5004 ile düşüyordu.
                val playlistHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                    "Origin" to origin
                )
                val (streamUrl, streamHeaders) = resolveMasterPlaylist(realUrl, playlistHeaders)
                    ?: (realUrl to playlistHeaders)

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = this.name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        headers = streamHeaders
                    }
                )
            } else {
                Log.e("Kekik_${this.name}", "Real URL bulunamadı veya deşifre edilemedi.")
            }

            processSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    /**
     * master.txt / master.m3u8 listesini açıp içindeki gerçek .m3u8 adresini seçer.
     * Liste çözülemezse null döner; çağıran ham adresle devam eder.
     */
    private suspend fun resolveMasterPlaylist(
        playlistUrl: String,
        headers: Map<String, String>
    ): Pair<String, Map<String, String>>? {
        if (!playlistUrl.contains("master.txt") && !playlistUrl.contains(".m3u8")) return null
        return try {
            val listText = app.get(playlistUrl, headers = headers).text
            pickVariant(listText, playlistUrl, headers)
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "master listesi açılamadı: ${e.message}")
            null
        }
    }

    /**
     * HLS listesinden en yüksek çözünürlüklü varyantı seçer.
     * playmix.uno bazen #EXT-X-STREAM-INF bloğu yerine çıplak .m3u8 satırı döndürüyor;
     * o yüzden hem etiketler hem düz satırlar taranıyor.
     */
    private fun pickVariant(
        listText: String,
        baseUrl: String,
        headers: Map<String, String>
    ): Pair<String, Map<String, String>>? {
        val lines = listText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var bestUrl: String? = null
        var bestScore = -1
        var pendingScore = -1

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingScore = variantScore(line)
                continue
            }
            if (line.startsWith("#")) continue

            val candidate = absolutize(line, baseUrl) ?: continue
            if (!candidate.contains(".m3u8") && !candidate.contains("master.txt")) continue

            // ! resolution= etiketi yoksa dosya adındaki 1080p/720p ipucu kullanılır.
            val score = if (pendingScore >= 0) pendingScore else nameScore(candidate)
            if (score > bestScore) {
                bestScore = score
                bestUrl = candidate
            }
            pendingScore = -1
        }

        val chosen = bestUrl ?: return null
        Log.d("Kekik_${this.name}", "master listesi çözüldü: $chosen")

        // ! Oynatıcının Origin/Referer'ı artık liste host'u değil, gerçek yayın host'u.
        val streamOrigin = originOf(chosen)
        val streamHeaders = if (streamOrigin != null && streamOrigin != originOf(baseUrl)) {
            mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$streamOrigin/",
                "Origin" to streamOrigin
            )
        } else {
            headers
        }
        return chosen to streamHeaders
    }

    private fun variantScore(streamInfLine: String): Int {
        val resolution = """RESOLUTION=(\d+)x(\d+)""".toRegex().find(streamInfLine)
        if (resolution != null) {
            val height = resolution.groupValues[2].toIntOrNull() ?: 0
            return height * 1000
        }
        val bandwidth = """BANDWIDTH=(\d+)""".toRegex().find(streamInfLine)
        return (bandwidth?.groupValues?.get(1)?.toLongOrNull() ?: 0L).let { (it / 1000).toInt() }
    }

    private fun nameScore(url: String): Int {
        val match = """(\d{3,4})p""".toRegex().find(url.lowercase()) ?: return 0
        return (match.groupValues[1].toIntOrNull() ?: 0) * 1000
    }

    /** Göreli liste satırını (index.m3u8, ../x/y.m3u8) tam adrese çevirir. */
    private fun absolutize(line: String, baseUrl: String): String? {
        val value = line.replace("\\/", "/").trim().trim('"', '\'')
        if (value.isBlank()) return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("//") -> "https:$value"
            else -> try {
                java.net.URI(baseUrl).resolve(value).toString()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun decryptNative(html: String): String? {
        try {
            // JS bloğunu yakala
            val scriptBlockMatch = """<script[^>]*>(.*?dc_[a-zA-Z0-9_]+\(.*?</script>)""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            val scriptContent = scriptBlockMatch?.groupValues?.get(1) ?: return null

            // 1. Şifreli diziyi çıkar
            val arrayMatch = """\(\[((?:"[^"]+",?\s*)+)\]\)""".toRegex().find(scriptContent)
            val parts = arrayMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('"').replace("\\/", "/") 
            } ?: return null

            // 2. Dinamik Modulo Çarpanlarını Çıkar
            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(scriptContent)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            // 3. Fonksiyon Gövdesini Regex OLMADAN İzole Et
            val funcStartIdx = scriptContent.indexOf("function dc_")
            val funcEndIdx = scriptContent.indexOf("function d1x()", funcStartIdx).takeIf { it != -1 } ?: scriptContent.length
            val functionBody = if (funcStartIdx != -1) scriptContent.substring(funcStartIdx, funcEndIdx) else scriptContent

            // 4. KRİTİK DOKUNUŞ: Dinamik ROT (Caesar) Kaydırma (Shift) Değerini Çıkar
            var rotShift = 13
            val rotShiftMatch = """charCodeAt\(0\)\s*\+\s*(\d+)""".toRegex().find(functionBody)
            if (rotShiftMatch != null) {
                rotShift = rotShiftMatch.groupValues[1].toInt()
            } else {
                val rotShiftMatch2 = """o\s*-\s*base\s*([+-])\s*(\d+)""".toRegex().find(functionBody)
                if (rotShiftMatch2 != null) {
                    val sign = rotShiftMatch2.groupValues[1]
                    val num = rotShiftMatch2.groupValues[2].toInt()
                    rotShift = if (sign == "-") (26 - num) % 26 else num
                }
            }

            // --- OPERASYON SIRASINI DİNAMİK OKU --- //
            val operations = mutableListOf<Pair<Int, String>>()

            var index = functionBody.indexOf("atob(")
            while (index >= 0) {
                operations.add(Pair(index, "atob"))
                index = functionBody.indexOf("atob(", index + 1)
            }

            index = functionBody.indexOf("reverse")
            while (index >= 0) {
                operations.add(Pair(index, "reverse"))
                index = functionBody.indexOf("reverse", index + 1)
            }

            index = functionBody.indexOf("replace")
            while (index >= 0) {
                operations.add(Pair(index, "rot"))
                index = functionBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }

            var result = parts.joinToString("")

            // İşlemleri sitenin belirlediği sıraya göre ateşle
            for (op in operations) {
                when (op.second) {
                    "reverse" -> {
                        result = result.reversed()
                    }
                    "atob" -> {
                        // Base64 padding (==) eksikliklerine karşı güvenlik
                        var paddedResult = result
                        while (paddedResult.length % 4 != 0) {
                            paddedResult += "="
                        }
                        result = String(Base64.decode(paddedResult, Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot" -> {
                        // Statik 13 yerine dinamik 'rotShift' kullanıyoruz
                        val rot = StringBuilder()
                        for (c in result) {
                            if (c in 'a'..'z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else if (c in 'A'..'Z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else {
                                rot.append(c)
                            }
                        }
                        result = rot.toString()
                    }
                }
            }

            // --- SON ADIM: Modulo Unmix (Daima en sonda çalışır) --- //
            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("Kekik_Extractor", "Native Çözümleme Hatası: ${e.message}")
            return null
        }
    }


    private fun processSubtitles(html: String, subtitleCallback: (SubtitleFile) -> Unit) {
        try {
            // JWPlayer setup içindeki tracks: [...] JSON bloğu
            val tracksMatch = """tracks\s*:\s*(\[.*?\])""".toRegex(RegexOption.DOT_MATCHES_ALL).find(html)
            tracksMatch?.groupValues?.get(1)?.let { tracksJson ->
                
                val trackPattern = """\{[^}]*\}""".toRegex()
                val fileRegex = """"file"\s*:\s*"([^"]+)"""".toRegex()
                val labelRegex = """"label"\s*:\s*"([^"]+)"""".toRegex()

                trackPattern.findAll(tracksJson).forEach { match ->
                    val block = match.value
                    val file = fileRegex.find(block)?.groupValues?.get(1)?.replace("\\/", "/")
                    val label = labelRegex.find(block)?.groupValues?.get(1) ?: "Altyazı"

                    // ! Sonundaki "/" kaldırıldı: o eğik çizgi .vtt dosyasını 404 yaptırıyordu.
                    if (!file.isNullOrBlank() && file.startsWith("http")) {
                        subtitleCallback.invoke(SubtitleFile(label, file.trimEnd('/')))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Altyazı Çözümleme Hatası: ${e.message}")
        }
    }
}
