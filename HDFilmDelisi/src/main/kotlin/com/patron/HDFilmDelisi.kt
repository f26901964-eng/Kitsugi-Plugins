//Eklenti kraptor reposundan çekilip hdfilmdelisi.one Next.js yapısına göre yeniden yazılmıştır.
package com.patron

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

data class LinkData(
    @JsonProperty("url") val url: String?,
    @JsonProperty("type") val type: String?,
    @JsonProperty("season") val season: Int?,
    @JsonProperty("episode") val episode: Int?,
    @JsonProperty("tmdbId") val tmdbId: Int?,
    @JsonProperty("imdbId") val imdbId: String?,
)

class HDFilmDelisi : MainAPI() {

    override var mainUrl = HDFilmDelisiConfig.MAIN_URL
    override var name = "HDFilmDelisi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Son Eklenen Filmler",
        "$mainUrl/diziler" to "Diziler",
        "$mainUrl/tur/aile" to "Aile",
        "$mainUrl/tur/aksiyon" to "Aksiyon",
        "$mainUrl/tur/animasyon" to "Animasyon",
        "$mainUrl/tur/belgeseller" to "Belgeseller",
        "$mainUrl/tur/bilim-kurgu" to "Bilim Kurgu",
        "$mainUrl/tur/dram" to "Dram",
        "$mainUrl/tur/fantastik" to "Fantastik",
        "$mainUrl/tur/gerilim" to "Gerilim",
        "$mainUrl/tur/gizem" to "Gizem",
        "$mainUrl/tur/komedi" to "Komedi",
        "$mainUrl/tur/korku" to "Korku",
        "$mainUrl/tur/macera" to "Macera",
        "$mainUrl/tur/romantik" to "Romantik",
        "$mainUrl/tur/savas" to "Savaş",
        "$mainUrl/tur/suc" to "Suç",
        "$mainUrl/tur/tarih" to "Tarih",
        "$mainUrl/tur/yerli" to "Yerli",
    )

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val headers = mapOf("User-Agent" to userAgent)
    private val mapper = jacksonObjectMapper()
    private val adFrameKeywords = listOf("google", "recaptcha", "izlegec", "doubleclick", "banner")

    // ============================================================
    // ANA SAYFA / KATEGORİ
    // ============================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val base = request.data.removeSuffix("/")
            if (base.contains("?")) "$base&sayfa=$page" else "$base?sayfa=$page"
        }

        val html = app.get(targetUrl, headers = headers).text
        val document = Jsoup.parse(html, targetUrl)
        val cards = HDFilmDelisiMarkup.parseCards(document)
        val items = cards.map { it.toSearchResponse() }

        return newHomePageResponse(request.name, items, hasNext = items.size >= 20)
    }

    // ============================================================
    // ARAMA
    // ============================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val candidates = listOf(
            "$mainUrl/?s=${query.encodeUrl()}",
            "$mainUrl/search?q=${query.encodeUrl()}",
        )

        for (candidate in candidates) {
            val html = try {
                app.get(candidate, headers = headers).text
            } catch (e: Exception) {
                continue
            }
            val document = Jsoup.parse(html, candidate)
            val cards = HDFilmDelisiMarkup.parseCards(document)
            if (cards.isNotEmpty()) return cards.map { it.toSearchResponse() }

            // RSC flight payload içinde kalmış slug'lar için yedek yol
            val fallback = HDFilmDelisiMarkup.fallbackSlugs(html, mainUrl)
            if (fallback.isNotEmpty()) return fallback.map { it.toSearchResponse() }
        }
        return emptyList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    private fun CardData.toSearchResponse(): SearchResponse {
        val type = if (isSeries) TvType.TvSeries else TvType.Movie
        val absoluteHref = fixUrlNull(href) ?: href
        return if (isSeries) {
            newTvSeriesSearchResponse(title, absoluteHref, type) {
                this.posterUrl = fixUrlNull(poster)
            }
        } else {
            newMovieSearchResponse(title, absoluteHref, type) {
                this.posterUrl = fixUrlNull(poster)
            }
        }
    }

    // ============================================================
    // DETAY
    // ============================================================
    override suspend fun load(url: String): LoadResponse? {
        val response = try {
            app.get(url, headers = headers)
        } catch (e: Exception) {
            throw ErrorLoadingException("Siteye ulaşılamıyor: ${e.message}")
        }
        val document = response.document
        val detail = HDFilmDelisiMarkup.parseDetail(document)
        if (detail.title.isBlank()) return null

        val isSeries = url.contains("/dizi/")
        val tvType = if (isSeries) TvType.TvSeries else TvType.Movie

        // TMDB zenginleştirmesi tamamen opsiyoneldir; hata video akışını bozmaz.
        val tmdb = runCatching {
            com.lagradost.api.Log.d(name, "TMDB araması: ${detail.title}")
            TmdbHelper.getTmdbDataByName(detail.title, tvType, detail.year, detail.plot)
        }.getOrNull()

        val poster = detail.poster ?: tmdb?.finalPoster
        val plot = detail.plot ?: tmdb?.finalPlot
        val year = detail.year ?: tmdb?.finalYear
        val duration = detail.duration ?: tmdb?.finalDuration
        val imdbId = tmdb?.imdbId
        val actors = (detail.actors.map { Actor(it.name, it.image) })
            .ifEmpty { tmdb?.actors?.map { it.actor } ?: emptyList() }

        fun buildPayload(pageUrl: String, season: Int? = null, episode: Int? = null): String =
            mapper.writeValueAsString(
                mapOf(
                    "url" to pageUrl,
                    "type" to if (isSeries) "series" else "movie",
                    "season" to season,
                    "episode" to episode,
                    "tmdbId" to tmdb?.tmdbId,
                    "imdbId" to imdbId,
                ),
            )

        return if (isSeries) {
            val episodeData = loadSeriesEpisodes(url, document, detail.seasonCount)
            com.lagradost.api.Log.d(
                name,
                "Dizi bölümleri: ${episodeData.size}/${detail.episodeCount ?: "?"}, " +
                    "sezon: ${detail.seasonCount ?: episodeData.map { it.season }.distinct().size}",
            )
            val episodes = episodeData.map { item ->
                newEpisode(buildPayload(item.href, item.season, item.episode)) {
                    this.name = item.name
                    this.season = item.season
                    this.episode = item.episode
                    this.posterUrl = fixUrlNull(item.poster) ?: fixUrlNull(poster)
                    // CloudStream's Episode.runTime is expressed in seconds.
                    this.runTime = item.runtimeSeconds
                }
            }

            // Boş listeyi sahte bir 1. Bölüm ile gizleme: uygulama yalnızca
            // sitede gerçekten bulunan bölüm URL'lerini göstermeli.
            newTvSeriesLoadResponse(detail.title, url, TvType.TvSeries, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.tags = detail.tags.ifEmpty { tmdb?.tags }
                this.score = detail.rating?.let { Score.from10(it) }
                this.duration = duration
                addActors(actors)
                (detail.trailerYoutubeId ?: tmdb?.trailerId)?.let {
                    addTrailer("https://www.youtube.com/watch?v=$it")
                }
            }
        } else {
            newMovieLoadResponse(detail.title, url, TvType.Movie, buildPayload(url)) {
                this.posterUrl = fixUrlNull(poster)
                this.plot = plot
                this.year = year
                this.tags = detail.tags.ifEmpty { tmdb?.tags }
                this.score = detail.rating?.let { Score.from10(it) }
                this.duration = duration
                addActors(actors)
                (detail.trailerYoutubeId ?: tmdb?.trailerId)?.let {
                    addTrailer("https://www.youtube.com/watch?v=$it")
                }
            }
        }
    }

    /**
     * Live sayfada ilk seçili sezon render edilir. Sezon sayısı sayfa metaverisinde
     * varsa her diğer sezonun gerçek ilk bölüm sayfasını alıp o sezonun SSR bölüm
     * bağlantılarını toplar. İstekler sınırlı paralellikte yapılır; tek sezon hatası
     * dizinin diğer sezonlarını düşürmez.
     */
    private suspend fun loadSeriesEpisodes(
        seriesUrl: String,
        seriesDocument: Document,
        declaredSeasonCount: Int?,
    ): List<EpisodeData> {
        val initialEpisodes = HDFilmDelisiMarkup.parseEpisodes(seriesDocument, seriesUrl)
        val seasons = HDFilmDelisiMarkup.parseSeasonNumbers(seriesDocument, initialEpisodes)
            .ifEmpty {
                declaredSeasonCount?.takeIf { it in 1..100 }?.let { (1..it).toList() }.orEmpty()
            }
        val knownSeasons = initialEpisodes.map { it.season }.toSet()
        val missingSeasons = seasons.filterNot { it in knownSeasons }
        val additionalEpisodes = missingSeasons.chunked(4).flatMap { batch ->
            coroutineScope {
                batch.map { season ->
                    async {
                        val seasonPageUrl = HDFilmDelisiMarkup.firstEpisodeUrl(seriesUrl, season)
                        try {
                            val seasonDocument = app.get(seasonPageUrl, headers = headers).document
                            HDFilmDelisiMarkup.parseEpisodes(seasonDocument, seriesUrl)
                                .filter { it.season == season }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            com.lagradost.api.Log.e(
                                name,
                                "Sezon $season bölüm listesi alınamadı ($seasonPageUrl): ${error.message}",
                            )
                            emptyList()
                        }
                    }
                }.awaitAll().flatten()
            }
        }

        return (initialEpisodes + additionalEpisodes)
            .distinctBy { "${it.season}-${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))
    }

    // ============================================================
    // VİDEO BAĞLANTILARI
    // ============================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val linkData = runCatching {
            mapper.readValue(data, LinkData::class.java)
        }.getOrNull()
        val targetUrl = linkData?.url?.takeIf { it.isNotBlank() } ?: data

        val pageHtml = try {
            app.get(targetUrl, headers = headers).text
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            com.lagradost.api.Log.e(name, "Sayfa indirilemedi: ${e.message}")
            return false
        }

        val emittedSubtitleUrls = HashSet<String>()
        val emitSubtitle: (SubtitleFile) -> Unit = { subtitle ->
            val url = subtitle.url.trim()
            if (url.isNotBlank() && emittedSubtitleUrls.add(url)) subtitleCallback(subtitle)
        }

        var foundVideo = false
        val emittedVideoKeys = HashSet<String>()
        val emitVideo: (ExtractorLink) -> Unit = { link ->
            val uniqueKey = "${link.source}|${link.name}|${link.url}|${link.type}|${link.quality}|${link.referer}"
            if (link.url.isNotBlank() && emittedVideoKeys.add(uniqueKey)) {
                foundVideo = true
                com.lagradost.api.Log.d(
                    name,
                    "Video callback: ${link.source}/${link.name} ${link.type} ${link.url.substringBefore('?')}",
                )
                callback(link)
            }
        }
        val refs = HDFilmDelisiPlayerParser.playerRefs(pageHtml)
        com.lagradost.api.Log.d(name, "Bulunan oynatıcı referansları: ${refs.size}")

        refs.forEach { ref ->
            try {
                when (ref.kind) {
                    PlayerVideoRef.Kind.VIDMODY -> {
                        VidMody().getUrl(ref.url, targetUrl, emitSubtitle, emitVideo)
                    }

                    PlayerVideoRef.Kind.DIRECT_MEDIA -> {
                        emitMediaSource(
                            mediaUrl = ref.url,
                            sourceName = "$name Doğrudan Kaynak",
                            referer = targetUrl,
                            subtitleCallback = emitSubtitle,
                            callback = emitVideo,
                        )
                    }

                    PlayerVideoRef.Kind.EMBED -> {
                        foundVideo = resolveEmbedPage(ref.url, targetUrl, emitSubtitle, emitVideo) || foundVideo
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                com.lagradost.api.Log.e(name, "Kaynak çözülemedi (${ref.url}): ${error.message}")
            }
        }

        // Fallback'lar yalnızca daha önce gerçek bir video-link callback'i oluşmadıysa denenir.
        if (!foundVideo) {
            foundVideo = resolveGenericFallbacks(pageHtml, targetUrl, emitSubtitle, emitVideo)
        }

        // Harici altyazı servisleri (IMDB kimliği varsa).
        val imdbId = linkData?.imdbId
        if (!imdbId.isNullOrBlank()) {
            invokeSubtitleAPI(imdbId, linkData.season, linkData.episode, emitSubtitle)
            invokeTurkceAltyaziAPI(imdbId, linkData.season, linkData.episode, emitSubtitle)
            invokeWyZIESUBAPI(imdbId, linkData.season, linkData.episode, emitSubtitle)
        }

        return foundVideo
    }

    private suspend fun emitMediaSource(
        mediaUrl: String,
        sourceName: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val trimmedUrl = mediaUrl.trim()
        if (trimmedUrl.isBlank()) return false
        val absoluteUrl = HDFilmDelisiPlayerParser.resolveUrl(referer, trimmedUrl)
            .takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: fixUrl(trimmedUrl)
        val sourceHeaders = headers + mapOf("Referer" to referer)

        if (absoluteUrl.contains(".m3u8", ignoreCase = true)) {
            HDFilmDelisiHlsResolver.emit(
                url = absoluteUrl,
                source = sourceName,
                referer = referer,
                headers = sourceHeaders,
                subtitleCallback = subtitleCallback,
                callback = callback,
            )
        } else {
            callback(
                newExtractorLink(
                    source = name,
                    name = sourceName,
                    url = absoluteUrl,
                    type = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = referer
                    this.quality = Qualities.Unknown.value
                    this.headers = sourceHeaders
                },
            )
        }
        return true
    }

    /** /embed/ sayfası içindeki <video>/<source> ve script tabanlı akışları çözer. */
    private suspend fun resolveEmbedPage(
        embedUrl: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val embedHtml = try {
            app.get(embedUrl, headers = headers + mapOf("Referer" to pageUrl)).text
        } catch (error: CancellationException) {
            throw error
        } catch (e: Exception) {
            com.lagradost.api.Log.e(name, "Embed alınamadı ($embedUrl): ${e.message}")
            return false
        }
        var found = false
        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank()) {
                found = true
                callback(link)
            }
        }
        val document = Jsoup.parse(embedHtml, embedUrl)
        val embedHeaders = headers + mapOf("Referer" to embedUrl)

        document.select("video source[src], video[src]").forEach { source ->
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            emitMediaSource(
                mediaUrl = source.absUrl("src").ifBlank { fixUrl(src) },
                sourceName = "$name Embed",
                referer = embedUrl,
                subtitleCallback = subtitleCallback,
                callback = emitLink,
            )
        }

        document.select("track[src]").forEach { track ->
            val kind = track.attr("kind").lowercase()
            if (kind.isNotBlank() && kind !in setOf("subtitles", "captions")) return@forEach
            val src = track.absUrl("src").ifBlank { fixUrl(track.attr("src")) }
            val language = track.attr("srclang").ifBlank {
                track.attr("label").ifBlank { "Unknown" }
            }
            if (src.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(language, src) {
                        this.headers = embedHeaders
                    },
                )
            }
        }

        HDFilmDelisiPlayerParser.mediaFileFromScript(embedHtml)?.let { file ->
            emitMediaSource(
                mediaUrl = file,
                sourceName = "$name Embed",
                referer = embedUrl,
                subtitleCallback = subtitleCallback,
                callback = emitLink,
            )
        }

        document.select("iframe[src], iframe[data-src]").forEach { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
            if (src.isNotBlank() && adFrameKeywords.none { src.contains(it, ignoreCase = true) }) {
                val nestedUrl = frame.absUrl("src").ifBlank { fixUrl(src) }
                try {
                    loadExtractor(nestedUrl, embedUrl, subtitleCallback, emitLink)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    com.lagradost.api.Log.e(name, "İç embed çözülemedi ($nestedUrl): ${error.message}")
                }
            }
        }
        return found
    }

    /** Bilinen oynatıcı işaretleri yoksa tüm iframe ve scriptleri tarar. */
    private suspend fun resolveGenericFallbacks(
        html: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var found = false
        val emitLink: (ExtractorLink) -> Unit = { link ->
            if (link.url.isNotBlank()) {
                found = true
                callback(link)
            }
        }
        val document = Jsoup.parse(html, pageUrl)
        val pageHeaders = headers + mapOf("Referer" to pageUrl)

        document.select("video source[src], video[src]").forEach { source ->
            val src = source.attr("src").takeIf { it.isNotBlank() } ?: return@forEach
            emitMediaSource(
                mediaUrl = source.absUrl("src").ifBlank { fixUrl(src) },
                sourceName = "$name Sayfa Kaynağı",
                referer = pageUrl,
                subtitleCallback = subtitleCallback,
                callback = emitLink,
            )
        }

        document.select("track[src]").forEach { track ->
            val kind = track.attr("kind").lowercase()
            if (kind.isNotBlank() && kind !in setOf("subtitles", "captions")) return@forEach
            val src = track.absUrl("src").ifBlank { fixUrl(track.attr("src")) }
            val language = track.attr("srclang").ifBlank {
                track.attr("label").ifBlank { "Unknown" }
            }
            if (src.isNotBlank()) {
                subtitleCallback(
                    newSubtitleFile(language, src) {
                        this.headers = pageHeaders
                    },
                )
            }
        }

        document.select("iframe[src], iframe[data-src]").forEach { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
            if (src.isNotBlank() && adFrameKeywords.none { src.contains(it, ignoreCase = true) }) {
                val absolute = frame.absUrl("src").ifBlank { fixUrl(src) }
                try {
                    if (absolute.contains("vidmody", ignoreCase = true)) {
                        VidMody().getUrl(absolute, pageUrl, subtitleCallback, emitLink)
                    } else {
                        loadExtractor(absolute, pageUrl, subtitleCallback, emitLink)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    com.lagradost.api.Log.e(name, "Yedek iframe çözülemedi ($absolute): ${error.message}")
                }
            }
        }

        HDFilmDelisiPlayerParser.mediaFileFromScript(html)?.let { file ->
            emitMediaSource(
                mediaUrl = file,
                sourceName = "$name Script",
                referer = pageUrl,
                subtitleCallback = subtitleCallback,
                callback = emitLink,
            )
        }
        return found
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
