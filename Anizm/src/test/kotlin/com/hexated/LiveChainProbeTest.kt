package com.hexated

import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **Canlı zincir probu** — eklentinin GERÇEK ayrıştırıcılarını canlı siteye karşı koşturur
 * ve video zincirini ham HTTP ile adım adım yürür.
 *
 * `LIVE_PROBE=1` verilmedikçe atlanır (normal `:Anizm:test` ağa çıkmaz).
 *
 * Kapsam (tahmin yok, ölçüm var):
 *  1. alan adı erişimi (anizm.net / puffytr / anizm.com.tr …) + Cloudflare durumu,
 *  2. `/arama?q=` arama sayfası → [AnizmSite.parseCards],
 *  3. `/searchAnime?...` JSON ucu (JSON anahtarları + kart eşlemesi),
 *  4. seri sayfası → [AnizmSite.parseDetail] + [AnizmSite.parseEpisodes],
 *  5. bölüm sayfası → `[data-sources]` / `[data-video][data-url]` dökümü + +18 kapısı,
 *  6. `data-sources` → `{videos:[…]}` (düz / XHR / XHR+CSRF),
 *  7. `/video/<id>` → `{url, playerOrigin}`,
 *  8. `/player/<id>` → 302 `Location`,
 *  9. anizmplayer `POST getVideo` → `securedLink` / `videoSource`.
 */
class LiveChainProbeTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val candidates = listOf(
        "https://anizm.net",
        "https://www.puffytr.com",
        "https://anizm.com.tr",
        "https://anizm.org"
    )

    private fun p(line: String) = println("[LIVE] $line")

    private class Res(val status: Int, val body: String?, val location: String?)

    private fun raw(
        url: String,
        referer: String? = null,
        xhr: Boolean = false,
        follow: Boolean = true,
        form: Map<String, String>? = null,
        extra: Map<String, String> = emptyMap(),
        timeoutMs: Int = 20000
    ): Res {
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
                setRequestProperty("Accept-Encoding", "gzip")
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
                    out.write(
                        form.entries.joinToString("&") {
                            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                        }.toByteArray()
                    )
                }
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
            Res(status, body, conn.getHeaderField("Location"))
        } catch (e: Throwable) {
            p("!! HTTP hatası $url → ${e.javaClass.simpleName}: ${e.message}")
            Res(0, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun title(html: String): String =
        Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.getOrNull(1)?.trim()?.take(80) ?: "-"

    private fun keys(json: String?): String =
        if (json == null) "-"
        else Regex("\"([A-Za-z_][A-Za-z0-9_]*)\"\\s*:").findAll(json)
            .map { it.groupValues[1] }.distinct().take(12).joinToString(",")

    @Test
    fun liveChain() {
        assumeTrue("LIVE_PROBE=1 değil", System.getenv("LIVE_PROBE") == "1")

        p("=== 1) ALAN ADI ERİŞİMİ ===")
        var base = ""
        for (dom in candidates) {
            val r = raw("$dom/")
            p("$dom/ → ${r.status} (${r.body?.length ?: 0}B) başlık=${r.body?.let { title(it) } ?: "-"}")
            if (r.status in 200..299 && (r.body?.length ?: 0) > 20000 && base.isEmpty()) base = dom
        }
        p("seçilen taban: ${base.ifBlank { "YOK" }}")
        if (base.isEmpty()) return

        p("\n=== 2) ARAMA SAYFASI ===")
        for (path in listOf("/arama?q=naruto", "/arama?arama=naruto")) {
            val r = raw("$base$path", "$base/")
            p("$path → ${r.status} (${r.body?.length ?: 0}B)")
            val body = r.body ?: continue
            if (body.length < 1000) continue
            val doc = Jsoup.parse(body, base)
            p("  article=${doc.select("article").size} " +
                "a[href*=-bolum-izle]=${doc.select("a[href*=-bolum-izle]").size} " +
                "a[href*=/anime/]=${doc.select("a[href*=/anime/]").size}")
            val cards = AnizmSite.parseCards(doc, base, 30)
            p("  parseCards → ${cards.size}")
            cards.take(8).forEach { p("    • ${it.name} | ${it.url} | ${it.poster?.take(70)}") }
        }

        p("\n=== 3) ARAMA JSON UCU ===")
        for (path in listOf(
            "/searchAnime?query=naruto&page=1&type=detailed&limit=10",
            "/searchAnime?query=naruto&page=1&type=detailed&limit=10" +
                "&priorityField=info_title&orderBy=info_year&orderDirection=ASC",
            "/getAnimeListForSearch?search=naruto&page=1"
        )) {
            val r = raw("$base$path", "$base/", xhr = true)
            p("$path → ${r.status} (${r.body?.length ?: 0}B) anahtarlar=${keys(r.body)}")
            p("    ${r.body?.take(260)?.replace("\n", " ")}")
        }

        p("\n=== 4) SERİ SAYFASI ===")
        for (seriesPath in listOf("/one-piece", "/naruto")) {
            val r = raw("$base$seriesPath", "$base/")
            p("$seriesPath → ${r.status} (${r.body?.length ?: 0}B) başlık=${r.body?.let { title(it) } ?: "-"}")
            val body = r.body ?: continue
            if (body.length < 2000) continue
            val doc = Jsoup.parse(body, "$base$seriesPath")
            p("  a[data-episode-number]=${doc.select("a[data-episode-number]").size} " +
                "a[href*=-bolum-izle]=${doc.select("a[href*=-bolum-izle]").size} " +
                "#bolumler=${doc.select("#bolumler").size}")
            val detail = AnizmSite.parseDetail(doc, base, "$base$seriesPath")
            p("  parseDetail → '${detail.title}' yıl=${detail.year} puan=${detail.score} " +
                "tür=${detail.typeText} etiket=${detail.tags.size} bölüm=${detail.episodes.size} " +
                "poster=${detail.poster?.take(70)}")
            val eps = AnizmSite.parseEpisodes(doc, base, "$base$seriesPath")
            p("  parseEpisodes → ${eps.size}")
            eps.take(3).forEach { p("    • #${it.number} '${it.name}' → ${it.url}") }
            eps.lastOrNull()?.let { p("    • son: #${it.number} '${it.name}' → ${it.url}") }
            if (eps.isNotEmpty()) break
        }

        p("\n=== 5) BÖLÜM SAYFASI ===")
        val episodeUrls = listOf("$base/one-piece-1-bolum-izle", "$base/naruto-1-bolum-izle")
        var episodeUrl = ""
        var csrf: String? = null
        val sourceUrls = mutableListOf<String>()
        for (candidate in episodeUrls) {
            val r = raw(candidate, "$base/")
            p("$candidate → ${r.status} (${r.body?.length ?: 0}B) başlık=${r.body?.let { title(it) } ?: "-"}")
            val body = r.body ?: continue
            if (body.length < 2000) continue
            val doc = Jsoup.parse(body, candidate)
            csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")
            p("  button[data-v3-translator]=${doc.select("button[data-v3-translator]").size} " +
                "[data-sources]=${doc.select("[data-sources]").size} " +
                "[data-video][data-url]=${doc.select("[data-video][data-url]").size} " +
                "[data-video]=${doc.select("[data-video]").size} " +
                "[translator]=${doc.select("[translator]").size} " +
                "iframe=${doc.select("iframe[src]").size} " +
                "video=${doc.select("video[src]").size}")
            p("  csrf-token=${if (csrf.isNullOrBlank()) "YOK" else "VAR(${csrf!!.length})"} " +
                "+18 kapısı=${body.contains("+18", true) || body.contains("18 yaş", true)}")
            doc.select("[data-sources]").take(6).forEach {
                p("  · çevirmen '${it.attr("title").ifBlank { it.text().trim().take(24) }}' → ${it.attr("data-sources")}")
                sourceUrls.add(it.attr("data-sources"))
            }
            doc.select("[data-video][data-url]").take(6).forEach {
                p("  · gömülü video data-video='${it.attr("data-video")}' data-url='${it.attr("data-url")}'")
            }
            if (sourceUrls.isNotEmpty() || doc.select("[data-video][data-url]").isNotEmpty()) {
                episodeUrl = candidate
                break
            }
        }
        if (episodeUrl.isEmpty()) {
            p("!! çevirmen/video düğümü bulunamadı — bölüm sayfası yapısı değişmiş olabilir")
            return
        }

        p("\n=== 6) data-sources → videos[] ===")
        val videoIds = mutableListOf<String>()
        for (rawSource in sourceUrls.take(4)) {
            val url = AnizmSite.absolute(base, rawSource) ?: continue
            for (variant in listOf("duz", "xhr", "xhrcsrf")) {
                val extra = if (variant == "xhrcsrf") {
                    buildMap {
                        csrf?.let { put("X-CSRF-TOKEN", it) }
                        put("Accept", "application/json")
                    }
                } else emptyMap()
                val r = raw(url, episodeUrl, xhr = variant != "duz", extra = extra)
                p("$url [$variant] → ${r.status} (${r.body?.length ?: 0}B) anahtarlar=${keys(r.body)}")
                p("    ${r.body?.take(200)?.replace("\n", " ")}")
                val body = r.body ?: continue
                Regex("\"id\"\\s*:\\s*\"?(\\d+)\"?").findAll(body)
                    .forEach { videoIds.add(it.groupValues[1]) }
                Regex("video=\"([^\"]+)\"").findAll(body)
                    .forEach { videoIds.add(it.groupValues[1]) }
                if (videoIds.isNotEmpty()) break
            }
            if (videoIds.isNotEmpty()) break
        }
        p("video kimlikleri: ${videoIds.take(12)}")

        p("\n=== 7) /video → {url,playerOrigin} ===")
        val bolumId = Regex("/bolum/(\\d+)").find(sourceUrls.firstOrNull() ?: "")?.groupValues?.get(1)
        p("bolumId=$bolumId")
        val playerUrls = mutableListOf<String>()
        val hashes = mutableSetOf<String>()
        for (vid in videoIds.take(6)) {
            val videoUrl = if (vid.startsWith("/")) {
                AnizmSite.absolute(base, vid) ?: continue
            } else if (bolumId != null) {
                "$base/bolum/$bolumId/video/$vid"
            } else {
                continue
            }
            val r = raw(videoUrl, episodeUrl, xhr = true)
            p("$videoUrl → ${r.status} (${r.body?.length ?: 0}B) anahtarlar=${keys(r.body)}")
            p("    ${r.body?.take(260)?.replace("\n", " ")}")
            val body = r.body ?: continue
            Regex("\"(?:url|player|src|link)\"\\s*:\\s*\"([^\"]+)\"").findAll(body).forEach {
                playerUrls.add(it.groupValues[1].replace("\\/", "/"))
            }
            Regex("\\b[a-f0-9]{32}\\b").findAll(body).forEach { hashes.add(it.value) }
        }
        p("oynatıcı adayları: ${playerUrls.take(6)}")

        p("\n=== 8) /player/<id> → Location ===")
        for (vid in videoIds.take(4)) {
            if (!vid.all(Char::isDigit)) continue
            val r = raw("$base/player/$vid", episodeUrl, follow = false)
            p("$base/player/$vid → ${r.status} Location=${r.location}")
            r.location?.let { loc ->
                Regex("\\b[a-f0-9]{32}\\b").findAll(loc).forEach { hashes.add(it.value) }
                if (loc.contains("anizmplayer")) playerUrls.add(loc)
            }
        }
        playerUrls.forEach { u ->
            Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})")
                .find(u)?.let { hashes.add(it.groupValues[1]) }
        }
        p("hash'ler: ${hashes.take(6)}")

        p("\n=== 9) anizmplayer getVideo ===")
        for (hash in hashes.take(4)) {
            for ((variant, ref) in listOf("site" to "$base/", "player" to "https://anizmplayer.com/video/$hash")) {
                val r = raw(
                    "https://anizmplayer.com/player/index.php?data=$hash&do=getVideo",
                    "https://anizmplayer.com/",
                    xhr = true,
                    form = mapOf("hash" to hash, "r" to ref),
                    extra = mapOf("Origin" to "https://anizmplayer.com")
                )
                p("getVideo ${hash.take(8)} [$variant] → ${r.status} (${r.body?.length ?: 0}B) " +
                    "anahtarlar=${keys(r.body)}")
                p("    ${r.body?.take(220)?.replace("\n", " ")}")
            }
        }
    }
}
