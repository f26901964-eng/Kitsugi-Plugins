package com.hexated

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLEncoder

/**
 * Anizm — puffytr.com (eski/paralel adlar: anizm.net, anizm.com.tr, anizle.org).
 *
 * ## Bu sürümün amacı (v3, 2026-10-25)
 * Önceki sürüm, sitenin **tek bir tema varsayımına** dayanıyordu: arama `/arama?q=`,
 * kaynaklar `button[data-v3-translator]`, oynatıcı yalnızca `videoSource`. Site bu
 * üç noktadan biri değiştiğinde arama 0 sonuç, kaynak listesi boş dönüyordu
 * (kullanıcı ekranı: "Akış bulunamadı").
 *
 * Yeni sürüm **kanıtlanmış iki istemcinin** zincirini birlikte uygular:
 *  - Aniyomi `src/tr/anizm` (2026-10-25): `/anime-izle?sayfa=N`, `div#episodesMiddle
 *    div.posterBlock > a`, `h2.anizm_pageTitle`, `div.infoPosterImg > img`,
 *    `div.episodeListTabContent div > a`, `div#fansec > a[translator]`,
 *    `a.videoPlayerButtons[video]`, `/video/` → `/player/` 302,
 *    anizmplayer = `POST /player/index.php?data=<hash>&do=getVideo` → `securedLink`.
 *  - CloudStream `SalooRepo/Anizm` (2026-10-15) + `hexated` orijinali:
 *    `/searchAnime?query=..&page=..&type=detailed&limit=..` (XHR başlıkları şart),
 *    `/getAnimeListForSearch` (tüm katalog, JSON), `/fullViewSearch?search=`.
 *
 * Zincir üç kuşak API'yi birlikte dener (hangisi canlıysa o kullanılır):
 *  1. **v3**: `[data-sources]` → çevirmen JSON'u → `/video/{id}` → `url`
 *  2. **v2**: `a[translator]` → `{data: html}` → `a[video|videoPlayerButtons]` → `{player: iframe}`
 *  3. **sayfa**: doğrudan `iframe[src]`, `a[video]`, `<video>` ve JS içindeki m3u8/mp4
 *
 * ## Uygulama (Kitsugi) uyumu
 *  - Arama, uygulamanın gönderdiği İngilizce/Türkçe başlıkları [AnizmMatch] ile
 *    çoklu varyanta çevirir ve sonuçları benzerliğe göre sıralar (site Türkçe/romanize
 *    ad kullandığı için ham arama sıfır dönüyordu).
 *  - Bölümlere `episode`/`season` numarası **her zaman** yazılır
 *    (Kitsugi `CsEpisodeMatcher` numarasız bölümü eşleştiremiyor).
 *  - `loadLinks` yalnızca **oynatılabilir** adres yayınlar (m3u8/mp4 ya da
 *    `loadExtractor`'ın çözdüğü host). Oynatıcı sayfası URL'si asla akış olarak
 *    verilmez — Kitsugi bunu embed tarayıcıya sokuyor ve cihazda
 *    `EmbedMediaScanner` kaynaklı KTS-CSP-9110'a düşebiliyor.
 *  - Hiçbir adım istisna fırlatmaz; hata = boş liste + log (uygulama çökmez).
 */
class Anizm : MainAPI() {

    override var mainUrl = DEFAULT_BASE
    override var name = "Anizm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val hasDownloadSupport = true

    /** mainPage URI'leri kurulum anındaki domain ile üretilir; domain değişirse haritalanır. */
    private val mainPageBase: String = mainUrl

    override val mainPage = mainPageOf(
        "$mainUrl/anime-izle?sayfa=" to "Son Eklenenler",
        "$mainUrl/kategoriler/2?sayfa=" to "Aksiyon",
        "$mainUrl/kategoriler/1?sayfa=" to "Macera",
        "$mainUrl/kategoriler/3?sayfa=" to "Komedi",
        "$mainUrl/kategoriler/4?sayfa=" to "Dram",
        "$mainUrl/kategoriler/13?sayfa=" to "Fantastik",
        "$mainUrl/kategoriler/34?sayfa=" to "Shounen",
        "$mainUrl/kategoriler/8?sayfa=" to "Bilim Kurgu",
        "$mainUrl/kategoriler/28?sayfa=" to "Romantizm",
        "$mainUrl/kategoriler/11?sayfa=" to "Doğaüstü Güçler",
        "$mainUrl/kategoriler/21?sayfa=" to "Mecha",
        "$mainUrl/kategoriler/30?sayfa=" to "Spor",
        "$mainUrl/kategoriler/22?sayfa=" to "Film",
        "$mainUrl/temalar/3?sayfa=" to "Isekai",
        "$mainUrl/temalar/24?sayfa=" to "Okul",
        "$mainUrl/temalar/27?sayfa=" to "Oyun"
    )

    // ------------------------------------------------------------------
    // HTTP katmanı
    // ------------------------------------------------------------------

    private fun headers(referer: String = mainUrl + "/", xhr: Boolean = false): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer)
        if (xhr) {
            put("Accept", "application/json, text/javascript, */*; q=0.01")
            put("X-Requested-With", "XMLHttpRequest")
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
        } else {
            put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
            put("Upgrade-Insecure-Requests", "1")
        }
    }

    /** GET — hata/timeout durumunda null döner (akışı boğmaz). */
    private suspend fun getText(
        url: String,
        referer: String = mainUrl + "/",
        xhr: Boolean = false,
        timeout: Long = 12L
    ): String? {
        return try {
            val response = app.get(url, headers = headers(referer, xhr), timeout = timeout)
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "GET ${response.code}: ${url.take(120)}")
                null
            } else {
                response.text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "GET hatası (${e.javaClass.simpleName}): ${url.take(120)}")
            null
        }
    }

    /** POST — JSON gövde, form gövde veya boş gövde. */
    private suspend fun postText(
        url: String,
        referer: String,
        xhr: Boolean = true,
        json: String? = null,
        form: Map<String, String>? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        timeout: Long = 12L
    ): String? {
        val merged = headers(referer, xhr) + extraHeaders
        return try {
            val response = when {
                json != null -> app.post(url, headers = merged, json = JsonAsString(json), timeout = timeout)
                form != null -> app.post(url, headers = merged, data = form, timeout = timeout)
                else -> app.post(url, headers = merged, timeout = timeout)
            }
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "POST ${response.code}: ${url.take(120)}")
                null
            } else {
                response.text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "POST hatası (${e.javaClass.simpleName}): ${url.take(120)}")
            null
        }
    }

    // ------------------------------------------------------------------
    // Domain çözümleme (site adı sık değişiyor + .com/.com.tr ikizleri var)
    // ------------------------------------------------------------------

    private fun looksBlocked(html: String?): Boolean {
        if (html.isNullOrBlank()) return true
        val title = try {
            Jsoup.parse(html).selectFirst("title")?.text() ?: ""
        } catch (_: Exception) {
            ""
        }
        val lower = title.lowercase()
        return lower.contains("just a moment") ||
            lower.contains("access denied") ||
            lower.contains("attention required") ||
            lower.contains("checking your browser") ||
            lower.contains("bot doğrulama") ||
            lower.contains("403 forbidden")
    }

    private fun pageHasContent(html: String?): Boolean {
        if (html.isNullOrBlank()) return false
        return try {
            val doc = Jsoup.parse(html)
            doc.select(
                "a[href*='-bolum'], div.posterBlock, div.anime-card, a.slideAnimeLink, " +
                    "div.cat-card, div#episodesMiddle, div.ui.card"
            ).size >= 3
        } catch (_: Exception) {
            false
        }
    }

    @Volatile
    private var lastBaseProbeAt = 0L

    @Volatile
    private var baseResolved = false

    /**
     * Yaşayan domaini belirler. Mevcut domain sağlıklıysa **tek** istek atar ve
     * sonucu [BASE_TTL_MS] boyunca hatırlar — her aramada ek gecikme olmaz.
     */
    private suspend fun ensureBase(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && baseResolved && now - lastBaseProbeAt < BASE_TTL_MS) return
        lastBaseProbeAt = now
        baseResolved = true

        if (probe(mainUrl)) return

        for (candidate in BASE_CANDIDATES) {
            if (candidate == mainUrl) continue
            if (probe(candidate)) {
                Log.i(LOG_TAG, "Domain değişti: $mainUrl → $candidate")
                mainUrl = candidate
                return
            }
        }
        Log.w(LOG_TAG, "Canlı domain bulunamadı; $mainUrl ile devam ediliyor")
    }

    private suspend fun probe(base: String): Boolean {
        val html = try {
            app.get(base + "/", headers = headers(base + "/"), timeout = 8).text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(LOG_TAG, "domain prob hatası: $base (${e.javaClass.simpleName})")
            null
        }
        val ok = !looksBlocked(html) && pageHasContent(html)
        Log.d(LOG_TAG, "domain prob: $base → ${if (ok) "CANLI" else "ölü"}")
        return ok
    }

    private fun remapBase(url: String): String =
        if (url.startsWith(mainPageBase)) mainUrl + url.substring(mainPageBase.length) else url

    // ------------------------------------------------------------------
    // Arama
    // ------------------------------------------------------------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        return try {
            searchInternal(query, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "search('$query') hatası: ${e.javaClass.simpleName}: ${e.message}")
            newSearchResponseList(emptyList(), false)
        }
    }

    private suspend fun searchInternal(query: String, page: Int): SearchResponseList {
        val trimmed = query.trim()
        if (trimmed.length < 2) return newSearchResponseList(emptyList(), false)
        ensureBase()

        val variants = AnizmMatch.queryVariants(trimmed).ifEmpty { listOf(trimmed) }
        Log.d(LOG_TAG, "search('$trimmed') varyantlar: $variants")

        val collected = LinkedHashMap<String, AnizmCard>()
        var hasNext = false

        fun merge(card: AnizmCard) {
            val existing = collected[card.url]
            if (existing == null) {
                collected[card.url] = card
            } else if (card.altNames.isNotEmpty()) {
                collected[card.url] = existing.copy(
                    altNames = (existing.altNames + card.altNames).distinct(),
                    poster = existing.poster ?: card.poster,
                    score = existing.score ?: card.score
                )
            }
        }

        // 1) Sunucu tarafı JSON araması (en isabetli yol).
        for (variant in variants.take(2)) {
            val cards = searchServer(variant, page)
            if (cards.isNotEmpty()) {
                cards.forEach(::merge)
                if (cards.size >= SEARCH_PAGE_SIZE) hasNext = true
            }
            if (collected.size >= 6) break
        }

        // 2) Yerel katalog (İngilizce/Japonca alternatif adlarla eşleşme — asıl kazanç).
        if (collected.size < 8) {
            val catalogue = loadCatalogue()
            if (catalogue.isNotEmpty()) {
                val ranked = AnizmMatch.rank(
                    items = catalogue,
                    queries = variants,
                    nameOf = { listOf(it.name) + it.altNames },
                    limit = 40
                )
                ranked.forEach(::merge)
                Log.d(LOG_TAG, "katalog eşleşmesi: ${ranked.size}/${catalogue.size}")
            }
        }

        // 3) HTML arama sayfası (JSON uçları kapanırsa).
        if (collected.isEmpty()) {
            for (variant in variants.take(2)) {
                val cards = searchHtml(variant)
                cards.forEach(::merge)
                if (collected.size >= 5) break
            }
        }

        val ranked = AnizmMatch.rank(
            items = collected.values.toList(),
            queries = variants,
            nameOf = { listOf(it.name) + it.altNames },
            limit = 40
        )
        Log.d(LOG_TAG, "search('$trimmed') → ${ranked.size} sonuç")
        return newSearchResponseList(ranked.map { it.toSearchResponse() }, hasNext || ranked.size >= SEARCH_PAGE_SIZE)
    }

    private fun AnizmCard.toSearchResponse(): AnimeSearchResponse =
        newAnimeSearchResponse(name, url, TvType.Anime) {
            this.posterUrl = this@toSearchResponse.poster
            this.score = this@toSearchResponse.score?.let { Score.from10(it) }
        }

    /** `GET /searchAnime?query=..&page=..&type=detailed` (XHR başlıkları zorunlu). */
    private suspend fun searchServer(query: String, page: Int): List<AnizmCard> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/searchAnime?query=$encoded&page=${page.coerceAtLeast(1)}" +
            "&type=detailed&limit=$SEARCH_PAGE_SIZE&priorityField=info_title" +
            "&orderBy=info_id&orderDirection=DESC"
        val body = getText(url, mainUrl + "/", xhr = true) ?: return emptyList()
        return parseSearchJson(body)
    }

    /** `/searchAnime` ve `/getAnimeListForSearch` aynı alan adlarını kullanır. */
    private fun parseSearchJson(body: String): List<AnizmCard> {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return emptyList()
        val array: JSONArray = try {
            if (trimmed.startsWith("[")) {
                JSONArray(trimmed)
            } else {
                JSONObject(trimmed).optJSONArray("data") ?: return emptyList()
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "arama JSON çözülemedi: ${e.message}")
            return emptyList()
        }
        val out = ArrayList<AnizmCard>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            cardOfJson(item)?.let(out::add)
        }
        return out
    }

    private fun cardOfJson(item: JSONObject): AnizmCard? {
        val otherNames = (item.optString("info_othernames") + "," + item.optString("info_japanese"))
            .split(',', '/', '|')
            .map { it.trim() }
            .filter { it.length in 2..120 }
        val score = item.optDouble("info_malpoint", 0.0).takeIf { !it.isNaN() && it > 0.0 && it <= 10.0 }
        return AnizmSite.cardFromJsonFields(
            base = mainUrl,
            title = item.optString("info_title").ifBlank { null },
            slug = item.optString("info_slug").ifBlank { null },
            poster = item.optString("info_poster").ifBlank { null },
            year = item.optString("info_year").toIntOrNull(),
            score = score,
            altNames = otherNames
        )
    }

    @Volatile
    private var catalogueCache: List<AnizmCard> = emptyList()

    @Volatile
    private var catalogueFetchedAt = 0L

    /**
     * Tüm katalog (`/getAnimeListForSearch`) — yaklaşık 6 bin kayıt, tek istek.
     * Uygulama İngilizce başlıkla aradığında Türkçe site araması boş dönerken bu
     * liste `info_othernames`/`info_japanese` üzerinden doğru animeyi bulur.
     * [CATALOGUE_TTL_MS] boyunca bellekte tutulur.
     */
    private suspend fun loadCatalogue(): List<AnizmCard> {
        val now = System.currentTimeMillis()
        if (catalogueCache.isNotEmpty() && now - catalogueFetchedAt < CATALOGUE_TTL_MS) return catalogueCache
        val body = getText("$mainUrl/getAnimeListForSearch", mainUrl + "/", xhr = true, timeout = 20)
            ?: return catalogueCache
        val parsed = parseSearchJson(body)
        if (parsed.isNotEmpty()) {
            catalogueCache = parsed
            catalogueFetchedAt = now
            Log.d(LOG_TAG, "katalog yüklendi: ${parsed.size} kayıt")
        }
        return catalogueCache
    }

    /** HTML arama (`/arama?q=`, `/fullViewSearch?search=`, `/arama/{q}`). */
    private suspend fun searchHtml(query: String): List<AnizmCard> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val pathEncoded = URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        val attempts = listOf(
            "$mainUrl/arama?q=$encoded",
            "$mainUrl/fullViewSearch?search=$encoded&skip=0",
            "$mainUrl/arama/$pathEncoded"
        )
        for (url in attempts) {
            val html = getText(url, mainUrl + "/") ?: continue
            if (looksBlocked(html)) continue
            val doc = Jsoup.parse(html, url)
            val cards = AnizmSite.parseCards(doc, url)
            if (cards.isNotEmpty()) {
                Log.d(LOG_TAG, "HTML arama ($url) → ${cards.size} kart")
                return cards
            }
        }
        return emptyList()
    }

    // ------------------------------------------------------------------
    // Ana sayfa
    // ------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            ensureBase()
            val url = remapBase(request.data) + page
            val html = getText(url, mainUrl + "/")
            if (html.isNullOrBlank()) return newHomePageResponse(request.name, emptyList(), false)
            val doc = Jsoup.parse(html, url)
            val cards = AnizmSite.parseCards(doc, url)
            Log.d(LOG_TAG, "mainPage('${request.name}') → ${cards.size} kart")
            val hasNext = cards.isNotEmpty() && page < MAX_MAIN_PAGE
            newHomePageResponse(request.name, cards.map { it.toSearchResponse() }, hasNext)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "getMainPage('${request.name}') hatası: ${e.javaClass.simpleName}: ${e.message}")
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    // ------------------------------------------------------------------
    // Detay + bölümler
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        ensureBase()
        val pageUrl = remapBase(url).let { if (AnizmSite.isEpisodeUrl(it)) AnizmSite.toSeriesUrl(it) ?: it else it }
        val html = getText(pageUrl, mainUrl + "/") ?: throw ErrorLoadingException("Anizm sayfası yüklenemedi")
        val doc = Jsoup.parse(html, pageUrl)
        val detail = AnizmSite.parseDetail(doc, pageUrl, pageUrl)

        // Sayfa düzeyinde hiç bölüm yoksa "tek parça" (film/OVA) olarak sunulur.
        val episodes = detail.episodes
        val season = AnizmMatch.seasonOf(detail.title) ?: 1
        val episodeList = episodes.map { episode ->
            val number = episode.number
            newEpisode(episode.url) {
                this.name = episode.name?.ifBlank { null } ?: number?.let { "$it. Bölüm" } ?: "Bölüm"
                this.episode = number
                this.season = season
                this.posterUrl = episode.poster ?: detail.poster
            }
        }

        val commonTags = detail.tags.distinct()

        return if (episodeList.isEmpty()) {
            val singleLink = doc.select("a[href*='-bolum'], a[href*='/bolum/'], a[href*='izle']")
                .mapNotNull { AnizmSite.absolute(pageUrl, it.attr("href")) }
                .firstOrNull { AnizmSite.isSiteUrl(it) }
                ?: pageUrl
            newMovieLoadResponse(detail.title, pageUrl, TvType.AnimeMovie, singleLink) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
                this.tags = commonTags
                this.score = detail.score?.let { Score.from10(it) }
                this.duration = 24
            }
        } else {
            newAnimeLoadResponse(detail.title, pageUrl, TvType.Anime, false) {
                this.posterUrl = detail.poster
                this.plot = detail.plot
                this.year = detail.year
                this.tags = commonTags
                this.score = detail.score?.let { Score.from10(it) }
                this.showStatus = if (episodeList.size >= 2) ShowStatus.Ongoing else ShowStatus.Completed
                addEpisodes(DubStatus.Subbed, episodeList)
            }
        }
    }

    // ------------------------------------------------------------------
    // Video kaynakları
    // ------------------------------------------------------------------

    /**
     * `/player/{id}` adresinin **302 hedefini** döndürür.
     *
     * Anizm'in (anizm.net/puffytr ailesi) kendi oynatıcı yolu bu: bölüm sayfasındaki
     * `a.videoPlayerButtons[video]` → `/video/{id}`; site oynatıcıyı `/player/{id}`
     * üzerinden açar ve **yönlendirme (Location) başlığıyla** gerçek host'a
     * (anizmplayer / filemoon / sibnet …) gönderir. İstemci yönlendirmeyi kendi
     * izlerse hedef adres kaybolur; bu yüzden yönlendirme KAPATILARAK okunur.
     */
    private suspend fun followLocation(url: String, referer: String): String? {
        return try {
            val response = app.get(
                url,
                headers = headers(referer, xhr = false),
                allowRedirects = false,
                timeout = 12L
            )
            // Bu fork'ta `Response.headers` Map<String, String> (başlık adı büyük/küçük
            // harfe duyarlı olabildiği için iki biçim de denenir).
            val location = (response.headers["Location"]
                ?: response.headers["location"])
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            if (location == null) null else AnizmSite.absolute(url, location)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(LOG_TAG, "Location okunamadı (${url.take(80)}): ${e.message}")
            null
        }
    }

    /** Phase A çıktısı: çözülecek oynatıcı adayı. */
    private data class Candidate(val url: String, val label: String, val referer: String)

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = remapBase(data)
        val candidates = try {
            collectCandidates(pageUrl, subtitleCallback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "loadLinks aday toplama hatası: ${e.javaClass.simpleName}: ${e.message}")
            emptyList()
        }
        if (candidates.isEmpty()) {
            Log.w(LOG_TAG, "loadLinks: hiç oynatıcı adayı bulunamadı ($pageUrl)")
            return false
        }
        Log.d(LOG_TAG, "loadLinks: ${candidates.size} aday çözülecek")

        val emitted = LinkedHashSet<String>()
        var emittedCount = 0
        // Yayın callback'i sıralı yapılır: Kitsugi'nin toplama listeleri eşzamanlı
        // yazıma karşı korumalı değil (ConcurrentModificationException riski).
        val emitLock = Mutex()
        for (candidate in candidates) {
            try {
                val count = emitLock.withLock {
                    resolveCandidate(candidate, emitted, subtitleCallback, callback)
                }
                emittedCount += count
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(LOG_TAG, "aday çözülemedi (${candidate.url.take(90)}): ${e.message}")
            }
        }
        Log.d(LOG_TAG, "loadLinks: $emittedCount akış yayınlandı")
        return emittedCount > 0
    }

    /**
     * Phase A — çevirmen/kaynak listelerini **paralel** toplar.
     * Üç kuşak API zinciri birlikte denenir; herhangi biri çalışırsa kaynak gelir.
     */
    private suspend fun collectCandidates(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<Candidate> = coroutineScope {
        val html = getText(pageUrl, mainUrl + "/") ?: return@coroutineScope emptyList()
        val doc = Jsoup.parse(html, pageUrl)
        val out = java.util.Collections.synchronizedList(mutableListOf<Candidate>())

        // Sayfadaki altyazı izleri (<track>) — bölüm sayfası düzeyinde.
        emitSubtitles(html, pageUrl, subtitleCallback)

        val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

        // 1) v3: data-sources (çevirmen) → videos[] → /video/{id}
        for (element in doc.select("[data-sources], [data-v3-translator], button[data-v3-translator]")) {
            val sourceUrl = AnizmSite.absolute(pageUrl, element.attr("data-sources")) ?: continue
            val fansub = element.attr("title").ifBlank { element.text() }.trim().ifBlank { "Kaynak" }
            jobs += async {
                collectV3(pageUrl, sourceUrl, fansub, out)
            }
        }

        // 2) v2: div#fansec a[translator] / div.episodeTranslators a[translator]
        for (anchor in doc.select("div#fansec a[translator], div.episodeTranslators a[translator], a[translator]")) {
            val translatorUrl = AnizmSite.absolute(pageUrl, anchor.attr("translator")) ?: continue
            val fansub = anchor.text().trim().ifBlank { "Çevirmen" }
            jobs += async {
                collectV2(pageUrl, translatorUrl, fansub, out)
            }
        }

        // 3) Sayfa düzeyi doğrudan oynatıcılar.
        for (iframe in doc.select("iframe[src], iframe[data-src]")) {
            val src = AnizmSite.absolute(pageUrl, iframe.attr("src").ifBlank { iframe.attr("data-src") })
                ?: continue
            if (isAdHost(src)) continue
            out.add(Candidate(src, "Sayfa", pageUrl))
        }
        for (anchor in doc.select("a[video], a[data-video]")) {
            val src = AnizmSite.absolute(pageUrl, anchor.attr("video").ifBlank { anchor.attr("data-video") })
                ?: continue
            if (isAdHost(src)) continue
            out.add(Candidate(src, anchor.text().trim().ifBlank { "Sayfa" }, pageUrl))
        }
        for (video in doc.select("video[src], video source[src]")) {
            val src = AnizmSite.absolute(pageUrl, video.attr("src")) ?: continue
            out.add(Candidate(src, "Doğrudan", pageUrl))
        }
        // Sayfa JS'i doğrudan m3u8/mp4 taşıyorsa.
        for (media in mediaFromHtml(html, pageUrl)) {
            out.add(Candidate(media, "Doğrudan", pageUrl))
        }

        jobs.awaitAll()
        out.distinctBy { it.url }
    }

    /** v3 zinciri: `{success, videos:[{id,name}]}` → `/video/{id}` → `{url}`. */
    private suspend fun collectV3(
        pageUrl: String,
        sourceUrl: String,
        fansub: String,
        out: MutableList<Candidate>
    ) {
        val body = getText(sourceUrl, pageUrl, xhr = true) ?: return
        val root = try {
            JSONObject(body)
        } catch (_: Exception) {
            Log.d(LOG_TAG, "v3 kaynak listesi JSON değil ($fansub)")
            return
        }
        if (!root.optBoolean("success", true)) {
            Log.d(LOG_TAG, "v3 kaynak listesi success=false ($fansub)")
            return
        }
        val videos = root.optJSONArray("videos") ?: return
        for (index in 0 until videos.length()) {
            val video = videos.optJSONObject(index) ?: continue
            val id = video.optInt("id", 0)
            if (id <= 0) continue
            val server = video.optString("name").ifBlank { "Kaynak ${index + 1}" }
            val videoUrl = sourceUrl.replace(Regex("/cevirmen/\\d+$"), "/video/$id")
            val payload = getText(videoUrl, pageUrl, xhr = true) ?: continue
            val target = try {
                val videoRoot = JSONObject(payload)
                if (!videoRoot.optBoolean("success", true)) null
                else videoRoot.optString("url").ifBlank { null }
            } catch (_: Exception) {
                null
            } ?: continue
            val absolute = AnizmSite.absolute(videoUrl, target) ?: continue
            if (isAdHost(absolute)) continue
            out.add(Candidate(absolute, "$fansub | $server", pageUrl))
        }
    }

    /** v2 zinciri: `{data: html}` → `a[video]` → `{player: iframe html}`. */
    private suspend fun collectV2(
        pageUrl: String,
        translatorUrl: String,
        fansub: String,
        out: MutableList<Candidate>
    ) {
        val body = getText(translatorUrl, pageUrl, xhr = true) ?: return
        val dataHtml = try {
            JSONObject(body).optString("data").ifBlank { body }
        } catch (_: Exception) {
            body
        }
        if (dataHtml.isBlank()) return
        val buttons = Jsoup.parse(dataHtml, translatorUrl)
            .select("a[video], a.videoPlayerButtons, a[data-video]")
        for (button in buttons) {
            val videoUrl = AnizmSite.absolute(
                translatorUrl,
                button.attr("video").ifBlank { button.attr("data-video") }
            ) ?: continue
            if (isAdHost(videoUrl)) continue
            val server = button.attr("data-video-name").ifBlank { button.text() }.trim().ifBlank { "Kaynak" }

            // video sayfası → {player: "<iframe …>"} → iframe src
            val payload = getText(videoUrl, pageUrl, xhr = true)
            val iframes = mutableListOf<String>()
            if (!payload.isNullOrBlank()) {
                val playerHtml = try {
                    JSONObject(payload).optString("player")
                } catch (_: Exception) {
                    payload
                }
                if (playerHtml.isNotBlank()) {
                    Jsoup.parse(playerHtml, videoUrl).select("iframe[src], iframe[data-src]").forEach { frame ->
                        AnizmSite.absolute(videoUrl, frame.attr("src").ifBlank { frame.attr("data-src") })
                            ?.let(iframes::add)
                    }
                    // JSON bazı kaynaklarda düz adres taşır.
                    if (iframes.isEmpty()) iframes.addAll(mediaFromHtml(playerHtml, videoUrl))
                }
            }
            val label = "$fansub | $server"

            // Aniyomi'nin kullandığı asıl yol: /video/{id} → /player/{id} → 302 Location.
            // (Site oynatıcıyı bu yönlendirme ile açar; hedef gerçek host adresidir.)
            val playerUrl = videoUrl.replace("/video/", "/player/")
            if (playerUrl != videoUrl) {
                val target = followLocation(playerUrl, pageUrl)
                if (target != null && !isAdHost(target)) {
                    out.add(Candidate(target, label, pageUrl))
                }
            }

            if (iframes.isEmpty()) {
                // Yedek: dönen gövdenin kendisi oynatıcı olabilir.
                out.add(Candidate(videoUrl, label, pageUrl))
            } else {
                iframes.filterNot(::isAdHost).forEach { out.add(Candidate(it, label, pageUrl)) }
            }
        }
    }

    /** Phase B — tek adayı oynatılabilir akışa çevirir; yayınlanan link sayısını döner. */
    private suspend fun resolveCandidate(
        candidate: Candidate,
        emitted: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val url = candidate.url
        if (url.isBlank()) return 0

        // 1) Doğrudan medya dosyası.
        if (looksLikeMedia(url)) {
            return if (emitMedia(url, candidate.label, candidate.referer, emitted, callback)) 1 else 0
        }

        // 2) Anizm'in kendi oynatıcısı (anizmplayer.com/video/<hash>).
        val playerHash = anizmPlayerHash(url)
        if (playerHash != null) {
            return resolveAnizmPlayer(playerHash, url, candidate.label, emitted, callback)
        }

        // 2.5) Site içi oynatıcı yolu (/player/{id} veya /video/{id}) → 302 Location.
        if (IN_SITE_PLAYER.containsMatchIn(url)) {
            val target = followLocation(url, candidate.referer)
            if (target != null && target != url) {
                val hash = anizmPlayerHash(target)
                if (hash != null) return resolveAnizmPlayer(hash, target, candidate.label, emitted, callback)
                val handledTarget = try {
                    loadExtractor(target, candidate.referer, subtitleCallback, callback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    false
                }
                if (handledTarget) return 1
                val html = getText(target, candidate.referer)
                if (html != null) {
                    var c = 0
                    for (link in mediaFromHtml(html, target).take(3)) {
                        if (emitMedia(link, candidate.label, target, emitted, callback)) c++
                    }
                    if (c > 0) return c
                }
            }
        }

        // 3) Kütüphane/uygulama extractor'ları (filemoon, sibnet, ok.ru, voe, sendvid…).
        var count = 0
        val handled = try {
            loadExtractor(url, candidate.referer, subtitleCallback, callback)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(LOG_TAG, "loadExtractor hatası (${url.take(80)}): ${e.message}")
            false
        }
        if (handled) return 1

        // 4) Oynatıcı sayfasını çek: içindeki m3u8/mp4 veya iç iframe.
        val pageHtml = getText(url, candidate.referer) ?: return 0
        val media = mediaFromHtml(pageHtml, url)
        for (link in media.take(3)) {
            if (emitMedia(link, candidate.label, url, emitted, callback)) count++
        }
        if (count > 0) return count

        // 5) Bir katman iç iframe (embed → player).
        val innerFrames = Jsoup.parse(pageHtml, url)
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { AnizmSite.absolute(url, it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot(::isAdHost)
            .take(2)
        for (frame in innerFrames) {
            val frameHash = anizmPlayerHash(frame)
            if (frameHash != null) {
                count += resolveAnizmPlayer(frameHash, frame, candidate.label, emitted, callback)
                continue
            }
            val frameHtml = getText(frame, url) ?: continue
            for (link in mediaFromHtml(frameHtml, frame).take(2)) {
                if (emitMedia(link, candidate.label, frame, emitted, callback)) count++
            }
        }
        return count
    }

    // ------------------------------------------------------------------
    // anizmplayer.com (Aincrad / FirePlayer)
    // ------------------------------------------------------------------

    private fun anizmPlayerHash(url: String): String? {
        if (!url.contains(PLAYER_HOST, ignoreCase = true)) return null
        val match = Regex("/video/([A-Za-z0-9_-]{8,64})").find(url) ?: return null
        return match.groupValues.getOrNull(1)
    }

    /**
     * `POST /player/index.php?data=<hash>&do=getVideo` (form: hash, r).
     * Yanıt: `securedLink` (HLS), `videoSource`, `videoSources[]` veya düz adres.
     * — Aniyomi `AincradExtractor` `securedLink`, `hexated` `videoSource` kullanıyor;
     * ikisi de okunur.
     */
    private suspend fun resolveAnizmPlayer(
        hash: String,
        playerPageUrl: String,
        label: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val apiUrl = "$PLAYER_HOST/player/index.php?data=$hash&do=getVideo"
        val body = postText(
            url = apiUrl,
            referer = playerPageUrl,
            xhr = true,
            form = mapOf("hash" to hash, "r" to (mainUrl + "/")),
            extraHeaders = mapOf(
                "Origin" to PLAYER_HOST,
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            ),
            timeout = 15
        )
        if (body.isNullOrBlank()) {
            Log.w(LOG_TAG, "anizmplayer yanıt yok: $hash")
            return 0
        }

        var count = 0
        val json = try {
            JSONObject(body)
        } catch (_: Exception) {
            null
        }
        if (json != null) {
            val secured = json.optString("securedLink").ifBlank { json.optString("videoSource") }
            if (secured.isNotBlank() && looksLikeMedia(secured)) {
                if (emitMedia(secured, label, playerPageUrl, emitted, callback)) count++
            }
            val sources = json.optJSONArray("videoSources")
            if (sources != null) {
                for (index in 0 until sources.length()) {
                    val source = sources.optJSONObject(index) ?: continue
                    val file = source.optString("file").ifBlank { source.optString("src") }
                    if (file.isBlank()) continue
                    val absolute = AnizmSite.absolute(playerPageUrl, file) ?: continue
                    val quality = source.optString("label").ifBlank { source.optString("quality") }
                    val sourceLabel = if (quality.isNotBlank()) "$label [$quality]" else label
                    if (emitMedia(absolute, sourceLabel, playerPageUrl, emitted, callback)) count++
                }
            }
        }
        // JSON dışı/düz yanıt: içindeki medya adresleri.
        for (link in mediaFromHtml(body, playerPageUrl).take(3)) {
            if (emitMedia(link, label, playerPageUrl, emitted, callback)) count++
        }
        if (count == 0) Log.w(LOG_TAG, "anizmplayer akış vermedi ($hash): ${body.take(120)}")
        return count
    }

    // ------------------------------------------------------------------
    // Yardımcılar: medya bulma / yayınlama
    // ------------------------------------------------------------------

    private fun looksLikeMedia(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mkv") ||
            path.endsWith(".webm") || path.endsWith(".mpd") || path.contains(".m3u8")
    }

    private fun isAdHost(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOSTS.any { lower.contains(it) }
    }

    /** HTML/JS içindeki m3u8/mp4 adresleri (kaçışlı `\/` biçimi dahil). */
    private fun mediaFromHtml(html: String, base: String): List<String> {
        if (html.isBlank()) return emptyList()
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/")
        val found = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""["'](https?://[^"']+?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["']([^"']+?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+?\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""file\s*:\s*["']([^"']+?\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+?\.mpd[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(unescaped)) {
                val raw = match.groupValues.lastOrNull()?.trim().orEmpty()
                if (raw.isEmpty() || raw.startsWith("data:")) continue
                val absolute = AnizmSite.absolute(base, raw) ?: continue
                if (isAdHost(absolute)) continue
                found.add(absolute)
                if (found.size >= 6) return found.toList()
            }
        }
        return found.toList()
    }

    private suspend fun emitMedia(
        url: String,
        label: String,
        referer: String,
        emitted: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val clean = url.trim().replace("\\/", "/")
        if (!clean.startsWith("http")) return false
        if (!emitted.add(clean)) return false
        val isHls = clean.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.contains(".m3u8") }
        val linkRef = referer.ifBlank { mainUrl + "/" }
        callback(
            newExtractorLink(
                source = name,
                name = label,
                url = clean,
                type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = linkRef
                this.headers = mapOf("Referer" to linkRef, "User-Agent" to UA)
                this.quality = getQualityFromName(label)
            }
        )
        return true
    }

    /** `<track>` ve `.vtt/.srt` adreslerini altyazı olarak bildirir (varsa). */
    private fun emitSubtitles(html: String?, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (html.isNullOrBlank()) return
        try {
            val doc = Jsoup.parse(html, base)
            doc.select("track[src]").forEach { track ->
                val kind = track.attr("kind").ifBlank { "subtitles" }
                if (!kind.equals("subtitles", true) && !kind.equals("captions", true)) return@forEach
                val src = AnizmSite.absolute(base, track.attr("src")) ?: return@forEach
                val lang = track.attr("srclang").ifBlank { track.attr("label") }.ifBlank { "Altyazı" }
                subtitleCallback(newSubtitleFile(lang, src))
            }
        } catch (e: Exception) {
            Log.d(LOG_TAG, "altyazı çözümleme hatası: ${e.message}")
        }
    }

    companion object {
        private const val LOG_TAG = "hexated_Anizm"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        private const val PLAYER_HOST = "https://anizmplayer.com"

        /** Site içi oynatıcı yolu — 302 Location ile gerçek host'a gider. */
        private val IN_SITE_PLAYER = Regex("/player/\\d+|/video/\\d+", RegexOption.IGNORE_CASE)
        private const val DEFAULT_BASE = "https://puffytr.com"
        private const val SEARCH_PAGE_SIZE = 24
        private const val MAX_MAIN_PAGE = 60
        private const val BASE_TTL_MS = 10 * 60 * 1000L
        private const val CATALOGUE_TTL_MS = 30 * 60 * 1000L

        /** Aynı sitenin paralel/eski adları — probe sırası öncelik sırasıdır. */
        private val BASE_CANDIDATES = listOf(
            "https://puffytr.com",
            "https://www.puffytr.com",
            "https://puffytr.com.tr",
            "https://anizm.net",
            "https://anizm.com.tr",
            "https://anizle.org"
        )

        /**
         * Reklam/ön-roll ağları — bunlar "oynatıcı adayı" gibi görünse de video
         * vermez. Kısa jenerik parçalar ("bet", "cpm") bilinçli olarak YOK:
         * gerçek host adlarını yanlışlıkla elerler.
         */
        private val AD_HOSTS = listOf(
            "doubleclick", "googlesyndication", "adsterra", "popads", "popcash", "propellerads",
            "onclickads", "a-ads.com", "adsystem", "juicyads", "exoclick", "hilltopads",
            "clickadu", "mgid.com", "adtng", "tsyndicate", "trafficjunky"
        )
    }
}

/**
 * `SubtitleFile` public constructor'ı fork'ta deprecated; fabrika fonksiyonu
 * aynı nesneyi üretir (davranış birebir aynı).
 */
private fun newSubtitleFile(lang: String, url: String): SubtitleFile = SubtitleFile(lang, url)
