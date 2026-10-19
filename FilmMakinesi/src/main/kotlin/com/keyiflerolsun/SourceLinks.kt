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
        val lower = host.lowercase()
        return when {
            lower.contains("closeload") -> Route.CLOSELOAD
            lower.startsWith("rapid") || lower.contains("rapidvid") || lower.contains("rapidrame") -> Route.RAPID
            lower.contains("rplayer") || lower.contains("playerr") -> Route.RAPID
            else -> Route.GENERIC
        }
    }

    enum class Route { CLOSELOAD, RAPID, GENERIC }

    /** URL'den konak adı (küçük harf). */
    fun hostOf(url: String): String? = runCatching { java.net.URI(url.trim()).host?.lowercase() }.getOrNull()
}
