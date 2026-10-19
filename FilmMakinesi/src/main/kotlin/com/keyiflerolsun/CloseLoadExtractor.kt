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
            val response = app.get(
                url,
                referer = origin,
                headers = headers2,
                interceptor = FilmMakinesiHttp.interceptor
            )
            val html = response.text

            // ! TANI: embed sayfasının tamamı (logcat satır sınırı için parçalı).
            // ! Yeni dc_ biçimini çözebilmek için ham JS gerekiyor.
            logScriptChunks("FLMM_TANI", "[closeload ${url.substringAfterLast('/').ifBlank { "x" }}]", html)

            // ! Sayfada şifreli/obfüskeli oynatıcı var mı? Varsa düz yedek adresler
            // ! ancak aşağıda tek tek doğrulanır; afiş/bayat URL kaynak sayılmaz.
            val packedPlayer = RplayerDecoder.hasPackedPlayer(html)

            // 1. Paketli eval/dc_ kaynağını contentUrl fallback'inden ÖNCE dene.
            var realUrl = RplayerDecoder.extractPackedStreamUrl(html)
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

            // 2. JS deşifre algoritmasını dene (paketlenmemiş dc_ gövdeleri).
            if (realUrl.isNullOrBlank()) {
                realUrl = decryptNative(html)?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            }

            // CloseLoad'un güncel embed'i kaynak URL'sini base64 (aHR0...) olarak
            // gömüyor. Eski extractor bu alanı hiç çözmediği için player'a link
            // gönderemiyordu; önce doğrudan bu URL'yi çıkar.
            if (realUrl.isNullOrBlank()) realUrl = extractBase64StreamUrl(html)

            if (realUrl.isNullOrBlank()) {
                if (packedPlayer) {
                    Log.w("Kekik_${this.name}", "Şifreli oynatıcı çözülemedi, doğrulanmış yedekler denenecek.")
                }
                realUrl = verifiedFallbackUrl(html, headers2)
            }

            Log.d("FLMM_TANI", "[closeload $url] cozulen adres: ${realUrl ?: "<yok>"}")

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                // CloseLoad master'ını değiştirmeden oynatıcıya veriyoruz. İmzalı
                // query token'ı, relative URL çözümlemesinde kaybolmamalı; master'daki
                // ses grupları da (dublaj/orijinal) CloudStream HLS oynatıcısına kalmalı.
                // Origin ve Referer embed host'undan alınır.
                val playlistHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                    "Origin" to origin
                )
                val normalized = normalizeStreamUrl(realUrl)
                if (normalized.isNullOrBlank()) {
                    Log.w("Kekik_${this.name}", "normalizeStreamUrl bozuk, elendi: $realUrl")
                } else if (normalized.startsWith("httpshttp://") || isTsSegment(normalized)) {
                    Log.w("Kekik_${this.name}", "çöp adres elendi: $normalized")
                } else {
                    // Signed HLS master URLs must be passed through untouched:
                    // resolving relative variants can drop query tokens and omit
                    // audio renditions. Let the player's HLS engine read the master.
                    val variants = if (isHlsPlaylistUrl(normalized)) {
                        listOf(normalized to playlistHeaders)
                    } else {
                        resolveMasterPlaylist(normalized, playlistHeaders)
                    }
                    if (variants != null && variants.isNotEmpty()) {
                        variants.forEachIndexed { idx, pair ->
                            val (streamUrl, streamHeaders) = pair
                            val norm = normalizeStreamUrl(streamUrl) ?: return@forEachIndexed
                            if (norm.startsWith("httpshttp://") || isTsSegment(norm)) {
                                Log.w("Kekik_${this.name}", "çöp varyant elendi: $norm")
                                return@forEachIndexed
                            }
                            val label = if (variants.size > 1) "${this.name} - ${idx + 1}" else this.name
                            val q = when (idx) { 0 -> Qualities.P1080.value; 1 -> Qualities.P720.value; else -> Qualities.P480.value }
                            callback.invoke(
                                newExtractorLink(
                                    source = this.name,
                                    name = label,
                                    url = norm,
                                    type = linkTypeFor(norm)
                                ) {
                                    quality = q
                                    headers = streamHeaders
                                }
                            )
                            Log.d("Kekik_${this.name}", "link [$idx] » $norm type=${linkTypeFor(norm)}")
                        }
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = normalized,
                                type = linkTypeFor(normalized)
                            ) {
                                quality = Qualities.P1080.value
                                headers = playlistHeaders
                            }
                        )
                        Log.d("Kekik_${this.name}", "link » $normalized type=${linkTypeFor(normalized)}")
                    }
                }
            } else {
                Log.e("Kekik_${this.name}", "Real URL bulunamadı veya deşifre edilemedi.")
            }

            processSubtitles(html, subtitleCallback)

        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "Hata: ${e.message}")
        }
    }

    /**
     * Akış tipi. Bilinen uzantılar için ExtractorLinkType, bilinmeyen adresler için null.
     *
     * ! DİKKAT — burası oynatmayı tamamen öldüren delikti:
     * ! CloudStream kütüphanesinde `val INFER_TYPE: ExtractorLinkType? = null`
     * ! (ExtractorApi.kt:447) tanımlı. Yani `INFER_TYPE!!` yazmak DERLEME HATASI
     * ! vermiyor ama ÇALIŞMA ANINDA her seferinde NullPointerException fırlatıyor.
     * ! O NPE eklentinin kendi try/catch'inde yutulduğu için callback hiç
     * ! çağrılmıyor, uygulama "0 kaynak" gösteriyor ve oynatıcı hata veriyordu.
     * ! Uzantısız adresler (master.txt varyantları, JSON'dan çözülen CDN
     * ! adresleri, token'lı /play) tam olarak bu dala düşüyordu.
     * ! Doğrusu: null geçmek. `newExtractorLink` type parametresi zaten
     * ! nullable; null verildiğinde kütüphane tipi adresten çıkarır.
     */
    private fun isHlsPlaylistUrl(url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".m3u8") || path.endsWith("master.txt")
    }

    private fun linkTypeFor(url: String): ExtractorLinkType? {
        val lower = url.lowercase().substringBefore("?")
        return when {
            lower.contains(".m3u8") || lower.contains("master.txt") || lower.contains(".m3u") -> ExtractorLinkType.M3U8
            lower.endsWith(".mpd") -> ExtractorLinkType.DASH
            lower.contains(".mp4") || lower.contains(".m4v") || lower.contains(".mkv") ||
                lower.contains(".webm") || lower.contains(".mov") || lower.contains(".avi") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    /**
     * Ham .ts SEGMENT adresi mi? Yalnızca gerçek dosya uzantısına bakar.
     * ! `contains(".ts")` çok genişti: "cdn.vod.ts.net/..." ve "lists.tsx/..."
     * ! gibi geçerli adresleri de eliyordu. Sorgu dizesi de hesaba katılır.
     */
    private fun isTsSegment(url: String): Boolean =
        url.substringBefore("?").trim().lowercase().endsWith(".ts")

    private fun normalizeStreamUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw.trim().replace("\\/", "/")
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val match = urlRegex.find(cleaned) ?: return null
        var url = match.value.trimEnd(',', '"', 39.toChar(), ')', ']', '}', ';').replace("\\", "")
        if (url.startsWith("httpshttp://")) url = "http://" + url.removePrefix("httpshttp://")
        if (url.startsWith("httpshttps://")) url = "https://" + url.removePrefix("httpshttps://")
        if (url.contains("httpshttp://")) {
            val idx = url.indexOf("http://")
            if (idx > 0) url = url.substring(idx)
        }
        if (url.contains("httpshttps://")) {
            val idx = url.lastIndexOf("https://")
            if (idx > 0) url = url.substring(idx)
        }
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            url
        } catch (e: Exception) {
            Log.w("Kekik_${this.name}", "normalizeStreamUrl bozuk: $raw -> $url (${e.message})")
            null
        }
    }

    private fun extractJsonStream(text: String): String? {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return null
        val keyRegex = Regex("\"(?:file|src|url|hls|source|stream|link)\"\\s*:\\s*\"([^\"]+)\"", RegexOption.IGNORE_CASE)
        val m = keyRegex.find(text)
        if (m != null) {
            val cand = m.groupValues[1].replace("\\/", "/")
            val norm = normalizeStreamUrl(cand)
            if (norm != null) {
                Log.d("Kekik_${this.name}", "JSON içinden stream çözüldü: $norm")
                return norm
            }
        }
        val urlRegex = Regex("""https?://[^\s"']+\.m3u8[^\s"']*|https?://[^\s"']+master\.txt[^\s"']*|https?://[^\s"']+\.mp4[^\s"']*""", RegexOption.IGNORE_CASE)
        val fallback = urlRegex.find(text)?.value
        return normalizeStreamUrl(fallback)
    }

    private fun isMediaPlaylist(text: String): Boolean {
        return text.contains("#EXTINF") || text.contains("#EXT-X-TARGETDURATION")
    }

    private suspend fun fetchPlaylistText(
        playlistUrl: String,
        embedHeaders: Map<String, String>
    ): String? {
        try {
            val t = app.get(
                playlistUrl,
                headers = embedHeaders,
                interceptor = FilmMakinesiHttp.interceptor
            ).text
            if (t.isNotBlank()) {
                val lower = t.lowercase()
                val isHtmlError = t.trim().startsWith("<") && (lower.contains("403") || lower.contains("forbidden") || lower.contains("not found") || lower.contains("access denied"))
                if (!isHtmlError) return t
                Log.w("Kekik_${this.name}", "embed Origin ile liste html hata, kendi hostu denenecek: $playlistUrl")
            }
        } catch (e: Exception) {
            Log.w("Kekik_${this.name}", "embed Origin liste açılamadı: ${e.message}")
        }
        val ownOrigin = originOf(playlistUrl)
        val embedOrigin = embedHeaders["Origin"]
        if (ownOrigin != null && ownOrigin != embedOrigin) {
            try {
                val altHeaders = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$ownOrigin/",
                    "Origin" to ownOrigin
                )
                val t2 = app.get(
                    playlistUrl,
                    headers = altHeaders,
                    interceptor = FilmMakinesiHttp.interceptor
                ).text
                if (t2.isNotBlank()) {
                    Log.d("Kekik_${this.name}", "kendi hostu ile liste alındı: $playlistUrl")
                    return t2
                }
            } catch (e: Exception) {
                Log.w("Kekik_${this.name}", "kendi hostu ile de açılamadı: ${e.message}")
            }
        }
        return null
    }

    private fun pickVariants(
        listText: String,
        baseUrl: String
    ): List<Pair<String, Int>> {
        extractJsonStream(listText)?.let { jsonUrl ->
            return listOf(jsonUrl to 999999)
        }
        if (isMediaPlaylist(listText)) {
            Log.d("Kekik_${this.name}", "media playlist tespit edildi, çözülmeden ham verilecek")
            return emptyList()
        }
        val candidates = mutableListOf<Pair<String, Int>>()
        var pendingScore = -1
        for (line in listText.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                pendingScore = variantScore(line)
                continue
            }
            if (line.startsWith("#")) continue
            val candidate = absolutize(line, baseUrl) ?: continue
            if (isTsSegment(candidate)) continue
            if (!candidate.contains(".m3u8") && !candidate.contains("master.txt")) continue
            val score = if (pendingScore >= 0) pendingScore else nameScore(candidate)
            candidates.add(candidate to score)
            pendingScore = -1
        }
        return candidates.sortedByDescending { it.second }
    }

    /**
     * master.txt / master.m3u8 listesini açıp içindeki gerçek .m3u8 adreslerini seçer.
     * Yeni: JSON, .ts filtre, media playlist, çift Origin, çoklu varyant.
     */
    private suspend fun resolveMasterPlaylist(
        playlistUrl: String,
        headers: Map<String, String>
    ): List<Pair<String, Map<String, String>>>? {
        if (!playlistUrl.contains("master.txt") && !playlistUrl.contains(".m3u8")) return null
        val listText = fetchPlaylistText(playlistUrl, headers) ?: run {
            Log.e("Kekik_${this.name}", "master listesi açılamadı: $playlistUrl")
            return null
        }
        extractJsonStream(listText)?.let { jsonUrl ->
            val norm = normalizeStreamUrl(jsonUrl) ?: return@let
            val streamOrigin = originOf(norm)
            val streamHeaders = if (streamOrigin != null && streamOrigin != originOf(playlistUrl)) {
                mapOf("User-Agent" to USER_AGENT, "Referer" to "$streamOrigin/", "Origin" to streamOrigin)
            } else headers
            return listOf(norm to streamHeaders)
        }
        if (isMediaPlaylist(listText)) return null
        val variants = pickVariants(listText, playlistUrl)
        if (variants.isEmpty()) return null
        val top = variants.take(3)
        Log.d("Kekik_${this.name}", "master listesi çözüldü (${top.size} varyant): ${top.joinToString { it.first }}")
        return top.map { (url, _) ->
            val streamOrigin = originOf(url)
            val streamHeaders = if (streamOrigin != null && streamOrigin != originOf(playlistUrl)) {
                mapOf("User-Agent" to USER_AGENT, "Referer" to "$streamOrigin/", "Origin" to streamOrigin)
            } else headers
            url to streamHeaders
        }
    }

    /**
     * HLS listesinden en yüksek çözünürlüklü varyantı seçer.
     * Eski tekil seçici, geriye uyum için tutuldu.
     */
    private fun pickVariant(
        listText: String,
        baseUrl: String,
        headers: Map<String, String>
    ): Pair<String, Map<String, String>>? {
        val variants = pickVariants(listText, baseUrl)
        val chosen = variants.firstOrNull()?.first ?: return null
        val streamOrigin = originOf(chosen)
        val streamHeaders = if (streamOrigin != null && streamOrigin != originOf(baseUrl)) {
            mapOf("User-Agent" to USER_AGENT, "Referer" to "$streamOrigin/", "Origin" to streamOrigin)
        } else headers
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

    /** Uzun metni logcat satır sınırını aşmayacak parçalara bölerek yazar. */
    private fun logScriptChunks(tag: String, label: String, text: String, chunk: Int = 1200, limit: Int = 30000) {
        val body = if (text.length > limit) text.substring(0, limit) + "…<kesildi>" else text
        var index = 0
        var part = 1
        while (index < body.length) {
            Log.d(tag, "$label #$part " + body.substring(index, minOf(index + chunk, body.length)))
            index += chunk
            part++
        }
    }

    /** Yayın olamayacak görsel uzantıları. */
    private val imageExtensions = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif", ".bmp", ".ico", ".tiff"
    )

    /** Görsel adresi mi? Afişler 200 döndüğü için "canlı" sanılmamalı. */
    private fun isImageUrl(url: String): Boolean {
        val path = url.trim().lowercase().substringBefore('?').substringBefore('#')
        if (path.startsWith("data:")) return true
        return imageExtensions.any { path.endsWith(it) }
    }

    /**
     * Düz yedek adayları — GÖRSELLER HARİÇ.
     * ! JSON-LD'deki contentUrl çoğu zaman afişin adresidir:
     * !   "thumbnail":{"@type":"ImageObject","contentUrl":".../poster/x.jpg"}
     * ! Bunu video sanmak "video verisi gelmiyor" hatasına yol açıyordu.
     */
    private fun plainCandidates(html: String): List<String> {
        val candidates = mutableListOf<String>()
        val regex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
        for (match in regex.findAll(html)) {
            val raw = match.groupValues.getOrNull(1)?.replace("\\/", "/")?.trim() ?: continue
            if (raw.isBlank() || isImageUrl(raw)) continue
            val start = (match.range.first - 240).coerceAtLeast(0)
            if (html.substring(start, match.range.first).lowercase().contains("thumbnail")) continue
            candidates.add(raw)
        }
        Regex("""https?://[^\s"']+/master\.txt[^\s"']*""").findAll(html).forEach { candidates.add(it.value) }
        Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(html).forEach { candidates.add(it.value) }
        return candidates.filterNot { isImageUrl(it) }.distinct()
    }

    /**
     * Düz yedek adayları (contentUrl / master.txt / .m3u8) doğrular.
     * ! Bayat (404) adresi oynatıcıya göndermemek, ama hâlâ yayında olan
     * ! yedeği de kaybetmemek için: adaylar tek tek denenir, yalnızca
     * ! 2xx/3xx dönen adres döndürülür.
     */
    private suspend fun verifiedFallbackUrl(html: String, headers: Map<String, String>): String? {
        val candidates = plainCandidates(html)

        for (candidate in candidates) {
            val norm = normalizeStreamUrl(candidate) ?: continue
            if (isUrlAlive(norm, headers)) {
                Log.d("Kekik_${this.name}", "yedek adres yayında: $norm")
                return norm
            }
            Log.w("Kekik_${this.name}", "bayat yedek adres elendi (yayında değil): $norm")
        }
        return null
    }

    /** Adres gerçekten cevap veriyor mu? HEAD bazı CDN'lerde 405 döndüğü için Range'li GET. */
    private suspend fun isUrlAlive(url: String, headers: Map<String, String>): Boolean {
        return try {
            val res = app.get(
                url,
                headers = headers + mapOf("Range" to "bytes=0-1", "Accept" to "*/*"),
                allowRedirects = true,
                interceptor = FilmMakinesiHttp.interceptor
            )
            if (!(res.isSuccessful || res.code in 300..399)) return false

            // ! Görsel dönen adres yayın değildir (afişler 200 döner!).
            val contentType = res.headers["Content-Type"].orEmpty().lowercase()
            if (contentType.startsWith("image/") || isImageUrl(url)) return false
            if (contentType.contains("text/html") || contentType.contains("application/json")) return false

            // ! 200 dönen HTML hata sayfası "yaşıyor" sayılmamalı.
            val path = url.substringBefore('?').lowercase()
            val looksHls = path.endsWith(".m3u8") || path.endsWith("master.txt") || path.contains(".urlset")
            if (!looksHls) return true
            val preview = res.text.take(2048)
            preview.contains("#EXTM3U") || preview.contains("#EXT-X")
        } catch (e: Exception) {
            Log.w("Kekik_${this.name}", "adres doğrulanamadı: $url (${e.message})")
            false
        }
    }

    /** CloseLoad stores the playable URL as a base64 token (often beginning aHR0...). */
    private fun extractBase64StreamUrl(html: String): String? {
        val candidates = Regex("""aHR0[A-Za-z0-9+/_=-]{12,}""")
            .findAll(html)
            .map { it.value.trimEnd('=', ';', ',') }
        for (candidate in candidates) {
            try {
                var padded = candidate
                while (padded.length % 4 != 0) padded += "="
                // ! - ve _ URL-safe alfabeye aittir; standart çözücü bunlarda
                // ! hata verir, bu yüzden URL_SAFE'e de düşülür.
                val bytes = runCatching { Base64.decode(padded, Base64.DEFAULT) }
                    .getOrElse { Base64.decode(padded, Base64.URL_SAFE) }
                val decoded = String(bytes, Charsets.UTF_8)
                    .replace("\\/", "/").trim()
                if ((decoded.startsWith("http://") || decoded.startsWith("https://")) &&
                    runCatching { java.net.URI(decoded).host }.getOrNull() != null) {
                    Log.d("Kekik_${this.name}", "base64 stream çözüldü: $decoded")
                    return decoded
                }
            } catch (_: Exception) {
                // Sayfadaki diğer base64 değerlerini dene.
            }
        }
        return null
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
                        // ! URL-safe karakterler (- / _) standart çözücüde hata
                        // ! verir; olmazsa URL_SAFE denenir.
                        val bytes = runCatching {
                            Base64.decode(paddedResult, Base64.NO_WRAP)
                        }.getOrElse {
                            Base64.decode(paddedResult, Base64.URL_SAFE)
                        }
                        result = String(bytes, Charsets.ISO_8859_1)
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
