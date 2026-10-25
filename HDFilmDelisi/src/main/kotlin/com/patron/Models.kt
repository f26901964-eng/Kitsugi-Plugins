package com.patron

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class AltyaziAPI(
    @JsonProperty("subtitles") val subtitles: List<TurkceAltyazi> = emptyList(),
    @JsonProperty("cacheMaxAge") val cacheMaxAge: Long = 0,
    @JsonProperty("staleRevalidate") val staleRevalidate: Long = 0,
    @JsonProperty("staleError") val staleError: Long = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TurkceAltyazi(
    @JsonProperty("url") val url: String = "",
    @JsonProperty("id") val id: String = "",
    @JsonProperty("lang") val lang: String = "",
    @JsonProperty("episode") val episode: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Subtitle(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("subEncoding") val subEncoding: String = "",
    @JsonProperty("lang") val lang: String = "",
    @JsonProperty("m") val m: String = "",
    @JsonProperty("g") val g: String = "",
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SubtitlesAPI(
    @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList(),
    @JsonProperty("cacheMaxAge") val cacheMaxAge: Long = 0,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WyZIESUB(
    @JsonProperty("id") val id: String = "",
    @JsonProperty("url") val url: String = "",
    @JsonProperty("flagUrl") val flagUrl: String = "",
    @JsonProperty("format") val format: String = "",
    @JsonProperty("display") val display: String = "",
    @JsonProperty("language") val language: String = "",
    @JsonProperty("media") val media: String = "",
    @JsonProperty("isHearingImpaired") val isHearingImpaired: Boolean = false,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WyZIEResponse(
    @JsonProperty("subtitles") val subtitles: List<WyZIESUB>? = null,
)
