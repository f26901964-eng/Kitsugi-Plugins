// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

// AnimeciX /secure/* uç noktalarının istediği şifreli başlık (site istemcisinden).
private const val X_E_H = "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"

// Sayfa gövdesinde bulunabilecek düz oynatma adresleri (m3u8 / mp4).
private val STREAM_URL_REGEX = Regex("""(?i)https?://[^\s"'<>]+?(?:\.m3u8|\.mp4)(?:\?[^\s"'<>]*)?""")

// best-video bazen doğrudan oynatıcı yerine iframe içeren bir ara sayfa döndürür.
private val IFRAME_SRC_REGEX = Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""")

class AnimeciX : MainAPI() {
    override var mainUrl              = "https://animecix.tv"
    override var name                 = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    override var sequentialMainPageDelay       = 200L  // ? 0.20 saniye
    override var sequentialMainPageScrollDelay = 200L  // ? 0.20 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/secure/last-episodes"                          to "Son Eklenen Bölümler",
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true"  to "Filmler",
    )

    // /secure/* uç noktaları "x-e-h" başlığı olmadan 403/boş dönüyor.
    // Başlık tek yerde tutulur; arama, ana sayfa ve içerik istekleri aynı şeyi gönderir.
    private fun secureHeaders() = mapOf(
        "x-e-h"   to X_E_H,
        "Referer" to "${mainUrl}/"
    )

    // data hem göreli (/secure/videos/...) hem de mutlak (https://animecix.tv/...)
    // gelebilir. Eski sürümler "$mainUrl/$data" ile birleştirdiği için mutlak
    // adreste "https://animecix.tv/https://animecix.tv/..." oluşuyordu.
    private fun repairEpisodeUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var url = raw.trim()
        Regex("^https?://[^/]+/(https?://.+)$").find(url)?.let { url = it.groupValues[1] }
        return fixUrl(url)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.contains("/last-episodes")) {
            val response = app.get(
                "${mainUrl}/secure/last-episodes?page=$page&perPage=10",
                headers = secureHeaders()
            ).parsedSafe<LastEpisodesResponse>()?.data ?: emptyList()
    
            val home = response.map {
                val formattedTitle = "S${it.seasonNumber}B${it.episodeNumber} - ${it.titleName}"
                newAnimeSearchResponse(
                    formattedTitle,
                    "${mainUrl}/secure/titles/${it.titleId}?titleId=${it.titleId}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(it.titlePoster)
                }
            }
    
            newHomePageResponse(request.name, home)
        } else {
            val response = app.get(
                "${request.data}&page=${page}&perPage=16",
                headers = secureHeaders()
            ).parsedSafe<Category>()
    
            val home = response?.pagination?.data?.map { anime ->
                newAnimeSearchResponse(
                    anime.title,
                    "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                    TvType.Anime
                ) {
                    this.posterUrl = fixUrlNull(anime.poster)
                }
            } ?: listOf()
    
            newHomePageResponse(request.name, home)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ! ÖNEMLİ: /secure/* uç noktaları "x-e-h" başlığı olmadan 403/boş dönüyor.
        // ! getMainPage ve load bu başlığı gönderiyordu, arama göndermiyordu —
        // ! bu yüzden eklenti arama sonuçlarında hep boş dönüyordu.
        val response = app.get(
            "${mainUrl}/secure/search/${query}?limit=20",
            headers = secureHeaders()
        ).parsedSafe<Search>() ?: return listOf()

        return response.results.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val response = app.get(
            url,
            headers = secureHeaders()
        ).parsedSafe<Title>() ?: return null
        val episodes = mutableListOf<Episode>()
        val titleId  = url.substringAfter("?titleId=")

        if (response.title.titleType == "anime") {
            for (sezon in response.title.seasons) {
                // Bölüm listesi de /secure/* altında; başlık gönderilmezse boş dönebiliyor.
                // Tek sezonun hatası tüm bölüm listesini düşürmesin.
                val sezonResponse = app.get(
                    "${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}",
                    headers = secureHeaders()
                ).parsedSafe<TitleVideos>() ?: continue
                for (video in sezonResponse.videos) {
                    episodes.add(newEpisode(video.url) {
                        this.name = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                        this.season = video.seasonNum
                        this.episode = video.episodeNum
                    })
                }
            }
        } else {
            if (response.title.videos.isNotEmpty()) {
                episodes.add(newEpisode(response.title.videos.first().url) {
                    this.name    = "Filmi İzle"
                    this.season  = 1
                    this.episode = 1
                })
            }
        }


        return newTvSeriesLoadResponse(
            response.title.title,
            "${mainUrl}/secure/titles/${response.title.id}?titleId=${response.title.id}",
            TvType.Anime,
            episodes
        ) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.year      = response.title.year
            this.plot      = response.title.description
            this.tags      = response.title.tags.map { it.name }
            addActors(response.title.actors.map { Actor(it.name, fixUrlNull(it.poster)) })
            addTrailer(response.title.trailer)
        }
    }
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("ACX", "data » $data")
        val pageUrl = repairEpisodeUrl(data) ?: return false
        Log.d("ACX", "pageUrl » $pageUrl")

        var emitted = false
        val relay: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        val firstResponse = try {
            app.get(pageUrl, headers = secureHeaders())
        } catch (e: Exception) {
            Log.e("ACX", "Bölüm sayfası alınamadı: $pageUrl", e)
            return false
        }

        var finalUrl  = repairEpisodeUrl(firstResponse.url) ?: pageUrl
        var finalHtml = firstResponse.text
        Log.d("ACX", "iframeLink » $finalUrl")

        // /secure/best-video bir yönlendirme uç noktası; gerçek oynatıcıya
        // (genelde tau-video embed) gider. Adres zaten çözüldüyse tekrar istemeyiz.
        if (finalUrl.contains("/secure/best-video")) {
            val redirectResponse = try {
                app.get(finalUrl, headers = secureHeaders())
            } catch (e: Exception) {
                Log.e("ACX", "best-video yönlendirmesi alınamadı: $finalUrl", e)
                null
            }
            if (redirectResponse != null) {
                finalUrl  = repairEpisodeUrl(redirectResponse.url) ?: finalUrl
                finalHtml = redirectResponse.text
                Log.d("ACX", "Redirected final URL » $finalUrl")
            }
        }

        // 1) Kayıtlı extractor'lar (tau-video dahil). Eskiden yalnızca adres
        //    "tau-video" içeriyorsa deneniyordu; site başka oynatıcıya geçerse
        //    hiç link üretilmiyordu.
        try {
            loadExtractor(finalUrl, "${mainUrl}/", subtitleCallback, relay)
        } catch (e: Exception) {
            Log.e("ACX", "Extractor hatası: $finalUrl", e)
        }

        // 2) best-video ara sayfa döndürdüyse içindeki iframe'leri dene.
        if (!emitted) {
            for (src in iframeSources(finalHtml)) {
                val iframeUrl = repairEpisodeUrl(src) ?: continue
                try {
                    loadExtractor(iframeUrl, "${mainUrl}/", subtitleCallback, relay)
                } catch (e: Exception) {
                    Log.e("ACX", "iframe extractor hatası: $iframeUrl", e)
                }
                if (emitted) break
            }
        }

        // 3) Son çare: düz m3u8/mp4 ya da base64'e gömülü adres.
        if (!emitted) {
            val direct = extractDirectStream(finalUrl) ?: extractDirectStream(finalHtml)
            if (direct != null) {
                relay(
                    newExtractorLink(
                        source = this.name,
                        name   = this.name,
                        url    = direct,
                        type   = INFER_TYPE
                    ) {
                        referer = "${mainUrl}/"
                        headers = mapOf("Referer" to "${mainUrl}/")
                    }
                )
                Log.d("ACX", "Fallback stream » $direct")
            }
        }

        if (!emitted) Log.e("ACX", "Hiçbir kaynak bulunamadı: $finalUrl")
        return emitted
    }
}

// Düz oynatma adresi bulur: önce metnin kendisinde, sonra base64'e gömülü
// parçalarda arar. Yalnızca extractor hiç sonuç vermediğinde kullanılır.
private fun extractDirectStream(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    STREAM_URL_REGEX.find(raw)?.let { return it.value }

    Regex("[A-Za-z0-9+/_=-]{24,}").findAll(raw).forEach { match ->
        try {
            val decoded = String(
                Base64.decode(match.value, Base64.DEFAULT or Base64.URL_SAFE),
                Charsets.UTF_8
            )
            STREAM_URL_REGEX.find(decoded)?.let { return it.value }
        } catch (ignored: IllegalArgumentException) {
            // base64 değil; sonraki adaya geç
        }
    }
    return null
}

private fun iframeSources(html: String?): List<String> =
    if (html.isNullOrBlank()) emptyList()
    else IFRAME_SRC_REGEX.findAll(html).map { it.groupValues[1] }.toList()
