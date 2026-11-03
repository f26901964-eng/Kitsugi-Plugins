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
import okhttp3.Request
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
     * Kitsugi keşfet ekranı (`AddonExploreInline` / `AddonExploreDialog`) `mainPage` içindeki
     * tüm satırları sırayla çekip tamamlanınca yükleme göstergesini kapatır. 29 satır (~27.5 MB HTML)
     * hem ekranı kilitliyor hem de Cloudflare hız sınırını tetikliyordu; en yüksek sinyalli 13
     * vitrin ve kategori satırı seçildi.
     */
    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/SAYFA/"              to "Son Bölümler",
        "${mainUrl}/tum-bolumler/page/SAYFA/?tip=populer"  to "Popüler Bölümler",
        "${mainUrl}/#new-series"                           to "Yeni Diziler",
        "${mainUrl}/efsane-diziler/page/SAYFA/"            to "Efsane Diziler",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"               to "Dizi Arşivi",
        "${mainUrl}/ulke/turkiye/page/SAYFA/"              to "Yerli Diziler",
        "${mainUrl}/tur/aksiyon/page/SAYFA/"               to "Aksiyon",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA/"            to "Bilimkurgu",
        "${mainUrl}/tur/drama/page/SAYFA/"                 to "Drama",
        "${mainUrl}/tur/fantastik/page/SAYFA/"             to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA/"               to "Gerilim",
        "${mainUrl}/tur/komedi/page/SAYFA/"                to "Komedi",
        "${mainUrl}/tur/suc/page/SAYFA/"                   to "Suç",
    )

    private fun networkInterceptor(): Interceptor = DiziBoxNetwork.interceptorFor(mainUrl)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionId = DiziBoxPlayerParser.extractSectionFragment(request.data)
        if (sectionId != null && page > 1) {
            return newHomePageResponse(HomePageList(request.name, emptyList()), hasNext = false)
        }

        val url = DiziBoxPlayerParser.buildMainPageUrl(request.data, page)
        val document = fetchDiziBoxDocument(url, cacheTime = 60)

        val cards = DiziBoxPlayerParser.parseListingCards(document, url, sectionId)
            .map { it.toSearchResult() }
        val isHorizontal = request.name.contains("Bölüm", ignoreCase = true)
        val hasNext = sectionId == null && cards.isNotEmpty() && DiziBoxPlayerParser.hasNextPage(document, page)

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
        val xhrHeaders = mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Accept"           to "application/json, text/javascript, */*; q=0.01",
        )
        val encoded = URLEncoder.encode(query, "UTF-8")
        val interceptor = networkInterceptor()

        val getBody = runCatching {
            withTimeoutOrNull(AJAX_SEARCH_TIMEOUT_MS) {
                app.get(
                    "$ajaxUrl?action=dwls_search&s=$encoded",
                    headers     = xhrHeaders,
                    referer     = "$mainUrl/",
                    interceptor = interceptor,
                ).text
            }
        }.getOrNull()
        val getResults = getBody?.let { DiziBoxPlayerParser.dwlsSearchResults(it) }.orEmpty()
        if (getResults.isNotEmpty()) return toDwlsResponses(getResults)

        val postBody = runCatching {
            withTimeoutOrNull(AJAX_SEARCH_TIMEOUT_MS) {
                app.post(
                    ajaxUrl,
                    data        = mapOf("action" to "dwls_search", "s" to query),
                    headers     = xhrHeaders,
                    referer     = "$mainUrl/",
                    interceptor = interceptor,
                ).text
            }
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
            val seriesDoc = try {
                withTimeoutOrNull(SEASON_FETCH_TIMEOUT_MS) {
                    fetchDiziBoxDocument(archiveTarget, referer = url)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (seriesDoc != null) archiveTarget to seriesDoc else url to initialDoc
        } else {
            url to initialDoc
        }

        val detail = DiziBoxPlayerParser.parseSeriesDetail(document, effectiveUrl)
            ?: DiziBoxPlayerParser.parseEpisodePageFallback(document, effectiveUrl)
            ?: return null

        val episodeMap = LinkedHashMap<String, DiziBoxPlayerParser.ParsedEpisode>()
        detail.activeEpisodes.forEach { episodeMap.putIfAbsent(it.url, it) }

        // Aktif sezon zaten ana dizi sayfasında yüklüdür (`a.hover`); diğer sezonlar
        // CsStreamRunner.safeLoad (15 sn) zaman kutusunu aşmamak için paralel çekilir.
        val seasonsToFetch = (if (episodeMap.isNotEmpty()) {
            detail.inactiveSeasonUrls
        } else {
            detail.allSeasonUrls
        }).filter { it != effectiveUrl }

        if (seasonsToFetch.isNotEmpty()) {
            val seasonGate = Semaphore(6)
            val fetchedSeasonLists = coroutineScope {
                seasonsToFetch.map { seasonUrl ->
                    async {
                        seasonGate.withPermit {
                            try {
                                val seasonDoc = withTimeoutOrNull(SEASON_FETCH_TIMEOUT_MS) {
                                    fetchDiziBoxDocument(seasonUrl, referer = detail.canonicalUrl)
                                } ?: return@withPermit emptyList()
                                DiziBoxPlayerParser.parseSeasonEpisodes(
                                    seasonDoc,
                                    seasonUrl,
                                    detail.posterUrl,
                                )
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }
                }.awaitAll()
            }
            fetchedSeasonLists.flatten().forEach { ep ->
                episodeMap.putIfAbsent(ep.url, ep)
            }
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

    internal class LinkCollector(
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
                    this.headers = DiziBoxNetwork.buildStreamHeaders(referer)
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
        val initialDoc = try {
            fetchDiziBoxDocument(data)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Bölüm sayfası alınamadı: ${e.message}")
            return false
        }

        // Yanlışlıkla dizi ana sayfası verilirse ilk bölüme geç
        val (episodeUrl, mainDoc) = if (
            initialDoc.selectFirst("div.tv-overview") != null &&
            DiziBoxPlayerParser.playerIframes(initialDoc, data).isEmpty()
        ) {
            val firstEpUrl = DiziBoxPlayerParser.parseSeasonEpisodes(initialDoc, data).firstOrNull()?.url
            if (!firstEpUrl.isNullOrBlank() && firstEpUrl != data) {
                val epDoc = runCatching { fetchDiziBoxDocument(firstEpUrl, referer = data) }.getOrNull()
                if (epDoc != null) firstEpUrl to epDoc else data to initialDoc
            } else {
                data to initialDoc
            }
        } else {
            data to initialDoc
        }

        val tabs = DiziBoxPlayerParser.playerTabs(mainDoc, episodeUrl).take(10)
        val collector = LinkCollector(subtitleCallback, callback)
        val tabSemaphore = Semaphore(4)

        coroutineScope {
            tabs.map { tab ->
                async {
                    tabSemaphore.withPermit {
                        withTimeoutOrNull(TAB_TIMEOUT_MS) {
                            processPlayerTab(tab, episodeUrl, mainDoc, collector)
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
                withTimeoutOrNull(TAB_DOC_TIMEOUT_MS) {
                    fetchDiziBoxDocument(tab.url, referer = rootEpisodeUrl)
                } ?: return
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
        if (DiziBoxNetwork.isDiziBoxHost(normalized, mainUrl) &&
            DiziBoxPlayerParser.isSupportedPlayerUrl(normalized, mainUrl)
        ) {
            return resolveDiziBoxWrapper(normalized, referer, tabLabel, collector, visited, depth)
        }

        // 2) MolyStream (`www.molystream.org` / `dbx.molystream.org` / `molystream.net` / `ydx.molystream.org`)
        if (DiziBoxPlayerParser.isMolyStreamHost(normalized)) {
            return resolveMolyStream(normalized, referer, tabLabel, collector, visited, depth)
        }

        // 3) Vidmoly (`vidmoly.net`, `vidmoly.biz`, `vidmoly.to`, `vidmoly.me`, `moly.to`)
        if (DiziBoxPlayerParser.isVidmolyHost(resolved) || DiziBoxPlayerParser.isVidmolyHost(normalized)) {
            return resolveVidmoly(resolved, referer, tabLabel, collector)
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
     * `wmode=opaque` sorgunun başına eklenir; `cookies` parametresi NiceHttp'e verilmeyip
     * `DiziBoxNetwork` son-katman interceptor'ı üzerinden tek `Cookie` başlığıyla ve
     * `Sec-Fetch-Dest: iframe` / `Sec-Fetch-Site: same-origin` ile çekilir.
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
        val rawHtml = withTimeoutOrNull(WRAPPER_TIMEOUT_MS) {
            app.get(
                wrapperUrl,
                referer     = referer,
                interceptor = networkInterceptor(),
            ).text
        } ?: return false

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
     * Türkiye'de `dbx.molystream.org` BTK engelli olduğundan ve Kitsugi `CsCfWarmupManager`
     * yalnızca `www.molystream.org` adresini ısıttığından ÖNCE `www.molystream.org` aynası
     * sınırlı zaman aşımıyla denenir; böylece tek engelli ayna 30 sn sekme bütçesini tüketemez.
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

        // Aşama A: www.molystream.org öncelikli sheila master M3U8 uç noktası
        if (sheilaUrl != null) {
            val sheilaCandidates = DiziBoxPlayerParser.molyStreamSheilaCandidates(sheilaUrl, embedPageUrl)
            sheilaCandidates.forEachIndexed { idx, (candidateSheila, candidateEmbedRef) ->
                val budgetMs = if (idx == 0) PRIMARY_CANDIDATE_TIMEOUT_MS else MIRROR_CANDIDATE_TIMEOUT_MS
                val verified = fetchVerifiedMolyPlaylist(candidateSheila, candidateEmbedRef, budgetMs)
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

        // Aşama B: /embed/{id} oynatıcı sayfası (CryptoJS AES / JWPlayer kurulumu + altyazılar)
        val embedCandidates = DiziBoxPlayerParser.molyStreamEmbedCandidates(embedPageUrl)
        embedCandidates.forEachIndexed { idx, candidateEmbed ->
            val budgetMs = if (idx == 0) PRIMARY_CANDIDATE_TIMEOUT_MS else MIRROR_CANDIDATE_TIMEOUT_MS
            for (parentRef in listOf(referer, "$mainUrl/").distinct()) {
                val rawHtml = try {
                    withTimeoutOrNull(budgetMs) {
                        app.get(
                            candidateEmbed,
                            headers     = DiziBoxNetwork.buildStreamHeaders(parentRef),
                            referer     = parentRef,
                            interceptor = networkInterceptor(),
                        ).text
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                } ?: continue

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

    internal data class VerifiedPlaylist(val url: String, val body: String)

    private suspend fun fetchVerifiedMolyPlaylist(
        url: String,
        embedReferer: String,
        timeoutMs: Long = PRIMARY_CANDIDATE_TIMEOUT_MS,
    ): VerifiedPlaylist? {
        return try {
            withTimeoutOrNull(timeoutMs) {
                val body = app.get(
                    url,
                    headers     = DiziBoxNetwork.buildStreamHeaders(embedReferer),
                    referer     = embedReferer,
                    interceptor = networkInterceptor(),
                ).text
                when {
                    DiziBoxPlayerParser.isHlsManifest(body) -> VerifiedPlaylist(url, body)
                    else -> {
                        val hopTarget = DiziBoxPlayerParser.plainHttpsTarget(body) ?: return@withTimeoutOrNull null
                        val hopBody = app.get(
                            hopTarget,
                            headers     = DiziBoxNetwork.buildStreamHeaders(embedReferer),
                            referer     = embedReferer,
                            interceptor = networkInterceptor(),
                        ).text
                        hopBody.takeIf { DiziBoxPlayerParser.isHlsManifest(it) }
                            ?.let { VerifiedPlaylist(hopTarget, it) }
                    }
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
     * Vidmoly (`Moly+` ve `İngilizce`): BTK-engelli `vidmoly.to` / `vidmoly.me` yerine
     * önce `vidmoly.net` ve `vidmoly.biz` aynalarını dener; canlı yönlendirme sonrasındaki
     * gerçek ayna adresini `Referer` olarak korur ve JWPlayer altyazılarını aktarır.
     */
    private suspend fun resolveVidmoly(
        rawUrl: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val headers = mapOf(
            "User-Agent"     to DiziBoxNetwork.UNIFIED_USER_AGENT,
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
        )

        val candidates = DiziBoxPlayerParser.vidmolyCandidates(rawUrl)
        for (candidateUrl in candidates) {
            val resp = try {
                withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                    app.get(candidateUrl, headers = headers, referer = referer)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue

            val effectiveEmbedUrl = resp.url.ifBlank { candidateUrl }
            val decoded = DiziBoxPlayerParser.decodeWrapperHtml(resp.text)
            emitParsedSubtitles(decoded, effectiveEmbedUrl, collector)
            if (emitParsedMediaSources(decoded, effectiveEmbedUrl, tabLabel, collector, expandM3u8 = true)) {
                return true
            }
        }

        val fallbackTarget = candidates.firstOrNull() ?: DiziBoxPlayerParser.normalizeEmbedUrl(rawUrl)
        return invokeBuiltInExtractor(fallbackTarget, referer, tabLabel, collector)
    }

    /**
     * OK.ru (`Odnok`):
     *  1) `haydi.php` içindeki tam `ok.ru` embed URL'sini (tüm sorgu parametreleriyle!) korur.
     *  2) `UNIFIED_USER_AGENT` ile çekerek `vd*.okcdn.ru` imzasındaki `srcAg=CHROME_ANDROID`
     *     parametresinin Kitsugi oynatıcısıyla birebir eşleşmesini sağlar.
     *  3) `.m3u8` HLS akışı varsa çözünürlük varyantlarına açarak yayar; böylece uzantısız
     *     `vd*.okcdn.ru/?ct=0` MP4 bağlantılarının embed kuyruğunu tıkaması önlenir.
     */
    private suspend fun resolveOkRu(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val okHeaders = mapOf(
            "Accept"     to "*/*",
            "Origin"     to "https://ok.ru",
            "User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT,
        )

        var streams = emptyList<DiziBoxPlayerParser.OkRuStream>()
        val embedCandidates = DiziBoxPlayerParser.okRuEmbedUrls(url)
        for (embedCandidate in embedCandidates) {
            streams = try {
                val html = withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                    app.get(embedCandidate, headers = okHeaders, referer = referer).text
                } ?: continue
                DiziBoxPlayerParser.parseOkRuStreams(html)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (streams.isNotEmpty()) break
        }

        val videoId = DiziBoxPlayerParser.okRuVideoId(url)
        if (streams.isEmpty() && videoId != null) {
            val primaryEmbed = embedCandidates.firstOrNull() ?: "https://ok.ru/videoembed/$videoId"
            streams = try {
                val metaJson = withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                    app.post(
                        "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                        headers = okHeaders,
                        referer = primaryEmbed,
                    ).text
                }
                metaJson?.let { DiziBoxPlayerParser.parseOkRuStreams(it) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        if (streams.isEmpty()) return false

        val playbackHeaders = mapOf(
            "Referer"    to "https://ok.ru/",
            "Origin"     to "https://ok.ru",
            "User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT,
        )

        val hlsStreams = streams.filter { it.isHls }
        var emittedAny = false

        for (hls in hlsStreams) {
            val masterBody = try {
                withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                    app.get(hls.url, headers = playbackHeaders, referer = "https://ok.ru/").text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }

            val variants = masterBody?.let { DiziBoxPlayerParser.hlsVariants(it, hls.url) }.orEmpty()
                .filter { DiziBoxPlayerParser.isClearlyDirectStreamUrl(it.url) }

            for (variant in variants) {
                val qLabel = variant.height.takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
                collector.emitLink(
                    newExtractorLink(
                        source = name,
                        name   = formatStreamLabel(tabLabel, qLabel),
                        url    = variant.url,
                        type   = ExtractorLinkType.M3U8,
                    ) {
                        this.referer = "https://ok.ru/"
                        this.headers = playbackHeaders
                        this.quality = variant.height.takeIf { it > 0 } ?: Qualities.Unknown.value
                    },
                )
                emittedAny = true
            }

            collector.emitLink(
                newExtractorLink(
                    source = name,
                    name   = formatStreamLabel(tabLabel, if (variants.isNotEmpty()) "Otomatik" else ""),
                    url    = hls.url,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "https://ok.ru/"
                    this.headers = playbackHeaders
                    this.quality = Qualities.Unknown.value
                },
            )
            emittedAny = true
        }

        // HLS mevcutsa yalnızca açık uzantılı (.mp4) yedekleri ekle; HLS yoksa tüm MP4 akışlarını yay.
        val mp4Candidates = streams.filterNot { it.isHls }.filter { mp4 ->
            !emittedAny || DiziBoxPlayerParser.isClearlyDirectStreamUrl(mp4.url)
        }
        for (stream in mp4Candidates) {
            collector.emitLink(
                newExtractorLink(
                    source = name,
                    name   = formatStreamLabel(tabLabel, stream.qualityLabel),
                    url    = stream.url,
                    type   = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "https://ok.ru/"
                    this.headers = playbackHeaders
                    this.quality = getQualityFromName(stream.qualityLabel)
                },
            )
            emittedAny = true
        }

        return emittedAny
    }

    /** Sibnet (`video.sibnet.ru/shell.php?videoid=<id>`). */
    private suspend fun resolveSibnet(
        url: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val shellUrl = DiziBoxPlayerParser.sibnetShellUrl(url) ?: return false
        val body = try {
            withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                app.get(
                    shellUrl,
                    headers = mapOf("User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT),
                    referer = "https://video.sibnet.ru/",
                ).text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

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
                this.headers = DiziBoxNetwork.buildStreamHeaders(shellUrl)
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
        val uaHeaders = mapOf("User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT)
        val resp = try {
            withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                app.get(url, headers = uaHeaders, referer = referer)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        var cookieHeader = DiziBoxNetwork.extractSetCookiePair(
            resp.okhttpResponse.headers.values("Set-Cookie"),
            "video_key",
        )
        var streams = DiziBoxPlayerParser.parseMailRuStreams(resp.text)

        if (streams.isEmpty()) {
            val metaPath = Regex(""""metaUrl"\s*:\s*"([^"]+)"""").find(resp.text)?.groupValues?.get(1)
            val metaUrl = DiziBoxPlayerParser.resolveUrl(metaPath?.replace("\\/", "/"), url)
            if (metaUrl != null) {
                val metaResp = try {
                    withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                        app.get(metaUrl, headers = uaHeaders, referer = url)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (metaResp != null) {
                    if (cookieHeader == null) {
                        cookieHeader = DiziBoxNetwork.extractSetCookiePair(
                            metaResp.okhttpResponse.headers.values("Set-Cookie"),
                            "video_key",
                        )
                    }
                    streams = DiziBoxPlayerParser.parseMailRuStreams(metaResp.text)
                }
            }
        }

        if (streams.isEmpty()) return false
        val headers = buildMap {
            put("Referer", "https://my.mail.ru/")
            put("Origin", "https://my.mail.ru")
            put("User-Agent", DiziBoxNetwork.UNIFIED_USER_AGENT)
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
        val body = try {
            withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                app.get(
                    url,
                    headers = mapOf("User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT),
                    referer = referer,
                ).text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

        val streams = DiziBoxPlayerParser.parseVkStreams(body)
        if (streams.isEmpty()) return false

        val headers = DiziBoxNetwork.buildStreamHeaders(url)
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

    /** Yerleşik CloudStream extractor'larını sekme etiketi ve başlıklarıyla çalıştırır. */
    private suspend fun invokeBuiltInExtractor(
        url: String,
        referer: String,
        tabLabel: String,
        collector: LinkCollector,
    ): Boolean {
        val captured = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            withTimeoutOrNull(EXTRACTOR_TIMEOUT_MS) {
                loadExtractor(url, referer, collector.rawSubtitleCallback()) { link ->
                    captured.add(link)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "loadExtractor (${url.take(80)}): ${e.message}")
        }
        if (captured.isEmpty()) return false

        for (link in captured) {
            val effectiveRef = link.referer.ifBlank { referer }
            val mergedHeaders = DiziBoxNetwork.buildStreamHeaders(effectiveRef, link.headers)
            val displayLabel = if (tabLabel.isNotBlank() && !link.name.contains(tabLabel, ignoreCase = true)) {
                "$tabLabel (${link.name})"
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
        val rawHtml = try {
            withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                app.get(
                    url,
                    headers = DiziBoxNetwork.buildStreamHeaders(referer),
                    referer = referer,
                ).text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return false

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
                val candidates = DiziBoxPlayerParser.molyStreamSheilaCandidates(mediaUrl, playerUrl)
                for ((idx, pair) in candidates.withIndex()) {
                    val budgetMs = if (idx == 0) PRIMARY_CANDIDATE_TIMEOUT_MS else MIRROR_CANDIDATE_TIMEOUT_MS
                    val verified = fetchVerifiedMolyPlaylist(pair.first, pair.second, budgetMs) ?: continue
                    emitHlsPlaylist(verified.body, verified.url, pair.second, tabLabel, collector)
                    emitted = true
                    break
                }
                continue
            }

            when {
                DiziBoxPlayerParser.isHls(source) -> {
                    val streamHeaders = DiziBoxNetwork.buildStreamHeaders(playerUrl)
                    val expanded = if (expandM3u8) {
                        runCatching {
                            withTimeoutOrNull(MIRROR_CANDIDATE_TIMEOUT_MS) {
                                M3u8Helper.generateM3u8(
                                    source    = name,
                                    streamUrl = mediaUrl,
                                    referer   = playerUrl,
                                    headers   = streamHeaders,
                                    name      = formatStreamLabel(tabLabel, source.label),
                                )
                            }
                        }.getOrNull()
                    } else {
                        null
                    }
                    if (!expanded.isNullOrEmpty()) {
                        expanded.forEach { link ->
                            val cleanHeaders = DiziBoxNetwork.buildStreamHeaders(
                                link.referer.ifBlank { playerUrl },
                                link.headers,
                            )
                            collector.emitLink(
                                newExtractorLink(
                                    source = name,
                                    name   = link.name,
                                    url    = link.url,
                                    type   = ExtractorLinkType.M3U8,
                                ) {
                                    this.referer = link.referer.ifBlank { playerUrl }
                                    this.headers = cleanHeaders
                                    this.quality = link.quality
                                },
                            )
                        }
                    } else {
                        emitDirectHls(
                            url       = mediaUrl,
                            referer   = playerUrl,
                            label     = formatStreamLabel(tabLabel, source.label),
                            quality   = source.label.takeIf { it.isNotBlank() }
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
                            this.headers = DiziBoxNetwork.buildStreamHeaders(playerUrl)
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
     * korunur; ExoPlayer segment isteklerinde bu Referer ve Origin beklenir.
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
                this.headers = DiziBoxNetwork.buildStreamHeaders(referer)
                this.quality = quality
            },
        )
    }

    /**
     * Kitsugi `CsStreamRunner` zaten `"${api.name} • ${link.name}"` biçiminde öneki eklediği için
     * burada tekrar `"DiziBox • "` eklenmez; `"DBX Pro 1080p"` gibi temiz etiket üretilir.
     */
    private fun formatStreamLabel(tabLabel: String, qualityOrVariant: String): String {
        val cleanTab = tabLabel.trim().ifEmpty { "Kaynak" }
        val cleanQual = qualityOrVariant.trim()
        return buildString {
            append(cleanTab)
            if (cleanQual.isNotEmpty() && !cleanQual.equals("HLS", ignoreCase = true)) {
                append(' ').append(cleanQual)
            }
        }
    }

    private suspend fun fetchDiziBoxDocument(
        url: String,
        referer: String = "$mainUrl/",
        cacheTime: Int? = null,
    ): Document {
        val interceptor = networkInterceptor()
        return if (cacheTime != null) {
            app.get(
                url,
                referer     = referer,
                interceptor = interceptor,
                cacheTime   = cacheTime,
            ).document
        } else {
            app.get(
                url,
                referer     = referer,
                interceptor = interceptor,
            ).document
        }
    }

    companion object {
        private const val TAG = "DZBX"
        private const val TAB_TIMEOUT_MS = 28_000L
        private const val TAB_DOC_TIMEOUT_MS = 14_000L
        private const val WRAPPER_TIMEOUT_MS = 12_000L
        private const val PRIMARY_CANDIDATE_TIMEOUT_MS = 12_000L
        private const val MIRROR_CANDIDATE_TIMEOUT_MS = 8_500L
        private const val EXTRACTOR_TIMEOUT_MS = 14_000L
        private const val SEASON_FETCH_TIMEOUT_MS = 10_000L
        private const val AJAX_SEARCH_TIMEOUT_MS = 7_500L
    }
}

/**
 * DiziBox ve yardımcı `ExtractorApi` sınıflarının paylaştığı birleşik oturum, başlık ve
 * Cloudflare uyumluluk katmanı.
 *
 * Kök neden korumaları:
 * 1) `NiceHttp` `cookies = ...` parametresi ASLA kullanılmaz (küçük harfli `"cookie"` +
 *    `CloudflareKiller` büyük harfli `"Cookie"` çift başlık çarpışmasını ve `;` kesmesini önler).
 * 2) Tüm istekler ve `ExtractorLink` başlıkları `UNIFIED_USER_AGENT` ile hizalanır
 *    (`cf_clearance` UA bağlaması + `okcdn.ru` `srcAg=CHROME_ANDROID` imzası).
 * 3) `CloudflareKiller`'ın ezdiği `Sec-Fetch-*` başlıkları `/player/` PHP sarmalayıcılarında
 *    `iframe` / `same-origin`, XHR ve M3U8 isteklerinde `empty` / `cors` olarak düzeltilir.
 */
internal object DiziBoxNetwork {
    const val UNIFIED_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val fallbackCloudflareKiller by lazy { CloudflareKiller() }

    fun diziboxCookies(nowMillis: Long = System.currentTimeMillis()): Map<String, String> = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
        "dbxu"          to nowMillis.toString(),
    )

    fun interceptorFor(siteUrl: String = "https://www.dizibox.live"): Interceptor {
        val hasGlobalCfKiller = runCatching {
            app.baseClient.interceptors.any { it.javaClass.simpleName == "CloudflareKiller" }
        }.getOrDefault(false)

        return if (hasGlobalCfKiller) {
            Interceptor { chain ->
                chain.proceed(normalizeWireRequest(chain.request(), siteUrl))
            }
        } else {
            Interceptor { outerChain ->
                val delegatedChain = object : Interceptor.Chain by outerChain {
                    override fun proceed(request: Request): Response {
                        return outerChain.proceed(normalizeWireRequest(request, siteUrl))
                    }
                }
                fallbackCloudflareKiller.intercept(delegatedChain)
            }
        }
    }

    internal fun normalizeWireRequest(request: Request, siteUrl: String): Request {
        val reqUrl = request.url.toString()
        val referer = request.header("Referer")
        val isXhr = request.header("X-Requested-With").equals("XMLHttpRequest", ignoreCase = true)

        val extraCookies = if (isDiziBoxHost(reqUrl, siteUrl)) diziboxCookies() else emptyMap()
        val mergedCookie = DiziBoxPlayerParser.mergeCookieHeaders(
            existingCookieHeaders = request.headers.values("Cookie"),
            extraCookies          = extraCookies,
        )
        val fetchMeta = DiziBoxPlayerParser.classifyFetchMetadata(
            requestUrl       = reqUrl,
            refererUrl       = referer,
            isXmlHttpRequest = isXhr,
        )

        val builder = request.newBuilder()
        builder.removeAll("Cookie")
        if (!mergedCookie.isNullOrBlank()) {
            builder.header("Cookie", mergedCookie)
        }

        builder.removeAll("User-Agent")
        builder.header("User-Agent", UNIFIED_USER_AGENT)

        builder.removeAll("Sec-Fetch-Dest")
        builder.removeAll("Sec-Fetch-Mode")
        builder.removeAll("Sec-Fetch-Site")
        builder.removeAll("Sec-Fetch-User")
        builder.header("Sec-Fetch-Dest", fetchMeta.dest)
        builder.header("Sec-Fetch-Mode", fetchMeta.mode)
        builder.header("Sec-Fetch-Site", fetchMeta.site)
        if (fetchMeta.includeUser) {
            builder.header("Sec-Fetch-User", "?1")
        }
        if (!fetchMeta.includeUpgradeInsecure) {
            builder.removeAll("Upgrade-Insecure-Requests")
        }

        return builder.build()
    }

    fun isDiziBoxHost(url: String, siteUrl: String = "https://www.dizibox.live"): Boolean {
        val host = DiziBoxPlayerParser.normalizedHost(url)?.removePrefix("www.") ?: return false
        val siteHost = DiziBoxPlayerParser.normalizedHost(siteUrl)?.removePrefix("www.") ?: return false
        return host.isNotBlank() && host == siteHost
    }

    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching null
        val authority = uri.rawAuthority ?: return@runCatching null
        "$scheme://$authority"
    }.getOrNull()

    fun buildStreamHeaders(
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["Referer"] = referer
        originOf(referer)?.let { out["Origin"] = it }
        out["User-Agent"] = UNIFIED_USER_AGENT
        for ((k, v) in extraHeaders) {
            when {
                k.equals("referer", ignoreCase = true) -> out["Referer"] = v
                k.equals("origin", ignoreCase = true) -> out["Origin"] = v
                k.equals("user-agent", ignoreCase = true) -> out["User-Agent"] = UNIFIED_USER_AGENT
                v.isNotBlank() -> out[k] = v
            }
        }
        return out
    }

    fun extractSetCookiePair(setCookies: List<String>, cookieName: String): String? {
        val prefix = "$cookieName="
        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            if (pair.startsWith(prefix, ignoreCase = true)) return pair
        }
        return null
    }
}
