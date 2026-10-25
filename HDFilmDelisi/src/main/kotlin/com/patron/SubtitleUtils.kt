//Eklenti kraptor reposundan çekilip düzenlenmiştir.
package com.patron

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import java.text.Normalizer
import java.util.Locale

suspend fun invokeSubtitleAPI(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    try {
        val url = if (season == null) {
            "https://opensubtitles-v3.strem.io/subtitles/movie/$id.json"
        } else {
            "https://opensubtitles-v3.strem.io/subtitles/series/$id:$season:$episode.json"
        }
        val headers = mapOf("User-Agent" to "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")
        val response = app.get(url, headers = headers, timeout = 10).text
        val parsed = parseJson<SubtitlesAPI>(response)
        parsed.subtitles.forEach { sub ->
            val lang = getLanguage(sub.lang)
            if (lang == "Turkish") {
                Log.d("OpenSubtitlesV3", "TR Altyazı: ${sub.url}")
                subtitleCallback(newSubtitleFile("OpenSubTürkçe", sub.url))
            }
        }
    } catch (e: Exception) {
        Log.d("OpenSubtitlesV3", "Hata: ${e.message}")
    }
}

suspend fun invokeTurkceAltyaziAPI(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    try {
        val tag = "TurkceAltyazi"
        val type = if (season == null) "movie" else "series"
        val queryId = if (season == null) id else "$id:$season:$episode"
        val addonHost = "https://turkcealtyazi.net"
        val url = "$addonHost/subtitles/$type/$queryId.json"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0",
            "Origin"     to addonHost
        )
        val response = app.get(url, headers = headers).text
        val json = parseJson<AltyaziAPI>(response)
        json.subtitles.forEach { sub ->
            if (sub.lang.contains("tr", ignoreCase = true)) {
                subtitleCallback(newSubtitleFile("TürkçeAltyazı", sub.url))
            }
        }
    } catch (e: Exception) {
        Log.d("TurkceAltyazi", "Hata: ${e.message}")
    }
}

suspend fun invokeWyZIESUBAPI(
    id: String? = null,
    season: Int? = null,
    episode: Int? = null,
    subtitleCallback: (SubtitleFile) -> Unit
) {
    try {
        val tag = "WyZIESUB"
        val fullKey = if (season == null) "movie/$id" else "series/$id:$season:$episode"
        val url = "https://wyziesub.com/subtitles/$fullKey.json"
        val res = app.get(url).text
        val parsed = parseJson<WyZIEResponse>(res)
        parsed.subtitles?.forEach { sub ->
            if (isTargetLanguage(sub.language)) {
                subtitleCallback(newSubtitleFile(sub.language, sub.url))
            }
        }
    } catch (e: Exception) {
        Log.d("WyZIESUB", "Hata: ${e.message}")
    }
}

fun isTargetLanguage(label: String): Boolean {
    val normalized = Normalizer.normalize(label.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    val words = normalized.split(Regex("[^a-z0-9]+"))
    val acceptedCodes = setOf("turkish", "turkce", "tr", "tur", "english", "en", "eng")
    return words.any { it in acceptedCodes }
}

private val languageMap = mapOf("Turkish" to setOf("tr", "tur"))

fun getLanguage(code: String): String {
    val lower = code.lowercase()
    return languageMap.entries.firstOrNull { lower in it.value }?.key ?: "UnKnown"
}
