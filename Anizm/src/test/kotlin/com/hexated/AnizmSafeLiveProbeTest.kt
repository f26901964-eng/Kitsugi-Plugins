package com.hexated

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.InputStream
import java.net.CookieHandler
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.Locale

/**
 * Güvenli canlı prob — yalnızca `ANIZM_SAFE_LIVE=1` ile koşar.
 *
 * Gerçek [AnizmStreams] çözücüsünü canlı bölüm sayfasına karşı çalıştırır.
 * Çıktıda imzalı URL, sorgu parametresi, cookie, token veya tam medya adresi
 * YOKTUR; yalnız host, tür, durum, içerik tipi ve bayraklar raporlanır.
 */
class AnizmSafeLiveProbeTest {

    private val ua = AnizmPlaybackPolicy.USER_AGENT
    private val bases = listOf(
        "https://anizm.com.tr",
        "https://anizm.net",
        "https://www.puffytr.com",
        "https://anizm.org"
    )

    private fun p(line: String) = println("[SAFE-LIVE] $line")

    private fun host(url: String?): String = try {
        if (url.isNullOrBlank()) "-" else URI(url).host?.lowercase(Locale.ROOT) ?: "-"
    } catch (_: Exception) { "-" }

    private fun originHost(value: String?): String = try {
        if (value.isNullOrBlank()) "-" else URI(value).host?.lowercase(Locale.ROOT) ?: "-"
    } catch (_: Exception) { "-" }

    private class Resp(
        val status: Int,
        val bytes: ByteArray,
        val contentType: String?,
        val disposition: String?,
        val location: String?,
        val finalUrl: String?
    ) {
        val text: String get() = bytes.toString(Charsets.UTF_8)
    }

    private inner class LiveHttp : AnizmHttp {
        val cookies = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
        private var csrf: String? = null

        init { CookieHandler.setDefault(cookies) }

        private fun open(
            url: String,
            referer: String?,
            xhr: Boolean,
            method: String,
            extra: Map<String, String>,
            body: ByteArray?,
            contentType: String?,
            follow: Boolean,
            range: String?
        ): HttpURLConnection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = method
            instanceFollowRedirects = follow
            connectTimeout = 10000
            readTimeout = 12000
            setRequestProperty("User-Agent", ua)
            setRequestProperty("Accept-Language", "tr-TR,tr;q=0.9,en;q=0.8")
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty(
                "Accept",
                if (xhr) "application/json, text/javascript, */*; q=0.01"
                else "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            if (!referer.isNullOrBlank()) setRequestProperty("Referer", referer)
            if (xhr) {
                setRequestProperty("X-Requested-With", "XMLHttpRequest")
                setRequestProperty("sec-fetch-dest", "empty")
                setRequestProperty("sec-fetch-mode", "cors")
                setRequestProperty("sec-fetch-site", "same-origin")
                csrf?.let { setRequestProperty("X-CSRF-TOKEN", it) }
            }
            range?.let { setRequestProperty("Range", it) }
            extra.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", contentType ?: "application/x-www-form-urlencoded")
            }
        }

        private fun readBounded(input: InputStream?, max: Int): ByteArray {
            if (input == null) return ByteArray(0)
            if (max <= 0) {
                try { input.close() } catch (_: Exception) { }
                return ByteArray(0)
            }
            return input.use { stream ->
                val out = java.io.ByteArrayOutputStream(minOf(max, 8192))
                val buf = ByteArray(8192)
                while (out.size() < max) {
                    val n = stream.read(buf, 0, minOf(buf.size, max - out.size()))
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        }

        fun request(
            url: String,
            referer: String? = null,
            xhr: Boolean = false,
            method: String = "GET",
            extra: Map<String, String> = emptyMap(),
            body: ByteArray? = null,
            contentType: String? = null,
            follow: Boolean = true,
            range: String? = null,
            maxBytes: Int = 524288
        ): Resp {
            var conn: HttpURLConnection? = null
            return try {
                conn = open(url, referer, xhr, method, extra, body, contentType, follow, range)
                if (body != null) conn.outputStream.use { it.write(body) }
                val status = conn.responseCode
                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                val bytes = readBounded(stream, maxBytes)
                val ctype = conn.contentType
                val disp = conn.getHeaderField("Content-Disposition")
                val loc = conn.getHeaderField("Location")
                val finalUrl = conn.url?.toString()
                if (!xhr && ctype.orEmpty().contains("html", true)) {
                    Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)""", RegexOption.IGNORE_CASE)
                        .find(bytes.toString(Charsets.UTF_8))?.groupValues?.getOrNull(1)?.let { csrf = it }
                }
                Resp(status, bytes, ctype, disp, loc, finalUrl)
            } catch (e: Exception) {
                Resp(0, ByteArray(0), null, null, null, null)
            } finally {
                conn?.disconnect()
            }
        }

        override suspend fun get(url: String, referer: String?): String? {
            val r = request(url, referer, maxBytes = 524288)
            return if (r.status in 200..299) r.text else null
        }

        override suspend fun getXhr(url: String, referer: String?): String? {
            var r = request(url, referer, xhr = true, maxBytes = 524288)
            if (r.status == 419 && !referer.isNullOrBlank()) {
                get(referer, null)
                r = request(url, referer, xhr = true, maxBytes = 524288)
            }
            return if (r.status in 200..299) r.text else null
        }

        override suspend fun location(url: String, referer: String?): String? {
            val r = request(url, referer, follow = false, maxBytes = 0)
            val loc = r.location ?: return null
            return try { URI(url).resolve(loc).toString() } catch (_: Exception) { loc }
        }

        override suspend fun getWith(url: String, extra: Map<String, String>): String? {
            val r = request(url, extra = extra, maxBytes = 524288)
            return if (r.status in 200..299) r.text else null
        }

        override suspend fun postJson(url: String, json: String, referer: String?): String? {
            val r = request(url, referer, method = "POST", body = json.toByteArray(),
                contentType = "application/json", maxBytes = 524288)
            return if (r.status in 200..299) r.text else null
        }

        override suspend fun postForm(url: String, form: Map<String, String>, referer: String?): String? {
            val encoded = form.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
            }
            val r = request(url, referer, method = "POST", body = encoded.toByteArray(),
                contentType = "application/x-www-form-urlencoded; charset=UTF-8", maxBytes = 524288)
            return if (r.status in 200..299) r.text else null
        }

        override suspend fun inspectDrive(url: String, referer: String): AnizmDriveResponse {
            var conn: HttpURLConnection? = null
            return try {
                conn = open(url, referer, false, "GET", emptyMap(), null, null, true, "bytes=0-262143")
                val status = conn.responseCode
                val stream = if (status in 200..399) conn.inputStream else conn.errorStream
                val sample = readBounded(stream, 4096)
                val ctype = conn.contentType
                val disp = conn.getHeaderField("Content-Disposition")
                val kind = AnizmPlaybackPolicy.classifyDrive(status, ctype, disp, sample)
                val body = if (kind == AnizmDriveKind.HTML) {
                    // İlk okuma sınırlıydı ve akış kapatıldı. HTML onay formu için
                    // sınırlı ikinci istek at; binary gövde asla tamponlanmaz.
                    val r2 = request(url, referer, range = "bytes=0-262143", maxBytes = 262144)
                    r2.text
                } else null
                AnizmDriveResponse(kind, body, conn.url?.toString())
            } catch (e: Exception) {
                AnizmDriveResponse(AnizmDriveKind.UNKNOWN)
            } finally {
                conn?.disconnect()
            }
        }

        fun probeMedia(url: String, link: ExtractorLink): String {
            val headers = AnizmPlaybackPolicy.mergeHeaders(
                mapOf("Referer" to link.referer, "User-Agent" to ua),
                link.headers
            )
            val r = request(url, link.referer, extra = headers, range = "bytes=0-65535", maxBytes = 65536)
            val mime = r.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            val text = r.bytes.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            val lower = text.lowercase(Locale.ROOT)
            val kind = when {
                r.status == 0 -> "network-error"
                r.status !in 200..299 -> "http-${r.status}"
                mime.contains("html") || lower.startsWith("<!doctype html") || lower.startsWith("<html") -> "html"
                lower.startsWith("#extm3u") && lower.contains("#ext-x-stream-inf") -> "hls-master"
                lower.startsWith("#extm3u") -> "hls-playlist"
                mime.startsWith("video/") || r.bytes.size > 8 && r.bytes.copyOfRange(4, 8).toString(Charsets.US_ASCII) == "ftyp" -> "mp4-or-binary"
                else -> "unknown"
            }
            val audioGroups = if (kind == "hls-master") Regex("#EXT-X-MEDIA:", RegexOption.IGNORE_CASE).findAll(text).count() else 0
            val duration = mp4DurationSeconds(r.bytes)
            val base = "status=${r.status} ctype=${mime.ifBlank { "-" }} kind=$kind bytes=${r.bytes.size} finalHost=${host(r.finalUrl)}"
            val extra = buildString {
                if (audioGroups > 0) append(" audioGroups=$audioGroups")
                if (duration != null) append(" mp4DurationSeconds=$duration")
            }
            val follow = if (kind == "hls-master") probeFirstHlsChild(text, r.finalUrl ?: url, headers) else ""
            return base + extra + follow
        }

        private fun probeFirstHlsChild(master: String, masterUrl: String, headers: Map<String, String>): String {
            val child = master.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return ""
            val childUrl = try { URI(masterUrl).resolve(child).toString() } catch (_: Exception) { return "" }
            val r = request(childUrl, headers["Referer"], extra = headers, range = "bytes=0-4095", maxBytes = 4096)
            val t = r.text.trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.ROOT)
            val kind = when {
                r.status == 0 -> "network-error"
                r.status !in 200..299 -> "http-${r.status}"
                t.startsWith("#extm3u") && t.contains("#extinf") -> "hls-media-playlist"
                t.startsWith("#extm3u") -> "hls-playlist"
                t.startsWith("<html") || t.startsWith("<!doctype html") -> "html"
                else -> "segment-or-binary"
            }
            val segment = if (kind == "hls-media-playlist") {
                val seg = t.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                if (seg == null) "" else {
                    val segUrl = try { URI(childUrl).resolve(seg).toString() } catch (_: Exception) { "" }
                    if (segUrl.isEmpty()) "" else {
                        val sr = request(segUrl, headers["Referer"], extra = headers, range = "bytes=0-1023", maxBytes = 1024)
                        " segmentStatus=${sr.status} segmentBytes=${sr.bytes.size}"
                    }
                }
            } else ""
            return " childStatus=${r.status} childKind=$kind childBytes=${r.bytes.size}$segment"
        }

        private fun mp4DurationSeconds(bytes: ByteArray): Double? {
            val marker = "mvhd".toByteArray(Charsets.US_ASCII)
            var i = 0
            while (i + 32 < bytes.size) {
                if (bytes[i] == marker[0] && bytes[i + 1] == marker[1] &&
                    bytes[i + 2] == marker[2] && bytes[i + 3] == marker[3]) {
                    return try {
                        val version = bytes[i + 4].toInt() and 0xFF
                        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
                        if (version == 1 && i + 36 <= bytes.size) {
                            val timescale = buf.getInt(i + 24).toLong() and 0xFFFFFFFFL
                            val duration = buf.getLong(i + 28)
                            if (timescale > 0) duration.toDouble() / timescale else null
                        } else if (i + 24 <= bytes.size) {
                            val timescale = buf.getInt(i + 16).toLong() and 0xFFFFFFFFL
                            val duration = buf.getInt(i + 20).toLong() and 0xFFFFFFFFL
                            if (timescale > 0) duration.toDouble() / timescale else null
                        } else null
                    } catch (_: Exception) { null }
                }
                i++
            }
            return null
        }
    }

    private fun category(url: String): String {
        val h = host(url)
        return when {
            h.contains("anizmplayer") -> "Aincrad/AnizmPlayer"
            h == "ok.ru" || h.endsWith(".ok.ru") || h.contains("okcdn") || h.contains("odnoklassniki") -> "OK.ru"
            h == "sendvid.com" || h.endsWith(".sendvid.com") -> "SendVid"
            h.contains("vidcache") || h.contains("yourupload") -> "YourUpload"
            h.contains("drive.google") || h.contains("docs.google") || h.contains("drive.usercontent") -> "Drive"
            else -> "other"
        }
    }

    @Test
    fun safeLiveProbe() = runBlocking {
        assumeTrue("ANIZM_SAFE_LIVE=1 değil", System.getenv("ANIZM_SAFE_LIVE") == "1")
        p("probe=anizm-safe-live version=22")
        val http = LiveHttp()
        var base = ""
        for (candidate in bases) {
            val r = http.request(candidate + "/", maxBytes = 65536)
            p("base host=${host(candidate)} status=${r.status} bytes=${r.bytes.size}")
            if (r.status in 200..299 && r.bytes.size > 10000 && base.isEmpty()) base = candidate
        }
        if (base.isEmpty()) {
            p("result=no-live-base")
            return@runBlocking
        }
        p("selectedBaseHost=${host(base)}")

        var seriesUrl = ""
        var seriesTitle = ""
        for (query in listOf("shingeki no kyojin", "attack on titan", "shingeki")) {
            val q = URLEncoder.encode(query, "UTF-8")
            val r = http.request("$base/arama?q=$q", "$base/", maxBytes = 524288)
            if (r.status !in 200..299) continue
            val doc = Jsoup.parse(r.text, base)
            val cards = AnizmSite.parseCards(doc, base, 30)
            val match = cards.firstOrNull { card ->
                val n = card.name.lowercase(Locale.ROOT)
                n.contains("shingeki") || n.contains("attack on titan") || n.contains("titan")
            }
            p("search query='$query' status=${r.status} cards=${cards.size} match=${match != null}")
            if (match != null) {
                seriesUrl = match.url
                seriesTitle = match.name
                break
            }
        }
        if (seriesUrl.isEmpty()) {
            for (path in listOf("/shingeki-no-kyojin", "/attack-on-titan")) {
                val r = http.request(base + path, "$base/", maxBytes = 524288)
                if (r.status in 200..299 && r.bytes.size > 2000) {
                    seriesUrl = base + path
                    seriesTitle = path.trim('/')
                    break
                }
            }
        }
        if (seriesUrl.isEmpty()) {
            p("result=series-not-found")
            return@runBlocking
        }
        p("series title='$seriesTitle' host=${host(seriesUrl)}")

        var episodeUrl = ""
        val seriesResp = http.request(seriesUrl, "$base/", maxBytes = 524288)
        if (seriesResp.status in 200..299) {
            val doc = Jsoup.parse(seriesResp.text, seriesUrl)
            val episodes = AnizmSite.parseEpisodes(doc, base, seriesUrl)
            val ep1 = episodes.firstOrNull { it.number == 1 }
            p("episodes parsed=${episodes.size} episode1=${ep1 != null}")
            if (ep1 != null) episodeUrl = ep1.url
        }
        if (episodeUrl.isEmpty()) {
            val slug = seriesUrl.trimEnd('/').substringAfterLast('/')
            for (candidate in listOf(
                "$base/$slug-1-bolum-izle",
                "$base/$slug-1-sezon-1-bolum-izle",
                "$base/$slug-1-bolum"
            )) {
                val r = http.request(candidate, seriesUrl, maxBytes = 524288)
                if (r.status in 200..299 && r.bytes.size > 2000) {
                    episodeUrl = candidate
                    break
                }
            }
        }
        if (episodeUrl.isEmpty()) {
            p("result=episode1-not-found")
            return@runBlocking
        }
        p("episode1 host=${host(episodeUrl)}")

        val links = Collections.synchronizedList(mutableListOf<ExtractorLink>())
        val streams = AnizmStreams("Anizm", { base }, http)
        val emitted = withTimeoutOrNull(180_000L) {
            streams.load(episodeUrl, { }, { link -> links.add(link) })
        }
        p("resolver emitted=${links.size} returned=${emitted ?: "timeout"}")
        if (links.isEmpty()) {
            p("result=no-links")
            return@runBlocking
        }

        val counts = linkedMapOf<String, Int>()
        links.take(20).forEachIndexed { index, link ->
            val cat = category(link.url)
            counts[cat] = (counts[cat] ?: 0) + 1
            val headerKeys = link.headers.keys.sorted().joinToString(",")
            val origin = AnizmPlaybackPolicy.header(link.headers, "Origin")
            val probe = try {
                http.probeMedia(link.url, link)
            } catch (e: Exception) {
                "probe-error=${e.javaClass.simpleName}"
            }
            p("link #$index cat=$cat type=${link.type} quality=${link.quality} " +
                "audioTracks=${link.audioTracks.size} extractorData=${link.extractorData != null} " +
                "refererHost=${host(link.referer)} originHost=${originHost(origin)} headerKeys=[$headerKeys] $probe")
        }
        p("summary counts=" + counts.entries.joinToString(",") { "${it.key}:${it.value}" })
        p("result=completed")
    }
}
