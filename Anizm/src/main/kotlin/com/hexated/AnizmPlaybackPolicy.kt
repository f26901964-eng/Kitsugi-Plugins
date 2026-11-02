package com.hexated

import com.lagradost.cloudstream3.utils.ExtractorLinkType
import java.net.URI
import okhttp3.Response
import java.net.URLDecoder
import java.util.Locale

enum class AnizmDriveKind { MEDIA, HLS, DASH, HTML, DEAD, UNKNOWN }
data class AnizmDriveResponse(
    val kind: AnizmDriveKind,
    val body: String? = null,
    /** Redirect sonrası nihai adres; imzalı sorgu ve dönüş hedefleri korunur. */
    val finalUrl: String? = null
)

/** URL aynı olsa bile farklı başlık/ses/interceptor koşulları ayrı akıştır. */
internal data class AnizmStreamIdentity(
    val url: String,
    val type: ExtractorLinkType,
    val quality: Int,
    val headers: Map<String, String>,
    val audioTracks: List<Pair<String, Map<String, String>>>,
    val extractorData: String?
)

/** Ağdan ve Android'den bağımsız, tekilleştirme ve yayın politikaları. */
internal object AnizmPlaybackPolicy {
    /** Çözümleme ve oynatma aynı UA'yı kullanır; oturum UA değişimiyle bozulmaz. */
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

    fun mergeHeaders(defaults: Map<String, String>, overrides: Map<String, String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun put(name: String, value: String) {
            if (name.isBlank() || value.isBlank()) return
            result.keys.filter { it.equals(name, ignoreCase = true) }.toList().forEach(result::remove)
            result[name] = value
        }
        defaults.forEach(::put)
        overrides.forEach(::put)
        return result
    }

    fun canonicalHeaders(headers: Map<String, String>): Map<String, String> =
        headers.entries.filter { it.key.isNotBlank() && it.value.isNotBlank() }
            .associate { it.key.lowercase(Locale.ROOT) to it.value }
            .toSortedMap()

    fun header(headers: Map<String, String>, name: String): String? =
        headers.entries.lastOrNull { it.key.equals(name, true) && it.value.isNotBlank() }?.value

    private fun httpUri(url: String): URI? = try {
        val uri = URI(url)
        if (uri.host.isNullOrBlank() || uri.userInfo != null ||
            !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true))) null else uri
    } catch (_: Exception) { null }

    private fun hostMatches(url: String, vararg domains: String): Boolean {
        val host = httpUri(url)?.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return domains.any { host == it || host.endsWith(".$it") }
    }

    fun origin(url: String?): String? {
        val uri = httpUri(url?.trim().orEmpty()) ?: return null
        val scheme = uri.scheme.lowercase(Locale.ROOT)
        val port = uri.port.takeIf {
            it > 0 && !(scheme == "http" && it == 80) && !(scheme == "https" && it == 443)
        }
        return "$scheme://${uri.host.lowercase(Locale.ROOT)}${port?.let { ":$it" }.orEmpty()}"
    }

    fun isDriveHost(url: String): Boolean = hostMatches(url,
        "drive.google.com", "docs.google.com", "drive.usercontent.google.com")

    fun isDriveDownload(url: String): Boolean {
        val uri = httpUri(url) ?: return false
        val host = uri.host.lowercase(Locale.ROOT).trimEnd('.')
        val path = uri.path.orEmpty().lowercase(Locale.ROOT).trimEnd('/')
        return (host == "drive.usercontent.google.com" && path == "/download") ||
            ((host == "drive.google.com" || host == "docs.google.com") && path == "/uc")
    }

    fun isOkRuHost(url: String): Boolean = hostMatches(url, "ok.ru", "odnoklassniki.ru")
    fun isSendvidHost(url: String): Boolean = hostMatches(url, "sendvid.com")
    fun needsEmbedOrigin(url: String): Boolean =
        isSendvidHost(url) || hostMatches(url, "vidcache.net", "yourupload.com") ||
            hostMatches(url, "okcdn.ru", "ok.ru", "odnoklassniki.ru")

    fun queryParameter(url: String, name: String): String? = try {
        URI(url).rawQuery.orEmpty().split('&').firstOrNull {
            URLDecoder.decode(it.substringBefore('='), "UTF-8") == name
        }?.let { URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
    } catch (_: Exception) { null }

    private val SENDVID_RESERVED_PATHS = setOf(
        "playlist", "playlists", "embed", "login", "register", "upload", "api",
        "terms", "privacy", "dmca", "contact", "about", "faq", "search",
        "trending", "popular", "new", "random", "video", "videos", "user",
        "users", "channel", "channels", "category", "categories", "tag", "tags",
        "sitemap", "robots.txt", "favicon.ico", "assets", "static", "css", "js", "img"
    )

    fun sendvidEmbed(url: String): String? {
        val uri = httpUri(url) ?: return null
        if (!isSendvidHost(url)) return null
        val id = Regex("^/(?:embed/)?([A-Za-z0-9_-]+)(?:\\.mp4)?/?$")
            .find(uri.path.orEmpty())?.groupValues?.get(1) ?: return null
        // SendVid kökündeki site sayfaları video kimliği değildir.
        if (id.lowercase(Locale.ROOT) in SENDVID_RESERVED_PATHS) return null
        return "https://sendvid.com/embed/$id"
    }

    /**
     * Yalnızca yanıtın ilk 4 KiB'si incelenir. 403/ağ hatası UNKNOWN'dır;
     * küçük dosya/kısa süre tek başına DEAD değildir. HTML, video diye yayınlanmaz.
     */
    fun classifyDrive(status: Int, contentType: String?, disposition: String?, sample: ByteArray): AnizmDriveKind {
        if (status == 404 || status == 410) return AnizmDriveKind.DEAD
        if (status !in 200..299) return AnizmDriveKind.UNKNOWN
        val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
        if (mime == "text/html" || mime == "application/xhtml+xml") return AnizmDriveKind.HTML
        val text = sample.toString(Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\t', '\r', '\n').lowercase(Locale.ROOT)
        if (Regex("^<(?:!doctype\\s+html|html|head|body|form|script|div|meta|title|style|center|iframe|!--)")
                .containsMatchIn(text)) return AnizmDriveKind.HTML
        if (text.startsWith("#extm3u") || mime in setOf(
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl")) {
            return AnizmDriveKind.HLS
        }
        if (mime == "application/dash+xml" || text.startsWith("<mpd") || text.startsWith("<?xml") && text.contains("<mpd")) {
            return AnizmDriveKind.DASH
        }
        return when {
            mime.startsWith("video/") || mime == "application/octet-stream" ||
                disposition.orEmpty().contains("attachment", ignoreCase = true) -> AnizmDriveKind.MEDIA
            else -> AnizmDriveKind.UNKNOWN
        }
    }
}

/**
 * Range yanıt vermeyen sunucuda bile en çok 256 KiB HTML / 4 KiB binary örnek okunur.
 * OkHttp peekBody tamponu sınırlıdır; yanıt her durumda kapatılır. NiceResponse.text
 * çağrılmaz (eski NiceHttp 5 MB'a kadar video gövdesini metne alabiliyordu).
 */
internal fun inspectDriveResponse(raw: Response): AnizmDriveResponse = raw.use {
    val sample = if (raw.isSuccessful) raw.peekBody(4096).bytes() else ByteArray(0)
    val kind = AnizmPlaybackPolicy.classifyDrive(
        raw.code, raw.header("Content-Type"), raw.header("Content-Disposition"), sample
    )
    val body = if (kind == AnizmDriveKind.HTML) raw.peekBody(262144).string() else null
    AnizmDriveResponse(kind, body, raw.request.url.toString())
}
