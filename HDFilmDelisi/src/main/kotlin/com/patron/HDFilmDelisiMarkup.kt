package com.patron

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * hdfilmdelisi.one işlenmiş HTML'i (Next.js SSR) üzerinden saf veri çıkaran
 * ayrıştırıcı. Ağ çağrısı ve Cloudstream tipleri içermez; bu sayede gerçek
 * site HTML'i ile birim testleri yapılabilir.
 */
internal data class CardData(
    val title: String,
    val href: String,
    val poster: String?,
    val isSeries: Boolean,
)

internal data class ActorInfo(
    val name: String,
    val image: String?,
    val role: String?,
)

internal data class DetailData(
    val title: String,
    val poster: String?,
    val plot: String?,
    val year: Int?,
    val rating: Double?,
    val duration: Int?,
    val tags: List<String>,
    val actors: List<ActorInfo>,
    val trailerYoutubeId: String?,
)

internal data class EpisodeData(
    val name: String,
    val href: String,
    val season: Int,
    val episode: Int,
)

internal object HDFilmDelisiMarkup {

    private val brandTitles = setOf("Film izle", "Dizi izle", "Film İzle", "Dizi İzle")
    private val yearPattern = Regex("""^(19|20)\d{2}$""")
    private val durationPattern = Regex("""(\d+)\s*dk""")
    private val imdbPattern = Regex("""IMDb\s*:\s*([0-9](?:[.,]\d)?)""", RegexOption.IGNORE_CASE)
    private val seasonPattern = Regex("""(?:sezon|season)[-_]?(\d+)""", RegexOption.IGNORE_CASE)
    private val episodePattern = Regex("""(?:bolum|bölüm|episode|ep)[-_]?(\d+)""", RegexOption.IGNORE_CASE)

    /** Ana sayfa, kategori ve arama sonuçlarındaki poster kartlarını ayrıştırır. */
    fun parseCards(document: Document): List<CardData> {
        val anchors = document.select("a.poster[href]").ifEmpty {
            document.select("a[href*='/film/'], a[href*='/dizi/']")
        }

        val result = LinkedHashMap<String, CardData>()
        for (anchor in anchors) {
            val href = anchor.attr("href").trim()
            if (!href.contains("/film/") && !href.contains("/dizi/")) continue

            val image = anchor.selectFirst("img")
            val rawTitle = anchor.attr("title").ifBlank { image?.attr("alt").orEmpty() }
                .ifBlank { anchor.text().trim() }
            val title = cleanTitle(rawTitle)
            if (title.isBlank()) continue

            val poster = image?.attr("src")?.ifBlank { image.attr("data-src") }
                ?.ifBlank { null }
            val base = document.baseUri().orEmpty()
            val absoluteHref = when {
                href.startsWith("http") -> href
                base.isBlank() -> href
                href.startsWith("/") -> "$base$href"
                else -> "$base/$href"
            }

            result.putIfAbsent(
                absoluteHref,
                CardData(
                    title = title,
                    href = absoluteHref,
                    poster = poster,
                    isSeries = href.contains("/dizi/"),
                ),
            )
        }
        return result.values.toList()
    }

    /** Film/dizi detay sayfasının meta verilerini çıkarır. */
    fun parseDetail(document: Document): DetailData {
        val title = document.select("h1")
            .firstOrNull { node ->
                val text = node.text().trim()
                text.isNotBlank() && text !in brandTitles
            }
            ?.text()?.trim()
            ?: cleanTitle(document.selectFirst("meta[property='og:title']")?.attr("content").orEmpty())
                .ifBlank { cleanTitle(document.title()) }

        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst("img[src*='poster'], img[src*='series'], img[src*='upload']")?.let { img ->
                img.attr("src").ifBlank { img.attr("data-src") }.ifBlank { null }
            }

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")?.ifBlank { null }
            ?: document.selectFirst("div.description, div.overview, p")?.text()?.trim()?.ifBlank { null }

        val spanTexts = document.select("span").map { it.text().trim() }.filter { it.isNotBlank() }

        val year = spanTexts.firstOrNull { yearPattern.matches(it) }?.toIntOrNull()
            ?: Regex("""(19|20)\d{2}""").find(
                document.selectFirst("meta[property='og:title']")?.attr("content").orEmpty(),
            )?.value?.toIntOrNull()

        val rating = spanTexts.firstNotNullOfOrNull { text ->
            imdbPattern.find(text)?.groupValues?.get(1)?.replace(",", ".")?.toDoubleOrNull()
        }

        val duration = spanTexts.firstNotNullOfOrNull { text ->
            durationPattern.find(text)?.groupValues?.get(1)?.toIntOrNull()
        }

        val tags = document.select("a[href*='/tur/']")
            .filter { it.closest("nav, header, footer") == null }
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()

        val actors = document.select("a[href*='/oyuncu-yonetmen/']").mapNotNull { anchor ->
            val image = anchor.selectFirst("img")?.attr("src")?.ifBlank { null }
            val name = anchor.select("p").firstOrNull { p ->
                p.className().split(" ").any { it.contains("font-medium") || it.contains("text-white") }
            }?.text()?.trim()
                ?: anchor.selectFirst("p")?.text()?.trim()
                ?: image?.let { Regex("""^(.*?)\s*fotoğrafı$""").find(it)?.groupValues?.get(1) }
                ?: anchor.text().trim().substringBefore(" ").takeIf { it.isNotBlank() }
            if (name.isNullOrBlank()) return@mapNotNull null
            val role = anchor.select("p").getOrNull(1)?.text()?.trim()?.ifBlank { null }
            ActorInfo(name, image, role)
        }.distinctBy { it.name }

        val trailerYoutubeId = document.selectFirst("iframe[src*='youtube.com/embed/'], iframe[src*='youtu.be']")
            ?.attr("src")
            ?.let { src ->
                Regex("""(?:embed/|youtu\.be/)([A-Za-z0-9_-]{6,})""").find(src)?.groupValues?.get(1)
            }

        return DetailData(
            title = title,
            poster = poster,
            plot = plot,
            year = year,
            rating = rating,
            duration = duration,
            tags = tags,
            actors = actors,
            trailerYoutubeId = trailerYoutubeId,
        )
    }

    /**
     * Dizi detay sayfasındaki bölüm bağlantılarını toplar. Sayfa yapısı değişse
     * bile çalışması için yalnızca URL kalıbına göre sezon/bölüm numarası türetir.
     */
    fun parseEpisodes(document: Document, seriesUrl: String): List<EpisodeData> {
        val baseHref = seriesUrl.removeSuffix("/")

        val candidates = document.select("a[href]").mapNotNull { anchor ->
            val href = anchor.attr("href").trim()
            if (href.isBlank() || href.startsWith("#")) return@mapNotNull null
            val absolute = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> {
                    val root = Regex("""(https?://[^/]+)""").find(seriesUrl)?.groupValues?.get(1).orEmpty()
                    "$root$href"
                }
                else -> "$baseHref/$href"
            }
            if (!absolute.startsWith(baseHref) || absolute == baseHref) return@mapNotNull null
            if (!absolute.contains(Regex("""(sezon|season|bolum|bölüm|episode|ep)[-_]?\d""", RegexOption.IGNORE_CASE)) &&
                !absolute.removePrefix(baseHref).contains(Regex("""\d"""))
            ) {
                return@mapNotNull null
            }
            absolute
        }.distinct()

        val episodes = candidates.mapIndexed { index, href ->
            val season = seasonPattern.find(href)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = episodePattern.find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""[-_](\d+)(?:\?|#|$)""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                ?: (index + 1)
            EpisodeData(
                name = "$season. Sezon $episode. Bölüm",
                href = href,
                season = season,
                episode = episode,
            )
        }.distinctBy { "${it.season}-${it.episode}" }
            .sortedWith(compareBy({ it.season }, { it.episode }))

        return episodes
    }

    /** Arama RSC payload içinde kalmış olabilecek slug'ları yedek olarak çıkarır. */
    fun fallbackSlugs(html: String, baseUrl: String): List<CardData> {
        val regex = Regex(""""/(film|dizi)/([a-zA-Z0-9_-]+)"""")
        return regex.findAll(html)
            .map { it.groupValues[1] to it.groupValues[2] }
            .distinct()
            .map { (type, slug) ->
                CardData(
                    title = slug.split("-").joinToString(" ") { part ->
                        part.replaceFirstChar { char -> char.uppercase(java.util.Locale.ROOT) }
                    },
                    href = "$baseUrl/$type/$slug",
                    poster = null,
                    isSeries = type == "dizi",
                )
            }
            .toList()
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s+izle\s*$""", RegexOption.IGNORE_CASE), "")
        .replace("Türkçe Dublaj", "", ignoreCase = true)
        .replace("ve Altyazılı", "", ignoreCase = true)
        .replace(Regex("""\((19|20)\d{2}\)"""), "")
        .trim()
}
