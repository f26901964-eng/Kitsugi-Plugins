package com.nikyokki

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **Canlı zincir probu** — eklentinin GERÇEK [HDFilmIzlePlayerParser] ayrıştırıcısını
 * canlı hdfilmizle.to sayfasına karşı koşturur.
 *
 * `LIVE_PROBE=1` verilmedikçe atlanır (normal `test` ağa çıkmaz). Runner IP'si
 * Cloudflare arkasındaki oynatıcı alan adlarına (vidrame vb.) takılabildiği için
 * yalnızca herkese açık bölüm sayfası ve saf ayrıştırıcı çıktısı doğrulanır;
 * oynatıcı sayfası isteği sadece durum kaydı olarak yazılır, iddia taşımaz.
 */
class LiveChainProbeTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/535.11 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val knownEpisode =
        "https://www.hdfilmizle.to/dizi/supernatural-izle/sezon-1/bolum-1/"

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

        p("=== HDFilmİzle canlı ayrıştırıcı probu ===")
        var pageUrl = knownEpisode
        var page = raw(pageUrl, "https://www.hdfilmizle.to/")
        p("$pageUrl → ${page.status} (${page.body?.length ?: 0}B)")

        // Bilinen bölüm erişilemezse ana sayfadan ilk /dizi/ veya /film/ çapasını dene.
        if (page.status !in 200..299 || (page.body?.length ?: 0) < 5000) {
            val home = raw("https://www.hdfilmizle.to/", "https://www.hdfilmizle.to/")
            val anchor = home.body?.let {
                Regex("""href=["'](https?://[^"']*(?:/dizi/|/film/)[^"']+)["']""")
                    .find(it)?.groupValues?.get(1)
            }
            p("yedek içerik çapası: ${anchor ?: "YOK"}")
            if (anchor != null) {
                pageUrl = anchor
                page = raw(pageUrl, "https://www.hdfilmizle.to/")
                p("$pageUrl → ${page.status} (${page.body?.length ?: 0}B)")
            }
        }

        val html = page.body
        assertTrue("bölüm sayfası erişilebilir değil (HTTP ${page.status})", html != null && html.length > 5000)
        html!!

        val partsCount = Regex("""(?:let|var|const)\s+parts\s*=\s*\[""").findAll(html).count()
        p("parts işaretçi sayısı: $partsCount | statik iframe: " +
            Regex("""(?i)<iframe[^>]+(?:src|data-src)""").findAll(html).count())

        // GERÇEK ayrıştırıcı çalışıyor.
        val frames = HDFilmIzlePlayerParser.pageFrames(html, pageUrl)
        p("pageFrames → ${frames.size} çerçeve")
        frames.forEach { frame ->
            p("  • ${frame.url} | lang=${frame.lang.ifBlank { "-" }} " +
                "oynatıcı=${frame.playerName.ifBlank { "-" }}")
        }

        val config = HDFilmIzlePlayerParser.ajaxPlayerConfig(html)
        p("ajax yapılandırma → nonce=${config.nonces.size} " +
            "postId=${config.postIds.size} player=${config.playerNames.size}")

        // Hiçbir ölü JS şablonu çerçeve olarak sızmamalı.
        assertTrue(
            "şablon URL'leri çerçeve olarak sızdı",
            frames.none { it.url.contains("\${") || it.url.contains("{{") },
        )
        // Desteklenen oynatıcı alan adlarından en az biri bulunmalı.
        val playerTokens = listOf("vidrame", "vidmoxy", "vidmoly", "setplay", "fastplay", "stfplay")
        assertTrue(
            "desteklenen oynatıcı çerçevesi bulunamadı",
            frames.any { frame -> playerTokens.any { token -> frame.url.contains(token, ignoreCase = true) } },
        )
        assertFalse("hiç çerçeve dönmedi", frames.isEmpty())

        // İlk oynatıcı sayfasının erişim durumu yalnızca kaydedilir (CF engeli beklenebilir).
        frames.firstOrNull()?.let { first ->
            val hop = raw(first.url, pageUrl)
            p("oynatıcı ucu ${first.url} → HTTP ${hop.status} (${hop.body?.length ?: 0}B) — iddia taşımaz")
        }
    }
}
