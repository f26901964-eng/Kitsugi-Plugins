package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Cookie
import okhttp3.FormBody
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLEncoder

/**
 * TrAnimeIzle (site: `https://www.tranimeizle.live`) — v45.
 *
 * Sözleşme, sitenin **kendi** istemci kodundan (`/js/site.js`) ve cihazdan alınan
 * sayfa dökümlerinden çıkarıldı; tahmin edilen hiçbir adım yok:
 *
 * | Adım | Uç / seçici | Yanıt |
 * | --- | --- | --- |
 * | Liste | `div.flx-block[data-href]` | kart (bölüm adresi taşır) |
 * | Detay | `div.playlist-title h1`, `dd` etiket + `dt` değer | seri bilgisi |
 * | Bölümler | `li.episodeBtn[data-slug]` | `/<slug>` |
 * | Bölüm sayfası | `input#EpisodeId`, `.fansubSelector[data-fid]` | kimlikler |
 * | Kaynaklar | `POST /api/fansubSources {EpisodeId,FansubId}` | `li.sourceBtn[data-id]` |
 * | Oynatıcı | `POST /api/sourcePlayer/{id}` (boş gövde) | `{source:"<iframe…>"}` |
 * | Yedek | `POST /api/preVideoAd/{id}` | düz oynatıcı HTML'i |
 * | Captcha | `POST /api/Captcha/` `cID,rT=1,tM` → 5 ikon → `rT=2,pC` | oturum çerezi |
 *
 * Bot kontrolü **HTTP ile** çözülür: oturum çerezi sınıf düzeyindeki
 * [sharedJar]'da tutulduğu için bir kez çözülen doğrulama tüm isteklerde geçerli
 * olur (kullanıcının "her sayfada captcha" şikâyetinin çözümü).
 *
 * +18 uyarısı sitede **istemci tarafında** (modal + `localStorage`) tutuluyor;
 * eklenti HTML çalıştırmadığı için uyarı hiç görünmez. Yine de sunucu taraflı bir
 * yaş kapısına düşülürse form otomatik onaylanır ([TrAgeGate]).
 */
class TrAnimeIzle : MainAPI(), TrStreamHttp {

    override var mainUrl: String = DEFAULT_DOMAIN
    override var name = "TrAnimeIzle"

    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/listeler/yenibolum/sayfa-" to "Yeni Bölümler",
        "$mainUrl/listeler/eklenen/sayfa-" to "Yeni Animeler",
        "$mainUrl/listeler/populer/sayfa-" to "Popüler Animeler",
        "$mainUrl/animeizle/aksiyon-anime-izle-" to "Aksiyon",
        "$mainUrl/animeizle/bilim-kurgu-anime-izle-" to "Bilim Kurgu",
        "$mainUrl/animeizle/dogaustu-gucler-anime-izle-" to "Doğaüstü Güçler",
        "$mainUrl/animeizle/dram-anime-izle-" to "Dram",
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
        "$mainUrl/animeizle/romantizm-anime-izle-" to "Romantizm",
        "$mainUrl/animeizle/shounen-anime-izle-" to "Shounen",
        "$mainUrl/animeizle/spor-anime-izle-" to "Spor"
    )

    // ==================================================================
    //  Arama
    // ==================================================================

    /**
     * Site aramayı **yol parçasına** koyuyor: `/arama/{ham metin}` (site.js).
     * Tarayıcı boşluğu `%20` yapar; `URLEncoder` ise `+` üretir ve sunucu
     * "one+piece" aradığı için **0 sonuç** dönerdi → `+` → `%20`.
     */
    private fun encodeSearchPath(raw: String): String =
        URLEncoder.encode(raw, "UTF-8").replace("+", "%20")

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)

        val cards = LinkedHashMap<String, TrCard>()
        for (variant in TrAnimeIzleMatch.queryVariants(clean).take(3)) {
            val suffix = if (page > 1) "?page=$page" else ""
            val url = "$mainUrl/arama/" + encodeSearchPath(variant) + suffix
            val html = get(url, "$mainUrl/") ?: continue
            val doc = Jsoup.parse(html, "$mainUrl/")
            for (card in TrAnimeIzleSite.parseCards(doc, "$mainUrl/", 40)) {
                val key = TrAnimeIzleMatch.seriesKey(card.url)
                if (key.isNotEmpty() && !cards.containsKey(key)) cards[key] = card
            }
            if (cards.isNotEmpty()) break
        }

        if (cards.isEmpty()) {
            Log.d(TAG, "search('$clean') → 0 kart")
            return newSearchResponseList(emptyList(), false)
        }

        val ranked = TrAnimeIzleMatch.rank(
            clean,
            cards.values.map { Triple(it.name, it.url, emptyList()) },
            threshold = 0.30
        )
        val byUrl = cards.values.associateBy { it.url }
        val ordered = ranked.mapNotNull { byUrl[it.first] }.ifEmpty { cards.values.toList() }
        Log.d(TAG, "search('$clean') → ${ordered.size} kart")
        return newSearchResponseList(ordered.map { it.toSearchResponse() }, page < 2)
    }

    private fun TrCard.toSearchResponse(): AnimeSearchResponse {
        // DİKKAT: `newAnimeSearchResponse { … }` bloğunun alıcısı da `score`
        // alanı taşıyor; niteliksiz `score` yanlışlıkla onu okur (Score? →
        // Score.from10'a verilemez). Değerler önce yerel değişkene alınır.
        val cardScore = score
        val cardPoster = poster
        return newAnimeSearchResponse(name, url, TvType.Anime) {
            this.posterUrl = cardPoster
            this.score = cardScore?.let { Score.from10(it) }
        }
    }

    // ==================================================================
    //  Ana sayfa
    // ==================================================================

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = remapDomain(request.data) + page
        val html = get(url, "$mainUrl/")
            ?: throw ErrorLoadingException("Anime listesi yüklenemedi (site yanıt vermedi).")
        val doc = Jsoup.parse(html, "$mainUrl/")
        var results = TrAnimeIzleSite.parseCards(doc, "$mainUrl/", 60).map { it.toSearchResponse() }
        if (results.isEmpty()) results = genericCards(doc)
        Log.d(TAG, "mainPage('${request.name}', $page) → ${results.size} kart")
        return newHomePageResponse(request.name, results, results.isNotEmpty())
    }

    /** Tema değişirse: kart sınıfı yerine `-izle` bağlantı deseninden topla. */
    private fun genericCards(doc: Document): List<AnimeSearchResponse> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<AnimeSearchResponse>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (!href.contains("bolum-izle", true) && !href.contains("anime-izle", true)) continue
            val url = fixUrlNull(href) ?: continue
            if (!seen.add(url)) continue
            val img = a.selectFirst("img") ?: continue
            val title = (a.selectFirst("h4,h3,h5")?.text() ?: img.attr("alt"))
                .replace("İzle", "").trim().takeIf { it.isNotBlank() } ?: continue
            out += newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = fixUrlNull(img.attr("src")) ?: fixUrlNull(img.attr("data-src"))
            }
        }
        return out
    }

    // ==================================================================
    //  Detay + bölüm listesi
    // ==================================================================

    override suspend fun load(url: String): LoadResponse? {
        var pageUrl = remapDomain(url)
        var html = get(pageUrl, "$mainUrl/")
            ?: throw ErrorLoadingException("Sayfa yüklenemedi: site yanıt vermedi.")
        var doc = Jsoup.parse(html, pageUrl)

        // Kart adresleri bölüm sayfasıdır (`/x-12-bolum-izle`). Seri sayfasına geç:
        // önce sayfanın kendi breadcrumb'ı (birebir doğru adres), sonra slug kuralı.
        if (TrAnimeIzleMatch.episodeFromUrl(pageUrl) != null) {
            val series = doc.selectFirst("ol.breadcrumb a[href*='/anime/']")?.attr("href")
                ?.let { TrAnimeIzleSite.absolute(pageUrl, it) }
                ?: TrAnimeIzleMatch.toSeriesUrl(pageUrl)
            if (!series.isNullOrBlank() && series != pageUrl) {
                val seriesHtml = get(series, "$mainUrl/")
                if (!seriesHtml.isNullOrBlank()) {
                    pageUrl = series
                    html = seriesHtml
                    doc = Jsoup.parse(html, pageUrl)
                }
            }
        }

        // Sunucu taraflı bir yaş kapısı varsa (nadir) otomatik onayla ve bir kez tekrar çek.
        TrAgeGate.detect(html, pageUrl)?.let { gate ->
            Log.d(TAG, "yaş kapısı bulundu → otomatik onay: ${gate.action.take(90)}")
            postForm(gate.action, gate.fields, pageUrl)
            get(pageUrl, "$mainUrl/")?.let {
                html = it
                doc = Jsoup.parse(html, pageUrl)
            }
        }

        val detail = TrAnimeIzleSite.parseDetail(doc, "$mainUrl/", pageUrl)
        val episodes = detail.episodes
        val isFilm = detail.typeText?.let {
            it.contains("film", true) || it.contains("movie", true)
        } == true || episodes.size <= 1

        val episodeList = episodes.map { ep ->
            newEpisode(ep.url) {
                this.name = ep.name?.takeIf { n -> n.isNotBlank() } ?: "${ep.number}. Bölüm"
                this.season = ep.season
                this.episode = ep.number
                this.posterUrl = ep.poster ?: detail.poster
            }
        }

        val completed = detail.totalEpisodes?.let { it > 0 && episodes.size >= it } == true

        Log.d(
            TAG,
            "load($pageUrl): '${detail.title}', ${episodeList.size} bölüm, tip=${detail.typeText ?: "?"}"
        )

        return if (isFilm) {
            val single = episodes.firstOrNull()?.url
                ?: TrAnimeIzleSite.absolute(pageUrl, doc.selectFirst("a[href*='-bolum-izle']")?.attr("href"))
                ?: pageUrl
            newMovieLoadResponse(detail.title, pageUrl, TvType.AnimeMovie, single) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
                this.tags = detail.tags
                this.score = detail.score?.let { Score.from10(it) }
                this.duration = detail.durationMinutes
            }
        } else {
            newAnimeLoadResponse(detail.title, pageUrl, TvType.Anime, false) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
                this.tags = detail.tags
                this.score = detail.score?.let { Score.from10(it) }
                this.showStatus = if (completed) ShowStatus.Completed else ShowStatus.Ongoing
                addEpisodes(DubStatus.Subbed, episodeList)
            }
        }
    }

    // ==================================================================
    //  Kaynaklar
    // ==================================================================

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = remapDomain(data)
        Log.d(TAG, "loadLinks: $pageUrl")
        val count = try {
            TrAnimeIzleStreams(name, { mainUrl }, this).load(pageUrl, subtitleCallback, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "loadLinks hatası: ${e.javaClass.simpleName}: ${e.message}")
            0
        }
        if (count > 0) {
            Log.d(TAG, "loadLinks: $count akış")
            return true
        }
        throw ErrorLoadingException(
            "Bu bölüm için kaynak bulunamadı (oynatıcı listesi boş döndü). " +
                "Başka bir bölüm ya da fansub deneyin."
        )
    }

    // ==================================================================
    //  HTTP yüzeyi (TrStreamHttp)
    // ==================================================================

    /**
     * NiceHttp oturumu. Çerez kavanozu **sınıf düzeyinde** ([sharedJar]) olduğu
     * için eklenti yeniden oluşturulsa da çözülmüş captcha oturumu korunur.
     */
    private val siteHttp by lazy {
        Session(app.baseClient).apply {
            baseClient = baseClient.newBuilder().cookieJar(sharedJar).build()
        }
    }

    private suspend fun request(
        label: String,
        referer: String?,
        xhr: Boolean,
        call: suspend (Map<String, String>) -> NiceResponse
    ): String? {
        val headers = headers(referer, xhr)

        suspend fun execute(): NiceResponse? = try {
            call(headers)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "$label ağ hatası: ${e.javaClass.simpleName}: ${e.message}")
            null
        }

        var response = execute() ?: return null
        var text = readText(response)

        // Captcha kapısı: çöz → aynı isteği tekrarla (en fazla 2 tur).
        var round = 0
        while (isCaptchaGate(response.url, text)) {
            if (round >= 2) {
                Log.w(TAG, "$label → captcha geçilemedi")
                return null
            }
            round++
            Log.w(TAG, "$label → captcha kapısı (tur $round)")
            if (!solveCaptcha()) return null
            response = execute() ?: return null
            text = readText(response)
        }

        if (!response.isSuccessful) {
            Log.w(TAG, "$label başarısız (HTTP ${response.code})")
            return null
        }
        if (text.isBlank()) {
            Log.w(TAG, "$label boş yanıt")
            return null
        }
        return text
    }

    private fun readText(response: NiceResponse): String = try {
        response.text
    } catch (e: Throwable) {
        Log.w(TAG, "gövde okunamadı: ${e.message}")
        ""
    }

    override suspend fun get(url: String, referer: String?): String? =
        request("GET ${url.take(90)}", referer, false) { h ->
            siteHttp.get(url, headers = h, timeout = PAGE_TIMEOUT)
        }

    override suspend fun getXhr(url: String, referer: String?): String? =
        request("XHR ${url.take(90)}", referer, true) { h ->
            siteHttp.get(url, headers = h, timeout = API_TIMEOUT)
        }

    override suspend fun getWith(url: String, extra: Map<String, String>): String? {
        val referer = url.substringBefore("/api/").ifEmpty { "$mainUrl/" }
        return request("GET(w) ${url.take(90)}", referer, true) { h ->
            siteHttp.get(url, headers = h + extra, timeout = API_TIMEOUT)
        }
    }

    override suspend fun postJson(url: String, json: String, referer: String?): String? =
        request("POST-JSON ${url.take(90)}", referer, true) { h ->
            siteHttp.post(url, headers = h, json = JsonAsString(json), timeout = API_TIMEOUT)
        }

    /**
     * Gövdesiz POST: sitenin istemcisi `axios.post(url)` çağırıyor, yani gövde
     * **yok**. NiceHttp'in boş `data` haritası form gövdesi ürettiği için
     * burada gerçekten boş bir gövde gönderilir.
     */
    override suspend fun postEmpty(url: String, referer: String?): String? =
        request("POST ${url.take(90)}", referer, true) { h ->
            siteHttp.post(
                url,
                headers = h,
                requestBody = ByteArray(0).toRequestBody(null),
                timeout = API_TIMEOUT
            )
        }

    override suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String?
    ): String? = request("POST-FORM ${url.take(90)}", referer, true) { h ->
        siteHttp.post(url, headers = h, data = form, timeout = API_TIMEOUT)
    }

    /** Ham bayt (captcha ikonları). Gövde metne çevrilmez. */
    override suspend fun bytes(url: String, referer: String?): ByteArray? {
        val response = try {
            siteHttp.get(url, headers = headers(referer, false), timeout = IMAGE_TIMEOUT)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "görsel alınamadı: ${e.message}")
            return null
        }
        val body = response.body ?: return null
        return try {
            if (!response.isSuccessful) {
                body.close()
                null
            } else {
                body.use { it.bytes() }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "görsel okunamadı: ${e.message}")
            null
        }
    }

    // ==================================================================
    //  Captcha (ikon doğrulama)
    // ==================================================================

    /**
     * IconCaptcha v2 akışı (sitendeki `validationPath: "/api/Captcha/"`):
     *
     * 1. `POST /api/Captcha/` · form `{cID, rT=1, tM}` → 5 ikon kimliği
     * 2. `GET  /api/Captcha/?cid={cID}&hash={hash}` ×5 → 5 PNG
     * 3. Dördü aynı, biri farklı → **MD5'i tek olan** hash seçilir
     * 4. `POST /api/Captcha/` · form `{cID, pC=hash, rT=2}` → oturum doğrulanır
     *
     * `cID` widget sırasıdır (0). Tema sayfadaki `captchaTheme` değeridir.
     */
    override suspend fun solveCaptcha(): Boolean = captchaLock.withLock {
        val site = mainUrl.trimEnd('/')
        for (cid in CAPTCHA_CIDS) {
            for (theme in CAPTCHA_THEMES) {
                val hashes = captchaHashes(site, cid, theme)
                if (hashes.size < 3) continue
                val chosen = captchaOddOneOut(site, cid, hashes) ?: continue
                if (captchaSubmit(site, cid, chosen)) {
                    Log.d(TAG, "captcha çözüldü (cID=$cid, tM=$theme, ${hashes.size} ikon)")
                    return@withLock true
                }
            }
        }
        Log.w(TAG, "captcha çözülemedi — site doğrulamayı kabul etmedi")
        false
    }

    private suspend fun captchaHashes(site: String, cid: String, theme: String): List<String> {
        val body = try {
            val response = siteHttp.post(
                "$site/api/Captcha/",
                headers = captchaHeaders(site),
                // `data` haritası NiceHttp'te `addEncoded` ile gönderiliyor (yani
                // değer önceden kodlanmış sayılıyor); hash'ler için FormBody şart.
                requestBody = FormBody.Builder()
                    .add("cID", cid)
                    .add("rT", "1")
                    .add("tM", theme)
                    .build(),
                timeout = CAPTCHA_TIMEOUT
            )
            readText(response)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "captcha listesi alınamadı (cID=$cid, tM=$theme): ${e.message}")
            return emptyList()
        }
        return TrCaptcha.hashes(body)
    }

    private suspend fun captchaOddOneOut(site: String, cid: String, hashes: List<String>): String? {
        val icons = hashes.map { hash ->
            try {
                bytes("$site/api/Captcha/?cid=$cid&hash=$hash", "$site/")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null
            }
        }
        val chosen = TrCaptcha.oddOneOut(hashes, icons)
        if (chosen == null) {
            Log.d(TAG, "captcha ikonları ayırt edilemedi (cID=$cid, ${icons.count { it != null }}/${hashes.size} indi)")
        }
        return chosen
    }

    private suspend fun captchaSubmit(site: String, cid: String, chosen: String): Boolean = try {
        val response = siteHttp.post(
            "$site/api/Captcha/",
            headers = captchaHeaders(site),
            requestBody = FormBody.Builder()
                .add("cID", cid)
                .add("pC", chosen)
                .add("rT", "2")
                .build(),
            timeout = CAPTCHA_TIMEOUT
        )
        val body = readText(response)
        val rejected = body.contains("incorrect", true) ||
            body.contains("\"error\"", true) ||
            body.contains("\"success\":false", true)
        if (!response.isSuccessful || rejected) {
            Log.d(TAG, "captcha yanıtı reddedildi (HTTP ${response.code})")
            false
        } else {
            true
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Log.d(TAG, "captcha yanıtı gönderilemedi: ${e.message}")
        false
    }

    private fun captchaHeaders(site: String): Map<String, String> =
        headers("$site/", xhr = true) + mapOf("Origin" to site)

    // ==================================================================
    //  Başlıklar / adres yardımcıları
    // ==================================================================

    /** Tarayıcı benzeri başlıklar (CloudStream'in varsayılan UA'sı bot imzasıdır). */
    private fun headers(referer: String?, xhr: Boolean): Map<String, String> = buildMap {
        // NiceHttp `defaultHeaders`'ında `user-agent: NiceHttp` var ve harita
        // birleştirmesi **büyük/küçük harfe duyarlı**: aynı anahtarı kullanarak
        // (küçük harf) varsayılanı eziyoruz, böylece istek tek UA ile gidiyor.
        put("user-agent", BotKontrolKt.UA_FIREFOX)
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer ?: "$mainUrl/")
        if (xhr) {
            put("Accept", "application/json, text/plain, */*")
            put("X-Requested-With", "XMLHttpRequest")
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
        } else {
            put(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )
            put("sec-fetch-dest", "document")
            put("sec-fetch-mode", "navigate")
            put("sec-fetch-user", "?1")
        }
        put("sec-fetch-site", "same-origin")
        put("Upgrade-Insecure-Requests", "1")
    }

    /** Captcha/yaş kapısı sayfası mı? (yalnızca gerçek işaretler) */
    private fun isCaptchaGate(url: String?, body: String?): Boolean {
        if (url != null && url.contains("/api/CaptchaChallenge", true)) return true
        return TrCaptcha.isChallenge(body)
    }

    /**
     * Kayıtlı/yer imi adresi eski alan adını taşıyorsa (`www.tranimeizle.io`,
     * `.top`…) güncel alan adına taşır. **Ağ isteği yapmaz.**
     */
    private fun remapDomain(url: String): String {
        val host = try {
            URI(url).host?.lowercase() ?: return url
        } catch (_: Exception) {
            return url
        }
        if (host !in KNOWN_HOSTS) return url
        val current = try {
            URI(mainUrl).host?.lowercase() ?: return url
        } catch (_: Exception) {
            return url
        }
        if (host == current) return url
        val tail = url.substringAfter("://", "").substringAfter("/", "")
        return mainUrl.trimEnd('/') + "/" + tail
    }

    companion object {
        private const val TAG = "kraptor_TrAnime"

        /** KANIT (25.10.2026): `tranimeizle.io` → 301 → `www.tranimeizle.live`. */
        private const val DEFAULT_DOMAIN = "https://www.tranimeizle.live"

        private val KNOWN_HOSTS = setOf(
            "www.tranimeizle.live", "tranimeizle.live",
            "www.tranimeizle.io", "tranimeizle.io",
            "www.tranimeizle.top", "tranimeizle.top",
            "www.tranimeizle.co", "tranimeizle.co",
            "www.tranimeizle.net", "tranimeizle.net"
        )

        private const val PAGE_TIMEOUT = 20L
        private const val API_TIMEOUT = 15L
        private const val IMAGE_TIMEOUT = 10L
        private const val CAPTCHA_TIMEOUT = 12L

        private val CAPTCHA_CIDS = listOf("0", "1")

        /** Sayfadaki `captchaTheme` önce denenir; bu liste yedektir. */
        private val CAPTCHA_THEMES = listOf("dark", "light")

        /** Tüm eklenti örneklerinde **aynı** çerez kavanozu (captcha oturumu). */
        private val sharedJar = TrCookieJar()

        /** Captcha çözümü serileştirilir: 10 paralel istek 10 kez çözmesin. */
        private val captchaLock = Mutex()
    }
}

/**
 * Domain/path/süre kontrolü yapan çerez kavanozu.
 *
 * NiceHttp'in `Session` sınıfı çerezleri **ada göre** tutuyor (domain yok
 * sayılıyor); site `.live` ve `.io` arasında yönlendirme yaptığı için bu,
 * çerezlerin yanlış alan adına gönderilmesine yol açıyordu.
 */
internal class TrCookieJar : CookieJar {
    private val entries = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val now = System.currentTimeMillis()
        for (cookie in cookies) {
            entries.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > now) entries.add(cookie)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        entries.removeAll { it.expiresAt <= now }
        return entries.filter { cookie ->
            val domain = cookie.domain.trimStart('.')
            url.host.endsWith(domain, ignoreCase = true) && url.encodedPath.startsWith(cookie.path)
        }
    }

    /** Oturum çerezi (`AitrWeb.Session`) var mı? */
    @Synchronized
    fun hasSession(): Boolean {
        val now = System.currentTimeMillis()
        return entries.any { it.name == BotKontrolKt.SESSION_COOKIE_NAME && it.expiresAt > now }
    }
}
