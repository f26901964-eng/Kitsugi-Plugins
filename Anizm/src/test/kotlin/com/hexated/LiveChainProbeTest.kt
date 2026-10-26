package com.hexated

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **Canlı zincir probu** — eklentinin GERÇEK kodunu canlı siteye karşı koşturur.
 *
 * `LIVE_PROBE=1` verilmedikçe atlanır (normal `:Anizm:test` ağa çıkmaz).
 * Amaç: "eklenti videoyu neden vermiyor?" sorusunu tahminle değil, adım adım
 * durum kodları ve ham yanıtlarla yanıtlamak:
 *
 *  1. alan adı erişimi (anizm.com.tr / anizm.net) + Cloudflare durumu,
 *  2. `/arama?q=` arama sayfası → [AnizmSite.parseCards],
 *  3. `/searchAnime?...` JSON ucu → [AnizmSite.cardFromJsonFields],
 *  4. seri sayfası → [AnizmSite.parseDetail] + [AnizmSite.parseEpisodes],
 *  5. bölüm sayfası → [AnizmStreams.load] (çevirmen → video → oynatıcı → akış).
 *
 * Çıktı Gradle `-i` ile PR yorumuna yazılır.
 */
class LiveChainProbeTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val candidates = listOf(
        "https://anizm.com.tr",
        "https://anizm.net",
        "https://anizm.org"
    )

    private fun p(line: String) = println("[LIVE] $line")

    /** Ham HTTP: (durum, gövde, Location). */
    private fun raw(
        url: String,
        referer: String? = null,
        xhr: Boolean = false,
        follow: Boolean = true,
        form: Map<String, String>? = null,
        extra: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000
    ): Triple<Int, String?, String?> {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (form == null) "GET" else "POST"
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
                referer?.let { setRequestProperty("Referer", it) }
                extra.forEach { (k, v) -> setRequestProperty(k, v) }
                if (form != null) {
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                }
            }
            if (form != null) {
                conn.outputStream.use { out ->
                    out.write(form.entries.joinToString("&") {
                        "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                    }.toByteArray())
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { input ->
                val raw = input.readBytes()
                val text = try {
                    if (conn.contentEncoding?.contains("gzip") == true) {
                        GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8)
                    } else {
                        raw.toString(Charsets.UTF_8)
                    }
                } catch (e: Throwable) {
                    raw.toString(Charsets.UTF_8)
                }
                text
            }
            Triple(status, body, conn.getHeaderField("Location"))
        } catch (e: Throwable) {
            p("!! HTTP hatası $url → ${e.javaClass.simpleName}: ${e.message}")
            Triple(0, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    private inner class LiveHttp : AnizmHttp {
        override suspend fun get(url: String, referer: String?): String? {
            val (s, b, _) = raw(url, referer)
            p("    GET $url → $s (${b?.length ?: 0}B)")
            return if (s in 200..299) b else null
        }

        override suspend fun getXhr(url: String, referer: String?): String? {
            val (s, b, _) = raw(url, referer, xhr = true)
            p("    XHR $url → $s (${b?.length ?: 0}B) ${b?.take(160)?.replace("\n", " ") ?: ""}")
            return if (s in 200..299) b else null
        }

        override suspend fun location(url: String, referer: String?): String? {
            val (s, _, loc) = raw(url, referer, follow = false)
            p("    LOC $url → $s → ${loc ?: "-"}")
            return loc
        }

        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            referer: String?
        ): String? {
            val (s, b, _) = raw(url, referer, xhr = true, form = form)
            p("    POST $url → $s (${b?.length ?: 0}B) ${b?.take(160)?.replace("\n", " ") ?: ""}")
            return if (s in 200..299) b else null
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
            if (s in 200..299 && (b?.length ?: 0) > 20000 && base.isEmpty()) base = dom
        }
        p("seçilen taban: $base")
        if (base.isEmpty()) {
            p("!! hiçbir alan adı açılmadı")
            return
        }

        val http = LiveHttp()

        p("\n=== 2) ARAMA SAYFASI (/arama?q=naruto) ===")
        val (ss, sb, _) = raw("$base/arama?q=naruto", "$base/")
        p("durum=$ss bytes=${sb?.length ?: 0}")
        if (sb != null && sb.length > 1000) {
            val doc = Jsoup.parse(sb, base)
            p("article sayısı: " + doc.select("article").size)
            p("v3-series-card: " + doc.select(".v3-series-card, [class*=v3-series]").size)
            p("a[href$=-bolum-izle]: " + doc.select("a[href*=-bolum-izle]").size)
            val cards = AnizmSite.parseCards(doc, base, 30)
            p("parseCards → ${cards.size}")
            cards.take(8).forEach { p("  • ${it.name} | ${it.url} | poster=${it.poster?.take(70)}") }
        }

        p("\n=== 3) ARAMA JSON UCU (/searchAnime) ===")
        val (js, jb, _) = raw(
            "$base/searchAnime?query=naruto&page=1&type=detailed&limit=10" +
                "&priorityField=info_title&orderBy=info_year&orderDirection=ASC",
            "$base/",
            xhr = true
        )
        p("durum=$js bytes=${jb?.length ?: 0}")
        p("gövde başı: ${jb?.take(500)?.replace("\n", " ")}")

        p("\n=== 4) SERİ SAYFASI ===")
        val seriesUrl = "$base/one-piece"
        val (ds, db, _) = raw(seriesUrl, "$base/")
        p("$seriesUrl → $ds (${db?.length ?: 0}B) başlık=${db?.let { title(it) } ?: "-"}")
        if (db != null && db.length > 2000) {
            val doc = Jsoup.parse(db, seriesUrl)
            p("data-episode-number: " + doc.select("a[data-episode-number]").size)
            p("a[href*=-bolum-izle]: " + doc.select("a[href*=-bolum-izle]").size)
            p("#bolumler: " + doc.select("#bolumler").size)
            val detail = AnizmSite.parseDetail(doc, base, seriesUrl)
            p("parseDetail → başlık=${detail.title} yıl=${detail.year} puan=${detail.score} " +
                "etiket=${detail.tags.size} bölüm=${detail.episodes.size}")
            val eps = AnizmSite.parseEpisodes(doc, base, seriesUrl)
            p("parseEpisodes → ${eps.size}")
            eps.take(3).forEach { p("  • #${it.number} ${it.name} → ${it.url}") }
            eps.lastOrNull()?.let { p("  • son: #${it.number} ${it.name} → ${it.url}") }
        }

        p("\n=== 5) BÖLÜM SAYFASI + AKIŞ ZİNCİRİ ===")
        val episodeUrl = "$base/one-piece-1-bolum-izle"
        val (es, eb, _) = raw(episodeUrl, "$base/")
        p("$episodeUrl → $es (${eb?.length ?: 0}B)")
        if (eb != null && eb.length > 2000) {
            val doc = Jsoup.parse(eb, episodeUrl)
            p("button[data-v3-translator]: " + doc.select("button[data-v3-translator]").size)
            p("[data-sources]: " + doc.select("[data-sources]").size)
            p("[data-video][data-url]: " + doc.select("[data-video][data-url]").size)
            p("csrf-token: " + (doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.take(12) ?: "YOK"))
            doc.select("[data-sources]").take(6).forEach {
                p("  · çevirmen '${it.attr("title").ifBlank { it.text().trim() }}' → ${it.attr("data-sources")}")
            }
            doc.select("[data-video][data-url]").take(6).forEach {
                p("  · video '${it.attr("data-video")}' → ${it.attr("data-url")}")
            }
            val inline = doc.select("script:not([src])").joinToString("\n") { it.data() }
            p("inline script boyutu: ${inline.length}; '/bolum/' geçişi: " +
                Regex("/bolum/").findAll(inline).count())
        }

        val streams = AnizmStreams("Anizm", { base }, http)
        var emitted = 0
        p("--- AnizmStreams.load($episodeUrl) ---")
        val count = try {
            runBlocking {
                streams.load(
                    episodeUrl,
                    { sub -> p("  ALTYAZI: ${sub.lang} ${sub.url.take(90)}") },
                    { link ->
                        emitted++
                        p("  AKIŞ #$emitted: '${link.name}' ${link.quality}p ${link.type} → ${link.url.take(160)}")
                    }
                )
            }
        } catch (e: Throwable) {
            p("!! load istisna: ${e.javaClass.simpleName}: ${e.message}")
            -1
        }
        p("sonuç: load=$count callback=$emitted")

        p("\n=== 6) İKİNCİ BÖLÜM (taze bölüm) ===")
        val freshUrl = "$base/cyberpunk-edgerunners-2-1-bolum-izle"
        val (fs2, fb, _) = raw(freshUrl, "$base/")
        p("$freshUrl → $fs2 (${fb?.length ?: 0}B)")
        if (fb != null && fb.length > 2000) {
            var n = 0
            val c2 = try {
                runBlocking {
                    streams.load(
                        freshUrl,
                        { },
                        { link ->
                            n++
                            p("  AKIŞ #$n: '${link.name}' ${link.quality}p ${link.type} → ${link.url.take(160)}")
                        }
                    )
                }
            } catch (e: Throwable) {
                -1
            }
            p("sonuç: load=$c2 callback=$n")
        }
    }

    private fun title(html: String): String =
        Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(80) ?: "-"
}
