package com.blackhope01

import android.util.Log
import com.blackhope01.extractors.FilmEkseniHlsResolver
import com.blackhope01.extractors.FilmEkseniPlayerParser
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import kotlinx.coroutines.CancellationException

class FilmEkseni : MainAPI() {
    override var mainUrl = FilmEkseniConfig.MAIN_URL
    override var name = "FilmEkseni"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ============================================================
    // ANA SAYFA KATEGORILERI
    // ============================================================
    override val mainPage = mainPageOf(
        "${mainUrl}/kategori/fragmanlar/" to "Yakında Gelecek",
        "${mainUrl}/kategori/tavsiye-filmler/" to "Tavsiye Filmler",
        "${mainUrl}/diziler/" to "Yeni Eklenen Diziler",
        "${mainUrl}/kategori/netflix-yapimlari/" to "Netflix Yapımları",
        "${mainUrl}/kategori/dc-yapimlari/" to "DC Yapımları",
        "${mainUrl}/kategori/marvel-yapimlari/" to "Marvel Yapımları",
        "${mainUrl}/ulke/yerli-film-izle/" to "Yerli Filmler",
        "${mainUrl}/tur/aile-filmleri/" to "Aile",
        "${mainUrl}/tur/bilim-kurgu-filmleri/" to "Bilim Kurgu",
        "${mainUrl}/tur/macera-filmleri/" to "Macera",
        "${mainUrl}/tur/aksiyon-filmleri/" to "Aksiyon",
        "${mainUrl}/tur/gerilim-filmleri/" to "Gerilim",
        "${mainUrl}/tur/komedi-filmleri/" to "Komedi",
        "${mainUrl}/tur/korku-filmleri/" to "Korku",
    )

    // ============================================================
    // ANA SAYFA - Film grid parse
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(name, "getMainPage - Sayfa: $page, Kategori: ${request.name}")

        val (home, hasNext) = if (page == 1) {
            val document = app.get(request.data).document
            val elements = document.select("a.group\\/poster.block")
            val list = elements.mapNotNull { it.toSearchResult() }
            list to list.isNotEmpty()
        } else {
            val offset = (page - 1) * 24
            val (csrfToken, cookies) = getCsrfTokenAndCookies(request.data)

            val loadMoreUrl = if (request.data.endsWith("/diziler/")) {
                "${mainUrl}/diziler/load-more-latest"
            } else {
                request.data.removeSuffix("/") + "/load-more"
            }

            Log.d(name, "loadMore -> URL: $loadMoreUrl | offset: $offset | token: [${csrfToken.take(10)}...] | cookies: ${cookies.keys}")

            val jsonBody = """{"offset":$offset,"sort":"latest","content_type":""}"""
                .toRequestBody("application/json".toMediaType())

            val response = app.post(
                loadMoreUrl,
                headers = mapOf(
                    "x-csrf-token" to csrfToken,
                    "x-requested-with" to "XMLHttpRequest",
                    "referer" to request.data,
                ),
                cookies = cookies,
                requestBody = jsonBody,
            )

            val json = try {
                response.parsed<LoadMoreResponse>()
            } catch (e: Exception) {
                Log.e(name, "loadMore parsed hatasi: ${e.message}")
                LoadMoreResponse()
            }

            val htmlDoc = Jsoup.parse(json.html)
            val elements = htmlDoc.select("a.group\\/poster.block")
            val list = elements.mapNotNull { it.toSearchResult() }
            list to json.hasMore
        }

        Log.d(name, "getMainPage - ${home.size} icerik bulundu, hasNext=$hasNext")
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    private suspend fun getCsrfTokenAndCookies(url: String): Pair<String, Map<String, String>> {
        val response = app.get(url)
        val document = response.document

        document.selectFirst("meta[name=csrf-token]")?.attr("content")?.let {
            Log.d(name, "CSRF meta[csrf-token]: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }
        document.selectFirst("meta[name=_token]")?.attr("content")?.let {
            Log.d(name, "CSRF meta[_token]: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }
        (response.cookies["XSRF-TOKEN"] ?: response.cookies["xsrf-token"])?.let {
            Log.d(name, "CSRF cookie: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }
        document.selectFirst("input[name=_token]")?.attr("value")?.let {
            Log.d(name, "CSRF input: ${it.take(20)}...")
            if (it.isNotBlank()) return it to response.cookies
        }

        Log.e(name, "CSRF token BULUNAMADI! URL: $url")
        return "" to response.cookies
    }

    data class LoadMoreResponse(
        val html: String = "",
        val hasMore: Boolean = false,
        val nextOffset: Int? = null,
    )

    // ============================================================
    // ARAMA SONUCU DONUSTURME
    // ============================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null

        val title = this.selectFirst("img")?.attr("alt")?.trim()
            ?: this.selectFirst("h3")?.text()?.trim()
            ?: return null

        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))

        val yearText = this.select("span.text-gray-300").text()
        val year = Regex("""\b(19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val imdbText = this.selectFirst("span.text-accent span:last-child")?.text()?.trim()
            ?: this.select("span.text-accent").lastOrNull()?.text()?.trim()
        val imdbScore = imdbText?.toFloatOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster
            this.year = year
            this.score = imdbScore?.let { Score.from10(it) }
        }
    }

    // ============================================================
    // ARAMA - JSON API Kullanimi
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(name, "search basladi - Sorgu: $query")
        return try {
            val response = app.get("${mainUrl}/api/search?q=${query}")
            val json = response.parsed<SearchApiResponse>()

            val results = json.data.mapNotNull { item ->
                val href = "/${item.slug}/"
                val year = item.releaseYear.toIntOrNull()

                newMovieSearchResponse(item.title, href, TvType.Movie) {
                    this.posterUrl = fixUrlNull(item.posterUrl)
                    this.year = year
                    this.score = if (item.imdbRating > 0) Score.from10(item.imdbRating.toFloat()) else null
                }
            }

            Log.d(name, "search tamamlandi - ${results.size} sonuc bulundu")
            results
        } catch (e: Exception) {
            Log.e(name, "search hatasi: ${e.message}")
            emptyList()
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    // ============================================================
    // FILM/DIZI DETAY - Parse
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        Log.d(name, "load basladi - URL: $url")

        val document = app.get(
            url,
            headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"),
        ).document

        val title = document.selectFirst("h1.text-4xl.font-bold")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (title == null) {
            Log.e(name, "load - Baslik bulunamadi")
            return null
        }

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.aspect-\\[2\\/3\\] img")?.attr("src")
                ?: document.selectFirst("div[class*='aspect'] img[alt]")?.attr("src"),
        )

        val year = document.select("a[href*='/yil/']").mapNotNull {
            it.text().trim().toIntOrNull()
        }.firstOrNull()
        Log.d(name, "load - Yil: $year")

        val description = document.selectFirst("div[x-ref='content']")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div:containsOwn(Türler:) + div a[href*='/tur/'], div:containsOwn(Türler:) ~ div a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        val finalTags = tags.ifEmpty {
            document.select("section a[href*='/tur/']")
                .map { it.text().trim() }
                .filter { it.isNotEmpty() && it.length < 30 }
                .distinct()
        }
        Log.d(name, "load - Turler: $finalTags")

        val imdbText = document.selectFirst("a[href*='imdb.com']")?.text()?.trim()
        val rating = imdbText?.let {
            Regex("""([0-9]+(?:\.[0-9]+)?)""").find(it)?.groupValues?.get(1)
        }

        val duration = document.select("span").map { it.ownText().trim() }
            .firstOrNull { it.matches(Regex("""\d+\s*dk""")) || it.matches(Regex("""\d+\s*dakika""")) }
            ?.replace(Regex("""[^0-9]"""), "")
            ?.toIntOrNull()
        Log.d(name, "load - Sure: $duration")

        val actorElements = document.select("a[href*='/oyuncu/']")
        Log.d(name, "load - Oyuncu element sayisi: ${actorElements.size}")

        val actors = actorElements.mapNotNull { a ->
            val actorName = a.selectFirst("h4")?.text()?.trim()
                ?: a.selectFirst("img")?.attr("alt")?.trim()
            val actorImg = fixUrlNull(a.selectFirst("img")?.attr("src"))
            val characterName = a.selectFirst("p")?.text()?.trim()

            if (actorName.isNullOrBlank()) {
                Log.w(name, "load - Oyuncu adi bos, atlaniyor")
                null
            } else {
                Log.d(name, "load - Oyuncu bulundu: $actorName | Karakter: $characterName | Resim: $actorImg")
                Pair(Actor(actorName, actorImg), characterName)
            }
        }
        Log.d(name, "load - Toplam gecerli oyuncu: ${actors.size}")

        val trailerRaw = document.selectFirst("iframe[src*='youtube.com/embed/']")?.attr("src")
            ?: document.selectFirst("iframe[src*='youtu.be']")?.attr("src")
            ?: ""
        val trailer = when {
            trailerRaw.startsWith("//") -> "https:$trailerRaw"
            trailerRaw.startsWith("http") -> trailerRaw
            trailerRaw.isNotBlank() -> fixUrl(trailerRaw)
            else -> ""
        }
        Log.d(name, "load - Fragman URL: $trailer")

        val recommendations = document.select("a.group\\/poster.block").mapNotNull { item ->
            val recName = item.selectFirst("img")?.attr("alt")?.trim()
                ?: item.selectFirst("h3")?.text()?.trim()
                ?: return@mapNotNull null

            val recHref = fixUrlNull(item.attr("href")) ?: return@mapNotNull null
            val recPoster = fixUrlNull(item.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPoster
                val recYear = Regex("""\b(19|20)\d{2}\b""").find(item.text())?.value?.toIntOrNull()
                this.year = recYear
            }
        }

        val isSeries = url.contains("/dizi/")

        Log.d(name, "load tamamlandi - Baslik: $title, Yil: $year, IMDb: $rating, Sure: $duration, Tur: ${if (isSeries) "Dizi" else "Film"}")

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()

            val seasonTabs = document.select("nav[aria-label='Sezonlar'] button, nav button[role='tab']")

            if (seasonTabs.isNotEmpty()) {
                seasonTabs.forEach { tab ->
                    val seasonText = tab.text().trim()
                    val seasonNum = Regex("""(\d+)\.\s*Sezon""").find(seasonText)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("""(\d+)""").find(seasonText)?.value?.toIntOrNull()

                    if (seasonNum != null) {
                        val episodeLinks = document.select("a[href*='${url.removeSuffix("/")}/sezon-$seasonNum/bolum-']")

                        episodeLinks.forEach { epLink ->
                            val epHref = fixUrlNull(epLink.attr("href")) ?: return@forEach
                            val epName = epLink.text().trim()

                            val epNum = Regex("""/bolum-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

                            if (epNum != null) {
                                episodes.add(
                                    newEpisode(epHref) {
                                        this.name = epName
                                        this.season = seasonNum
                                        this.episode = epNum
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (episodes.isEmpty()) {
                document.select("a[href*='/sezon-'][href*='/bolum-']").forEach { epLink ->
                    val epHref = fixUrlNull(epLink.attr("href")) ?: return@forEach
                    val epName = epLink.text().trim()

                    val seasonMatch = Regex("""/sezon-(\d+)""").find(epHref)
                    val epMatch = Regex("""/bolum-(\d+)""").find(epHref)

                    val seasonNum = seasonMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epNum = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                        },
                    )
                }
            }

            val sortedEpisodes = episodes.distinctBy { "${it.season}-${it.episode}" }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, sortedEpisodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = finalTags
                this.score = rating?.let { Score.from10(it.toFloat()) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = finalTags
                this.score = rating?.let { Score.from10(it.toFloat()) }
                this.duration = duration
                this.recommendations = recommendations
                addActors(actors)
                if (trailer.isNotBlank()) addTrailer(trailer)
            }
        }
    }

    // ============================================================
    // VIDEO/LINK - Çok katmanlı oynatıcı çözümü
    // ============================================================
    private val playerDataPatterns = listOf(
        Regex("""videoPlayerData\(JSON\.parse\('([^']+)'\)"""),
        Regex("""videoPlayerData\(JSON\.parse\("([^"]+)"\)"""),
        Regex("""videoPlayerData\(JSON\.parse\(&quot;(.+?)&quot;\)\)"""),
    )
    private val adKeywords = listOf("google", "recaptcha", "doubleclick", "izlegec", "banner", "googlesyndication")
    private val knownPlayerHosts = Regex(
        """https?:\\?/\\?/[^\s"'<>]*(?:eksenload|vidmoly|vidmody|filemoon|vidlop|streamtape|dood|luluvdo|sendvid)[^\s"'<>]*""",
        RegexOption.IGNORE_CASE,
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        Log.d(name, "loadLinks basladi - Data: $data")
        val userAgent =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        val pageHeaders = mapOf("User-Agent" to userAgent, "Referer" to data)
        val response = try {
            app.get(data, headers = pageHeaders)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(name, "loadLinks sayfası alınamadı: ${error.message}")
            return false
        }
        val document = response.document
        val rawHtml = response.text
        val attemptedSources = mutableSetOf<String>()
        val emittedLinkKeys = HashSet<String>()
        val emittedSubtitleUrls = HashSet<String>()
        var foundVideo = false

        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            val absoluteUrl = FilmEkseniPlayerParser.resolveUrl(data, subtitle.url)
            if (subtitle.url.isNotBlank() && absoluteUrl.isNotBlank() && emittedSubtitleUrls.add(absoluteUrl)) {
                subtitle.url = absoluteUrl
                if (subtitle.headers.isNullOrEmpty()) subtitle.headers = pageHeaders
                subtitleCallback(subtitle)
            }
        }
        val emitLink: (ExtractorLink) -> Unit = { link ->
            val audioKey = link.audioTracks.map { it.url }.sorted().joinToString(",")
            val uniqueKey = "${link.url}|${link.name}|${link.type}|$audioKey"
            if (link.url.isNotBlank() && emittedLinkKeys.add(uniqueKey)) {
                foundVideo = true
                if (link.referer.isBlank()) link.referer = data
                if (link.headers.isNullOrEmpty()) link.headers = pageHeaders
                val audioHeaders = link.headers.toMutableMap().apply {
                    if (keys.none { it.equals("Referer", ignoreCase = true) }) put("Referer", link.referer)
                }
                link.audioTracks.forEach { audio ->
                    if (audio.headers.isNullOrEmpty()) audio.headers = audioHeaders
                }
                callback(link)
            }
        }

        // Captured site payload: the source groups are parallel alternatives,
        // so process every group rather than stopping after the first successful one.
        val playerDiv = document.selectFirst("div[data-video-player]")
        val candidateBlobs = buildList {
            playerDiv?.attr("x-data")?.takeIf { it.isNotBlank() }?.let { add(it) }
            document.select("script").forEach { script ->
                val scriptData = script.data()
                if (scriptData.contains("videoPlayerData")) add(scriptData)
            }
        }
        candidateBlobs.forEach { blob ->
            val jsonMatch = playerDataPatterns.firstNotNullOfOrNull { pattern -> pattern.find(blob) }
            if (jsonMatch == null) return@forEach
            val jsonStr = jsonMatch.groupValues[1]
                .replace("\\u0022", "\"")
                .replace("&quot;", "\"")
                .replace("\\\\", "\\")
            try {
                val videoData = parseJson<VideoPlayerData>(jsonStr)
                val allVideos = buildList {
                    videoData.dual?.forEach { add(it to "dual") }
                    videoData.tr?.forEach { add(it to "tr") }
                    videoData.eng?.forEach { add(it to "eng") }
                    videoData.en?.forEach { add(it to "en") }
                    videoData.yerli?.forEach { add(it to "yerli") }
                }
                Log.d(name, "loadLinks - ${allVideos.size} video kaynağı/grubu bulundu")
                allVideos.forEach { (video, group) ->
                    try {
                        resolveVideoItem(
                            video = video,
                            group = group,
                            pageUrl = data,
                            attemptedSources = attemptedSources,
                            headers = pageHeaders,
                            subtitleCallback = emitSubtitle,
                            callback = emitLink,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(name, "Video kaynağı çözülemedi (${video.service_name}): ${error.message}")
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.e(name, "videoPlayerData JSON parse hatası: ${error.message}")
            }
        }

        // Inline <video>/<source> and <track> elements are independent fallbacks;
        // do not gate them on an attempted iframe or a parsed but unusable payload.
        document.select("video[src], video source[src]").forEach { source ->
            val mediaUrl = source.absUrl("src").ifBlank { source.attr("src") }
            if (mediaUrl.isNotBlank()) {
                emitMediaUrl(mediaUrl, "$name Doğrudan Kaynak", data, attemptedSources, pageHeaders, emitSubtitle, emitLink)
            }
        }
        val pageSubtitleTracks = buildList {
            addAll(FilmEkseniPlayerParser.subtitles(rawHtml))
            document.select("script").forEach { addAll(FilmEkseniPlayerParser.subtitles(it.data())) }
        }.distinctBy { it.url }
        pageSubtitleTracks.forEach { track ->
            val label = track.language?.takeIf(String::isNotBlank) ?: track.label
            val subtitleUrl = FilmEkseniPlayerParser.resolveUrl(data, track.url)
            emitSubtitle(newSubtitleFile(label, subtitleUrl) { this.headers = pageHeaders })
        }
        document.select("track[src]").forEach { track ->
            val kind = track.attr("kind").lowercase()
            if (kind.isNotBlank() && kind !in setOf("subtitles", "captions")) return@forEach
            val url = track.absUrl("src").ifBlank { track.attr("src") }
            if (url.isNotBlank()) {
                val label = track.attr("srclang").ifBlank { track.attr("label").ifBlank { "Subtitle" } }
                emitSubtitle(newSubtitleFile(label, url) { this.headers = pageHeaders })
            }
        }

        // Try every page iframe even if an earlier source emitted a link. `visited`
        // deduplicates requests; `foundVideo` is based only on actual link callbacks.
        document.select("iframe[src], iframe[data-src]").forEach { frame ->
            val rawSrc = frame.attr("src").ifBlank { frame.attr("data-src") }
            val playerUrl = FilmEkseniPlayerParser.resolveUrl(data, rawSrc)
            if (rawSrc.isNotBlank() &&
                adKeywords.none { playerUrl.contains(it, ignoreCase = true) } &&
                attemptedSources.add("player:$playerUrl")
            ) {
                try {
                    loadPlayerLinks(playerUrl, data, pageHeaders, emitSubtitle, emitLink)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.e(name, "iframe çözülemedi ($playerUrl): ${error.message}")
                }
            }
        }

        // Some mirrors are stored as escaped URLs in inline scripts/RSC data rather
        // than DOM iframes. Try these in addition to the payload and DOM paths above.
        knownPlayerHosts.findAll(rawHtml)
            .map { FilmEkseniPlayerParser.normalizePlayerUrl(it.value).trimEnd(',', ';', ')', ']') }
            .distinct()
            .forEach { rawUrl ->
                val playerUrl = FilmEkseniPlayerParser.resolveUrl(data, rawUrl)
                if (adKeywords.none { playerUrl.contains(it, ignoreCase = true) } &&
                    attemptedSources.add("player:$playerUrl")
                ) {
                    try {
                        loadPlayerLinks(playerUrl, data, pageHeaders, emitSubtitle, emitLink)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Log.e(name, "HTML oynatıcı çözülemedi ($playerUrl): ${error.message}")
                    }
                }
            }

        val directMediaUrls = buildList {
            addAll(FilmEkseniPlayerParser.mediaFiles(rawHtml))
            document.select("script").forEach { addAll(FilmEkseniPlayerParser.mediaFiles(it.data())) }
        }.distinct()
        directMediaUrls.forEach { mediaUrl ->
            emitMediaUrl(mediaUrl, "$name Doğrudan Kaynak", data, attemptedSources, pageHeaders, emitSubtitle, emitLink)
        }

        Log.d(name, "loadLinks tamamlandı; video linki=${emittedLinkKeys.size}, altyazı=${emittedSubtitleUrls.size}")
        return foundVideo
    }

    private suspend fun emitMediaUrl(
        rawUrl: String,
        sourceName: String,
        pageUrl: String,
        attemptedSources: MutableSet<String>,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val mediaUrl = FilmEkseniPlayerParser.resolveUrl(pageUrl, rawUrl.trim())
        if (mediaUrl.isBlank() || !attemptedSources.add("media:$mediaUrl")) return
        if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
            FilmEkseniHlsResolver.emit(
                url = mediaUrl,
                source = sourceName,
                referer = pageUrl,
                headers = headers,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        } else {
            val type = if (mediaUrl.contains(".mpd", ignoreCase = true)) {
                ExtractorLinkType.DASH
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(name, sourceName, mediaUrl, type) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                },
            )
        }
    }

    /** Load a player URL and normalize any returned HLS master before emitting it. */
    private suspend fun loadPlayerLinks(
        url: String,
        referer: String,
        pageHeaders: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val playerPageUrl = url.substringBefore('#')
        val playerSubtitleCallback: (SubtitleFile) -> Unit = { subtitle ->
            if (subtitle.url.isNotBlank()) {
                subtitle.url = FilmEkseniPlayerParser.resolveUrl(playerPageUrl, subtitle.url)
                if (subtitle.headers.isNullOrEmpty()) subtitle.headers = pageHeaders
                subtitleCallback(subtitle)
            }
        }
        val extractedLinks = ArrayList<ExtractorLink>()
        try {
            loadExtractor(url, referer, playerSubtitleCallback) { extractedLinks += it }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(name, "Extractor çalıştırılamadı ($url): ${error.message}")
        }

        val seen = HashSet<String>()
        extractedLinks.forEach { link ->
            val audioKey = link.audioTracks.map { it.url }.sorted().joinToString(",")
            if (!seen.add("${link.url}|${link.name}|${link.type}|$audioKey")) return@forEach
            val isHls = link.type == ExtractorLinkType.M3U8 || link.url.contains(".m3u8", ignoreCase = true)
            val alreadyResolvedByPlugin = link.source.equals("Eksenload", ignoreCase = true) ||
                link.source.equals("VidMoly", ignoreCase = true)
            if (isHls && link.audioTracks.isEmpty() && !alreadyResolvedByPlugin) {
                val headers = link.headers.takeIf { it.isNotEmpty() } ?: pageHeaders
                FilmEkseniHlsResolver.emit(
                    url = link.url,
                    source = link.source,
                    displayName = link.name,
                    referer = link.referer.ifBlank { referer },
                    headers = headers,
                    subtitleCallback = playerSubtitleCallback,
                    callback = callback,
                )
            } else {
                callback(link)
            }
        }
    }

    private fun isVideoMediaUrl(url: String): Boolean {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return listOf(".m3u8", ".mpd", ".mp4", ".m4v", ".webm", ".mov", ".mkv")
            .any(path::endsWith)
    }

    private fun decodeVideoTemplate(template: String): String {
        if (template.isBlank()) return ""
        val candidates = listOf(android.util.Base64.DEFAULT, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP)
        return candidates.firstNotNullOfOrNull { flags ->
            runCatching {
                String(android.util.Base64.decode(template, flags), Charsets.UTF_8)
            }.getOrNull()?.takeIf { decoded ->
                decoded.contains("://") ||
                    decoded.contains("<iframe", ignoreCase = true) ||
                    decoded.contains(".m3u8", ignoreCase = true) ||
                    decoded.contains(".mpd", ignoreCase = true) ||
                    decoded.contains(".mp4", ignoreCase = true) ||
                    decoded.contains(".m4v", ignoreCase = true) ||
                    decoded.contains(".webm", ignoreCase = true) ||
                    decoded.contains(".mov", ignoreCase = true) ||
                    decoded.contains(".mkv", ignoreCase = true) ||
                    decoded.contains(".vtt", ignoreCase = true) ||
                    decoded.contains("file", ignoreCase = true)
            }
        } ?: template
    }

    /** Decode a source template, preserving all subtitle and media variants it contains. */
    private suspend fun resolveVideoItem(
        video: VideoItem,
        group: String,
        pageUrl: String,
        attemptedSources: MutableSet<String>,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        if (video.template.isBlank() && video.link.isBlank()) return
        val decodedTemplate = decodeVideoTemplate(video.template)
        val langTag = when {
            video.note?.contains("dublaj", ignoreCase = true) == true -> "dublaj"
            video.note?.contains("altyazi", ignoreCase = true) == true -> "altyazi"
            group == "dual" -> "dublaj"
            group == "eng" || group == "en" -> "altyazi"
            else -> "tr"
        }

        val templateSources = FilmEkseniPlayerParser.iframeSources(decodedTemplate).toMutableList()
        val directMediaUrls = FilmEkseniPlayerParser.mediaFiles(decodedTemplate).toMutableList()
        if (isVideoMediaUrl(decodedTemplate)) directMediaUrls.add(decodedTemplate)
        if (isVideoMediaUrl(video.link)) directMediaUrls.add(video.link)

        if (templateSources.isEmpty() &&
            (decodedTemplate.startsWith("http://", true) || decodedTemplate.startsWith("https://", true) ||
                decodedTemplate.startsWith("//")) &&
            !isVideoMediaUrl(decodedTemplate)
        ) {
            templateSources.add(decodedTemplate)
        }
        if (video.link.isNotBlank() && templateSources.isEmpty() && !isVideoMediaUrl(video.link)) {
            templateSources.add(video.link)
        }

        val expandedSources = templateSources.map { rawSource ->
            rawSource
                .replace("{url}", video.link)
                .replace("{slug}", video.slug)
        }
        val resolvedSources = expandedSources
            .map { FilmEkseniPlayerParser.resolveUrl(pageUrl, it) }
            .filter(String::isNotBlank)
            .distinct()
        val templateBase = resolvedSources.firstOrNull() ?: pageUrl

        FilmEkseniPlayerParser.subtitles(decodedTemplate).forEach { track ->
            val subtitleUrl = FilmEkseniPlayerParser.resolveUrl(templateBase, track.url)
            val label = track.language?.takeIf(String::isNotBlank) ?: track.label
            subtitleCallback(newSubtitleFile(label, subtitleUrl) { this.headers = headers })
        }
        directMediaUrls.distinct().forEach { rawMedia ->
            emitMediaUrl(
                rawMedia,
                "${video.service_name.ifBlank { name }} ($langTag)",
                templateBase,
                attemptedSources,
                headers,
                subtitleCallback,
                callback,
            )
        }

        resolvedSources.forEach { playerUrl ->
            val taggedUrl = "${playerUrl.substringBefore('#')}#$langTag"
            if (attemptedSources.add("player:$taggedUrl")) {
                try {
                    loadPlayerLinks(taggedUrl, pageUrl, headers, subtitleCallback, callback)
                } catch (error: CancellationException) {
                    throw error
                }
            }
        }
    }

    // ============================================================
    // DATA CLASSES
    // ============================================================
    data class SearchApiResponse(
        val data: List<SearchResultItem> = emptyList(),
    )

    data class SearchResultItem(
        val id: String = "",
        val title: String = "",
        val slug: String = "",
        val posterUrl: String = "",
        val releaseYear: String = "",
        val imdbRating: Double = 0.0,
        val contentableType: String = "",
    )

    data class VideoPlayerData(
        val dual: List<VideoItem>? = null,
        val tr: List<VideoItem>? = null,
        val eng: List<VideoItem>? = null,
        val en: List<VideoItem>? = null,
        val yerli: List<VideoItem>? = null,
    )

    data class VideoItem(
        val link: String = "",
        val service_slug: String = "",
        val service_name: String = "",
        val quality: String = "",
        val note: String? = null,
        val template: String = "",
        val slug: String = "",
    )
}
