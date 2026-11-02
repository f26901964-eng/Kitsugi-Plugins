// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import java.net.URI

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/library/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 50L  // ? 0.05 saniye
    override var sequentialMainPageScrollDelay = 50L  // ? 0.05 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())

            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler/page/SAYFA/"  to "Tüm Bölümler",
        "${mainUrl}/ulke/turkiye"              to "Yerli",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"   to "Dizi Arşivi",
        "${mainUrl}/tur/aile/page/SAYFA/"      to "Aile",
        "${mainUrl}/tur/aksiyon/page/SAYFA"    to "Aksiyon",
        "${mainUrl}/tur/animasyon/page/SAYFA"  to "Animasyon",
        "${mainUrl}/tur/belgesel/page/SAYFA"   to "Belgesel",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA" to "Bilimkurgu",
        "${mainUrl}/tur/biyografi/page/SAYFA"  to "Biyografi",
        "${mainUrl}/tur/dram/page/SAYFA"       to "Dram",
        "${mainUrl}/tur/drama/page/SAYFA"      to "Drama",
        "${mainUrl}/tur/fantastik/page/SAYFA"  to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA"    to "Gerilim",
        "${mainUrl}/tur/gizem/page/SAYFA"      to "Gizem",
        "${mainUrl}/tur/komedi/page/SAYFA"     to "Komedi",
        "${mainUrl}/tur/korku/page/SAYFA"      to "Korku",
        "${mainUrl}/tur/macera/page/SAYFA"     to "Macera",
        "${mainUrl}/tur/muzik/page/SAYFA"      to "Müzik",
        "${mainUrl}/tur/muzikal/page/SAYFA"    to "Müzikal",
        "${mainUrl}/tur/reality-tv/page/SAYFA" to "Reality TV",
        "${mainUrl}/tur/romantik/page/SAYFA"   to "Romantik",
        "${mainUrl}/tur/savas/page/SAYFA"      to "Savaş",
        "${mainUrl}/tur/spor/page/SAYFA"       to "Spor",
        "${mainUrl}/tur/suc/page/SAYFA"        to "Suç",
        "${mainUrl}/tur/tarih/page/SAYFA"      to "Tarih",
        "${mainUrl}/tur/western/page/SAYFA"    to "Western",
        "${mainUrl}/tur/yarisma/page/SAYFA"    to "Yarışma"
    )

    /**
     * Canlı tema (2026) kart türleri:
     *  - article.article-series-poster      (ana sayfa dizi afişleri; başlık a.poster-title)
     *  - article.article-series-small-grid  (ana sayfa küçük dizi kartları; başlık yok, a.figure)
     *  - article.article-episode-card       (/tum-bolumler; başlık a.episode-card-title)
     *  - article.article-episode-small-grid (ana sayfa bölüm kartları; a.figure-link)
     *  - article.detailed-article           (eski tema / arşiv / arama)
     */
    private val cardSelector =
        "article.article-series-poster, article.article-series-small-grid, " +
            "article.article-episode-card, article.article-episode-small-grid, article.detailed-article"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(
            url,
            cookies     = playerCookies,
            interceptor = interceptor, cacheTime = 60
        ).document
        val home = document.select(cardSelector)
            .mapNotNull { it.toCardResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    /** Tüm kart türleri için tek ayrıştırıcı. Başlık ve afiş için birden çok yedek kullanır. */
    private fun Element.toCardResult(): SearchResponse? {
        val anchor = selectFirst("a.episode-card-title")
            ?: selectFirst("a.poster-title")
            ?: selectFirst("a.series-details")
            ?: selectFirst("a.figure-link[href]")
            ?: selectFirst("a.figure[href]")
            ?: selectFirst("a[href]")
            ?: return null
        val href = fixUrlNull(anchor.attr("href").trim()) ?: return null
        val image = selectFirst("img")

        // Küçük kartlarda <a> içinde IMDb puanı (span.imdb) da bulunur; metinden çıkarılır.
        val anchorText = anchor.clone().apply { select(".imdb").remove() }.text()
        val title = listOf(
            anchorText,
            anchor.attr("title"),
            image?.attr("alt") ?: "",
            slugTitle(href),
        ).map { raw ->
            // Yan çubuk bağlantılarında başlık "Before izle Drama" gibi tür etiketi taşır.
            raw.replace(Regex("\\s+"), " ").replace(Regex("\\s+izle\\b.*$", RegexOption.IGNORE_CASE), "").trim()
        }.firstOrNull { it.isNotBlank() } ?: return null

        val backgroundUrl = selectFirst("div.box-image")?.attr("style")
            ?.let { Regex("""url\(['"]?([^'")]+)""").find(it)?.groupValues?.get(1) }
        val imageUrl = listOf(
            image?.attr("data-src"),
            image?.attr("src"),
            backgroundUrl,
        ).firstOrNull { !it.isNullOrBlank() && !it.startsWith("data:") }

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = fixUrlNull(imageUrl)
        }
    }

    /** URL slug'ından okunabilir başlık: "friends-izle-hd" -> "Friends". */
    private fun slugTitle(href: String): String {
        return href.trimEnd('/').substringAfterLast('/')
            .replace(Regex("(-izle)+$"), "")
            .replace(Regex("-hd(-\\d+)?$"), "")
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Canlı tema: arama admin-ajax dwls_search ile JSON döndürür (üst düzey dizi).
        ajaxSearch(query)?.let { if (it.isNotEmpty()) return it }

        // Hata/boş yanıt durumunda klasik HTML aramasına düş.
        val document = app.get(
            "${mainUrl}/?s=${java.net.URLEncoder.encode(query, "UTF-8")}",
            cookies     = playerCookies,
            interceptor = interceptor
        ).document

        return document.select(cardSelector)
            .mapNotNull { it.toCardResult() }
            .distinctBy { it.url }
    }

    /** GET ile dener; boş/hatalı ise aynı parametreleri POST ile dener. */
    private suspend fun ajaxSearch(query: String): List<SearchResponse>? {
        val ajaxUrl = "${mainUrl}/wp-admin/admin-ajax.php"
        val headers = mapOf(
            "User-Agent" to playerUserAgent,
            "X-Requested-With" to "XMLHttpRequest",
            "Accept" to "application/json, text/javascript, */*; q=0.01",
        )
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")

        val getBody = runCatching {
            app.get(
                "$ajaxUrl?action=dwls_search&s=$encoded",
                headers     = headers,
                cookies     = playerCookies,
                referer     = "$mainUrl/",
                interceptor = interceptor,
            ).text
        }.getOrNull()
        val getResults = getBody?.let { DiziBoxPlayerParser.dwlsSearchResults(it) }.orEmpty()
        if (getResults.isNotEmpty()) return toDwlsResponses(getResults)

        val postBody = runCatching {
            app.post(
                ajaxUrl,
                data        = mapOf("action" to "dwls_search", "s" to query),
                headers     = headers,
                cookies     = playerCookies,
                referer     = "$mainUrl/",
                interceptor = interceptor,
            ).text
        }.getOrNull()
        val postResults = postBody?.let { DiziBoxPlayerParser.dwlsSearchResults(it) }.orEmpty()
        return if (postResults.isNotEmpty()) toDwlsResponses(postResults) else null
    }

    private fun toDwlsResponses(results: List<DiziBoxPlayerParser.DwlsResult>): List<SearchResponse> =
        results.map { result ->
            newTvSeriesSearchResponse(result.title, result.url, TvType.TvSeries) {
                this.posterUrl = fixUrlNull(DiziBoxPlayerParser.upscaleThumbnail(result.thumbnail))
            }
        }.distinctBy { it.url }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val initial = app.get(
            url,
            cookies     = playerCookies,
            interceptor = interceptor
        ).document

        // Eski bölüm/eser bağlantıları arşiv kutusuna taşınmış olabiliyor.
        val relocated = DiziBoxPlayerParser.archiveBoxLink(initial, url)
        val document = if (relocated != null && relocated != url) {
            app.get(relocated, cookies = playerCookies, interceptor = interceptor).document
        } else {
            initial
        }

        val title       = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.tv-overview figure img")?.attr("src"))
        val description = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']").map { it.text() }
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("div.tv-overview iframe")?.attr("src")

        val episodeList = mutableListOf<Episode>()
        document.select("div#seasons-list a").forEach {
            val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
            val epDoc = app.get(
                epUrl,
                cookies     = mapOf(
                    "LockUser"      to "true",
                    "isTrustedUser" to "true",
                    "dbxu"          to "1743289650198"
                ),
                interceptor = interceptor
            ).document

            epDoc.select("article.grid-box").forEach ep@ { epElem ->
                val epTitle   = epElem.selectFirst("div.post-title a")?.text()?.trim() ?: return@ep
                val epHref    = fixUrlNull(epElem.selectFirst("div.post-title a")?.attr("href")) ?: return@ep
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name = epTitle
                    this.season = epSeason
                    this.episode = epEpisode
                })
            }
        }

        // Tema değiştiyse ve grid-box yoksa slug kalıbından bölümleri topla.
        if (episodeList.isEmpty()) {
            DiziBoxPlayerParser.episodeAnchors(document, url).forEach { anchor ->
                episodeList.add(newEpisode(anchor.url) {
                    this.name = "${anchor.season}. Sezon ${anchor.episode}. Bölüm"
                    this.season = anchor.season
                    this.episode = anchor.episode
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            addActors(actors)
            addTrailer(trailer)
        }
    }

    private val playerCookies = mapOf(
        "LockUser" to "true",
        "isTrustedUser" to "true",
        "dbxu" to "1743289650198",
    )
    private val playerUserAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun isDiziBoxHost(url: String): Boolean {
        val host = runCatching { URI(url).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        val siteHost = runCatching { URI(mainUrl).host?.lowercase()?.removePrefix("www.") }.getOrNull()
        return !host.isNullOrBlank() && host == siteHost
    }

    private fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching null
        val authority = uri.rawAuthority ?: return@runCatching null
        "$scheme://$authority"
    }.getOrNull()

    private fun playerRequestHeaders(referer: String): Map<String, String> = buildMap {
        put("User-Agent", playerUserAgent)
        originOf(referer)?.let { put("Origin", it) }
    }

    private fun decodePackedPlayerHtml(html: String): String {
        val functionStart = html.indexOf("unescape", ignoreCase = true)
        if (functionStart < 0) return html
        val openParen = html.indexOf('(', functionStart + "unescape".length)
        if (openParen < 0) return html
        val quoteIndex = (openParen + 1 until html.length)
            .firstOrNull { !html[it].isWhitespace() } ?: return html
        val quote = html[quoteIndex].takeIf { it == '"' || it.code == 39 } ?: return html
        val endQuote = html.indexOf(quote, quoteIndex + 1)
        if (endQuote < 0) return html
        val packed = html.substring(quoteIndex + 1, endQuote)

        return runCatching {
            val uriDecoded = packed.decodeUri()
            String(Base64.decode(uriDecoded, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrDefault(html)
    }

    private fun decryptPlayerHtml(html: String): String {
        val payload = DiziBoxPlayerParser.encryptedPayload(html) ?: return html
        return runCatching {
            CryptoJS.decrypt(payload.password, payload.ciphertext)
        }.getOrDefault(html)
    }

    /**
     * M3U8 linkini oynatıcıya verir. db5.*.xyz gibi uçlardaki bölümler ".png"
     * uzantısıyla maskelenmiş TS segmentleri içerebilir; ExoPlayer/HLS segmenti
     * playlist'teki URI'den uzantıya bakmadan çeker ve aşağıdaki başlıkları tüm
     * isteklere uygular. İmzalı segment URI'larını eklenti tarafında yeniden
     * yazmak imzayı bozabileceği için bilerek dokunmuyoruz.
     */
    private suspend fun emitHlsLink(
        url: String,
        referer: String,
        linkName: String = name,
        quality: Int = Qualities.Unknown.value,
        callback: (ExtractorLink) -> Unit,
    ) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = linkName,
                url = url,
                type = ExtractorLinkType.M3U8,
            ) {
                headers = buildMap {
                    put("Referer", referer)
                    put("User-Agent", playerUserAgent)
                    originOf(referer)?.let { put("Origin", it) }
                }
                this.quality = quality
            },
        )
    }

    /**
     * MolyStream'in `/embed/sheila/{id}` uç noktası her zaman embed sayfası
     * döndürmüyor: bağımsız çözücü fikstürleri hem HLS master listesi hem de düz
     * HTTPS URL yanıtını kapsıyor. Yanıt gövdesini kontrol etmeden kaynak
     * yayınlanmaz; düz URL gelirse hedef de aynı Referer ile HLS diye doğrulanır.
     */
    private data class VerifiedPlaylist(val url: String, val body: String)

    private suspend fun verifyMolyStream(url: String, referer: String): VerifiedPlaylist? {
        return try {
            val response = app.get(
                url,
                headers = playerRequestHeaders(referer),
                referer = referer,
            ).text
            when {
                DiziBoxPlayerParser.isHlsManifest(response) -> VerifiedPlaylist(url, response)
                else -> {
                    val target = DiziBoxPlayerParser.plainHttpsTarget(response) ?: return null
                    val targetResponse = app.get(
                        target,
                        headers = playerRequestHeaders(referer),
                        referer = referer,
                    ).text
                    targetResponse
                        .takeIf { DiziBoxPlayerParser.isHlsManifest(it) }
                        ?.let { VerifiedPlaylist(target, it) }
                }
            }
        } catch (exception: Exception) {
            Log.d("DZBX", "MolyStream source verification failed: ${exception.message}")
            null
        }
    }

    /**
     * Master listesindeki HER varyantı gerçek çözünürlüğüyle ayrı link olarak
     * üretir; varyant yoksa tek M3U8 linkiyle devam eder (kaynak kaybolmaz).
     */
    private suspend fun emitPlaylist(
        body: String,
        playlistUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val variants = DiziBoxPlayerParser.hlsVariants(body, playlistUrl)
        if (variants.isEmpty()) {
            emitHlsLink(playlistUrl, referer, callback = callback)
            return
        }
        variants.forEach { variant ->
            emitHlsLink(
                url = variant.url,
                referer = playlistUrl,
                linkName = variant.height.takeIf { it > 0 }?.let { "$name - ${it}p" } ?: name,
                quality = variant.height.takeIf { it > 0 } ?: Qualities.Unknown.value,
                callback = callback,
            )
        }
    }

    private suspend fun iframeDecode(
        referer: String,
        iframe: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        visited: MutableSet<String>,
        depth: Int = 0,
    ): Boolean {
        if (depth > 4) return false
        val resolvedUrl = DiziBoxPlayerParser.resolveUrl(iframe, referer) ?: return false
        if (!visited.add(resolvedUrl)) return false

        if (!DiziBoxPlayerParser.isSupportedPlayerUrl(resolvedUrl, mainUrl)) {
            return loadExtractor(resolvedUrl, referer, subtitleCallback, callback)
        }

        val playerCandidates = (listOf(resolvedUrl) +
            listOfNotNull(DiziBoxPlayerParser.molyStreamPlayerPageUrl(resolvedUrl))).distinct()

        // MolyStream embed sayfası bazen king.php'yi, bazen DiziBox ana adresini Referer
        // bekliyor. Önce mevcut üst sayfa, olmazsa ana adres denenir.
        val refererCandidates = if (isDiziBoxHost(resolvedUrl)) listOf(referer) else listOf(referer, mainUrl).distinct()
        for (playerReferer in refererCandidates) {
            for ((candidateIndex, candidateUrl) in playerCandidates.withIndex()) {
                if (candidateIndex > 0 && !visited.add(candidateUrl)) continue

                // king/moly/haydi sarmalayıcıları wmode=opaque enjeksiyonu bekliyor.
                val playerUrl = DiziBoxPlayerParser.withWOpaque(candidateUrl)

                val playerHtml = try {
                    if (isDiziBoxHost(playerUrl)) {
                        app.get(
                            playerUrl,
                            headers = playerRequestHeaders(playerReferer),
                            referer = playerReferer,
                            cookies = playerCookies,
                            interceptor = interceptor,
                        ).text
                    } else {
                        // Preserve the actual parent page as Referer. In the observed
                        // King -> MolyStream chain this is king.php, not the site root.
                        app.get(
                            playerUrl,
                            headers = playerRequestHeaders(playerReferer),
                            referer = playerReferer,
                        ).text
                    }
                } catch (exception: Exception) {
                    Log.d("DZBX", "Player request failed: ${exception.message}")
                    if (candidateIndex < playerCandidates.lastIndex) continue
                    break
                }

                // Doğrudan `/embed/sheila/{id}` iframe'i HLS master listesi döndürebiliyor;
                // listedeki tüm çözünürlük varyantları ayrı kaynak olarak üretilir.
                if (DiziBoxPlayerParser.isHlsManifest(playerHtml)) {
                    emitPlaylist(playerHtml, playerUrl, playerUrl, callback)
                    return true
                }

                // Bazı MolyStream yanıtları playlist yerine düz HTTPS URL verir; hedefi
                // aynı üst Referer ile HLS diye doğrula ve varyantlarını yay.
                val directTarget = DiziBoxPlayerParser.plainHttpsTarget(playerHtml)
                if (DiziBoxPlayerParser.isMolyStreamHost(playerUrl) && directTarget != null) {
                    val playlist = verifyMolyStream(directTarget, playerReferer)
                    if (playlist != null) {
                        emitPlaylist(playlist.body, playlist.url, playerReferer, callback)
                        return true
                    }
                }

                val packedHtml = if (
                    playerUrl.contains("/player/moly/moly.php") ||
                    playerUrl.contains("/player/haydi.php")
                ) decodePackedPlayerHtml(playerHtml) else playerHtml
                val decodedPlayerHtml = decryptPlayerHtml(packedHtml)
                val playerDocument = Jsoup.parse(decodedPlayerHtml, playerUrl)
                var extracted = false

                DiziBoxPlayerParser.subtitleTracks(decodedPlayerHtml).forEach { track ->
                    val subtitleUrl = DiziBoxPlayerParser.resolveUrl(track.url, playerUrl) ?: return@forEach
                    subtitleCallback.invoke(SubtitleFile(track.label, subtitleUrl))
                }

                DiziBoxPlayerParser.mediaSources(decodedPlayerHtml).forEach { source ->
                    val mediaUrl = DiziBoxPlayerParser.resolveUrl(source.url, playerUrl) ?: return@forEach
                    val sourceReferer = playerUrl

                    // sheila akışı doğrulanır ve master varyantlarının tamamı yayılır.
                    if (DiziBoxPlayerParser.isMolyStreamStreamUrl(mediaUrl)) {
                        val playlist = verifyMolyStream(mediaUrl, sourceReferer) ?: return@forEach
                        emitPlaylist(playlist.body, playlist.url, sourceReferer, callback)
                        extracted = true
                        return@forEach
                    }

                    val linkName = source.label.takeIf { it.isNotBlank() }?.let { "$name - $it" } ?: name
                    val linkQuality = source.label.takeIf { it.isNotBlank() }
                        ?.let { getQualityFromName(it) } ?: Qualities.Unknown.value

                    when {
                        DiziBoxPlayerParser.isHls(source) -> {
                            emitHlsLink(mediaUrl, sourceReferer, linkName, linkQuality, callback)
                            extracted = true
                        }
                        DiziBoxPlayerParser.isMp4(source) -> {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = linkName,
                                    url = mediaUrl,
                                    type = ExtractorLinkType.VIDEO,
                                ) {
                                    headers = mapOf(
                                        "Referer" to sourceReferer,
                                        "User-Agent" to playerUserAgent,
                                    )
                                    quality = linkQuality
                                },
                            )
                            extracted = true
                        }
                        else -> {
                            extracted = loadExtractor(mediaUrl, playerUrl, subtitleCallback, callback) || extracted
                        }
                    }
                }

                DiziBoxPlayerParser.nestedIframes(playerDocument, playerUrl).forEach { nestedFrame ->
                    if (nestedFrame == playerUrl) return@forEach
                    val found = if (DiziBoxPlayerParser.isSupportedPlayerUrl(nestedFrame, mainUrl)) {
                        iframeDecode(playerUrl, nestedFrame, subtitleCallback, callback, visited, depth + 1)
                    } else {
                        loadExtractor(nestedFrame, playerUrl, subtitleCallback, callback)
                    }
                    extracted = found || extracted
                }

                if (extracted) return true
            }
        }

        return loadExtractor(resolvedUrl, referer, subtitleCallback, callback)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d("DZBX", "data » $data")
        val document = try {
            app.get(
                data,
                cookies = playerCookies,
                interceptor = interceptor,
            ).document
        } catch (exception: Exception) {
            Log.d("DZBX", "Episode page failed: ${exception.message}")
            return false
        }

        // Ana sayfa + araç çubuğundaki alternatif oynatıcı sayfaları (sınırlı tut).
        val pageUrls = (listOf(data) + DiziBoxPlayerParser.alternatePlayerPages(document, data))
            .distinct()
            .take(9)
        val visitedPages = mutableSetOf<String>()
        val visitedFrames = mutableSetOf<String>()
        var emittedLinks = 0
        val linkCallback: (ExtractorLink) -> Unit = { link ->
            emittedLinks += 1
            callback(link)
        }

        pageUrls.forEach { pageUrl ->
            if (!visitedPages.add(pageUrl)) return@forEach
            val pageDocument = if (pageUrl == data) {
                document
            } else {
                try {
                    app.get(
                        pageUrl,
                        referer = data,
                        cookies = playerCookies,
                        interceptor = interceptor,
                    ).document
                } catch (exception: Exception) {
                    Log.d("DZBX", "Alternate player page failed: ${exception.message}")
                    return@forEach
                }
            }

            // Her oynatıcı denemesi ayrı korunur: bir kaynağın hatası diğerlerini düşürmez.
            DiziBoxPlayerParser.playerIframes(pageDocument, pageUrl).forEach { iframe ->
                Log.d("DZBX", "iframe » $iframe")
                runCatching {
                    iframeDecode(pageUrl, iframe, subtitleCallback, linkCallback, visitedFrames)
                }.onFailure { Log.d("DZBX", "iframe failed: ${it.message}") }
            }

            // Oynatıcı menüsü (DBX Pro / Moly+ / Odnok) adresleri ayrı sarmalayıcılar olabilir.
            DiziBoxPlayerParser.playerOptionUrls(pageDocument, pageUrl).forEach { option ->
                Log.d("DZBX", "player option » $option")
                runCatching {
                    iframeDecode(pageUrl, option, subtitleCallback, linkCallback, visitedFrames)
                }.onFailure { Log.d("DZBX", "player option failed: ${it.message}") }
            }
        }

        Log.d("DZBX", "Extracted links: $emittedLinks")
        return emittedLinks > 0
    }
}
