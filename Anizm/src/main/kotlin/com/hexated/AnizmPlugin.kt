package com.hexated

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

/**
 * Anizm (puffytr.com) eklenti girişi.
 *
 * - [Anizm]: MainAPI — arama / katalog / detay / bölüm zinciri.
 *
 * Site Cloudflare'da datacenter IP'lerini (1005) engeller; normal cihaz
 * (rezidans) IP'si engellenmediği için eklenti düz HTTP ile çalışır —
 * WebView/oturum mekanizması gerekmez. Yaş duvarı ise SADECE client-side
 * (sessionStorage/localStorage — kanıt: v3-content-warning.js v1.0.5);
 * video API'lerinde server-side duvar YOKTUR (kanıt: /bolum/{eid}/cevirmen/{tid}
 * düz GET ile JSON döner).
 */
@CloudstreamPlugin
class AnizmPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Anizm())
    }
}
