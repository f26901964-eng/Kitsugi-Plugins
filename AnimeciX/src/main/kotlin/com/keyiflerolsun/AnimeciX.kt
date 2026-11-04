// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

// AnimeciX /secure/* uç noktalarının istediği şifreli başlık (site istemcisinden).
// ! Son bilinen token. Canlı token alınamazsa yedek olarak kullanılır; asla tek başına
// ! güvenilmez. `resolveToken()` her oturumda önce sayfadan okumayı dener.
private const val X_E_H = "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"

/** Canlı token okuması için üst sınır; ana sayfa yavaşsa eklentiyi bekletmesin. */
private const val TOKEN_TIMEOUT_MS = 8_000L

/** Tek sezonun bölüm listesi için üst sınır (paralel çalıştığı için toplam büyümez). */
private const val SEASON_TIMEOUT_MS = 15_000L

// Sayfa gövdesinde bulunabilecek düz oynatma adresleri (m3u8 / mp4).
private val STREAM_URL_REGEX = Regex("""(?i)https?://[^\s"'<>]+?(?:\.m3u8|\.mp4)(?:\?[^\s"'<>]*)?""")

// best-video bazen doğrudan oynatıcı yerine iframe içeren bir ara sayfa döndürür.
private val IFRAME_SRC_REGEX = Regex("""(?i)<iframe[^>]+src=["']([^"']+)["']""")

class AnimeciX : MainAPI() {
    override var mainUrl              = "https://animecix.tv"
    override var name                 = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    // ! `SearchViewModel` hızlı aramada `hasQuickSearch=false` olan eklentileri
    // ! TAMAMEN atlıyor (SearchViewModel.kt:233). `quickSearch()` zaten vardı ama
    // ! kapı kapalı olduğu için AnimeciX arama ekranında hiç görünmüyordu.
    override val hasQuickSearch       = true
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
    // ! Token SABİT DEĞİL: site onu ana sayfa/JS yükü içinde üretiyor ve döndürüyor.
    // ! Burada sabit tutulduğu sürece token eskidiği anda eklenti tamamen ölüyordu.
    // ! Önce canlı token denenir, bulunamazsa bilinen son değer kullanılır.
    @Volatile private var cachedToken: String? = null

    private suspend fun resolveToken(): String {
        cachedToken?.let { return it }

        val fromSite = runCatching {
            withTimeoutOrNull(TOKEN_TIMEOUT_MS) {
                val html = app.get(mainUrl, headers = mapOf("Referer" to "$mainUrl/")).text
                Regex("""['"]x-e-h['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            }
        }.getOrNull()

        val token = fromSite?.takeIf { it.isNotBlank() } ?: X_E_H
        if (!fromSite.isNullOrBlank()) Log.d("ACX", "x-e-h canlı token alındı")
        else Log.w("ACX", "x-e-h canlı token bulunamadı, bilinen değer kullanılıyor")
        cachedToken = token
        return token
    }

    /** Token'ı bir kez tazeler (403 alındığında çağrılır). */
    private suspend fun refreshToken(): String {
        cachedToken = null
        return resolveToken()
    }

    private suspend fun secureHeaders(): Map<String, String> = mapOf(
        "x-e-h"   to resolveToken(),
        "Referer" to "$mainUrl/"
    )

    /**
     * `/secure` uç noktalarına istek; 403/boş yanıtta token'ı **bir kez** tazeleyip tekrar dener.
     *
     * ! Token süresi dolduğunda eklenti eskiden tamamen sessizce boş dönüyordu.
     * ! Artık tek bir tazeleme hakkı var; bu, token rotasyonunu kendi kendine atlatır.
     */
    private suspend fun getSecure(url: String): String {
        val first = app.get(url, headers = secureHeaders())
        if (first.code != 403 && first.text.isNotBlank()) return first.text

        Log.w("ACX", "secure istek başarısız (${first.code}), token tazeleniyor: $url")
        val retry = app.get(url, headers = secureHeaders().plus("x-e-h" to refreshToken()))
        return retry.text
    }

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
                    this.score     = anime.toScore()
                }
            } ?: listOf()

            // ! Sayfalama verisi vardı ama `hasNext` hiç verilmiyordu → Keşfet tek sayfada kalıyordu.
            val hasNext = response?.pagination?.let { it.currentPage < it.lastPage } ?: false
            newHomePageResponse(request.name, home, hasNext = hasNext)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // ! ÖNEMLİ: /secure/* uç noktaları "x-e-h" başlığı olmadan 403/boş dönüyor.
        // ! getMainPage ve load bu başlığı gönderiyordu, arama göndermiyordu —
        // ! bu yüzden eklenti arama sonuçlarında hep boş dönüyordu.
        // ! Sorgu URL'e gömülüyor; boşluk/özel karakter kaçırılmazsa 404 dönüyordu.
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val response = runCatching {
            mapper.readValue(getSecure("${mainUrl}/secure/search/$encoded?limit=30"), Search::class.java)
        }.getOrNull() ?: return listOf()

        return response.results.map { anime ->
            newAnimeSearchResponse(
                anime.title,
                "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}",
                TvType.Anime
            ) {
                this.posterUrl = fixUrlNull(anime.poster)
                this.score     = anime.toScore()
            }
        }
    }

    /** MAL/TMDB puanını CloudStream `Score`'una çevirir; geçersizse null. */
    private fun AnimeSearch.toScore(): Score? {
        val mal = rating?.replace(',', '.')?.toDoubleOrNull()
        if (mal != null && mal > 0) return Score.from10(mal)
        val tmdb = tmdbVoteAverage
        if (tmdb != null && tmdb > 0) return Score.from10(tmdb)
        return null
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
            // ! Sezonlar SIRALI çekiliyordu: 4 sezon × ~3 sn = 12 sn+ ve tek sezonun
            // ! yavaşlığı tüm bölüm listesini geciktiriyordu. Artık paralel.
            val headers = secureHeaders()
            val seasonLists = coroutineScope {
                response.title.seasons.map { sezon ->
                    async {
                        runCatching {
                            withTimeoutOrNull(SEASON_TIMEOUT_MS) {
                                app.get(
                                    "${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}",
                                    headers = headers,
                                ).parsedSafe<TitleVideos>()?.videos.orEmpty()
                            }.orEmpty()
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }

            seasonLists.flatten()
                .sortedWith(compareBy({ it.seasonNum }, { it.episodeNum }))
                .forEach { video ->
                    episodes.add(newEpisode(video.url) {
                        this.name    = "${video.seasonNum}. Sezon ${video.episodeNum}. Bölüm"
                        this.season  = video.seasonNum
                        this.episode = video.episodeNum
                    })
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


        val canonicalUrl = "${mainUrl}/secure/titles/${response.title.id}?titleId=${response.title.id}"
        val titleType    = response.title.titleType?.lowercase()

        // ! Filmler de `TvType.Anime` + dizi yanıtıyla dönüyordu; Kitsugi bunları dizi
        // ! olarak etiketliyordu. `movie` tipi ayrı ele alınır.
        if (titleType == "movie" || (titleType == null && response.title.seasons.isEmpty())) {
            val videoUrl = response.title.videos.firstOrNull()?.url ?: url
            return newMovieLoadResponse(
                response.title.title,
                canonicalUrl,
                TvType.Movie,
                videoUrl,
            ) {
                this.posterUrl = fixUrlNull(response.title.poster)
                this.year      = response.title.year
                this.plot      = response.title.description
                this.tags      = response.title.tags.map { it.name }
                addActors(response.title.actors.map { Actor(it.name, fixUrlNull(it.poster)) })
                addTrailer(response.title.trailer)
            }
        }

        return newTvSeriesLoadResponse(
            response.title.title,
            canonicalUrl,
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
