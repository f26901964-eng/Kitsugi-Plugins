package com.kraptor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * TrAnimeIzle eklenti girişi.
 *
 * ## v41: çalışma zamanı uyumsuzluğu (KTS-CSP-9110) düzeltmesi
 *
 * Eklenti iki yerden Android çerçeve sınıflarına bağlanıyordu; ikisi de kaldırıldı:
 *
 * 1. **WebView/AlertDialog "bot kontrolü" diyaloğu** (`BotKontrol.kt`) —
 *    `android.webkit.*`, `android.app.*`, `android.view.*`, `android.widget.*`.
 *    Bu sınıflar eklenti sınıf yükleyicisinde çözülemediğinde uygulama
 *    "çalışma zamanı uyumsuzluğu" hatası verip eklentiyi hiç açmıyordu.
 *    Çalışan hiçbir eklenti (Anizm, HDFilmCehennemi…) bu paketlere bağlanmıyor.
 *
 * 2. **`SharedPreferences` tercih okuması** — çalışan eklentilerin hiçbiri
 *    `android.content.SharedPreferences` kullanmıyor. Domain seçimi artık
 *    eklentinin kendi içinde (canlı domain doğrulaması + aday listesi) yapılıyor.
 *
 * `android.content.Context` yalnızca [Plugin.load] imzası gereği burada durur;
 * eklenti kodunun geri kalanı saf Kotlin/CloudStream API'sidir.
 */
@CloudstreamPlugin
class TrAnimeIzlePlugin : Plugin() {

    override fun load(context: Context) {
        // v41: Context'in tek kullanımı API imzası; prefs okuması YOK.
        TrAnimeIzleHelper.setup(context)
        registerMainAPI(TrAnimeIzle())
        registerExtractorAPI(OptracoExtractor())
        registerExtractorAPI(HdvidExtractor())
        registerExtractorAPI(Hexload())
    }
}
