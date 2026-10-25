package com.hexated

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
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.util.Collections

/**
 * Anizm akış çözücüsü — **tek, deterministik zincir** (v5).
 *
 * Sitenin kendi oynatıcı yolu (Aniyomi `src/tr/anizm`, 25.10.2026 sürümü):
 *
 *   1. Bölüm sayfası            → `div#fansec > a[translator]`  (metin = fansub adı)
 *   2. translator adresi (GET)  → `{"data":"<html>"}`
 *                                 → `a.videoPlayerButtons[video]` (metin = host adı)
 *   3. `/video/{id}` → `/player/{id}` → **302 Location** = gerçek host adresi
 *   4. host adresi              → uygulama extractor'ları (filemoon, sibnet, voe,
 *                                 sendvid, mp4upload, ok.ru, streamtape, dood,
 *                                 uqload, yourupload, gdriveplayer…)
 *   5. `anizmplayer.com`        → `POST /player/index.php?data=<hash>&do=getVideo`
 *                                 → `securedLink` (HLS)
 *
 * Eski sürüm her kaynağı **sırayla** çözüyordu (kaynak başına 2–3 istek, art arda);
 * 10 kaynaklı bir bölüm 30+ saniye sürüyor ve uygulama zaman aşımına düşüyordu.
 * Bu sürüm hepsini paralel çözer, sonuçları çözüldükçe yayınlar ve HLS master
 * listelerini kalite varyantlarına ayırır.
 */
internal class AnizmStreams(
    private val pluginName: String,
    private val base: () -> String,
    private val http: AnizmHttp
) {

    private data class Source(val fansub: String, val host: String, val playerUrl: String)

    private val emitted: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val seenSubs: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
    private val emitLock = Mutex()

    @Volatile
    private var emittedCount: Int = 0

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
        emitSubtitles(html, pageUrl, subtitleCallback)
        val doc = Jsoup.parse(html, pageUrl)

        val translators = collectTranslators(doc, pageUrl)
        Log.d(TAG, "çevirmen listesi: ${translators.size}")

        val sources = if (translators.isEmpty()) {
            pageLevelSources(doc, html, pageUrl)
        } else {
            val gate = Semaphore(4)
            coroutineScope {
                translators.map { (fansub, url) ->
                    async {
                        gate.withPermit {
                            try {
                                fetchTranslatorSources(fansub, url, pageUrl)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.w(TAG, "çevirmen '$fansub' kaynakları alınamadı: ${e.message}")
                                emptyList()
                            }
                        }
                    }
                }.awaitAll().flatten()
            }.distinctBy { it.playerUrl }
        }
        Log.d(TAG, "oynatıcı: ${sources.size}")

        if (sources.isEmpty()) return emittedCount

        runBounded(sources, concurrency = 8, budgetMs = TOTAL_BUDGET_MS) { source ->
            resolveSource(source, pageUrl, subtitleCallback, callback)
        }
        Log.d(TAG, "toplam ${emittedCount} akış yayınlandı")
        return emittedCount
    }

    // ------------------------------------------------------------------
    // 1) Çevirmen → oynatıcı butonları
    // ------------------------------------------------------------------

    /** `div#fansec > a[translator]` (birincil) + eski kuşak seçiciler. */
    private fun collectTranslators(doc: org.jsoup.nodes.Document, pageUrl: String): List<Pair<String, String>> {
        val out = LinkedHashMap<String, String>()
        val anchors = doc.select(
            "div#fansec > a[translator], div#fansec a[translator], " +
                "div.episodeTranslators a[translator], a[translator]"
        )
        for (anchor in anchors) {
            val url = AnizmSite.absolute(pageUrl, anchor.attr("translator")) ?: continue
            if (!url.startsWith("http")) continue
            val name = anchor.text().trim().ifBlank { "Çevirmen" }
            out.putIfAbsent(url, name)
        }
        return out.map { (url, name) -> name to url }
    }

    /**
     * Çevirmenin kaynak listesi: `{"data": "<html>"}` → `a.videoPlayerButtons[video]`.
     * v3 API'si (`[data-sources]` → `videos[]`) da desteklenir.
     */
    private suspend fun fetchTranslatorSources(
        fansub: String,
        translatorUrl: String,
        pageUrl: String
    ): List<Source> {
        val body = http.get(translatorUrl, pageUrl) ?: return emptyList()
        val dataHtml = try {
            val root = JSONObject(body)
            root.optString("data").ifBlank { body }
        } catch (_: Exception) {
            body
        }
        if (dataHtml.isBlank()) return emptyList()

        val out = mutableListOf<Source>()
        val buttons = Jsoup.parse(dataHtml, translatorUrl)
            .select("a.videoPlayerButtons, a[video], a[data-video]")
        for (button in buttons) {
            val raw = button.attr("video").ifBlank { button.attr("data-video") }
            val absolute = AnizmSite.absolute(translatorUrl, raw) ?: continue
            if (!absolute.startsWith("http")) continue
            val host = button.text().trim().ifBlank { "Kaynak" }
            // Site oynatıcıyı /video/{id} → /player/{id} yönlendirmesiyle açıyor.
            val playerUrl = when {
                absolute.contains("/video/") -> absolute.replace("/video/", "/player/")
                else -> absolute
            }
            out.add(Source(fansub, host, playerUrl))
        }
        if (out.isNotEmpty()) return out

        // v3: {success, videos:[{id,name}]} → /video/{id} → {url}
        return try {
            val videos = JSONObject(body).optJSONArray("videos") ?: JSONArray()
            for (index in 0 until videos.length()) {
                val video = videos.optJSONObject(index) ?: continue
                val id = video.optInt("id", 0)
                if (id <= 0) continue
                val host = video.optString("name").ifBlank { "Kaynak ${index + 1}" }
                val videoUrl = translatorUrl.replace(Regex("/cevirmen/\\d+$"), "/video/$id")
                out.add(Source(fansub, host, videoUrl.replace("/video/", "/player/")))
            }
            out
        } catch (_: Exception) {
            out
        }
    }

    /** Sayfada hazır oynatıcı varsa (çevirmen listesi yoksa) doğrudan kullanılır. */
    private fun pageLevelSources(
        doc: org.jsoup.nodes.Document,
        html: String,
        pageUrl: String
    ): List<Source> {
        val out = mutableListOf<Source>()
        for (anchor in doc.select("a.videoPlayerButtons, a[video], a[data-video]")) {
            val raw = anchor.attr("video").ifBlank { anchor.attr("data-video") }
            val url = AnizmSite.absolute(pageUrl, raw) ?: continue
            out.add(Source("Sayfa", anchor.text().trim().ifBlank { "Kaynak" }, url))
        }
        for (frame in doc.select("iframe[src], iframe[data-src]")) {
            val url = AnizmSite.absolute(pageUrl, frame.attr("src").ifBlank { frame.attr("data-src") })
                ?: continue
            if (isAdHost(url)) continue
            out.add(Source("Sayfa", "Oynatıcı", url))
        }
        for (video in doc.select("video[src], video source[src]")) {
            val url = AnizmSite.absolute(pageUrl, video.attr("src")) ?: continue
            out.add(Source("Sayfa", "Doğrudan", url))
        }
        if (out.isEmpty()) {
            for (media in mediaFromHtml(html, pageUrl)) {
                out.add(Source("Sayfa", "Doğrudan", media))
            }
        }
        return out.distinctBy { it.playerUrl }
    }

    // ------------------------------------------------------------------
    // 2) Kaynak → akış
    // ------------------------------------------------------------------

    private suspend fun resolveSource(
        source: Source,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = buildLabel(source)
        var target: String? = null

        // /player/{id} → 302 Location = gerçek host adresi (sitelerin kendi yolu).
        if (source.playerUrl.contains("/player/") || source.playerUrl.contains("/video/")) {
            target = http.location(source.playerUrl, pageUrl)
            if (target != null && isAdHost(target)) target = null
        }
        if (target == null) target = source.playerUrl

        if (resolveTarget(target, pageUrl, label, subtitleCallback, callback, depth = 0)) return

        // Yönlendirme hedefi işe yaramadıysa oynatıcı sayfasının kendisi denenir.
        if (target != source.playerUrl) {
            resolveTarget(source.playerUrl, pageUrl, label, subtitleCallback, callback, depth = 1)
        }
    }

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

        if (isDirectMedia(url)) {
            emitMedia(url, label, referer, callback)
            return true
        }

        // Anizm'in kendi oynatıcısı (Aincrad / FirePlayer).
        val hash = anizmPlayerHash(url)
        if (hash != null && resolveAnizmPlayer(hash, url, label, callback)) return true

        // Google Drive → gdriveplayer köprüsü (site bu yolu kullanıyor).
        if (url.contains("drive.google") || url.contains("docs.google")) {
            val bridged = "https://gdriveplayer.to/embed2.php?link=" +
                java.net.URLEncoder.encode(url, "UTF-8")
            if (tryExtractors(bridged, referer, label, subtitleCallback, callback)) return true
        }

        if (tryExtractors(url, referer, label, subtitleCallback, callback)) return true
        if (depth >= 2) return false

        val page = http.get(url, referer) ?: return false
        emitSubtitles(page, url, subtitleCallback)

        var found = false
        for (stream in mediaFromHtml(page, url).take(4)) {
            emitMedia(stream, label, url, callback)
            found = true
        }
        if (found) return true

        val nested = Jsoup.parse(page, url)
            .select("iframe[src], iframe[data-src]")
            .mapNotNull { AnizmSite.absolute(url, it.attr("src").ifBlank { it.attr("data-src") }) }
            .filterNot { isAdHost(it) }
            .filter { it != url }
            .distinct()
        for (inner in nested.take(2)) {
            if (resolveTarget(inner, url, label, subtitleCallback, callback, depth + 1)) found = true
        }
        return found
    }

    private fun buildLabel(source: Source): String {
        val fansub = source.fansub.trim()
        val host = source.host.trim()
        return when {
            fansub.isEmpty() && host.isEmpty() -> "Kaynak"
            fansub.isEmpty() -> host
            host.isEmpty() -> fansub
            host.contains(fansub, ignoreCase = true) -> host
            else -> "$fansub • $host"
        }
    }

    // ------------------------------------------------------------------
    // anizmplayer.com (Aincrad)
    // ------------------------------------------------------------------

    private fun anizmPlayerHash(url: String): String? {
        if (!url.contains(PLAYER_HOST, ignoreCase = true)) return null
        return Regex("/(?:video|player|e)/([A-Za-z0-9_-]{6,64})").find(url)?.groupValues?.getOrNull(1)
    }

    /**
     * `POST https://anizmplayer.com/player/index.php?data=<hash>&do=getVideo`
     * (form: hash, r) → `securedLink` (HLS). Kaynak: Aniyomi `AincradExtractor`.
     */
    private suspend fun resolveAnizmPlayer(
        hash: String,
        playerUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val body = http.postForm(
            "$PLAYER_HOST/player/index.php?data=$hash&do=getVideo",
            mapOf("hash" to hash, "r" to (base().trimEnd('/') + "/")),
            playerUrl
        )
        if (body.isNullOrBlank()) {
            Log.d(TAG, "anizmplayer yanıt yok: $hash")
            return false
        }
        var found = false
        try {
            val json = JSONObject(body)
            val secured = json.optString("securedLink").ifBlank { json.optString("videoSource") }
            if (secured.isNotBlank()) {
                emitMedia(secured, label, playerUrl, callback)
                found = true
            }
            val sources = json.optJSONArray("videoSources")
            if (sources != null) {
                for (index in 0 until sources.length()) {
                    val item = sources.optJSONObject(index) ?: continue
                    val file = item.optString("file").ifBlank { item.optString("src") }
                    if (file.isBlank()) continue
                    val quality = item.optString("label").ifBlank { item.optString("quality") }
                    emitMedia(file, if (quality.isBlank()) label else "$label $quality", playerUrl, callback)
                    found = true
                }
            }
        } catch (_: Exception) {
            // JSON değil: gövdenin içindeki m3u8/mp4 adresleri.
        }
        if (!found) {
            for (stream in mediaFromHtml(body, playerUrl).take(3)) {
                emitMedia(stream, label, playerUrl, callback)
                found = true
            }
        }
        if (!found) Log.d(TAG, "anizmplayer akış vermedi ($hash)")
        return found
    }

    // ------------------------------------------------------------------
    // Yayınlama
    // ------------------------------------------------------------------

    private suspend fun tryExtractors(
        url: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val bag = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        try {
            loadExtractor(url, referer, subtitleCallback) { link -> bag.add(link) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "loadExtractor hatası (${url.take(90)}): ${e.message}")
        }
        if (bag.isEmpty()) return false
        for (link in bag) {
            emitLink(link.url, label, referer, link.type, link.quality, link.headers, callback)
        }
        return true
    }

    private suspend fun emitMedia(
        rawUrl: String,
        label: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = normalize(AnizmSite.absolute(referer, rawUrl) ?: rawUrl)
        if (url.isEmpty()) return
        val isHls = url.substringBefore('?').lowercase().let { it.endsWith(".m3u8") || it.contains(".m3u8") }
        if (isHls) {
            val variants = try {
                withTimeoutOrNull(10_000) {
                    M3u8Helper.generateM3u8(
                        pluginName,
                        url,
                        referer,
                        headers = streamHeaders(referer, url)
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
                    val qualityLabel = if (variant.quality in 144..4320) "$label ${variant.quality}p" else label
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
        emitLink(
            url,
            label,
            referer,
            if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
            null,
            null,
            callback
        )
    }

    private suspend fun emitLink(
        rawUrl: String,
        label: String,
        referer: String,
        type: ExtractorLinkType,
        quality: Int?,
        extraHeaders: Map<String, String>?,
        callback: (ExtractorLink) -> Unit
    ) {
        val url = normalize(rawUrl)
        if (url.isEmpty() || !emitted.add(url)) return
        val ref = referer.ifBlank { base() + "/" }
        val headers = streamHeaders(ref, url) + (extraHeaders ?: emptyMap())
        val link = newExtractorLink(
            source = pluginName,
            name = label.ifBlank { pluginName },
            url = url,
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

    /**
     * Akış isteklerinde gönderilen başlıklar. `Origin` gönderilmiyor:
     * bazı CDN'ler yabancı Origin'de 403 veriyor (referer yeterli).
     */
    private fun streamHeaders(referer: String, streamUrl: String): Map<String, String> = mapOf(
        "Referer" to referer,
        "User-Agent" to UA
    )

    // ------------------------------------------------------------------

    private suspend fun emitSubtitles(html: String?, base: String, subtitleCallback: (SubtitleFile) -> Unit) {
        if (html.isNullOrBlank()) return
        val doc = try {
            Jsoup.parse(html, base)
        } catch (_: Exception) {
            return
        }
        for (track in doc.select("track[src]")) {
            val kind = track.attr("kind").ifBlank { "subtitles" }
            if (!kind.equals("subtitles", true) && !kind.equals("captions", true)) continue
            val src = AnizmSite.absolute(base, track.attr("src")) ?: continue
            val lang = track.attr("srclabel").ifBlank { track.attr("label") }
                .ifBlank { track.attr("srclang") }
                .ifBlank { "Altyazı" }
            if (seenSubs.add(src)) subtitleCallback(newSubtitleFile(lang, src))
        }
    }

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

    private fun isDirectMedia(url: String): Boolean {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd") ||
            path.endsWith(".mkv") || path.endsWith(".webm")
    }

    private fun isAdHost(url: String): Boolean {
        val lower = url.lowercase()
        return AD_HOSTS.any { lower.contains(it) }
    }

    /** HTML/JS içindeki m3u8/mp4/mpd adresleri (kaçışlı `\/` biçimi dahil). */
    private fun mediaFromHtml(html: String, base: String): List<String> {
        if (html.isBlank()) return emptyList()
        val unescaped = html.replace("\\/", "/").replace("\\u002F", "/").replace("&amp;", "&")
        val found = LinkedHashSet<String>()
        val patterns = listOf(
            Regex("""["'](https?://[^"']+?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source|hls)\s*[:=]\s*["']([^"']+?\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+?\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|src|source)\s*[:=]\s*["']([^"']+?\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["'](https?://[^"']+?\.mpd[^"']*)["']""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            for (match in pattern.findAll(unescaped)) {
                val raw = match.groupValues.lastOrNull()?.trim().orEmpty()
                if (raw.isEmpty() || raw.startsWith("data:") || raw.startsWith("blob:")) continue
                val absolute = normalize(AnizmSite.absolute(base, raw) ?: raw)
                if (absolute.isEmpty() || isAdHost(absolute)) continue
                found.add(absolute)
                if (found.size >= 6) return found.toList()
            }
        }
        return found.toList()
    }

    companion object {
        private const val TAG = "hexated_AnizmStreams"
        private const val PLAYER_HOST = "https://anizmplayer.com"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        private const val TOTAL_BUDGET_MS = 35_000L

        /** Reklam/analitik host işaretleri — oynatıcı adayı olarak alınmaz. */
        private val AD_HOSTS = listOf(
            "doubleclick", "googlesyndication", "adsterra", "popads", "popcash",
            "propellerads", "onclickads", "adservice", "juicyads", "exoclick",
            "hilltopads", "clickadu", "mgid.com", "adtng", "tsyndicate",
            "trafficjunky", "betweendigital", "reklam", "google-analytics",
            "googletagmanager", "facebook.net", "hotjar"
        )
    }
}

/**
 * Anizm akış zincirinin HTTP yüzeyi (eklenti sınıfı `MainAPI.app` ile uygular).
 */
internal interface AnizmHttp {

    /** Belge GET. */
    suspend fun get(url: String, referer: String? = null): String?

    /** Yönlendirme İZLENMEDEN GET → `Location` başlığı (302 hedefi). */
    suspend fun location(url: String, referer: String? = null): String?

    /** Form gövdeli POST (anizmplayer). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null
    ): String?
}
