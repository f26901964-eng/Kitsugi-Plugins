//Eklenti kraptor reposundan çekilip düzenlenmiştir.
package com.patron

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.app
import com.lagradost.api.Log
import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.CancellationException

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
            if (sub.url.isBlank()) return@forEach
            val lang = getLanguage(sub.lang)
            Log.d("OpenSubtitlesV3", "${lang} altyazı: ${sub.url}")
            subtitleCallback(newSubtitleFile(lang, sub.url))
        }
    } catch (error: CancellationException) {
        throw error
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
            if (sub.url.isNotBlank() && isTurkishLanguage(sub.lang)) {
                subtitleCallback(newSubtitleFile("Turkish", sub.url))
            }
        }
    } catch (error: CancellationException) {
        throw error
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
        val fullKey = if (season == null) "movie/$id" else "series/$id:$season:$episode"
        val url = "https://wyziesub.com/subtitles/$fullKey.json"
        val res = app.get(url).text
        val parsed = parseJson<WyZIEResponse>(res)
        parsed.subtitles?.forEach { sub ->
            if (sub.url.isNotBlank()) {
                val language = sub.language.ifBlank { sub.display }.ifBlank { sub.id }
                subtitleCallback(newSubtitleFile(getLanguage(language), sub.url))
            }
        }
    } catch (error: CancellationException) {
        throw error
    } catch (e: Exception) {
        Log.d("WyZIESUB", "Hata: ${e.message}")
    }
}

fun isTargetLanguage(label: String): Boolean =
    getLanguage(label) in setOf("Turkish", "English")

private fun isTurkishLanguage(label: String): Boolean = getLanguage(label) == "Turkish"

private fun languageTokens(label: String): Set<String> {
    val normalized = Normalizer.normalize(label.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
    return normalized.split(Regex("[^a-z0-9]+"))
        .filter { it.isNotBlank() }
        .toSet()
}

private val languageMap = mapOf(
    "Turkish" to setOf("tr", "tur", "turkish", "turkce"),
    "English" to setOf("en", "eng", "english"),
)

/** Return canonical names for common language tags, preserving other valid labels. */
fun getLanguage(code: String): String {
    val tokens = languageTokens(code)
    return languageMap.entries.firstOrNull { (_, aliases) -> tokens.any { it in aliases } }?.key
        ?: code.trim().ifBlank { "Unknown" }
}
