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
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Collections

/**
 * Anizm akış çözücüsü — **v3 API + eski kuşak yedekleri** (v6).
 *
 * Sitenin 2026-10 itibarıyla canlı zinciri (kanıt: canlı bölüm sayfası HTML'i,
 * `button[data-v3-translator]` / `.v3-translator-button[data-sources]`):
 *
 *   1. Bölüm sayfası  → `button[data-v3-translator][data-sources]`
 *                       `data-sources = /bolum/{bolumId}/cevirmen/{cevirmenId}`
 *                       (aynı sayfada hazır `button[data-video][data-url]` de olabilir)
 *   2. GET {data-sources} (XHR) → JSON `{videos:[{id,name}]}` **veya** `{data:"<html>"}`
 *                                 → `a[video]` / `button[data-video]` etiketleri
 *   3. Her video: `{sourcesUrl}` içindeki `/cevirmen/{id}` → `/video/{vid}`
 *                 → GET (XHR) → JSON `{url, player}`; `player` bir iframe HTML'i olabilir
 *   4. Oynatıcı → `anizmplayer.com/video/<hash>` → `POST /player/index.php?data=<hash>&do=getVideo`
 *                 → `securedLink` (HLS master → varyantlar)
 *   5. Çözülemeyen host → uygulamanın 325 kayıtlı extractor'ı → host sayfası taraması
 *
 * Eski kuşak (`div#fansec a[translator]` → `{data:html}` → `a.videoPlayerButtons`)
 * ve sayfa düzeyi oynatıcılar da desteklenir; tema/kuşak değişse bile kaynak gelir.
 *
 * Hız: kaynaklar PARALEL çözülür (semaphore + istek zaman aşımı + toplam bütçe),
 * sonuçlar çözüldükçe yayınlanır. Hiçbir adım istisna fırlatmaz.
 */
internal class AnizmStreams(
    private val pluginName: String,
    private val base: () -> String,
    private val http: AnizmHttp
) {

    /** Çözülecek oynatıcı adayı. */
    private data class Source(val fansub: String, val host: String, val url: String)

    /** Bir çevirmen (fansub) düğümü. */
    private data class Translator(val url: String, val name: String, val active: Boolean)

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
        val site = base().trimEnd('/')
        val referer = "$site/"
        val html = http.get(pageUrl, referer)
        if (html.isNullOrBlank()) {
            Log.w(TAG, "bölüm sayfası alınamadı: $pageUrl")
            return 0
        }
        emitSubtitles(html, pageUrl, subtitleCallback)
        val doc = Jsoup.parse(html, pageUrl)

        // 1) v3 çevirmen butonları → kaynak listeleri → video adresleri
        var primary = collectV3(doc, pageUrl, site)
        // 2) eski kuşak: a[translator] → {data:html} → a[video]
        var stage = "v3"
        if (primary.isEmpty()) {
            primary = collectV2(doc, pageUrl)
            stage = "v2"
        }
        // 3) çevirmen dizini bölüm kimliğinden yeniden kurulur (/bolum/{id})
        if (primary.isEmpty()) {
            primary = collectBolumFallback(doc, html, pageUrl, site)
            stage = "bolum"
        }
        // 4) sayfa düzeyi hazır oynatıcılar (her zaman eklenir)
        val pageSources = pageLevelSources(doc, html, pageUrl, site)

        val all = (primary + pageSources).distinctBy { it.url }
        Log.d(TAG, "aday: $stage=${primary.size} sayfa=${pageSources.size} tekil=${all.size}")

        if (all.isEmpty()) return emittedCount

        runBounded(all, concurrency = 8, budgetMs = TOTAL_BUDGET_MS) { source ->
            resolveSource(source, pageUrl, subtitleCallback, callback)
        }
        Log.d(TAG, "toplam $emittedCount akış yayınlandı")
        return emittedCount
    }

    // ------------------------------------------------------------------
    // 1) v3: data-v3-translator / data-sources
    // ------------------------------------------------------------------

    private suspend fun collectV3(
        doc: org.jsoup.nodes.Document,
        pageUrl: String,
        site: String
    ): List<Source> {
        val translators = translatorButtons(doc, pageUrl)
        Log.d(TAG, "v3 çevirmen: ${translators.size}")

        // Sayfada hazır video adresleri (aktif çevirmen için sunucu tarafı render).
        val preResolved = doc.select("[data-video][data-url], [video], [data-video]")
            .mapNotNull { el ->
                val raw = el.attr("data-url").ifBlank { el.attr("video") }
                val url = AnizmSite.absolute(pageUrl, raw) ?: return@mapNotNull null
                if (!url.contains("/video/") && !isDirectMedia(url)) return@mapNotNull null
                val name = el.attr("data-video-name").ifBlank { el.text() }.trim()
                Source("Sayfa", name.ifBlank { "Oynatıcı" }, url)
            }
            .distinctBy { it.url }

        val out = Collections.synchronizedList(mutableListOf<Source>())
        if (translators.isNotEmpty()) {
            val gate = Semaphore(4)
            coroutineScope {
                translators.values.map { translator ->
                    async {
                        gate.withPermit {
                            try {
                                fetchV3Translator(translator.url, translator.name, pageUrl)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                Log.d(TAG, "v3 çevirmen hatası (${translator.name}): ${e.message}")
                                emptyList()
                            }
                        }
                    }
                }.awaitAll().forEach { out.addAll(it) }
            }
        }

        // Aktif çevirmenin hazır adresleri + API'den gelenler birleşir.
        val active = translators.values.firstOrNull { it.active }
        val pre = preResolved.map { it.copy(fansub = active?.name ?: it.fansub) }
        val merged = (out.toList() + pre).distinctBy { it.url }
        if (merged.isNotEmpty()) return merged

        // Hiçbiri çalışmadıysa yine de sayfadaki hazır adresleri dene.
        return preResolved
    }

    /**
     * Çevirmen düğümlerini bulur. Canlı sayfada `data-sources` **boş** olabiliyor
     * (JS dolduruyor) veya JSON/çoklu adres taşıyabiliyor; düğümdeki `data-id`
     * (çevirmen kimliği) ve sayfadaki bölüm kimliğinden adres yeniden kurulur:
     * `/bolum/{bolumId}/cevirmen/{cevirmenId}`.
     */
    private fun translatorButtons(
        doc: org.jsoup.nodes.Document,
        pageUrl: String
    ): LinkedHashMap<String, Translator> {
        val out = LinkedHashMap<String, Translator>()
        val bolumId = doc.select("a[href*=/bolum/], a[href*=/episode/], a[href*=/raporver/]")
            .mapNotNull { bolumIdOf(it.attr("href")) }
            .firstOrNull()
            ?: bolumIdOf(pageUrl)
        // `[translator]` HER etikette olabilir (a/button/div) — tag'e bağlanma.
        for (el in doc.select("[data-sources], [data-v3-translator], [data-translator], [translator]")) {
            val name = el.attr("title").ifBlank { el.attr("data-fansub-name") }
                .ifBlank { el.attr("data-name") }
                .ifBlank { el.text() }
                .trim().replace(Regex("\\s+"), " ")
                .ifBlank { "Çevirmen" }
            val active = el.attr("aria-pressed").equals("true", true) ||
                el.classNames().any { it.contains("active", true) }
            val raw = el.attr("data-sources").ifBlank { el.attr("data-v3-translator") }
                .ifBlank { el.attr("data-translator") }
                .ifBlank { el.attr("translator") }
            for (candidate in sourceUrlsOf(raw, el, bolumId, pageUrl)) {
                out.putIfAbsent(candidate, Translator(candidate, name, active))
            }
        }
        return out
    }

    /** Bölüm kimliği: `/bolum/{id}` (v3) veya `/episode/{id}` (eski kuşak). */
    private fun bolumIdOf(text: String): String? =
        Regex("/(?:bolum|episode)/(\\d+)").find(text)?.groupValues?.getOrNull(1)

    /**
     * Aynı çevirmenin iki olası rota biçimi (v3: `/bolum/…/cevirmen/…`,
     * eski kuşak: `/episode/…/translator/…`). Tercih edilen rota önce gelir.
     */
    private fun translatorPaths(bolumId: String, cid: String, preferEpisode: Boolean = false): List<String> {
        val bolum = "/bolum/$bolumId/cevirmen/$cid"
        val episode = "/episode/$bolumId/translator/$cid"
        return if (preferEpisode) listOf(episode, bolum) else listOf(bolum, episode)
    }

    /** Bir çevirmen düğümünün taşıyabileceği tüm adres biçimlerini çözer. */
    private fun sourceUrlsOf(
        raw: String,
        el: org.jsoup.nodes.Element,
        bolumId: String?,
        pageUrl: String
    ): List<String> {
        val value = raw.trim()
        val out = LinkedHashSet<String>()
        if (value.startsWith("[") || value.startsWith("{")) {
            // JSON: dizi, nesne veya `{"url": "…"}` — tüm http adreslerini topla.
            for (match in Regex("""https?://[^"'\s\\]+""").findAll(value.replace("\\/", "/"))) {
                out.add(match.value)
            }
            for (match in Regex("/[a-z0-9/_-]+", RegexOption.IGNORE_CASE).findAll(value)) {
                AnizmSite.absolute(pageUrl, match.value)?.let { out.add(it) }
            }
        } else if (value.isNotEmpty()) {
            for (part in value.split(',', ' ', '|', ';')) {
                if (part.isBlank()) continue
                val absolute = AnizmSite.absolute(pageUrl, part.trim())
                if (absolute != null) out.add(absolute)
            }
        }
        // Düğümde yalnızca çevirmen kimliği varsa adresi kendimiz kurarız.
        if (out.isEmpty() && bolumId != null) {
            val cid = el.attr("data-id").ifBlank { el.attr("data-cid") }
                .ifBlank { el.attr("data-translator-id") }
                .filter { it.isDigit() }
            val candidates = if (cid.isNotEmpty()) listOf(cid) else listOf("1", "2", "3")
            val preferEpisode = pageUrl.contains("/episode/")
            for (id in candidates) {
                val path = translatorPaths(bolumId, id, preferEpisode).first()
                out.add(AnizmSite.absolute(pageUrl, path) ?: continue)
            }
        }
        return out.filter { it.startsWith("http") }
    }

    /**
     * Son çare: bölüm sayfası çevirmen düğümlerini sunmadıysa `/bolum/{id}`
     * dizini okunur; o da boşsa `/bolum/{id}/cevirmen/1..3` doğrudan denenir.
     */
    private suspend fun collectBolumFallback(
        doc: org.jsoup.nodes.Document,
        html: String,
        pageUrl: String,
        site: String
    ): List<Source> {
        val bolumId = Regex("/(?:bolum|episode)/(\\d+)").find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""EpisodeId"?\s*[:=]\s*"?(\d{3,7})""").find(html)?.groupValues?.getOrNull(1)
            ?: return emptyList()
        Log.d(TAG, "bolum yedeği: id=$bolumId")

        val directory = http.get("$site/bolum/$bolumId", "$site/")
        if (!directory.isNullOrBlank()) {
            val directoryDoc = runCatching { Jsoup.parse(directory, "$site/bolum/$bolumId") }.getOrNull()
            if (directoryDoc != null) {
                val translators = translatorButtons(directoryDoc, "$site/bolum/$bolumId")
                if (translators.isNotEmpty()) {
                    val out = Collections.synchronizedList(mutableListOf<Source>())
                    coroutineScope {
                        translators.values.map { translator ->
                            async {
                                try {
                                    fetchV3Translator(translator.url, translator.name, pageUrl)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    emptyList()
                                }
                            }
                        }.awaitAll().forEach { out.addAll(it) }
                    }
                    if (out.isNotEmpty()) return out.toList().distinctBy { it.url }
                }
            }
        }

        val out = Collections.synchronizedList(mutableListOf<Source>())
        val attempts = (1..3).flatMap { id ->
            translatorPaths(bolumId, id.toString()).map { "$site$it" to id }
        }
        coroutineScope {
            attempts.map { (url, index) ->
                async {
                    try {
                        out.addAll(fetchV3Translator(url, "Çevirmen $index", pageUrl))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // o çevirmen/rota yok
                    }
                }
            }.awaitAll()
        }
        return out.toList().distinctBy { it.url }
    }

    /** `{videos:[{id,name}]}` veya `{data:"<html>"}` → video/oynatıcı adresleri. */
    private suspend fun fetchV3Translator(
        sourcesUrl: String,
        fansub: String,
        pageUrl: String
    ): List<Source> {
        val body = http.getXhr(sourcesUrl, pageUrl) ?: return emptyList()
        val out = mutableListOf<Source>()

        // A) JSON: videos[] / data / items
        val parsed = runCatching { JSONObject(body.trim()) }.getOrNull()
        if (parsed != null) {
            val videos = parsed.optJSONArray("videos")
                ?: parsed.optJSONArray("items")
                ?: parsed.optJSONArray("data")
            if (videos != null) {
                for (index in 0 until videos.length()) {
                    val item = videos.optJSONObject(index) ?: continue
                    val id = item.optString("id").ifBlank { item.optInt("id", 0).toString() }
                    if (id.isBlank() || id == "0") continue
                    val host = item.optString("name").ifBlank { item.optString("host") }
                        .ifBlank { "Kaynak ${index + 1}" }
                    val videoUrl = if (item.optString("url").startsWith("http")) {
                        item.optString("url")
                    } else {
                        videoUrlFrom(sourcesUrl, id)
                    }
                    out.add(Source(fansub, host, videoUrl))
                }
            }
            val html = parsed.optString("data").ifBlank { parsed.optString("html") }
            if (html.contains('<')) out.addAll(parsePlayerTags(html, sourcesUrl, fansub))
            if (out.isNotEmpty()) return out.distinctBy { it.url }
        }

        // B) HTML veya JSON içine gömülü HTML: a[video] / button[data-video]
        if (body.contains('<')) {
            out.addAll(parsePlayerTags(body, sourcesUrl, fansub))
        }
        return out.distinctBy { it.url }
    }

    private fun parsePlayerTags(html: String, baseUrl: String, fansub: String): List<Source> {
        val doc = runCatching { Jsoup.parse(html, baseUrl) }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Source>()
        for (el in doc.select("[video], [data-video], [data-url]")) {
            val raw = el.attr("video").ifBlank { el.attr("data-video") }
                .ifBlank { el.attr("data-url") }
            val url = AnizmSite.absolute(baseUrl, raw) ?: continue
            if (!url.startsWith("http")) continue
            val host = el.attr("data-video-name").ifBlank { el.text() }.trim()
                .replace(Regex("\\s+"), " ")
            out.add(Source(fansub, host.ifBlank { "Oynatıcı" }, url))
        }
        return out.distinctBy { it.url }
    }

    /**
     * `…/bolum/{ep}/cevirmen/{cid}` + video id → `…/video/{vid}`.
     * (Canlı istemci kanıtı: Migurdex `AnizmProvider`; site kendi JS'inde de böyle.)
     */
    private fun videoUrlFrom(sourcesUrl: String, videoId: String): String {
        val replaced = Regex("/cevirmen/\\d+/?$").replace(sourcesUrl, "/video/$videoId")
        return if (replaced != sourcesUrl) replaced else sourcesUrl.trimEnd('/') + "/video/$videoId"
    }

    // ------------------------------------------------------------------
    // 2) Eski kuşak: a[translator]
    // ------------------------------------------------------------------

    private suspend fun collectV2(doc: org.jsoup.nodes.Document, pageUrl: String): List<Source> {
        val anchors = doc.select("div#fansec [translator], [translator]")
        if (anchors.isEmpty()) return emptyList()
        val out = mutableListOf<Source>()
        for (anchor in anchors) {
            val url = AnizmSite.absolute(pageUrl, anchor.attr("translator")) ?: continue
            val fansub = anchor.text().trim().ifBlank { "Çevirmen" }
            val body = http.getXhr(url, pageUrl) ?: continue
            val html = runCatching { JSONObject(body).optString("data") }.getOrDefault("")
                .ifBlank { body }
            out.addAll(parsePlayerTags(html, url, fansub))
        }
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // 3) Sayfa düzeyi
    // ------------------------------------------------------------------

    private fun pageLevelSources(
        doc: org.jsoup.nodes.Document,
        html: String,
        pageUrl: String,
        site: String
    ): List<Source> {
        val out = mutableListOf<Source>()
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
            for (media in mediaFromHtml(html, pageUrl).take(3)) {
                out.add(Source("Sayfa", "Doğrudan", media))
            }
        }
        // anizmplayer hash'i doğrudan sayfada varsa
        val hash = Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})")
            .find(html)?.groupValues?.getOrNull(1)
            ?: Regex("""index\\.php\\?data=([a-f0-9]{32})""", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.getOrNull(1)
        if (hash != null) out.add(Source("Sayfa", "AnizmPlayer", "$PLAYER_HOST/video/$hash"))
        return out.distinctBy { it.url }
    }

    // ------------------------------------------------------------------
    // Aday → akış
    // ------------------------------------------------------------------

    private suspend fun resolveSource(
        source: Source,
        pageUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val label = buildLabel(source)
        val url = source.url
        if (url.isBlank()) return

        // /video/{id} → JSON {url, player} (XHR), sonra oynatıcı.
        if (url.contains("/video/") && !url.contains(PLAYER_HOST)) {
            val target = videoTargetFrom(url, pageUrl)
            if (target != null && target != url) {
                if (resolveTarget(target, pageUrl, label, subtitleCallback, callback, depth = 0)) return
            }
        }
        if (resolveTarget(url, pageUrl, label, subtitleCallback, callback, depth = 0)) return
        // /video/{id} oynatıcı sayfasına yönlenmiyorsa doğrudan /player/{id} denenir.
        if (url.contains("/video/")) {
            val player = url.replace("/video/", "/player/")
            resolveTarget(player, pageUrl, label, subtitleCallback, callback, depth = 1)
        }
    }

    /** `/video/{id}` sayfasının JSON cevabından oynatıcı adresi. */
    private suspend fun videoTargetFrom(videoUrl: String, pageUrl: String): String? {
        val body = http.getXhr(videoUrl, pageUrl) ?: return null
        val json = runCatching { JSONObject(body.trim()) }.getOrNull()
        if (json != null) {
            val playerHtml = json.optString("player")
            if (playerHtml.contains('<')) {
                val frame = Jsoup.parse(playerHtml, videoUrl).selectFirst("iframe[src], iframe[data-src]")
                val src = frame?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                AnizmSite.absolute(videoUrl, src)?.let { return it }
            }
            for (key in listOf("url", "player", "src", "link")) {
                val value = json.optString(key)
                if (value.startsWith("http") || value.startsWith("//")) {
                    return AnizmSite.absolute(videoUrl, value)
                }
            }
        }
        // JSON değilse gövdenin içindeki iframe/hash.
        val doc = runCatching { Jsoup.parse(body, videoUrl) }.getOrNull()
        val frame = doc?.selectFirst("iframe[src], iframe[data-src]")
        val src = frame?.let { it.attr("src").ifBlank { it.attr("data-src") } }
        if (!src.isNullOrBlank()) return AnizmSite.absolute(videoUrl, src)
        val hash = Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})")
            .find(body)?.groupValues?.getOrNull(1)
        return if (hash != null) "$PLAYER_HOST/video/$hash" else null
    }

    private suspend fun resolveTarget(
        rawUrl: String,
        referer: String,
        label: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        val url = normalize(AnizmSite.absolute(referer, rawUrl) ?: rawUrl)
        if (url.isEmpty()) return false

        if (isDirectMedia(url)) {
            emitMedia(url, label, referer, callback)
            return true
        }

        val hash = anizmPlayerHash(url)
        if (hash != null && resolveAnizmPlayer(hash, url, label, callback)) return true

        // Site içi /player/{id} → 302 Location hedefi.
        if (url.contains("/player/")) {
            val location = http.location(url, referer)
            if (location != null && location != url && !isAdHost(location)) {
                if (resolveTarget(location, referer, label, subtitleCallback, callback, depth)) return true
            }
        }

        // Host'a özel yerel çözücüler (extractor katmanı bozulsa da çalışır).
        if (isOkRu(url) && resolveOkRu(url, label, callback)) return true
        if (isSibnet(url) && resolveSibnet(url, label, callback)) return true

        if (url.contains("drive.google") || url.contains("docs.google")) {
            val bridged = "https://gdriveplayer.to/embed2.php?link=" +
                URLEncoder.encode(url, "UTF-8")
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

        val innerHash = Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]{8,64})")
            .find(page)?.groupValues?.getOrNull(1)
        if (innerHash != null && resolveAnizmPlayer(innerHash, url, label, callback)) return true

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

    // ------------------------------------------------------------------
    // Yerel host çözücüleri (ok.ru, sibnet)
    // ------------------------------------------------------------------

    private fun isOkRu(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")
        return host.endsWith("ok.ru") || host.endsWith("odnoklassniki.ru")
    }

    private fun isSibnet(url: String): Boolean =
        url.contains("sibnet.ru", ignoreCase = true)

    /**
     * ok.ru: `metadataUrl` → `{videos:[{name,url}], hlsManifestUrl}`.
     * (Tarayıcıdaki `postMessage`/`flashvars` yolunun HTTP karşılığı.)
     */
    private suspend fun resolveOkRu(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:videoembed|video)/(\d{6,})""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        val body = http.get("https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$id", "$OKRU/") ?: return false
        val json = runCatching { JSONObject(body.trim()) }.getOrNull() ?: return false
        var found = false
        json.optString("hlsManifestUrl").takeIf { it.startsWith("http") }?.let { hls ->
            emitMedia(hls, label, "$OKRU/", callback)
            found = true
        }
        json.optJSONArray("videos")?.let { videos ->
            for (index in 0 until videos.length()) {
                val item = videos.optJSONObject(index) ?: continue
                val file = item.optString("url")
                if (!file.startsWith("http")) continue
                val name = item.optString("name")
                emitMedia(file, if (name.isBlank()) label else "$label • $name", "$OKRU/", callback)
                found = true
            }
        }
        return found
    }

    /** sibnet: `shell.php?videoid=<id>` → `player.src([{src:"/videos/…"}])`. */
    private suspend fun resolveSibnet(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val id = Regex("""(?:videoid=|/video)(\d{3,})""").find(url)?.groupValues?.getOrNull(1)
            ?: Regex("""/([A-Za-z0-9_-]{6,})/?$""").find(url)?.groupValues?.getOrNull(1)
            ?: return false
        val shell = if (url.contains("shell.php")) url else "$SIBNET/shell.php?videoid=$id"
        val body = http.get(shell, "$SIBNET/") ?: return false
        val path = Regex("""player\.src\(\[\{src:\s*["']([^"']+)["']""").find(body)?.groupValues?.getOrNull(1)
            ?: Regex("""["'](/videos/[^"']+\.(?:mp4|m3u8)[^"']*)["']""").find(body)?.groupValues?.getOrNull(1)
            ?: return false
        emitMedia(AnizmSite.absolute("$SIBNET/", path) ?: path, label, "$SIBNET/", callback)
        return true
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
    // anizmplayer.com (Aincrad / FirePlayer)
    // ------------------------------------------------------------------

    private fun anizmPlayerHash(url: String): String? {
        if (!url.contains(PLAYER_HOST, ignoreCase = true)) return null
        return Regex("/(?:video|player|e)/([A-Za-z0-9_-]{6,64})").find(url)?.groupValues?.getOrNull(1)
    }

    /**
     * `POST https://anizmplayer.com/player/index.php?data=<hash>&do=getVideo`
     * (form: hash, r) → `securedLink`.
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
        val json = runCatching { JSONObject(body) }.getOrNull()
        if (json != null) {
            val secured = json.optString("securedLink").ifBlank { json.optString("videoSource") }
            if (secured.isNotBlank()) {
                emitMedia(secured, label, "$PLAYER_HOST/", callback)
                found = true
            }
            val array = json.optJSONArray("video")
                ?: json.optJSONArray("videoSources")
                ?: json.optJSONArray("sources")
            array?.let { sources ->
                for (index in 0 until sources.length()) {
                    val item = sources.optJSONObject(index) ?: continue
                    val file = item.optString("file").ifBlank { item.optString("src") }
                        .ifBlank { item.optString("url") }
                    if (file.isBlank()) continue
                    val quality = item.optString("label").ifBlank { item.optString("quality") }
                    emitMedia(
                        file,
                        if (quality.isBlank()) label else "$label • $quality",
                        "$PLAYER_HOST/",
                        callback
                    )
                    found = true
                }
            }
        }
        if (!found) {
            for (stream in mediaFromHtml(body, playerUrl).take(3)) {
                emitMedia(stream, label, playerUrl, callback)
                found = true
            }
        }
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
                withTimeoutOrNull(8_000) {
                    M3u8Helper.generateM3u8(
                        pluginName,
                        url,
                        referer,
                        headers = streamHeaders(referer)
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
        val headers = streamHeaders(ref) + (extraHeaders ?: emptyMap())
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

    private fun streamHeaders(referer: String): Map<String, String> = mapOf(
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
            val lang = track.attr("label").ifBlank { track.attr("srclang") }.ifBlank { "Altyazı" }
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
        private const val OKRU = "https://ok.ru"
        private const val SIBNET = "https://video.sibnet.ru"
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"
        private const val TOTAL_BUDGET_MS = 30_000L

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

    /** XHR GET (JSON uçları: `data-sources`, `/video/{id}`). */
    suspend fun getXhr(url: String, referer: String? = null): String?

    /** Yönlendirme İZLENMEDEN GET → `Location` başlığı (302 hedefi). */
    suspend fun location(url: String, referer: String? = null): String?

    /** Form gövdeli POST (anizmplayer). */
    suspend fun postForm(
        url: String,
        form: Map<String, String>,
        referer: String? = null
    ): String?
}
