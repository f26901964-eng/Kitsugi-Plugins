package com.kraptor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TrAnimeIzle ana API (DEX'teki com.kraptor.TrAnimeIzle'in kaynak yeniden yazımı).
 *
 * Akış: arama/anasayfa/bölüm listesi → `load` (metadata + Kitsu zenginleştirme +
 * bölümler) → `loadLinks` (fansub → sourcePlayer → iframe → loadExtractor).
 * Bot kontrolü [BotKontrolKt] üzerinden yapılır (WebView oturumu + AitrWeb.Session).
 */
class TrAnimeIzle(
    private val context: Context,
    domainPrefs: SharedPreferences
) : MainAPI() {

    override var mainUrl: String = Companion.getDomain(domainPrefs, "TrAnimeIzle")
    override var name = "TrAnimeIzle"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime)

    // ! Yollar DEX'teki ile birebir aynı (mainUrl + suffix).
    override val mainPage = mainPageOf(
        "$mainUrl/listeler/yenibolum/sayfa-" to "Yeni Bölümler",
        "$mainUrl/listeler/eklenen/sayfa-" to "Yeni Animeler",
        "$mainUrl/listeler/populer/sayfa-" to "Popüler Animeler",
        "$mainUrl/animeizle/aksiyon-anime-izle-" to "Aksiyon",
        "$mainUrl/animeizle/askeri-anime-izle-" to "Askeri",
        "$mainUrl/animeizle/bilim-kurgu-anime-izle-" to "Bilim Kurgu",
        "$mainUrl/animeizle/buyu-anime-izle-" to "Büyü",
        "$mainUrl/animeizle/dogaustu-gucler-anime-izle-" to "Doğaüstü Güçler",
        "$mainUrl/animeizle/dram-anime-izle-" to "Dram",
        "$mainUrl/animeizle/dovus-anime-izle-" to "Dövüş",
        "$mainUrl/animeizle/ecchi-anime-izle-" to "Ecchi",
        "$mainUrl/animeizle/fantastik-anime-izle-" to "Fantastik",
        "$mainUrl/animeizle/gerilim-anime-izle-" to "Gerilim",
        "$mainUrl/animeizle/gizem-anime-izle-" to "Gizem",
        "$mainUrl/animeizle/harem-anime-izle-" to "Harem",
        "$mainUrl/animeizle/komedi-anime-izle-" to "Komedi",
        "$mainUrl/animeizle/korku-anime-izle-" to "Korku",
        "$mainUrl/animeizle/macera-anime-izle-" to "Macera",
        "$mainUrl/animeizle/mecha-anime-izle-" to "Mecha",
        "$mainUrl/animeizle/movie-anime-izle-" to "Film",
        "$mainUrl/animeizle/okul-anime-izle-" to "Okul",
        "$mainUrl/animeizle/psikolojik-anime-izle-" to "Psikolojik",
        "$mainUrl/animeizle/romantizm-anime-izle-" to "Romantizm",
        "$mainUrl/animeizle/seinen-anime-izle-" to "Seinen",
        "$mainUrl/animeizle/shoujo-anime-izle-" to "Shoujo",
        "$mainUrl/animeizle/shoujo-ai-anime-izle-" to "Shoujo Ai",
        "$mainUrl/animeizle/shounen-anime-izle-" to "Shounen",
        "$mainUrl/animeizle/shounen-ai-anime-izle-" to "Shounen Ai",
        "$mainUrl/animeizle/yasamdan-kesitler-anime-izle-" to "Yaşamdan Kesitler",
        "$mainUrl/animeizle/spor-anime-izle-" to "Spor"
    )

    // ------------------------------------------------------------------
    // Arama + ana sayfa
    // ------------------------------------------------------------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        if (!TrAnimeIzleHelper.isAllowedVersion) {
            return newSearchResponseList(emptyList(), false)
        }
        val url = mainUrl + "/arama/" + query + "?page=" + page
        val document = app.get(url).document
        val results = document.select("div.flx-block").mapNotNull { toMainPageResult(it) }
        return newSearchResponseList(results, true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (!TrAnimeIzleHelper.isAllowedVersion) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            val url = request.data + page
            val response = app.get(url)
            if (!response.isSuccessful) {
                throw ErrorLoadingException("Siteye ulaşılamıyor! Tarayıcıdan test edin.")
            }
            val results = response.document
                .select("div.flx-block")
                .mapNotNull { toMainPageResult(it) }
            newHomePageResponse(request.name, results, true)
        } catch (e: Exception) {
            throw ErrorLoadingException("Siteye ulaşılamıyor! Tarayıcıdan test edin.")
        }
    }

    private fun toMainPageResult(el: Element): AnimeSearchResponse? {
        val title = el.selectFirst("h4")?.text()?.replace("İzle", "")?.takeIf { it.isNotBlank() }
            ?: return null
        val url = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(el.selectFirst("img")?.attr("src"))
        val rating = el.selectFirst("rating")?.attr("data-src")
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.score = Score.from10(rating)
        }
    }

    private fun toRecommendationResult(el: Element): AnimeSearchResponse? {
        val title = el.selectFirst("a img")?.attr("alt")?.takeIf { it.isNotBlank() }
            ?: return null
        val url = fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(el.selectFirst("a img")?.attr("data-src"))
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    // ------------------------------------------------------------------
    // Bölüm sayfası (load)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        if (!TrAnimeIzleHelper.isAllowedVersion) return null

        var cookie = BotKontrolKt.getSavedCookie(context, mainUrl)
        Log.d(LOG_TAG, "cookies = $cookie")

        var document = app.get(url, headers = mapOf("Cookie" to cookie)).document

        val pageTitle = document.selectFirst("title")?.text()?.toString() ?: ""
        if (pageTitle.contains("Bot", ignoreCase = true)) {
            cookie = BotKontrolKt.botKontrol(context, mainUrl, forceRefresh = true)
            document = app.get(url, headers = mapOf("Cookie" to cookie)).document
        }

        val banasayfa = fixUrlNull(
            document.selectFirst("ol.breadcrumb li + li:not(.active) a")?.attr("href")
        )
        Log.d(LOG_TAG, "banasayfa = $banasayfa")
        if (!banasayfa.isNullOrEmpty()) {
            document = app.get(banasayfa, headers = mapOf("Cookie" to cookie)).document
        }

        val title = document.selectFirst("div.playlist-title h1")?.text()
            ?.replace("İzle", "")?.trim() ?: ""
        val poster = fixUrlNull(document.selectFirst("div.poster img")?.attr("src"))
        val description = document.select("div.p-10 p")
            .joinToString("\n") { it.text() }
            .ifBlank { null }
        val year = document.selectFirst("div.col-sm-7 dd:contains(Yapım Yılı) + dt")
            ?.text()?.toIntOrNull()
        val tags = document.select("div.tags-inner a").map { it.text() }
        val rating = document.selectFirst("span.dt_rating_vgs")?.text()
        val duration = document.selectFirst("div.col-sm-7 dt:contains(dk)")?.text()
            ?.split(" ")?.firstOrNull()?.toIntOrNull()
        val recommendations = document.select("div.srelacionados article")
            .mapNotNull { toRecommendationResult(it) }
        val actors = document.select("span.valor a").map { it.text() }
        val trailer = Regex("embed/(.*)\\?rel").find(document.html())
            ?.groupValues?.getOrNull(1)?.let { "https://www.youtube.com/embed/$it" }
        val animeTipi = document.selectFirst("div.col-sm-5 dd:contains(Anime Tipi) + dt")?.text()

        // "Bölüm Sayısı: 24/24" → tamamı yayınlanmış (completed) sayılır.
        val totalEpText = document.selectFirst("dd:contains(Bölüm Sayısı) + dt")?.text()?.trim()
        val isCompleted = if (totalEpText != null) {
            val parts = totalEpText.split("/")
            parts.size == 2 && parts[0].trim() == parts[1].trim()
        } else false

        val meta = fetchKitsuDataByTitle(title)

        // İlk bölüm URL'si (film tipi için data alanı).
        val firstEpisodeUrl = fixUrlNull(
            document.selectFirst("div.animeDetail-playlist li a")?.attr("href")
        )

        // Bölüm listesi.
        val episodes = mutableListOf<Episode>()
        val dateFormat = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("tr"))
        for (a in document.select("div.animeDetail-items li a")) {
            val epUrl = fixUrlNull(a.attr("href")) ?: continue
            val rawName = a.selectFirst("div.etitle span")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("p.title")?.text()?.takeIf { it.isNotBlank() }
                ?: a.selectFirst("span")?.text()?.takeIf { it.isNotBlank() }
                ?: continue
            // Sitede bölüm başlığı anime tipine göre öneklenir ("Dizi Bölüm 1" vb.).
            val siteName = if (animeTipi != null && rawName.startsWith(animeTipi, ignoreCase = true))
                rawName.removePrefix(animeTipi).trim() else rawName
            val number = rawName
                .substringBefore(". Bölüm", "")
                .substringAfterLast(" ", "")
                .trim()
                .toIntOrNull()
            val kitsuEp = number?.let { meta?.episodes?.get(it) }
            val dateText = a.selectFirst("small.author")?.text()?.trim()
            val date = dateText?.let {
                try {
                    LocalDate.parse(it, dateFormat).toString()
                } catch (_: Exception) {
                    null
                }
            }
            val thumb = fixUrlNull(a.selectFirst("img")?.attr("src"))
            episodes.add(
                newEpisode(epUrl) {
                    this.name = kitsuEp?.title?.ifBlank { null } ?: siteName
                    this.posterUrl = kitsuEp?.thumb?.ifBlank { null } ?: thumb
                    if (kitsuEp != null) this.description = kitsuEp.description
                    this.episode = number
                    if (date != null) this.date = date
                }
            )
        }

        val metaTitle = meta?.title
        return if ((animeTipi?.contains("Dizi", ignoreCase = true) == true ||
                    animeTipi?.contains("OVA", ignoreCase = true) == true) &&
            metaTitle != null
        ) {
            // Dizi/OVA + Kitsu eşleşmesi → film (tek parça) olarak sunulur (DEX davranışı).
            newMovieLoadResponse(
                metaTitle,
                url,
                TvType.AnimeMovie,
                firstEpisodeUrl ?: ""
            ) {
                this.posterUrl = meta?.cover?.ifBlank { null } ?: poster
                if (description != null) this.plot = meta?.description
                this.year = meta?.year ?: year
                this.tags = tags.distinct()
                this.score = meta?.score?.let { Score.from(it, 0) } ?: Score.from10(rating)
                this.duration = meta?.duration ?: duration
                this.recommendations = recommendations
                addActors(actors.map { Actor(it, null) })
                val finalTrailer = meta?.trailerUrl ?: trailer
                if (finalTrailer != null) addTrailer(finalTrailer)
                meta?.kitsuId?.let { syncData["kitsu"] = it }
                meta?.banner?.ifBlank { null }?.let { this.backgroundPosterUrl = it }
            }
        } else {
            newAnimeLoadResponse(
                meta?.title?.ifBlank { null } ?: title,
                url,
                TvType.Anime,
                !isCompleted
            ) {
                this.posterUrl = meta?.cover?.ifBlank { null } ?: poster
                if (description != null) this.plot = meta?.description
                this.year = meta?.year ?: year
                this.episodes = mutableMapOf(DubStatus.Subbed to episodes)
                this.tags = tags.distinct()
                this.synonyms = meta?.synonyms
                this.score = meta?.score?.let { Score.from(it, 0) } ?: Score.from10(rating)
                this.duration = meta?.duration ?: duration
                this.recommendations = recommendations
                this.showStatus = when {
                    meta?.showStatus == ShowStatus.Completed -> ShowStatus.Completed
                    meta?.totalEpisodes != null && meta.totalEpisodes == episodes.size ->
                        ShowStatus.Completed
                    meta?.showStatus != null -> meta.showStatus
                    isCompleted -> ShowStatus.Completed
                    else -> ShowStatus.Ongoing
                }
                addActors(actors.map { Actor(it, null) })
                val finalTrailer = meta?.trailerUrl ?: trailer
                if (finalTrailer != null) addTrailer(finalTrailer)
                meta?.kitsuId?.let { syncData["kitsu"] = it }
                meta?.banner?.ifBlank { null }?.let { this.backgroundPosterUrl = it }
            }
        }
    }

    // ------------------------------------------------------------------
    // Kitsu zenginleştirme
    // ------------------------------------------------------------------

    data class KitsuEpisode(
        val title: String,
        val thumb: String,
        val description: String
    )

    data class KitsuMetadata(
        val banner: String,
        val cover: String,
        val description: String,
        val duration: Int?,
        val episodes: Map<Int, KitsuEpisode>,
        val kitsuId: String,
        val score: Double?,
        val showStatus: ShowStatus?,
        val synonyms: List<String>,
        val title: String,
        val totalEpisodes: Int?,
        val trailerUrl: String?,
        val year: Int?
    )

    private suspend fun fetchKitsuDataByTitle(rawTitle: String?): KitsuMetadata? {
        if (rawTitle.isNullOrBlank()) return null
        val (cleanTitle, _) = parseTitleAndSeason(rawTitle)
        val encoded = cleanTitle.replace(" ", "%20")
        return try {
            val response = app.get("https://kitsu.io/api/edge/anime?filter[text]=$encoded")
            val responseText = response.text ?: return null
            val json = JSONObject(responseText)
            val data = json.getJSONArray("data")
            if (data.length() == 0) return null
            val first = data.getJSONObject(0)
            val kitsuId = first.getString("id")
            val attrs = first.getJSONObject("attributes")

            val title = attrs.optString("canonicalTitle", "")
            val description = attrs.optString("synopsis", "")
            val banner = attrs.optJSONObject("posterImage")?.optString("original", "")
            val cover = attrs.optJSONObject("coverImage")?.optString("original", "")
            val statusRaw = attrs.optString("status", "")
            val showStatus = when (statusRaw) {
                "finished" -> ShowStatus.Completed
                "current" -> ShowStatus.Ongoing
                else -> null
            }
            val totalEpisodes = attrs.optInt("episodeCount", 0).takeIf { it != 0 }
            val duration = attrs.optInt("episodeLength", 0).takeIf { it != 0 }
            val score = attrs.optString("averageRating", "").toDoubleOrNull()
            val year = attrs.optString("startDate", "")
                .split("-").firstOrNull()?.toIntOrNull()
            val youtubeId = attrs.optString("youtubeVideoId", "")
            val trailerUrl = if (youtubeId.isNotBlank())
                "https://www.youtube.com/watch?v=$youtubeId" else null

            // Synonymler: titles objesi (değerleri) + abbreviatedTitles dizisi.
            val synonyms = mutableListOf<String>()
            attrs.optJSONObject("titles")?.let { titlesObj ->
                for (key in titlesObj.keys()) {
                    titlesObj.optString(key, "").takeIf { it.isNotBlank() }?.let(synonyms::add)
                }
            }
            attrs.optJSONArray("abbreviatedTitles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i, "").takeIf { it.isNotBlank() }?.let(synonyms::add)
                }
            }

            // Bölümler (en fazla 100) — sayısal harita (DEX'teki davranış).
            // NOT: DEX burada "canonicalTitle"/"synopsis" anahtarlarını okuyordu;
            // Kitsu episode API'sinde bu anahtarlar olmadığı için hep boş çıkıyordu.
            // Burası doğru API anahtarları ("title"/"description") ile düzeltildi.
            val epMap = mutableMapOf<Int, KitsuEpisode>()
            val epResponse = app.get("https://kitsu.io/api/edge/anime/$kitsuId/episodes?page[limit]=100")
            val epText = epResponse.text ?: return null
            val epJson = JSONObject(epText)
            val epData = epJson.getJSONArray("data")
            for (i in 0 until epData.length()) {
                val epObj = epData.getJSONObject(i)
                val epAttrs = epObj.getJSONObject("attributes")
                val number = epAttrs.optInt("number", -1)
                if (number > 0) {
                    val epTitle = epAttrs.optString("title", "")
                    val epThumb = try {
                        epAttrs.optJSONObject("thumbnail")?.optString("original", "") ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    val epDesc = epAttrs.optString("description", "")
                    epMap[number] = KitsuEpisode(
                        title = if (epTitle.isBlank()) "" else epTitle,
                        thumb = if (epThumb.isBlank()) "" else epThumb,
                        description = if (epDesc.isBlank()) "" else epDesc
                    )
                }
            }

            KitsuMetadata(
                banner = banner ?: "",
                cover = cover ?: "",
                description = description,
                duration = duration,
                episodes = epMap,
                kitsuId = kitsuId,
                score = score,
                showStatus = showStatus,
                synonyms = synonyms.distinct(),
                title = title,
                totalEpisodes = totalEpisodes,
                trailerUrl = trailerUrl,
                year = year
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Kitsu verisi alınamadı: ${e.message}")
            null
        }
    }

    private fun parseTitleAndSeason(title: String): Pair<String, Int> {
        val seasonRegex = Regex(
            "(?i)\\b(?:season|sezon)\\s*(\\d+)|(\\d+)[.\\s]*sezon|(\\d+)(?:st|nd|rd|th)?\\s+season"
        )
        val match = seasonRegex.find(title)
        val season = match?.let { m ->
            m.groupValues[1].toIntOrNull()
                ?: m.groupValues[2].toIntOrNull()
                ?: m.groupValues[3].toIntOrNull()
        } ?: 1
        val cleanTitle = if (match != null) title.replace(seasonRegex, "") else title
        return cleanTitle.trim() to season
    }

    // ------------------------------------------------------------------
    // Kaynak yükleme (loadLinks)
    // ------------------------------------------------------------------

    data class VideoApi(
        @JsonProperty("source") val source: String
    )

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!TrAnimeIzleHelper.isAllowedVersion) return false
        return coroutineScope {
            Log.d(LOG_TAG, "data = $data")
            var cookie = BotKontrolKt.getSavedCookie(context, mainUrl)
            var headers = if (cookie.isNotEmpty()) mapOf("Cookie" to cookie) else emptyMap()

            var document = app.get(data, headers = headers).document
            val pageTitle = document.selectFirst("title")?.text()?.toString() ?: ""
            if (pageTitle.contains("Bot", ignoreCase = true)) {
                cookie = BotKontrolKt.botKontrol(context, mainUrl, forceRefresh = true)
                if (cookie.isNotEmpty()) {
                    headers = mapOf("Cookie" to cookie)
                    document = app.get(data, headers = headers).document
                }
            }

            val episodeId = document.selectFirst("input#EpisodeId")?.attr("value")?.toIntOrNull()
            val found = AtomicBoolean(false)

            val fansubItems = document.select("div.playlist-title div.fansubSelector li")
            coroutineScope {
                fansubItems.map { li ->
                    async { loadFansubSources(li, episodeId, headers, found, subtitleCallback, callback) }
                }.awaitAll()
            }
            found.get()
        }
    }

    private suspend fun loadFansubSources(
        li: Element,
        episodeId: Int?,
        headers: Map<String, String>,
        found: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fansubId = li.attr("data-fid").toIntOrNull()
        val fansubLabel = li.attr("data-fad")
        try {
            val response = app.post(
                mainUrl + "/api/fansubSources",
                data = mapOf(
                    "EpisodeId" to (episodeId ?: 0).toString(),
                    "FansubId" to (fansubId ?: 0).toString()
                ),
                headers = headers +
                    mapOf(
                        "Referer" to mainUrl,
                        "Accept" to "application/json, text/plain, */*",
                        "Content-Type" to "application/json"
                    )
            )
            val sourceButtons = response.document.select("li.sourceBtn")
            coroutineScope {
                sourceButtons.map { btn ->
                    async { loadSingleSource(btn, headers, found, subtitleCallback, callback) }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e("kraptor_$fansubLabel", "Error loading fansub $fansubLabel: ${e.message}")
        }
    }

    private suspend fun loadSingleSource(
        btn: Element,
        headers: Map<String, String>,
        found: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceId = btn.attr("data-id")
        val eid = btn.attr("data-eid")
        val sourceName = btn.selectFirst("p.title")?.text()?.trim() ?: "Bilinmeyen"
        try {
            Log.d(LOG_TAG, "Video: $sourceName | ID: $sourceId | EID: $eid")
            val response = app.post(
                mainUrl + "/api/sourcePlayer/$sourceId/$eid",
                headers = headers +
                    mapOf(
                        "Referer" to mainUrl,
                        "Accept" to "application/json, text/plain, */*",
                        "Content-Type" to "application/json"
                    )
            )
            val videoApi = response.parsedSafe<VideoApi>() ?: return
            val iframe = Jsoup.parse(videoApi.source).selectFirst("iframe")?.attr("src")
            // DEX: yalnız luffy embed'ları çözülür; id'si loadExtractor'a verilir.
            val value = iframe?.substringAfter(LUFFY_PREFIX, "") ?: ""
            if (value.isNotEmpty()) {
                Log.d(LOG_TAG, "iframe = $value")
                if (loadExtractor(value, mainUrl + "/", subtitleCallback, callback)) {
                    found.set(true)
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error loading source $sourceName: ${e.message}")
        }
    }

    companion object {
        private const val LOG_TAG = "kraptor_TrAnime"
        private const val LUFFY_PREFIX = "luffytra2.top/embed2/?id="
        private const val RAW_DOMAIN_LIST_URL =
            "https://raw.githubusercontent.com/Kraptor123/domainListesi/refs/heads/main/eklenti_domainleri.txt"

        // T3: tek doğru host (repo'daki eklenti_domainleri.txt ve uygulamanın
        // DEFAULT_PLUGIN_DOMAINS değeriyle uyumlu). DEX'in sabit ".io" değeri
        // burayı kullanmaz; cihazda T2 doğrulamasıyla teyit edilecek.
        private const val DEFAULT_DOMAIN = "https://www.tranimeizle.live"

        /**
         * Domain çözümleme (DEX Companion.getDomain'in yeniden yazımı):
         *  - SharedPreferences ("DomainListesi", anahtar = eklenti adı) önde.
         *  - Kayıt yoksa uzak `eklenti_domainleri.txt` çekilir
         *    (biçim: `Ad:https://domain|Ad2:https://domain2`) ve eşleşen
         *    kayıt + zaman damgası yazılır.
         *  - Uzak liste de alınamazsa sabit yedek domain döndürülür.
         *    (DEX'in aksine — DEX bu durumda "" döndürüyordu — asgari
         *    kullanılabilir davranış garantisi için.)
         */
        fun getDomain(prefs: SharedPreferences, pluginName: String): String = runBlocking {
            val lastUpdate = prefs.getLong("last_domain_update", 0L)
            val now = System.currentTimeMillis()
            val cached = prefs.getString(pluginName, "")
            if (cached.isNullOrBlank() && now - lastUpdate >= 0) {
                try {
                    // Buradaki `app`, com.lagradost.cloudstream3 paketindeki
                    // üst düzey (top-level) istemci (DEX de aynı yerden çağırıyordu).
                    val text = app.get(RAW_DOMAIN_LIST_URL).text
                    if (!text.isNullOrBlank()) {
                        text.split("|").filter { it.isNotBlank() }.forEach { entry ->
                            val parts = entry.trim().split(":")
                            if (parts.size == 2 && parts[0].trim() == pluginName) {
                                prefs.edit()
                                    .putString(pluginName, parts[1].trim())
                                    .putLong("last_domain_update", now)
                                    .apply()
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Uzak listeye erişilemedi → yedek domain.
                }
            }
            return@runBlocking prefs.getString(pluginName, "")?.ifBlank { null }
                ?: DEFAULT_DOMAIN
        }
    }
}
