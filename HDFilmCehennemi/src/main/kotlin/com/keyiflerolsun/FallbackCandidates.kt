// ! HDFilmCehennemi — düz (şifresiz) yedek aday seçici.
//
// ! NEDEN AYRI DOSYA?
// ! Bu mantık android.* bağımlılığı içermez; böylece canlı sayfadan alınmış
// ! gerçek HTML üzerinde birim testiyle doğrulanabilir.
//
// ! ÇÖZÜLEN HATA (canlı sayfa ile kanıtlandı):
// !   Hdfilmcehennemi bölüm sayfasının JSON-LD'sinde contentUrl alanı VARDIR
// !   ama VİDEO DEĞİLDİR — afişin adresidir:
// !
// !     "thumbnail":{"@type":"ImageObject",
// !                  "contentUrl":"https://.../uploads/poster/supernatural.jpg"}
// !
// !   Eski kod `"contentUrl"\s*:\s*"([^"]+)"` ile bunu yakalıyor, adres
// !   200 döndüğü için "canlı kaynak" sayılıyor ve oynatıcıya video diye
// !   gönderiliyordu. Sonuç: "video verisi gelmiyor".
// !
// !   Kural: görsel uzantılı (veya thumbnail/ImageObject bloğundaki) adres
// !   ASLA yayın adayı olamaz.

package com.keyiflerolsun

object FallbackCandidates {

    /** Yayın olamayacak görsel uzantıları. */
    private val IMAGE_EXTENSIONS = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif", ".bmp", ".ico", ".tiff"
    )

    /** Görsel adresi mi? (uzantıya ve data: URI'ye bakar) */
    fun isImageUrl(url: String): Boolean {
        val path = url.trim().lowercase().substringBefore('?').substringBefore('#')
        if (path.startsWith("data:")) return true
        return IMAGE_EXTENSIONS.any { path.endsWith(it) }
    }

    /**
     * HTML içindeki `contentUrl` değerlerini döndürür — görseller HARİÇ.
     * ! JSON-LD'deki `thumbnail > ImageObject > contentUrl` bloğu atlanır.
     */
    fun contentUrls(html: String): List<String> {
        val results = mutableListOf<String>()
        val regex = Regex(""""contentUrl"\s*:\s*"([^"]+)"""")
        for (match in regex.findAll(html)) {
            val raw = match.groupValues.getOrNull(1)?.replace("\\/", "/")?.trim() ?: continue
            if (raw.isBlank()) continue
            if (isImageUrl(raw)) continue
            // ! Eşleşmeden önceki 240 karakterde "thumbnail" varsa bu bir
            // ! görsel nesnesidir; uzantı gizlenmiş olsa da aday olmaz.
            val start = (match.range.first - 240).coerceAtLeast(0)
            val context = html.substring(start, match.range.first).lowercase()
            if (context.contains("thumbnail")) continue
            results.add(raw)
        }
        return results.distinct()
    }

    /**
     * Şifresiz (düz) tüm yedek adayları: contentUrl (görsel değil),
     * master.txt ve .m3u8 adresleri.
     * ! Sıra korunur: contentUrl önce, sonra playlist yolları.
     */
    fun plainCandidates(html: String): List<String> {
        val candidates = mutableListOf<String>()
        candidates.addAll(contentUrls(html))
        Regex("""https?://[^\s"']+/master\.txt[^\s"']*""").findAll(html).forEach { candidates.add(it.value) }
        Regex("""https?://[^\s"']+\.m3u8[^\s"']*""").findAll(html).forEach { candidates.add(it.value) }
        return candidates.filterNot { isImageUrl(it) }.distinct()
    }
}
