package com.kraptor

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **TrAnimeIzle canlı zincir probu** — eklentinin gerçek kodunu canlı siteye koşturur.
 *
 * `LIVE_PROBE=1` verilmedikçe atlanır. Site Cloudflare arkasında olduğundan
 * doğrudan erişim 403 dönebiliyor; bu durumda okuma katmanı olarak
 * `r.jina.ai` (ham HTML) denenir ve hangi katmanın çalıştığı raporlanır.
 * POST uçları (`/api/fansubSources`, `/api/sourcePlayer`) proxy'lenemez;
 * onların durum kodu doğrudan raporlanır — bu, sorunun kodda mı yoksa
 * ağ katmanında mı olduğunu kesin ayırır.
 */
class LiveChainProbeTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val candidates = listOf(
        "https://www.tranimeizle.live",
        "https://www.tranimeizle.io",
        "https://www.tranimeizle.top"
    )

    private fun p(line: String) = println("[LIVE] $line")

    private fun raw(
        url: String,
        referer: String? = null,
        xhr: Boolean = false,
        follow: Boolean = true,
        json: String? = null,
        form: Map<String, String>? = null,
        post: Boolean = false,
        timeoutMs: Int = 20000,
        extraHeaders: Map<String, String> = emptyMap()
    ): Triple<Int, String?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (post) "POST" else "GET"
                instanceFollowRedirects = follow
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                setRequestProperty(
                    "Accept",
                    if (xhr) "application/json, text/plain, */*"
                    else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                if (xhr) setRequestProperty("X-Requested-With", "XMLHttpRequest")
                extraHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
                referer?.let { setRequestProperty("Referer", it) }
                if (json != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                } else if (form != null) {
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                } else if (post) {
                    doOutput = true
                    setRequestProperty("Content-Length", "0")
                }
            }
            val payload = json ?: form?.entries?.joinToString("&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
            if (payload != null) {
                conn.outputStream.use { it.write(payload.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { input ->
                val raw = input.readBytes()
                try {
                    if (conn.contentEncoding?.contains("gzip") == true) {
                        GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8)
                    } else {
                        raw.toString(Charsets.UTF_8)
                    }
                } catch (e: Throwable) {
                    raw.toString(Charsets.UTF_8)
                }
            }
            Triple(status, body, conn.getHeaderField("Location"))
        } catch (e: Throwable) {
            p("!! HTTP hatası $url → ${e.javaClass.simpleName}: ${e.message}")
            Triple(0, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun viaJina(url: String): String? {
        val (s, b, _) = raw("https://r.jina.ai/$url", extraHeaders = mapOf("x-respond-with" to "html"))
        p("    JINA $url → $s (${b?.length ?: 0}B)")
        return if (s in 200..299) b else null
    }


    private inner class LiveHttp : TrStreamHttp {
        override suspend fun get(url: String, referer: String?): String? {
            val (s, b, _) = raw(url, referer)
            p("    GET $url → $s (${b?.length ?: 0}B)")
            if (s in 200..299) return b
            return viaJina(url)
        }

        override suspend fun getXhr(url: String, referer: String?): String? {
            val (s, b, _) = raw(url, referer, xhr = true)
            p("    XHR $url → $s (${b?.length ?: 0}B) ${b?.take(140)?.replace("\n", " ") ?: ""}")
            return if (s in 200..299) b else null
        }

        override suspend fun postJson(url: String, json: String, referer: String?): String? {
            val (s, b, _) = raw(url, referer, xhr = true, json = json, post = true)
            p("    POSTJSON $url → $s (${b?.length ?: 0}B) ${b?.take(140)?.replace("\n", " ") ?: ""}")
            return if (s in 200..299) b else null
        }

        override suspend fun postEmpty(url: String, referer: String?): String? {
            val (s, b, _) = raw(url, referer, xhr = true, post = true)
            p("    POSTEMPTY $url → $s (${b?.length ?: 0}B) ${b?.take(140)?.replace("\n", " ") ?: ""}")
            return if (s in 200..299) b else null
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            referer: String?
        ): String? {
            val (s, b, _) = raw(url, referer, xhr = true, form = form, post = true)
            p("    POSTFORM $url → $s (${b?.length ?: 0}B)")
            return if (s in 200..299) b else null
        }

        override suspend fun bytes(url: String, referer: String?): ByteArray? = null

        override suspend fun solveCaptcha(): Boolean {
            p("    solveCaptcha() çağrıldı (prob: çözülmüyor)")
            return false
        }
    }

    @Test
    fun liveChain() {
        assumeTrue("LIVE_PROBE=1 değil", System.getenv("LIVE_PROBE") == "1")

        p("=== 1) ALAN ADI ERİŞİMİ ===")
        var base = ""
        for (dom in candidates) {
            val (s, b, _) = raw("$dom/")
            p("$dom/ → $s (${b?.length ?: 0}B) başlık=${b?.let { title(it) } ?: "-"}")
            if (s in 200..299 && (b?.length ?: 0) > 5000 && base.isEmpty()) base = dom
        }
        if (base.isEmpty()) {
            base = candidates.first()
            p("doğrudan erişim yok → jina katmanı denenecek")
        }
        p("seçilen taban: $base")

        val http = LiveHttp()

        p("\n=== 2) ANA SAYFA (jina dahil) ===")
        val home = http.runBlockingGet("$base/")
        p("ana sayfa bytes=${home?.length ?: 0} başlık=${home?.let { title(it) } ?: "-"}")
        if (home != null && home.length > 2000) {
            val doc = Jsoup.parse(home, base)
            p("div.flx-block: " + doc.select("div.flx-block").size)
            p("[data-href]: " + doc.select("[data-href]").size)
            p("a[href*=-bolum-izle]: " + doc.select("a[href*=-bolum-izle]").size)
            val cards = TrAnimeIzleSite.parseCards(doc, base, 20)
            p("parseCards → ${cards.size}")
            cards.take(5).forEach { p("  • ${it.name} | ${it.url}") }
        }

        p("\n=== 3) ARAMA ===")
        for (path in listOf("/arama/naruto", "/arama?q=naruto")) {
            val html = http.runBlockingGet("$base$path", "$base/")
            p("$path → bytes=${html?.length ?: 0}")
            if (html != null && html.length > 1500) {
                val cards = TrAnimeIzleSite.parseCards(Jsoup.parse(html, base), base, 20)
                p("  parseCards → ${cards.size}")
                cards.take(4).forEach { p("    • ${it.name} | ${it.url}") }
            }
        }

        p("\n=== 4) DETAY + BÖLÜMLER ===")
        for (detailUrl in listOf("$base/anime/one-piece-izle", "$base/anime/shingeki-no-kyojin-izle")) {
            val html = http.runBlockingGet(detailUrl, "$base/")
            p("$detailUrl → bytes=${html?.length ?: 0}")
            if (html == null || html.length < 1500) continue
            val doc = Jsoup.parse(html, detailUrl)
            val detail = TrAnimeIzleSite.parseDetail(doc, base, detailUrl)
            p("  parseDetail → ${detail.title} yıl=${detail.year} bölüm=${detail.episodes.size}")
            detail.episodes.take(3).forEach { p("    • #${it.number} ${it.name} → ${it.url}") }
            break
        }

        p("\n=== 5) BÖLÜM + AKIŞ ZİNCİRİ ===")
        val episodeUrl = "$base/shingeki-no-kyojin-1-bolum-izle"
        val html = http.runBlockingGet(episodeUrl, "$base/")
        p("$episodeUrl → bytes=${html?.length ?: 0}")
        if (html != null && html.length > 1500) {
            val doc = Jsoup.parse(html, episodeUrl)
            p("fansubSelector: " + doc.select(".fansubSelector").size)
            p("[data-fid]: " + doc.select("[data-fid]").size)
            p("episodeId: " + (TrAnimeIzleSite.episodeIdFrom(html, doc) ?: "YOK"))
            doc.select("[data-fid]").take(5).forEach {
                p("  · fansub '${it.text().trim().take(24)}' fid=${it.attr("data-fid")} fad=${it.attr("data-fad")}")
            }
            val fansubs = TrAnimeIzleSite.fansubsFrom(doc)
            p("fansubsFrom → ${fansubs.size}: " + fansubs.take(5).joinToString { "${it.label}(${it.id})" })
        }

        val streams = TrAnimeIzleStreams("TrAnimeIzle", { base }, http)
        var n = 0
        val count = try {
            runBlocking {
                streams.load(
                    episodeUrl,
                    { sub -> p("  ALTYAZI: ${sub.lang} ${sub.url.take(90)}") },
                    { link ->
                        n++
                        p("  AKIŞ #$n: '${link.name}' ${link.quality}p ${link.type} → ${link.url.take(160)}")
                    }
                )
            }
        } catch (e: Throwable) {
            p("!! load istisna: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
        p("sonuç: load=$count callback=$n")
    }

    @Suppress("BlockingMethodInNonBlockingContext")
    private fun TrStreamHttp.runBlockingGet(url: String, referer: String? = null): String? =
        runBlocking { get(url, referer) }

    private fun title(html: String): String =
        Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(80) ?: "-"
}
