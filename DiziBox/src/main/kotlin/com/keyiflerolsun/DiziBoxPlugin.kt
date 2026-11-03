package com.keyiflerolsun

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziBoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DiziBox())
        registerExtractorAPI(MolyStreamExtractor())
        registerExtractorAPI(MolyStreamDbxExtractor())
        registerExtractorAPI(MolyStreamNetExtractor())
        registerExtractorAPI(MolyStreamYdxExtractor())
        registerExtractorAPI(DiziBoxOkRuExtractor())
        registerExtractorAPI(DiziBoxOkRuHttpExtractor())
        registerExtractorAPI(DiziBoxOkRuWwwExtractor())
        registerExtractorAPI(DiziBoxOkRuMobileExtractor())
        registerExtractorAPI(DiziBoxOdnoklassnikiExtractor())
        registerExtractorAPI(DiziBoxMailRuExtractor())
    }
}
