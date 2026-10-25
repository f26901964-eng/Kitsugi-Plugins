package com.kraptor

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.JsonAsString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.io.IOException
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
 *
 * v19:
 *  - Cihaz kanıtı (v18): arama 0 sonuç + HİÇ diyalog açılmadı → site
 *    oturumsuz isteklere bot sayfası değil BOŞ 200 sayfa dönüyor.
 *    DEX davranışı geri getirildi: arama/ana sayfa/load/loadLinks
 *    isteklerinden ÖNCE oturum garantisi (sessionBootstrap; kayıtlı
 *    oturum varsa diyalog açılmaz, yoksa WebView ~5 sn'de kapanır).
 *
 * v20:
 *  - Cihaz kanıtı (v19): arama YİNE 0 sonuç + diyalog YİNE yok → v19'un
 *    hatası: kayıtlı ESKİ oturum çerezi (v17'den) doğrulanmadan kullanıldı;
 *    site eski/geçersiz oturuma sessizce BOŞ sayfa döndürüyor.
 *  - Düzeltme (3 katman, her koşulda içerik garantisi):
 *    1) ensureValidSession(): epoch başında ANA SAYFA PROB'U (GET).
 *       Kart linki var → oturum GEÇERLİ, diyalog yok. Boş/bot →
 *       eski çerezler TEMİZLENİR + ana sayfada taze WebView diyaloğu
 *       (challenge varsa çözülür; ana sayfanın içeriği garantili).
 *    2) GET boş içerik döndürürse (site document-dışı isteğe farklı
 *       dönebilir) → aynı URL WebView'da açılır, sayfanın TAM DOM'u
 *       (outerHTML) oradan alınır ve O çözülür.
 *    3) BotKontrol diyaloğu artık çerez seti + sayfa DOM HTML'i döndürür
 *       (WebFetchResult); botKontrolFetch(wipeFirst) taze oturum açar.
 *  - HTTP istekleri Firefox document/XHR imzasıyla (sec-fetch-* başlıkları).
 *
 * v21 (cihaz kanıtı: arama + bölüm SAYFASI ÇALIŞIYOR, KAYNAK LİSTESİ BOŞ):
 *  - sourcePlayer cevabındaki oynatıcı ÜÇ formatta çözümlenir:
 *    <iframe> (kayıtlı extractor'lar), <video>/<source> düz URL,
 *    HTML/JS içindeki mp4/m3u8 adresi (site video.js kullanıyor →
 *    iframe'siz doğrudan oynatıcı mümkün).
 *  - iframe için kayıtlı extractor yoksa iframe sayfası DOĞRUDAN çekilir
 *    ve içindeki düz video adresi aranır (host agnostik yedek).
 *  - fansub/source seçicileri toleranslı (yapı değişse bile data-fid /
 *    data-id'li öğe bulunur — site.js 22.09.2026 davranışı).
 *  - Hata artık EKRANDA görünür: her basamağın nedeni kaynak panelinde
 *    ErrorLoadingException mesajı olarak gösterilir (KTS-CSP-9103 yerine).
 *  - loadLinks bölüm sayfası da içerik garantili (webViewFallback=true).
 *  - (v21.1: derleyici tetikleyici dokunuş — kod değişikliği yok.)
 *
 * v22 (KANIT MODU — cihazdaki gerçek HTML'in ekranda görünmesi):
 *  - Araştırma (20.07.2026 + 22.09.2026 arşivleri, site.js TAMI birebir aynı;
 *    videoSource.css: #videoPlayer > iframe; altyazı/kalite API'si YOK):
 *    kaynak zinciri = bölüm sayfası (data-fid öğeleri) →
 *    POST /api/fansubSources {EpisodeId,FansubId} → HTML #sourceList
 *    (li.sourceBtn: data-id/data-eid/p.title = KALİTE/FANSUB adı) →
 *    POST /api/sourcePlayer/{id} → {source: HTML} → #videoPlayer > iframe.
 *  - KANIT MODU: kaynak boşsa hata mesajı artık AŞAMALI GERÇEK HTML içerir:
 *    KANIT[1] sayfa + fansub bölgesi parçası, KANIT[2] fansubSources
 *    cevabı parçası, KANIT[3] sourcePlayer cevabı parçası, KANIT[4]
 *    iframe/direct/embed adresleri → cihaz ekran görüntüsüyle sorun
 *    SONUNA KADAR teşhis edilir.
 *  - Kalite: kaynak adı 1080p/720p/4K/HD içeriyorsa ExtractorLink.quality.
 *  - Altyazı: source HTML + iframe sayfasındaki <track> ve .vtt/.srt
 *    adresleri subtitleCallback ile eklenir (varsa).
 *
 * v23 (TÜM KAYNAK ZİNCİRİ ONARIMI — "akış gelmedi" kök nedeni):
 *  - KÖK NEDEN (kanıt: site.js axios kodu + NiceHttp 0.4.13 kaynak kodu):
 *    fansubSources POST'u FORM gövdeyle + sahte Content-Type: application/json
 *    başlığıyla gidiyordu; site GERÇEK JSON gövde bekliyor
 *    ({"EpisodeId":123,"FansubId":456}) → sunucu gövdeyi okuyamadı →
 *    kaynak listesi v17'den beri hep boştu. Artık JsonAsString ile BİREBİR
 *    axios JSON gövdesi; FORM gövde + JSON-sarmal cevap yedekleri de denenir.
 *  - sourcePlayer: cevap JSON değil düz HTML ise o HTML doğrudan oynatıcı
 *    HTML'i sayılır.
 *  - iframe'ler: base URL ile çözülür (göreli src artık bozulmaz); TÜM
 *    iframe'ler sırayla denenir (reklam iframe'i ön planda olsa bile).
 *  - Sayfada sunucu tarafı kaynak butonu varsa API'ye gerek kalmadan çözülür.
 *  - SON KATMAN: tüm API yolları boşsa bölüm sayfası WebView'de AÇILIR,
 *    render edilen DOM (tarayıcıda görünenin birebiri) çözülür; oturum
 *    taze çerezle yenilenip API yeniden denenir.
 *  - Oynatma başlıklarına User-Agent eklendi (CDN koruması için).
 *
 * v24 (EKSİKSİZ KAYNAK — site listesindeki HER kaynak link olsun):
 *  - Cihaz kanıtı (V23): JSON gövde düzeltmesi kaynakları GETİRDİ, ama
 *    site 10 kaynak sunarken sadece iframe/MP4 tabanlı 3'ü çözüldü.
 *    Dosya-host kaynakları (Gdrive, Mega, SendVid, Voe, Ok.RU, Byandex,
 *    Sibnet, MailRu…) HTML içinde iframe DEĞİL düz dosya linki taşır →
 *    önceki sürümler onları "iframe/video yok" diye atlıyordu.
 *  - v24: oynatıcı HTML'indeki TÜM adresler taranır; video host'una
 *    uyan ilk adres (drive.google/mega.nz/ok.ru/sendvid/voe.sx/sibnet/
 *    byandex/vudeo/upstream…) kaynağın linki olarak eklenir.
 *  - Host sayfaları (sendvid/voe gibi) iç stream adresi taşır: sayfa
 *    çekilip içinde mp4/m3u8 aranır; JSON anahtarları da çözülür
 *    ("file":"...mp4" / "src":"...m3u8"). Gdrive /file/d/{id} →
 *    doğrudan indirme adresine çevrilir.
 *  - Çözülemediyse bile HAM host adresi link olarak eklenir — kaynak
 *    listesinde TEK bir kaynak bile boş kalmaz.
 *
 * v25 (SERTLEŞTİRME + SİTENİN KENDİ 2. OYNATICI YOLU):
 *  - site.js satır-satır yeniden incelendi (iki dönem BİREBİR aynı):
 *    video ile ilgili TÜM uçlar: fansubSources (JSON gövde),
 *    sourcePlayer/{id} (boş gövde → {source, contributerNotes}),
 *    preVideoAd/{id} (boş gövde → DÜZ oynatıcı HTML'i; site günün
 *    ilk tıklamasında bunu kullanır).
 *  - YENİ YOL: sourcePlayer boş/bozuk dönerse site'nin kendi
 *    ilk-tıklama yolu olan POST /api/preVideoAd/{id} denenir
 *    (bağımsız 2. oynatıcı kaynağı).
 *  - TÜM ağ isteklerine zaman aşımı eklendi (POST 15 sn, sayfa 10 sn) —
 *    takılan host/iframe artık zinciri BOĞMAZ (v24'te "bir anda hata"
 *    veren en olası mekanizma: sınırsız host sayfası çekimleri).
 *  - CancellationException YUTULMAZ — uygulama loadLinks'i iptal
 *    ettiğinde eklenti anında durur (arka planda işe devam edip son
 *    anda hata vermesi engellenir).
 *  - Host sayfası çekimi YALNINCA sayfasında stream taşıyan hostlar
 *    için (sendvid/voe/streamtape); Gdrive adres dönüşümü çekimsiz,
 *    diğer hostlar çekimsiz ham link.
 *  - Beklenmedik hata mesajına istisna türü eklenir (teşhis).
 *
 * v26 (DOMAIN ROTASYONU — "tak diye hata" KÖK NEDENİ):
 *  - KANIT (25.10.2026): site artık www.tranimeizle.io adresinde yayın
 *    yapıyor (izleani.me canlı takipçi — 3 saat önceki bölümlerin linkleri
 *    .io; Semrush: .io 14.47M ziyaret/ay; cs-kraptor'un her gün güncellenen
 *    domain dosyası .io). Eklenti tek domain'e (.live) KİLİDLİYDİ — site
 *    domain değiştirdiğinde tüm istekler ölü domain'e gitti → veri hiç
 *    gelmedi, "tak diye hata". (.live son çalışması: V23 cihaz kanıtı.)
 *  - Düzeltme: domain rotasyonu. Aday listesi [.io, .live, .top]; ana
 *    sayfa PROB'u ile canlı domain otomatik seçilir (8 sn zaman aşımı),
 *    sonuç prefs'e kalıcı yazılır. Ağ hatasında domain değiştirilip
 *    istek BİR kez tekrarlanır. mainPage/arama URL'leri mevcut domain'e
 *    haritalanır. Varsayılan domain .io.
 */
class TrAnimeIzle(
    private val context: Context,
    domainPrefs: SharedPreferences
) : MainAPI() {

    override var mainUrl: String = Companion.getDomain(domainPrefs, "TrAnimeIzle")
    override var name = "TrAnimeIzle"

    // v26: domain rotasyonu durumu.
    // - domPrefs: kalıcı tercih (son doğrulanan domain).
    // - mainPageBase: mainPage haritasının kurulumda hangi domainle
    //   üretildiği (run-time domain değişince URL'ler buna göre haritalanır).
    // - lastDomainCheckAt: canlı domain kontrolü sıklığı (TTL).
    private val domPrefs: SharedPreferences = domainPrefs
    private var mainPageBase: String = mainUrl
    private var lastDomainCheckAt = 0L
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
        // v26: istekten önce canlı domain garantisi (site domain değiştirdiyse
        // ana sayfa anahtar kelimesi: otomatik geçiş).
        ensureDomainAlive()
        val enc = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) mainUrl + "/arama/" + enc
        else mainUrl + "/arama/" + enc + "?page=" + page
        val html = fetchHtml(url, mainUrl + "/", ensureSession = true, webViewFallback = true)
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
            // v26: canlı domain garantisi + mainPage URL'ini mevcut domain'e haritala.
            ensureDomainAlive()
            val url = remapDomain(request.data) + page
            val html = fetchHtml(url, mainUrl + "/", ensureSession = true, webViewFallback = true)
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
        referer: String = mainUrl + "/",
        isXhr: Boolean = false
    ): Map<String, String> = buildMap {
        put("User-Agent", BotKontrolKt.UA_FIREFOX)
        put(
            "Accept",
            if (isXhr) "application/json, text/plain, */*"
            else "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
        put("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
        put("Referer", referer)
        if (isXhr) {
            // v20: Firefox'un XHR imzası (site kendi axios isteklerini böyle yapar).
            put("sec-fetch-dest", "empty")
            put("sec-fetch-mode", "cors")
            put("sec-fetch-site", "same-origin")
            put("X-Requested-With", "XMLHttpRequest")
        } else {
            // v20: Firefox'un document navigasyon imzası — WAF'ların
            // "gerçek tarayıcı mı?" testinde baktığı başlıklar.
            put("sec-fetch-dest", "document")
            put("sec-fetch-mode", "navigate")
            put("sec-fetch-site", "same-origin")
            put("sec-fetch-user", "?1")
            put("priority", "u=0,i")
            put("Upgrade-Insecure-Requests", "1")
        }
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

    // ------------------------------------------------------------------
    // v20: oturum doğrulama (epoch) + WebView DOM çekme katmanı
    // ------------------------------------------------------------------

    /**
     * Sayfadaki içerik kart linki sayısı (…-bolum-izle / …-anime-izle).
     * Ana sayfa/liste/arama sayfasında oturum GEÇERLİYSE bu sayfa > 0'dır
     * (ana sayfa arşivi 22.09.2026: kartlar garantili).
     */
    private fun izleCount(html: String?): Int {
        if (html.isNullOrBlank()) return 0
        return try {
            Jsoup.parse(html).select("a[href*='bolum-izle'], a[href*='anime-izle']").size
        } catch (_: Exception) {
            0
        }
    }

    // ------------------------------------------------------------------
    // v26: DOMAIN ROTASYONU (site domain'i sık değişiyor — BTK engelleri)
    // ------------------------------------------------------------------

    /**
     * v26: bu domain YAŞIYOR mu? = ana sayfa GET 200 + bot değil + içerik
     * kart linki taşıyor. 8 sn zaman aşımı — ölü domain zinciri boğmaz.
     */
    private suspend fun probeDomainAlive(base: String): Boolean {
        return try {
            val resp = app.get(base, headers = browserHeaders(cachedSession(), base + "/"), timeout = 8)
            val ok = resp.isSuccessful && !isBotBody(resp.text) && izleCount(resp.text) > 0
            Log.d(LOG_TAG, "domain probe: $base → ${if (ok) "ALIVE" else "DEAD"} (status=${resp.statusCode})")
            ok
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "domain probe istisnası: $base → ${e.message}")
            false
        }
    }

    /**
     * v26: mevcut domain ölü mü? → adayları sırayla dene; canlı olanı
     * benimsa (mainUrl + prefs'e kalıcı yaz + oturumu GEÇERSİZ kıl —
     * çerezler domain bazlıdır). Mevcut (değişmiş olabilecek) domaini döndürür.
     */
    private suspend fun ensureDomainAlive(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && now - lastDomainCheckAt < DOMAIN_CHECK_TTL_MS) return mainUrl
        lastDomainCheckAt = now
        if (probeDomainAlive(mainUrl)) return mainUrl
        Log.w(LOG_TAG, "MEVCUT DOMAIN ÖLÜ ($mainUrl) → adaylar deneniyor...")
        for (c in DOMAIN_CANDIDATES) {
            if (c == mainUrl) continue
            if (probeDomainAlive(c)) {
                Log.w(LOG_TAG, "DOMAIN DEĞİŞTİRİLDİ: $mainUrl → $c")
                mainUrl = c
                try {
                    domPrefs.edit().putString(ACTIVE_DOMAIN_KEY, c).apply()
                } catch (_: Exception) {
                }
                // yeni domain → yeni oturum (eski çerezler eski domainin)
                sessionValidated = false
                lastCookie = ""
                return c
            }
        }
        Log.e(LOG_TAG, "aday domainlerden canlısı yok — $mainUrl ile devam (1 dk sonra tekrar dene)")
        lastDomainCheckAt = now - DOMAIN_CHECK_TTL_MS + 60_000
        return mainUrl
    }

    /**
     * v26: kurulumda eski domainle üretilmiş bir URL'in önünü mevcut
     * domainle değiştirir (mainPage listesi, bookmark'dan gelen URL).
     */
    private fun remapDomain(url: String): String {
        if (url.startsWith(mainPageBase)) return mainUrl + url.substring(mainPageBase.length)
        return url
    }

    private var sessionValidated = false

    /**
     * v20 oturum doğrulaması (epoch başında BİR kez):
     *  1) HIZLI PROB: ana sayfa GET (kayıtlı çerezle). Kart linki varsa
     *     oturum GEÇERLİ → diyalog yok, anında dön.
     *     (v19'un hatası: kayıtlı çerez GEÇERLİLİĞİNE bakılmadan kullanıldı;
     *     site eski oturuma sessizce boş sayfa döndürüyordu → 0 sonuç +
     *     diyalog hiç açılmadı.)
     *  2) Prob boş/bot → ESKİ ÇEREZLER TEMİZLENİR + ana sayfada taze WebView
     *     diyaloğu (challenge varsa otomatik/elle çözülür). Ana sayfanın
     *     içeriği garanti olduğundan, içerik gelmesi = oturumun tarayıcı
     *     kanıtıyla geçerli olduğu demektir.
     *  3) Diyaloğta da içerik yoksa → elimizdeki en iyi setle devam (log).
     */
    private suspend fun ensureValidSession(): String {
        if (sessionValidated) return lastCookie
        sessionValidated = true
        var cookie = lastCookie.ifBlank { cachedSession() }
        var probe = try {
            // v26: 10 sn zaman aşımı — ölü domain probu zinciri boğmasın.
            app.get(mainUrl, headers = browserHeaders(cookie, mainUrl + "/"), timeout = 10)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Ana sayfa prob hatası: ${e.message}")
            null
        }
        if (probe == null) {
            // Ağ gecikmesi olmasın diye: prob hatasında bir kez daha dene.
            delay(1_500)
            probe = try {
                app.get(mainUrl, headers = browserHeaders(cookie, mainUrl + "/"), timeout = 10)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Ana sayfa prob 2. deneme hatası: ${e.message}")
                null
            }
        }
        val probeText = probe?.text ?: ""
        val probeLinks = izleCount(probeText)
        if (probe != null && probe.isSuccessful && probeLinks > 0) {
            Log.d(LOG_TAG, "Oturum GEÇERLİ (ana sayfa prob: $probeLinks kart linki) — diyalog yok")
            lastCookie = cookie
            return cookie
        }
        Log.w(
            LOG_TAG,
            "Ana sayfa prob GEÇERSİZ (isSuccess=${probe?.isSuccessful ?: "?"}, $probeLinks kart linki, " +
                "botCevabı=${isBotBody(probeText)}) → taze oturum diyaloğu (eski çerezler temizleniyor)"
        )
        val res = try {
            BotKontrolKt.botKontrolFetch(context, mainUrl, wipeFirst = true)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Oturum diyaloğu hatası: ${e.message}")
            BotKontrolKt.WebFetchResult("", "")
        }
        val resLinks = izleCount(res.html)
        if (resLinks > 0) {
            Log.d(LOG_TAG, "Taze oturum alındı ($resLinks kart linki, set ${res.jar.length} karakter)")
            lastCookie = res.jar
            return res.jar
        }
        Log.e(
            LOG_TAG,
            "Diyalog sayfasında da içerik yok (html=${res.html.length} karakter, set=${res.jar.length}) — mevcut setle devam"
        )
        lastCookie = res.jar.ifBlank { cookie }
        return lastCookie
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
    private suspend fun fetchHtml(
        rawUrl: String,
        rawReferer: String,
        ensureSession: Boolean = false,
        webViewFallback: Boolean = false
    ): String {
        var url = rawUrl
        var referer = rawReferer
        // v20: ensureSession=true → epoch oturum doğrulaması (geçerlilik kanıtıyla).
        var cookie = lastCookie.ifBlank {
            if (ensureSession) ensureValidSession() else cachedSession()
        }
        var resp = try {
            app.get(url, headers = browserHeaders(cookie, referer))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // v26: ağ seviyesi hata (bağlantı/timeout) — domain değişmiş
            // olabilir: aday domainler denenir, canlısına geçilip BİR kez
            // tekrarlanmazsa hata yukarı fırlatılır.
            Log.w(LOG_TAG, "GET ağ hatası: $url → ${e.message}")
            val before = mainUrl
            ensureDomainAlive(force = true)
            if (mainUrl != before) {
                Log.w(LOG_TAG, "ağ hatası → domain değişti ($before → $mainUrl), tekrar deniyor")
                url = remapDomain(url)
                referer = remapDomain(referer)
                cookie = cachedSession()
                try {
                    app.get(url, headers = browserHeaders(cookie, referer))
                } catch (e2: CancellationException) {
                    throw e2
                } catch (e2: Exception) {
                    Log.e(LOG_TAG, "GET tekrar denemesi de başarısız: ${e2.message}")
                    throw e
                }
            } else {
                throw e
            }
        }
        if (!resp.isSuccessful || isBotBody(resp.text)) {
            // v26: 403/bot sayfası da ölü domain belirtisi olabilir → önce
            // domain rotasyonu, sonra oturum yenileme.
            val before = mainUrl
            ensureDomainAlive(force = true)
            if (mainUrl != before) {
                Log.w(LOG_TAG, "bot/403 → domain değişti ($before → $mainUrl), tekrar deniyor: $url")
                url = remapDomain(url)
                referer = remapDomain(referer)
                cookie = cachedSession()
                resp = try {
                    app.get(url, headers = browserHeaders(cookie, referer))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "domain sonrası GET hatası: ${e.message}")
                    throw IOException("GET başarısız: $url (${e.message})")
                }
            }
            if (!resp.isSuccessful || isBotBody(resp.text)) {
                Log.e(LOG_TAG, "Bot/başarısız cevap → oturum yenileniyor: $url")
                cookie = refreshSession(url)
                resp = app.get(url, headers = browserHeaders(cookie, referer))
            }
        }
        lastCookie = cookie
        val ok = resp.isSuccessful
        val text = resp.text
        Log.d(
            LOG_TAG,
            "GET $url → ${if (ok) "başarılı" else "BAŞARISIZ"}, ${text?.length ?: 0} karakter, " +
                "${izleCount(text)} kart linki"
        )

        // v20 SON KATMAN: GET, oturum geçerli olmasına rağmen BOŞ içerik
        // dönebiliyor (site document-dışı isteklere farklı dönebilir).
        // Aynı URL WebView'da açılır; tarayıcının render ettiği TAM DOM
        // (outerHTML) alınır ve o çözülür — kullanıcı tarayıcıda ne
        // görüyorsa eklenti de onu görür.
        if (webViewFallback && ok && !isBotBody(text) && izleCount(text) == 0) {
            Log.w(LOG_TAG, "GET boş içerik getirdi → WebView DOM çekme deneniyor: $url")
            try {
                val res = BotKontrolKt.botKontrolFetch(context, url, wipeFirst = false)
                if (res.html.isNotBlank() &&
                    (izleCount(res.html) > 0 || res.html.length > (text?.length ?: 0))
                ) {
                    if (res.jar.isNotBlank()) {
                        lastCookie = res.jar
                        BotKontrolKt.saveJar(context, url, res.jar)
                    }
                    Log.d(
                        LOG_TAG,
                        "WebView DOM alındı: ${res.html.length} karakter, ${izleCount(res.html)} kart linki"
                    )
                    return res.html
                }
                Log.e(LOG_TAG, "WebView DOM'da da içerik yok (html=${res.html.length})")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "WebView DOM çekme hatası: ${e.message}")
            }
        }
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

        // v26: canlı domain garantisi; URL eski (ölü) domaini taşıyorsa
        // (arama öncesinden kalan / bookmark) mevcut domain'e haritalanır.
        ensureDomainAlive()
        val url = remapDomain(url)

        // v17: fetchHtml → kayıtlı oturumla dener, bot cevabı alırsa
        // WebView diyaloğuyla oturumu yeniler ve bir kez tekrar dener.
        val html = fetchHtml(url, mainUrl + "/", ensureSession = true, webViewFallback = true)
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
        // v26: canlı domain garantisi; bölüm URL'i ölü domaini taşıyorsa
        // mevcut domain'e haritalanır.
        ensureDomainAlive()
        val dataUrl = remapDomain(data)
        val errors = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        return try {
            val result = coroutineScope {
                Log.d(LOG_TAG, "data = $dataUrl")
                // v21: bölüm sayfası da içerik garantili (WebView DOM katmanı).
                val html = fetchHtml(dataUrl, mainUrl + "/", ensureSession = true, webViewFallback = true)
                val document = Jsoup.parse(html)

                // v17 (site.js kanıtı, 22.09.2026): bölüm sayfası episodeId'i
                // `animeWatch.initialize(animeId, episodeId, fansubId, ...)` script
                // çağrısında taşır; input#EpisodeId yoksa buradan çıkilir.
                val episodeId = document.selectFirst("input#EpisodeId")?.attr("value")?.toIntOrNull()
                    ?: Regex("animeWatch\\.initialize\\(\\s*(\\d+)\\s*,\\s*(\\d+)")
                        .find(html)?.groupValues?.getOrNull(2)?.toIntOrNull()
                Log.d(LOG_TAG, "episodeId = $episodeId")
                if (episodeId == null) {
                    errors += "bölüm ID'si bulunamadı (EpisodeId/animeWatch)"
                }
                val found = AtomicBoolean(false)
                val subsFound = AtomicBoolean(false)

                // v21 (site.js 22.09.2026): .fansubSelector öğeleri data-fid /
                // data-fad / data-furl taşır ve tıklanabilir; yapı değişse bile
                // data-fid'li öğe bulunur.
                val fansubItems = document
                    .select("li.fansubSelector, div.fansubSelector, a.fansubSelector")
                    .ifEmpty { document.select("[data-fid]") }
                Log.d(LOG_TAG, "fansub öğesi sayısı: ${fansubItems.size}")
                // v22 KANIT[1]: sayfanın GERÇEK HTML'inden fansub bölgesi.
                evidence += "KANIT[1] SAYFA ${html.length / 1000}k | fansub öğesi: ${fansubItems.size} | " +
                    htmlSnippet(html, "data-fid", 150, 420)
                if (fansubItems.isEmpty()) {
                    Log.e(LOG_TAG, "fansub öğesi yok — sayfa ${html.length} karakter, başı:\n" + html.take(800))
                    errors += "sayfada fansub listesi yok (sayfa ${html.length / 1000}k)"
                }
                coroutineScope {
                    fansubItems.mapIndexed { i, li ->
                        async {
                            loadFansubSources(
                                li, i, episodeId, found, errors, evidence, subsFound,
                                subtitleCallback, callback
                            )
                        }
                    }.awaitAll()
                }
                // v23-1: kaynak listesi sayfanın KENDİNDE sunucu tarafı
                // önceden render edilmiş olabilir (default fansub) — API
                // vermediyse sayfada doğrudan kaynak butonları aranır.
                if (!found.get()) {
                    val pageSources = document.select("li.sourceBtn, a.sourceBtn")
                        .ifEmpty {
                            document.select("[data-id]").filter {
                                it.attr("data-id").isNotEmpty() &&
                                    (it.attr("data-eid").isNotEmpty() ||
                                        it.attr("data-id").toIntOrNull() != null)
                            }
                        }
                    if (pageSources.isNotEmpty()) {
                        Log.d(LOG_TAG, "Sayfada sunucu tarafı kaynak butonu: ${pageSources.size}")
                        evidence += "KANIT[5] SAYFA-KAYNAK ${pageSources.size} adet"
                        coroutineScope {
                            pageSources.mapIndexed { j, btn ->
                                async {
                                    loadSingleSource(
                                        btn, j, episodeId, found, errors, evidence, subsFound,
                                        subtitleCallback, callback
                                    )
                                }
                            }.awaitAll()
                        }
                    }
                }
                // v23-2: SON KATMAN — bölüm sayfası gerçek tarayıcıda
                // (WebView) açılır ve RENDER EDİLEN DOM çözülür: tarayıcıda
                // görünen her şey eklentiye de gelir. Oturum taze çerezle
                // yenilenir, API yeniden denenir.
                if (!found.get()) {
                    Log.w(LOG_TAG, "API yolları kaynak vermedi → WebView DOM son katman")
                    try {
                        val res = BotKontrolKt.botKontrolFetch(context, dataUrl, wipeFirst = false)
                        if (res.jar.isNotBlank()) {
                            lastCookie = res.jar
                            BotKontrolKt.saveJar(context, dataUrl, res.jar)
                        }
                        val wDoc = if (res.html.isNotBlank()) Jsoup.parse(res.html) else null
                        val wBtns = wDoc?.select("li.sourceBtn, a.sourceBtn") ?: emptyList()
                        evidence += "KANIT[5] WEBVIEW DOM ${res.html.length / 1000}k | kaynak butonu: ${wBtns.size}"
                        val wSources = wBtns.ifEmpty { wDoc?.select("[data-id]") ?: emptyList() }
                        if (wSources.isNotEmpty()) {
                            Log.d(LOG_TAG, "WebView DOM'da kaynak butonu: ${wSources.size}")
                            coroutineScope {
                                wSources.mapIndexed { j, btn ->
                                    async {
                                        loadSingleSource(
                                            btn, j, episodeId, found, errors, evidence, subsFound,
                                            subtitleCallback, callback
                                        )
                                    }
                                }.awaitAll()
                            }
                        } else if (wDoc != null) {
                            // DOM'da kaynak yoksa taze oturumla API bir kez daha.
                            val wf = wDoc.selectFirst("[data-fid]") ?: fansubItems.firstOrNull()
                            if (wf != null) {
                                loadFansubSources(wf, 0, episodeId, found, errors, evidence, subsFound, subtitleCallback, callback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "WebView DOM son katman hatası: ${e.message}")
                        errors += "WebView DOM katmanı: ${e.message}"
                    }
                }
                found.get()
            }
            // v22: hata + KANIT bloğu EKRANDA görünür (Kitsugi mesajı gösterir).
            if (!result && (errors.isNotEmpty() || evidence.isNotEmpty())) {
                val msg = buildString {
                    if (errors.isNotEmpty()) append(errors.distinct().take(3).joinToString(" | "))
                    if (evidence.isNotEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append(evidence.takeLast(5).joinToString("\n"))
                    }
                }
                Log.e(LOG_TAG, "loadLinks KANIT raporu:\n$msg")
                throw ErrorLoadingException(msg)
            }
            result
        } catch (e: CancellationException) {
            throw e
        } catch (e: ErrorLoadingException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "loadLinks hatası: ${e::class.simpleName}: ${e.message}")
            throw ErrorLoadingException("Kaynaklar yüklenemedi (${e::class.simpleName}): ${e.message}")
        }
    }

    /**
     * v22: HTML içinde [anchor] özniteliği çevresındaki kısa parçayı döndürür
     * (ekran üstü kanıt mesajları için; anchor yoksa sayfanın başı).
     */
    private fun htmlSnippet(html: String, anchor: String, before: Int, after: Int): String {
        val idx = html.indexOf(anchor, ignoreCase = true)
        val raw = if (idx < 0) html.take(before + after)
        else html.substring(maxOf(0, idx - before), minOf(html.length, idx + after))
        return raw.replace(Regex("\\s+"), " ").take(560)
    }

    private suspend fun loadFansubSources(
        li: Element,
        index: Int,
        episodeId: Int?,
        found: AtomicBoolean,
        errors: MutableList<String>,
        evidence: MutableList<String>,
        subsFound: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fansubId = li.attr("data-fid").toIntOrNull()
        val fansubLabel = li.attr("data-fad").ifBlank { "fid=${fansubId ?: "?"}" }
        val tag = "kraptor_$fansubLabel"
        try {
            val eid = episodeId ?: 0
            val fid = fansubId ?: 0
            val xhrHeaders = browserHeaders(lastCookie, isXhr = true)
            // v23 (KÖK NEDEN DÜZELTİLMESİ): site.js (axios) GERÇEK JSON gövde
            // gönderiyor: {"EpisodeId":123,"FansubId":456}. NiceHttp 0.4.13
            // `data=map` parametresini FORM gövdeye çeviriyor; v17–v22'de
            // FORM gövde + sahte Content-Type: application/json başlığı gitti
            // → sunucu gövdeyi okuyamadı → kaynak listesi hep boştu.
            var bodyText = ""
            var httpOk = false
            var variant = "JSON"
            var sourceButtons: List<Element> = emptyList()
            var response = app.post(
                mainUrl + "/api/fansubSources",
                headers = xhrHeaders,
                json = JsonAsString("""{"EpisodeId":$eid,"FansubId":$fid}"""),
                timeout = 15
            )
            bodyText = response.text ?: ""
            httpOk = response.isSuccessful
            sourceButtons = sourceButtonsFromHtml(bodyText)
            if (sourceButtons.isEmpty()) {
                // v23: cevap JSON sarmallı gelmiş olabilir ({"data":"<html>"}).
                val wrapped = try {
                    val t = bodyText.trim()
                    if (t.startsWith("{")) JSONObject(t).optString("data", "") else ""
                } catch (_: Exception) {
                    ""
                }
                if (wrapped.isNotBlank() && wrapped.contains('<')) {
                    sourceButtons = sourceButtonsFromHtml(wrapped)
                    bodyText = wrapped
                }
            }
            if (sourceButtons.isEmpty()) {
                // v23: yedek — FORM gövdeli istek (JSON başlıksız, eski form).
                variant = "FORM"
                response = app.post(
                    mainUrl + "/api/fansubSources",
                    data = mapOf("EpisodeId" to eid.toString(), "FansubId" to fid.toString()),
                    headers = xhrHeaders,
                    timeout = 15
                )
                bodyText = response.text ?: ""
                httpOk = response.isSuccessful
                sourceButtons = sourceButtonsFromHtml(bodyText)
            }
            Log.d(tag, "fansubSources(fid=$fansubId, ep=$episodeId) [$variant] → ${if (httpOk) "ok" else "BAŞARISIZ"}, ${sourceButtons.size} kaynak, ${bodyText.length / 1000}k")
            // v22 KANIT[2]: İLK fansubun cevabı (mesaj büyüsün diye tek).
            if (index == 0) {
                evidence += "KANIT[2] fansubSources(fid=$fansubId, ep=$episodeId) [$variant] ok=$httpOk ${bodyText.length / 1000}k | " +
                    bodyText.replace(Regex("\\s+"), " ").take(420)
            }
            if (sourceButtons.isEmpty()) {
                Log.e(tag, "sourceBtn yok — cevap başı:\n" + bodyText.take(600))
                errors += "fansub '$fansubLabel': kaynak listesi ${if (httpOk) "boş" else "alınamadı (HTTP)"}"
            }
            coroutineScope {
                sourceButtons.mapIndexed { j, btn ->
                    async {
                        loadSingleSource(
                            btn, j, episodeId, found, errors, evidence, subsFound,
                            subtitleCallback, callback
                        )
                    }
                }.awaitAll()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "Error loading fansub $fansubLabel: ${e.message}")
            errors += "fansub '$fansubLabel': ${e.message}"
        }
    }

    /**
     * v23: fansubSources/sayfa HTML'inden kaynak butonlarını seçer
     * (toleranslı: li/a.sourceBtn, yoksa data-id'li öğe).
     */
    private fun sourceButtonsFromHtml(html: String): List<Element> {
        val doc = Jsoup.parse(html)
        return doc.select("li.sourceBtn, a.sourceBtn").ifEmpty { doc.select("[data-id]") }
    }

    private suspend fun loadSingleSource(
        btn: Element,
        index: Int,
        episodeId: Int?,
        found: AtomicBoolean,
        errors: MutableList<String>,
        evidence: MutableList<String>,
        subsFound: AtomicBoolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val sourceId = btn.attr("data-id")
        val eid = btn.attr("data-eid").ifBlank { episodeId?.toString() ?: "" }
        val sourceName = btn.selectFirst("p.title")?.text()?.trim()
            ?: btn.text().trim().ifBlank { "Kaynak ${sourceId.ifBlank { "?" }}" }
        // v22: KALİTE — her kaynak (sourceBtn) ayrı bir ExtractorLink olarak
        // sunulur; kaynak adı (p.title: 1080p/720p/fansub adı) arayüzde kalite
        // seçimi görevi görür (site bu zinciri böyle sunuyor; ayrı kalite API yok).
        Log.d(LOG_TAG, "Kaynak adı (kalite/fansub): $sourceName")
        try {
            Log.d(LOG_TAG, "Video: $sourceName | ID: $sourceId | EID: $eid")
            // v17 (site.js kanıtı, 22.09.2026): site kendi JS'inde TEK PARAMETRELİ
            // `POST /api/sourcePlayer/{id}` kullanıyor (li.sourceBtn'un data-id'si).
            // Eski ikili form ({sourceId}/{eid}) yalnızca yedek olarak denenecek.
            val primary = app.post(
                mainUrl + "/api/sourcePlayer/$sourceId",
                headers = browserHeaders(lastCookie, isXhr = true),
                timeout = 15
            )
            Log.d(LOG_TAG, "sourcePlayer($sourceName) [1 param] → ${if (primary.isSuccessful) "ok" else "BAŞARISIZ"}")
            // v23: cevap JSON {source} OLABİLDİĞİ GİBİ düz oynatıcı HTML'i de
            // olabilir — ikisi de kabul edilir.
            var sourceHtml: String? = try {
                primary.parsedSafe<VideoApi>()?.source?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                null
            }
            if (sourceHtml == null) {
                val raw1 = (primary.text ?: "").trimStart()
                if (raw1.startsWith("<") || raw1.contains("<iframe") || raw1.contains("<video")) {
                    sourceHtml = raw1
                    Log.d(LOG_TAG, "sourcePlayer cevabı JSON değil — düz HTML olarak alındı")
                }
            }
            if (sourceHtml == null) {
                Log.d(LOG_TAG, "sourcePlayer($sourceName) → yedek form (2 param) deneniyor")
                val legacy = app.post(
                    mainUrl + "/api/sourcePlayer/$sourceId/$eid",
                    headers = browserHeaders(lastCookie, isXhr = true),
                    timeout = 15
                )
                sourceHtml = try {
                    legacy.parsedSafe<VideoApi>()?.source?.takeIf { it.isNotBlank() }
                } catch (e: Exception) {
                    null
                } ?: (legacy.text ?: "").trimStart().takeIf {
                    it.startsWith("<") || it.contains("<iframe") || it.contains("<video")
                }
            }
            // v25 (site.js'in kendi ilk-tıklama yolu): günün ilk
            // tıklamasında site sourcePlayer DEĞİL preVideoAd çağırır;
            // cevap DÜZ oynatıcı HTML'i'dir (response.data birebir).
            // sourcePlayer boşsa bu bağımsız 2. yol da denenir.
            if (sourceHtml == null) {
                try {
                    val preAd = app.post(
                        mainUrl + "/api/preVideoAd/$sourceId",
                        headers = browserHeaders(lastCookie, isXhr = true),
                        timeout = 15
                    )
                    val preBody = (preAd.text ?: "").trimStart()
                    sourceHtml = preBody.takeIf {
                        it.startsWith("<") || it.contains("<iframe") || it.contains("<video")
                    }
                    if (sourceHtml != null) {
                        Log.d(LOG_TAG, "sourcePlayer boş → preVideoAd düz oynatıcı HTML'i döndürdü ($sourceName)")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "preVideoAd denemesi hatası ($sourceName): ${e.message}")
                }
            }
            if (sourceHtml == null) {
                val raw = primary.text ?: ""
                Log.e(LOG_TAG, "sourcePlayer JSON parse hatası: ${raw.take(500)}")
                // v22 KANIT[3a]: cevabın GERÇEK başı ekrana düşsün.
                if (index == 0) {
                    evidence += "KANIT[3] sourcePlayer(id=$sourceId) ok=${primary.isSuccessful} | cevab başı: " +
                        raw.replace(Regex("\\s+"), " ").take(380)
                }
                errors += "'$sourceName': sourcePlayer yanıtı boş/parse edilemedi"
                return
            }
            // v23: oynatıcı HTML'i BASE URL ile parse et — göreli iframe src
            // çözülür; birden fazla iframe (reklam + player) sırayla denenecek.
            val sourceDoc = Jsoup.parse(sourceHtml, mainUrl + "/")
            val iframes = sourceDoc.select("iframe")
                .map { it.attr("abs:src").ifBlank { it.attr("src") } }
                .filter { it.isNotBlank() && !it.startsWith("javascript") }
                .take(4)
            val directUrl = directVideoUrlFromHtml(sourceHtml, mainUrl + "/")
            Log.d(LOG_TAG, "iframes = $iframes | direct = $directUrl")
            // v22 KANIT[3b]: source HTML'inin GERÇEK başı.
            if (index == 0) {
                evidence += "KANIT[3] sourcePlayer(id=$sourceId) ok | source: ${sourceHtml.length / 1000}k | " +
                    sourceHtml.replace(Regex("\\s+"), " ").take(420)
            }
            // v22: altyazılar — source HTML'indeki <track> / .vtt / .srt.
            if (index == 0) {
                subsFound.set(extractSubtitles(sourceHtml, mainUrl + "/", subtitleCallback))
            }
            if (iframes.isEmpty() && directUrl == null) {
                Log.e(LOG_TAG, "ne iframe ne düz video — source HTML başı:\n" + sourceHtml.take(800))
                errors += "'$sourceName': oynatıcı HTML'inde iframe/video yok"
                return
            }

            var ok = false
            // 1) Düz video adresi varsa en sağlam yol — doğrudan oynat.
            if (directUrl != null) {
                callback(
                    newExtractorLink(source = sourceName, name = sourceName, url = directUrl, type = INFER_TYPE) {
                        this.headers = mapOf(
                            "Referer" to mainUrl + "/",
                            "User-Agent" to BotKontrolKt.UA_FIREFOX
                        )
                    }
                )
                ok = true
                Log.d(LOG_TAG, "DİREKT video: $directUrl")
            }
            // 2) v23: TÜM iframe'ler sırayla denenir (reklam iframe'i ön
            //    planda olsa bile): kayıtlı extractor → embed sayfası taraması.
            for (iframe in iframes) {
                if (ok) break
                val luffyId = iframe.substringAfter(LUFFY_PREFIX, "")
                val candidates = if (luffyId.isNotEmpty()) listOf(luffyId, iframe) else listOf(iframe)
                for (target in candidates.distinct()) {
                    if (loadExtractor(target, mainUrl + "/", subtitleCallback, callback)) {
                        ok = true
                        break
                    }
                }
                // 3) v21: extractor eşleşmediyse iframe sayfası doğrudan çekilir
                //    ve içindeki düz video aranır (host agnostik yedek).
                if (!ok) {
                    val (genUrl, embedHead) = fetchEmbedVideoUrl(iframe, sourceName)
                    if (index == 0) {
                        evidence += "KANIT[4] iframes=${iframes.size} | denerilen=$iframe | direct=${directUrl ?: "YOK"} | embed=${if (genUrl != null) "video: $genUrl" else "video YOK"}"
                        if (!subsFound.get() && embedHead.isNotEmpty()) {
                            subsFound.set(extractSubtitles(embedHead, iframe, subtitleCallback))
                        }
                    }
                    if (genUrl != null) {
                        callback(
                            newExtractorLink(source = sourceName, name = sourceName, url = genUrl, type = INFER_TYPE) {
                                this.headers = mapOf(
                                    "Referer" to mainUrl + "/",
                                    "User-Agent" to BotKontrolKt.UA_FIREFOX
                                )
                            }
                        )
                        ok = true
                        Log.d(LOG_TAG, "GENEL embed çözümü: $genUrl")
                    }
                }
            }
            // 4) v24: DOSYA-HOST kaynakları (Gdrive, Mega, SendVid, Voe,
            //    Ok.RU, Byandex, Sibnet…) — oynatıcı HTML'inde iframe
            //    değil düz dosya linki var. HTML'deki TÜM adresler
            //    taranır; video host'una uyan ilk adres çözülüp link
            //    olarak eklenir (kaynak boş kalmaz).
            if (!ok) {
                val hostCandidates = Regex("""https?://[^\s"'<>()\\]+""")
                    .findAll(sourceHtml)
                    .map { it.value.replace(Regex("[.,;:]+$"), "") }
                    .distinct()
                    .filter { u ->
                        val lu = u.lowercase()
                        videoHostMarkers.any { lu.contains(it) } ||
                            Regex("\\.(mp4|m3u8|webm|mkv)(\\?|$)", RegexOption.IGNORE_CASE).find(u) != null
                    }
                    .take(5)
                for (u in hostCandidates) {
                    Log.d(LOG_TAG, "Dosya hostü adayı ($sourceName): $u")
                    val resolved = tryResolveVideoHostUrl(u, sourceName)
                    if (index == 0) {
                        evidence += "KANIT[6] host-aday=$u | cozulen=${resolved ?: "HAM"}"
                    }
                    callback(
                        newExtractorLink(source = sourceName, name = sourceName, url = resolved ?: u, type = INFER_TYPE) {
                            this.headers = mapOf(
                                "Referer" to mainUrl + "/",
                                "User-Agent" to BotKontrolKt.UA_FIREFOX
                            )
                        }
                    )
                    ok = true
                    Log.d(LOG_TAG, "DOSYA HOSTÜ kaynağı eklendi: $sourceName → ${resolved ?: u}")
                    break
                }
            }
            if (ok) {
                found.set(true)
            } else {
                Log.w(LOG_TAG, "source '$sourceName' çözülemedi (iframes=$iframes, host adayı yok)")
                errors += "'$sourceName': iframe/video/host adresi bulunamadı"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error loading source $sourceName: ${e.message}")
            errors += "'$sourceName': ${e.message}"
        }
    }

    /**
     * v22: HTML içindeki altyazı adreslerini ekler (varsa).
     *  - <track kind="subtitles|captions" src="..." [srclang=...] [label=...]>
     *  - metin/JS içindeki .vtt / .srt adresleri
     * @return en az bir altyazı eklendi mi.
     */
    private fun extractSubtitles(
        html: String,
        base: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        var added = false
        try {
            val doc = Jsoup.parse(html, base)
            doc.select("track[src]").forEach { t ->
                val kind = t.attr("kind").ifBlank { "subtitles" }
                if (!kind.equals("subtitles", true) && !kind.equals("captions", true)) return@forEach
                val url = t.attr("abs:src")
                if (!url.startsWith("http")) return@forEach
                val lang = t.attr("srclang").ifBlank { t.attr("lang") }
                val label = t.attr("label").ifBlank { lang.ifBlank { "Altyazı" } }
                subtitleCallback(SubtitleFile(lang.ifBlank { label }, url))
                added = true
                Log.d(LOG_TAG, "Altyazı (track): $label → $url")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "track altyazı çözümü hatası: ${e.message}")
        }
        if (!added) {
            Regex("""["'](https?://[^"']+?\.(?:vtt|srt))[^"']*["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .take(3)
                .forEach { url ->
                    subtitleCallback(SubtitleFile("Altyazı", url))
                    added = true
                    Log.d(LOG_TAG, "Altyazı (url): $url")
                }
        }
        return added
    }
    /**
     * v21: HTML içindeki DOĞRUDAN video URL'sini bulur.
     * Sırasıyla: <video src> / <source src> (göreli adres [base] ile çözülür)
     * → metin/JS içindeki mp4, m3u8 adresleri.
     */
    private fun directVideoUrlFromHtml(html: String, base: String? = null): String? {
        val doc = try {
            if (base != null) Jsoup.parse(html, base) else Jsoup.parse(html)
        } catch (_: Exception) {
            null
        }
        doc?.let {
            val v = it.selectFirst("video[src]")?.attr("abs:src")
                ?: it.selectFirst("video source[src]")?.attr("abs:src")
                ?: it.selectFirst("source[src]")?.attr("abs:src")
            if (!v.isNullOrBlank() && v.startsWith("http")) return v
        }
        val patterns = listOf(
            // v24: JSON tırnaklı anahtarlar ("file":"...mp4", "src":"...m3u8")
            // — host sayfaları (sendvid/voe vb.) stream adresini böyle taşır.
            Regex("""["'](file|src|source|url|srcUrl|video_src|videourl|urlSource|hls)["']\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](file|src|source|url|srcUrl|video_src|videourl|urlSource|hls)["']\s*[:=]\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|url)\s*[:=]\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (p in patterns) {
            p.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.startsWith("http") }?.let { return it }
        }
        return null
    }

    /**
     * v21: kayıtlı extractor'ın çözemediği iframe sayfasını DOĞRUDAN çekip
     * içindeki düz video URL'sini arar (host agnostik yedek).
     * v22: (videoUrl, sayfaBaşı600) döndürür — kanıt mesajı + altyazı için.
     */
    private suspend fun fetchEmbedVideoUrl(iframeUrl: String, sourceName: String): Pair<String?, String> {
        return try {
            // v23: tam tarayıcı imzası + site oturum çerezi (embed same-site
            // CF arkasındaysa geçer; cross-site host'ta zararsız).
            val resp = app.get(
                iframeUrl,
                headers = browserHeaders(lastCookie, referer = iframeUrl, isXhr = false),
                timeout = 10
            )
            if (!resp.isSuccessful) {
                Log.e(LOG_TAG, "embed sayfası alınamadı ($sourceName): $iframeUrl")
                return null to ""
            }
            val text = resp.text ?: return null to ""
            Log.d(LOG_TAG, "embed sayfası çekildi (${text.length} karakter): $iframeUrl")
            val url = directVideoUrlFromHtml(text, iframeUrl)
            url to text.replace(Regex("\\s+"), " ").take(600)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(LOG_TAG, "embed sayfası çekme hatası: ${e.message}")
            null to ""
        }
    }

    /**
     * v24: video dosya hostları — site kaynak listesi bunları taşıyor
     * (Gdrive, Mega, SendVid, Voe/Vudeo, Ok.RU, Byandex, Sibnet, MailRu…).
     * Küçük harf URL'de alt dizi eşleşmesi.
     */
    private val videoHostMarkers = listOf(
        "drive.google.com", "docs.google.com", "drive.usercontent.google.com",
        "mega.nz", "mega.co.nz", "ok.ru", "cloud.mail.ru", "my.mail.ru",
        "sendvid", "voe.sx", "voe.to", "vdo.sx", "sibnet", "byandex",
        "vudeo", "upstream", "uploadeveryone", "streamtape", "videopress"
    )

    /**
     * v25: sayfasında DOĞRUDAN stream adresi taşıyan hostlar — yalnız
     * bunların sayfası çekilir (10 sn zaman aşımıyla). Diğer hostlar
     * (mega/ok.ru/sibnet vb.) için çekim YAPILMAZ, ham link eklenir.
     */
    private val pageFetchableHosts = listOf(
        "sendvid", "voe.sx", "voe.to", "vdo.sx", "streamtape"
    )

    /**
     * v24: video-host adresi → doğrudan stream adresi (mümkünse).
     *  - Adres zaten video uzantılıysa → olduğu gibi.
     *  - Gdrive /file/d/{id} → doğrudan indirme adresi (confirm=t).
     *  - Aksi halde host sayfası (sendvid/voe için /embed|/watch/{id}
     *    formlarıyla da) çekilip içinde mp4/m3u8 aranır.
     *  - Bulunamazsa null (ham adres link olarak kullanılır).
     */
    private suspend fun tryResolveVideoHostUrl(url: String, sourceName: String): String? {
        if (Regex("\\.(mp4|m3u8|webm|mkv)(\\?|$)", RegexOption.IGNORE_CASE).find(url) != null) return url
        val driveId = Regex("drive\\.google\\.com/file/d/([A-Za-z0-9_-]+)").find(url)?.groupValues?.getOrNull(1)
        if (driveId != null) {
            val dl = "https://drive.usercontent.google.com/download?id=$driveId&export=download&confirm=t"
            Log.d(LOG_TAG, "Gdrive doğrudan indirme: $dl")
            return dl
        }
        // v25: yalnızca sayfasında stream TAŞIYAN hostların sayfası
        // çekilir; mega/ok.ru/sibnet vb. için çekim yok (ham link) —
        // zincir boğulmasın, "bir anda hata" vermesin.
        val lu = url.lowercase()
        if (pageFetchableHosts.none { lu.contains(it) }) return null
        val host = Regex("https?://([^/]+)").find(url)?.groupValues?.getOrNull(1) ?: ""
        val embedId = Regex("/(?:watch|embed|v|video)/(?:video-)?([A-Za-z0-9_-]{6,})").find(url)?.groupValues?.getOrNull(1)
        val pages = mutableListOf(url)
        if (host.isNotEmpty() && embedId != null) {
            pages += "https://$host/embed/$embedId"
            pages += "https://$host/watch/$embedId"
        }
        for (c in pages.distinct().take(2)) {
            try {
                val resp = app.get(c, headers = browserHeaders(lastCookie, referer = c, isXhr = false), timeout = 10)
                if (resp.isSuccessful) {
                    val body = resp.text ?: ""
                    val v = directVideoUrlFromHtml(body, c)
                    if (v != null) {
                        Log.d(LOG_TAG, "host sayfasında stream bulundu ($sourceName): $v")
                        return v
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "host sayfası çekme hatası ($sourceName): ${e.message}")
            }
        }
        return null
    }

    companion object {
        private const val LOG_TAG = "kraptor_TrAnime"
        private const val LUFFY_PREFIX = "luffytra2.top/embed2/?id="

        // v26: ana domain (25.10.2026 kanıtı): izleani.me canlı takipçisi
        // sitenin SON bölümlerini www.tranimeizle.io üzerinden listeliyor;
        // Semrush'a göre .io 14.47M ziyaret/ay (ana domain); cs-kraptor'un
        // günlük güncellenen domain dosyası da .io taşıyor. .live artık
        // aktif domain DEĞİL — ama tek domain'e kilitlenmek bir daha asla
        // olmaz: DOMAIN_CANDIDATES otomatik rotasyonda denenir.
        private const val DEFAULT_DOMAIN = "https://www.tranimeizle.io"

        /** v26: otomatik rotasyon aday domainleri (sıra = öncelik). */
        private val DOMAIN_CANDIDATES = listOf(
            "https://www.tranimeizle.io",
            "https://www.tranimeizle.live",
            "https://www.tranimeizle.top"
        )

        private const val ACTIVE_DOMAIN_KEY = "kraptor_active_domain"
        private const val DOMAIN_CHECK_TTL_MS = 10 * 60 * 1000L

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
            // v26: 1) uygulamanın açık tercihi (varsa), 2) eklentinin son
            // doğruladığı domain, 3) varsayılan (.io).
            val domain = prefs.getString(pluginName, "")?.ifBlank { null }
                ?: prefs.getString(ACTIVE_DOMAIN_KEY, "")?.ifBlank { null }
                ?: DEFAULT_DOMAIN
            Log.d(LOG_TAG, "domain = $domain")
            return domain
        }
    }
}
