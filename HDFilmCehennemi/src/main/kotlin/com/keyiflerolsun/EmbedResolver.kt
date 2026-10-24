// ! HDFilmCehennemi'nin embed (oynatıcı) adres çözümleyicisi.
//
// ! NEDEN SAF KOTLIN?
// ! android.* bağımlılığı İÇERMEZ; böylece canlı siteden alınmış örnekler
// ! (fixture) üzerinde birim testiyle doğrulanabilir:
// !   HDFilmCehennemi/src/test/kotlin/com/keyiflerolsun/EmbedResolverTest.kt
//
// ! ÜÇ KÖK NEDEN BURADA DÜZELTİLDİ (tahmin değil, canlı yanıt + 4 bağımsız
// ! referans implementasyonla doğrulandı):
//
// ! 1) JSON KAÇIŞLI SLASH
// !    /video/{id}/ uç noktası JSON döndürüyor ve HTML'i kaçışlı slash ile
// !    veriyor:  {"data":{"html":"<iframe data-src=\"https:\/\/site\/rplayer\/ID\/\">"}}
// !    Eski regex (data-src=\\?"([^"\\]+)\") karakter sınıfında backslash'te
// !    DURUYOR, kapanış tırnağı gelmediği için HİÇ EŞLEŞMİYORDU →
//!    iframeFromHtml() null döndürüyor → uygulama "0 kaynak" gösteriyordu.
//!    Düzeltme: JSON'daki "html" alanı çözülür, kaçışlar geri alınır, sonra
// !    HTML'den data-src okunur.
//
// ! 2) KENDİ-HOST FİLTRESİ
// !    normalizeEmbed() kendi sayfasına (aynı host) işaret eden adresleri
// !    kaynak saymıyordu. Oysa sitenin ASIL oynatıcısı site-içi
// !    /rplayer/<id>/ adresidir. Bu kural oynatıcıyı eliyordu.
//
// ! 3) ?rapidrame_id= DÖNÜŞÜMÜ
// !    Harici iframe adresi oynatıcının kimliğini sorgu dizesinde taşıyor:
//!    https://harici-host/.../?rapidrame_id=<id>  →  <site>/rplayer/<id>/
// !    Ayrıca rplayer adresi SONDA / ile olmalı; sondaki / yoksa sunucu 301
// !    yönlendiriyor ve oynatıcı sayfası boş dönüyor.

package com.keyiflerolsun

object EmbedResolver {

    /** Embed adresi kalıpları (JSON kaçışları çözüldükten sonra uygulanır). */
    val EMBED_URL_PATTERNS = listOf(
        Regex("""data-src\s*=\s*["']([^"']+)["']"""),
        Regex("""<iframe[^>]+data-src\s*=\s*["']([^"']+)["']"""),
        Regex("""src\s*=\s*["']([^"']+)["']"""),
        Regex("""<iframe[^>]+src\s*=\s*["']([^"']+)["']"""),
        // ! Doğrudan akış adresi gömülüyse (bazı sayfalar iframe kullanmıyor).
        Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*"""),
        Regex("""https?://[^\s"'<>]+/master\.txt[^\s"'<>]*""")
    )

    /** Siteye ait oynatıcı yolları — kendi host'unda olsalar da geçerli kaynaktır. */
    val PLAYER_PATHS = listOf(
        "/rplayer/", "/playerr/", "/player/", "/embed/", "/video/", "/play/", "/stream/"
    )

    private val DIRECT_MEDIA_SUFFIXES = listOf(
        ".m3u8", ".m3u", ".mpd", ".mp4", ".m4v", ".mkv", ".webm", ".mov", ".avi"
    )
    private val VIDEO_ID_REGEX = Regex("""[A-Za-z0-9_-]{1,128}""")

    /**
     * URL gerçekten medya dosyası/playlist'ine mi işaret ediyor?
     * Sorgu dizesini değil, URL path'ini kontrol eder; ör. `master.m3u8?token=...`
     * doğrudan yayın sayılırken bir görsel URL'sindeki `.m3u8` sorgu değeri sayılmaz.
     */
    fun isDirectMediaUrl(raw: String): Boolean {
        val value = raw.trim()
        if (value.isBlank()) return false
        val path = runCatching { java.net.URI(value).path }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: value.substringBefore('?').substringBefore('#')
        val lowerPath = path.lowercase()
        return lowerPath.endsWith("master.txt") || DIRECT_MEDIA_SUFFIXES.any { lowerPath.endsWith(it) }
    }

    /**
     * `<video>/<source>` ve lazy player düğümlerindeki doğrudan medya adresleri.
     * Yalnızca dosya/playlist yolu gibi görünen değerler döner; poster ve reklam
     * iframe'leri bu listeden geçemez.
     */
    fun embeddedMediaSources(document: org.jsoup.nodes.Document): List<String> {
        val attributes = listOf("src", "data-src", "data-lazy-src", "data-file", "data-hls", "data-stream", "data-mp4")
        val out = mutableListOf<String>()

        document.select("video, source, [data-file], [data-hls], [data-stream], [data-mp4]").forEach { element ->
            attributes.forEach { attribute ->
                val value = element.attr(attribute).trim()
                if (value.isNotBlank() && isDirectMediaUrl(value) && !FallbackCandidates.isImageUrl(value)) {
                    out.add(value)
                }
            }
        }

        return out.distinct()
    }

    /**
     * Site `/video/{id}/` uç noktasındaki kimliği hem düz değerlerden hem de
     * göreli/tam URL'lerden alır. `/video/embed/...` bir oynatıcı adresidir,
     * API kimliği değildir; özellikle ayırt edilir.
     */
    fun videoIdFrom(raw: String): String? {
        val value = raw.trim().replace("\\/", "/").trim('"', '\'')
        if (value.isBlank() || isDirectMediaUrl(value)) return null

        val path = runCatching { java.net.URI(value).path.orEmpty() }
            .getOrElse { value.substringBefore('?').substringBefore('#') }
        val segments = path.split('/').filter { it.isNotBlank() }
        val videoIndex = segments.indexOfLast { it.equals("video", ignoreCase = true) }
        if (videoIndex >= 0) {
            val segment = segments.getOrNull(videoIndex + 1) ?: return null
            if (segment.lowercase() in setOf("embed", "player", "play", "rplayer", "playerr")) return null
            return segment.takeIf { VIDEO_ID_REGEX.matches(it) }
        }

        val plain = value.substringBefore('?').substringBefore('#').trim('/')
        if (plain.contains('/') || plain.contains("://") || plain.contains('.')) return null
        return plain.takeIf { VIDEO_ID_REGEX.matches(it) }
    }

    /** JSON gövdesindeki "html" alanını çözer (JSON kaçışlarını geri alır). */
    fun jsonHtmlField(raw: String): String? {
        val match = Regex("\"html\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(raw) ?: return null
        return match.groupValues[1]
            .replace("\\/", "/")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    /**
     * Oynatıcı kapsayıcısındaki lazy iframe kaynaklarını döndürür.
     * Bu adaylar sayfadaki alternatif düğme kimliklerinden önce denenmelidir;
     * `data-src` mutlaksa değeri değiştirilmeden korunur.
     *
     * ! GÜNCELLEME (2026-10-19): Site artık SPA (Next.js) ve oynatıcı
     * ! `[aria-label='Player'] iframe` içinde saklanıyor. Eski
     * ! `.video-container` kontrolüne ek olarak aria-label da taranıyor.
     * ! Ayrıca `src` ve `data-lazy-src` varyantları da eklenir.
     */
    fun preferredPlayerDataSrcs(document: org.jsoup.nodes.Document): List<String> {
        val out = mutableListOf<String>()

        // Eski yapı: video-container / hdmv-play-container / player
        document.select("iframe[data-src]")
            .filter { frame ->
                frame.parents().any {
                    it.hasClass("video-container") || it.hasClass("hdmv-play-container") ||
                        it.hasClass("player") || it.hasClass("video-player")
                }
            }
            .mapTo(out) { it.attr("data-src").trim() }

        // Yeni SPA yapı: [aria-label='Player'] içindeki iframe'ler
        document.select("[aria-label='Player'] iframe")
            .mapTo(out) {
                val v = it.attr("data-src").ifBlank { it.attr("src") }.ifBlank { it.attr("data-lazy-src") }
                v.trim()
            }

        // Genel fallback: aria-label içeren herhangi bir player kapsayıcısı
        document.select("[aria-label='Alternative tab content'] iframe, [aria-label='Play video'] ~ iframe, .player iframe[data-src], .video-player iframe[data-src]")
            .mapTo(out) {
                val v = it.attr("data-src").ifBlank { it.attr("src") }
                v.trim()
            }

        return out.filter { it.isNotBlank() }.distinct()
    }

    /**
     * /video/{id}/ yanıtından embed (oynatıcı) adresini çıkarır.
     * Yanıt JSON ise önce "html" alanı çözülür; değilse ham metin taranır.
     */
    fun extractEmbedUrl(rawResponse: String?): String? {
        if (rawResponse.isNullOrBlank()) return null
        val html = jsonHtmlField(rawResponse) ?: rawResponse
        for (pattern in EMBED_URL_PATTERNS) {
            val match = pattern.find(html) ?: continue
            val raw = match.groupValues.getOrNull(1)?.replace("\\/", "/")?.replace("\\", "")?.trim()
            if (!raw.isNullOrBlank() && raw != "about:blank") return raw
        }
        return null
    }

    /**
     * iframe adresinden oynatıcı sayfasının adresini kurar.
     *
     * @param mainUrl sitenin kök adresi (sonunda / olmayabilir)
     */
    fun toPlayerUrl(iframe: String, mainUrl: String): String {
        val value = iframe.trim()
        val lower = value.lowercase()

        // ! GÜNCELLEME (2026-10): Sitenin iframe'i artık oynatıcının KENDİ
        // ! mutlak adresidir ve host'u mainUrl'den farklıdır:
        //!    https://hdfilmcehennemi.mobi/video/embed/<id>/?rapidrame_id=<id>
        // ! Bu adres `/rplayer/<id>/` biçimine ÇEVRİLMEMELİ; eski dönüşüm
        // ! geçerli embed adresini 404 bir site-içi yola çeviriyordu
        // ! ("0 kaynak"). Adres olduğu gibi korunur.
        if (lower.contains("/video/embed/")) return value

        // ! Site-içi oynatıcı yolu: sonda / şart (yoksa sunucu 301 döndürüyor
        // ! ve oynatıcı sayfası boş geliyor).
        if (lower.contains("/rplayer/") || lower.contains("/playerr/")) {
            return if (value.endsWith("/")) value else "$value/"
        }

        // ! Eski biçim: harici bir adres oynatıcı kimliğini sorgu dizesinde
        // ! taşıyor (…?rapidrame_id=<id>). Bu durumda site-içi yola çevrilir.
        val rapidrameId = Regex("""[?&]rapidrame_id=([^&#]+)""", RegexOption.IGNORE_CASE)
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            .orEmpty()
        if (rapidrameId.isNotBlank()) {
            return "${mainUrl.trimEnd('/')}/rplayer/$rapidrameId/"
        }
        return value
    }

    /** Siteye ait oynatıcı yolları — kendi host'unda olsalar da geçerli kaynaktır. */
    fun isPlayerPath(url: String): Boolean {
        val path = runCatching { java.net.URI(url).path?.lowercase().orEmpty() }.getOrDefault("")
        return PLAYER_PATHS.any { path.contains(it) }
    }

    /**
     * Ham embed adresini kaynak adresine çevirir.
     * @return geçerli kaynak değilse null
     */
    fun normalizeEmbed(raw: String, pageUrl: String?, mainUrl: String): String? {
        var value = raw.replace("\\/", "/").replace("\\", "").trim().trim('"', '\'')
        if (value.isBlank() || value == "about:blank") return null
        // ! Görsel/ikon adresleri kaynak değildir (lazyload data-src'leri elenir).
        if (FallbackCandidates.isImageUrl(value)) return null
        if (value.startsWith("//")) value = "https:$value"
        if (!value.startsWith("http")) {
            val base = pageUrl ?: mainUrl
            value = absolutizeUrl(value, base) ?: return null
        }
        if (!value.startsWith("http")) return null
        // ! Kendi sayfamızın adresi kaynak değildir; sonsuz döngüyü keser.
        // !
        // ! ANCAK sitenin ASIL oynatıcısı (/rplayer/, /playerr/, /embed/ ...)
        // ! yine kendi host'unda oluyor. Bu kural onları eliyor ve oynatmayı
        // ! tamamen engelliyordu ("0 kaynak"). Oynatıcı yolları serbest bırakıldı.
        if (originOf(value) == originOf(mainUrl) && !isPlayerPath(value) && !isDirectMediaUrl(value)) return null
        return value
    }

    private fun originOf(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host ?: return null
            val scheme = uri.scheme ?: "https"
            val port = if (uri.port > 0 && uri.port != 80 && uri.port != 443) ":${uri.port}" else ""
            "$scheme://$host$port"
        } catch (e: Exception) {
            null
        }
    }

    private fun absolutizeUrl(raw: String, baseUrl: String): String? {
        val value = raw.trim()
        if (value.isBlank()) return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        if (value.startsWith("//")) return "https:$value"
        return try {
            java.net.URI(baseUrl).resolve(value).toString()
        } catch (e: Exception) {
            null
        }
    }
}
