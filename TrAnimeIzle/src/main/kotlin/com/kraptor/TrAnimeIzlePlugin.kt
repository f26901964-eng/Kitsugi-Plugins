package com.kraptor

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * TrAnimeIzle eklenti girişi (DEX'teki TrAnimeIzlePlugin'in yeniden yazımı).
 *
 * - [TrAnimeIzleHelper.setup]: v28'de imza kapısı KALDIRILDI (no-op); eskiden
 *   izinli olmayan kurulumlarda eklenti sessizce boş sonuç dönüyordu.
 * - Domain, "DomainListesi" tercihleri üzerinden çözülür (Companion.getDomain).
 * - Üç oynatıcı çözücü: OpTraco, Hdvid, Hexload.
 */
@CloudstreamPlugin
class TrAnimeIzlePlugin : Plugin() {

    override fun load(context: Context) {
        TrAnimeIzleHelper.setup(context)
        val domainPrefs =
            context.getSharedPreferences("DomainListesi", Context.MODE_PRIVATE)
        registerMainAPI(TrAnimeIzle(context, domainPrefs))
        registerExtractorAPI(OptracoExtractor())
        registerExtractorAPI(HdvidExtractor())
        registerExtractorAPI(Hexload())
    }
}
