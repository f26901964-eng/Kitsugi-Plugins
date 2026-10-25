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
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TrAnimeIzle ana API (DEX'teki com.kraptor.TrAnimeIzle'in kaynak yeniden yazımı).
 *
 * Akış: arama/anasayfa/bölüm listesi → `load` (metadata + Kitsu zenginleştirme +
 * bölümler) → `loadLinks` (fansub → sourcePlayer → iframe → loadExtractor).
 * Bot kontrolü [BotKontrolKt] üzerinden yapılır.
 *
 * v17 (cihaz kanıtı + site arşivi 22.09.2026):
 *  - Site Cloudflare Error 1005 (ASN ban) arkasında; cihaz WebView oturumu
 *    tek yasal yol. Tüm site istekleri [fetchHtml] üzerinden: tarayıcı
 *    başlıkları (Firefox UA, Referer, Accept-Language) + kayıtlı oturum;
 *    bot/başarısız cevapta WebView diyaloğuyla oturum yenileme + tek tekrar.
 *  - sourcePlayer: site kendi JS'inde TEK PARAMETRELİ `POST /api/sourcePlayer/{id}`
 *    kullanıyor (data-id) → birincil yol; eski iki parametreli form yedek.
 *  - Sonuç kartları tıklamada `data-href`'e gidiyor → URL çözümü önce data-href.
 *  - episodeId: input#EpisodeId yoksa `animeWatch.initialize(aId, eId, ...)`
 *    script çağrısından regex ile.
 *  - Arama sorgusu URL-kodlanıyor.
 *  - Her adım log'a yazılıyor (kraptor_TrAnime) — cihaz kanıtı için.
 *  - Harici (potansiyel olarak eski) domain listesi kaldırıldı.
 *
 * v18:
 *  - Arama page 1: ÇIPLAK `/arama/{sorgu}` (site'nin kendi JS'i ?page= kullanmıyor).
 *  - Sonuç kartları flx-block ile gelmezse href deseni (…-bolum-izle /
 *    …-anime-izle) + resim tabanlı yedek çözümleme (parseGenericCards).
 *  - isBotBody: Cloudflare challenge başlıkları ("Just a moment…" vb.) da
 *    bot cevabı sayılır.
 *  - BotKontrol: CF challenge sayfası "yüklendi" sayılmaz (WebView kendisi
 *    çözer, beklenir).
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

    // ! Yollar DEX'teki ile birebir aynı (mainUrl + suffix); listeler/* yolları
    //   22.09.2026 ana sayfa arşivinde doğrulandı (/listeler/eklenen/sayfa-1 vb.).
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
        // v17: sorgu kodlanıyor (boşluk/Türkçe karakter → 404 olmuyor),
        // istek oturum çerezi + tarayıcı başlıklarıyla gidiyor,
        // bot cevabında oturum yenilenip bir kez tekrar deniyor.
        // v18: site kendi JS'inde arama için ÇIPLAK `/arama/{sorgu}` kullanıyor
        // (site.js 22.09.2026 — ?page= parametresi YOK) → page 1 çıplak URL;
        // page>1 için DEX davranışı korunur.
        val enc = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) mainUrl + "/arama/" + enc
        else mainUrl + "/arama/" + enc + "?page=" + page
        val html = fetchHtml(url, mainUrl + "/")
        val document = Jsoup.parse(html)
        var results = document.select("div.flx-block").mapNotNull { toMainPageResult(it) }
        if (results.isEmpty()) {
            // v18: kart sınıfı değişmiş olabilir → href deseni tabanlı yedek çözüm.
            results = parseGenericCards(document)
            Log.w(LOG_TAG, "search('$query'): flx-block 0 → genel çözümleme: ${results.size}")
        }
        Log.d(LOG_TAG, "search('$query') → ${results.size} sonuç")
        if (results.isEmpty()) {
            Log.e(
                LOG_TAG,
                "search('$query') 0 sonuç — a[href*=izle] sayısı: " +
                    document.select("a[href*='izle']").size +
                    " | sayfa başı:\n" + html.take(2000)
            )
        }
        return newSearchResponseList(results, true)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (!TrAnimeIzleHelper.isAllowedVersion) {
                return newHomePageResponse(request.name, emptyList(), false)
            }
            val url = request.data + page
            val html = fetchHtml(url, mainUrl + "/")
            val document = Jsoup.parse(html)
            var results = document.select("div.flx-block").mapNotNull { toMainPageResult(it) }
            if (results.isEmpty()) {
                results = parseGenericCards(document)
                Log.w(LOG_TAG, "mainPage('${request.name}'): flx-block 0 → genel çözümleme: ${results.size}")
            }
            Log.d(LOG_TAG, "mainPage('${request.name}') → ${results.size} sonuç")
            newHomePageResponse(request.name, results, true)
        } catch (e: Exception) {
            throw ErrorLoadingException("Siteye ulaşılamıyor! Tarayıcıdan test edin.")
        }
    }

    private fun toMainPageResult(el: Element): AnimeSearchResponse? {
        val title = el.selectFirst("h4")?.text()?.replace("İzle", "")?.takeIf { it.isNotBlank() }
            ?: return null
        // v17 (site.js kanıtı, 22.09.2026): .flx-block kartları tıklamada
        // `data-href` özniteliğine yönlendiriyor — <a href> ile aynı olmak zorunda
        // değil; önce data-href, sonra <a href> denenir.
        val url = fixUrlNull(el.attr("data-href"))
            ?: fixUrlNull(el.selectFirst("a")?.attr("href")) ?: return null
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

    /**
     * v18 yedek kart çözümleme: sonuç/liste kartlarının sınıfları değişse bile,
     * site'nin URL desenine (…-bolum-izle / …-anime-izle) + kart içindeki
     * resme dayanarak sonuç üretir. (Ana sayfa arşivi 22.09.2026 bu deseni
     * doğruluyor: her kartta img + izle-sonekli href var.)
     */
    private fun parseGenericCards(document: Document): List<AnimeSearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<AnimeSearchResponse>()
        for (a in document.select("a[href]")) {
            val href = a.attr("href")
            if (!href.contains("bolum-izle", ignoreCase = true) &&
                !href.contains("anime-izle", ignoreCase = true)
            ) continue
            val url = fixUrlNull(href) ?: continue
            if (!seen.add(url)) continue
            val img = a.selectFirst("img") ?: continue
            val poster = fixUrlNull(img.attr("src"))?.ifBlank { null }
                ?: fixUrlNull(img.attr("data-src"))
            val title = (a.selectFirst("h4,h3,h5")?.text() ?: img.attr("alt"))
                ?.replace("İzle", "")?.trim()?.takeIf { it.isNotBlank() } ?: continue
            out += newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // v17: oturum + tarayıcı başlıkları + bot cevabı işleme
    // ------------------------------------------------------------------

    /** Son bilinen oturum çerez seti (fetchHtml tarafından güncellenir). */
    private var lastCookie: String = ""

    /**
     * Tarayıcı benzeri başlıklar. CloudStream'in varsayılan UA'sı bot imzası
     * olduğu için tüm site isteklerinde Firefox UA + Referer + Accept-Language
     * ile gidiyoruz (BotKontrol'un WebView'inde kullandığı ile aynı UA).
     */
    private fun browserHeaders(
        cookie: String,
        referer: String = mainUrl + "/"
    ): Map<String, String> = buildMap {
        put("User-Agent", BotKontrolKt.UA_FIREFOX)
        put(
            "Accept",
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer)
        if (cookie.isNotEmpty()) put("Cookie", cookie)
    }

    /**
     * Suskun (diyalog açmayan) oturum çözümü:
     *  1) CookieManager'daki site seti (uygulamanın kendi WebView'i siteyi
     *     zaten açmış olabilir — KNOWN_WEBVIEW_PLUGINS kancası),
     *  2) önceki WebView akışından kayıtlı çerez seti.
     */
    private fun cachedSession(): String {
        val jar = BotKontrolKt.collectJar(mainUrl)
        if (BotKontrolKt.jarHasSession(jar)) {
            BotKontrolKt.saveJar(context, mainUrl, jar)
            return jar
        }
        val cached = BotKontrolKt.getSavedCookie(context, mainUrl)
        if (cached.isNotEmpty()) {
            BotKontrolKt.resetCookieManagerTo(cached, mainUrl)
            return cached
        }
        return ""
    }

    /** WebView diyaloğu ile oturumu YENİLER (bot cevabı alındıysa). */
    private suspend fun refreshSession(url: String): String = try {
        BotKontrolKt.botKontrol(context, url, forceRefresh = true)
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Oturum yenileme hatası: ${e.message}")
        BotKontrolKt.getSavedCookie(context, mainUrl)
    }

    /**
     * İçerik bot/captcha/Cloudflare challenge sayfası mı?
     * v18: site Cloudflare arkasında (Error 1005 ASN ban kanıtlı) → CF
     * managed challenge başlıkları da bot cevabı sayılır. (Sadece TITLE
     * bazlı kontrol — gövdedeki cf- işaretleri her CF korumalı sayfada
     * normalde de bulunur, yanlış pozitif vermemek için.)
     */
    private fun isBotBody(text: String?): Boolean {
        val t = text ?: ""
        if (t.isEmpty()) return true
        if (t.contains("/api/Captcha", ignoreCase = true)) return true
        val title = try {
            Jsoup.parse(t).selectFirst("title")?.text() ?: ""
        } catch (_: Exception) {
            ""
        }
        return title.contains("Bot", ignoreCase = true) ||
            title.contains("Just a moment", ignoreCase = true) ||
            title.contains("Checking your browser", ignoreCase = true) ||
            title.contains("Attention Required", ignoreCase = true) ||
            Regex("Error\\s?100[5678]|Error\\s?1015|Error\\s?1020|Access denied")
                .containsMatchIn(title)
    }

    /**
     * v17 çekirdek GET: önce kayıtlı oturumla dener; başarısız/bot/captcha
     * cevabı gelirse WebView diyaloğu ile oturumu yenileyip TEK bir kez
     * tekrar dener. Her adımda log bırakır (cihaz kanıtı için).
     */
    private suspend fun fetchHtml(url: String, referer: String): String {
        var cookie = lastCookie.ifBlank { cachedSession() }
        var resp = app.get(url, headers = browserHeaders(cookie, referer))
        if (!resp.isSuccessful || isBotBody(resp.text)) {
            Log.e(LOG_TAG, "Bot/başarısız cevap → oturum yenileniyor: $url")
            cookie = refreshSession(url)
            resp = app.get(url, headers = browserHeaders(cookie, referer))
        }
        lastCookie = cookie
        val ok = resp.isSuccessful
        val text = resp.text
        Log.d(LOG_TAG, "GET $url → ${if (ok) "başarılı" else "BAŞARISIZ"}, ${text?.length ?: 0} karakter")
        if (text.isNullOrBlank()) {
            throw ErrorLoadingException("Siteye ulaşılamadı.")
        }
        return text
    }

    // ------------------------------------------------------------------
    // Bölüm sayfası (load)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        if (!TrAnimeIzleHelper.isAllowedVersion) return null

        // v17: fetchHtml → kayıtlı oturumla dener, bot cevabı alırsa
        // WebView diyaloğuyla oturumu yeniler ve bir kez tekrar dener.
        val html = fetchHtml(url, mainUrl + "/")
        var document = Jsoup.parse(html)

        val banasayfa = fixUrlNull(
            document.selectFirst("ol.breadcrumb li + li:not(.active) a")?.attr("href")
        )
        Log.d(LOG_TAG, "banasayfa = $banasayfa")
        if (!banasayfa.isNullOrEmpty()) {
            val banHtml = app.get(banasayfa, headers = browserHeaders(lastCookie)).text
            if (!banHtml.isNullOrBlank()) {
                document = Jsoup.parse(banHtml)
            }
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
                    if (date != null) this.addDate(date)
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
            val html = fetchHtml(data, mainUrl + "/")
            val document = Jsoup.parse(html)

            // v17 (site.js kanıtı, 22.09.2026): bölüm sayfası episodeId'i
            // `animeWatch.initialize(animeId, episodeId, fansubId, ...)` script
            // çağrısında taşır; input#EpisodeId yoksa buradan çıkılır.
            val episodeId = document.selectFirst("input#EpisodeId")?.attr("value")?.toIntOrNull()
                ?: Regex("animeWatch\\.initialize\\(\\s*(\\d+)\\s*,\\s*(\\d+)")
                    .find(html)?.groupValues?.getOrNull(2)?.toIntOrNull()
            Log.d(LOG_TAG, "episodeId = $episodeId")
            val found = AtomicBoolean(false)

            val fansubItems = document.select("div.playlist-title div.fansubSelector li")
            Log.d(LOG_TAG, "fansubSelector li sayısı: ${fansubItems.size}")
            if (fansubItems.isEmpty()) {
                Log.e(LOG_TAG, "fansubSelector boş — sayfa başı:\n" + html.take(1200))
            }
            coroutineScope {
                fansubItems.map { li ->
                    async { loadFansubSources(li, episodeId, found, subtitleCallback, callback) }
                }.awaitAll()
            }
            found.get()
        }
    }

    private suspend fun loadFansubSources(
        li: Element,
        episodeId: Int?,
        found: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fansubId = li.attr("data-fid").toIntOrNull()
        val fansubLabel = li.attr("data-fad")
        val tag = "kraptor_$fansubLabel"
        try {
            // v17 (site.js kanıtı, 22.09.2026): POST /api/fansubSources gövdesi
            // {EpisodeId, FansubId} — güncel site ile birebir aynı.
            val response = app.post(
                mainUrl + "/api/fansubSources",
                data = mapOf(
                    "EpisodeId" to (episodeId ?: 0).toString(),
                    "FansubId" to (fansubId ?: 0).toString()
                ),
                headers = browserHeaders(lastCookie) +
                    mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Content-Type" to "application/json"
                    )
            )
            val sourceButtons = response.document.select("li.sourceBtn")
            Log.d(tag, "fansubSources(fid=$fansubId) → ${if (response.isSuccessful) "başarılı" else "BAŞARISIZ"}, ${sourceButtons.size} kaynak")
            if (sourceButtons.isEmpty()) {
                Log.e(tag, "sourceBtn yok — cevap başı:\n" + (response.text ?: "").take(600))
            }
            coroutineScope {
                sourceButtons.map { btn ->
                    async { loadSingleSource(btn, found, subtitleCallback, callback) }
                }.awaitAll()
            }
        } catch (e: Exception) {
            Log.e(tag, "Error loading fansub $fansubLabel: ${e.message}")
        }
    }

    private suspend fun loadSingleSource(
        btn: Element,
        found: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceId = btn.attr("data-id")
        val eid = btn.attr("data-eid")
        val sourceName = btn.selectFirst("p.title")?.text()?.trim() ?: "Bilinmeyen"
        try {
            Log.d(LOG_TAG, "Video: $sourceName | ID: $sourceId | EID: $eid")
            // v17 (site.js kanıtı, 22.09.2026): site kendi JS'inde TEK PARAMETRELİ
            // `POST /api/sourcePlayer/{id}` kullanıyor (li.sourceBtn'un data-id'si).
            // Eski ikili form ({sourceId}/{eid}) yalnızca yedek olarak denenecek.
            val primary = app.post(
                mainUrl + "/api/sourcePlayer/$sourceId",
                headers = browserHeaders(lastCookie) +
                    mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "Content-Type" to "application/json"
                    )
            )
            Log.d(LOG_TAG, "sourcePlayer($sourceName) [1 param] → ${if (primary.isSuccessful) "başarılı" else "BAŞARISIZ"}")
            val videoApi = try {
                primary.parsedSafe<VideoApi>()?.takeIf { it.source.isNotBlank() }
            } catch (e: Exception) {
                null
            } ?: try {
                Log.d(LOG_TAG, "sourcePlayer($sourceName) → yedek form (2 param) deneniyor")
                val legacy = app.post(
                    mainUrl + "/api/sourcePlayer/$sourceId/$eid",
                    headers = browserHeaders(lastCookie) +
                        mapOf(
                            "Accept" to "application/json, text/plain, */*",
                            "Content-Type" to "application/json"
                        )
                )
                legacy.parsedSafe<VideoApi>()?.takeIf { it.source.isNotBlank() }
            } catch (e: Exception) {
                null
            }
            if (videoApi == null) {
                Log.e(LOG_TAG, "sourcePlayer JSON parse hatası: ${(primary.text ?: "").take(500)}")
                return
            }
            val iframe = Jsoup.parse(videoApi.source).selectFirst("iframe")?.attr("src")
            Log.d(LOG_TAG, "iframe = $iframe")
            if (iframe.isNullOrBlank()) {
                Log.e(LOG_TAG, "iframe bulunamadı — source HTML başı:\n" + videoApi.source.take(800))
                return
            }
            // DEX davranışı: luffy embed'in id'si loadExtractor'a verilirdi.
            // v17: o çalışmazsa TAM iframe URL'si de denenir (kayıtlı
            // çözücü mainUrl eşleştirmesiyle tutarlı olan standart yol).
            val luffyId = iframe.substringAfter(LUFFY_PREFIX, "")
            val candidates = if (luffyId.isNotEmpty()) listOf(luffyId, iframe) else listOf(iframe)
            for (target in candidates) {
                if (loadExtractor(target, mainUrl + "/", subtitleCallback, callback)) {
                    found.set(true)
                    break
                }
            }
            if (!found.get()) {
                Log.w(LOG_TAG, "source '$sourceName' çözülemedi (adaylar: $candidates)")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error loading source $sourceName: ${e.message}")
        }
    }

    companion object {
        private const val LOG_TAG = "kraptor_TrAnime"
        private const val LUFFY_PREFIX = "luffytra2.top/embed2/?id="

        // T3: tek doğru host (repo'daki eklenti_domainleri.txt ve uygulamanın
        // DEFAULT_PLUGIN_DOMAINS değeriyle uyumlu); 22.09.2026 arşivinde de
        // aktif domain olarak doğrulandı. DEX'in sabit ".io" değeri kullanılmaz.
        private const val DEFAULT_DOMAIN = "https://www.tranimeizle.live"

        /**
         * Domain çözümleme (v17, DEX Companion.getDomain'in sadeleştirilmiş hali):
         *  - SharedPreferences ("DomainListesi", anahtar = eklenti adı) önde
         *    (uygulama bu alana seçili domain'i yazar).
         *  - Yoksa sabit [DEFAULT_DOMAIN].
         *  - DEX ayrıca harici bir domain listesi (Kraptor123/domainListesi)
         *    çekiyordu; o liste ESKİ domain'ler (ör. .io) taşıyabildiği için
         *    v17'de kaldırıldı — tek kaynak: uygulama tercihi + yedek.
         */
        fun getDomain(prefs: SharedPreferences, pluginName: String): String {
            val domain = prefs.getString(pluginName, "")?.ifBlank { null }
                ?: DEFAULT_DOMAIN
            Log.d(LOG_TAG, "domain = $domain")
            return domain
        }
    }
}
