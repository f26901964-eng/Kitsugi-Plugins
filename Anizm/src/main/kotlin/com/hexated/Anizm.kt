package com.hexated

import android.util.Base64 as AndroidBase64 // v27: java.util.Base64 API 26+ (minSdk 21'de cokerdi)
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.safeApiCall
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Anizm (puffytr.com) ana API — tam yeniden yazım (2026-10-25).
 *
 * SİTE YAPISI (tümü site'nin kendi HTML/JS arşivleriyle kanıtlandı;
 * kanıtlar research/anizm-capture/ altında):
 *
 * 1. Arama:  GET /arama?q={sorgu}  → server-rendered `article.v3-catalog-card`
 *    (href, poster, puan, yıl, bölüm sayısı, özet, türler).
 * 2. Katalog: ana sayfa + /kategoriler/{id} (her ikisi ?sayfa=N sayfalı).
 * 3. Detay:  GET /{slug} → h1 a (başlık), itemprop image (poster),
 *    itemprop description (özet), a.ui.label (türler),
 *    `a[href=/{slug}-{n}-bolum-izle]` (bölüm listesi).
 * 4. Bölüm:  GET /{slug}-{n}-bolum-izle → `button[data-v3-translator]`
 *    (data-sources = https://puffytr.com/bolum/{eid}/cevirmen/{tid},
 *    title = fansub adı) + meta[name=csrf-token].
 * 5. Kaynaklar: GET {data-sources} → JSON {success, videos:[{id,name,watchTogether}]}
 *    (kanıt: 2026-10-25 SPN capture — 8 kaynak: Aincrad, Odnoklassniki, GDrive,
 *    Abyss, YourUpload, HDVid, Voe, StreamTape).
 * 6. Video:  GET /bolum/{eid}/video/{vid} → JSON {success, url, source, ...};
 *    url SAME-ORIGIN (https://puffytr.com/player/{pid}) — kanıt: v3-episode.js v1.3.5.
 * 7. Player: GET /player/{pid} → 302 (kanıt: X-Archive-Orig-Location header capture):
 *    - https://anizmplayer.com/video/{token} → KENDİ OYNATICILARI (FirePlayer/JW Player)
 *    - diğer hostlar → harici provider (streamtape, hdvid, ok.ru, ...)
 *
 * FIREPLAYER PROTOKOLÜ (anizmplayer.com — scripts.php v12 + cryptojs-aes-format.js 2.1.1):
 * - /video/{token} sayfasındaki obfike script DECODE edilirse
 *   `FirePlayer("{token}", {videoSettings})` çağrısı ortaya çıkar.
 *   videoSettings: title (kalite dahil), ck (AES şifresi, \xNN escaped),
 *   tracks (VTT), thumbnail. Decode algoritması node'da BİREBİR doğrulandı.
 * - POST /player/index.php?data={token}&do=getVideo  form: {hash: token, r: referer}
 *   → JSON:
 *     hls=true  → {videoSource: "https://....m3u8"} (düz)
 *     hls=false → {videoSources: [{file: "<AES>", ...}], ck: "<AES şifresi>"}
 * - AES: cryptojs-aes-php formatı {"ct","iv","s"}; anahtar = MD5(şifre + salt)
 *   (EVP_BytesToKey 1 iterasyon), AES-128-CBC, sonuç = JSON string.
 *
 * BOT KORUMASI: Site CF'da yalnızca DATACENTER IP'lerini engeller (1005).
 * Cihaz (rezidans) IP'si engellenmez → düz HTTP yeterli, WebView gerekmez.
 * Yaş duvarı SADECE client-side (sessionStorage/localStorage — kanıt:
 * v3-content-warning.js v1.0.5); video API'lerinde server-side duvar YOK.
 *
 * ALTYAZI: Ayncrad/JW Player kaynaklarında videoSettings.tracks içinde
 * kind=captions olan VTT'ler subtitleCallback ile eklenir (varsa).
 * Diğer fansub videolarında altyazı videoya gömülüdür (sitenin standardı).
 */
/**
 * v27: SubtitleFile public constructor'u deprecated; fork'un önerdigi
 * newSubtitleFile ayni nesneyi üretir (Anizm eski API'de kalmisti).
 */
private fun newSubtitleFile(lang: String, url: String): SubtitleFile =
    SubtitleFile(lang, url)

class Anizm : MainAPI() {

    override var mainUrl = "https://puffytr.com"
    override var name = "Anizm"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)
    override val hasDownloadSupport = true

    companion object {
        private const val LOG_TAG = "hexated_Anizm"
        private const val PLAYER_SERVER = "https://anizmplayer.com"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:132.0) Gecko/20100101 Firefox/132.0"

        // 2026-10-25 ana sayfa capture'ından doğrulanan kategori listesi (id → ad).
        private val CATEGORIES: List<Pair<Int, String>> = listOf(
            1 to "Macera", 2 to "Aksiyon", 3 to "Komedi", 4 to "Dram", 5 to "Romantizm",
            6 to "Ecchi", 7 to "Askeri", 8 to "Bilim-Kurgu", 9 to "Büyü", 10 to "Dedektif",
            11 to "Doğaüstü Güçler", 12 to "Dövüş", 13 to "Fantastik", 14 to "Gerilim",
            15 to "Gizem", 16 to "Harem", 17 to "Hazine-Avcılığı", 18 to "Josei",
            20 to "Korku", 21 to "Mecha", 22 to "Film", 23 to "Müzik", 24 to "Ninja",
            25 to "OAD-ONA-OVA", 26 to "Okul", 27 to "Oyun", 28 to "Politik",
            29 to "Psikolojik", 30 to "Dövüş Sanatları", 31 to "Seinen", 32 to "Shoujo",
            33 to "Shoujo-Ai", 34 to "Shounen", 35 to "Shounen-Ai", 36 to "Yaşamdan Kesitler",
            37 to "Spor", 38 to "Süper-Güç", 39 to "Tarihi", 40 to "Tuhaf", 41 to "Uzay",
            42 to "Vampir", 43 to "Yaoi", 44 to "Yuri", 45 to "Şeytanlar", 46 to "Savaş",
            47 to "Samuray", 48 to "Parodi", 49 to "Çocuklar", 50 to "Bunama",
            51 to "Canlı Aksiyon", 52 to "Arabalar", 53 to "Polisiye", 55 to "Hentai",
            56 to "Erkeklerin Aşkı", 57 to "Kızların Aşkı", 58 to "Ödüllü", 59 to "Gurme",
            60 to "Erotik", 61 to "Mahou Shoujo"
        )

        // Anime slug'ı OLMAYAN bilinen path'ler (kart çözümlemede elenir).
        private val BLOCKED_SLUGS = setOf(
            "kategoriler", "temalar", "profil", "arama", "girisyap", "uyeol", "listeler",
            "bolum", "player", "raporver", "storage", "upload", "js", "css", "images",
            "api", "search", "anasayfa", "home", "index", "hakkimizda", "iletisim",
            "sss", "duyurular", "gizlilik", "kullanim-sartlari", "cerez", "destek",
            "kurallar", "haber", "blog", "tv", "yayin", "takvim", "sozluk"
        )

        private val EPISODE_SUFFIX = Regex("-([0-9]+)-bolum-izle$")
        private val SLUG_RE = Regex("^[a-z0-9][a-z0-9-]*$")
    }

    override val mainPage = mainPageOf(
        *(listOf("$mainUrl?sayfa=" to "Son Eklenenler") +
            CATEGORIES.map { (id, n) -> "$mainUrl/kategoriler/$id?sayfa=" to n }).toTypedArray()
    )

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private fun docHeaders(referer: String): Map<String, String> = mapOf(
        "User-Agent" to UA,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "tr-TR,tr;q=0.9,en;q=0.8",
        "Referer" to referer
    )

    private fun xhrHeaders(referer: String, csrf: String? = null): Map<String, String> = buildMap {
        put("User-Agent", UA)
        put("Accept", "application/json, text/plain, */*")
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer)
        put("X-Requested-With", "XMLHttpRequest")
        if (csrf != null) put("X-CSRF-TOKEN", csrf)
    }

    private suspend fun fetchDoc(url: String, referer: String): String {
        val resp = try {
            // v27: zaman asimi ZORUNLU — sinirsiz bekleme "takilma + son anda
            // hata" mekanizmasinin kacak halidir (TrAnimeIzle v24 kaniti).
            app.get(url, headers = docHeaders(referer), timeout = 15)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "GET ağ hatası: $url → ${e.message}")
            throw ErrorLoadingException("Anizm'e ulaşılamadı: ${e.message}")
        }
        if (!resp.isSuccessful) {
            Log.e(LOG_TAG, "GET başarısız: $url → HTTP ${resp.code}")
            throw ErrorLoadingException(
                if (resp.code == 403)
                    "Anizm erişimi engelledi (HTTP 403). Tarayıcıdan test edin."
                else
                    "Anizm yanıt vermedi (HTTP ${resp.code})."
            )
        }
        val text = resp.text
        if (text.isNullOrBlank()) throw ErrorLoadingException("Boş sayfa döndü: $url")
        return text
    }

    private suspend fun fetchJson(url: String, referer: String, csrf: String? = null): String {
        val resp = try {
            app.get(url, headers = xhrHeaders(referer, csrf), timeout = 15)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "JSON GET ağ hatası: $url → ${e.message}")
            throw ErrorLoadingException("Kaynak listesi alınamadı (${e::class.simpleName})")
        }
        if (!resp.isSuccessful) {
            Log.e(LOG_TAG, "JSON GET başarısız: $url → HTTP ${resp.code}")
            throw ErrorLoadingException("Kaynak listesi HTTP ${resp.code}")
        }
        val text = resp.text
        if (text.isNullOrBlank()) throw ErrorLoadingException("Kaynak listesi boş yanıt verdi")
        return text
    }

    // ------------------------------------------------------------------
    // Kart çözümleme (arama + katalog)
    // ------------------------------------------------------------------

    /** href → anime detay URL'si (bölüm linkleri slug'a indirgenir). */
    private fun toAnimeUrl(href: String): String? {
        if (href.isBlank()) return null
        val u = fixUrlNull(href) ?: return null
        // Yalnız kendi domainimiz kabul edilir (footer'da eski domain linkleri
        // var). v27: `www.` ve port farkları toleranslı — uygulama mainUrl'i
        // enjekte ettiğinde (www.'li/portsuz) tüm kartlar eleniyordu.
        if (hostOf(u) != hostOf(mainUrl)) return null
        val path = u.substringAfter("://").substringAfter("/").substringBefore("?").substringBefore("#")
        val slug = EPISODE_SUFFIX.replace(path, "")
        if (!SLUG_RE.matches(slug) || BLOCKED_SLUGS.contains(slug)) return null
        return "$mainUrl/$slug"
    }

    /** v27: şema/www/port bağımsız host adı. */
    private fun hostOf(url: String): String = url
        .substringAfter("://", url)
        .substringBefore("/")
        .substringBefore(":")
        .removePrefix("www.")
        .lowercase()

    private fun cleanAlt(alt: String?): String? = alt
        ?.substringBefore(" - Anizm.TV")
        ?.substringBefore(" posteri")
        ?.trim()
        ?.takeIf { it.length >= 2 }

    private fun parseAnimeCards(doc: Document): List<AnimeSearchResponse> {
        val out = mutableListOf<AnimeSearchResponse>()
        val seen = LinkedHashSet<String>()
        for (a in doc.select("a[href]")) {
            val url = toAnimeUrl(a.attr("href")) ?: continue
            if (!seen.add(url)) continue
            // v3-catalog-card (arama/katalog) + swiper-slide (ana sayfa) + genel yedek
            val title = a.selectFirst("h3, h6.slideAnimeTitle, h4, h5, .title")?.text()?.trim()?.ifBlank { null }
                ?: cleanAlt(a.selectFirst("img")?.attr("alt"))
                ?: continue
            val poster = a.selectFirst("img")?.let { img ->
                fixUrlNull(img.attr("src")) ?: fixUrlNull(img.attr("data-src"))
            }
            val score = a.selectFirst("span.v3-catalog-score")?.text()?.trim()
                ?.replace(Regex("[^0-9.]"), "")?.takeIf { it.isNotBlank() }
            out += newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                if (score != null) this.score = Score.from10(score)
            }
        }
        return out
    }

    // ------------------------------------------------------------------
    // Arama + katalog
    // ------------------------------------------------------------------

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // v27: arama ASLA istisna fırlatmaz — kontrolsüz istisna eklenti
        // çubuğundan yayılır ve uygulamayı çökertir (kullanıcı raporu).
        return try {
            searchInternal(query, page)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "search('$query') hatası: ${e::class.simpleName}: ${e.message}")
            newSearchResponseList(emptyList(), false)
        }
    }

    private suspend fun searchInternal(query: String, page: Int): SearchResponseList {
        val enc = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/arama?q=$enc" + if (page > 1) "&sayfa=$page" else ""
        val html = fetchDoc(url, "$mainUrl/")
        val results = parseAnimeCards(Jsoup.parse(html))
        Log.d(LOG_TAG, "search('$query') → ${results.size} sonuç")
        return newSearchResponseList(results, results.isNotEmpty())
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val url = request.data + page
            val html = fetchDoc(url, "$mainUrl/")
            val results = parseAnimeCards(Jsoup.parse(html))
            Log.d(LOG_TAG, "mainPage('${request.name}') → ${results.size} sonuç")
            newHomePageResponse(request.name, results, results.isNotEmpty())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e(LOG_TAG, "mainPage('${request.name}') hatası: ${e::class.simpleName}: ${e.message}")
            // v27: hata diyaloğunda koca HTML/stacktrace yerine kısa mesaj.
            throw ErrorLoadingException(
                "Anizm ana sayfa alınamadı (${e::class.simpleName})".take(400)
            )
        }
    }

    // ------------------------------------------------------------------
    // Detay (load)
    // ------------------------------------------------------------------

    override suspend fun load(url: String): LoadResponse? {
        val html = fetchDoc(url, "$mainUrl/")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.anizm_pageTitle a")?.text()?.trim()
            ?: doc.selectFirst("title")?.text()
                ?.replace(Regex(" (izle|İzle)\\s*\\|\\s*PuffyTR", RegexOption.IGNORE_CASE), "")
                ?.trim()
            ?: "Anizm"
        val poster = fixUrlNull(doc.selectFirst("meta[itemprop=image]")?.attr("content"))
            ?: fixUrlNull(doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = doc.selectFirst("meta[itemprop=description]")?.attr("content")
            ?: doc.selectFirst("meta[property=og:description]")?.attr("content")
        val tags = doc.select("a.ui.label[href*='/kategoriler/']").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct()

        // Bölüm listesi: /{slug}-{n}-bolum-izle
        val episodes = mutableListOf<Episode>()
        val seenEp = HashSet<Int>()
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            val m = EPISODE_SUFFIX.find(href.substringAfter("://").substringAfter("/").substringBefore("?"))
                ?: continue
            val n = m.groupValues[1].toIntOrNull() ?: continue
            if (!seenEp.add(n)) continue
            val epUrl = fixUrlNull(href) ?: continue
            episodes += newEpisode(epUrl) {
                this.name = "${n}. Bölüm"
                this.episode = n
            }
        }
        // Film/OVA: -bolum-izle linki yoksa izleme linki dene.
        if (episodes.isEmpty()) {
            for (a in doc.select("a[href*='/bolum/'], a[href*='-izle']")) {
                val href = fixUrlNull(a.attr("href")) ?: continue
                if (href.contains("puffytr.com/bolum/") || href.endsWith("-izle")) {
                    episodes += newEpisode(href) {
                        this.name = "Bölüm 1"
                        this.episode = 1
                    }
                    break
                }
            }
        }
        episodes.sortBy { it.episode ?: 0 }

        val firstUrl = episodes.firstOrNull()?.data ?: url
        return if (episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, firstUrl) {
                this.posterUrl = poster
                if (description != null) this.plot = description
                this.tags = tags
            }
        } else {
            newAnimeLoadResponse(title, url, TvType.Anime, false) {
                this.posterUrl = poster
                if (description != null) this.plot = description
                this.tags = tags
            }
        }
    }

    // ------------------------------------------------------------------
    // Kaynaklar (loadLinks)
    // ------------------------------------------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // v27: loadLinks istisna firlatirsa oynatici yuku duseye kadar
        // yayilabiliyor — buradan CIKANYI kisa mesajla bos listeye ceviriyoruz.
        val html = try {
            fetchDoc(data, "$mainUrl/")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "loadLinks sayfa hatası: ${e::class.simpleName}: ${e.message}")
            return false
        }
        val doc = Jsoup.parse(html)
        val csrf = doc.selectFirst("meta[name=csrf-token]")?.attr("content")?.ifBlank { null }

        // Çevirmen (fansub) butonları — kanıt: v3-episode.js v1.3.5 + bölüm sayfası capture.
        val translators = doc.select("button[data-v3-translator], [data-v3-translator]")
        if (translators.isEmpty()) {
            Log.e(LOG_TAG, "loadLinks: çevirmen butonu yok — sayfa ${html.length / 1000}k")
            // v27: "kaynak yok" hata degil bos listedir; istisna firlatmak
            // uygulamada kirmizi cokme kutusu olarak gorunuyordu.
            return false
        }
        Log.d(LOG_TAG, "loadLinks: ${translators.size} çevirmen bulundu")

        return coroutineScope {
            translators.mapIndexed { index, btn ->
                async {
                    val fansub = btn.attr("title").ifBlank { btn.text() }.ifBlank { "Çevirmen ${index + 1}" }
                    val sourcesUrl = fixUrlNull(btn.attr("data-sources")) ?: return@async
                    safeApiCall {
                        loadTranslatorSources(fansub, sourcesUrl, data, csrf, subtitleCallback, callback)
                    }
                }
            }.awaitAll()
            true
        }
    }

    /** Tek çevirmenin tüm video kaynaklarını çözer. */
    private suspend fun loadTranslatorSources(
        fansub: String,
        sourcesUrl: String,
        episodeUrl: String,
        csrf: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val json = fetchJson(sourcesUrl, episodeUrl, csrf)
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "$sourcesUrl JSON parse hatası: ${json.take(200)}")
            throw ErrorLoadingException("Kaynak listesi çözülemedi (JSON değil)")
        }
        if (!root.optBoolean("success", false)) {
            Log.e(LOG_TAG, "$sourcesUrl success=false: ${root.optString("message")}")
            throw ErrorLoadingException(
                "Kaynak listesi başarısız: ${root.optString("message").ifBlank { "site hayır dedi" }}"
            )
        }
        val videos = root.optJSONArray("videos")
        if (videos == null || videos.length() == 0) {
            Log.w(LOG_TAG, "$sourcesUrl videos boş")
            return
        }
        Log.d(LOG_TAG, "$fansub → ${videos.length()} kaynak")

        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            val videoId = v.optInt("id", 0)
            val videoName = v.optString("name", "Kaynak ${i + 1}")
            if (videoId <= 0) continue
            // v3-episode.js: sources.replace(/\/cevirmen\/\d+$/, '/video/' + videoId)
            val videoUrl = sourcesUrl.replace(Regex("/cevirmen/[0-9]+$"), "/video/$videoId")
            try {
                val vjson = fetchJson(videoUrl, episodeUrl, csrf)
                val vroot = JSONObject(vjson)
                if (!vroot.optBoolean("success", false)) {
                    Log.w(LOG_TAG, "$videoUrl success=false: ${vroot.optString("message")}")
                    continue
                }
                val playerUrl = fixUrlNull(vroot.optString("url"))
                val sourceName = vroot.optString("source").ifBlank { videoName }
                if (playerUrl.isNullOrBlank()) {
                    Log.w(LOG_TAG, "$videoUrl url boş — atlanıyor")
                    continue
                }
                val linkName = if (sourceName == videoName) "$fansub | $videoName"
                else "$fansub | $sourceName (${videoName})"
                resolvePlayer(playerUrl, linkName, episodeUrl, subtitleCallback, callback)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "$videoUrl çözümü hatası: ${e.message}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Player çözümü
    // ------------------------------------------------------------------

    /**
     * /player/{id} → 302 hedefini çözer.
     * - anizmplayer.com/video/{token} → FirePlayer protokolu (m3u8/AES).
     * - diğerleri → harici provider (loadExtractor) veya düz dosya.
     */
    private suspend fun resolvePlayer(
        playerUrl: String,
        linkName: String,
        episodeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Redirect'i KENDİMİZ izliyoruz (Location + sonraki hop'un refereri için).
        var currentUrl = playerUrl
        var currentReferer = episodeUrl
        for (hop in 1..5) {
            val resp = try {
                app.get(
                    currentUrl,
                    headers = docHeaders(currentReferer),
                    allowRedirects = false,
                    timeout = 12
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "player GET hatası: $currentUrl → ${e.message}")
                throw IOException("Oynatıcı sayfası alınamadı: $currentUrl (${e::class.simpleName})")
            }
            val code = resp.code
            if (code in 300..399) {
                val location = resp.headers["location"] ?: resp.headers["Location"]
                Log.d(LOG_TAG, "player hop $hop: $currentUrl → ${code} → $location")
                if (location.isNullOrBlank() || !location.startsWith("http")) {
                    Log.e(LOG_TAG, "302 Location bozuk: $location")
                    throw IOException("Oynatıcı 302 hedefi okunamadı: $currentUrl")
                }
                currentReferer = currentUrl
                currentUrl = location
                continue
            }
            val text = resp.text
            val ct = (resp.headers["content-type"] ?: resp.headers["Content-Type"] ?: "").orEmpty()
            Log.d(LOG_TAG, "player nihai: $currentUrl (HTTP $code, ${ct}, ${text?.length ?: 0} B)")
            if (text != null && handlePlayerBody(currentUrl, currentReferer, ct, text, linkName, subtitleCallback, callback)) {
                return
            }
            throw IOException("Oynatıcı hedefi video vermedi: $currentUrl")
        }
        throw IOException("Oynatıcı redirect zinciri 5 adımda bitmedi: $playerUrl")
    }

    /** Player hedefi 200 döndürdüğünde içeriğe göre çözümler. */
    private suspend fun handlePlayerBody(
        url: String,
        referer: String,
        contentType: String,
        text: String,
        linkName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1) Doğrudan akış.
        if (contentType.contains("mpegurl", true) || url.contains(".m3u8")) {
            emitLink(url, linkName + " [HLS]", referer, callback)
            return true
        }
        if (contentType.startsWith("video/", true) ||
            Regex("\\.(mp4|webm|mkv|mov)$", RegexOption.IGNORE_CASE).find(url) != null
        ) {
            emitLink(url, linkName, referer, callback)
            return true
        }

        val lower = text.lowercase()

        // 2) Kendi player'ımız: FirePlayer (anizmplayer.com/video/{token}).
        if (url.contains(PLAYER_SERVER, true) || lower.contains("fireplayer")) {
            val settings = decodeFirePlayerSettings(text)
            val tokenFromUrl = Regex("/video/([a-f0-9]{16,64})").find(url)?.groupValues?.getOrNull(1)
            val token = tokenFromUrl ?: settings?.token
            if (token != null) {
                if (tryFirePlayer(token, referer, settings?.title, settings?.tracks ?: emptyList(), linkName, subtitleCallback, callback)) {
                    return true
                }
            }
        }

        // 3) <iframe> (provider player sayfası).
        val iframeSrc = Jsoup.parse(text).select("iframe[src]").mapNotNull { fixUrlNull(it.attr("src")) }
            .firstOrNull()
        if (iframeSrc != null) {
            val ok = try {
                loadExtractor(iframeSrc, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                false
            }
            if (ok) return true
            // yedek: iframe sayfasını düz çek, içindeki videoyu ara.
            try {
                val ih = fetchDoc(iframeSrc, referer)
                // v27: referer = iframe'in KENDİ adresi olmalı (url değil).
                if (handlePlayerBody(iframeSrc, iframeSrc, "", ih, linkName, subtitleCallback, callback)) return true
            } catch (e: Exception) {
                Log.e(LOG_TAG, "iframe çözülemiyor: $iframeSrc → ${e.message}")
            }
            return false
        }

        // 4) Sayfa içinde düz m3u8/mp4 (player setup JSON blob'u, video.js, vs).
        val m3u8 = Regex("https?://[^\\s\"'<>]+?\\.m3u8[^\\s\"'<>]*").find(text)?.value
            ?: Regex("https?:\\\\//[^\\\\\"'<>]+?\\.m3u8").find(text)?.value?.replace("\\/", "/")
        if (m3u8 != null) {
            emitLink(m3u8, linkName + " [HLS]", referer, callback)
            return true
        }
        val mp4 = Regex("https?://[^\\s\"'<>]+?\\.(mp4|webm|mkv)[^\\s\"'<>]*", RegexOption.IGNORE_CASE)
            .find(text)?.value?.replace("\\/", "/")
        if (mp4 != null) {
            emitLink(mp4, linkName, referer, callback)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // FirePlayer protokolu (anizmplayer.com)
    // ------------------------------------------------------------------

    private data class FireSettings(
        val token: String?,
        val title: String?,
        val ck: String?,
        val tracks: List<Pair<String, String>> // (file, label) — kind=captions
    )

    private data class FireVideo(
        val url: String,
        val label: String?,
        val isHls: Boolean
    )

    /**
     * /video/{token} sayfasındaki obfike eval'i decode edip
     * FirePlayer("{token}", {videoSettings}) çağrısını çıkarır.
     * Decode algoritması: javascript-obfuscator string-array substitution —
     * node'da gerçek payload ile BİREBİR doğrulandı (2026-10-25).
     */
    private fun decodeFirePlayerSettings(html: String): FireSettings? {
        val decoded = try {
            decodeObfuscatedPage(html)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "player sayfası decode hatası: ${e.message}")
            null
        } ?: return null

        // FirePlayer("TOKEN",{json},autoStart,source)
        val callIdx = decoded.indexOf("FirePlayer(")
        if (callIdx < 0) return null
        val rest = decoded.substring(callIdx + "FirePlayer(".length)
        val open = rest.indexOf('"')
        if (open < 0) return null
        val tokenEnd = rest.indexOf('"', open + 1)
        if (tokenEnd < 0) return null
        val token = rest.substring(open + 1, tokenEnd)

        // JSON objesi: tırnaklı string'leri atlayarak süslü parantez dengesiyle ayır.
        val jsonStart = rest.indexOf('{', tokenEnd)
        if (jsonStart < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        var jsonEnd = -1
        for (i in jsonStart until rest.length) {
            val ch = rest[i]
            if (inStr) {
                when {
                    esc -> esc = false
                    ch == '\\' -> esc = true
                    ch == '"' -> inStr = false
                }
            } else when (ch) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        jsonEnd = i
                        break
                    }
                }
            }
        }
        if (jsonEnd < 0) return null
        val jsonStr = rest.substring(jsonStart, jsonEnd + 1)
        // Kaynak JSON geçerli standart JSON'dır (\\/ → /, \\/ → / normal JSON
        // unescape'ıyla çözülür). Yalnız "ck" değeri JS hex-escape taşır (\xNN).
        val jo = try {
            JSONObject(jsonStr)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "videoSettings JSON parse hatası: ${jsonStr.take(200)}")
            return FireSettings(token, null, null, emptyList())
        }
        val title = jo.optString("title").ifBlank { null }
        val ck = jo.optString("ck").ifBlank { null }?.let { unescapeJsHex(it) }
        val tracks = mutableListOf<Pair<String, String>>()
        val tracksArr = jo.optJSONArray("tracks")
        if (tracksArr != null) {
            for (i in 0 until tracksArr.length()) {
                val t = tracksArr.optJSONObject(i) ?: continue
                if (t.optString("kind") != "captions") continue
                val file = t.optString("file")
                if (file.isBlank()) continue
                val label = t.optString("label").ifBlank { t.optString("language").ifBlank { "Altyazı" } }
                tracks += file to label
            }
        }
        return FireSettings(token, title, ck, tracks)
    }

    /** JS single-quoted string başlangıcından (tırnakta) string'i çıkarır. */
    private fun extractJsString(s: String, start: Int): Pair<String, Int>? {
        // start = açılış tırnağının konumu
        if (start >= s.length || s[start] != '\'') return null
        val sb = StringBuilder()
        var i = start + 1
        while (i < s.length) {
            val ch = s[i]
            when {
                ch == '\\' && i + 1 < s.length -> {
                    val nx = s[i + 1]
                    when (nx) {
                        '\\' -> sb.append('\\')
                        '\'' -> sb.append('\'')
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        else -> sb.append(nx)
                    }
                    i += 2
                    continue
                }
                ch == '\'' -> return sb.toString() to (i + 1)
                else -> sb.append(ch)
            }
            i++
        }
        return null
    }

    /**
     * eval(function(p,a,c,k,e,d){...return p}('payload',a,c,'...'.split('|'),0,{}))
     * desenini bulup decode eder (template sabit; payload değişken).
     */
    private fun decodeObfuscatedPage(html: String): String {
        val marker = "return p}("
        val idx = html.indexOf(marker)
        if (idx < 0) throw IOException("obfike eval deseni bulunamadı")
        val argStart = idx + marker.length
        // 1) payload: ilk JS string
        val (payload, after1) = extractJsString(html, argStart) ?: throw IOException("payload bulunamadı")
        // 2) a
        var pos = after1
        while (pos < html.length && html[pos] == ',') pos++
        while (pos < html.length && html[pos] == ' ') pos++
        var aEnd = pos
        while (aEnd < html.length && html[aEnd].isDigit()) aEnd++
        val a = html.substring(pos, aEnd).toIntOrNull() ?: throw IOException("radix bulunamadı")
        pos = aEnd
        while (pos < html.length && (html[pos] == ',' || html[pos] == ' ')) pos++
        // 3) c
        var cEnd = pos
        while (cEnd < html.length && html[cEnd].isDigit()) cEnd++
        val count = html.substring(pos, cEnd).toIntOrNull() ?: throw IOException("count bulunamadı")
        pos = cEnd
        while (pos < html.length && (html[pos] == ',' || html[pos] == ' ')) pos++
        // 4) k string'i ('...' .split('|'))
        val (kStr, _) = extractJsString(html, pos) ?: throw IOException("string-array bulunamadı")
        val k: MutableList<String?> = kStr.split('|').map { it as String? }.toMutableList()
        while (k.size < count) k.add(null)
        return decodeObfuscated(payload, a, count, k)
    }

    private fun eCode(x: Int, a: Int): String {
        fun rec(v: Int): String {
            val prefix = if (v < a) "" else rec(v / a)
            val r = v % a
            return prefix + if (r > 35) ((r + 29).toChar()).toString() else r.toString(36)
        }
        return rec(x)
    }

    private fun decodeObfuscated(p: String, a: Int, count: Int, k: List<String?>): String {
        var out = p
        for (i in count - 1 downTo 0) {
            val key = eCode(i, a)
            val rep = k[i]
            if (!rep.isNullOrEmpty()) {
                out = out.replace(Regex("\\b" + Regex.escape(key) + "\\b"), rep)
            }
        }
        return out
    }

    /** "\x4e\x44\x67..." → "NDg..." (JS hex-escape açma). */
    private fun unescapeJsHex(s: String): String {
        if (!s.contains("\\x")) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 3 < s.length && s[i + 1] == 'x') {
                val code = s.substring(i + 2, i + 4).toIntOrNull(16)
                if (code != null) {
                    sb.append(code.toChar())
                    i += 4
                    continue
                }
            }
            sb.append(s[i])
            i++
        }
        return sb.toString()
    }

    /**
     * FirePlayer ana akışı:
     * POST /player/index.php?data={token}&do=getVideo  form {hash, r}
     * → hls=true:  {videoSource: m3u8}
     * → hls=false: {videoSources: [{file: AES, label, ...}], ck: şifre}
     */
    private suspend fun tryFirePlayer(
        token: String,
        referer: String,
        title: String?,
        tracks: List<Pair<String, String>>,
        linkName: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Altyazılar (kind=captions VTT) — var ise.
        for ((file, label) in tracks) {
            try {
                subtitleCallback(newSubtitleFile(label, fixUrlNull(file) ?: file))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "altyazı eklenemedi: $file → ${e.message}")
            }
        }
        val quality = Regex("(2160p|4K|1440p|1080p|720p|480p|360p)", RegexOption.IGNORE_CASE)
            .find(title ?: "")?.value

        val postUrl = "$PLAYER_SERVER/player/index.php?data=$token&do=getVideo"
        val json = try {
            app.post(
                postUrl,
                timeout = 15,
                data = mapOf("hash" to token, "r" to referer),
                headers = buildMap {
                    put("User-Agent", UA)
                    put("Accept", "application/json, text/javascript, */*; q=0.01")
                    put("Origin", PLAYER_SERVER)
                    put("Referer", "$PLAYER_SERVER/video/$token")
                    put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    put("X-Requested-With", "XMLHttpRequest")
                }
            ).text ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getVideo POST hatası: ${e.message}")
            return false
        }
        // Cevap JSON değilse düz hata mesajıdır (alert) — ama bazen düz URL de olabilir.
        if (!json.trimStart().startsWith("{")) {
            if (json.startsWith("http")) {
                emitLink(json, linkName + qualitySuffix(quality), referer, callback)
                return true
            }
            Log.e(LOG_TAG, "getVideo JSON değil: ${json.take(200)}")
            return false
        }
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "getVideo JSON parse hatası: ${json.take(200)}")
            return false
        }
        val delivered = mutableListOf<FireVideo>()

        // hls=true → videoSource (düz m3u8)
        val hls = root.optBoolean("hls", false)
        if (hls) {
            val m3u8 = root.optString("videoSource").ifBlank { null }
            if (m3u8 != null) {
                delivered += FireVideo(m3u8, quality, true)
            }
        }
        // hls=false → videoSources[] (AES şifreli file alanları)
        val sources = root.optJSONArray("videoSources")
        if (sources != null) {
            val ck = root.optString("ck").ifBlank { null }?.let { unescapeJsHex(it) }
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val file = s.optString("file")
                if (file.isBlank()) continue
                val label = s.optString("label").ifBlank { null }
                val realUrl = try {
                    if (ck != null) decryptFireFile(file, ck) else null
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "AES decrypt hatası: ${e.message}")
                    null
                }
                // Şifreli değer JSON.stringify'lenmiş bir değerdi: sonuç ya
                // düz URL, ya tırnaklı string, ya {"url":...,"label":...} objesi.
                val (u, l) = realUrl?.let { plain ->
                    try {
                        val jo = JSONObject(plain)
                        (jo.optString("url").ifBlank { jo.optString("file") }.ifBlank { jo.optString("src") }) to
                            (jo.optString("label").ifBlank { null })
                    } catch (e: Exception) {
                        val unquoted = plain.trim()
                            .removeSurrounding("\"")
                            .ifBlank { null }
                        unquoted to null
                    }
                } ?: (file to null)
                if (u != null && u.startsWith("http")) {
                    delivered += FireVideo(
                        u,
                        l ?: label ?: quality,
                        u.contains(".m3u8") || hls
                    )
                }
            }
        }

        if (delivered.isEmpty()) {
            Log.e(LOG_TAG, "getVideo video vermedi: ${json.take(300)}")
            return false
        }

        var emitted = false
        for (v in delivered) {
            val q = v.label?.takeIf { Regex("^[0-9]+p|4K", RegexOption.IGNORE_CASE).matches(it) } ?: quality
            val suffix = qualitySuffix(q)
            emitLink(v.url, linkName + suffix, referer, callback)
            emitted = true
        }
        return emitted
    }

    /**
     * v27: tek noktadan oynatıcı linki. Fork sözleşmesi: `source` = LİNKİ
     * ÜRETEN EKLENTİ (burada "Anizm"), `name` = kullanıcıya görünen etiket.
     * v1'de ikisine de kaynak etiketi verildiği için uygulama tarafında
     * sağlayıcı ayrımı bozuluyordu. Tip (M3U8/VIDEO) URL'den, kalite
     * etiketten türetilir.
     */
    private suspend fun emitLink(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val type = if (url.contains(".m3u8", ignoreCase = true)) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
        callback(
            newExtractorLink(source = name, name = label, url = url, type = type) {
                this.referer = referer
                this.quality = getQualityFromName(label)
                if (referer.isNotBlank()) {
                    this.headers = mapOf("Referer" to referer, "User-Agent" to UA)
                }
            }
        )
    }

    private fun qualitySuffix(q: String?): String =
        if (q.isNullOrBlank()) "" else " [$q]"

    // ------------------------------------------------------------------
    // AES (cryptojs-aes-php formatı 2.1.1)
    // ------------------------------------------------------------------

    /**
     * {"ct":"<base64>","iv":"<hex>","s":"<hex salt>"} + şifre → plaintext.
     * Anahtar: EVP_BytesToKey(MD5, 1 iterasyon, 16B) = MD5(SALT + şifre) → AES-128
     * (OpenSSL/CryptoJS sırası: salt ÖNCE — node'da cryptojs-aes-php formatıyla
     * roundtrip doğrulandı, 2026-10-25).
     * IV: JSON'daki hex iv (yoksa MD5(key + salt + şifre)).
     * Sonuç UTF-8; FirePlayer bunu tekrar JSON.parse eder (object/string).
     */
    private fun decryptFireFile(encrypted: String, password: String): String {
        val jo = JSONObject(encrypted)
        val ct = AndroidBase64.decode(jo.getString("ct"), AndroidBase64.DEFAULT)
        val ivHex = jo.optString("iv", "")
        val saltHex = jo.optString("s", "")
        val salt = if (saltHex.isNotEmpty()) saltHex.hexToBytes() else ByteArray(0)
        val pwd = password.toByteArray(Charsets.UTF_8)
        val key: ByteArray
        val iv: ByteArray
        if (salt.isNotEmpty()) {
            key = md5(salt + pwd)                        // 16B → AES-128 (salt ÖNCE)
            iv = if (ivHex.isNotEmpty()) ivHex.hexToBytes() else md5(key + salt + pwd)
        } else {
            val raw = pwd
            key = when {
                raw.size >= 32 -> raw.copyOf(32)        // AES-256
                raw.size >= 24 -> raw.copyOf(24)        // AES-192
                else -> {
                    val k = ByteArray(16)
                    raw.copyInto(k)
                    k
                }
            }
            iv = if (ivHex.isNotEmpty()) ivHex.hexToBytes() else ByteArray(16)
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val plain = cipher.doFinal(ct)
        return String(plain, Charsets.UTF_8)
    }

    private fun md5(b: ByteArray): ByteArray =
        MessageDigest.getInstance("MD5").digest(b)

    private fun String.hexToBytes(): ByteArray {
        val clean = this.filter { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        val out = ByteArray(clean.length / 2)
        for (i in out.indices) {
            out[i] = clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

}
