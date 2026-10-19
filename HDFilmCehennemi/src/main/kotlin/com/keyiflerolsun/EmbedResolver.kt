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
     */
    fun preferredPlayerDataSrcs(document: org.jsoup.nodes.Document): List<String> =
        document.select("iframe[data-src]")
            .filter { frame ->
                frame.parents().any {
                    it.hasClass("video-container") || it.hasClass("hdmv-play-container") ||
                        it.hasClass("player") || it.hasClass("video-player")
                }
            }
            .map { it.attr("data-src").trim() }
            .filter { it.isNotBlank() }
            .distinct()

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
        val rapidrameId = iframe.substringAfter("?rapidrame_id=", "").substringBefore("&").trim()
        if (rapidrameId.isNotBlank()) {
            return "${mainUrl.trimEnd('/')}/rplayer/$rapidrameId/"
        }
        if (iframe.contains("/rplayer/") || iframe.contains("/playerr/")) {
            return if (iframe.endsWith("/")) iframe else "$iframe/"
        }
        return iframe
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
        val lower = value.lowercase()
        if (listOf(".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif").any { lower.contains(it) }) return null
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
        if (originOf(value) == originOf(mainUrl) && !isPlayerPath(value)) return null
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
