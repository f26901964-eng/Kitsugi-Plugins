package com.kraptor

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.util.Collections

/**
 * TrAnimeIzle akış çözücüsü — **tek, deterministik zincir** (v30).
 *
 * Neden yeniden yazıldı?
 *  v17–v29 arasındaki sürümler zinciri "her ihtimale karşı" onlarca yolla
 *  deniyordu (WebView oturumu, DOM kanıt katmanı, host sayfası taraması…).
 *  Sonuç: kullanıcının gördüğü şey **dakikalarca süren** bir yükleme ve çoğu
 *  zaman BOŞ kaynak listesiydi. Oysa sitenin kendi oynatıcı zinciri tek ve
 *  sabittir (site.js, 22.09.2026 arşivi + 25.10.2026 canlı istemci):
 *
 *   1. Bölüm sayfası                 → `input#EpisodeId` (yoksa `animeWatch.initialize(aId, eId…)`)
 *   2. Fansub seçiciler              → `li/div.fansubSelector[data-fid]`
 *   3. POST /api/fansubSources       → JSON gövde `{"EpisodeId":…,"FansubId":…}`
 *                                     → HTML: `li.sourceBtn[data-id]` + `p` = host adı
 *   4. POST /api/sourcePlayer/{id}   → `{"source":"<iframe src=…>"}`
 *                                     → iframe `…/embed2/?id=<GERÇEK HOST URL>`
 *   5. Host URL                      → uygulamanın extractor'ları (filemoon, voe,
 *                                     sibnet, ok.ru, sendvid, streamlare, vudeo,
 *                                     mp4upload, mixdrop, yourupload…)
 *
 *  Bu sınıf tam olarak bu zinciri uygular. Ek olarak:
 *  - **Hız**: fansub/kaynak/host çözümlemesi PARALEL (semaphore), her istek
 *    zaman aşımlı, WebView **yok**, toplam süre bütçeli. Sonuçlar çözüldükçe
 *    yayınlanır (kullanıcı ilk kaynağı ~2 sn içinde görür).
 *  - **Eksiksizlik**: listedeki HER kaynak denenir; `sourcePlayer` boşsa sitenin
 *    ikinci oynatıcı yolu `POST /api/preVideoAd/{id}` devreye girer.
 *  - **Kalite**: HLS master listeleri varyantlarına ayrılır (`M3u8Helper`),
 *    böylece 1080p/720p/480p ayrı ayrı görünür.
 *  - **Captcha**: site ara sıra `/api/Captcha` doğrulaması ister (Aniyomi
 *    istemcisi `ShittyCaptchaInterceptor` ile çözüyor). Burada da aynı yöntem
 *    (en farklı görselin hash'i) uygulanır; yalnızca gerektiğinde çalışır.
 */
internal class TrAnimeIzleStreams(
    private val pluginName: String,
    private val base: () -> String,
    private val http: TrStreamHttp
) {

    /** Fansub listesinden gelen tek bir kaynak butonu. */
    private data class Src(val id: String, val label: String, val fansub: String)

    /** Aynı adresin iki kez yayınlanmaması için (paralel iş parçacıkları güvenli). */
    private val emitted: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val seenSubs: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val emitLock = Mutex()

    @Volatile
    private var emittedCount: Int = 0

    // ------------------------------------------------------------------
    // Giriş noktası
    // ------------------------------------------------------------------

    suspend fun load(
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Int {
        val site = base()
        val referer = "$site/"
        val html = http.get(pageUrl, referer)
        if (html.isNullOrBlank()) {
            Log.w(TAG, "bölüm sayfası alınamadı: $pageUrl")
            return 0
        }
        val doc = Jsoup.parse(html, pageUrl)
        emitSubtitles(html, pageUrl, subtitleCallback)

        val episodeId = TrAnimeIzleSite.episodeIdFrom(html, doc) ?: 0
        val fansubs = TrAnimeIzleSite.fansubsFrom(doc)
        Log.d(TAG, "episodeId=$episodeId fansub=${fansubs.size}")

        // 1) Fansub → kaynak butonları (paralel, en fazla 4 eşzamanlı).
        val srcGate = Semaphore(4)
        val sources = coroutineScope {
            fansubs.map { fansub ->
                async {
                    srcGate.withPermit { fetchSources(fansub, episodeId, pageUrl) }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.id }

        // 2) API hiç kaynak vermediyse sayfadaki hazır butonlar (tema yedeği).
        val allSources = if (sources.isEmpty()) {
            pageLevelSources(doc, html, episodeId)
        } else {
            sources
        }
        Log.d(TAG, "kaynak butonu: ${allSources.size}")

        if (allSources.isEmpty()) return emittedCount

        // 3) Kaynak butonlarını çöz — 8 eşzamanlı, dar zaman aşımı, sonuç
        //    çözüldükçe yayınlanır.
        runBounded(allSources, concurrency = 8, budgetMs = TOTAL_BUDGET_MS) { src ->
            val label = buildLabel(src)
            val body = http.postEmpty("$site/api/sourcePlayer/${src.id}", pageUrl)
            val ok = !body.isNullOrBlank() && resolvePlayerBody(body, pageUrl, label, subtitleCallback, callback)
            if (!ok) {
                // Sitenin ikinci oynatıcı yolu (ilk tıklama): preVideoAd.
                val alt = http.postEmpty("$site/api/preVideoAd/${src.id}", pageUrl)
                if (!alt.isNullOrBlank()) {
                    resolvePlayerBody(alt, pageUrl, label, subtitleCallback, callback)
                }
            }
        }
        Log.d(TAG, "toplam ${emittedCount} akış yayınlandı")
        return emittedCount
    }

    private fun buildLabel(src: Src): String {
        val fansub = src.fansub.trim()
        val host = src.label.trim()
        return when {
            fansub.isEmpty() && host.isEmpty() -> "Kaynak"
            fansub.isEmpty() -> host
            host.isEmpty() -> fansub
            host.contains(fansub, ignoreCase = true) -> host
            else -> "$fansub • $host"
        }
    }

    // ------------------------------------------------------------------
    // 1) Kaynak listesi
    // ------------------------------------------------------------------

    private suspend fun fetchSources(
        fansub: TrFansub,
        episodeId: Int,
        pageUrl: String
    ): List<Src> {
        val site = base()
        val body = http.postJson(
            "$site/api/fansubSources",
            """{"EpisodeId":$episodeId,"FansubId":${fansub.id}}""",
            pageUrl
        )
        if (body.isNullOrBlank()) {
            Log.d(TAG, "fansubSources boş (fid=${fansub.id})")
            return emptyList()
        }
        if (looksLikeCaptcha(body)) {
            Log.w(TAG, "fansubSources captcha istedi (fid=${fansub.id})")
            http.solveCaptcha()
            val retry = http.postJson(
                "$site/api/fansubSources",
                """{"EpisodeId":$episodeId,"FansubId":${fansub.id}}""",
                pageUrl
            )
            if (retry.isNullOrBlank()) return emptyList()
            return TrAnimeIzleSite.sourcesFrom(retry).map {
                Src(it.id, it.label, fansub.label)
            }
        }
        val parsed = TrAnimeIzleSite.sourcesFrom(body)
        if (parsed.isEmpty()) Log.d(TAG, "kaynak butonu yok (fid=${fansub.id}, ${body.length} krkt)")
        return parsed.map { Src(it.id, it.label, fansub.label) }
    }

    /** Sayfada sunucu tarafında render edilmiş kaynak butonları. */
    private fun pageLevelSources(doc: org.jsoup.nodes.Document, html: String, episodeId: Int): List<Src> {
        val direct = TrAnimeIzleSite.sourcesFrom(html)
        if (direct.isNotEmpty()) {
            return direct.map { Src(it.id, it.label, "Sayfa") }
        }
        val fromDoc = doc.select("li.sourceBtn, a.sourceBtn, div.sourceBtn")
            .mapNotNull { el ->
                val id = el.attr("data-id").trim()
                if (id.isEmpty()) return@mapNotNull null
                val label = el.selectFirst("p.title")?.text()?.trim()
                    ?: el.selectFirst("p, span, strong")?.text()?.trim()
                    ?: el.text().trim()
                Src(id, label, "Sayfa")
            }
        if (fromDoc.isNotEmpty()) return fromDoc
        // Son yedek: sayfadaki data-eid/data-id çiftleri (episodeId eşleşmeli).
        return doc.select("[data-id]").mapNotNull { el ->
            val id = el.attr("data-id").trim()
            if (id.isEmpty() || id.any { it.isWhitespace() }) return@mapNotNull null
            val eid = el.attr("data-eid").toIntOrNull()
            if (episodeId > 0 && eid != null && eid != episodeId) return@mapNotNull null
            Src(id, el.text().trim(), "Sayfa")
        }.distinctBy { it.id }
    }

    // ------------------------------------------------------------------
    // 2) Oynatıcı gövdesi → hedef adresler
    // ------------------------------------------------------------------

    /**
     * `sourcePlayer` / `preVideoAd` cevabını çözer: JSON sarmalı açılır,
     * iframe adresleri ve doğrudan medya adresleri toplanıp çözülür.
     */
    private suspend fun resolvePlayerBody(
        body: String,
        pageUrl: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html = TrAnimeIzleSite.unwrapJsonHtml(body).ifBlank { body }
        emitSubtitles(html, pageUrl, subtitleCallback)

        val targets = LinkedHashSet<String>()
        for (frame in TrAnimeIzleSite.iframesFrom(html, pageUrl)) {
            for (candidate in TrAnimeIzleSite.embedCandidates(frame)) {
                val url = decode(candidate)
                // `id` parametresi çoğu zaman GERÇEK host adresidir; bazı
                // durumlarda yalnızca bir kimlik olur → o zaman iframe'in
                // kendisi hedef olur (katman sayfası açılıp içine bakılır).
                if (url.startsWith("http") || url.startsWith("//")) targets.add(url)
            }
            targets.add(frame)
        }
        targets.addAll(TrAnimeIzleSite.directMediaFrom(html, pageUrl))

        if (targets.isEmpty()) return false

        var any = false
        runBounded(targets.take(6), concurrency = 4, budgetMs = SOURCE_BUDGET_MS) { target ->
            if (resolveTarget(target, pageUrl, label, subtitleCallback, callback, depth = 0)) any = true
        }
        return any || emittedCount > 0
    }

    // ------------------------------------------------------------------
    // 3) Hedef adres çözümleme
    // ------------------------------------------------------------------

    private suspend fun resolveTarget(
        rawUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val url = normalize(rawUrl)
        if (url.isEmpty()) return false

        // Doğrudan medya (m3u8/mp4/mpd).
        if (isDirectMedia(url)) {
            emitMedia(url, label, referer, callback)
            return true
        }

        // Google Drive → gdriveplayer köprüsü (tarayıcıda oynayan yol).
        if (url.contains("drive.google.com") || url.contains("docs.google.com")) {
            if (resolveGoogleDrive(url, referer, label, subtitleCallback, callback)) return true
        }

        // Uygulamanın kayıtlı extractor'ları (325 site: filemoon, voe, sibnet,
        // ok.ru, sendvid, streamlare, mp4upload, mixdrop, yourupload, uqload…).
        if (tryExtractors(url, referer, label, subtitleCallback, callback)) return true

        if (depth >= 2) return false

        // Host sayfasını çek: içinde m3u8/mp4 ya da bir iç iframe ara.
        val page = http.get(url, referer)
        if (page.isNullOrBlank()) return false
        emitSubtitles(page, url, subtitleCallback)

        val media = TrAnimeIzleSite.directMediaFrom(page, url)
        var found = false
        for (stream in media.take(4)) {
            emitMedia(stream, label, url, callback)
            found = true
        }
        if (found) return true

        // Bir katman iç iframe (luffytra sarmalı, reklam katmanı vb.).
        val nested = TrAnimeIzleSite.iframesFrom(page, url)
            .flatMap { TrAnimeIzleSite.embedCandidates(it) }
            .map { decode(it) }
            .filter { it != url }
            .distinct()
        for (inner in nested.take(2)) {
            if (resolveTarget(inner, url, label, subtitleCallback, callback, depth + 1)) found = true
        }
        return found
    }

    /** Kayıtlı extractor'ları dener; yayınlanan her linki kendi etiketimizle geçirir. */
    private suspend fun tryExtractors(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bag = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val handled = try {
            loadExtractor(url, referer, subtitleCallback) { link -> bag.add(link) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "loadExtractor hatası (${url.take(90)}): ${e.message}")
            false
        }
        if (bag.isEmpty()) return false
        for (link in bag) {
            emitLink(link.url, label, link.referer.ifBlank { referer }, link.type, link.quality, link.headers, callback)
        }
        return handled || bag.isNotEmpty()
    }

    private suspend fun resolveGoogleDrive(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""/d/([A-Za-z0-9_-]{6,})""").find(url)?.groupValues?.getOrNull(1)
        val bridged = "https://gdriveplayer.to/embed2.php?link=" +
            java.net.URLEncoder.encode(url, "UTF-8")
        if (tryExtractors(bridged, referer, label, subtitleCallback, callback)) return true
        if (id != null) {
            // Son çare: doğrudan indirme adresi (bazı dosyalarda çalar).
            emitMedia(
                "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t",
                label,
                referer,
                callback
            )
            return true
        }
        return false
    }

    // ------------------------------------------------------------------
    // 4) Yayınlama
    // ------------------------------------------------------------------

    /** Medya adresini yayınlar; HLS master ise kalite varyantlarına ayırır. */
    private suspend fun emitMedia(
        url: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = normalize(url)
        if (clean.isEmpty()) return
        val isHls = clean.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.contains(".m3u8") }
        if (isHls) {
            val variants = try {
                withTimeoutOrNull(10_000) {
                    M3u8Helper.generateM3u8(
                        pluginName,
                        clean,
                        referer,
                        headers = streamHeaders(referer, clean)
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.d(TAG, "m3u8 varyant çözümü başarısız: ${e.message}")
                null
            }
            if (!variants.isNullOrEmpty()) {
                for (variant in variants) {
                    val qualityLabel = when {
                        variant.quality in 144..4320 -> "$label ${variant.quality}p"
                        else -> label
                    }
                    emitLink(
                        variant.url,
                        qualityLabel,
                        referer,
                        ExtractorLinkType.M3U8,
                        variant.quality,
                        variant.headers,
                        callback
                    )
                }
                return
            }
        }
        emitLink(clean, label, referer, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO, null, null, callback)
    }

    private suspend fun emitLink(
        url: String,
        label: String,
        referer: String,
        type: ExtractorLinkType,
        quality: Int?,
        extraHeaders: Map<String, String>?,
        callback: (ExtractorLink) -> Unit
    ) {
        val clean = normalize(url)
        if (clean.isEmpty() || !emitted.add(clean)) return
        val ref = referer.ifBlank { base() + "/" }
        val headers = streamHeaders(ref, clean) + (extraHeaders ?: emptyMap())
        val link = newExtractorLink(
            source = pluginName,
            name = label.ifBlank { pluginName },
            url = clean,
            type = type
        ) {
            this.referer = ref
            this.headers = headers
            this.quality = quality ?: getQualityFromName(label)
        }
        emitLock.withLock {
            emittedCount += 1
            callback(link)
        }
    }

    private fun streamHeaders(referer: String, streamUrl: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to UA,
        "Origin" to originOf(streamUrl)
    )

    private fun originOf(url: String): String {
        val host = TrAnimeIzleSite.hostOf(url)
        return if (host.isEmpty()) base() else "https://$host"
    }

    // ------------------------------------------------------------------
    // 5) Altyazı
    // ------------------------------------------------------------------

    private suspend fun emitSubtitles(html: String?, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (html.isNullOrBlank()) return
        for ((lang, url) in TrAnimeIzleSite.subtitleTracks(html, base)) {
            if (seenSubs.add(url)) subtitleCallback(newSubtitleFile(lang, url))
        }
    }

    // ------------------------------------------------------------------
    // Yardımcılar
    // ------------------------------------------------------------------

    /**
     * Sınırlı eşzamanlılık + toplam süre bütçesi. Tek bir yavaş host
     * tüm listeyi kilitlemez; bütçe dolunca o ana kadar çözülenler geçerlidir.
     */
    private suspend fun <T> runBounded(
        items: List<T>,
        concurrency: Int,
        budgetMs: Long,
        block: suspend (T) -> Unit
    ) {
        if (items.isEmpty()) return
        val gate = Semaphore(concurrency)
        withTimeoutOrNull(budgetMs) {
            coroutineScope {
                items.map { item ->
                    async {
                        gate.withPermit {
                            try {
                                block(item)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.d(TAG, "çözümleme hatası: ${e.javaClass.simpleName}: ${e.message}")
                            }
                        }
                    }
                }.awaitAll()
            }
        }
    }

    private fun normalize(raw: String): String {
        var url = raw.trim()
        if (url.isEmpty()) return ""
        if (url.startsWith("//")) url = "https:$url"
        if (!url.startsWith("http")) return ""
        return url.replace("\\/", "/").replace("&amp;", "&")
    }

    private fun decode(raw: String): String = try {
        URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        raw
    }

    private fun isDirectMedia(url: String): Boolean = TrAnimeIzleSite.isDirectMedia(url)

    private fun looksLikeCaptcha(body: String): Boolean {
        val lower = body.lowercase()
        return lower.contains("captcha") || lower.contains("guvenlik dogrulama") ||
            lower.contains("güvenlik doğrulama")
    }

    companion object {
        private const val TAG = "kraptor_TrAnimeStreams"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

        /** Tüm kaynak araması için üst sınır — kullanıcı bu süreden fazla beklemez. */
        private const val TOTAL_BUDGET_MS = 35_000L

        /** Tek bir kaynak butonunun çözüm bütçesi. */
        private const val SOURCE_BUDGET_MS = 20_000L
    }
}

/**
 * Akış zincirinin ihtiyaç duyduğu HTTP yüzeyi.
 *
 * Eklenti sınıfı (`TrAnimeIzle`) bunu `MainAPI.app` + tarayıcı başlıklarıyla
 * uygular; böylece bu dosya uygulama nesnesine bağlı kalmadan test edilebilir.
 */
internal interface TrStreamHttp {

    /** Belge (GET). */
    suspend fun get(url: String, referer: String? = null): String?

    /** XHR GET. */
    suspend fun getXhr(url: String, referer: String? = null): String?

    /** JSON gövdeli POST (`Content-Type: application/json`). */
    suspend fun postJson(url: String, json: String, referer: String? = null): String?

    /** Gövdesiz POST (sitenin oynatıcı uçları böyle çalışır). */
    suspend fun postEmpty(url: String, referer: String? = null): String?

    /** Form gövdeli POST (captcha uçları). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null
    ): String?

    /** Ham bayt (captcha görselleri). */
    suspend fun bytes(url: String, referer: String? = null): ByteArray?

    /**
     * Sitenin `/api/Captcha` doğrulamasını çözer (en farklı görselin hash'i).
     * Gerekmedikçe çağrılmaz; başarısız olursa sessizce döner.
     */
    suspend fun solveCaptcha(): Boolean
}
