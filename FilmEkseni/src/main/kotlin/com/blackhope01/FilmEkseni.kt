package com.blackhope01

import android.util.Log
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

        val response = app.get(data)
        val document = response.document
        val rawHtml = response.text
        val attempted = mutableSetOf<String>()

        // ---- Katman 1: Alpine x-data videoPlayerData JSON bloğu ----
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
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                    .replace("\\u0022", "\"")
                    .replace("&quot;", "\"")
                    .replace("\\\\", "\\")

                try {
                    val videoData = parseJson<VideoPlayerData>(jsonStr)
                    val allVideos = mutableListOf<Pair<VideoItem, String>>()
                    videoData.dual?.forEach { allVideos.add(it to "dual") }
                    videoData.tr?.forEach { allVideos.add(it to "tr") }
                    videoData.eng?.forEach { allVideos.add(it to "eng") }
                    videoData.en?.forEach { allVideos.add(it to "en") }
                    videoData.yerli?.forEach { allVideos.add(it to "yerli") }

                    Log.d(name, "loadLinks - Toplam video kaynagi: ${allVideos.size}")

                    allVideos.forEach { (video, group) ->
                        resolveVideoItem(video, group, data, attempted, subtitleCallback, callback)
                    }
                } catch (ex: Exception) {
                    Log.e(name, "loadLinks JSON parse hatasi: ${ex.message}")
                }
            }
        }

        // ---- Katman 2: Sayfadaki tüm iframe'ler ----
        if (attempted.isEmpty()) {
            document.select("iframe[src], iframe[data-src]").forEach { frame ->
                val src = frame.attr("src").ifBlank { frame.attr("data-src") }
                val absolute = normalizeEmbed(src)
                if (absolute != null && adKeywords.none { absolute.contains(it, ignoreCase = true) } && attempted.add(absolute)) {
                    Log.d(name, "loadLinks - iframe yedek: $absolute")
                    loadExtractor(absolute, data, subtitleCallback, callback)
                }
            }
        }

        // ---- Katman 3: Ham HTML içinde bilinen oynatıcı adresleri ----
        if (attempted.isEmpty()) {
            knownPlayerHosts.findAll(rawHtml)
                .map { it.value.replace("\\/", "/").replace("\\", "").trim() }
                .distinct()
                .forEach { playerUrl ->
                    if (adKeywords.none { playerUrl.contains(it, ignoreCase = true) } && attempted.add(playerUrl)) {
                        Log.d(name, "loadLinks - ham HTML oynatıcı: $playerUrl")
                        loadExtractor(playerUrl, data, subtitleCallback, callback)
                    }
                }
        }

        // ---- Katman 4: Script içinde doğrudan m3u8/mp4 ----
        if (attempted.isEmpty()) {
            document.select("script").forEach { script ->
                FilmEkseniPlayerParser.mediaFiles(script.data()).forEach { mediaUrl ->
                    if (attempted.add(mediaUrl)) {
                        val type = if (mediaUrl.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        Log.d(name, "loadLinks - doğrudan medya: $mediaUrl")
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Doğrudan Kaynak",
                                url = mediaUrl,
                                type = type,
                            ) {
                                this.referer = data
                                this.quality = Qualities.Unknown.value
                            },
                        )
                    }
                }
            }
        }

        return attempted.isNotEmpty()
    }

    /** Tek bir VideoItem içindeki base64 şablonunu çözer ve uygun oynatıcıya yönlendirir. */
    private suspend fun resolveVideoItem(
        video: VideoItem,
        group: String,
        pageUrl: String,
        attempted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val videoLink = video.link
        val videoTemplate = video.template
        val videoSlug = video.slug

        if (videoLink.isBlank() || videoTemplate.isBlank()) return

        try {
            val decodedTemplate = String(
                android.util.Base64.decode(videoTemplate, android.util.Base64.DEFAULT),
                Charsets.UTF_8,
            )

            val langTag = when {
                video.note?.contains("dublaj", ignoreCase = true) == true -> "#dublaj"
                video.note?.contains("altyazi", ignoreCase = true) == true -> "#altyazi"
                group == "dual" -> "#dublaj"
                group == "eng" || group == "en" -> "#altyazi"
                else -> "#tr"
            }

            // Şablon içinde doğrudan medya dosyası varsa extractor'a gerek yok.
            FilmEkseniPlayerParser.mediaFiles(decodedTemplate).forEach { directMedia ->
                if (attempted.add(directMedia)) {
                    val type = if (directMedia.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    callback(
                        newExtractorLink(
                            source = video.service_name.ifBlank { name },
                            name = "${video.service_name.ifBlank { name }} (${langTag.removePrefix("#")})",
                            url = directMedia,
                            type = type,
                        ) {
                            this.referer = pageUrl
                            this.quality = Qualities.Unknown.value
                        },
                    )
                }
            }

            FilmEkseniPlayerParser.iframeSources(decodedTemplate).forEach { rawSrc ->
                val embedUrl = rawSrc.replace("{url}", videoLink).replace("{slug}", videoSlug)
                val normalized = normalizeEmbed(embedUrl) ?: return@forEach
                val tagged = "$normalized$langTag"
                if (attempted.add(normalized)) {
                    Log.d(name, "loadLinks - Embed: $tagged (Service: ${video.service_name}, Grup: $group)")
                    loadExtractor(tagged, pageUrl, subtitleCallback, callback)
                }
            }
        } catch (ex: Exception) {
            Log.e(name, "loadLinks template parse hatasi: ${ex.message}")
        }
    }

    private fun normalizeEmbed(src: String): String? {
        val trimmed = src.trim().ifBlank { return null }
        return when {
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("http") -> trimmed
            else -> "https://$trimmed"
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
