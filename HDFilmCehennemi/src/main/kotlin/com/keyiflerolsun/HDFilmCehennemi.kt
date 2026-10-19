// ! https://github.com/hexated/cloudstream-extensions-hexated/blob/master/Hdfilmcehennemi/src/main/kotlin/com/hexated/Hdfilmcehennemi.kt

package com.keyiflerolsun

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.syncproviders.SyncIdName
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.updateUrl
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

// TMDB genel (public) API anahtarı — IMDb ID → başlık çözümlemesi için.
private const val TMDB_API_KEY = "c4ffcab48dfaa7b41625ac13d61aec31"
private const val USER_AGENT   = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

class HDFilmCehennemi : MainAPI() {
    // ! 12 Ekim kaydı .biz'i canlı sanıyordu; 18 Ekim 2026'da .biz cPanel
    // ! varsayılan sayfası, hdfilmcehennemi7.com satışta. Eklentinin ayrıştırıcısıyla
    // ! uyumlu canlı site .nl (images/list/poster). Yedekler sırayla denenir; cPanel,
    // ! satış sayfası ve WordPress klonu sabitlenmez.
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    private val domainCandidates      = listOf(
        "https://www.hdfilmcehennemi.nl",
        "https://hdfilmcehennemi.nl",
        "https://www.hdfilmcehennemi.la",
        "https://hdfilmcehennemi.la",
        "https://www.hdfilmcehennemi.com",
        "https://www.hdfilmcehennemi.com.tr",
        "https://www.hdfilmcehennemi.biz",
        "https://hdfilmcehennemi.biz",
        "https://hdfilmcehennemi7.com",
    )
    private var domainResolved        = false
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    // ! IMDb ID ile doğrudan içerik açma (Anizium ile aynı davranış).
    override val supportedSyncNames   = setOf(SyncIdName.Imdb)

    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 150L  // ? 0.15 saniye
    override var sequentialMainPageScrollDelay = 150L  // ? 0.15 saniye

    // ! CloudFlare v2
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())

            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }

            return response
        }
    }

    // ! Yollar göreli: domain sonradan çözülse de istek doğru host'a gider.
    override val mainPage = mainPageOf(
        "/load/page/sayfano/home/"                                       to "Yeni Eklenen Filmler",
        //"/load/page/sayfano/categories/nette-ilk-filmler/"               to "Nette İlk Filmler",
        "/load/page/sayfano/home-series/"                                to "Yeni Eklenen Diziler",
        "/load/page/sayfano/categories/tavsiye-filmler-izle3/"           to "Tavsiye Filmler",
        "/load/page/sayfano/imdb7/"                                      to "IMDB 7+ Filmler",
        "/load/page/sayfano/mostCommented/"                              to "En Çok Yorumlananlar",
        "/load/page/sayfano/mostLiked/"                                  to "En Çok Beğenilenler",
        //"${mainUrl}/load/page/sayfano/genres/aile-filmleri-izleyin-6/"             to "Aile Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/aksiyon-filmleri-izleyin-5/"          to "Aksiyon Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/animasyon-filmlerini-izleyin-5/"      to "Animasyon Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/belgesel-filmlerini-izle-1/"          to "Belgesel Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/bilim-kurgu-filmlerini-izleyin-3/"    to "Bilim Kurgu Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/komedi-filmlerini-izleyin-1/"         to "Komedi Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/korku-filmlerini-izle-4/"             to "Korku Filmleri",
        //"${mainUrl}/load/page/sayfano/genres/romantik-filmleri-izle-2/"            to "Romantik Filmleri"
    )

    private suspend fun resolveDomain() {
        if (domainResolved) return
        domainResolved = true
        for (candidate in domainCandidates) {
            try {
                val res = app.get(
                    candidate,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$candidate/"
                    ),
                    interceptor = interceptor,
                    cacheTime = 60
                )
                if (!res.isSuccessful) continue
                val pinned = originOf(res.url) ?: continue
                if (!siteLooksAlive(
                        pinned,
                        res.text,
                        "hdfilmcehennemi",
                        listOf("images/list/poster", "alternative-link", "load/page", "post-info")
                    )
                ) continue
                mainUrl = pinned
                Log.d("HDCH", "domain sabitlendi: $mainUrl")
                return
            } catch (e: Exception) {
                Log.d("HDCH", "domain adayi olmadi: $candidate (${e.message})")
            }
        }
        Log.w("HDCH", "hicbir domain ayakta degil, varsayilan kaldi: $mainUrl")
    }

    /** Eski domaindeki kayıtlı içerik/bölüm adresini çözülen host'a taşır. */
    private fun migrateUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains("hdfilmcehennemi")) return url
        return try {
            updateUrl(url)
        } catch (e: Exception) {
            url
        }
    }

    private fun originOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Altyazı adresini mutlaklaştırır ve sonundaki eğik çizgiyi atar.
     * ! O eğik çizgi .vtt dosyasını 404 yaptırıyordu.
     */
    private fun subtitleUrlOf(file: String): String? {
        val value = file.replace("\\/", "/").trim().trim('"', '\'')
        if (value.isBlank()) return null
        val absolute = when {
            value.startsWith("http") -> value
            value.startsWith("//") -> "https:$value"
            else -> absolutizeUrl(value, mainUrl) ?: return null
        }
        return absolute.trimEnd('/')
    }

    /** Göreli adresi (index.m3u8, ../x/y.m3u8) tam adrese çevirir. */
    private fun absolutizeUrl(raw: String, baseUrl: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
        return try {
            java.net.URI(baseUrl).resolve(value).toString()
        } catch (e: Exception) {
            null
        }
    }

    // === Oynatıcı düzeltmeleri: kör M3U8 + 4 delik ===

    /**
     * Akış tipi. Bilinen uzantılar için ExtractorLinkType, bilinmeyen adresler için null.
     *
     * ! DİKKAT — burası oynatmayı tamamen öldüren delikti:
     * ! CloudStream kütüphanesinde `val INFER_TYPE: ExtractorLinkType? = null`
     * ! (ExtractorApi.kt:447) tanımlı. Yani `INFER_TYPE!!` yazmak DERLEME HATASI
     * ! vermiyor ama ÇALIŞMA ANINDA her seferinde NullPointerException fırlatıyor.
     * ! O NPE eklentinin kendi try/catch'inde yutulduğu için callback hiç
     * ! çağrılmıyor, uygulama "0 kaynak" gösteriyor ve oynatıcı hata veriyordu.
     * ! Uzantısız adresler (master.txt varyantları, JSON'dan çözülen CDN
     * ! adresleri, token'lı /play) tam olarak bu dala düşüyordu.
     * ! Doğrusu: null geçmek. `newExtractorLink` type parametresi zaten
     * ! nullable; null verildiğinde kütüphane tipi adresten çıkarır.
     */
    private fun linkTypeFor(url: String): ExtractorLinkType? {
        val lower = url.lowercase().substringBefore("?")
        return when {
            lower.contains(".m3u8") || lower.contains("master.txt") || lower.contains(".m3u") -> ExtractorLinkType.M3U8
            lower.contains(".mp4") || lower.contains(".mkv") || lower.contains(".webm") || lower.contains(".mov") || lower.contains(".avi") -> ExtractorLinkType.VIDEO
            else -> null
        }
    }

    /**
     * Ham .ts SEGMENT adresi mi? Yalnızca gerçek dosya uzantısına bakar.
     * ! `contains(".ts")` çok genişti: "cdn.vod.ts.net/..." ve "lists.tsx/..."
     * ! gibi geçerli adresleri de eliyordu. Sorgu dizesi de hesaba katılır.
     */
    private fun isTsSegment(url: String): Boolean =
        url.substringBefore("?").trim().lowercase().endsWith(".ts")

    private fun normalizeStreamUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var cleaned = raw.trim().replace("\\/", "/")
        // JSON içinde tırnaklı değer olabilir, regex ile ilk geçerli http adresini çıkar
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val match = urlRegex.find(cleaned) ?: return null
        var url = match.value.trimEnd(',', '"', 39.toChar(), ')', ']', '}', ';').replace("\\", "")
        // substringAfter("https") hatası: httpshttp:// → düzelt
        if (url.startsWith("httpshttp://")) url = "http://" + url.removePrefix("httpshttp://")
        if (url.startsWith("httpshttps://")) url = "https://" + url.removePrefix("httpshttps://")
        // genel çift şema: httpshttps, httpshttp
        if (url.contains("httpshttp://")) {
            val idx = url.indexOf("http://")
            if (idx > 0) url = url.substring(idx)
        }
        if (url.contains("httpshttps://")) {
            val idx = url.lastIndexOf("https://")
            if (idx > 0) url = url.substring(idx)
        }
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            if (host.isBlank()) return null
            if (!url.startsWith("http://") && !url.startsWith("https://")) return null
            url
        } catch (e: Exception) {
            Log.w("HDCH", "normalizeStreamUrl bozuk: $raw -> $url (${e.message})")
            null
        }
    }

    /**
     * İmzalı master HLS adresini aynen oynatıcıya aktarır.
     *
     * Master listesini burada açmak, göreli varyantlara geçerken base URL'deki
     * query token'ını düşürür ve EXT-X-MEDIA ses gruplarını yok eder. HLS
     * oynatıcı zaten master listesini çözebildiğinden ayrı varyant üretmiyoruz.
     */
    private suspend fun resolveStreamUrl(
        streamUrl: String,
        headers: Map<String, String>
    ): List<Pair<String, Map<String, String>>>? {
        val path = streamUrl.substringBefore('?').lowercase()
        if (!path.endsWith(".m3u8") && !path.endsWith("master.txt")) return null
        return listOf(streamUrl to headers)
    }

    private fun siteLooksAlive(finalUrl: String, body: String, marker: String, required: List<String>): Boolean {
        val host = runCatching { java.net.URI(finalUrl).host?.lowercase().orEmpty() }.getOrDefault("")
        if (!host.contains(marker)) return false
        val lower = body.lowercase()
        val dead = listOf(
            "yasal nedenlerle",
            "unavailable for legal reasons",
            "domain for sale",
            "buy this domain",
            "is for sale",
            "godaddy",
            "default web site page",
            "cgi-sys/defaultwebpage",
            "wp-content",
            "wp-login.php",
        )
        if (dead.any { lower.contains(it) }) return false
        if (body.length < 300) return false
        return required.any { lower.contains(it) }
    }

    private fun absoluteUrl(path: String): String {
        if (path.startsWith("http://") || path.startsWith("https://")) return path
        return mainUrl.trimEnd('/') + "/" + path.trimStart('/')
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        resolveDomain()
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        val url = absoluteUrl(request.data.replace("sayfano", page.toString()))
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0",
            "Accept" to "*/*", "X-Requested-With" to "fetch"
        )
        val doc = app.get(url, headers = headers, referer = mainUrl, interceptor = interceptor)
        val home: List<SearchResponse>?
        if (!doc.toString().contains("Sayfa Bulunamadı")) {
            val aa: HDFC = objectMapper.readValue(doc.toString())
            val document = Jsoup.parse(aa.html)

            home = document.select("a").mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, home)
        }
        return newHomePageResponse(request.name, emptyList())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.attr("title")
        val href = fixUrlNull(this.attr("href"))?.let { migrateUrl(it) } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        resolveDomain()
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val response      = app.get(
            "${mainUrl}/search?q=$encoded",
            headers = mapOf(
                "User-Agent"       to USER_AGENT,
                "X-Requested-With" to "fetch",
                "Content-Type"     to "application/json"
            ),
            referer = "${mainUrl}/"
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()

        response.results.forEach { resultHtml ->
            val document = Jsoup.parse(resultHtml)

            val title = document.selectFirst("h4.title")?.text()?.trim()
                ?: document.selectFirst(".title")?.text()?.trim()
                ?: return@forEach
            val anchor = document.selectFirst("a.search-result") ?: document.selectFirst("a")
            val href   = fixUrlNull(anchor?.attr("href"))?.let { migrateUrl(it) } ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src"))
                ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))

            // ! ÖNEMLİ: Diziler /dizi/... adresinde. Önceden TÜM sonuçlar
            // ! TvType.Movie dönüyordu; uygulama dizi aramasında bu kayıtları
            // ! "film" sanıp eliyordu (The Mentalist / Supernatural vb.).
            val isSeries = href.contains("/dizi/")
            if (isSeries) {
                searchResults.add(
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                    }
                )
            } else {
                searchResults.add(
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl?.replace("/thumb/", "/list/")
                    }
                )
            }
        }

        return searchResults
    }

    // ── IMDb ID → içerik sayfası ─────────────────────────────────────────────
    // Uygulama "video izle" akışında önce getLoadUrl(Imdb, "tt1234567") çağırır.
    // IMDb kimliği TMDB üzerinden başlığa çevrilir, site aramasıyla eşleştirilir
    // ve doğrudan içerik sayfası döndürülür. Böylece başlık yazım farklarından
    // (Türkçe/İngilizce ad) kaynaklanan "veri gelmiyor" durumu engellenir.
    private data class TmdbItem(
        @JsonProperty("title")          val title: String?         = null,
        @JsonProperty("name")           val name: String?          = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("original_name")  val originalName: String?  = null
    )

    private data class TmdbFind(
        @JsonProperty("movie_results") val movieResults: List<TmdbItem>? = null,
        @JsonProperty("tv_results")    val tvResults: List<TmdbItem>?    = null
    )

    private suspend fun tmdbTitlesByImdb(imdbId: String): List<String> {
        val body = app.get(
            "https://api.themoviedb.org/3/find/$imdbId" +
                "?api_key=$TMDB_API_KEY&external_source=imdb_id&language=tr-TR"
        ).text
        val parsed  = AppUtils.tryParseJson<TmdbFind>(body) ?: return emptyList()
        val entries = parsed.movieResults.orEmpty() + parsed.tvResults.orEmpty()
        return entries
            .flatMap { listOfNotNull(it.title, it.originalTitle, it.name, it.originalName) }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    private fun normalizeTitle(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun titleScore(a: String, b: String): Double {
        val x = normalizeTitle(a)
        val y = normalizeTitle(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        if (x.contains(y) || y.contains(x)) return 0.85
        return 0.0
    }

    override suspend fun getLoadUrl(name: SyncIdName, id: String): String? {
        if (name != SyncIdName.Imdb || id.isBlank()) return null
        val titles = try { tmdbTitlesByImdb(id) } catch (e: Exception) { emptyList() }
        for (title in titles) {
            val results = try { search(title) } catch (e: Exception) { emptyList() }
            val best = results
                .map { it to titleScore(title, it.name) }
                .filter { it.second >= 0.7 }
                .maxByOrNull { it.second } ?: continue
            return best.first.url
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse? {
        resolveDomain()
        val pageUrl = migrateUrl(url)
        val document = app.get(pageUrl, interceptor = interceptor).document

        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        // ! `selectFirst("strong")!!` NPE fırlatıyordu; markup değişince (ya da
        // ! "strong" içermeyen bir oyuncu bağlantısında) load() tümden patlıyor
        // ! ve kullanıcı detay sayfasını hiç göremiyordu. Adı olmayan oyuncu atlanır.
        val actors      = document.select("div.post-info-cast a").mapNotNull {
            val actorName = it.selectFirst("strong")?.text()?.trim()?.takeIf { n -> n.isNotEmpty() }
                ?: return@mapNotNull null
            Actor(actorName, it.select("img").attr("data-src"))
        }

        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
                val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
                val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href"))?.let { h -> migrateUrl(h) } ?: return@mapNotNull null
                val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

                newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) {
                    this.posterUrl = recPosterUrl
                }
            }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            Log.d("HDCH", "Trailer: $trailer")
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href"))?.let { h -> migrateUrl(h) } ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+)\. ?Bölüm""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+)\. ?Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                }
            }

            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            Log.d("HDCH", "Trailer: $trailer")
            newMovieLoadResponse(title, pageUrl, TvType.Movie, pageUrl) {
                this.posterUrl       = poster
                this.year            = year
                this.plot            = description
                this.tags            = tags
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    data class DecOp(val name: String, val rotShift: Int = 0)

    private fun decryptLocalUrl(unpackedScript: String): String? {
        try {
            // 1. Extract parts array
            val partsMatch = """\(\[\s*((?:['"][^'"]+['"]\s*,?\s*)+)\]\)""".toRegex().find(unpackedScript)
            val parts = partsMatch?.groupValues?.get(1)?.split(",")?.map { 
                it.trim().trim('\'', '"').replace("\\/", "/") 
            } ?: return null

            // 2. Extract magicNum and magicOffset
            val moduloMatch = """(\d+)\s*%\s*\(i\s*\+\s*(\d+)\)""".toRegex().find(unpackedScript)
            val magicNum = moduloMatch?.groupValues?.get(1)?.toLongOrNull() ?: 399756995L
            val magicOffset = moduloMatch?.groupValues?.get(2)?.toIntOrNull() ?: 5

            // 3. Isolate function body
            val funcBody = unpackedScript.substringAfter("function dc_").substringBefore("function d1x")

            // 4. Extract operations and their shift values in execution order
            val operations = mutableListOf<Pair<Int, DecOp>>()

            var index = funcBody.indexOf("atob(")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("atob")))
                index = funcBody.indexOf("atob(", index + 1)
            }

            index = funcBody.indexOf("reverse")
            while (index >= 0) {
                operations.add(Pair(index, DecOp("reverse")))
                index = funcBody.indexOf("reverse", index + 1)
            }

            index = funcBody.indexOf("replace")
            while (index >= 0) {
                val block = funcBody.substring(index, minOf(index + 300, funcBody.length))
                var shift = 13
                val rotShiftMatch = """charCodeAt\(0\)\s*\+\s*(\d+)""".toRegex().find(block)
                if (rotShiftMatch != null) {
                    shift = rotShiftMatch.groupValues[1].toInt()
                } else {
                    val rotShiftMatch2 = """o\s*-\s*base\s*([+-])\s*(\d+)""".toRegex().find(block)
                    if (rotShiftMatch2 != null) {
                        val sign = rotShiftMatch2.groupValues[1]
                        val num = rotShiftMatch2.groupValues[2].toInt()
                        shift = if (sign == "-") (26 - num) % 26 else num
                    }
                }
                operations.add(Pair(index, DecOp("rot", shift)))
                index = funcBody.indexOf("replace", index + 1)
            }

            operations.sortBy { it.first }

            var result = parts.joinToString("")

            // Execute operations in order
            for (op in operations) {
                val action = op.second
                when (action.name) {
                    "reverse" -> {
                        result = result.reversed()
                    }
                    "atob" -> {
                        var paddedResult = result
                        while (paddedResult.length % 4 != 0) {
                            paddedResult += "="
                        }
                        result = String(android.util.Base64.decode(paddedResult, android.util.Base64.NO_WRAP), Charsets.ISO_8859_1)
                    }
                    "rot" -> {
                        val rotShift = action.rotShift
                        val rot = StringBuilder()
                        for (c in result) {
                            if (c in 'a'..'z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else if (c in 'A'..'Z') {
                                val shifted = c.code + rotShift
                                rot.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                            } else {
                                rot.append(c)
                            }
                        }
                        result = rot.toString()
                    }
                }
            }

            // 5. Modulo Unmix
            val unmix = StringBuilder()
            for (i in result.indices) {
                val charCode = result[i].code.toLong()
                val decryptedCode = (charCode - (magicNum % (i + magicOffset)) + 256) % 256
                unmix.append(decryptedCode.toInt().toChar())
            }

            return unmix.toString()

        } catch (e: Exception) {
            Log.e("HDCH", "decryptLocalUrl Error: ${e.message}")
            return null
        }
    }

    /** CloseLoad encodes its playable stream URL as a base64 token in the embed HTML. */
    private fun extractBase64StreamUrl(html: String): String? {
        val candidates = Regex("""aHR0[A-Za-z0-9+/_=-]{12,}""").findAll(html)
        for (match in candidates) {
            try {
                var encoded = match.value.trimEnd('=', ';', ',')
                while (encoded.length % 4 != 0) encoded += "="
                val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT), Charsets.UTF_8)
                    .replace("\\/", "/").trim()
                if ((decoded.startsWith("http://") || decoded.startsWith("https://")) &&
                    runCatching { java.net.URI(decoded).host }.getOrNull() != null) return decoded
            } catch (_: Exception) {
                // Ignore unrelated base64 data and continue.
            }
        }
        return null
    }

    /** RapidVid/Rapidrame JWPlayer's av('...') URL obfuscation used by the host. */
    private fun extractRapidStreamUrl(html: String): String? {
        val encoded = Regex("""av\(['"]([^'"]+)['"]\)""").find(html)?.groupValues?.get(1)
            ?: return null
        return try {
            fun decodeBase64(value: String): String {
                var padded = value
                while (padded.length % 4 != 0) padded += "="
                val bytes = runCatching { android.util.Base64.decode(padded, android.util.Base64.DEFAULT) }
                    .getOrElse { android.util.Base64.decode(padded, android.util.Base64.URL_SAFE) }
                return String(bytes, Charsets.ISO_8859_1)
            }
            val first = decodeBase64(encoded.reversed())
            val key = "K9L"
            val shifted = buildString {
                first.forEachIndexed { index, char ->
                    val offset = key[index % key.length].code % 5 + 1
                    append((char.code - offset).toChar())
                }
            }
            val decoded = decodeBase64(shifted).replace("\\/", "/").trim()
            decoded.takeIf { (it.startsWith("http://") || it.startsWith("https://")) &&
                runCatching { java.net.URI(it).host }.getOrNull() != null }
        } catch (e: Exception) {
            Log.w("HDCH", "Rapidrame URL çözülemedi: ${e.message}")
            null
        }
    }

    /**
     * /rplayer/ sayfasındaki paketli kaynağı çözer.
     *
     * ! Sayfa JW Player kurar; gerçek m3u8 adresi "Dean Edwards packer" ile
     * ! paketlenmiş bir JS bloğunun içindedir. RplayerDecoder bloğu açar,
     * ! işlemleri (atob / reverse / harf-kaydırma) KAYNAK SIRASINA göre
     * ! uygular ve son adımdaki sayısal bayt kaydırmayı yapar. Tuzak
     * ! (decoy) fonksiyonlar sayıma katılmaz. Altyazılar aynı sayfada düz
     * ! (paketlenmemiş) "tracks:" JSON'undadır.
     */
    private suspend fun extractRplayerSource(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val html = fetchText(url, "$mainUrl/") ?: run {
                Log.e("HDCH", "rplayer sayfasi alinamadi: $url")
                return
            }

            // ! TANI: sayfadaki paketli script'in başı ve tracks bloğu.
            val packedAt = html.indexOf("eval(function(p,a,c,k,e,d)")
            Log.d(
                "HDCH_TANI",
                "rplayer $url paketli script: " +
                    (if (packedAt >= 0) html.substring(packedAt, minOf(packedAt + 300, html.length)) else "<yok>")
            )
            Log.d("HDCH_TANI", "rplayer tracks: " + html.substringAfter("tracks:", "").take(300).replace("\n", " "))

            // ! Altyazılar: düz tracks JSON'undan.
            RplayerDecoder.extractTracks(html).forEach { track ->
                val subUrl = subtitleUrlOf(track.file) ?: return@forEach
                val label = track.label.ifBlank { track.language.ifBlank { "Türkçe" } }
                subtitleCallback(newSubtitleFile(label, subUrl))
            }

            // ! Yeni çözücü; eski yöntem yedek olarak bırakıldı.
            val streamUrl = RplayerDecoder.extractStreamUrl(html)
                ?: decryptLocalUrl(getAndUnpack(html))
            Log.d("HDCH_TANI", "rplayer cozulen adres: $streamUrl")
            if (streamUrl.isNullOrBlank()) {
                Log.e("HDCH", "rplayer kaynagi cozulemedi: $url")
                return
            }

            // CDN imza kontrolü rplayer sayfasının Referer'ını bekliyor; Origin
            // ise sitenin kök origin'i olmalı. Bu başlıklar master playlist'teki
            // token ile birlikte doğrudan video isteklerine taşınır.
            val playerOrigin = originOf(url) ?: mainUrl
            val embedHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to url,
                "Origin" to playerOrigin
            )
            val variants = resolveStreamUrl(streamUrl, embedHeaders)
            if (!variants.isNullOrEmpty()) {
                variants.take(3).forEachIndexed { index, (variantUrl, variantHeaders) ->
                    val norm = normalizeStreamUrl(variantUrl) ?: return@forEachIndexed
                    // ! Not: "quality" adı link kalitesi değişkeniyle çakışırdı
                    // ! (ExtractorLink.quality ataması yerel val'e düşüyordu).
                    val q = when (index) {
                        0 -> Qualities.P1080.value
                        1 -> Qualities.P720.value
                        else -> Qualities.P480.value
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = source,
                            name = if (variants.size > 1) "$source - ${index + 1}" else source,
                            url = norm,
                            type = linkTypeFor(norm)
                        ) {
                            quality = q
                            this.referer = url
                            headers = variantHeaders
                        }
                    )
                    Log.d("HDCH", "link [$index] » $norm type=${linkTypeFor(norm)}")
                }
            } else {
                val norm = normalizeStreamUrl(streamUrl) ?: return
                callback.invoke(
                    newExtractorLink(source = source, name = source, url = norm, type = linkTypeFor(norm)) {
                        quality = Qualities.P1080.value
                        this.referer = url
                        headers = embedHeaders
                    }
                )
                Log.d("HDCH", "link » $norm type=${linkTypeFor(norm)}")
            }
        } catch (e: Exception) {
            Log.e("HDCH", "extractRplayerSource error: ${e.message}")
        }
    }

    private suspend fun extractMobiOrCloseLoad(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // ! Origin/Referer embed host'una sabitlenir. Eksik kalınca uygulama
            // ! onu playmix.uno yapıyor ve yayın 5004 ile düşüyordu.
            val origin = originOf(url) ?: mainUrl
            val headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$origin/",
                "Origin" to origin
            )
            val html = app.get(url, headers = headers, interceptor = interceptor).text

            // CloseLoad and Rapidrame each use a different obfuscated source format.
            var realUrl = extractRapidStreamUrl(html) ?: extractBase64StreamUrl(html)

            // JSON-LD contentUrl fallback
            if (realUrl.isNullOrBlank()) {
                val ldJsonMatch = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(html)
                realUrl = ldJsonMatch?.groupValues?.get(1)?.replace("\\/", "/")
            }

            // 2. Direct master.txt / m3u8
            if (realUrl.isNullOrBlank()) {
                val txtMatch = """https?://[^\s"']+/master\.txt[^\s"']*""".toRegex().find(html)?.value
                    ?: """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex().find(html)?.value
                realUrl = txtMatch
            }

            // 3. Some Rapidrame/CloseLoad embeds pack their player script first.
            if (realUrl.isNullOrBlank() && (html.contains("function dc_") || html.contains("eval(function"))) {
                val unpacked = getAndUnpack(html)
                realUrl = extractRapidStreamUrl(unpacked)
                    ?: extractBase64StreamUrl(unpacked)
                    ?: decryptLocalUrl(unpacked)
            }

            val normalizedReal = normalizeStreamUrl(realUrl)
            if (!normalizedReal.isNullOrBlank()) {
                val variants = resolveStreamUrl(normalizedReal, headers)
                if (variants != null && variants.isNotEmpty()) {
                    variants.forEachIndexed { idx, pair ->
                        val (streamUrl, streamHeaders) = pair
                        val norm = normalizeStreamUrl(streamUrl) ?: return@forEachIndexed
                        if (norm.startsWith("httpshttp://") || isTsSegment(norm)) {
                            Log.w("HDCH", "çöp adres elendi: $norm")
                            return@forEachIndexed
                        }
                        val label = if (variants.size > 1) "$source - ${idx + 1}" else source
                        val q = when (idx) { 0 -> Qualities.P1080.value; 1 -> Qualities.P720.value; else -> Qualities.P480.value }
                        callback.invoke(
                            newExtractorLink(
                                source = source,
                                name = label,
                                url = norm,
                                type = linkTypeFor(norm)
                            ) {
                                quality = q
                                this.headers = streamHeaders
                            }
                        )
                        Log.d("HDCH", "link [${idx}] » $norm type=${linkTypeFor(norm)}")
                    }
                } else {
                    val norm = normalizedReal
                    if (norm.startsWith("httpshttp://") || isTsSegment(norm)) {
                        Log.w("HDCH", "çöp adres elendi: $norm")
                    } else {
                        callback.invoke(
                            newExtractorLink(
                                source = source,
                                name = source,
                                url = norm,
                                type = linkTypeFor(norm)
                            ) {
                                quality = Qualities.P1080.value
                                this.headers = headers
                            }
                        )
                        Log.d("HDCH", "link » $norm type=${linkTypeFor(norm)}")
                    }
                }
            } else if (!realUrl.isNullOrBlank()) {
                Log.w("HDCH", "normalizeStreamUrl bozuk, elendi: $realUrl")
            }

            // Parse subtitles if present in embed
            val subData = html.substringAfter("tracks: [", "").substringBefore("]")
            if (subData.isNotBlank()) {
                AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" || it.kind == "subtitles" }?.forEach {
                    val subFile = it.file ?: return@forEach
                    // ! Sonundaki "/" kaldırıldı: o eğik çizgi .vtt dosyasını 404 yaptırıyordu.
                    val subtitleUrl = subtitleUrlOf(subFile) ?: return@forEach
                    subtitleCallback(newSubtitleFile(it.language ?: "Türkçe", subtitleUrl))
                }
            }
        } catch (e: Exception) {
            Log.e("HDCH", "extractMobiOrCloseLoad error: ${e.message}")
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        val origin = originOf(url) ?: mainUrl
        val pageText = try {
            app.get(
                url,
                referer = "$origin/",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$origin/",
                    "Origin" to origin
                ),
                interceptor = interceptor
            ).text
        } catch (e: Exception) {
            return
        }
        val script = Jsoup.parse(pageText).select("script").find {
            val code = it.data()
            code.contains("sources:") || code.contains("jwSetup.sources") ||
                code.contains("av(") || code.contains("eval(function") || code.contains("function dc_")
        }?.data() ?: pageText.takeIf {
            it.contains("sources:") || it.contains("jwSetup.sources") || it.contains("av(") || it.contains("function dc_")
        }
        Log.d("HDCH", "source script found » ${script != null}")
        var decryptedUrl: String? = extractRapidStreamUrl(pageText) ?: extractBase64StreamUrl(pageText)
        if (decryptedUrl.isNullOrBlank() && script != null) {
            val unpackedScript = getAndUnpack(script)
            decryptedUrl = extractRapidStreamUrl(unpackedScript)
                ?: extractBase64StreamUrl(unpackedScript)
                ?: decryptLocalUrl(unpackedScript)
        }

        // Fallback: Check for contentUrl or master.txt or m3u8 in pageText
        if (decryptedUrl.isNullOrBlank()) {
            decryptedUrl = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(pageText)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: """https?://[^\s"']+/master\.txt[^\s"']*""".toRegex().find(pageText)?.value
                ?: """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex().find(pageText)?.value
        }
        if (decryptedUrl.isNullOrBlank()) return
        val rawStream = normalizeStreamUrl(decryptedUrl)
        if (rawStream.isNullOrBlank()) {
            Log.w("HDCH", "normalizeStreamUrl bozuk, elendi: $decryptedUrl")
            return
        }
        if (rawStream.startsWith("httpshttp://") || isTsSegment(rawStream)) {
            Log.w("HDCH", "çöp adres elendi: $rawStream")
            return
        }
        val embedHeaders = mapOf("User-Agent" to USER_AGENT, "Referer" to "$origin/", "Origin" to origin)
        val variants = resolveStreamUrl(rawStream, embedHeaders)
        val subData = pageText.substringAfter("tracks: [", "").substringBefore("]")
        if (subData.isNotBlank()) {
            AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" || it.kind == "subtitles" }?.forEach {
                val subFile = it.file ?: return@forEach
                val subtitleUrl = subtitleUrlOf(subFile) ?: return@forEach
                subtitleCallback(newSubtitleFile(it.language ?: "Türkçe", subtitleUrl))
            }
        }
        if (variants != null && variants.isNotEmpty()) {
            variants.forEachIndexed { idx, pair ->
                val (u, h) = pair
                val norm = normalizeStreamUrl(u) ?: return@forEachIndexed
                if (norm.startsWith("httpshttp://") || isTsSegment(norm)) {
                    Log.w("HDCH", "çöp varyant elendi: $norm")
                    return@forEachIndexed
                }
                val label = if (variants.size > 1) "$source - ${idx + 1}" else source
                val q = when (idx) { 0 -> Qualities.P1080.value; 1 -> Qualities.P720.value; else -> Qualities.P480.value }
                callback.invoke(
                    newExtractorLink(
                        source = source,
                        name = label,
                        url = norm,
                        type = linkTypeFor(norm)
                    ) {
                        headers = h
                        quality = q
                    }
                )
                Log.d("HDCH", "link [$idx] » $norm type=${linkTypeFor(norm)}")
            }
        } else {
            callback.invoke(
                newExtractorLink(
                    source = source,
                    name = source,
                    url = rawStream,
                    type = linkTypeFor(rawStream)
                ) {
                    headers = embedHeaders
                    quality = Qualities.Unknown.value
                }
            )
            Log.d("HDCH", "link » $rawStream type=${linkTypeFor(rawStream)}")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveDomain()
        val pageUrl = migrateUrl(data)
        Log.d("HDCH", "data » $pageUrl")
        val document = app.get(pageUrl, interceptor = interceptor).document
        var linkFound = false

        // ! Artık yalnızca eski "alternative-link" düğmesine bakılmıyor: kaynak
        // ! düğmesi bulunamayınca boş liste dönüyordu. Sayfadaki iframe, data-video
        // ! ve gömülü adresler de taranıyor; /video/{id}/ ile Rapidrame rplayer yolu deneniyor.
        // ! TANI: sayfadaki kaynak butonları ve data-video değerleri.
        document.select("div.alternative-links button.alternative-link").forEach { b ->
            Log.d("HDCH_TANI", "kaynak butonu: etiket=${b.text().trim().take(40)} data-video=${b.attr("data-video")}")
        }
        // ! TANI: iframe ve oynatıcıyla ilgili script blokları.
        document.select("iframe").take(6).forEach { f ->
            Log.d("HDCH_TANI", "iframe: src=${f.attr("src")} data-src=${f.attr("data-src")} class=${f.attr("class")}")
        }
        document.select("script").forEach { sc ->
            val code = sc.data()
            if (code.contains("sources:") || code.contains("jwplayer") || code.contains("jwSetup") ||
                code.contains("m3u8") || code.contains("master.txt") || code.contains("rplayer") ||
                code.contains("eval(function") || code.contains("function dc_") || code.contains("av(")
            ) {
                Log.d("HDCH_TANI", "script: " + code.replace("\n", " ").take(1200))
            }
        }
        val candidates = collectLinkCandidates(document, pageUrl)
        Log.d("HDCH", "aday kaynak: ${candidates.size}")
        Log.d("HDCH_TANI", "adaylar: " + candidates.joinToString(" | ") { "vid=${it.videoId} url=${it.url}" })
        val tried = mutableSetOf<String>()

        candidates.forEach { candidate ->
            var embedUrl: String? = null

            // 1) /video/{id}/ → iframe (eski alternative-link akışı + Rapidrame rplayer)
            val videoId = candidate.videoId
            if (!videoId.isNullOrBlank() && !videoId.contains("://")) {
                try {
                    val apiGet = app.get(
                        "${mainUrl}/video/$videoId/", interceptor = interceptor,
                        headers = mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "fetch",
                            "User-Agent" to USER_AGENT,
                            "Referer" to pageUrl
                        ),
                        referer = pageUrl
                    ).text
                    Log.d("HDCH", "Found videoID: $videoId")
                    // ! TANI: ham API yanıtının ilk 900 karakteri (yapıyı görmek için).
                    Log.d("HDCH_TANI", "video/$videoId/ yanit: " + apiGet.take(900).replace("\n", " "))
                    embedUrl = extractEmbedUrl(apiGet)
                    Log.d("HDCH_TANI", "video/$videoId/ embed: $embedUrl")
                    // ! Site bazen harici adres + ?rapidrame_id= veriyor; site-içi
                    // ! rplayer adresine çevriliyor ve sondaki / ekleniyor.
                    embedUrl = embedUrl?.let { toPlayerUrl(it) }
                } catch (e: Exception) {
                    Log.e("HDCH", "Error loading videoID $videoId: ${e.message}")
                }
            }

            // 2) Aday adresin kendisi zaten gömülü oynatıcı mı?
            if (embedUrl.isNullOrBlank()) embedUrl = candidate.url.takeIf { it.isNotBlank() }

            // 3) rplayer sayfası kendi içinde paketli kaynağı taşır; bir seviye
            //    daha çıkmaya gerek yok (içinde iframe yok, kaynak gizli JS'te).

            val target = embedUrl?.trim()
            if (target.isNullOrBlank() || target == "about:blank" || !target.startsWith("http")) return@forEach
            if (!tried.add(target)) return@forEach
            Log.d("HDCH", "${candidate.source} » $target")

            val source = candidate.source
            val wrapped: (ExtractorLink) -> Unit = { link ->
                linkFound = true
                Log.d("HDCH_TANI", "link » ${link.name} type=${link.type} url=${link.url}")
                callback(link)
            }
            val looksLikeEmbed = target.contains("mobi") || target.contains("closeload") ||
                target.contains("/embed/") || target.contains("rplayer") || target.contains("rapidrame") ||
                target.contains("rapidvid")

            // ! rplayer sayfası kendi paketli çözücüsüyle ele alınır.
            val isRplayer = target.contains("/rplayer/") || target.contains("/playerr/")
            if (isRplayer) {
                extractRplayerSource(source, target, subtitleCallback, wrapped)
            } else if (looksLikeEmbed) {
                extractMobiOrCloseLoad(source, target, subtitleCallback, wrapped)
            } else {
                invokeLocalSource(source, target, subtitleCallback, wrapped)
            }

            // ! İlk yol boş dönerse aynı adres diğer çözümleyiciyle de denenir.
            if (!linkFound) {
                if (isRplayer) {
                    extractMobiOrCloseLoad(source, target, subtitleCallback, wrapped)
                } else if (looksLikeEmbed) {
                    invokeLocalSource(source, target, subtitleCallback, wrapped)
                } else {
                    extractMobiOrCloseLoad(source, target, subtitleCallback, wrapped)
                }
            }
        }

        Log.d("HDCH", "linkFound » $linkFound")
        return linkFound
    }

    private data class LinkCandidate(val source: String, val url: String, val videoId: String? = null)

    /** Sayfadaki tüm kaynak adaylarını toplar: alternative-link düğmeleri, iframe'ler, data-video ve gömülü adresler. */
    private fun collectLinkCandidates(document: org.jsoup.nodes.Document, pageUrl: String): List<LinkCandidate> {
        val candidates = mutableListOf<LinkCandidate>()

        // 1) Eski yol: alternative-links düğmeleri (data-video → /video/{id}/)
        document.select("div.alternative-links").forEach { element ->
            val langCode = element.attr("data-lang").uppercase()
            element.select("button.alternative-link").forEach { button ->
                val label = button.text().replace("(HDrip Xbet)", "").trim()
                    .ifBlank { button.attr("data-name").trim() }
                    .ifBlank { "Kaynak" }
                val source = if (langCode.isNotBlank()) "$label $langCode" else label
                val vid = listOf(
                    button.attr("data-video"),
                    button.attr("data-video-url"),
                    button.attr("data-video_url"),
                    button.attr("data-id"),
                    button.attr("data-src"),
                    button.attr("data-url"),
                    button.attr("data-embed"),
                    button.attr("data-server")
                ).firstOrNull { it.isNotBlank() } ?: return@forEach
                val normalized = normalizeEmbed(vid, pageUrl)
                val rawId = vid.trim().removePrefix("/video/").trim('/')
                    .takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("://") }
                if (normalized != null) {
                    candidates.add(LinkCandidate(source, normalized, rawId))
                } else if (rawId != null) {
                    candidates.add(LinkCandidate(source, "", rawId))
                }
            }
        }

        // 2) data-video / data-video_url / data-src / data-url taşıyan tüm öğeler
        document.select("[data-video], [data-video-url], [data-video_url], [data-src], [data-url], [data-embed], [data-server]").forEach { element ->
            val raw = listOf(
                element.attr("data-video"),
                element.attr("data-video-url"),
                element.attr("data-video_url"),
                element.attr("data-src"),
                element.attr("data-url"),
                element.attr("data-embed"),
                element.attr("data-server")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach
            val label = element.text().trim().take(40)
                .ifBlank { element.attr("title").trim() }
                .ifBlank { "Kaynak" }
            val normalized = normalizeEmbed(raw, pageUrl)
            val rawId = raw.trim().removePrefix("/video/").trim('/')
                .takeIf { it.isNotBlank() && !it.contains('/') && !it.contains("://") }
            if (normalized != null) {
                candidates.add(LinkCandidate(label, normalized, rawId))
            } else if (rawId != null) {
                // IDs are resolved through the site's /video/{id}/ endpoint.
                candidates.add(LinkCandidate(label, "", rawId))
            }
        }

        // 3) iframe'ler (src / data-src / data-lazy-src)
        document.select("iframe").forEach { frame ->
            val raw = listOf(
                frame.attr("data-src"),
                frame.attr("src"),
                frame.attr("data-lazy-src")
            ).firstOrNull { it.isNotBlank() } ?: return@forEach
            val normalized = normalizeEmbed(raw, pageUrl) ?: return@forEach
            candidates.add(LinkCandidate("iframe", normalized))
        }

        // 4) Sayfanın ham HTML'inde gömülü duran oynatıcı adresleri
        val html = document.html()
        listOf(
            """"file"\s*:\s*"([^"]+\.m3u8[^"]*)"""".toRegex(),
            """https?://[^\s"'<>]+/master\.txt[^\s"'<>]*""".toRegex(),
            """https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex(),
            """https?://[^\s"'<>]*(?:rplayer|rapidvid|rapidrame)[^\s"'<>]*""".toRegex(RegexOption.IGNORE_CASE)
        ).forEach { regex ->
            regex.findAll(html).forEach { match ->
                val value = match.groupValues.getOrElse(1) { match.value }
                val normalized = normalizeEmbed(value, pageUrl) ?: return@forEach
                candidates.add(LinkCandidate("Gömülü", normalized))
            }
        }

        return candidates.distinctBy { it.url.ifBlank { "id:${it.videoId}" } }
    }

    /**
     * /video/{id}/ yanıtından gömülü oynatıcı adresini çıkarır.
     *
     * ! SİTE YANITI JSON'DUR ve HTML İÇİNDE KAÇIŞLI SLASH KULLANIR:
     * !   {"data":{"html":"<iframe data-src=\"https:\/\/site\/rplayer\/ID\/\">"}}
     * !
     * ! Eski regex (data-src=\\?"([^"\\]+)") kaçışlı slash'te duruyor ve
     * ! kapanış tırnağı gelmediği için HİÇ EŞLEŞMİYORDU → embed bulunamıyor
     * ! → uygulama "0 kaynak" gösteriyordu. Bu yüzden önce JSON'daki "html"
     * ! alanı çözülüyor, kaçışlar geri alınıyor, sonra HTML'den data-src okunuyor.
     */
    private fun extractEmbedUrl(rawResponse: String?): String? = EmbedResolver.extractEmbedUrl(rawResponse)

    /** JSON gövdesindeki "html" alanını çözer (JSON kaçışlarını geri alır). */
    private fun jsonHtmlField(raw: String): String? = EmbedResolver.jsonHtmlField(raw)

    /**
     * iframe adresinden oynatıcı sayfasının adresini kurar.
     *
     * ! Site bazen doğrudan site-içi /rplayer/<id>/ adresi verir; bazen de
     * ! harici bir adres verip rapidrame_id'sini sorgu dizesinde taşır
     * ! (canlıda test edilmiş scraper'lar bu yolu kullanıyor). Harici
     * ! adresteki rapidrame_id, site-içi rplayer adresine çevrilir.
     * !
     * ! rplayer adresi SONDA / ile olmalı: sonda / yoksa sunucu 301
     * ! yönlendiriyor ve oynatıcı sayfası boş dönüyor.
     */
    private fun toPlayerUrl(iframe: String): String = EmbedResolver.toPlayerUrl(iframe, mainUrl)

    private fun normalizeEmbed(raw: String, pageUrl: String?): String? =
        EmbedResolver.normalizeEmbed(raw, pageUrl, mainUrl)

    /** Siteye ait oynatıcı yolları — kendi host'unda olsalar da geçerli kaynaktır. */
    private fun isPlayerPath(url: String): Boolean = EmbedResolver.isPlayerPath(url)

    private suspend fun fetchText(url: String, referer: String): String? {
        return try {
            val origin = originOf(url) ?: mainUrl
            app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$referer",
                    "Origin" to origin
                ),
                interceptor = interceptor
            ).text
        } catch (e: Exception) {
            Log.e("HDCH", "fetchText error ($url): ${e.message}")
            null
        }
    }
    private data class SubSource(
        @JsonProperty("file")    val file: String?  = null,
        @JsonProperty("label")   val label: String? = null,
        @JsonProperty("language") val language: String? = null,
        @JsonProperty("kind")    val kind: String?  = null
    )

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
    data class HDFC(
        @JsonProperty("html") val html: String,
        @JsonProperty("meta") val meta: Meta
    )

    data class Meta(
        @JsonProperty("title") val title: String,
        @JsonProperty("canonical") val canonical: String,
        @JsonProperty("keywords") val keywords: Boolean
    )

}
