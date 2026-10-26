package com.hexated

import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * **Tam kapsam doğrulaması (Anizm)** — `LIVE_PROBE=1` iken koşar.
 *
 * Amaç: "tek bir kaynak eksik gelmesin" iddiasını ÖLÇMEK.
 *
 * Akış:
 *   bölüm sayfası → TÜM çevirmenler → her çevirmenin TÜM videoları
 *   → her video için `/video/{id}` → `{url}` → `/player/{id}` → 302 `Location`
 *   → son adresin host'una göre eklentinin çözücü kapsamı
 *   → ve son adresin gerçekten oynatılabilir olduğunun HTTP kontrolü.
 *
 * Çıktı, kaynak kaynak bir tablo:  `[çevirmen] host → durum`.
 * Sonunda: `kapsanan/kaynak` sayacı ve KAPSANMAYAN host listesi.
 */
class FullSourceAuditTest {

    private val ua =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    private val candidates = listOf(
        "https://anizm.com.tr",
        "https://anizm.net",
        "https://www.puffytr.com"
    )

    /** Eklentinin AnizmStreams içinde yerel çözücüsü olan host'lar. */
    private val localResolvers = listOf(
        "anizmplayer.com", "drive.google", "docs.google", "ok.ru", "odnoklassniki",
        "sibnet"
    )

    /** Uygulamanın kayıtlı extractor'larıyla çözülen bilinen video host'ları. */
    private val extractorHosts = listOf(
        "filemoon", "mixdrop", "mp4upload", "sendvid", "streamlare", "streamtape",
        "voe.", "vudeo", "upstream", "hdvid", "hexload", "optraco", "luffytra",
        "yourupload", "uqload", "dood", "vidmoly", "luluvdo", "mega.nz", "mega.co",
        "mail.ru", "yandex", "bylt", "gofile", "abyssplayer", "playmogo", "sitemio",
        "dailymotion", "youtube", "vidcache", "ok.ru"
    )

    private fun p(line: String) = println("[AUDIT] $line")

    private class Res(val status: Int, val body: String?, val location: String?, val ctype: String?)

    private fun raw(
        url: String,
        referer: String? = null,
        xhr: Boolean = false,
        follow: Boolean = true,
        form: Map<String, String>? = null,
        extra: Map<String, String> = emptyMap(),
        method: String = "GET",
        range: String? = null,
        timeoutMs: Int = 20000
    ): Res {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = if (form != null) "POST" else method
                instanceFollowRedirects = follow
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", ua)
                setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
                setRequestProperty(
                    "Accept",
                    if (xhr) "application/json, text/plain, */*"
                    else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
                )
                setRequestProperty("Accept-Encoding", "gzip")
                if (xhr) setRequestProperty("X-Requested-With", "XMLHttpRequest")
                range?.let { setRequestProperty("Range", it) }
                referer?.let { setRequestProperty("Referer", it) }
                extra.forEach { (k, v) -> setRequestProperty(k, v) }
                if (form != null) {
                    doOutput = true
                    setRequestProperty(
                        "Content-Type",
                        "application/x-www-form-urlencoded; charset=UTF-8"
                    )
                }
            }
            if (form != null) {
                conn.outputStream.use { out ->
                    out.write(
                        form.entries.joinToString("&") {
                            "${it.key}=${java.net.URLEncoder.encode(it.value, "UTF-8")}"
                        }.toByteArray()
                    )
                }
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.let { input ->
                val bytes = input.readBytes()
                try {
                    if (conn.contentEncoding?.contains("gzip") == true) {
                        GZIPInputStream(bytes.inputStream()).readBytes().toString(Charsets.UTF_8)
                    } else {
                        bytes.toString(Charsets.UTF_8)
                    }
                } catch (e: Throwable) {
                    bytes.toString(Charsets.UTF_8)
                }
            }
            Res(status, body, conn.getHeaderField("Location"), conn.contentType)
        } catch (e: Throwable) {
            Res(0, null, null, null)
        } finally {
            conn?.disconnect()
        }
    }

    private fun hostOf(url: String): String =
        try {
            URL(url).host.lowercase()
        } catch (e: Throwable) {
            ""
        }

    private fun coverage(host: String): String = when {
        localResolvers.any { host.contains(it) } -> "YEREL"
        extractorHosts.any { host.contains(it) } -> "EXTRACTOR"
        else -> "KAPSANMIYOR"
    }

    /** Medya adresinin gerçekten oynatılabilir olduğunu HTTP ile doğrular. */
    private fun probePlayable(url: String, referer: String): String {
        val path = url.substringBefore('?').lowercase()
        val isHls = path.endsWith(".m3u8") || url.lowercase().contains(".m3u8")
        val r = if (isHls) {
            raw(url, referer, range = "bytes=0-2047")
        } else {
            raw(url, referer, range = "bytes=0-1023")
        }
        val okStatus = r.status in 200..299 || r.status == 206
        val body = r.body ?: ""
        return when {
            isHls && okStatus && body.contains("#EXTM3U") -> "OYNAR (HLS ${r.status})"
            isHls && okStatus -> "HLS ${r.status} (manifest imzası yok)"
            okStatus && (r.ctype?.contains("video") == true || r.ctype?.contains("octet-stream") == true) ->
                "OYNAR (${r.ctype} ${r.status})"
            okStatus -> "HTTP ${r.status} ctype=${r.ctype}"
            else -> "HATA ${r.status}"
        }
    }

    /** Kapsanmayan oynatıcının gerçekte ne servis ettiğini yazar (çözücü yazmak için kanıt). */
    private fun dumpHost(url: String, name: String, base: String) {
        val r = raw(url, "$base/")
        val body = r.body ?: ""
        p("      ┌ DÖKÜM [$name] $url")
        p("      │ HTTP ${r.status} ctype=${r.ctype} bytes=${body.length}")
        p("      │ başlık: " + (Regex("<title[^>]*>([^<]*)", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)?.trim()?.take(80) ?: "-"))
        val media = Regex("""(?:https?:)?//[^"'\s<>]+?\.(?:m3u8|mp4|mpd)[^"'\s<>]*""")
            .findAll(body.replace("\\/", "/")).map { it.value }.distinct().take(3).toList()
        if (media.isNotEmpty()) p("      │ medya: $media")
        val iframes = Regex("""<iframe[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.groupValues[1] }.distinct().take(3).toList()
        if (iframes.isNotEmpty()) p("      │ iframe: $iframes")
        p(
            "      │ anahtar: " + Regex("""["]([A-Za-z_][A-Za-z0-9_]*)["]\s*:""").findAll(body)
                .map { it.groupValues[1] }.distinct().take(12).joinToString(",")
        )
        // Oynatıcı SPA ise API uçlarını ve script dosyalarını göster.
        val scripts = Regex("""<script[^>]+src=["']([^"']+)""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.groupValues[1] }.distinct().take(4).toList()
        if (scripts.isNotEmpty()) p("      │ script: $scripts")
        val apiHits = Regex("""["'](/?api/[A-Za-z0-9_./?=&-]{3,80})["']""")
            .findAll(body).map { it.groupValues[1] }.distinct().take(6).toList()
        if (apiHits.isNotEmpty()) p("      │ api: $apiHits")
        val srcHits = Regex("""(?:sources|file|src|hls|m3u8|playlist)["']?\s*[:=]\s*["']([^"']{8,120})["']""", RegexOption.IGNORE_CASE)
            .findAll(body).map { it.groupValues[1] }.distinct().take(5).toList()
        if (srcHits.isNotEmpty()) p("      │ kaynak: $srcHits")
        p("      └ gövde: " + body.replace(Regex("\\s+"), " ").take(500))
    }

    @Test
    fun auditEverySource() {
        assumeTrue("LIVE_PROBE=1 değil", System.getenv("LIVE_PROBE") == "1")

        var base = ""
        for (dom in candidates) {
            val r = raw("$dom/")
            if (r.status in 200..299 && (r.body?.length ?: 0) > 20000) { base = dom; break }
        }
        if (base.isEmpty()) { p("taban alan adı yok"); return }
        p("taban: $base")

        val episodes = listOf(
            "$base/one-piece-1-bolum-izle",
            "$base/naruto-1-bolum-izle",
            "$base/cyberpunk-edgerunners-2-1-bolum-izle"
        )

        var totalSources = 0
        var coveredSources = 0
        var playableSources = 0
        val uncovered = LinkedHashSet<String>()
        val broken = LinkedHashSet<String>()

        for (epUrl in episodes) {
            val page = raw(epUrl, "$base/").body ?: continue
            if (page.length < 2000) { p("$epUrl → sayfa yok"); continue }
            val doc = Jsoup.parse(page, epUrl)

            val translators = doc.select("[data-sources]").mapNotNull { el ->
                val path = el.attr("data-sources").trim()
                if (path.isEmpty()) null
                else path to el.attr("title").ifBlank { el.text().trim() }.ifBlank { "çevirmen" }
            }.distinctBy { it.first }
            p("")
            p("═══ ${epUrl.removePrefix(base)} — çevirmen: ${translators.size}")
            if (translators.isEmpty()) { p("  !! çevirmen düğümü yok"); continue }

            for ((tPath, tName) in translators) {
                val tUrl = if (tPath.startsWith("http")) tPath else base + tPath
                val body = raw(tUrl, epUrl, xhr = true).body ?: continue
                val bolumId = Regex("/bolum/(\\d+)").find(tUrl)?.groupValues?.get(1)
                val vids = LinkedHashSet<String>()
                Regex("\"id\"\\s*:\\s*\"?(\\d+)\"?[^}]*?\"name\"\\s*:\\s*\"([^\"]*)\"")
                    .findAll(body).forEach { m ->
                        vids.add(m.groupValues[1] + "|" + m.groupValues[2])
                    }
                if (vids.isEmpty()) {
                    Regex("video=\"([^\"]+)\"").findAll(body).forEach { vids.add(it.groupValues[1] + "|") }
                }
                p("  ── çevirmen '$tName' (bolum=$bolumId) → ${vids.size} video")
                for (v in vids) {
                    val id = v.substringBefore('|')
                    val name = v.substringAfter('|').ifBlank { "video $id" }
                    totalSources++

                    val videoUrl = when {
                        id.startsWith("http") -> id
                        id.startsWith("/") -> base + id
                        bolumId != null -> "$base/bolum/$bolumId/video/$id"
                        else -> continue
                    }
                    val vJson = raw(videoUrl, epUrl, xhr = true).body ?: ""
                    val playerRef = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(vJson)
                        ?.groupValues?.get(1)?.replace("\\/", "/")
                    val playerId = Regex("/player/(\\d+)").find(playerRef ?: "")
                        ?.groupValues?.get(1) ?: id

                    val loc = raw("$base/player/$playerId", epUrl, follow = false).location
                        ?: playerRef
                    if (loc == null) {
                        p("    ✗ [$tName] $name → oynatıcı yok")
                        broken.add("$name (oynatıcı yok)")
                        continue
                    }
                    val host = hostOf(loc)
                    val cov = coverage(host)
                    if (cov != "KAPSANMIYOR") coveredSources++ else if (uncovered.add(host)) {
                        dumpHost(loc, "$name", base)
                    }

                    // anizmplayer doğrudan API ile denenir; engellenirse not düşülür.
                    val verdict = when {
                        host.contains("anizmplayer") -> {
                            val hash = Regex("anizmplayer\\.com/(?:video|player|embed)/([A-Za-z0-9_-]+)")
                                .find(loc)?.groupValues?.get(1)
                            if (hash == null) "hash yok"
                            else {
                                val got = raw(
                                    "https://anizmplayer.com/player/index.php?data=$hash&do=getVideo",
                                    "https://anizmplayer.com/",
                                    xhr = true,
                                    form = mapOf("hash" to hash, "r" to "$base/"),
                                    extra = mapOf("Origin" to "https://anizmplayer.com")
                                )
                                val stream = Regex("\"(?:securedLink|videoSource)\"\\s*:\\s*\"([^\"]+)\"")
                                    .find(got.body ?: "")?.groupValues?.get(1)
                                if (stream != null) probePlayable(stream.replace("\\/", "/"), "https://anizmplayer.com/")
                                else "API ${got.status} (bölge/IP engeli olabilir)"
                            }
                        }
                        host.contains("drive.google") -> {
                            val fid = Regex("/d/([A-Za-z0-9_-]{6,})").find(loc)?.groupValues?.get(1)
                            if (fid == null) "id yok"
                            else {
                                // Eklentinin YENİ akışı: uyarı sayfası → gizli form → gerçek adres.
                                val warn = raw(
                                    "https://drive.usercontent.google.com/download?id=$fid&export=download",
                                    "https://drive.google.com/"
                                ).body ?: ""
                                val action = Regex(
                                    """<form[^>]+action="(https://drive\.usercontent\.google\.com/download[^"]*)""""
                                ).find(warn)?.groupValues?.get(1)
                                val fields = Regex("""<input[^>]+name="([^"]+)"[^>]*value="([^"]*)"""")
                                    .findAll(warn).associate { it.groupValues[1] to it.groupValues[2] }
                                val target = if (action != null && fields.isNotEmpty()) {
                                    action + "?" + fields.entries.joinToString("&") {
                                        "${it.key}=" + java.net.URLEncoder.encode(it.value, "UTF-8")
                                    }
                                } else {
                                    "https://drive.usercontent.google.com/download?id=$fid&export=download&confirm=t"
                                }
                                p("      drive form=${if (action != null && fields.isNotEmpty()) "VAR" else "YOK"} (${fields.keys})")
                                probePlayable(target, "https://drive.google.com/")
                            }
                        }
                        else -> probePlayable(loc, epUrl)
                    }
                    if (verdict.startsWith("OYNAR")) playableSources++
                    if (verdict.startsWith("HATA")) broken.add("$name @ $host → $verdict")
                    p("    · [$tName] $name → $host [$cov] $verdict")
                }
            }
        }

        p("")
        p("════════ ÖZET ════════")
        p("toplam kaynak      : $totalSources")
        p("çözücü kapsamında  : $coveredSources")
        p("oynatılabilir      : $playableSources")
        if (uncovered.isNotEmpty()) p("KAPSANMAYAN host   : ${uncovered.joinToString()}")
        if (broken.isNotEmpty()) {
            p("SORUNLU kaynaklar  : ${broken.size}")
            broken.take(20).forEach { p("  ✗ $it") }
        }
    }
}
