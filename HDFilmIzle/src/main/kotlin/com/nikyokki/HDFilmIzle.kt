package com.nikyokki

import Video
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class HDFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.to"
    override var name = "HDFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon/" to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel/" to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/dram/" to "Dram Filmleri",
        "${mainUrl}/tur/fantastik/" to "Fantastik Filmleri",
        "${mainUrl}/tur/gerilim/" to "Gerilim Filmleri",
        "${mainUrl}/tur/gizem/" to "Gizem Filmleri",
        "${mainUrl}/tur/komedi/" to "Komedi Filmleri",
        "${mainUrl}/tur/korku/" to "Korku Filmleri",
        "${mainUrl}/tur/macera/" to "Macera Filmleri",
        "${mainUrl}/tur/romantik/" to "Romantik Filmler",
        "${mainUrl}/tur/savas/" to "Savaş Filmleri",
        "${mainUrl}/tur/suc/" to "Suç Filmleri",
        "${mainUrl}/tur/tarih/" to "Tarih Filmleri",
        "${mainUrl}/tur/vahsi-bati/" to "Vahşi Batı Filmleri",
        "${mainUrl}/tur/yerli-film-izle/" to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home: List<SearchResponse>?

        home = document.select("div#moviesListResult a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.title")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val score = this.selectFirst("div.poster-imdb")?.text()?.trim()

        return if (HDFilmIzleParser.isSeriesUrl(href)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = mainUrl,
            data = mapOf("query" to query)
        ).document
        val searchResults = mutableListOf<SearchResponse>()

        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        try {
            val videos: List<Video> = objectMapper.readValue(response.body().text())
            videos.forEach { video ->
                val title = video.name
                val href = fixUrlNull(video.slug) ?: return@forEach
                val posterUrl = fixUrlNull(video.thumbUrl) ?: fixUrlNull(video.thumbWebp)

                val result = if (HDFilmIzleParser.isSeriesUrl(href)) {
                    newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    }
                } else {
                    newMovieSearchResponse(title, href, TvType.Movie) {
                        this.posterUrl = posterUrl
                    }
                }
                searchResults.add(result)
            }
        } catch (e: Exception) {
            println("Error parsing JSON: ${e.message}")
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val orgTitle = document.selectFirst("div.page-title h1")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: return null
        val altTitle = document.selectFirst("div.page-title")
            ?.selectFirst("small.text-muted.alt-name")?.text()?.trim().orEmpty()
        val title = if (altTitle.isNotEmpty() && orgTitle != altTitle) "$orgTitle - $altTitle" else orgTitle
        val poster = fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("data-src"))
        val tags = document.select("div.pb-2.genres a").map { it.text() }
        val year = document.selectFirst("div.page-title")?.selectFirst("small.text-muted")?.text()
            ?.replace("(", "")?.replace(")", "")?.toIntOrNull()
        val description = document.selectFirst("article.text-white > p")?.text()?.trim()
        val rating = document.selectFirst("div.rate.mb-2 span")?.text()
        val actors = document.select("div.stories-wrapper a").mapNotNull { actorElement ->
            val actorName = actorElement.selectFirst("div.story-item-title")?.text()?.trim()
                ?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Actor(actorName, fixUrlNull(actorElement.select("img").attr("data-src")))
        }

        val recommendations = document.select("div#swiper-wrapper-benzer").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }
        val trailer = document.selectFirst("div.nav-link")?.attr("data-trailer")

        if (HDFilmIzleParser.isSeriesUrl(url)) {
            val episodes = document.select("a[href]").mapNotNull { anchor ->
                val episodeUrl = fixUrlNull(anchor.attr("href")) ?: return@mapNotNull null
                val episode = HDFilmIzleParser.parseEpisodeUrl(episodeUrl) ?: return@mapNotNull null
                newEpisode(episode.url) {
                    this.name = episode.name
                    this.season = episode.season
                    this.episode = episode.episode
                }
            }.distinctBy { it.data }
            if (episodes.isEmpty()) return null

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.score = Score.from10(rating)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDF", "loadLinks » $data")
        val document = app.get(data, referer = "$mainUrl/").document
        val html = document.outerHtml()

        // parts blokları + statik iframe'ler + script içi oynatıcı URL'leri.
        // Sitede sunulan HER oynatıcı ve HER ses sürümü ayrı bir çerçevedir.
        val frames = HDFilmIzlePlayerParser.pageFrames(html, data)
        Log.d("HDF", "oynatıcı çerçeveleri » ${frames.size}")

        var delivered = false
        frames.forEach { frame ->
            Log.d("HDF", "çerçeve » ${frame.playerName.ifBlank { frame.url }} (${frame.lang})")
            val langTag = HDFilmIzlePlayerParser.languageLabel(frame.lang)
            val wrapped: (ExtractorLink) -> Unit = { link ->
                val base = link.name.ifBlank { frame.playerName.ifBlank { link.source } }
                callback(if (langTag.isNotBlank()) link.copy(name = "$base • $langTag") else link)
            }

            val handled = runCatching {
                HDFilmIzlePlayerResolver.resolve(frame, data, subtitleCallback, wrapped)
            }.getOrDefault(false)

            if (handled) {
                delivered = true
            } else {
                // Vidmoly ve uygulamanın bildiği diğer gömülü oynatıcılar.
                if (runCatching { loadExtractor(frame.url, data, subtitleCallback, wrapped) }.getOrDefault(false)) {
                    delivered = true
                }
            }
        }

        // Yedek: bazı temalar oynatıcıları admin-ajax get_video_url üzerinden verir.
        if (!delivered) delivered = resolveViaAdminAjax(html, data, subtitleCallback, callback)

        Log.d("HDF", "kaynak teslim edildi » $delivered")
        return delivered
    }

    /**
     * WordPress teması admin-ajax `get_video_url` uç noktasını kullanıyorsa
     * sayfadaki nonce/post/oynatıcı izleriyle oynatıcı URL'lerini elde eder.
     */
    private suspend fun resolveViaAdminAjax(
        html: String,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val config = HDFilmIzlePlayerParser.ajaxPlayerConfig(html)
        if (config.postIds.isEmpty() || config.nonces.isEmpty()) return false
        val players = config.playerNames.ifEmpty { listOf("") }

        for (postId in config.postIds) {
            for (player in players) {
                for (nonce in config.nonces) {
                    val payload = mapOf(
                        "action" to "get_video_url",
                        "nonce" to nonce,
                        "post_id" to postId,
                        "player_name" to player,
                        "part_key" to "",
                    )
                    val response = runCatching {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            headers = mapOf(
                                "X-Requested-With" to "XMLHttpRequest",
                                "Accept" to "application/json, text/javascript, */*; q=0.01",
                            ),
                            referer = pageUrl,
                            data = payload,
                        )
                    }.getOrNull() ?: continue

                    val tree = runCatching { ObjectMapper().readTree(response.text) }.getOrNull() ?: continue
                    if (!tree.path("success").asBoolean(false)) continue
                    val url = tree.path("data").path("url").asText("").replace("\\/", "/")
                    if (url.isBlank()) continue

                    val frame = HDFilmIzlePlayerParser.PageFrame(url)
                    if (HDFilmIzlePlayerResolver.resolve(frame, pageUrl, subtitleCallback, callback)) return true
                    if (runCatching {
                            loadExtractor(url, pageUrl, subtitleCallback, callback)
                        }.getOrDefault(false)
                    ) return true
                }
            }
        }
        return false
    }

    data class Results(
        @JsonProperty("results") val results: List<String> = arrayListOf()
    )
}