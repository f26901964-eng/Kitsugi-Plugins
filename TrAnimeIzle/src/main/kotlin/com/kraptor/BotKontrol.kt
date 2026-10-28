package com.kraptor

/**
 * TrAnimeIzle — oturum/çerez yardımcıları.
 *
 * ## v41: Android UI bağımlılığı KALDIRILDI (çalışma zamanı uyumsuzluğu düzeltmesi)
 *
 * Ölçüm (yayındaki `TrAnimeIzle.cs3` v40 dex'i):
 *
 * | Paket | TrAnimeIzle v40 | Anizm v16 (çalışıyor) | HDFilmCehennemi v63 (çalışıyor) |
 * | --- | --- | --- | --- |
 * | `android.webkit` (WebView, CookieManager…) | **VAR** | yok | yok |
 * | `android.app` / `android.view` / `android.widget` (AlertDialog…) | **VAR** | yok | yok |
 *
 * Bu sınıfların hepsi v17–v40 arasındaki **WebView "bot kontrolü" diyaloğundan**
 * geliyordu: eklenti, uygulama içinde bir AlertDialog + WebView açıp siteyi
 * yüklemeye çalışıyordu. Çalışan hiçbir eklenti bunu yapmıyor.
 *
 * Sonuç: uygulamanın eklenti sınıf yükleyicisi bu sınıflardan birini çözemediğinde
 * **"eklenti çalışma zamanı uyumsuzluğu" (KTS-CSP-9110 / LinkageError)** hatası
 * veriyor ve eklenti hiç açılmıyor — yani arama da, kaynaklar da boş kalıyor.
 *
 * Neden WebView'e zaten gerek yok:
 *  - WebView katmanı **veri merkezi IP engeli** (Cloudflare Error 1005) için
 *    yazılmıştı: ev/telefon bağlantısıyla site normal açılıyor.
 *  - Kullanıcı zaten **telefondan** bağlanıyor → engel yok → diyalog gereksiz.
 *  - Site korumalı isteği `/api/CaptchaChallenge`'a yönlendirdiğinde ikon
 *    captcha **HTTP ile** çözülüyor (v39) — WebView gerekmiyor.
 *
 * Kalan işlevler saf Kotlin: çerez dizgesi ayrıştırma ve yardımcı sabitler.
 */
object BotKontrolKt {

    const val TAG = "kraptor_TrAnime"

    /** Site ile aynı tarayıcı imzası (Hdvid / OpTraco oynatıcıları da bunu ister). */
    const val UA_FIREFOX =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0"

    /** Sitenin oturum çerezi (giriş şart değil; anonim oturum da bu adı kullanır). */
    const val SESSION_COOKIE_NAME = "AitrWeb.Session"

    /**
     * Sayfa çekme sonucu: çerez seti + HTML.
     *
     * v41: WebView kaldırıldığı için bu tip artık yalnızca **HTTP** sonucu taşır
     * (`fetchWithSession` doldurur). Tip, çağrı yerlerini kırmamak için korunur.
     */
    data class WebFetchResult(val jar: String, val html: String)

    /** Çerez dizgesinde oturum çerezi var mı? */
    fun jarHasSession(jar: String): Boolean =
        jar.split(";").any { it.trim().startsWith("$SESSION_COOKIE_NAME=") }

    /**
     * Çerez dizgesi içinden `.AitrWeb.Session=...` parçasını döndürür
     * (bulunamazsa boş string).
     */
    fun extractSessionCookie(cookieHeader: String?): String {
        if (cookieHeader.isNullOrBlank()) return ""
        return cookieHeader.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("$SESSION_COOKIE_NAME=") }
            ?: ""
    }

    /**
     * Challenge URL'sinden hash parametresini çıkarır
     * (ör. `.../api/Captcha/?cid=0&hash=abc123` → `abc123`).
     */
    fun extractHashFromUrl(url: String): String {
        return url.substringAfter('?', "")
            .split('&')
            .also { parts -> if (parts.isEmpty()) return "" }
            .firstOrNull { it.startsWith("hash=") }
            ?.substringAfter("hash=")
            ?.takeIf { it.isNotBlank() }
            ?: ""
    }

    /** `a=1; b=2` → `{a=1, b=2}` (bozuk parçalar atlanır). */
    fun cookiePairs(jar: String): Map<String, String> =
        jar.split(";")
            .map { it.trim() }
            .filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
}
