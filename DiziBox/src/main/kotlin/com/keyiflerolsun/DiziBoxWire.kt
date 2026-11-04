package com.keyiflerolsun

import okhttp3.Request
import java.net.URI

/**
 * Saf OkHttp / URI tel seviyesi (wire-level) başlık, çerez ve `Sec-Fetch-*` normalleştiricisi.
 *
 * CloudStream `compileOnly` sınıflarına bağımlı olmadığından JVM birim testlerinde
 * (`testDebugUnitTest`) doğrudan koşturulup doğrulanabilir.
 */
internal object DiziBoxWire {
    const val UNIFIED_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /**
     * Sitenin kendi head betiğinin set ettiği çerezler (2026-11-04 dump doğrulaması):
     *
     * ```js
     * "true"!=getCookie("isTrustedUser") && (
     *     setCookie("LockUser","true",1),
     *     setCookie("isTrustedUser","true",30)
     * );
     * ```
     *
     * ! `dbxu` çerezi sitede YOK (4 dump'ta 0 eşleşme). Elle uydurulmuş bir değerdi;
     * ! sunucunun bilinmeyen çerezi bot işareti sayma riski yüzünden kaldırıldı.
     */
    fun diziboxCookies(): Map<String, String> = mapOf(
        "LockUser"      to "true",
        "isTrustedUser" to "true",
    )

    fun normalizeWireRequest(request: Request, siteUrl: String): Request {
        val reqUrl = request.url.toString()
        val referer = request.header("Referer")
        val isXhr = request.header("X-Requested-With").equals("XMLHttpRequest", ignoreCase = true)

        val extraCookies = if (isDiziBoxHost(reqUrl, siteUrl)) diziboxCookies() else emptyMap()
        val mergedCookie = DiziBoxPlayerParser.mergeCookieHeaders(
            existingCookieHeaders = request.headers.values("Cookie"),
            extraCookies          = extraCookies,
        )
        val fetchMeta = DiziBoxPlayerParser.classifyFetchMetadata(
            requestUrl       = reqUrl,
            refererUrl       = referer,
            isXmlHttpRequest = isXhr,
        )

        val builder = request.newBuilder()
        builder.removeHeader("Cookie")
        if (!mergedCookie.isNullOrBlank()) {
            builder.header("Cookie", mergedCookie)
        }

        builder.removeHeader("User-Agent")
        builder.header("User-Agent", UNIFIED_USER_AGENT)

        builder.removeHeader("Sec-Fetch-Dest")
        builder.removeHeader("Sec-Fetch-Mode")
        builder.removeHeader("Sec-Fetch-Site")
        builder.removeHeader("Sec-Fetch-User")
        builder.header("Sec-Fetch-Dest", fetchMeta.dest)
        builder.header("Sec-Fetch-Mode", fetchMeta.mode)
        builder.header("Sec-Fetch-Site", fetchMeta.site)
        if (fetchMeta.includeUser) {
            builder.header("Sec-Fetch-User", "?1")
        }
        if (!fetchMeta.includeUpgradeInsecure) {
            builder.removeHeader("Upgrade-Insecure-Requests")
        }

        return builder.build()
    }

    fun isDiziBoxHost(url: String, siteUrl: String = "https://www.dizibox.live"): Boolean {
        val host = DiziBoxPlayerParser.normalizedHost(url)?.removePrefix("www.") ?: return false
        val siteHost = DiziBoxPlayerParser.normalizedHost(siteUrl)?.removePrefix("www.") ?: return false
        return host.isNotBlank() && host == siteHost
    }

    fun originOf(url: String): String? = runCatching {
        val uri = URI(url)
        val scheme = uri.scheme ?: return@runCatching null
        val authority = uri.rawAuthority ?: return@runCatching null
        "$scheme://$authority"
    }.getOrNull()

    fun buildStreamHeaders(
        referer: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out["Referer"] = referer
        originOf(referer)?.let { out["Origin"] = it }
        out["User-Agent"] = UNIFIED_USER_AGENT
        for ((k, v) in extraHeaders) {
            when {
                k.equals("referer", ignoreCase = true) -> out["Referer"] = v
                k.equals("origin", ignoreCase = true) -> out["Origin"] = v
                k.equals("user-agent", ignoreCase = true) -> out["User-Agent"] = UNIFIED_USER_AGENT
                v.isNotBlank() -> out[k] = v
            }
        }
        return out
    }

    fun extractSetCookiePair(setCookies: List<String>, cookieName: String): String? {
        val prefix = "$cookieName="
        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            if (pair.startsWith(prefix, ignoreCase = true)) return pair
        }
        return null
    }
}
