package com.patron

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HDFilmDelisiPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HDFilmDelisi())
        registerExtractorAPI(VidMody())
    }
}
