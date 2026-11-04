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
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Element
import java.net.URLEncoder

class DiziPal : MainAPI() {
    // ! Site adresi SÜRÜM NUMARALI ve sürekli değişiyor. 2026-11-04 dump'ında
    // ! canonical = dizipal1594.com ve sitede "Sonraki adresimiz dizipal1595.com"
    // ! duyurusu var. Tek adrese sabitlenince sürüm atladığı anda eklenti tamamen ölüyordu.
    override var mainUrl              = "https://dizipal1594.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    // ! CloudflareKiller WebView kullanıyor; CloudStream'in izin/uyarı akışı için bildirilmeli.
    override val usesWebView          = true

    /** Sürüm atlamalarına karşı aday listesi (yüksekten alçağa + sabit alan adları). */
    private val domainCandidates = listOf(
        "https://dizipal1595.com",
        "https://dizipal1594.com",
        "https://dizipal1596.com",
        "https://dizipal1593.com",
        "https://dizipal.com",
    )
    @Volatile private var domainResolved = false

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

    /**
     * Sürüm numaralı alan adı listesini dolaşıp ayakta olanı `mainUrl`'e sabitler.
     *
     * Ölçüm (2026-11-04): dump'taki canonical `dizipal1594.com`; sitede
     * "Sonraki adresimiz dizipal1595.com" duyurusu var. Sabit tek adres, sürüm
     * atladığı anda eklentiyi tamamen öldürüyordu.
     */
    private suspend fun resolveDomain() {
        if (domainResolved) return

        for (candidate in domainCandidates) {
            try {
                // ! 5 aday × uzun timeout = Keşfet bütçesini yiyor; 8 sn yeterli.
                val res = app.get(candidate, headers = getHeaders("$candidate/"), timeout = 8_000L)
                if (!res.isSuccessful) continue

                // Yönlendirme olmuşsa gerçek kaynağı kullan.
                val pinned = runCatching {
                    val uri = java.net.URI(res.url.toString())
                    val scheme = uri.scheme ?: "https"
                    "$scheme://${uri.host}"
                }.getOrNull() ?: candidate

                if (!looksLikeDizipal(res.text)) continue

                mainUrl        = pinned.trimEnd('/')
                domainResolved = true
                Log.d("DZP", "domain sabitlendi: $mainUrl")
                return
            } catch (e: Exception) {
                Log.d("DZP", "domain adayı olmadı: $candidate (${e.message})")
            }
        }

        Log.w("DZP", "hiçbir domain ayakta değil, varsayılan kaldı: $mainUrl")
    }

    /** Dönen gövdenin gerçekten Dizipal olduğunu doğrular (park/askıya alma sayfalarını eler). */
    private fun looksLikeDizipal(body: String): Boolean {
        if (body.isBlank()) return false
        val lower = body.lowercase()
        val dead = listOf("domain is parked", "buy this domain", "bu alan adı satılık", "hesabı askıya")
        if (dead.any { lower.contains(it) }) return false
        return body.contains("dizipal", ignoreCase = true) ||
            body.contains("/series/", ignoreCase = true) ||
            body.contains("arama-yap", ignoreCase = true)
    }

    /** Eski sürüm adresindeki kayıtlı bağlantıyı çözülen alan adına taşır. */
    private fun migrateUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains("dizipal")) return url
        return url.replaceFirst(Regex("""^https?://[^/]+"""), mainUrl)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()

        // * Sayfalama sunucunun verdiği `pageHref` dizisiyle yürür (tahmin yok).
        var links = pageHrefCache[request.data] ?: emptyList()
        var html  = ""

        if (page > 0 && links.isNotEmpty()) {
            val target = links.getOrNull(page) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
            html = fetch(fixUrl(target))
        } else {
            html  = fetch(migrateUrl(request.data))
            links = DiziPalParser.pageHrefs(html)
            if (links.isNotEmpty()) pageHrefCache[request.data] = links

            if (page > 0) {
                val target = links.getOrNull(page) ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
                html = fetch(fixUrl(target))
            }
        }

        val cards = parseListing(html)

        // ! `pageHref` 2026-11 dump'ında HİÇ yok; o yol kapalıysa ?sayfa=N üzerinden yürü.
        //   Aksi hâlde her satır ilk sayfada kilitli kalıyordu.
        val hasNext = if (links.isNotEmpty()) {
            links.size > page + 1
        } else {
            cards.isNotEmpty() && DiziPalParser.hasNextPage(html, page + 1)
        }

        return newHomePageResponse(request.name, cards, hasNext = hasNext)
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

        // ! Arama sonuçlarında puan (`r`) ve yıl da taşınıyor; önceden atılıyordu.
        val score = score?.replace(',', '.')?.toDoubleOrNull()?.let { Score.from10(it) }

        return if (isMovie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = fixUrlNull(poster)
                this.year      = this@toSearchResponse.year
                this.score     = score
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(poster)
                this.year      = this@toSearchResponse.year
                this.score     = score
            }
        }
    }

    // ── Arama ────────────────────────────────────────────────────────────────

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()

        val url  = "${mainUrl}/arama-yap?s=${URLEncoder.encode(query, "UTF-8")}"
        val html = fetch(url)

        // ! Arama sayfası RSC'de `results.dizi / results.film / results.oyuncu` adlı
        // ! ÜÇ ayrı dizi taşıyor; `parseCards` kalıbı bu alan sırasını yakalayamıyor.
        // ! Önce yapısal ayrıştırıcı, sonra DOM kartları, en son genel kalıp.
        val structured = DiziPalParser.parseSearchResults(html).map { it.toSearchResponse() }
        if (structured.isNotEmpty()) return structured

        val hits = parseListing(html)
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
                requestBody = """{"action":"searchContent","params":["${query.replace("\"", "\\\"")}"]}"""
                    .toRequestBody("application/json".toMediaType()),
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
        resolveDomain()

        val html  = fetch(migrateUrl(url))
        val slots = DiziPalParser.parseSlots(html)

        val title = slots["title"] ?: slots["mjTitle"] ?: Jsoup.parse(html).selectFirst("h1")?.text() ?: return null
        val poster      = fixUrlNull(slots["mjPoster"])
        val description = slots["mjOverview"]
        val year        = slots["mjYear"]?.trim()?.take(4)?.toIntOrNull()
        val tags        = DiziPalParser.parseGenres(html)
        val plain       = DiziPalParser.unescapeRsc(html)
        val duration    = Regex("\"min\":(\\d+)").find(plain)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("\"runtimeMin\":(\\d+)").find(plain)?.groupValues?.get(1)?.toIntOrNull()
        val trailer     = Regex("\"trailerRaw\":\"([A-Za-z0-9_-]{6,})\"").find(plain)?.groupValues?.get(1)

        val canonical = migrateUrl(url)

        if (canonical.contains("/movies/")) {
            return newMovieLoadResponse(title, canonical, TvType.Movie, canonical) {
                this.posterUrl = poster
                this.plot      = description
                this.year      = year
                this.tags      = tags
                this.duration  = duration
                if (trailer != null) addTrailer("https://www.youtube.com/watch?v=$trailer")
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

        if (episodes.isEmpty()) Log.w("DZP", "bölüm listesi boş: $canonical")

        return newTvSeriesLoadResponse(title, canonical, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.duration  = duration
            if (trailer != null) addTrailer("https://www.youtube.com/watch?v=$trailer")
        }
    }

    // ── Oynatıcı ─────────────────────────────────────────────────────────────

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()

        val html   = fetch(migrateUrl(data))
        val player = DiziPalParser.parsePlayer(html)

        if (player == null) {
            Log.w("DZP", "player nesnesi yok: $data")
            return fallbackExtractor(migrateUrl(data), "$mainUrl/", subtitleCallback, callback)
        }

        val sources = player.sources.ifEmpty { listOf(DiziPalSource(null, null, null, null)) }

        // ! Eskiden kaynaklar TEK TEK (sıralı) çekiliyordu: 2 kaynak × 2 istek × 60 sn
        // ! = 240 sn, CloudStream'in 120 sn'lik loadLinks bütçesini aşıyordu. Artık
        // ! paralel; her kaynak kendi zaman kutusunda (SOURCE_TIMEOUT_MS).
        // ! `Mutex.withLock` SUSPEND'dir; ama CloudStream'in `loadExtractor`'ı suspend
        // ! olmayan `(SubtitleFile) -> Unit` / `(ExtractorLink) -> Unit` bekliyor.
        // ! Bu yüzden suspend kilit kullanılamaz (derleme hatası: "Suspension functions
        // ! can only be called within coroutine body"). Amaç zaten karşılıklı dışlama
        // ! değil, PARALEL kaynaklardan gelen AYNI linkin iki kez iletilmemesiydi.
        // ! `newKeySet()` bunu kilitsiz ve atomik yapar.
        val emittedLinks     = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val emittedSubtitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val found = java.util.concurrent.atomic.AtomicBoolean(false)

        // ! `@Volatile` yerel değişkende derlenmez; bayrak atomik tutuluyor.
        val relay: (ExtractorLink) -> Unit = { link ->
            found.set(true)
            callback(link)
        }

        coroutineScope {
            sources.map { source ->
                async {
                    try {
                        fetchOneSource(source, html, migrateUrl(data), subtitleCallback, relay, emittedLinks, emittedSubtitles)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w("DZP", "kaynak hatası (${source.label ?: "-"}): ${e.message}")
                    }
                }
            }.awaitAll()
        }

        if (!found.get()) Log.w("DZP", "hiçbir kaynak çözülemedi: $data")
        return found.get()
    }

    /**
     * Tek bir `sources[]` girdisini çözer.
     *
     * Altyazı/dublaj bilgisi artık serbest metin etiketin içinde kaybolmuyor:
     * `dub` bayrağı kaynak adının **başında** yapısal olarak yer alır
     * (`Dublaj · 1080P`, `Altyazı · 1080P`) ve altyazı kaynağı için ayrıca
     * `SubtitleFile` üretilir.
     */
    private suspend fun fetchOneSource(
        source: DiziPalSource,
        rootHtml: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        emittedLinks: MutableSet<String>,
        emittedSubtitles: MutableSet<String>,
    ) {
        val sourceUrl = source.href?.let { fixUrl(migrateUrl(it)) }

        val sourceHtml = withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            if (sourceUrl == null || sourceUrl == pageUrl) rootHtml
            else runCatching { fetch(sourceUrl, referer = pageUrl) }.getOrNull() ?: rootHtml
        } ?: rootHtml

        val payload = DiziPalParser.parsePlayer(sourceHtml) ?: return

        // ! `?: run { ...; return }` val başlatıcısında tip çıkarımını bozar;
        //   ayrı ifadeye alındı.
        val target = DiziPalParser.decryptPlayer(payload)
        if (target == null) {
            Log.w("DZP", "şifre çözülemedi (${source.label ?: "-"}): $pageUrl")
            return
        }

        val label = DiziPalParser.sourceLabel(source, payload.language)

        // ? Dublaj/altyazı bayrağı: `?lang=dublaj` / `?lang=altyazi` bağlantıları da yakalanır.
        val declaredDub = source.dub ?: payload.dub
        val langHint    = DiziPalParser.languageHint(source.href, source.label, payload.language)
        val isDub       = declaredDub == true || langHint == "dublaj"

        // * Altyazı kaynağıysa oynatıcı sayfasından altyazı izlerini ayrıca topla.
        // ! Kilitsiz dedupe: aynı altyazı/link farklı kaynaklardan gelebiliyor.
        val perSourceSubtitle: (SubtitleFile) -> Unit = { file ->
            val lang = if (isDub) "dub" else "sub"
            if (emittedSubtitles.add(file.url)) {
                subtitleCallback(SubtitleFile(file.lang ?: lang, file.url))
            }
        }

        val linkRelay: (ExtractorLink) -> Unit = { link ->
            if (emittedLinks.add(link.url)) callback(link)
        }

        withTimeoutOrNull(SOURCE_TIMEOUT_MS) {
            extractFromPlayer(target, label, pageUrl, perSourceSubtitle, linkRelay)
        }
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
            app.get(playerUrl, headers = getHeaders(referer), referer = referer, interceptor = interceptor, timeout = 60_000L).text
        }.getOrNull()

        if (body != null) {
            emitSubtitles(body, subtitleCallback)

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
                    app.get(nestedUrl, headers = getHeaders(playerUrl), referer = playerUrl, interceptor = interceptor, timeout = 60_000L).text
                }.getOrNull()

                if (nestedBody != null) {
                    emitSubtitles(nestedBody, subtitleCallback)

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

    private suspend fun buildLink(
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

    private fun emitSubtitles(body: String, subtitleCallback: (SubtitleFile) -> Unit) {
        DiziPalParser.subtitleTracks(body).forEach { (lang, url) ->
            subtitleCallback.invoke(SubtitleFile(lang, fixUrl(url)))
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

    private suspend fun fetch(
        url: String,
        referer: String = "$mainUrl/",
        // ! 60 sn'lik tek istek tüm loadLinks bütçesini yiyordu; çağıran kısaltabilir.
        timeout: Long = 60_000L,
    ): String {
        return app.get(
            url,
            headers     = getHeaders(referer),
            referer     = referer,
            interceptor = interceptor,
            timeout     = timeout,
        ).text
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        /** Kaynak başına zaman kutusu; paralel çalıştığı için toplam bütçeyi şişirmez. */
        private const val SOURCE_TIMEOUT_MS = 25_000L

        private fun getHeaders(referer: String): Map<String, String> = mapOf(
            "Accept"          to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
            "Referer"         to referer,
            "User-Agent"      to USER_AGENT,
        )
    }
}
