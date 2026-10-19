package com.keyiflerolsun

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

/** FilmMakinesi sayfaları Cloudflare kontrolü döndürdüğünde asıl HTML'i alır. */
internal object FilmMakinesiHttp {
    private val cloudflareKiller by lazy { CloudflareKiller() }
    val interceptor: Interceptor by lazy { FilmMakinesiCloudflareInterceptor(cloudflareKiller) }
}

private class FilmMakinesiCloudflareInterceptor(
    private val cloudflareKiller: CloudflareKiller
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val contentType = response.header("Content-Type").orEmpty().lowercase()
        val looksLikeHtml = contentType.contains("text/html") || contentType.contains("application/xhtml+xml")
        if (!looksLikeHtml && response.code !in 403..503) return response

        val html = response.peekBody(256 * 1024).string()
        val lower = html.lowercase()
        val challengePage = lower.contains("just a moment") ||
            lower.contains("cf-browser-verification") ||
            lower.contains("checking your browser") ||
            (response.code in 403..503 && lower.contains("cloudflare"))

        if (!challengePage) return response

        // Zincirde aynı isteği CloudflareKiller ile tekrarlamadan önce gövdeyi kapat.
        response.close()
        return cloudflareKiller.intercept(chain)
    }
}
