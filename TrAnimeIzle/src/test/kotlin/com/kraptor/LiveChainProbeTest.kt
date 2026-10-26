package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **TrAnimeIzle canlı zincir probu** — eklentinin gerçek ayrıştırıcılarını canlı siteye
 * karşı koşturur, video zincirini ham HTTP ile yürür.
 *
 * `LIVE_PROBE=1` verilmedikçe atlanır.
 *
 * Kapsam:
 *  1. alan adı erişimi (.live / .io / .top) — doğrudan ve `r.jina.ai` okuma katmanı,
 *  2. ana sayfa → [TrAnimeIzleSite.parseCards],
 *  3. `/arama/…` → kartlar,
 *  4. detay → [TrAnimeIzleSite.parseDetail] + bölümler,
 *  5. bölüm → `.fansubSelector[data-fid][data-fad]`, `episodeIdFrom`, `fansubsFrom`,
 *  6. `POST /api/fansubSources` (JSON + form varyantları) → `li.sourceBtn[data-id]`,
 *  7. `POST /api/sourcePlayer/{id}` ve `POST /api/preVideoAd/{id}` → iframe,
 *  8. iframe → host (ok.ru / sibnet / voe / …) ön-dökümü.
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

    private class Res(val status: Int, val body: String?, val location: String?)

    private fun raw(
        url: String,
        referer: String? = null,
        xhr: Boolean = false,
        follow: Boolean = true,
        json: String? = null,
        form: Map<String, String>? = null,
        post: Boolean = false,
        extra: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000
    ): Res {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (post || json != null || form != null) "POST" else "GET"
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
                setRequestProperty("Accept-Encoding", "gzip")
                if (xhr) setRequestProperty("X-Requested-With", "XMLHttpRequest")
                referer?.let { setRequestProperty("Referer", it) }
                extra.forEach { (k, v) -> setRequestProperty(k, v) }
                when {
                    json != null -> {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    }
                    form != null -> {
                        doOutput = true
                        setRequestProperty(
                            "Content-Type",
                            "application/x-www-form-urlencoded; charset=UTF-8"
                        )
                    }
                    post -> {
                        doOutput = true
                        setRequestProperty("Content-Length", "0")
                    }
                }
            }
            val payload = json ?: form?.entries?.joinToString("&") {
                "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
            }
            if (payload != null) conn.outputStream.use { it.write(payload.toByteArray()) }
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
            Res(status, body, conn.getHeaderField("Location"))
        } catch (e: Throwable) {
            p("!! HTTP hatası $url → ${e.javaClass.simpleName}: ${e.message}")
            Res(0, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    /** `r.jina.ai` ham HTML katmanı (Cloudflare arkasındaki sayfalar için). */
    private fun jina(url: String): String? {
        val r = raw("https://r.jina.ai/$url", extra = mapOf("x-respond-with" to "html"))
        p("    JINA $url → ${r.status} (${r.body?.length ?: 0}B)")
        return if (r.status in 200..299) r.body else null
    }

    private fun title(html: String): String =
        Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(80) ?: "-"

    private fun keys(json: String?): String =
        if (json == null) "-"
        else Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:").findAll(json)
            .map { it.groupValues[1] }.distinct().take(10).joinToString(",")

    @Test
    fun liveChain() {
        assumeTrue("LIVE_PROBE=1 değil", System.getenv("LIVE_PROBE") == "1")

        p("=== 1) ALAN ADI ERİŞİMİ ===")
        var base = ""
        for (dom in candidates) {
            val r = raw("$dom/")
            p("$dom/ → ${r.status} (${r.body?.length ?: 0}B) başlık=${r.body?.let { title(it) } ?: "-"}")
            if (r.status in 200..299 && (r.body?.length ?: 0) > 5000 && base.isEmpty()) base = dom
        }
        if (base.isEmpty()) base = candidates.first()
        p("seçilen taban: $base")

        p("\n=== 2) ANA SAYFA ===")
        var home = raw("$base/").body
        if (home == null || home.length < 5000) home = jina("$base/")
        p("ana sayfa bytes=${home?.length ?: 0} başlık=${home?.let { title(it) } ?: "-"}")
        if (home != null && home.length > 2000) {
            val doc = Jsoup.parse(home, base)
            p("div.flx-block=${doc.select("div.flx-block").size} [data-href]=${doc.select("[data-href]").size} " +
                "a[href*=-bolum-izle]=${doc.select("a[href*=-bolum-izle]").size}")
            val cards = TrAnimeIzleSite.parseCards(doc, base, 20)
            p("parseCards → ${cards.size}")
            cards.take(5).forEach { p("  • ${it.name} | ${it.url}") }
        }

        p("\n=== 3) ARAMA ===")
        for (path in listOf("/arama/naruto", "/arama?q=naruto", "/search?q=naruto")) {
            var body = raw("$base$path", "$base/").body
            val viaJina = body == null || body.length < 1500
            if (viaJina) body = jina("$base$path")
            p("$path → bytes=${body?.length ?: 0}${if (viaJina) " (jina)" else ""}")
            if (body != null && body.length > 1000) {
                val cards = TrAnimeIzleSite.parseCards(Jsoup.parse(body, base), base, 20)
                p("  parseCards → ${cards.size}")
                cards.take(4).forEach { p("    • ${it.name} | ${it.url}") }
            }
        }

        p("\n=== 4) DETAY + BÖLÜMLER ===")
        for (detailUrl in listOf(
            "$base/anime/one-piece-izle",
            "$base/anime/shingeki-no-kyojin-izle",
            "$base/anime/naruto-izle"
        )) {
            var body = raw(detailUrl, "$base/").body
            if (body == null || body.length < 1500) body = jina(detailUrl)
            p("$detailUrl → bytes=${body?.length ?: 0} başlık=${body?.let { title(it) } ?: "-"}")
            if (body == null || body.length < 1000) continue
            val doc = Jsoup.parse(body, detailUrl)
            p("  fansubSelector=${doc.select(".fansubSelector").size} [data-fid]=${doc.select("[data-fid]").size} " +
                "a[href*=-bolum-izle]=${doc.select("a[href*=-bolum-izle]").size}")
            val detail = TrAnimeIzleSite.parseDetail(doc, base, detailUrl)
            p("  parseDetail → '${detail.title}' yıl=${detail.year} puan=${detail.score} " +
                "bölüm=${detail.episodes.size} fansub=${detail.fansubs}")
            detail.episodes.take(3).forEach { p("    • S${it.season} #${it.number} '${it.name}' → ${it.url}") }
            break
        }

        p("\n=== 5) BÖLÜM SAYFASI ===")
        var episodeUrl = ""
        var episodeHtml: String? = null
        for (path in listOf(
            "$base/shingeki-no-kyojin-1-bolum-izle",
            "$base/one-piece-1-bolum-izle",
            "$base/naruto-1-bolum-izle"
        )) {
            var body = raw(path, "$base/").body
            if (body == null || body.length < 1500) body = jina(path)
            p("$path → bytes=${body?.length ?: 0} başlık=${body?.let { title(it) } ?: "-"}")
            if (body != null && body.length > 1000) {
                episodeUrl = path
                episodeHtml = body
                break
            }
        }
        if (episodeHtml == null) {
            p("!! bölüm sayfası alınamadı (tüm katmanlar)")
            return
        }
        val doc = Jsoup.parse(episodeHtml, episodeUrl)
        p("fansubSelector=${doc.select(".fansubSelector").size} [data-fid]=${doc.select("[data-fid]").size} " +
            "sourceBtn=${doc.select("li.sourceBtn, .sourceBtn").size} iframe=${doc.select("iframe[src]").size}")
        val episodeId = TrAnimeIzleSite.episodeIdFrom(episodeHtml, doc)
        p("episodeId=$episodeId")
        doc.select("[data-fid]").take(6).forEach {
            p("  · fansub '${it.text().trim().take(24)}' fid=${it.attr("data-fid")} fad=${it.attr("data-fad")}")
        }
        val fansubs = TrAnimeIzleSite.fansubsFrom(doc)
        p("fansubsFrom → ${fansubs.size}: ${fansubs.take(6).joinToString { "${it.label}(${it.id})" }}")

        p("\n=== 6) POST /api/fansubSources ===")
        val sourceIds = mutableListOf<String>()
        for (fansub in fansubs.take(3)) {
            val payloads = listOf(
                "json-named" to """{"fansub_id":"${fansub.id}","fansub_adi":"","anime_id":"$episodeId","anime_adi":""}""",
                "json-raw" to """{"fid":${fansub.id},"fad":$episodeId}"""
            )
            for ((tag, payload) in payloads) {
                val r = raw("$base/api/fansubSources", episodeUrl, xhr = true, json = payload)
                p("fansubSources(${fansub.id},$episodeId) [$tag] → ${r.status} " +
                    "(${r.body?.length ?: 0}B) anahtarlar=${keys(r.body)}")
                p("    ${r.body?.take(240)?.replace("\n", " ")}")
                val body = r.body ?: continue
                Regex("data-id=[\"'](\\d+)").findAll(body).forEach { sourceIds.add(it.groupValues[1]) }
                Regex("\"id\"\\s*:\\s*\"?(\\d+)\"?").findAll(body).forEach { sourceIds.add(it.groupValues[1]) }
                if (sourceIds.isNotEmpty()) break
            }
            if (sourceIds.isNotEmpty()) break
        }
        p("kaynak kimlikleri: ${sourceIds.distinct().take(10)}")

        p("\n=== 7) POST /api/sourcePlayer + preVideoAd ===")
        val iframes = mutableListOf<String>()
        for (id in sourceIds.distinct().take(6)) {
            val r1 = raw("$base/api/sourcePlayer/$id", episodeUrl, xhr = true, post = true)
            p("sourcePlayer/$id → ${r1.status} (${r1.body?.length ?: 0}B) anahtarlar=${keys(r1.body)}")
            p("    ${r1.body?.take(240)?.replace("\n", " ")}")
            r1.body?.let { body ->
                Regex("<iframe[^>]+src=[\"']([^\"']+)").findAll(body).forEach { iframes.add(it.groupValues[1]) }
                Regex("\"source\"\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach {
                    Regex("src=[\"']([^\"']+)").findAll(it.groupValues[1])
                        .forEach { m -> iframes.add(m.groupValues[1]) }
                }
            }
            if (iframes.isEmpty()) {
                val r2 = raw("$base/api/preVideoAd/$id", episodeUrl, xhr = true, post = true)
                p("preVideoAd/$id → ${r2.status} (${r2.body?.length ?: 0}B) anahtarlar=${keys(r2.body)}")
                p("    ${r2.body?.take(240)?.replace("\n", " ")}")
                r2.body?.let { body ->
                    Regex("<iframe[^>]+src=[\"']([^\"']+)").findAll(body)
                        .forEach { iframes.add(it.groupValues[1]) }
                }
            }
        }
        p("iframe adayları: ${iframes.distinct().take(8)}")

        p("\n=== 8) iframe host'ları ===")
        for (frame in iframes.distinct().take(4)) {
            p("  · ${frame.take(140)}")
            val embeds = TrAnimeIzleSite.embedCandidates(frame)
            p("    embedCandidates → ${embeds.take(5)}")
            val r = raw(frame, episodeUrl)
            p("    GET → ${r.status} (${r.body?.length ?: 0}B) başlık=${r.body?.let { title(it) } ?: "-"}")
            r.body?.let { body ->
                val media = TrAnimeIzleSite.directMediaFrom(body, frame)
                p("    directMediaFrom → ${media.take(3)}")
            }
        }
    }
}
