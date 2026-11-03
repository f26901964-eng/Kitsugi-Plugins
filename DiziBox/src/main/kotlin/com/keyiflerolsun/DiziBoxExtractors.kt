// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Kitsugi `CsStreamRunner.resolveEmbedUrl` aşaması `loadExtractor` listesini TERSTEN tarar.
 *
 * 1) `MolyStream*` sınıfları: `molystream.org` yerleşik kütüphanede bulunmadığından ve
 *    uzantısız `/embed/sheila/{id}` uç noktaları embed kuyruğuna yönlendirildiğinde `Referer`
 *    korunarak çözümlenebilsin diye kaydedilir.
 * 2) `DiziBoxOkRu*` ve `DiziBoxMailRuExtractor`: CloudStream AAR içindeki yerleşik
 *    `Odnoklassniki` ve `MailRu` sınıfları `kotlinx.serialization.SerializationException` /
 *    Jackson `readValue` referansı taşıdığından Kitsugi çalışma zamanında `NoClassDefFoundError`
 *    (`KTS-CSP-9110`) fırlatır; saf Kotlin sürümleri bunların önüne geçer.
 */
open class MolyStreamExtractor : ExtractorApi() {
    override val name            = "MolyStream"
    override val mainUrl         = "https://www.molystream.org"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val embedPageUrl = DiziBoxPlayerParser.molyStreamPlayerPageUrl(url) ?: url
        val sheilaUrl = if (DiziBoxPlayerParser.isMolyStreamStreamUrl(url)) {
            url
        } else {
            DiziBoxPlayerParser.molyStreamSheilaUrl(embedPageUrl)
        }
        val interceptor = DiziBoxNetwork.interceptorFor()

        if (sheilaUrl != null) {
            for ((idx, pair) in DiziBoxPlayerParser.molyStreamSheilaCandidates(sheilaUrl, embedPageUrl).withIndex()) {
                val (candidateSheila, candidateEmbedRef) = pair
                val budget = if (idx == 0) 12_000L else 8_000L
                val playlist = fetchMolyManifest(candidateSheila, candidateEmbedRef, interceptor, budget) ?: continue
                emitMolyVariants(playlist.first, playlist.second, candidateEmbedRef, callback)
                return
            }
        }

        val parentRef = referer?.takeIf { it.isNotBlank() } ?: "https://www.dizibox.live/"
        for ((idx, candidateEmbed) in DiziBoxPlayerParser.molyStreamEmbedCandidates(embedPageUrl).withIndex()) {
            val budget = if (idx == 0) 12_000L else 8_000L
            val html = try {
                withTimeoutOrNull(budget) {
                    app.get(
                        candidateEmbed,
                        headers     = DiziBoxNetwork.buildStreamHeaders(parentRef),
                        referer     = parentRef,
                        interceptor = interceptor,
                    ).text
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            } ?: continue

            if (DiziBoxPlayerParser.isHlsManifest(html)) {
                emitMolyVariants(candidateEmbed, html, candidateEmbed, callback)
                return
            }

            val decoded = DiziBoxPlayerParser.decodeWrapperHtml(html)
            DiziBoxPlayerParser.subtitleTracks(decoded).forEach { track ->
                val subUrl = DiziBoxPlayerParser.resolveUrl(track.url, candidateEmbed) ?: return@forEach
                subtitleCallback(
                    newSubtitleFile(track.label.ifBlank { "Altyazı" }, subUrl) {
                        this.headers = DiziBoxNetwork.buildStreamHeaders(candidateEmbed)
                    },
                )
            }

            var found = false
            for (source in DiziBoxPlayerParser.mediaSources(decoded)) {
                val mediaUrl = DiziBoxPlayerParser.resolveUrl(source.url, candidateEmbed) ?: continue
                if (DiziBoxPlayerParser.isMolyStreamStreamUrl(mediaUrl)) {
                    val playlist = fetchMolyManifest(mediaUrl, candidateEmbed, interceptor, 10_000L) ?: continue
                    emitMolyVariants(playlist.first, playlist.second, candidateEmbed, callback)
                    found = true
                } else if (DiziBoxPlayerParser.isHls(source)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = if (source.label.isNotBlank()) "$name ${source.label}" else name,
                            url    = mediaUrl,
                            type   = ExtractorLinkType.M3U8,
                        ) {
                            this.referer = candidateEmbed
                            this.headers = DiziBoxNetwork.buildStreamHeaders(candidateEmbed)
                            this.quality = getQualityFromName(source.label)
                        },
                    )
                    found = true
                } else if (DiziBoxPlayerParser.isMp4(source)) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name   = if (source.label.isNotBlank()) "$name ${source.label}" else name,
                            url    = mediaUrl,
                            type   = ExtractorLinkType.VIDEO,
                        ) {
                            this.referer = candidateEmbed
                            this.headers = DiziBoxNetwork.buildStreamHeaders(candidateEmbed)
                            this.quality = getQualityFromName(source.label)
                        },
                    )
                    found = true
                }
            }
            if (found) return
        }
    }

    private suspend fun fetchMolyManifest(
        url: String,
        embedReferer: String,
        interceptor: okhttp3.Interceptor,
        timeoutMs: Long,
    ): Pair<String, String>? = try {
        withTimeoutOrNull(timeoutMs) {
            val body = app.get(
                url,
                headers     = DiziBoxNetwork.buildStreamHeaders(embedReferer),
                referer     = embedReferer,
                interceptor = interceptor,
            ).text
            when {
                DiziBoxPlayerParser.isHlsManifest(body) -> url to body
                else -> {
                    val hop = DiziBoxPlayerParser.plainHttpsTarget(body) ?: return@withTimeoutOrNull null
                    val hopBody = app.get(
                        hop,
                        headers     = DiziBoxNetwork.buildStreamHeaders(embedReferer),
                        referer     = embedReferer,
                        interceptor = interceptor,
                    ).text
                    if (DiziBoxPlayerParser.isHlsManifest(hopBody)) hop to hopBody else null
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    private suspend fun emitMolyVariants(
        playlistUrl: String,
        body: String,
        embedReferer: String,
        callback: (ExtractorLink) -> Unit,
    ) {
        val headers = DiziBoxNetwork.buildStreamHeaders(embedReferer)
        val variants = DiziBoxPlayerParser.hlsVariants(body, playlistUrl)
        if (variants.isEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = playlistUrl,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = embedReferer
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                },
            )
            return
        }
        for (variant in variants) {
            val qLabel = variant.height.takeIf { it > 0 }?.let { "${it}p" }.orEmpty()
            callback(
                newExtractorLink(
                    source = name,
                    name   = if (qLabel.isNotEmpty()) "$name $qLabel" else name,
                    url    = variant.url,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = embedReferer
                    this.headers = headers
                    this.quality = variant.height.takeIf { it > 0 } ?: Qualities.Unknown.value
                },
            )
        }
    }
}

class MolyStreamDbxExtractor : MolyStreamExtractor() {
    override val mainUrl = "https://dbx.molystream.org"
}

class MolyStreamNetExtractor : MolyStreamExtractor() {
    override val mainUrl = "https://molystream.net"
}

class MolyStreamYdxExtractor : MolyStreamExtractor() {
    override val mainUrl = "https://ydx.molystream.org"
}

open class DiziBoxOkRuExtractor : ExtractorApi() {
    override val name            = "Odnoklassniki"
    override val mainUrl         = "https://ok.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val okHeaders = mapOf(
            "Accept"     to "*/*",
            "Origin"     to "https://ok.ru",
            "User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT,
        )
        val effectiveRef = referer?.takeIf { it.isNotBlank() } ?: "https://ok.ru/"

        var streams = emptyList<DiziBoxPlayerParser.OkRuStream>()
        val candidates = DiziBoxPlayerParser.okRuEmbedUrls(url)
        for (candidate in candidates) {
            streams = try {
                val html = withTimeoutOrNull(9_000L) {
                    app.get(candidate, headers = okHeaders, referer = effectiveRef).text
                } ?: continue
                DiziBoxPlayerParser.parseOkRuStreams(html)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
            if (streams.isNotEmpty()) break
        }

        val videoId = DiziBoxPlayerParser.okRuVideoId(url)
        if (streams.isEmpty() && videoId != null) {
            val primaryEmbed = candidates.firstOrNull() ?: "https://ok.ru/videoembed/$videoId"
            streams = try {
                val metaJson = withTimeoutOrNull(9_000L) {
                    app.post(
                        "https://ok.ru/dk?cmd=videoPlayerMetadata&mid=$videoId",
                        headers = okHeaders,
                        referer = primaryEmbed,
                    ).text
                }
                metaJson?.let { DiziBoxPlayerParser.parseOkRuStreams(it) }.orEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                emptyList()
            }
        }

        val playbackHeaders = mapOf(
            "Referer"    to "https://ok.ru/",
            "Origin"     to "https://ok.ru",
            "User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT,
        )

        var emittedHls = false
        for (stream in streams.filter { it.isHls }) {
            callback(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = stream.url,
                    type   = ExtractorLinkType.M3U8,
                ) {
                    this.referer = "https://ok.ru/"
                    this.headers = playbackHeaders
                    this.quality = Qualities.Unknown.value
                },
            )
            emittedHls = true
        }

        for (stream in streams.filterNot { it.isHls }) {
            if (emittedHls && !DiziBoxPlayerParser.isClearlyDirectStreamUrl(stream.url)) continue
            callback(
                newExtractorLink(
                    source = name,
                    name   = if (stream.qualityLabel.isNotBlank()) "$name ${stream.qualityLabel}" else name,
                    url    = stream.url,
                    type   = ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "https://ok.ru/"
                    this.headers = playbackHeaders
                    this.quality = getQualityFromName(stream.qualityLabel)
                },
            )
        }
    }
}

class DiziBoxOkRuHttpExtractor : DiziBoxOkRuExtractor() {
    override val mainUrl = "http://ok.ru"
}

class DiziBoxOkRuWwwExtractor : DiziBoxOkRuExtractor() {
    override val mainUrl = "https://www.ok.ru"
}

class DiziBoxOkRuMobileExtractor : DiziBoxOkRuExtractor() {
    override val mainUrl = "https://m.ok.ru"
}

class DiziBoxOdnoklassnikiExtractor : DiziBoxOkRuExtractor() {
    override val mainUrl = "https://odnoklassniki.ru"
}

class DiziBoxMailRuExtractor : ExtractorApi() {
    override val name            = "MailRu"
    override val mainUrl         = "https://my.mail.ru"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ) {
        val uaHeaders = mapOf("User-Agent" to DiziBoxNetwork.UNIFIED_USER_AGENT)
        val resp = try {
            withTimeoutOrNull(9_000L) {
                app.get(url, headers = uaHeaders, referer = referer ?: "https://my.mail.ru/")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return

        var cookieHeader = DiziBoxNetwork.extractSetCookiePair(
            resp.okhttpResponse.headers.values("Set-Cookie"),
            "video_key",
        )
        var streams = DiziBoxPlayerParser.parseMailRuStreams(resp.text)

        if (streams.isEmpty()) {
            val metaPath = Regex(""""metaUrl"\s*:\s*"([^"]+)"""").find(resp.text)?.groupValues?.get(1)
            val metaUrl = DiziBoxPlayerParser.resolveUrl(metaPath?.replace("\\/", "/"), url)
            if (metaUrl != null) {
                val metaResp = try {
                    withTimeoutOrNull(9_000L) {
                        app.get(metaUrl, headers = uaHeaders, referer = url)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (metaResp != null) {
                    if (cookieHeader == null) {
                        cookieHeader = DiziBoxNetwork.extractSetCookiePair(
                            metaResp.okhttpResponse.headers.values("Set-Cookie"),
                            "video_key",
                        )
                    }
                    streams = DiziBoxPlayerParser.parseMailRuStreams(metaResp.text)
                }
            }
        }

        if (streams.isEmpty()) return
        val headers = buildMap {
            put("Referer", "https://my.mail.ru/")
            put("Origin", "https://my.mail.ru")
            put("User-Agent", DiziBoxNetwork.UNIFIED_USER_AGENT)
            if (!cookieHeader.isNullOrBlank()) put("Cookie", cookieHeader)
        }
        for (stream in streams) {
            val isHls = stream.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
            callback(
                newExtractorLink(
                    source = name,
                    name   = if (stream.qualityLabel.isNotBlank()) "$name ${stream.qualityLabel}" else name,
                    url    = stream.url,
                    type   = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                ) {
                    this.referer = "https://my.mail.ru/"
                    this.headers = headers
                    this.quality = getQualityFromName(stream.qualityLabel)
                },
            )
        }
    }
}
