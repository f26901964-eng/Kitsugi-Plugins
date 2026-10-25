//Eklenti kraptor reposundan çekilip hdfilmdelisi.one Next.js yapısına göre yeniden yazılmıştır.
package com.patron

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
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
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.Jsoup

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
            val episodeData = HDFilmDelisiMarkup.parseEpisodes(document, url)
            val episodes = mutableListOf<Episode>()

            episodeData.forEach { item ->
                episodes.add(
                    newEpisode(buildPayload(item.href, item.season, item.episode)) {
                        this.name = item.name
                        this.season = item.season
                        this.episode = item.episode
                        this.posterUrl = poster
                    },
                )
            }

            // Bölüm bağlantısı ayrı sayfa değilse (oynatıcı aynı sayfada) tek bölüm üret.
            if (episodes.isEmpty()) {
                episodes.add(
                    newEpisode(buildPayload(url, 1, 1)) {
                        this.name = "1. Bölüm"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    },
                )
            }

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
        } catch (e: Exception) {
            com.lagradost.api.Log.e(name, "Sayfa indirilemedi: ${e.message}")
            return false
        }

        var foundVideo = false
        val refs = HDFilmDelisiPlayerParser.playerRefs(pageHtml)
        com.lagradost.api.Log.d(name, "Bulunan oynatıcı referansları: ${refs.size}")

        refs.forEach { ref ->
            runCatching {
                when (ref.kind) {
                    PlayerVideoRef.Kind.VIDMODY -> {
                        VidMody().getUrl(ref.url, targetUrl, subtitleCallback) { link ->
                            foundVideo = true
                            callback(link)
                        }
                    }

                    PlayerVideoRef.Kind.DIRECT_MEDIA -> {
                        val type = if (ref.url.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name Doğrudan Kaynak",
                                url = fixUrl(ref.url),
                                type = type,
                            ) {
                                this.referer = targetUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = this@HDFilmDelisi.headers
                            },
                        )
                        foundVideo = true
                    }

                    PlayerVideoRef.Kind.EMBED -> {
                        foundVideo = resolveEmbedPage(ref.url, targetUrl, subtitleCallback, callback) || foundVideo
                    }
                }
            }.onFailure { error ->
                com.lagradost.api.Log.e(name, "Kaynak çözülemedi (${ref.url}): ${error.message}")
            }
        }

        // Referanslar hiç bulunamazsa genel iframe/script yedekleri.
        if (!foundVideo) {
            foundVideo = resolveGenericFallbacks(pageHtml, targetUrl, subtitleCallback, callback)
        }

        // Harici altyazı servisleri (IMDB kimliği varsa).
        val imdbId = linkData?.imdbId
        if (!imdbId.isNullOrBlank()) {
            invokeSubtitleAPI(imdbId, linkData.season, linkData.episode, subtitleCallback)
            invokeTurkceAltyaziAPI(imdbId, linkData.season, linkData.episode, subtitleCallback)
            invokeWyZIESUBAPI(imdbId, linkData.season, linkData.episode, subtitleCallback)
        }

        return foundVideo
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
        } catch (e: Exception) {
            return false
        }
        var found = false
        val document = Jsoup.parse(embedHtml, embedUrl)

        document.select("video source[src], video[src], source[src]").forEach { source ->
            val src = source.attr("src").ifBlank { null } ?: return@forEach
            val type = if (src.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Embed",
                    url = fixUrl(src),
                    type = type,
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = this@HDFilmDelisi.headers
                },
            )
            found = true
        }

        HDFilmDelisiPlayerParser.mediaFileFromScript(embedHtml)?.let { file ->
            val type = if (file.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Embed",
                    url = file,
                    type = type,
                ) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = this@HDFilmDelisi.headers
                },
            )
            found = true
        }

        document.select("iframe[src]").forEach { frame ->
            val src = frame.attr("src")
            if (adFrameKeywords.none { src.contains(it, ignoreCase = true) }) {
                loadExtractor(fixUrl(src), pageUrl, subtitleCallback, callback)
                found = true
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
        val document = Jsoup.parse(html, pageUrl)

        document.select("iframe[src], iframe[data-src]").forEach { frame ->
            val src = frame.attr("src").ifBlank { frame.attr("data-src") }
            if (src.isNotBlank() && adFrameKeywords.none { src.contains(it, ignoreCase = true) }) {
                val absolute = fixUrl(src)
                if (absolute.contains("vidmody", ignoreCase = true)) {
                    VidMody().getUrl(absolute, pageUrl, subtitleCallback, callback)
                    found = true
                } else {
                    loadExtractor(absolute, pageUrl, subtitleCallback, callback)
                    found = true
                }
            }
        }

        HDFilmDelisiPlayerParser.mediaFileFromScript(html)?.let { file ->
            val type = if (file.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            callback(
                newExtractorLink(
                    source = name,
                    name = "$name Script",
                    url = file,
                    type = type,
                ) {
                    this.referer = pageUrl
                    this.quality = Qualities.Unknown.value
                    this.headers = this@HDFilmDelisi.headers
                },
            )
            found = true
        }
        return found
    }

    private fun String.encodeUrl(): String =
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}
