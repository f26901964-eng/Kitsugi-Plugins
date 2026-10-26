package com.nikyokki

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class HDFilmIzlePlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmIzle())
        // HDFilmİzle oynatıcı ailesinin tamamı: XOR/EE.dd/SPG/FastPlay akışları
        // HDFilmIzlePlayerResolver içinde ortak çözücüye bağlanır.
        registerExtractorAPI(VidRameExtractor())
        registerExtractorAPI(VidMoxyExtractor())
        registerExtractorAPI(SetPlayExtractor())
        registerExtractorAPI(FastPlayExtractor())
    }
}
