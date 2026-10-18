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
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
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

    private suspend fun extractMobiOrCloseLoad(
        source: String,
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
                "Referer" to "${mainUrl}/"
            )
            val html = app.get(url, headers = headers, interceptor = interceptor).text

            // 1. JSON-LD contentUrl
            val ldJsonMatch = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(html)
            var realUrl = ldJsonMatch?.groupValues?.get(1)?.replace("\\/", "/")

            // 2. Direct master.txt / m3u8
            if (realUrl.isNullOrBlank()) {
                val txtMatch = """https?://[^\s"']+/master\.txt[^\s"']*""".toRegex().find(html)?.value
                    ?: """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex().find(html)?.value
                realUrl = txtMatch
            }

            // 3. Native decrypt if present
            if (realUrl.isNullOrBlank() && html.contains("function dc_")) {
                val unpacked = getAndUnpack(html)
                realUrl = decryptLocalUrl(unpacked)
            }

            if (!realUrl.isNullOrBlank() && realUrl.startsWith("http")) {
                val finalStream = realUrl.substringAfter("https").let { "https$it" }
                callback.invoke(
                    newExtractorLink(
                        source = source,
                        name = source,
                        url = finalStream,
                        type = ExtractorLinkType.M3U8
                    ) {
                        quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to headers["User-Agent"]!!
                        )
                    }
                )
            }

            // Parse subtitles if present in embed
            val subData = html.substringAfter("tracks: [", "").substringBefore("]")
            if (subData.isNotBlank()) {
                AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" || it.kind == "subtitles" }?.forEach {
                    val subFile = it.file ?: return@forEach
                    val subtitleUrl = if (subFile.startsWith("http")) subFile else "${mainUrl}${subFile}/"
                    subtitleCallback(newSubtitleFile(it.language ?: "Türkçe", subtitleUrl))
                }
            }
        } catch (e: Exception) {
            Log.e("HDCH", "extractMobiOrCloseLoad error: ${e.message}")
        }
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit ) {
        val pageText = try {
            app.get(url, referer = "${mainUrl}/", interceptor = interceptor).text
        } catch (e: Exception) {
            return
        }
        val script = Jsoup.parse(pageText).select("script").find { it.data().contains("sources:") }?.data()
            ?: pageText.takeIf { it.contains("sources:") }
        Log.d("HDCH", "script » $script")
        var decryptedUrl: String? = null
        if (script != null) {
            val unpackedScript = getAndUnpack(script)
            decryptedUrl = decryptLocalUrl(unpackedScript)
        }

        // Fallback: Check for contentUrl or master.txt or m3u8 in pageText
        if (decryptedUrl.isNullOrBlank()) {
            decryptedUrl = """"contentUrl"\s*:\s*"([^"]+)"""".toRegex().find(pageText)?.groupValues?.get(1)?.replace("\\/", "/")
                ?: """https?://[^\s"']+/master\.txt[^\s"']*""".toRegex().find(pageText)?.value
                ?: """https?://[^\s"']+\.m3u8[^\s"']*""".toRegex().find(pageText)?.value
        }
        if (decryptedUrl.isNullOrBlank()) return
        val lastUrl = decryptedUrl.substringAfter("https").let { "https$it" }
        val subData = pageText.substringAfter("tracks: [", "").substringBefore("]")
        if (subData.isNotBlank()) {
            AppUtils.tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" || it.kind == "subtitles" }?.forEach {
                val subFile = it.file ?: return@forEach
                val subtitleUrl = if (subFile.startsWith("http")) subFile else "${mainUrl}${subFile}/"
                subtitleCallback(newSubtitleFile(it.language ?: "Türkçe", subtitleUrl))
            }
        }
        callback.invoke(
            newExtractorLink(
                source  = source,
                name    = source,
                url     = lastUrl,
                type    = ExtractorLinkType.M3U8
            ) {
                headers = mapOf("Referer" to "${mainUrl}/", "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Norton/124.0.0.0")
                quality = Qualities.Unknown.value
            }
        )
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

    document.select("div.alternative-links").map { element ->
        element to element.attr("data-lang").uppercase()
    }.forEach { (element, langCode) ->
        element.select("button.alternative-link").mapNotNull { button ->
            val vid = button.attr("data-video").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            (button.text().replace("(HDrip Xbet)", "").trim() + " $langCode") to vid
        }.forEach { (source, videoID) ->
            try {
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/", interceptor = interceptor,
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "X-Requested-With" to "fetch"
                    ),
                    referer = pageUrl
                ).text
                Log.d("HDCH", "Found videoID: $videoID")
                val iframeRaw = Regex("""data-src=\\?"([^"\\]+)""").find(apiGet)?.groupValues?.get(1)
                    ?: Regex("""src=\\?"([^"\\]+)""").find(apiGet)?.groupValues?.get(1)
                    ?: return@forEach
                var iframe = iframeRaw.replace("\\/", "/").replace("\\", "").trim()
                if (iframe.startsWith("//")) iframe = "https:$iframe"
                Log.d("HDCH", "$source » $videoID » $iframe")

                if (iframe.contains("mobi") || iframe.contains("closeload") || iframe.contains("/embed/")) {
                    extractMobiOrCloseLoad(source, iframe, subtitleCallback) { link ->
                        linkFound = true
                        callback(link)
                    }
                } else {
                    invokeLocalSource(source, iframe, subtitleCallback) { link ->
                        linkFound = true
                        callback(link)
                    }
                }
            } catch (e: Exception) {
                Log.e("HDCH", "Error loading videoID $videoID: ${e.message}")
            }
        }
    }
    return linkFound
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
