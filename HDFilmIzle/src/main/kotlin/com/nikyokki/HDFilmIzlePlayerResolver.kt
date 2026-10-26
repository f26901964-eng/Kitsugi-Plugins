package com.nikyokki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response

/**
 * HDFilmİzle'nin dört oynatıcı ailesini tek elden çözen ağ katmanı:
 *
 *  - Vidrame  : XOR + EE.dd karıştırmalı `sources` ve `tracks`
 *  - Vidmoxy  : EE.dd (base64 → ters → ROT13) kaynakları
 *  - SetPlay  : SPG.cerceve XOR köprüsü üzerinden FastPlay'e ulaşır
 *  - FastPlay : /manifests/ HLS çıktısı + X-Sp imzası + altyazılar
 *
 * Tüm çözme işlemleri [HDFilmIzlePlayerParser] / [HDFilmIzlePlayerDecoders]
 * içinde saf fonksiyonlarla yapılır; bu sınıf yalnızca ağ ve link üretiminden
 * sorumludur. Hiçbir oynatıcı sessizce yutulmaz: her ailenin tüm varyantları
 * ayrı ExtractorLink olarak verilir. İstekler derinlik ve döngü kümeleriyle
 * sınırlandırılmıştır (nuvio oynatıcı testlerindeki beklentilerle uyumlu).
 */
object HDFilmIzlePlayerResolver {

    private const val TAG = "HDFilmIzle"
    private const val MAX_HOPS = 4

    val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:136.0) Gecko/20100101 Firefox/136.0"

    val interceptor: Interceptor by lazy { VidrameCloudflareInterceptor() }

    /** Sayfadan gelen bir çerçeveyi host'una göre uygun çözücüye yönlendirir. */
    suspend fun resolve(
        frame: HDFilmIzlePlayerParser.PageFrame,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val visited = HashSet<String>()
        val host = frame.url.lowercase()
        return when {
            host.contains("vidrame") ->
                resolveVidrame(frame.url, pageUrl, subtitleCallback, callback, visited, 0)
            host.contains("vidmoxy") ->
                resolveVidmoxy(frame.url, pageUrl, subtitleCallback, callback, visited, 0)
            host.contains("setplay") || host.contains("stfplay") ->
                resolveSetPlay(frame.url, pageUrl, subtitleCallback, callback, visited, 0)
            host.contains("fastplay") ->
                resolveFastPlay(frame.url, pageUrl, subtitleCallback, callback, visited, 0)
            else -> false // vidmoly ve diğer bilinenler uygulamanın genel çözücüsüne bırakılır
        }
    }

    // ───────────────────────────── Vidrame ─────────────────────────────

    private suspend fun resolveVidrame(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: HashSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_HOPS || !visited.add(url)) return false
        val body = runCatching {
            app.get(url, headers = browserHeaders(referer), interceptor = interceptor).text
        }.getOrNull() ?: return false

        val sources = HDFilmIzlePlayerParser.vidrameSources(body)
        val generic = if (sources.isEmpty()) HDFilmIzlePlayerParser.genericSources(body, url) else emptyList()
        val emitted = emitSources("Vidrame", sources + generic, url, callback)
        HDFilmIzlePlayerParser.tracks(body, url).forEach { track ->
            subtitleCallback(newSubtitleFile(track.label, track.url))
        }
        if (emitted) return true
        return followNestedFrame(body, url, referer, subtitleCallback, callback, visited, depth)
    }

    // ───────────────────────────── Vidmoxy ─────────────────────────────

    private suspend fun resolveVidmoxy(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: HashSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_HOPS || !visited.add(url)) return false
        val body = runCatching {
            app.get(url, headers = browserHeaders(referer), interceptor = interceptor).text
        }.getOrNull() ?: return false

        val sources = HDFilmIzlePlayerParser.vidmoxySources(body)
        val generic = if (sources.isEmpty()) HDFilmIzlePlayerParser.genericSources(body, url) else emptyList()
        val emitted = emitSources("Vidmoxy", sources + generic, url, callback)
        HDFilmIzlePlayerParser.tracks(body, url).forEach { track ->
            subtitleCallback(newSubtitleFile(track.label, track.url))
        }
        if (emitted) return true
        return followNestedFrame(body, url, referer, subtitleCallback, callback, visited, depth)
    }

    // ───────────────────────────── SetPlay ─────────────────────────────

    private suspend fun resolveSetPlay(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: HashSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_HOPS || !visited.add(url)) return false
        // nb2=1: sayfa doğrudan sunucu manifestini (STF_KOPRU) verebiliyor.
        val directUrl = if (url.contains("nb2=")) url else url + (if (url.contains("?")) "&" else "?") + "nb2=1"
        val body = runCatching {
            app.get(directUrl, headers = browserHeaders(referer)).text
        }.getOrNull() ?: return false

        var emitted = false
        HDFilmIzlePlayerParser.setPlayTargets(body).forEach { target ->
            val absolute = HDFilmIzlePlayerParser.resolveUrl(target, directUrl) ?: return@forEach
            when {
                absolute.lowercase().contains("fastplay") ->
                    emitted = resolveFastPlay(absolute, directUrl, subtitleCallback, callback, visited, depth + 1) || emitted
                absolute.contains("/manifest") || absolute.lowercase().contains(".m3u8") -> {
                    val headers = browserHeaders(directUrl).toMutableMap()
                    HDFilmIzlePlayerParser.fastplaySpToken(body)?.let { (sp, spT) ->
                        headers["X-Sp"] = HDFilmIzlePlayerDecoders.buildXSp(sp, spT)
                    }
                    emitLink("SetPlay", absolute, "m3u8", 0, directUrl, headers, callback)
                    emitted = true
                }
                absolute != directUrl ->
                    emitted = resolveSetPlay(absolute, directUrl, subtitleCallback, callback, visited, depth + 1) || emitted
            }
        }
        if (emitted) return true
        return followNestedFrame(body, directUrl, referer, subtitleCallback, callback, visited, depth)
    }

    // ───────────────────────────── FastPlay ─────────────────────────────

    private suspend fun resolveFastPlay(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: HashSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > MAX_HOPS || !visited.add(url)) return false
        val response = runCatching { app.get(url, headers = browserHeaders(referer)) }.getOrNull() ?: return false
        val body = response.text
        val manifestPath = HDFilmIzlePlayerParser.fastplayManifestPath(body)
        if (manifestPath != null) {
            val manifestUrl = HDFilmIzlePlayerParser.resolveUrl(manifestPath, url) ?: return false
            val headers = browserHeaders(url).toMutableMap()
            HDFilmIzlePlayerParser.fastplaySpToken(body)?.let { (sp, spT) ->
                headers["X-Sp"] = HDFilmIzlePlayerDecoders.buildXSp(sp, spT)
            }
            HDFilmIzlePlayerParser.fastplaySubtitles(body, url).forEach { track ->
                subtitleCallback(newSubtitleFile(track.label, track.url))
            }
            emitLink("FastPlay", manifestUrl, "m3u8", 0, url, headers, callback)
            return true
        }
        return followNestedFrame(body, url, referer, subtitleCallback, callback, visited, depth)
    }

    /** İç içe geçmiş iframe'i bir kez daha ilgili çözücüye taşır. */
    private suspend fun followNestedFrame(
        body: String,
        parentUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: HashSet<String>,
        depth: Int,
    ): Boolean {
        if (depth >= MAX_HOPS) return false
        val nested = Regex("""(?i)<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""")
            .findAll(body)
            .mapNotNull { HDFilmIzlePlayerParser.resolveUrl(it.groupValues[1], parentUrl) }
            .firstOrNull { it !in visited && !it.lowercase().contains("about:blank") }
            ?: return false

        // Zincirdeki visited/depth bilgisini koruyarak doğru aileye yönlendir.
        val host = nested.lowercase()
        return when {
            host.contains("vidrame") ->
                resolveVidrame(nested, parentUrl, subtitleCallback, callback, visited, depth + 1)
            host.contains("vidmoxy") ->
                resolveVidmoxy(nested, parentUrl, subtitleCallback, callback, visited, depth + 1)
            host.contains("setplay") || host.contains("stfplay") ->
                resolveSetPlay(nested, parentUrl, subtitleCallback, callback, visited, depth + 1)
            host.contains("fastplay") ->
                resolveFastPlay(nested, parentUrl, subtitleCallback, callback, visited, depth + 1)
            else -> false
        }
    }

    // ───────────────────────── Link üretimi ─────────────────────────

    private suspend fun emitSources(
        sourceName: String,
        sources: List<HDFilmIzlePlayerParser.JsSource>,
        embedUrl: String,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (sources.isEmpty()) return false
        var emitted = false
        sources.forEach { source ->
            val headers = browserHeaders(embedUrl)
            emitted = emitLink(
                sourceName,
                source.url,
                source.type,
                source.quality,
                embedUrl,
                headers,
                callback,
            ) || emitted
        }
        return emitted
    }

    /**
     * Tek bir medya kaynağını bağlar. HLS master listesindeki HER varyant
     * (farklı çözünürlükler) ayrı bir ExtractorLink olarak üretilir; yardımcı
     * başarısız olursa liste tamamen kaybolmasın diye tek M3U8 linkine düşülür.
     */
    private suspend fun emitLink(
        sourceName: String,
        url: String,
        type: String,
        quality: Int,
        referer: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkType = when {
            type.contains("dash", true) -> ExtractorLinkType.DASH
            type.contains("mp4", true) -> ExtractorLinkType.VIDEO
            else -> ExtractorLinkType.M3U8
        }

        if (linkType == ExtractorLinkType.M3U8) {
            val variants = runCatching {
                withTimeoutOrNull(10_000) {
                    M3u8Helper.generateM3u8(sourceName, url, referer, headers = headers)
                }
            }.getOrNull()
            if (!variants.isNullOrEmpty()) {
                variants.forEach { callback(it) }
                return true
            }
        }

        callback(
            newExtractorLink(sourceName, sourceName, url, linkType) {
                this.referer = referer
                this.quality = quality.takeIf { it > 0 } ?: Qualities.Unknown.value
                this.headers = headers
            },
        )
        return true
    }

    private fun browserHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to BROWSER_UA,
        "Accept" to "*/*",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to referer,
        "Origin" to originOf(referer),
    )

    private fun originOf(url: String): String = runCatching {
        val uri = java.net.URI(url)
        "${uri.scheme}://${uri.authority}"
    }.getOrDefault("https://www.hdfilmizle.to")
}

/**
 * Vidrame bazı isteklerde Cloudflare/JS doğrulama sayfası döndürüyor; uygulama
 * yanıtı tarayıcıyla doğrulayıp çerezi otomatik eklesin diye istekleri işaretler.
 */
class VidrameCloudflareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val peeked = runCatching { response.peekBody(1024 * 256)?.string() }.getOrNull().orEmpty()
        val needsBrowser = response.code in intArrayOf(403, 429, 503) ||
            peeked.contains("Güvenlik taramasından geçiriliyorsunuz") ||
            peeked.contains("cf-browser-verification") ||
            peeked.contains("Just a moment") ||
            peeked.contains("Checking your browser")
        return if (needsBrowser) {
            response.close()
            val warmed = request.newBuilder()
                .header("User-Agent", HDFilmIzlePlayerResolver.BROWSER_UA)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .build()
            chain.proceed(warmed)
        } else {
            response
        }
    }
}
