// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.
//
// Dizipal — 2026-11 sözleşmesi (tahmin değil, ölçüm)
// --------------------------------------------------
// Site artık Next.js/Turbopack. Eski PHP temasının seçicileri
// (`div.episode-item`, `article.type2 ul li`, `/api/search-autocomplete`)
// tamamen kalktı. Ölçülen akış:
//
//   Rotalar      : /series/<slug>  /movies/<slug>  /bolum/<slug>-<s>x<e>
//                  /arama-yap?s=<q>  /trendler  /yabanci-dizi-izle  /film-izle
//   Kart         : a.tc (span.tt = başlık, img = afiş, aria-label = "Başlık, Yıl, Tür")
//   Bölümler     : RSC "seasons":[{"no":1,"episodes":[{"n":1,"title","href"}]}]
//   Oynatıcı     : RSC "player":{"sources":[…],"enc"{ciphertext,iv,salt},"k"}
//                  PBKDF2-HMAC-SHA512(k, salt, 999, 32) + AES-256-CBC
//                  → "//sn.dplayer82.site/iframe.php?v=…" → oradan m3u8
//
// Ayrıştırma/şifre çözme mantığı `DiziPalParser` içinde; JVM testleri oradan
// besleniyor (`src/test`). Bu dosya yalnızca CloudStream bağlantısı.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPal : MainAPI() {
    override var mainUrl              = "https://dizipal1593.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! Cloudflare: site zaman zaman "Just a moment" basamağı gösteriyor.
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body     = response.peekBody(1024L * 512L).string()

            if (body.contains("Just a moment") || body.contains("challenge-platform") || body.contains("cf-browser-verification")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/trendler"                     to "Trend Diziler",
        "${mainUrl}/yeni-eklenen-dizi-bolumler"   to "Son Bölümler",
        "${mainUrl}/yabanci-dizi-izle"            to "Yabancı Diziler",
        "${mainUrl}/film-izle"                    to "Filmler",
        "${mainUrl}/platform-bolumler"            to "Platformlar",
        "${mainUrl}/kategori/aksiyon-macera"      to "Aksiyon & Macera",
        "${mainUrl}/kategori/bilim-kurgu-fantazi" to "Bilim Kurgu & Fantazi",
        "${mainUrl}/kategori/dram"                to "Dram",
        "${mainUrl}/kategori/gizem"               to "Gizem",
        "${mainUrl}/kategori/komedi"              to "Komedi",
        "${mainUrl}/kategori/suc"                 to "Suç",
        "${mainUrl}/imdb-top-100"                 to "IMDb Top 100",
    )

    // ── Ana sayfa ────────────────────────────────────────────────────────────

    private val pageHrefCache = mutableMapOf<String, List<String>>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // * Sayfalama sunucunun verdiği `pageHref` dizisiyle yürür (tahmin yok).
        var links = pageHrefCache[request.data] ?: emptyList()
        var html  = ""

        if (page > 0 && links.isNotEmpty()) {
            val target = links.getOrNull(page) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
            html = fetch(fixUrl(target))
        } else {
            html  = fetch(request.data)
            links = DiziPalParser.pageHrefs(html)
            if (links.isNotEmpty()) pageHrefCache[request.data] = links

            if (page > 0) {
                val target = links.getOrNull(page) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
                html = fetch(fixUrl(target))
            }
        }

        return newHomePageResponse(
            request.name,
            parseListing(html),
            hasNext = links.size > page + 1
        )
    }

    /** Liste sayfası: önce DOM kartları (`a.tc`), sonra RSC akışları. */
    private fun parseListing(html: String): List<SearchResponse> {
        val document = Jsoup.parse(html)
        val cards    = document.select("a.tc").mapNotNull { it.toSearchResponse() }
        if (cards.isNotEmpty()) return cards

        val feed = DiziPalParser.parseEpisodeFeed(html)
        if (feed.isNotEmpty()) return feed.map { it.toSearchResponse() }

        return DiziPalParser.parseCards(html).map { it.toSearchResponse() }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/series/") && !href.contains("/movies/")) return null

        val label = attr("aria-label")
        val title = selectFirst("span.tt")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: label.substringBefore(",").trim().takeIf { it.isNotBlank() }
            ?: return null

        val posterUrl = fixUrlNull(selectFirst("img")?.attr("src"))
        val meta      = select("span").lastOrNull { it.text().contains("·") }?.text() ?: label
        val year      = Regex("(19|20)\\d{2}").find(meta)?.value?.toIntOrNull()

        return if (href.contains("/movies/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year      = year
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year      = year
            }
        }
    }

    private fun DiziPalCard.toSearchResponse(): SearchResponse {
        val url = fixUrl(href)

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) { this.posterUrl = fixUrlNull(poster) }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) { this.posterUrl = fixUrlNull(poster) }
        }
    }

    // ── Arama ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        val url  = "${mainUrl}/arama-yap?s=${URLEncoder.encode(query, "UTF-8")}"
        val hits = parseListing(fetch(url))
        if (hits.isNotEmpty()) return hits

        // * Son çare: sitenin kendi RPC ucu (POST /api/mj) — sayfa boş dönerse.
        return searchRpc(query)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun searchRpc(query: String): List<SearchResponse> {
        return try {
            val body = app.post(
                "${mainUrl}/api/mj",
                headers = getHeaders("$mainUrl/arama-yap").plus(
                    mapOf(
                        "content-type" to "application/json",
                        "x-requested-with" to "XMLHttpRequest"
                    )
                ),
                data    = """{"action":"searchContent","params":["${query.replace("\"", "\\\"")}"]}""",
                referer = "$mainUrl/arama-yap?s=${URLEncoder.encode(query, "UTF-8")}",
            ).text

            DiziPalParser.parseCards(body).map { it.toSearchResponse() }
        } catch (error: Exception) {
            Log.w("DZP", "searchRpc: ${error.message}")
            emptyList()
        }
    }

    // ── Detay ────────────────────────────────────────────────────────────────

    override suspend fun load(url: String): LoadResponse? {
        val html  = fetch(url)
        val slots = DiziPalParser.parseSlots(html)

        val title = slots["title"] ?: slots["mjTitle"] ?: Jsoup.parse(html).selectFirst("h1")?.text() ?: return null
        val poster      = fixUrlNull(slots["mjPoster"])
        val description = slots["mjOverview"]
        val year        = slots["mjYear"]?.trim()?.take(4)?.toIntOrNull()
        val tags        = DiziPalParser.parseGenres(html)
        val duration    = Regex("\"min\":(\\d+)").find(DiziPalParser.unescapeRsc(html))?.groupValues?.get(1)?.toIntOrNull()

        if (url.contains("/movies/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.duration  = duration
            }
        }

        val episodes = DiziPalParser.parseSeasons(html).map { episode ->
            newEpisode(fixUrl(episode.href)) {
                this.name     = episode.title
                this.season   = episode.season
                this.episode  = episode.episode
                this.posterUrl = poster
            }
        }

        if (episodes.isEmpty()) Log.w("DZP", "bölüm listesi boş: $url")

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.duration  = duration
        }
    }

    // ── Oynatıcı ─────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html   = fetch(data)
        val player = DiziPalParser.parsePlayer(html)

        if (player == null) {
            Log.w("DZP", "player nesnesi yok: $data")
            return fallbackExtractor(data, "$mainUrl/", subtitleCallback, callback)
        }

        var found   = false
        val sources = player.sources.ifEmpty { listOf(DiziPalSource(null, null, null, null)) }

        for (source in sources) {
            val sourceUrl  = source.href?.let { fixUrl(it) }
            val sourceHtml = if (sourceUrl == null || sourceUrl == data) html
                             else runCatching { fetch(sourceUrl, referer = data) }.getOrNull() ?: html
            val payload    = DiziPalParser.parsePlayer(sourceHtml) ?: player

            val target = DiziPalParser.decryptPlayer(payload)
            if (target == null) {
                Log.w("DZP", "şifre çözülemedi (${source.label ?: "-"}): $data")
                continue
            }

            val label = listOfNotNull(source.quality, source.label ?: payload.language)
                .joinToString(" ")
                .ifBlank { "Dizipal" }

            if (extractFromPlayer(target, label, data, subtitleCallback, callback)) found = true
        }

        return found
    }

    /** Oynatıcı adresini açar: m3u8 → altyazı → (yoksa) extractor katmanı. */
    private suspend fun extractFromPlayer(
        target: String,
        label: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerUrl = if (target.startsWith("//")) "https:$target" else target
        val body = runCatching {
            app.get(playerUrl, headers = getHeaders(referer), referer = referer, timeout = 60_000L).text
        }.getOrNull()

        if (body != null) {
            emitSubtitles(body, playerUrl, subtitleCallback)

            val media = DiziPalParser.mediaUrls(body)
            if (media.isNotEmpty()) {
                media.forEach { url -> emitStream(url, label, playerUrl, callback) }
                return true
            }

            // ? Oynatıcı içinde bir çerçeve daha olabilir (ölçüldü: iframe.php → iç çerçeve)
            val nested = DiziPalParser.iframeUrls(body).firstOrNull()
            if (nested != null) {
                val nestedUrl  = fixUrl(nested)
                val nestedBody = runCatching {
                    app.get(nestedUrl, headers = getHeaders(playerUrl), referer = playerUrl, timeout = 60_000L).text
                }.getOrNull()

                if (nestedBody != null) {
                    emitSubtitles(nestedBody, nestedUrl, subtitleCallback)

                    val nestedMedia = DiziPalParser.mediaUrls(nestedBody) +
                        DiziPalParser.decodedBlobs(nestedBody).flatMap { DiziPalParser.mediaUrls(it) }

                    if (nestedMedia.isNotEmpty()) {
                        nestedMedia.distinct().forEach { url -> emitStream(url, label, nestedUrl, callback) }
                        return true
                    }
                }

                return fallbackExtractor(nestedUrl, playerUrl, subtitleCallback, callback)
            }
        }

        return fallbackExtractor(playerUrl, referer, subtitleCallback, callback)
    }

    private suspend fun emitStream(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url

        if (!fixed.contains(".m3u8")) {
            callback.invoke(buildLink(fixed, label, referer, ExtractorLinkType.VIDEO))
            return
        }

        // * Ana M3U8 ise kalite varyantlarını da çöz (varsa)
        val variants = runCatching {
            M3u8Helper.generateM3u8(
                source    = this.name,
                name      = label,
                streamUrl = fixed,
                referer   = referer,
                headers   = mapOf("Referer" to referer, "User-Agent" to USER_AGENT),
            )
        }.getOrNull()

        if (!variants.isNullOrEmpty()) {
            variants.forEach { callback.invoke(it) }
            return
        }

        callback.invoke(buildLink(fixed, label, referer, ExtractorLinkType.M3U8))
    }

    private fun buildLink(
        url: String,
        label: String,
        referer: String,
        type: ExtractorLinkType
    ): ExtractorLink {
        return newExtractorLink(
            source = this.name,
            name   = "$label · DiziPal",
            url    = url,
            type   = type
        ) {
            this.referer = referer
            this.headers = mapOf("Referer" to referer, "User-Agent" to USER_AGENT)
            this.quality = qualityOf(label)
        }
    }

    private fun emitSubtitles(body: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit) {
        DiziPalParser.subtitleTracks(body).forEach { (lang, url) ->
            subtitleCallback.invoke(SubtitleFile(lang, fixUrl(url, referer)))
        }
    }

    private suspend fun fallbackExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            loadExtractor(url, referer, subtitleCallback, callback)
        } catch (error: Exception) {
            Log.w("DZP", "extractor: ${error.message}")
            false
        }
    }

    private fun qualityOf(name: String?): Int = when {
        name == null -> Qualities.Unknown.value
        name.contains("2160") || name.contains("4K", true) -> Qualities.P2160.value
        name.contains("1080") -> Qualities.P1080.value
        name.contains("720")  -> Qualities.P720.value
        name.contains("480")  -> Qualities.P480.value
        name.contains("360")  -> Qualities.P360.value
        else -> Qualities.Unknown.value
    }

    private suspend fun fetch(url: String, referer: String = "$mainUrl/"): String {
        return app.get(
            url,
            headers     = getHeaders(referer),
            referer     = referer,
            interceptor = interceptor,
            timeout     = 60_000L,
        ).text
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer"         to referer,
            "User-Agent"      to USER_AGENT,
        )
    }
}
