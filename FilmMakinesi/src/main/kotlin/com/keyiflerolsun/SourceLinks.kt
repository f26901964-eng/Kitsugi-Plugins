// ! FilmMakinesi — kaynak adresi seçimi ve yönlendirme.
//
// ! NEDEN AYRI DOSYA? android.* bağımlılığı yok; canlı bölüm sayfasından
// ! alınan gerçek değerlerle birim testi yapılabiliyor.
//
// ! CANLI SAYFADAN DOĞRULANANLAR (The Mentalist S4B19):
// !   <div class="video-parts">
// !     <a href="#" class="btn btn-default active"
// !        data-video_url="https://closeload.filmmakinesi.to/video/embed/sdKumgu2fZ0/?imdb_id=tt1196946">DUAL Close</a>
// !     <a href="#" class="btn btn-default"
// !        data-video_url="https://rapid.filmmakinesi.to/embed-h1j6349abwr5/">DUAL Rapid</a>
// !   </div>
// !   <div class="after-player">
// !     <iframe data-src="https://closeload.filmmakinesi.to/video/embed/sdKumgu2fZ0/?imdb_id=tt1196946">
// !   </div>
// !
// ! ! SAYFADAKİ TUZAK: JSON-LD'de gerçek yayın değil, YOUTUBE FRAGMANI var:
// !   "video": {"@type":"VideoObject","embedUrl":"https://www.youtube.com/embed/..."}
// ! Bu adres kaynak sayılırsa oynatıcıya fragman gider; asla kabul edilmez.
// !
// ! ! İKİNCİ TUZAK: düğmelerin href'i "#"tir. Adres data-video_url'dedir;
// ! href okunursa aday "#" olur ve oynatıcıya çöp gider.

package com.keyiflerolsun

object SourceLinks {

    /** Kaynak olarak kullanılamayacak şemalar/konaklar. */
    private val BLOCKED_HOSTS = listOf("youtube.com", "youtu.be", "youtube-nocookie.com", "googlevideo.com")
    private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif", ".bmp", ".ico", ".tif", ".tiff")
    private val SOURCE_ATTRIBUTES = listOf(
        "data-video_url", "data-video-url", "data-video", "data-video-id", "data-video_id",
        "data-embed-url", "data-embed", "data-player-url", "data-player", "data-server",
        "data-source", "data-file", "data-hls", "data-stream", "data-mp4",
        "data-src", "data-lazy-src", "data-href", "data-url", "src", "href"
    )

    /** Adres gerçekten yayın/oynatıcı adresi mi? */
    fun isUsable(url: String): Boolean {
        val value = url.trim()
        val lower = value.lowercase()
        if (value.isBlank() || value == "#" || lower.startsWith("javascript:")) return false
        if (lower.startsWith("data:") || lower.startsWith("mailto:")) return false
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = hostOf(value) ?: return false
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) return false
        // ! Görseller kaynak değildir; query ve fragment uzantı kontrolüne dahil edilmez.
        val path = value.substringBefore('?').substringBefore('#').lowercase()
        if (IMAGE_EXTENSIONS.any { path.endsWith(it) }) return false
        return true
    }

    /** Göreli adresi mutlağa çevirir; kullanılamıyorsa null. */
    fun normalize(raw: String, mainUrl: String): String? {
        val value = raw.trim()
        if (value.isBlank() || value == "#" || value.lowercase().startsWith("javascript:")) return null
        val absolute = runCatching {
            val base = java.net.URI(mainUrl)
            when {
                value.startsWith("//") -> "${base.scheme ?: "https"}:$value"
                else -> base.resolve(value).toString()
            }
        }.getOrElse { return null }
        return absolute.takeIf { isUsable(it) }
    }

    /**
     * Bir içerik sayfasından oynatıcı/kaynak URL'lerini toplar.
     * Eski `.video-parts` yapısına ek olarak yeni iframe lazy alanları, `video/source`
     * etiketleri, data-* kontrolleri ve script içine JSON/JS olarak gömülen URL'ler taranır.
     */
    fun pageCandidates(document: org.jsoup.nodes.Document, pageUrl: String): List<String> {
        val out = linkedSetOf<String>()
        val selector = listOf(
            "iframe", "video", "source", ".video-parts a", ".video-options a",
            ".video-servers a", ".alternative-links a", "[aria-label*=Alternative] a",
            "[data-video_url]", "[data-video-url]", "[data-video]", "[data-video-id]", "[data-video_id]",
            "[data-embed-url]", "[data-embed]", "[data-player-url]", "[data-player]", "[data-server]",
            "[data-source]", "[data-file]", "[data-hls]", "[data-stream]", "[data-mp4]",
            "[data-src*=embed]", "[data-src*=video]", "[data-src*=player]",
            "[href*='embed']", "[href*='rplayer']", "[href*='playerr']",
            "[href*='.m3u8']", "[href*='.mpd']", "[href*='.mp4']"
        ).joinToString(", ")

        document.select(selector).forEach { element ->
            SOURCE_ATTRIBUTES.forEach { attribute ->
                val raw = element.attr(attribute).trim()
                if (raw.isNotBlank() && !isPlainId(raw)) {
                    normalize(raw, pageUrl)?.let(out::add)
                }
            }
        }

        // JS objeleri bazen `https:\/\/host/...` biçiminde kaçışlıdır.
        val rawHtml = document.html()
            .replace("\\/", "/")
            .replace("\\u002f", "/", ignoreCase = true)
            .replace("\\u003a", ":", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
        Regex("""https?://[^\s"'<>]+""").findAll(rawHtml).forEach { match ->
            val raw = match.value.trimEnd(',', ';', ')', ']', '}')
            val lower = raw.lowercase()
            val isPlayerOrStream = isDirectStreamUrl(raw) || routeFor(raw) != Route.GENERIC ||
                lower.contains("/embed/") || lower.contains("/rplayer/") || lower.contains("/playerr/") ||
                lower.contains("/video/") || lower.contains("rapidrame_id=")
            if (!isPlayerOrStream) return@forEach
            normalize(raw, pageUrl)?.let(out::add)
        }

        // JSON/JS player configs often store a relative path as `file` or `src`.
        val scriptUrlValue = Regex(
            """["'](?:file|src|source|contentUrl|embedUrl|playerUrl|url)["']\s*:\s*["']([^"'<>]+)["']""",
            RegexOption.IGNORE_CASE
        )
        scriptUrlValue.findAll(rawHtml).forEach { match ->
            val normalized = normalize(match.groupValues[1], pageUrl) ?: return@forEach
            val lower = normalized.lowercase()
            val isPlayerOrStream = isDirectStreamUrl(normalized) || routeFor(normalized) != Route.GENERIC ||
                lower.contains("/embed/") || lower.contains("/rplayer/") || lower.contains("/playerr/") ||
                lower.contains("/video/") || lower.contains("rapidrame_id=")
            if (isPlayerOrStream) out.add(normalized)
        }

        return out.toList()
    }

    private fun isPlainId(raw: String): Boolean =
        Regex("""[A-Za-z0-9_-]{1,64}""").matches(raw.trim())

    /** URL doğrudan HLS/DASH/video dosyası mı? */
    fun isDirectStreamUrl(url: String): Boolean {
        val path = runCatching { java.net.URI(url.trim()).path.orEmpty() }
            .getOrElse { url.substringBefore('?').substringBefore('#') }
            .lowercase()
        return path.endsWith("master.txt") || listOf(
            ".m3u8", ".m3u", ".mpd", ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi"
        ).any { extension -> path.endsWith(extension) }
    }

    /**
     * Kaynak düğmesindeki ilk kullanılabilir adresi seçer.
     * ! `href="#"` bir bağlantı değildir; `data-video_url` önceliklidir.
     */
    fun partUrl(
        mainUrl: String,
        dataVideoUrl: String,
        dataVideoDashUrl: String,
        href: String,
        dataHref: String,
        dataUrl: String
    ): String? {
        for (raw in listOf(dataVideoUrl, dataVideoDashUrl, href, dataHref, dataUrl)) {
            normalize(raw, mainUrl)?.let { return it }
        }
        return null
    }

    /**
     * Adresi hangi çözümleyici işler?
     * ! `rapid.filmmakinesi.to` eskiden hiçbir çözümleyiciye gitmiyordu:
     * ! yalnızca "rapidvid"/"rapidrame" metni arandığı için "DUAL Rapid"
     * ! düğmesi sessizce kayboluyordu.
     */
    fun routeFor(url: String): Route {
        val host = hostOf(url) ?: return Route.GENERIC
        val lowerHost = host.lowercase()
        val lowerPath = runCatching { java.net.URI(url.trim()).path.orEmpty().lowercase() }.getOrDefault("")
        return when {
            lowerHost.contains("closeload") -> Route.CLOSELOAD
            lowerPath.contains("/rplayer/") || lowerPath.contains("/playerr/") -> Route.RAPID
            lowerHost.startsWith("rapid") || lowerHost.contains("rapidvid") || lowerHost.contains("rapidrame") -> Route.RAPID
            lowerHost.contains("rplayer") || lowerHost.contains("playerr") -> Route.RAPID
            else -> Route.GENERIC
        }
    }

    enum class Route { CLOSELOAD, RAPID, GENERIC }

    /** URL'den konak adı (küçük harf). */
    fun hostOf(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
}
