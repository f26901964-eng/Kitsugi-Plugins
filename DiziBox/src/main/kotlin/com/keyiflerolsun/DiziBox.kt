// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    /**
     * CloudflareKiller doğrudan kullanılır:
     * 1) Daha önce çözülmüş `cf_clearance` çerezi ve `UNIFIED_USER_AGENT` ilk istekten
     *    itibaren eklenir (önce çerezsiz 403 alıp ikinci kez istek atma hatası önlenir).
     * 2) `dizibox.live` isteklerinde özel masaüstü `User-Agent` başlığı ASLA gönderilmez;
     *    aksi halde `cf_clearance` çerezi UA uyuşmazlığından 403 alır ve doğrulama penceresi
     *    her ekranda yeniden açılır.
     */
    private val cloudflareKiller by lazy { CloudflareKiller() }

    /**
     * Depodaki diğer eklentilerin (DiziPal, Dizilla, DiziMom, CinemaCity...) kullandığı
     * sarmalayıcı: killer YALNIZCA gövde gerçekten bir Cloudflare basamağıysa çağrılır.
     *
     * DiziBox önceden killer'ı doğrudan `interceptor` olarak veriyordu. Sayfa/arama
     * istekleri `coroutineScope` içinde paralel atıldığı için aynı killer örneği eşzamanlı
     * WebView doğrulamasına giriyor; bu, doğrulamayı bekleyen isteklerin asılı kalmasına
     * (uygulamada "eklenti hiç çalışmıyor" görüntüsü) yol açabiliyordu. Artık killer
     * yalnızca basamak görüldüğünde ve sırayla çalışır.
     */
    private val cloudflareInterceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        private val lock = Any()

        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = runCatching { response.peekBody(1024L * 512L).string() }.getOrNull()

            val challenged = response.code == 403 || response.code == 503 ||
                (body != null && CHALLENGE_MARKERS.any { body.contains(it, ignoreCase = true) })

            if (!challenged) return response

            // * Aynı anda tek doğrulama: paralel istekler WebView'i birbirine kilitlemesin.
            return synchronized(lock) { cloudflareKiller.intercept(chain) }
        }

        private companion object {
            val CHALLENGE_MARKERS = listOf(
                "Just a moment",
                "challenge-platform",
                "cf-browser-verification",
                "cf_chl_opt",
                "Enable JavaScript and cookies to continue",
            )
        }
    }

    /**
     * Sitenin satır içi betiği (`LockUser=true`, `isTrustedUser=true`) ve `jquery.main.min.js`
     * içindeki 30 dakikalık `dbxu = Date.now()` çerez sözleşmesiyle uyumlu taze çerez kümesi.
     */
    private fun diziboxCookies(): Map<String, String> = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to System.currentTimeMillis().toString(),
    )

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/SAYFA/"              to "Son Bölümler",
        "${mainUrl}/tum-bolumler/page/SAYFA/?tip=populer"  to "Popüler Bölümler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"               to "Dizi Arşivi",
        "${mainUrl}/efsane-diziler/page/SAYFA/"            to "Efsane Diziler",
        "${mainUrl}/ulke/turkiye/page/SAYFA/"              to "Yerli Diziler",
        "${mainUrl}/tur/aksiyon/page/SAYFA/"               to "Aksiyon",
        "${mainUrl}/tur/animasyon/page/SAYFA/"             to "Animasyon",
        "${mainUrl}/tur/belgesel/page/SAYFA/"              to "Belgesel",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA/"            to "Bilimkurgu",
        "${mainUrl}/tur/biyografi/page/SAYFA/"             to "Biyografi",
        "${mainUrl}/tur/dram/page/SAYFA/"                  to "Dram",
        "${mainUrl}/tur/drama/page/SAYFA/"                 to "Drama",
        "${mainUrl}/tur/fantastik/page/SAYFA/"             to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA/"               to "Gerilim",
        "${mainUrl}/tur/gizem/page/SAYFA/"                 to "Gizem",
        "${mainUrl}/tur/komedi/page/SAYFA/"                to "Komedi",
        "${mainUrl}/tur/korku/page/SAYFA/"                 to "Korku",
        "${mainUrl}/tur/macera/page/SAYFA/"                to "Macera",
        "${mainUrl}/tur/aile/page/SAYFA/"                  to "Aile",
        "${mainUrl}/tur/muzik/page/SAYFA/"                 to "Müzik",
        "${mainUrl}/tur/muzikal/page/SAYFA/"               to "Müzikal",
        "${mainUrl}/tur/reality-tv/page/SAYFA/"            to "Reality TV",
        "${mainUrl}/tur/romantik/page/SAYFA/"              to "Romantik",
        "${mainUrl}/tur/savas/page/SAYFA/"                 to "Savaş",
        "${mainUrl}/tur/spor/page/SAYFA/"                  to "Spor",
        "${mainUrl}/tur/suc/page/SAYFA/"                   to "Suç",
        "${mainUrl}/tur/tarih/page/SAYFA/"                 to "Tarih",
        "${mainUrl}/tur/western/page/SAYFA/"               to "Western",
        "${mainUrl}/tur/yarisma/page/SAYFA/"               to "Yarışma",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // * `sequentialMainPage` açık: CloudStream 0'dan, WordPress 1'den sayar.
        //   Önceden `page` doğrudan verildiği için 2. parça 1. sayfayı TEKRARLIYORDU.
        val apiPage  = DiziBoxPlayerParser.sequentialPageIndex(page)
        val url      = DiziBoxPlayerParser.buildMainPageUrl(request.data, apiPage)
        val document = fetchDiziBoxDocument(url) // ! önbellek yok: 403 basamağı yapışmasın

        val cards = DiziBoxPlayerParser.parseListingCards(document, url)
            .map { it.toSearchResult() }
        val isHorizontal = request.name.contains("Bölüm", ignoreCase = true)
        val hasNext = cards.isNotEmpty() && DiziBoxPlayerParser.hasNextPage(document, apiPage)

        return newHomePageResponse(
            HomePageList(request.name, cards, isHorizontalImages = isHorizontal),
            hasNext = hasNext,
        )
    }

    private fun DiziBoxPlayerParser.ListingCard.toSearchResult(): SearchResponse =
        newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(this@toSearchResult.posterUrl)
            this.score     = Score.from10(this@toSearchResult.imdbScore)
            this.year      = this@toSearchResult.year
        }

    // ────────── Arama ──────────

    override suspend fun search(query: String): List<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 1) Canlı tema XHR araması (DavesWordPressLiveSearchConfig: minCharsToSearch = 3)
        if (trimmed.length >= 3) {
            ajaxSearch(trimmed)?.let { if (it.isNotEmpty()) return it }
        }

        val encoded = URLEncoder.encode(trimmed, "UTF-8")

        // 2) /search/{sorgu}/ sayfası (üstte diziler, altta bölümler; dizi kartları önceliklidir)
        val searchPathUrl = "${mainUrl}/search/${encoded}/"
        val slugHits = runCatching {
            val doc = fetchDiziBoxDocument(searchPathUrl)
            DiziBoxPlayerParser.parseSearchCards(doc, searchPathUrl).map { it.toSearchResult() }
        }.getOrNull()
        if (!slugHits.isNullOrEmpty()) return slugHits

        // 3) Klasik WordPress /?s={sorgu} yedek araması
        val queryUrl = "${mainUrl}/?s=${encoded}"
        val fallbackDoc = runCatching { fetchDiziBoxDocument(queryUrl) }.getOrNull() ?: return emptyList()
        return DiziBoxPlayerParser.parseSearchCards(fallbackDoc, queryUrl).map { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private suspend fun ajaxSearch(query: String): List<SearchResponse>? {
        val ajaxUrl = "${mainUrl}/wp-admin/admin-ajax.php"
        // DİKKAT: Burada özel User-Agent gönderilmez; CloudflareKiller'ın kendi UA'sı korunur.
        val xhrHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
        )
        val encoded = URLEncoder.encode(query, "UTF-8")

        val getBody = runCatching {
            app.get(
                "$ajaxUrl?action=dwls_search&s=$encoded",
                headers     = xhrHeaders,
                cookies     = diziboxCookies(),
                referer     = "$mainUrl/",
                interceptor = cloudflareKiller,
            ).text
        }.getOrNull()
        val getResults = getBody?.let { DiziBoxPlayerParser.dwlsSearchResults(it) }.orEmpty()
        if (getResults.isNotEmpty()) return toDwlsResponses(getResults)

        val postBody = runCatching {
            app.post(
                ajaxUrl,
                data        = mapOf("action" to "dwls_search", "s" to query),
                headers     = xhrHeaders,
                cookies     = diziboxCookies(),
                referer     = "$mainUrl/",
                interceptor = cloudflareKiller,
            ).text
        }.getOrNull()
        val postResults = postBody?.let { DiziBoxPlayerParser.dwlsSearchResults(it) }.orEmpty()
        return if (postResults.isNotEmpty()) toDwlsResponses(postResults) else null
    }

    private fun toDwlsResponses(results: List<DiziBoxPlayerParser.DwlsResult>): List<SearchResponse> =
        results.mapNotNull { result ->
            val resolvedUrl = fixUrlNull(result.url) ?: return@mapNotNull null
            newTvSeriesSearchResponse(result.title, resolvedUrl, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(DiziBoxPlayerParser.upscaleThumbnail(result.thumbnail))
            }
        }.distinctBy { it.url }

    // ────────── Dizi & Bölüm Detayı ──────────

    override suspend fun load(url: String): LoadResponse? {
        val initialDoc = fetchDiziBoxDocument(url)

        // Ana sayfadaki "Son Bölümler" kartları doğrudan bölüm sayfasına gider;
        // her bölüm sayfasındaki `#archive-box a.archive-title` ana dizi sayfasına yönlendirir.
        val archiveTarget = DiziBoxPlayerParser.archiveBoxLink(initialDoc, url)
        val (effectiveUrl, document) = if (
            initialDoc.selectFirst("div.tv-overview") == null &&
            !archiveTarget.isNullOrBlank() &&
            archiveTarget != url
        ) {
            val seriesDoc = runCatching { fetchDiziBoxDocument(archiveTarget, referer = url) }.getOrNull()
            if (seriesDoc != null) archiveTarget to seriesDoc else url to initialDoc
        } else {
            url to initialDoc
        }

        val detail = DiziBoxPlayerParser.parseSeriesDetail(document, effectiveUrl)
            ?: DiziBoxPlayerParser.parseEpisodePageFallback(document, effectiveUrl)
            ?: return null

        val episodeMap = LinkedHashMap<String, DiziBoxPlayerParser.ParsedEpisode>()
        detail.activeEpisodes.forEach { episodeMap.putIfAbsent(it.url, it) }

        // Aktif sezon zaten ana dizi sayfasında yüklüdür (`a.hover`); yalnızca diğer sezonlar çekilir.
        val seasonsToFetch = if (episodeMap.isNotEmpty()) {
            detail.inactiveSeasonUrls
        } else {
            detail.allSeasonUrls
        }

        seasonsToFetch.forEach { seasonUrl ->
            if (seasonUrl == effectiveUrl) return@forEach
            val seasonDoc = runCatching {
                fetchDiziBoxDocument(seasonUrl, referer = detail.canonicalUrl)
            }.getOrNull() ?: return@forEach

            DiziBoxPlayerParser.parseSeasonEpisodes(seasonDoc, seasonUrl, detail.posterUrl)
                .forEach { ep -> episodeMap.putIfAbsent(ep.url, ep) }
        }

        // Grid-box bulunmayan eski temalar için slug kalıbı yedeği
        if (episodeMap.isEmpty()) {
            DiziBoxPlayerParser.episodeAnchors(document, detail.canonicalUrl).forEach { anchor ->
                episodeMap.putIfAbsent(
                    anchor.url,
                    DiziBoxPlayerParser.ParsedEpisode(
                        url       = anchor.url,
                        name      = "${anchor.season}.Sezon ${anchor.episode}.Bölüm",
                        season    = anchor.season,
                        episode   = anchor.episode,
                        posterUrl = detail.posterUrl,
                    ),
                )
            }
        }

        val episodes = episodeMap.values
            .sortedWith(compareBy({ it.season }, { it.episode ?: Int.MAX_VALUE }))
            .map { ep ->
                newEpisode(ep.url) {
                    this.name      = ep.name
                    this.season    = ep.season
                    this.episode   = ep.episode
                    this.posterUrl = fixUrlNull(ep.posterUrl ?: detail.posterUrl)
                    this.date      = ep.dateMillis
                }
            }

        return newTvSeriesLoadResponse(detail.title, detail.canonicalUrl, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrlNull(detail.posterUrl)
            this.plot      = detail.plot
            this.year      = detail.year
            this.tags      = detail.tags
            this.score     = Score.from10(detail.imdbScore)
            if (detail.actors.isNotEmpty()) addActors(detail.actors.map { Actor(it) })
            if (!detail.trailerUrl.isNullOrBlank()) addTrailer(detail.trailerUrl)
        }
    }

    // ────────── Video Kaynakları & Oynatıcı Çözümleme ──────────

    private data class StreamKey(
        val url: String,
        val type: ExtractorLinkType,
        val quality: Int,
        val referer: String,
    )

    private class LinkCollector(
        private val subtitleCallback: (SubtitleFile) -> Unit,
        private val linkCallback: (ExtractorLink) -> Unit,
    ) {
        private val mutex = Mutex()
        private val seenLinks = HashSet<StreamKey>()
        private val seenSubtitles = Collections.synchronizedSet(HashSet<String>())

        @Volatile
        var count: Int = 0
            private set

        suspend fun emitLink(link: ExtractorLink) {
            val key = StreamKey(link.url, link.type, link.quality, link.referer)
            mutex.withLock {
                if (seenLinks.add(key)) {
                    count += 1
                    linkCallback.invoke(link)
                }
            }
        }

        suspend fun emitSubtitle(lang: String, url: String, referer: String? = null) {
            if (!seenSubtitles.add(url)) return
            val sub = newSubtitleFile(lang.ifBlank { "Altyazı" }, url) {
                if (!referer.isNullOrBlank()) {
                    this.headers = buildStreamHeaders(referer)
                }
            }
            subtitleCallback.invoke(sub)
        }

        fun rawSubtitleCallback(): (SubtitleFile) -> Unit = { sub ->
            if (seenSubtitles.add(sub.url)) {
                subtitleCallback.invoke(sub)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(TAG, "loadLinks » $data")
        val mainDoc = try {
            fetchDiziBoxDocument(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bölüm sayfası alınamadı: ${e.message}")
            return false
        }

        val tabs = DiziBoxPlayerParser.playerTabs(mainDoc, data).take(10)
        val collector = LinkCollector(subtitleCallback, callback)
        val tabSemaphore = Semaphore(3)

        coroutineScope {
            tabs.map { tab ->
                async {
                    tabSemaphore.withPermit {
                        withTimeoutOrNull(TAB_TIMEOUT_MS) {
                            processPlayerTab(tab, data, mainDoc, collector)
                        }
                    }
                }
            }.awaitAll()
        }

        Log.d(TAG, "Toplam yayınlanan akış: ${collector.count}")
        return collector.count > 0
    }

    private suspend fun processPlayerTab(
        tab: DiziBoxPlayerParser.PlayerTab,
        rootEpisodeUrl: String,
        rootDocument: Document,
        collector: LinkCollector,
    ) {
        val pageDocument = if (tab.url == rootEpisodeUrl) {
            rootDocument
        } else {
            try {
                fetchDiziBoxDocument(tab.url, referer = rootEpisodeUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Alternatif sayfa alınamadı (${tab.label}): ${e.message}")
                return
            }
        }

        val iframes = DiziBoxPlayerParser.playerIframes(pageDocument, tab.url)
        val options = DiziBoxPlayerParser.playerOptionUrls(pageDocument, tab.url)

        if (iframes.isEmpty() && options.isEmpty()) {
            if (DiziBoxPlayerParser.isGeoBlockedVideoArea(pageDocument)) {
                Log.d(TAG, "${tab.label} Türkiye dışı IP kısıtlaması nedeniyle atlandı")
            }
            return
        }

        val visited = HashSet<String>()
        (iframes + options).distinct().forEach { candidateUrl ->
            try {
                resolvePlayerChain(
                    rawUrl    = candidateUrl,
                    referer   = tab.url,
                    tabLabel  = tab.label,
                    collector = collector,
                    visited   = visited,
                    depth     = 0,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "Oynatıcı hatası (${tab.label}): ${e.message}")
            }
        }
    }

    private suspend fun resolvePlayerChain(
        rawUrl: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
        visited: MutableSet<String>,
        depth: Int,
    ): Boolean {
        if (depth > 4) return false
        val resolved = DiziBoxPlayerParser.resolveUrl(rawUrl, referer) ?: return false
        val normalized = DiziBoxPlayerParser.normalizeEmbedUrl(resolved)
        if (!visited.add(normalized)) return false

        // 1) DiziBox PHP sarmalayıcıları (`king.php`, `moly.php`, `haydi.php`, `spidy.php`, `player.php`)
        if (isDiziBoxHost(normalized) && DiziBoxPlayerParser.isSupportedPlayerUrl(normalized, mainUrl)) {
            return resolveDiziBoxWrapper(normalized, referer, tabLabel, collector, visited, depth)
        }

        // 2) MolyStream (`dbx.molystream.org` / `www.molystream.org` / `molystream.net` / `ydx.molystream.org`)
        if (DiziBoxPlayerParser.isMolyStreamHost(normalized)) {
            return resolveMolyStream(normalized, referer, tabLabel, collector, visited, depth)
        }

        // 3) Vidmoly (`vidmoly.*`, `moly.to` — `Moly+` ve `İngilizce` oynatıcıları)
        if (DiziBoxPlayerParser.isVidmolyHost(normalized)) {
            return resolveVidmoly(normalized, referer, tabLabel, collector)
        }

        // 4) Odnoklassniki / OK.ru (`Odnok` oynatıcısı)
        if (DiziBoxPlayerParser.isOkRuHost(normalized)) {
            return resolveOkRu(normalized, referer, tabLabel, collector)
        }

        // 5) Sibnet (`video.sibnet.ru`)
        if (DiziBoxPlayerParser.isSibnetHost(normalized)) {
            if (resolveSibnet(normalized, tabLabel, collector)) return true
        }

        // 6) Mail.ru (`my.mail.ru`)
        if (DiziBoxPlayerParser.isMailRuHost(normalized)) {
            if (resolveMailRu(normalized, referer, tabLabel, collector)) return true
        }

        // 7) VK Video (`vk.com` / `vkvideo.ru`)
        if (DiziBoxPlayerParser.isVkHost(normalized)) {
            if (resolveVk(normalized, referer, tabLabel, collector)) return true
        }

        // 8) Yerleşik CloudStream extractor'ları + genel gömülü oynatıcı yedeği
        if (invokeBuiltInExtractor(normalized, referer, tabLabel, collector)) return true

        return resolveGenericEmbed(normalized, referer, tabLabel, collector, visited, depth)
    }

    /**
     * DiziBox'ın kendi `/player/.../{sarmalayici}.php` uç noktaları:
     * `jquery.main.min.js` kuralıyla `wmode=opaque` sorgu başına eklenir,
     * `diziboxCookies()` + `cloudflareKiller` ile çekilir (özel UA gönderilmez!).
     */
    private suspend fun resolveDiziBoxWrapper(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
        visited: MutableSet<String>,
        depth: Int,
    ): Boolean {
        val wrapperUrl = DiziBoxPlayerParser.withWOpaque(url)
        val rawHtml = app.get(
            wrapperUrl,
            referer     = referer,
            cookies     = diziboxCookies(),
            interceptor = cloudflareKiller,
        ).text

        val decodedHtml = DiziBoxPlayerParser.decodeWrapperHtml(rawHtml)
        val doc = Jsoup.parse(decodedHtml, wrapperUrl)
        var extracted = false

        emitParsedSubtitles(decodedHtml, wrapperUrl, collector)
        if (emitParsedMediaSources(decodedHtml, wrapperUrl, tabLabel, collector)) {
            extracted = true
        }

        for (nested in DiziBoxPlayerParser.nestedIframes(doc, wrapperUrl)) {
            if (nested == wrapperUrl) continue
            if (resolvePlayerChain(nested, wrapperUrl, tabLabel, collector, visited, depth + 1)) {
                extracted = true
            }
        }
        return extracted
    }

    /**
     * MolyStream çözümleyicisi (`DBX Pro`):
     *  A) `/embed/{id}` -> `/embed/sheila/{id}` master M3U8 uç noktası (`Referer = /embed/{id}` ile!).
     *     Üretilen tüm varyantlarda `Referer` olarak `sheila` DEĞİL, gerçek iframe belgesi olan
     *     `/embed/{id}` korunur (`db5.*.xyz` segment CDN'i bu Referer'ı doğrular).
     *  B) `sheila` yanıt vermezse `/embed/{id}` HTML gövdesindeki `CryptoJS.AES.decrypt` çözülür.
     */
    private suspend fun resolveMolyStream(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
        visited: MutableSet<String>,
        depth: Int,
    ): Boolean {
        val embedPageUrl = DiziBoxPlayerParser.molyStreamPlayerPageUrl(url) ?: url
        val sheilaUrl = if (DiziBoxPlayerParser.isMolyStreamStreamUrl(url)) {
            url
        } else {
            DiziBoxPlayerParser.molyStreamSheilaUrl(embedPageUrl)
        }

        // Aşama A: doğrudan sheila master M3U8 uç noktası (+ www.molystream.org ayna yedeği)
        if (sheilaUrl != null) {
            val sheilaCandidates = listOfNotNull(
                sheilaUrl to embedPageUrl,
                DiziBoxPlayerParser.molyStreamMirrorUrl(sheilaUrl)?.let { mirror ->
                    mirror to (DiziBoxPlayerParser.molyStreamMirrorUrl(embedPageUrl) ?: embedPageUrl)
                },
            )
            for ((candidateSheila, candidateEmbedRef) in sheilaCandidates) {
                val verified = fetchVerifiedMolyPlaylist(candidateSheila, candidateEmbedRef)
                if (verified != null) {
                    emitHlsPlaylist(
                        body        = verified.body,
                        playlistUrl = verified.url,
                        streamRef   = candidateEmbedRef,
                        tabLabel    = tabLabel,
                        collector   = collector,
                    )
                    return true
                }
            }
        }

        // Aşama B: /embed/{id} oynatıcı sayfası (CryptoJS AES / JWPlayer kurulumu)
        val embedCandidates = listOfNotNull(
            embedPageUrl,
            DiziBoxPlayerParser.molyStreamMirrorUrl(embedPageUrl),
        ).distinct()

        for (candidateEmbed in embedCandidates) {
            for (parentRef in listOf(referer, "$mainUrl/").distinct()) {
                val rawHtml = runCatching {
                    app.get(
                        candidateEmbed,
                        headers     = buildStreamHeaders(parentRef),
                        referer     = parentRef,
                        interceptor = cloudflareKiller,
                    ).text
                }.getOrNull() ?: continue

                if (DiziBoxPlayerParser.isHlsManifest(rawHtml)) {
                    emitHlsPlaylist(rawHtml, candidateEmbed, candidateEmbed, tabLabel, collector)
                    return true
                }

                val decoded = DiziBoxPlayerParser.decodeWrapperHtml(rawHtml)
                emitParsedSubtitles(decoded, candidateEmbed, collector)
                if (emitParsedMediaSources(decoded, candidateEmbed, tabLabel, collector)) {
                    return true
                }

                val doc = Jsoup.parse(decoded, candidateEmbed)
                var nestedFound = false
                for (nested in DiziBoxPlayerParser.nestedIframes(doc, candidateEmbed)) {
                    if (nested == candidateEmbed) continue
                    if (resolvePlayerChain(nested, candidateEmbed, tabLabel, collector, visited, depth + 1)) {
                        nestedFound = true
                    }
                }
                if (nestedFound) return true
            }
        }

        return false
    }

    private data class VerifiedPlaylist(val url: String, val body: String)

    private suspend fun fetchVerifiedMolyPlaylist(url: String, embedReferer: String): VerifiedPlaylist? {
        return try {
            val body = app.get(
                url,
                headers     = buildStreamHeaders(embedReferer),
                referer     = embedReferer,
                interceptor = cloudflareKiller,
            ).text
            when {
                DiziBoxPlayerParser.isHlsManifest(body) -> VerifiedPlaylist(url, body)
                else -> {
                    val hopTarget = DiziBoxPlayerParser.plainHttpsTarget(body) ?: return null
                    val hopBody = app.get(
                        hopTarget,
                        headers     = buildStreamHeaders(embedReferer),
                        referer     = embedReferer,
                        interceptor = cloudflareKiller,
                    ).text
                    hopBody.takeIf { DiziBoxPlayerParser.isHlsManifest(it) }
                        ?.let { VerifiedPlaylist(hopTarget, it) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "MolyStream sheila doğrulaması başarısız: ${e.message}")
            null
        }
    }

    /**
     * Vidmoly (`Moly+` ve `İngilizce`): canlı yönlendirme sonrasındaki gerçek ayna adresini
     * (`vidmoly.biz`, `ww*.vidmoly.to`, `vidmoly.net` vb.) `Referer` olarak korur ve JWPlayer
     * altyazı izlerini (`English`, `Türkçe`) eksiksiz aktarır.
     */
    private suspend fun resolveVidmoly(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val headers = mapOf(
            "user-agent"     to STREAM_USER_AGENT,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
        )
        val resp = runCatching {
            app.get(url, headers = headers, referer = referer)
        }.getOrNull()

        if (resp != null) {
            val effectiveEmbedUrl = resp.url.ifBlank { url }
            val decoded = DiziBoxPlayerParser.decodeWrapperHtml(resp.text)
            emitParsedSubtitles(decoded, effectiveEmbedUrl, collector)
            if (emitParsedMediaSources(decoded, effectiveEmbedUrl, tabLabel, collector, expandM3u8 = true)) {
                return true
            }
        }

        return invokeBuiltInExtractor(url, referer, tabLabel, collector)
    }

    /**
     * OK.ru (`Odnok`):
     *  1) `GET https://ok.ru/videoembed/<id>` içindeki `data-options` JSON'unu okur.
     *  2) Boşsa `POST https://ok.ru/dk?cmd=videoPlayerMetadata&mid=<id>` ucunu dener
     *     (GET isteği OK.ru'da giriş sayfasına yönlendiği için POST zorunludur).
     *  Hem HLS master hem de MP4 çözünürlüklerini (`1080p`..`144p`) Jackson kullanmadan yayar.
     */
    private suspend fun resolveOkRu(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val videoId = DiziBoxPlayerParser.okRuVideoId(url)
        val embedUrl = if (videoId != null) "https://ok.ru/videoembed/$videoId" else url
        val okHeaders = mapOf(
            "Accept"     to "*/*",
            "Origin"     to "https://ok.ru",
            "user-agent" to STREAM_USER_AGENT,
        )

        var streams = runCatching {
            val html = app.get(embedUrl, headers = okHeaders, referer = referer).text
            DiziBoxPlayerParser.parseOkRuStreams(html)
        }.getOrDefault(emptyList())

        if (streams.isEmpty() && videoId != null) {
            streams = runCatching {
                val metaJson = app.post(
                    "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                    headers = okHeaders,
                    referer = embedUrl,
                ).text
                DiziBoxPlayerParser.parseOkRuStreams(metaJson)
            }.getOrDefault(emptyList())
        }

        if (streams.isNotEmpty()) {
            val playbackHeaders = mapOf(
                "Referer"    to "https://ok.ru/",
                "Origin"     to "https://ok.ru",
                "user-agent" to STREAM_USER_AGENT,
            )
            for (stream in streams) {
                val type = if (stream.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                val quality = getQualityFromName(stream.qualityLabel)
                val label = formatStreamLabel(tabLabel, stream.qualityLabel)
                collector.emitLink(
                    newExtractorLink(
                        source = name,
                        name   = label,
                        url    = stream.url,
                        type   = type,
                    ) {
                        this.referer = "https://ok.ru/"
                        this.headers = playbackHeaders
                        this.quality = quality
                    },
                )
            }
            return true
        }

        return invokeBuiltInExtractor(embedUrl, referer, tabLabel, collector)
    }

    /** Sibnet (`video.sibnet.ru/shell.php?videoid=<id>`). */
    private suspend fun resolveSibnet(
        url: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val shellUrl = DiziBoxPlayerParser.sibnetShellUrl(url) ?: return false
        val body = runCatching {
            app.get(
                shellUrl,
                headers = mapOf("user-agent" to STREAM_USER_AGENT),
                referer = "https://video.sibnet.ru/",
            ).text
        }.getOrNull() ?: return false

        val mediaUrl = DiziBoxPlayerParser.parseSibnetUrl(body, shellUrl) ?: return false
        val type = if (mediaUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        collector.emitLink(
            newExtractorLink(
                source = name,
                name   = formatStreamLabel(tabLabel, ""),
                url    = mediaUrl,
                type   = type,
            ) {
                this.referer = shellUrl
                this.headers = buildStreamHeaders(shellUrl)
                this.quality = Qualities.Unknown.value
            },
        )
        return true
    }

    /** Mail.ru (`my.mail.ru`): `video_key` çerezini `okhttpResponse.headers` üzerinden korur. */
    private suspend fun resolveMailRu(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val resp = runCatching {
            app.get(url, headers = mapOf("user-agent" to STREAM_USER_AGENT), referer = referer)
        }.getOrNull() ?: return false

        var cookieHeader = extractSetCookiePair(resp.okhttpResponse.headers.values("Set-Cookie"), "video_key")
        var streams = DiziBoxPlayerParser.parseMailRuStreams(resp.text)

        if (streams.isEmpty()) {
            val metaPath = Regex(""""metaUrl"\s*:\s*"([^"]+)"""").find(resp.text)?.groupValues?.get(1)
            val metaUrl = DiziBoxPlayerParser.resolveUrl(metaPath?.replace("\\/", "/"), url)
            if (metaUrl != null) {
                val metaResp = runCatching {
                    app.get(metaUrl, headers = mapOf("user-agent" to STREAM_USER_AGENT), referer = url)
                }.getOrNull()
                if (metaResp != null) {
                    if (cookieHeader == null) {
                        cookieHeader = extractSetCookiePair(metaResp.okhttpResponse.headers.values("Set-Cookie"), "video_key")
                    }
                    streams = DiziBoxPlayerParser.parseMailRuStreams(metaResp.text)
                }
            }
        }

        if (streams.isEmpty()) return false
        val headers = buildMap {
            put("Referer", "https://my.mail.ru/")
            put("Origin", "https://my.mail.ru")
            put("user-agent", STREAM_USER_AGENT)
            if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader)
        }
        for (stream in streams) {
            val type = if (stream.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            collector.emitLink(
                newExtractorLink(
                    source = name,
                    name   = formatStreamLabel(tabLabel, stream.qualityLabel),
                    url    = stream.url,
                    type   = type,
                ) {
                    this.referer = "https://my.mail.ru/"
                    this.headers = headers
                    this.quality = getQualityFromName(stream.qualityLabel)
                },
            )
        }
        return true
    }

    /** VK Video (`vk.com/video_ext.php`, `vkvideo.ru/video_ext.php`). */
    private suspend fun resolveVk(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val body = runCatching {
            app.get(url, headers = mapOf("user-agent" to STREAM_USER_AGENT), referer = referer).text
        }.getOrNull() ?: return false

        val streams = DiziBoxPlayerParser.parseVkStreams(body)
        if (streams.isEmpty()) return false

        val headers = buildStreamHeaders(url)
        for (stream in streams) {
            collector.emitLink(
                newExtractorLink(
                    source = name,
                    name   = formatStreamLabel(tabLabel, stream.qualityLabel),
                    url    = stream.url,
                    type   = if (stream.isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = url
                    this.headers = headers
                    this.quality = getQualityFromName(stream.qualityLabel)
                },
            )
        }
        return true
    }

    /** Yerleşik 113+ CloudStream extractor'ını sekme etiketi ve tüm başlıklarıyla çalıştırır. */
    private suspend fun invokeBuiltInExtractor(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val captured = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(url, referer, collector.rawSubtitleCallback()) { link ->
                captured.add(link)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "loadExtractor (${url.take(80)}): ${e.message}")
        }
        if (captured.isEmpty()) return false

        for (link in captured) {
            val effectiveRef = link.referer.ifBlank { referer }
            val mergedHeaders = buildMap {
                put("Referer", effectiveRef)
                put("user-agent", STREAM_USER_AGENT)
                link.headers.forEach { (k, v) -> put(k, v) }
            }
            val displayLabel = if (tabLabel.isNotBlank() && !link.name.contains(tabLabel, ignoreCase = true)) {
                "$name • $tabLabel (${link.name})"
            } else {
                link.name
            }
            collector.emitLink(
                newExtractorLink(
                    source = name,
                    name   = displayLabel,
                    url    = link.url,
                    type   = link.type,
                ) {
                    this.referer       = effectiveRef
                    this.headers       = mergedHeaders
                    this.quality       = link.quality
                    this.audioTracks   = link.audioTracks
                    this.extractorData = link.extractorData
                },
            )
        }
        return true
    }

    /** Bilinmeyen özel gömülü oynatıcılar için güvenli HTML5 / JWPlayer / Packer yedeği. */
    private suspend fun resolveGenericEmbed(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
        visited: MutableSet<String>,
        depth: Int,
    ): Boolean {
        val rawHtml = runCatching {
            app.get(
                url,
                headers = buildStreamHeaders(referer),
                referer = referer,
            ).text
        }.getOrNull() ?: return false

        if (DiziBoxPlayerParser.isHlsManifest(rawHtml)) {
            emitHlsPlaylist(rawHtml, url, referer, tabLabel, collector)
            return true
        }

        val decoded = DiziBoxPlayerParser.decodeWrapperHtml(rawHtml)
        emitParsedSubtitles(decoded, url, collector)
        if (emitParsedMediaSources(decoded, url, tabLabel, collector, expandM3u8 = true)) {
            return true
        }

        if (depth >= 3) return false
        val doc = Jsoup.parse(decoded, url)
        var found = false
        for (nested in DiziBoxPlayerParser.nestedIframes(doc, url).take(3)) {
            if (nested == url) continue
            if (resolvePlayerChain(nested, url, tabLabel, collector, visited, depth + 1)) {
                found = true
            }
        }
        return found
    }

    // ────────── Medya & Altyazı Yayınlama Yardımcıları ──────────

    private suspend fun emitParsedSubtitles(
        html: String,
        baseUrl: String,
        collector: LinkCollector,
    ) {
        DiziBoxPlayerParser.subtitleTracks(html).forEach { track ->
            val subUrl = DiziBoxPlayerParser.resolveUrl(track.url, baseUrl) ?: return@forEach
            collector.emitSubtitle(track.label, subUrl, referer = baseUrl)
        }
    }

    private suspend fun emitParsedMediaSources(
        html: String,
        playerUrl: String,
        tabLabel: String,
        collector: LinkCollector,
        expandM3u8: Boolean = false,
    ): Boolean {
        var emitted = false
        for (source in DiziBoxPlayerParser.mediaSources(html)) {
            val mediaUrl = DiziBoxPlayerParser.resolveUrl(source.url, playerUrl) ?: continue

            if (DiziBoxPlayerParser.isMolyStreamStreamUrl(mediaUrl)) {
                val verified = fetchVerifiedMolyPlaylist(mediaUrl, playerUrl) ?: continue
                emitHlsPlaylist(verified.body, verified.url, playerUrl, tabLabel, collector)
                emitted = true
                continue
            }

            when {
                DiziBoxPlayerParser.isHls(source) -> {
                    val streamHeaders = buildStreamHeaders(playerUrl)
                    val expanded = if (expandM3u8) {
                        runCatching {
                            M3u8Helper.generateM3u8(
                                source    = name,
                                streamUrl = mediaUrl,
                                referer   = playerUrl,
                                headers   = streamHeaders,
                                name      = formatStreamLabel(tabLabel, source.label),
                            )
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (!expanded.isNullOrEmpty()) {
                        expanded.forEach { collector.emitLink(it) }
                    } else {
                        emitDirectHls(
                            url      = mediaUrl,
                            referer  = playerUrl,
                            label    = formatStreamLabel(tabLabel, source.label),
                            quality  = source.label.takeIf { it.isNotBlank() }
                                ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value,
                            collector = collector,
                        )
                    }
                    emitted = true
                }

                DiziBoxPlayerParser.isMp4(source) -> {
                    val quality = source.label.takeIf { it.isNotBlank() }
                        ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value
                    collector.emitLink(
                        newExtractorLink(
                            source = name,
                            name   = formatStreamLabel(tabLabel, source.label),
                            url    = mediaUrl,
                            type   = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = playerUrl
                            this.headers = buildStreamHeaders(playerUrl)
                            this.quality = quality
                        },
                    )
                    emitted = true
                }

                // KTS-CSP-9110 KURALI: Doğrudan medya olmayan (embed/sayfa) adresler ASLA
                // ExtractorLink olarak yayınlanmaz; yalnızca yerleşik extractor'a devredilir.
                else -> {
                    if (invokeBuiltInExtractor(mediaUrl, playerUrl, tabLabel, collector)) {
                        emitted = true
                    }
                }
            }
        }
        return emitted
    }

    /**
     * Master M3U8 varyantlarını gerçek çözünürlükleriyle yayar.
     * `streamRef` olarak playlist'in kendisi DEĞİL, üst oynatıcı belgesi (`/embed/{id}`)
     * korunur; tarayıcı/ExoPlayer segment isteklerinde bu Referer beklenir.
     */
    private suspend fun emitHlsPlaylist(
        body: String,
        playlistUrl: String,
        streamRef: String,
        tabLabel: String,
        collector: LinkCollector,
    ) {
        val variants = DiziBoxPlayerParser.hlsVariants(body, playlistUrl)
        if (variants.isEmpty()) {
            emitDirectHls(
                url       = playlistUrl,
                referer   = streamRef,
                label     = formatStreamLabel(tabLabel, ""),
                quality   = Qualities.Unknown.value,
                collector = collector,
            )
            return
        }
        variants.forEach { variant ->
            val qLabel = variant.height.takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
            emitDirectHls(
                url       = variant.url,
                referer   = streamRef,
                label     = formatStreamLabel(tabLabel, qLabel),
                quality   = variant.height.takeIf { it > 0 } ?: Qualities.Unknown.value,
                collector = collector,
            )
        }
    }

    private suspend fun emitDirectHls(
        url: String,
        referer: String,
        label: String,
        quality: Int,
        collector: LinkCollector,
    ) {
        collector.emitLink(
            newExtractorLink(
                source = name,
                name   = label,
                url    = url,
                type   = ExtractorLinkType.M3U8,
            ) {
                this.referer = referer
                this.headers = buildStreamHeaders(referer)
                this.quality = quality
            },
        )
    }

    private fun formatStreamLabel(tabLabel: String, qualityOrVariant: String): String {
        val cleanTab = tabLabel.trim()
        val cleanQual = qualityOrVariant.trim()
        return buildString {
            append(name)
            if (cleanTab.isNotEmpty() && !cleanTab.equals(name, ignoreCase = true)) {
                append(" • ").append(cleanTab)
            }
            if (cleanQual.isNotEmpty() && !cleanQual.equals("HLS", ignoreCase = true)) {
                append(" ").append(cleanQual)
            }
        }
    }

    private suspend fun fetchDiziBoxDocument(
        url: String,
        referer: String = "$mainUrl/",
        cacheTime: Int? = null,
    ): Document = if (cacheTime != null) {
        app.get(
            url,
            referer     = referer,
            cookies     = diziboxCookies(),
            interceptor = cloudflareInterceptor,
            cacheTime   = cacheTime,
        ).document
    } else {
        app.get(
            url,
            referer     = referer,
            cookies     = diziboxCookies(),
            interceptor = cloudflareInterceptor,
        ).document
    }

    private fun isDiziBoxHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val siteHost = runCatching { URI(mainUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        return !host.isNullOrBlank() && host == siteHost
    }

    private fun extractSetCookiePair(setCookies: List<String>, cookieName: String): String? {
        val prefix = "$cookieName="
        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            if (pair.startsWith(prefix, ignoreCase = true)) return pair
        }
        return null
    }

    companion object {
        private const val TAG = "DZBX"
        private const val TAB_TIMEOUT_MS = 30_000L

        /**
         * Yalnızca dış video CDN'lerinde (MolyStream / Vidmoly / OK.ru / Sibnet / Mail.ru / VK)
         * kullanılır; `dizibox.live` isteklerinde ASLA kullanılmaz. Küçük harfli `"user-agent"`
         * anahtarı OkHttp / RequestsHelper harita birleştirmesinde çift başlık oluşmasını önler.
         */
        private const val STREAM_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private fun originOf(url: String): String? = runCatching {
            val uri = URI(url)
            val scheme = uri.scheme ?: return@runCatching null
            val authority = uri.rawAuthority ?: return@runCatching null
            "$scheme://$authority"
        }.getOrNull()

        private fun buildStreamHeaders(referer: String): Map<String, String> = buildMap {
            put("Referer", referer)
            put("user-agent", STREAM_USER_AGENT)
            originOf(referer)?.let { put("Origin", it) }
        }
    }
}
