package com.keyiflerolsun

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.jsoup.Jsoup
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **Canlı zincir probu** — DiziBox eklentisinin GERÇEK [DiziBoxPlayerParser]
 * ayrıştırıcılarını canlı dizibox.live sayfasına karşı koşturur.
 *
 * `LIVE_PROBE=1` verilmedikçe atlanır. Runner IP'leri sitede 403 alabildiği
 * için erişim engelinde test nazikçe sonlanır (başarısız sayılmaz); erişim
 * varsa oynatıcı çerçevelerinin gerçekten çıkarıldığı doğrulanır.
 */
class LiveChainProbeTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val knownEpisode =
        "https://www.dizibox.live/supernatural-1-sezon-1-bolum-hd-3-izle/"

    private fun p(line: String) = println("[LIVE] $line")

    private class Res(val status: Int, val body: String?)

    private fun raw(url: String, referer: String? = null, timeoutMs: Int = 25000): Res {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = true
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Encoding", "gzip")
                referer?.let { setRequestProperty("Referer", it) }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { input ->
                val bytes = input.readBytes()
                try {
                    if (conn.contentEncoding?.contains("gzip") == true) {
                        GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                } catch (e: Throwable) {
                    bytes.toString(Charsets.UTF_8)
                }
            }
            Res(status, body)
        } catch (e: Throwable) {
            p("!! HTTP hatası $url → ${e.javaClass.simpleName}: ${e.message}")
            Res(0, null)
        } finally {
            conn?.disconnect()
        }
    }

    @Test
    fun liveChain() {
        assumeTrue("LIVE_PROBE=1 değil", System.getenv("LIVE_PROBE") == "1")

        p("=== DiziBox canlı ayrıştırıcı probu ===")
        val res = raw(knownEpisode, "https://www.dizibox.live/")
        p("$knownEpisode → ${res.status} (${res.body?.length ?: 0}B)")

        val html = res.body
        if (res.status !in 200..299 || html == null || html.length < 5000 ||
            html.contains("Just a moment", ignoreCase = true)
        ) {
            // Veri merkezi IP'si engelleniyor: kod sorunu değil, kanıt olarak kaydet.
            p("!! bölüm sayfası runner'dan erişilebilir değil (HTTP ${res.status}); iddia çalıştırılmadı")
            return
        }

        val document = Jsoup.parse(html, knownEpisode)
        p("video-area iframe=${document.select("#video-area iframe[src], #video-area iframe[data-src]").size} " +
            "toplam iframe=${document.select("iframe[src], iframe[data-src]").size} " +
            "archive-box=${document.select("div#archive-box").size}")

        val current = DiziBoxPlayerParser.playerIframes(document, knownEpisode)
        val nested = DiziBoxPlayerParser.nestedIframes(document, knownEpisode)
        p("playerIframes → ${current.size}")
        current.forEach { p("  • $it") }
        p("nestedIframes → ${nested.size}")
        nested.take(8).forEach { p("  · $it") }

        val mediaFallbacks = DiziBoxPlayerParser.fallbackMediaUrls(html)
        p("fallbackMediaUrls → ${mediaFallbacks.size}")
        mediaFallbacks.take(8).forEach { p("  · $it") }

        assertTrue(
            "sayfa erişilebilir olmasına rağmen tek bir oynatıcı izi bulunamadı",
            current.isNotEmpty() || nested.isNotEmpty() || mediaFallbacks.isNotEmpty(),
        )
        assertTrue(
            "çözülmemiş JS şablonu iframe olarak sızdı",
            (current + nested).none { it.contains("\${") || it.contains("{{") },
        )
    }
}
