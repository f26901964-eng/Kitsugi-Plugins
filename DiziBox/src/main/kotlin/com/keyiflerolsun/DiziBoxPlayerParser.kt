package com.keyiflerolsun

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * DiziBox sayfa/oynatıcı ayrıştırıcıları ve çoklu video kaynağı yardımcıları.
 *
 * Saf Kotlin + Jsoup olarak tutulur; Android veya CloudStream çalışma zamanı
 * sınıflarına bağımlı olmadığından tüm fonksiyonlar doğrudan JVM JUnit
 * testlerinde gerçek site HTML dökümleriyle (`DiziBox/site-dumps`) doğrulanır.
 */
internal object DiziBoxPlayerParser {
    data class EncryptedPayload(val ciphertext: String, val password: String)
    data class MediaSource(
        val url: String,
        val type: String = "",
        val kind: String = "",
        val label: String = "",
    )
    data class SubtitleTrack(
        val url: String,
        val label: String,
        val kind: String = "",
        val language: String = "",
    )
    data class HlsVariant(val url: String, val height: Int)
    data class DwlsResult(val title: String, val url: String, val thumbnail: String)
    data class EpisodeAnchor(val url: String, val season: Int, val episode: Int)

    data class ListingCard(
        val title: String,
        val url: String,
        val posterUrl: String? = null,
        val imdbScore: String? = null,
        val year: Int? = null,
        val isEpisode: Boolean = false,
    )

    data class ParsedEpisode(
        val url: String,
        val name: String,
        val season: Int,
        val episode: Int?,
        val posterUrl: String? = null,
        val dateMillis: Long? = null,
    )

    data class SeriesDetail(
        val title: String,
        val canonicalUrl: String,
        val posterUrl: String? = null,
        val plot: String? = null,
        val year: Int? = null,
        val imdbScore: String? = null,
        val tags: List<String> = emptyList(),
        val actors: List<String> = emptyList(),
        val trailerUrl: String? = null,
        val activeEpisodes: List<ParsedEpisode> = emptyList(),
        val inactiveSeasonUrls: List<String> = emptyList(),
        val allSeasonUrls: List<String> = emptyList(),
    )

    data class PlayerTab(val url: String, val label: String)
    data class OkRuStream(val url: String, val qualityLabel: String, val isHls: Boolean)
    data class MailRuStream(val url: String, val qualityLabel: String)
    data class VkStream(val url: String, val qualityLabel: String, val isHls: Boolean)

    private val aesDecryptLiteral = Regex(
        """CryptoJS\s*\.\s*AES\s*\.\s*decrypt\s*\(\s*(?:"((?:\\.|[^"\\])+)"|'((?:\\.|[^'\\])+)')\s*,\s*(?:"([^"]*)"|'([^']*)')\s*(?:,\s*[^)]*)?\)""",
        RegexOption.IGNORE_CASE,
    )
    private val aesDecryptVar = Regex(
        """CryptoJS\s*\.\s*AES\s*\.\s*decrypt\s*\(\s*(?:JSON\.stringify\s*\(\s*)?([A-Za-z_$][A-Za-z0-9_$]*)\s*\)?\s*,\s*(?:"([^"]*)"|'([^']*)')\s*(?:,\s*[^)]*)?\)""",
        RegexOption.IGNORE_CASE,
    )
    private val fileProperty = Regex(
        """(?:["']file["']|\bfile)\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val extraStreamProperty = Regex(
        """(?:["'](?:hlsUrl|videoSource|securedLink|video_source)["']|\b(?:hlsUrl|videoSource|securedLink|video_source))\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val typeProperty = Regex(
        """(?:["']type["']|\btype)\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val kindProperty = Regex(
        """(?:["']kind["']|\bkind)\s*:\s*(?:"([^"]+)"|'([^']+)')""",
        RegexOption.IGNORE_CASE,
    )
    private val labelProperty = Regex(
        """(?:["']label["']|\blabel)\s*:\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE,
    )
    private val languageProperty = Regex(
        """(?:["'](?:srclang|language|lang)["']|\b(?:srclang|language|lang)\b)\s*:\s*(?:"([^"]*)"|'([^']*)')""",
        RegexOption.IGNORE_CASE,
    )
    private val objectPattern = Regex("""\{[^{}]*}""")
    private val sourcesArray = Regex("""\bsources\s*:\s*\[""", RegexOption.IGNORE_CASE)
    private val knownPlayerPath = Regex(
        """/(?:player/)?(?:[a-z0-9_-]+/)?(?:king|moly|haydi|spidy|player)\.php$|/player/.+\.php$|/embed/""",
        RegexOption.IGNORE_CASE,
    )
    private val adOrNonPlayerHost = Regex(
        """(?:^|\.)(?:googletagmanager\.com|google-analytics\.com|googlesyndication\.com|doubleclick\.net|google\.com|gstatic\.com|facebook\.com|connect\.facebook\.net|twitter\.com|x\.com|yandex\.ru|mc\.yandex\.ru|gravatar\.com|dizibox\.cc)$""",
        RegexOption.IGNORE_CASE,
    )

    private const val CHROME_SELECTOR =
        "#alphabetical-category, ul.alphabetical-category-list, aside, #sidebar, .sidebar, " +
            ".box-sidebar, #recommended-series, header, footer, #related-posts, #comments, .comments-area"

    private const val CARD_SELECTOR =
        "article.article-series-poster, article.detailed-article, article.article-episode-card, " +
            "article.article-series-small-grid, article.article-episode-small-grid"

    private val TURKISH_MONTHS = mapOf(
        "ocak" to 1, "oca" to 1,
        "şubat" to 2, "subat" to 2, "şub" to 2, "sub" to 2,
        "mart" to 3, "mar" to 3,
        "nisan" to 4, "nis" to 4,
        "mayıs" to 5, "mayis" to 5, "may" to 5,
        "haziran" to 6, "haz" to 6,
        "temmuz" to 7, "tem" to 7,
        "ağustos" to 8, "agustos" to 8, "ağu" to 8, "agu" to 8,
        "eylül" to 9, "eylul" to 9, "eyl" to 9,
        "ekim" to 10, "eki" to 10,
        "kasım" to 11, "kasim" to 11, "kas" to 11,
        "aralık" to 12, "aralik" to 12, "ara" to 12,
    )

    // ────────── Ana sayfa, kategori ve arama DOM ayrıştırıcıları ──────────

    /**
     * WordPress sayfalama kuralı: 1. sayfada `/page/1/` kullanılmaz (301 yönlendirmesini önler),
     * 2+ sayfalarda `SAYFA` yer tutucusu sayfa numarasıyla değiştirilir.
     */
    /**
     * Sayfa indeksini siteye çevirir: 0 ve 1 → **ilk sayfa**.
     *
     * CloudStream sıralı ana sayfa 0'dan, Kitsugi "Keşfet" ekranı 1'den başlıyor;
     * ikisi de ilk sayfa demek. (Önceki `page + 1` dönüşümü Kitsugi'de her bölümü
     * 2. sayfaya düşürüp, 2. sayfası olmayan bölümlerde listeyi boşaltıyordu.)
     */
    fun mainPageIndex(page: Int): Int = if (page < 1) 1 else page

    fun buildMainPageUrl(templateUrl: String, page: Int): String {
        if (page <= 1) {
            val query = templateUrl.substringAfter('?', "")
            val pathPart = templateUrl.substringBefore('?')
                .replace(Regex("""/page/SAYFA/?$"""), "/")
                .replace(Regex("""(?<!:)//+"""), "/")
            return if (query.isNotEmpty()) "$pathPart?$query" else pathPart
        }
        return templateUrl.replace("SAYFA", page.toString())
    }

    /**
     * Her sayfada bulunan ~750 KB A-Z başlık dizinini (`#alphabetical-category`, ~3.000 dizi)
     * ve sağ yan çubuktaki "Gündemdekiler" (`div.box-sidebar`) bileşenini ayıklayarak
     * yalnızca gerçek sayfa içeriğini bırakır.
     */
    fun stripChrome(document: Document): Document {
        val clone = document.clone()
        clone.select(CHROME_SELECTOR).remove()
        return clone
    }

    /** Ana sayfa / kategori / arşiv / bölüm listesi kartlarını ayrıştırır. */
    fun parseListingCards(document: Document, pageUrl: String): List<ListingCard> {
        val cleanDoc = stripChrome(document)
        val seen = LinkedHashSet<String>()
        val results = mutableListOf<ListingCard>()

        cleanDoc.select(CARD_SELECTOR).forEach { element ->
            val card = parseCardElement(element, pageUrl) ?: return@forEach
            if (seen.add(card.url)) {
                results += card
            }
        }
        return results
    }

    /**
     * HTML arama sayfaları (`/search/<q>/`, `/?s=<q>`) üstte eşleşen dizileri
     * (`article.detailed-article`), altta ise `Dizi Bölümleri` başlığı altında tekil
     * bölümleri (`article.article-episode-card`) listeler. Dizi eşleşmesi varsa
     * yalnızca dizi kartları döndürülür; yalnızca dizi yoksa bölüm kartlarına düşülür.
     */
    fun parseSearchCards(document: Document, pageUrl: String): List<ListingCard> {
        val allCards = parseListingCards(document, pageUrl)
        val seriesCards = allCards.filterNot { it.isEpisode }
        return if (seriesCards.isNotEmpty()) seriesCards else allCards
    }

    /** Sayfalama bağlantılarında `currentPage + 1` sayfasının varlığını denetler. */
    fun hasNextPage(document: Document, currentPage: Int): Boolean {
        if (currentPage < 1) return false
        val nextPageToken = "/page/${currentPage + 1}"
        if (document.select("a.next, a[rel=next], .woca-pagination a, .pagination a, .wp-pagenavi a")
                .any { anchor ->
                    val href = anchor.attr("href")
                    href.contains("$nextPageToken/") ||
                        href.endsWith(nextPageToken) ||
                        href.contains("$nextPageToken?")
                }
        ) {
            return true
        }
        return document.select("a[href*='$nextPageToken/'], a[href$='$nextPageToken']").isNotEmpty()
    }

    private fun parseCardElement(element: Element, pageUrl: String): ListingCard? {
        val anchor = element.selectFirst("a.episode-card-title[href]")
            ?: element.selectFirst("a.poster-title[href]")
            ?: element.selectFirst("h3 a[href]")
            ?: element.selectFirst("h2 a[href]")
            ?: element.selectFirst("a.series-details[href]")
            ?: element.selectFirst("a.figure-link[href]")
            ?: element.selectFirst("a.figure[href]")
            ?: element.selectFirst("a[href]")
            ?: return null

        val href = resolveUrl(anchor.attr("href"), pageUrl) ?: return null
        val isEpisode = element.hasClass("article-episode-card") ||
            element.hasClass("article-episode-small-grid") ||
            (!href.contains("/diziler/") && episodeNumbers(href) != null)

        val posterImg = selectPosterImage(element)
        val anchorCleanText = anchor.clone().apply { select(".imdb, i, svg").remove() }.text()

        // Bölüm kartlarında title özniteliği ("Tracker 4.Sezon 5.Bölüm") temiz başlığı verir.
        val titleCandidates = if (element.hasClass("article-episode-card")) {
            listOf(anchor.attr("title"), anchorCleanText, posterImg?.attr("alt").orEmpty(), slugTitle(href))
        } else {
            listOf(anchorCleanText, anchor.attr("title"), posterImg?.attr("alt").orEmpty(), slugTitle(href))
        }
        val title = titleCandidates
            .map { raw ->
                raw.replace(Regex("""\s+"""), " ")
                    .replace(Regex("""\s+izle\b.*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
            }
            .firstOrNull { it.isNotBlank() }
            ?: return null

        val backgroundUrl = element.selectFirst("div.box-image")?.attr("style")
            ?.let { extractCssUrl(it) }
        val rawImage = listOf(
            posterImg?.attr("data-src"),
            posterImg?.attr("data-lazy-src"),
            posterImg?.attr("src"),
            backgroundUrl,
        ).firstOrNull { isUsableImageUrl(it) }
            ?.let { resolveUrl(it, pageUrl) }

        val finalPoster = rawImage?.let { if (isEpisode) it else upscaleThumbnail(it) }

        val imdbScore = extractDecimalScore(
            element.selectFirst("span.label-imdb b, div.imdb, span.imdb")?.text()
                ?: Regex("""imdb\s*:\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
                    .find(element.text())?.groupValues?.get(1),
        )
        val year = element.selectFirst("div.release, a[href*='/yil/']")?.text()
            ?.let { Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.value?.toIntOrNull() }
            ?: Regex("""\b(19\d{2}|20\d{2})\b""").find(element.text())?.value?.toIntOrNull()

        return ListingCard(
            title = title,
            url = href,
            posterUrl = finalPoster,
            imdbScore = imdbScore,
            year = year,
            isEpisode = isEpisode,
        )
    }

    /** Dil bayrağı ikonlarını (`altyazi.png`, `dublaj.png`, `.language-flag`) afişten dışlar. */
    private fun selectPosterImage(container: Element): Element? {
        container.selectFirst("img.afis, img.wp-post-image, img.main-cover, img.small-thumbnail")
            ?.let { return it }
        return container.select("img").firstOrNull { img ->
            !img.hasClass("language-flag") &&
                !img.attr("src").contains("altyazi", ignoreCase = true) &&
                !img.attr("src").contains("dublaj", ignoreCase = true) &&
                !img.attr("alt").equals("Dil Seçeneği", ignoreCase = true)
        }
    }

    private fun isUsableImageUrl(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val trimmed = raw.trim()
        if (trimmed.startsWith("data:", ignoreCase = true)) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        return !lower.endsWith("/altyazi.png") && !lower.endsWith("/dublaj.png")
    }

    private fun extractCssUrl(style: String): String? =
        Regex("""url\(\s*['"]?([^'")\s]+)['"]?\s*\)""", RegexOption.IGNORE_CASE)
            .find(style)?.groupValues?.get(1)

    private fun extractDecimalScore(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return Regex("""\b(\d+(?:\.\d+)?)\b""").find(raw.replace(',', '.'))?.groupValues?.get(1)
    }

    /** URL slug'ından okunabilir başlık: `friends-izle-hd` -> `Friends`. */
    fun slugTitle(href: String): String =
        href.trimEnd('/').substringAfterLast('/')
            .replace(Regex("""(-izle)+$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""-hd(-\d+)?$""", RegexOption.IGNORE_CASE), "")
            .split('-')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }

    // ────────── Dizi detay, sezon ve bölüm ayrıştırıcıları ──────────

    /** Bölüm sayfasındaki arşiv kutusundan ana dizi sayfasının adresini çıkarır. */
    fun archiveBoxLink(document: Document, pageUrl: String): String? =
        document.selectFirst("div#archive-box a.archive-title[href], div#archive-box a.figure[href]")
            ?.attr("href")
            ?.let { resolveUrl(it, pageUrl) }

    /** `div.tv-overview` içeren dizi / sezon sayfasını tam üst veri ve bölümlerle ayrıştırır. */
    fun parseSeriesDetail(document: Document, pageUrl: String): SeriesDetail? {
        val overview = document.selectFirst("div.tv-overview") ?: return null
        val title = overview.selectFirst("h1 a, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val canonicalUrl = overview.selectFirst("h1 a[href]")?.attr("href")
            ?.let { resolveUrl(it, pageUrl) }
            ?: pageUrl

        val coverImg = document.selectFirst("figure#main-cover img, div.tv-overview figure img")
        val posterUrl = listOf(
            coverImg?.attr("data-src"),
            coverImg?.attr("data-lazy-src"),
            coverImg?.attr("src"),
        ).firstOrNull { isUsableImageUrl(it) }
            ?.let { resolveUrl(it, pageUrl) }
            ?.let { upscaleThumbnail(it) }

        val plot = document.selectFirst("div.tv-story p, div.tv-story")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
        val year = overview.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val imdbScore = extractDecimalScore(overview.selectFirst("span.label-imdb b, span.label-imdb")?.text())
        val tags = overview.select("a[href*='/tur/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val actors = overview.select("a[href*='/oyuncu/']")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .distinct()
        val trailerUrl = document.selectFirst("div#trailer-box iframe, div.tv-overview iframe")
            ?.let { iframe ->
                listOf("src", "data-src", "data-lazy-src")
                    .firstNotNullOfOrNull { attr -> resolveUrl(iframe.attr(attr), pageUrl) }
            }

        val seasonAnchors = document.select("div#seasons-list a[href]")
        val allSeasonUrls = seasonAnchors.mapNotNull { resolveUrl(it.attr("href"), pageUrl) }.distinct()
        val inactiveSeasonUrls = seasonAnchors
            .filterNot { it.hasClass("hover") || it.hasClass("active") || it.hasClass("current") }
            .mapNotNull { resolveUrl(it.attr("href"), pageUrl) }
            .distinct()

        val activeEpisodes = parseSeasonEpisodes(document, pageUrl, posterUrl)

        return SeriesDetail(
            title = title,
            canonicalUrl = canonicalUrl,
            posterUrl = posterUrl,
            plot = plot,
            year = year,
            imdbScore = imdbScore,
            tags = tags,
            actors = actors,
            trailerUrl = trailerUrl,
            activeEpisodes = activeEpisodes,
            inactiveSeasonUrls = inactiveSeasonUrls,
            allSeasonUrls = allSeasonUrls,
        )
    }

    /** Sezon sayfasındaki `article.grid-box` bölüm kartlarını ayrıştırır. */
    fun parseSeasonEpisodes(
        document: Document,
        pageUrl: String,
        fallbackPoster: String? = null,
    ): List<ParsedEpisode> {
        val episodes = LinkedHashMap<String, ParsedEpisode>()

        document.select("section#category-posts article.grid-box, article.grid-box").forEach { box ->
            val anchor = box.selectFirst("div.post-title a.season-episode[href], div.post-title a[href], a[href]")
                ?: return@forEach
            val epUrl = resolveUrl(anchor.attr("href"), pageUrl) ?: return@forEach
            val rawTitle = anchor.text().replace(Regex("""\s+"""), " ").trim()
            if (rawTitle.isBlank()) return@forEach

            val slugNums = episodeNumbers(epUrl)
            val season = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE)
                .find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: slugNums?.first
                ?: 1
            val episode = Regex("""(\d+)\.?\s*Bölüm""", RegexOption.IGNORE_CASE)
                .find(rawTitle)?.groupValues?.get(1)?.toIntOrNull()
                ?: slugNums?.second

            val boxImage = box.selectFirst("div.box-image")?.attr("style")
                ?.let { extractCssUrl(it) }
                ?.let { resolveUrl(it, pageUrl) }
                ?: fallbackPoster

            val dateText = box.selectFirst("small.date")?.text()?.trim()
            val dateMillis = parseTurkishDateMillis(dateText)

            episodes.putIfAbsent(
                epUrl,
                ParsedEpisode(
                    url = epUrl,
                    name = rawTitle,
                    season = season,
                    episode = episode,
                    posterUrl = boxImage,
                    dateMillis = dateMillis,
                ),
            )
        }
        return episodes.values.toList()
    }

    /**
     * Yalnızca bölüm sayfasına düşüldüğünde ve `archive-box` hedefi bulunamadığında
     * `#related-posts` veya sayfa başlığından (`h1`) tekil dizi/bölüm yanıtı üretir.
     */
    fun parseEpisodePageFallback(document: Document, pageUrl: String): SeriesDetail? {
        val heading = document.selectFirst("main h1, .content-wrapper h1, h1")?.text()
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val seriesTitle = heading
            .replace(Regex("""\s*\d+\.?\s*Sezon.*$""", RegexOption.IGNORE_CASE), "")
            .trim()
            .ifBlank { heading }

        val relatedEpisodes = LinkedHashMap<String, ParsedEpisode>()
        document.select("#related-posts ul li a[href]").forEach { a ->
            val href = resolveUrl(a.attr("href"), pageUrl) ?: return@forEach
            val label = a.clone().apply { select(".watl-button, input, label").remove() }
                .text().replace(Regex("""\s+"""), " ").trim()
            val nums = episodeNumbers(href)
            val season = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: nums?.first ?: 1
            val ep = Regex("""(\d+)\.?\s*Bölüm""", RegexOption.IGNORE_CASE)
                .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: nums?.second
            relatedEpisodes.putIfAbsent(
                href,
                ParsedEpisode(
                    url = href,
                    name = label.ifBlank { "${season}.Sezon ${ep ?: 1}.Bölüm" },
                    season = season,
                    episode = ep,
                ),
            )
        }

        if (relatedEpisodes.isEmpty()) {
            val nums = episodeNumbers(pageUrl)
            relatedEpisodes[pageUrl] = ParsedEpisode(
                url = pageUrl,
                name = heading,
                season = nums?.first ?: 1,
                episode = nums?.second ?: 1,
            )
        }

        val archiveThumb = document.selectFirst("#archive-box img")?.attr("src")
            ?.takeIf { isUsableImageUrl(it) }
            ?.let { resolveUrl(it, pageUrl) }
            ?.let { upscaleThumbnail(it) }

        return SeriesDetail(
            title = seriesTitle,
            canonicalUrl = pageUrl,
            posterUrl = archiveThumb,
            activeEpisodes = relatedEpisodes.values.toList(),
        )
    }

    /** `"14 Ekim 2026"` veya `"2 Kas 26"` gibi Türkçe tarih metinlerini UTC epoch milisaniyeye çevirir. */
    fun parseTurkishDateMillis(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val match = Regex("""(\d{1,2})\s+([A-Za-zÇĞİÖŞÜçğıöşü]+)\s+(\d{2,4})""").find(raw.trim())
            ?: return null
        val day = match.groupValues[1].toIntOrNull() ?: return null
        val monthName = match.groupValues[2].lowercase(Locale("tr", "TR"))
        val month = TURKISH_MONTHS[monthName] ?: return null
        var year = match.groupValues[3].toIntOrNull() ?: return null
        if (year in 0..99) year += 2000
        if (day !in 1..31 || year !in 1950..2100) return null

        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, 0, 0, 0)
        }
        return cal.timeInMillis
    }

    // ────────── Bölüm oynatıcı sekmeleri ve çerçeve ayrıştırıcıları ──────────

    /** Player iframe URLs in the episode's current #video-area. */
    fun playerIframes(document: Document, pageUrl: String): List<String> =
        iframeUrls(document.select("#video-area iframe, #Player iframe, .video-area iframe"), pageUrl)
            .filterNot { isIgnoredEmbedUrl(it) }

    /** Nested frames inside a player wrapper (for example King -> MolyStream). */
    fun nestedIframes(document: Document, pageUrl: String): List<String> =
        iframeUrls(document.select("iframe"), pageUrl)
            .filterNot { isIgnoredEmbedUrl(it) }

    private fun isIgnoredEmbedUrl(url: String): Boolean {
        val host = normalizedHost(url) ?: return true
        if (adOrNonPlayerHost.containsMatchIn(host)) return true
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("googletagmanager") || lower.contains("/recaptcha/")
    }

    /**
     * Oynatıcı seçim menüsündeki (`DBX Pro`, `Moly+`, `Odnok`, `İngilizce` vb.) tüm sekmeleri
     * hem sayfa adresi hem de okunabilir kaynak etiketiyle döndürür.
     */
    fun playerTabs(document: Document, pageUrl: String): List<PlayerTab> {
        val tabs = LinkedHashMap<String, PlayerTab>()
        val options = document.select(
            "select.woca-linkpages-dd option, div.video-toolbar select option",
        )
        val activeFallbackLabel = activePlayerLabel(document)

        options.forEachIndexed { index, option ->
            val rawUrl = option.attr("value").ifBlank { option.attr("href") }
            val resolved = resolveUrl(rawUrl, pageUrl)
                ?: if (option.hasAttr("selected") || option.hasClass("woca-current-page") || index == 0) pageUrl else null
            if (resolved == null) return@forEachIndexed
            val label = option.text().replace(Regex("""\s+"""), " ").trim()
                .ifBlank { if (resolved == pageUrl) activeFallbackLabel.orEmpty() else "" }
                .ifBlank { "Alternatif ${index + 1}" }
            tabs.putIfAbsent(resolved, PlayerTab(resolved, label))
        }

        if (tabs.isEmpty()) {
            tabs[pageUrl] = PlayerTab(pageUrl, activeFallbackLabel ?: "DBX Pro")
        } else if (!tabs.containsKey(pageUrl)) {
            val merged = LinkedHashMap<String, PlayerTab>()
            merged[pageUrl] = PlayerTab(pageUrl, activeFallbackLabel ?: "DBX Pro")
            merged.putAll(tabs)
            return merged.values.toList()
        }

        return tabs.values.toList()
    }

    /** `#video-area` içindeki `<!--baslik:DBX Pro-->` veya seçili option etiketini okur. */
    fun activePlayerLabel(document: Document): String? {
        val videoAreaHtml = document.selectFirst("#video-area")?.html().orEmpty()
        Regex("""<!--\s*baslik\s*:\s*([^-]+?)\s*-->""", RegexOption.IGNORE_CASE)
            .find(videoAreaHtml)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return document.selectFirst(
            "select.woca-linkpages-dd option[selected], select.woca-linkpages-dd option.woca-current-page",
        )?.text()?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Türkiye dışı IP'lerde DBX Pro'nun döndürdüğü coğrafi kısıtlama uyarısını tanır. */
    fun isGeoBlockedVideoArea(document: Document): Boolean {
        val area = document.selectFirst("#video-area") ?: return false
        if (area.select("iframe").isNotEmpty()) return false
        val text = area.text().lowercase(Locale("tr", "TR"))
        return text.contains("sadece türkiye ziyaretçilerine açıktır") ||
            text.contains("diğer video alternatiflerimizden izleyiniz")
    }

    /**
     * Oynatıcı seçim menüsündeki (DBX Pro / Moly+ / Odnok gibi) öğelerin doğrudan oynatıcı adresleri.
     * Yalnızca oynatıcıya benzeyen adresler alınır (bölüm sayfası değil).
     */
    fun playerOptionUrls(document: Document, pageUrl: String): List<String> {
        val playerLike = Regex(
            """(?i)/player/|/embed/|king\.php|moly\.php|haydi\.php|spidy\.php|sheila""",
        )
        val attributeNames = listOf(
            "data-src", "data-url", "data-iframe", "data-player", "data-embed", "data-link", "value",
        )
        return document.select(
            "[data-src], [data-url], [data-iframe], [data-player], [data-embed], [data-link], option[value]",
        )
            .asSequence()
            .filterNot { it.tagName().equals("iframe", ignoreCase = true) }
            .flatMap { element -> attributeNames.asSequence().map { element.attr(it) } }
            .mapNotNull { resolveUrl(it, pageUrl) }
            .filter { playerLike.containsMatchIn(it) }
            .distinct()
            .toList()
    }

    /** Alternate player pages exposed by the current selector and the legacy toolbar. */
    fun alternatePlayerPages(document: Document, pageUrl: String): List<String> {
        val options = document.select(
            "select.woca-linkpages-dd option[value], " +
                "select.woca-linkpages-dd option[href], " +
                "div.video-toolbar option[value], " +
                "div.video-toolbar option[href]",
        )
        return options.mapNotNull { option ->
            val raw = option.attr("value").ifBlank { option.attr("href") }
            resolveUrl(raw, pageUrl)
        }.distinct()
    }

    // ────────── Oynatıcı sarmalayıcı (wrapper) ve şifre çözme ──────────

    /**
     * DiziBox'ın `jquery.main.min.js` betiğiyle birebir aynı biçimde `wmode=opaque`
     * parametresini sorgunun BAŞINA ekler (`king.php?wmode=opaque&v=...`).
     */
    fun withWOpaque(url: String): String {
        if (!Regex("""(?:king|moly|haydi|spidy|player)\.php""", RegexOption.IGNORE_CASE).containsMatchIn(url)) {
            return url
        }
        if (url.contains("wmode=", ignoreCase = true)) return url
        val fragment = url.substringAfter('#', "")
        val withoutFragment = url.substringBefore('#')
        val rewritten = if (withoutFragment.contains('?')) {
            val base = withoutFragment.substringBefore('?')
            val query = withoutFragment.substringAfter('?')
            if (query.isEmpty()) "$base?wmode=opaque" else "$base?wmode=opaque&$query"
        } else {
            "$withoutFragment?wmode=opaque"
        }
        return if (fragment.isNotEmpty()) "$rewritten#$fragment" else rewritten
    }

    /**
     * `moly.php` / `haydi.php` / gömülü oynatıcılardaki tüm gizleme katmanlarını açar:
     *  1. Dean Edwards `eval(function(p,a,c,k,e,d){...})` paketli JS
     *  2. `atob(unescape("..."))`, `unescape(atob("..."))`, düz `unescape("%3C...")` ve `atob("...")`
     *  3. `CryptoJS.AES.decrypt(...)` OpenSSL-salted AES-256-CBC
     */
    fun decodeWrapperHtml(html: String): String {
        if (html.isBlank()) return html
        var working = html

        unpackJs(working)?.let { unpacked ->
            working = "$working\n$unpacked"
        }

        extractUnescapeOrBase64Payload(working)?.let { decoded ->
            working = "$decoded\n$working"
        }

        encryptedPayload(working)?.let { payload ->
            runCatching { CryptoJS.decrypt(payload.password, payload.ciphertext) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { decrypted -> working = "$decrypted\n$working" }
        }

        return working
    }

    private fun extractUnescapeOrBase64Payload(html: String): String? {
        // 1) unescape("...") — gövde ya doğrudan %3Ciframe... ya da Base64 dizgesidir
        val unescapeMatch = Regex("""unescape\s*\(\s*(['"])((?:\\.|(?!\1).)+)\1\s*\)""", RegexOption.IGNORE_CASE)
            .find(html)
        if (unescapeMatch != null) {
            val raw = unescapeMatch.groupValues[2]
            val uriDecoded = percentDecode(raw)
            if (uriDecoded.contains('<') || uriDecoded.contains("http", ignoreCase = true)) {
                return uriDecoded
            }
            val b64Decoded = tryDecodeBase64Utf8(uriDecoded)
            if (b64Decoded != null) return percentDecode(b64Decoded)
        }

        // 2) atob("...") — doğrudan Base64 ile gizlenmiş <iframe> veya <script>
        for (match in Regex("""atob\s*\(\s*(['"])([A-Za-z0-9+/=\s_-]{16,})\1\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(html)) {
            val decoded = tryDecodeBase64Utf8(match.groupValues[2]) ?: continue
            val clean = percentDecode(decoded)
            if (clean.contains('<') || clean.contains("http", ignoreCase = true)) {
                return clean
            }
        }
        return null
    }

    private fun percentDecode(input: String): String {
        val uReplaced = input.replace(Regex("""%u([0-9A-Fa-f]{4})""")) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
        return runCatching {
            URLDecoder.decode(uReplaced.replace("+", "%2B"), "UTF-8")
        }.getOrDefault(uReplaced)
    }

    private fun tryDecodeBase64Utf8(candidate: String): String? {
        val compact = candidate.trim()
        if (compact.length < 12) return null
        return runCatching {
            val bytes = CryptoJS.base64Decode(compact)
            if (bytes.isEmpty()) return@runCatching null
            val text = String(bytes, Charsets.UTF_8)
            if (text.count { it == '\uFFFD' } > text.length / 10) null else text
        }.getOrNull()
    }

    /** Dean Edwards `eval(function(p,a,c,k,e,d){...}('payload',radix,count,'sym1|sym2'.split('|')))` açıcı. */
    fun unpackJs(html: String): String? {
        if (!html.contains("eval(function(p,a,c,k,e,", ignoreCase = true)) return null
        val regex = Regex(
            """eval\(function\(p,a,c,k,e,[dr]\)\{.*?\}\(\s*(['"])((?:\\.|(?!\1).)*)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])((?:\\.|(?!\5).)*)\5\.split\(['"]\|['"]\)""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val outputs = mutableListOf<String>()
        for (match in regex.findAll(html)) {
            val payload = match.groupValues[2]
                .replace("\\'", "'")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
            val radix = match.groupValues[3].toIntOrNull() ?: continue
            val count = match.groupValues[4].toIntOrNull() ?: continue
            val symtab = match.groupValues[6].split('|')
            if (radix !in 2..62 || count <= 0) continue

            val unpacked = Regex("""\b[0-9a-zA-Z]+\b""").replace(payload) { tokenMatch ->
                val token = tokenMatch.value
                val index = decodeBaseN(token, radix)
                if (index in 0 until count && index < symtab.size && symtab[index].isNotEmpty()) {
                    symtab[index]
                } else {
                    token
                }
            }
            outputs += unpacked
        }
        return outputs.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun decodeBaseN(token: String, radix: Int): Int {
        var result = 0
        for (ch in token) {
            val digit = when (ch) {
                in '0'..'9' -> ch - '0'
                in 'a'..'z' -> ch - 'a' + 10
                in 'A'..'Z' -> ch - 'A' + 36
                else -> return -1
            }
            if (digit >= radix) return -1
            result = result * radix + digit
        }
        return result
    }

    fun encryptedPayload(html: String): EncryptedPayload? {
        val literal = aesDecryptLiteral.find(html)
        if (literal != null) {
            val ciphertext = literal.groupValues[1].ifBlank { literal.groupValues[2] }
            val password = literal.groupValues[3].ifBlank { literal.groupValues[4] }
            if (ciphertext.isNotBlank() && password.isNotBlank()) {
                return EncryptedPayload(ciphertext, password)
            }
        }
        val varCall = aesDecryptVar.find(html) ?: return null
        val varName = varCall.groupValues[1]
        val password = varCall.groupValues[2].ifBlank { varCall.groupValues[3] }
        if (password.isBlank()) return null
        val varAssign = Regex(
            """\b${Regex.escape(varName)}\s*=\s*(?:"((?:\\.|[^"\\])+)"|'((?:\\.|[^'\\])+)'|(\{[^{}]*\}))""",
        ).find(html) ?: return null
        val ciphertext = varAssign.groupValues[1]
            .ifBlank { varAssign.groupValues[2] }
            .ifBlank { varAssign.groupValues[3] }
        if (ciphertext.isBlank()) return null
        return EncryptedPayload(ciphertext, password)
    }

    // ────────── JWPlayer / HTML5 / HLS / Altyazı ayrıştırıcıları ──────────

    /** Reads JWPlayer's file/type/kind/label properties, PlayerJS lists and plain HTML5 source tags. */
    fun mediaSources(html: String): List<MediaSource> {
        val sources = mutableListOf<MediaSource>()
        val declaredSources = extractArray(html, sourcesArray)
        val sourceObjects = declaredSources?.let { objectPattern.findAll(it) } ?: objectPattern.findAll(html)

        sourceObjects.forEach { objectMatch ->
            val source = mediaSourceFromObject(objectMatch.value) ?: return@forEach
            sources += source
        }

        val document = Jsoup.parse(html)
        document.select("video[src], video source[src], source[src]").forEach { element ->
            val url = element.attr("src").trim()
            if (url.isNotBlank()) {
                sources += MediaSource(
                    url = url,
                    type = element.attr("type").trim(),
                    label = element.attr("label").ifBlank { element.attr("res") }.trim(),
                )
            }
        }

        if (sources.isEmpty() && declaredSources == null) {
            fileProperty.findAll(html).forEach { match ->
                val raw = normalizeJsLiteral(match.groupValues[1].ifBlank { match.groupValues[2] })
                sources += expandPlayerJsFiles(raw)
            }
            extraStreamProperty.findAll(html).forEach { match ->
                val raw = normalizeJsLiteral(match.groupValues[1].ifBlank { match.groupValues[2] })
                if (raw.isNotBlank()) sources += MediaSource(raw)
            }
        }

        if (sources.isEmpty()) {
            fallbackMediaUrls(html).forEach { sources += MediaSource(it) }
        }

        return sources
            .filterNot { source ->
                source.kind.equals("captions", ignoreCase = true) ||
                    source.kind.equals("subtitles", ignoreCase = true) ||
                    source.kind.equals("thumbnails", ignoreCase = true) ||
                    source.kind.equals("chapters", ignoreCase = true)
            }
            .distinctBy { it.url }
    }

    /** PlayerJS biçimi: `"[720p]https://.../720.mp4,[1080p]https://.../1080.mp4"`. */
    private fun expandPlayerJsFiles(raw: String): List<MediaSource> {
        if (raw.isBlank()) return emptyList()
        if (!raw.contains("[") || !raw.contains("]http", ignoreCase = true)) {
            return listOf(MediaSource(raw))
        }
        return raw.split(',').mapNotNull { part ->
            val trimmed = part.trim()
            val labeled = Regex("""^\[([^\]]+)]\s*(https?://\S+)$""", RegexOption.IGNORE_CASE).find(trimmed)
            if (labeled != null) {
                MediaSource(url = labeled.groupValues[2], label = labeled.groupValues[1].trim())
            } else if (trimmed.startsWith("http", ignoreCase = true)) {
                MediaSource(url = trimmed)
            } else {
                null
            }
        }
    }

    /** Extracts subtitle/caption tracks from player objects and HTML5 `<track>` tags. */
    fun subtitleTracks(html: String): List<SubtitleTrack> {
        val tracks = LinkedHashMap<String, SubtitleTrack>()

        objectPattern.findAll(html)
            .mapNotNull { objectMatch -> subtitleTrackFromObject(objectMatch.value) }
            .forEach { tracks.putIfAbsent(it.url, it) }

        val doc = Jsoup.parse(html)
        doc.select("track[src]").forEach { el ->
            val kind = el.attr("kind").trim()
            if (kind.isNotBlank() &&
                !kind.equals("captions", ignoreCase = true) &&
                !kind.equals("subtitles", ignoreCase = true)
            ) {
                return@forEach
            }
            val src = el.attr("src").trim().takeIf { it.isNotBlank() } ?: return@forEach
            val lang = el.attr("srclang").trim()
            val label = el.attr("label").trim().ifBlank { lang }.ifBlank { "Altyazı" }
            tracks.putIfAbsent(src, SubtitleTrack(src, label, kind.ifBlank { "subtitles" }, lang))
        }

        return tracks.values.toList()
    }

    fun isHls(source: MediaSource): Boolean =
        source.type.contains("mpegurl", ignoreCase = true) ||
            source.type.contains("hls", ignoreCase = true) ||
            source.url.substringBefore('?').substringBefore('#').endsWith(".m3u8", ignoreCase = true) ||
            source.url.contains(".m3u8?", ignoreCase = true) ||
            source.url.contains("/embed/sheila/", ignoreCase = true)

    fun isMp4(source: MediaSource): Boolean {
        val cleanPath = source.url.substringBefore('?').substringBefore('#')
        return source.type.contains("mp4", ignoreCase = true) ||
            source.type.contains("webm", ignoreCase = true) ||
            cleanPath.endsWith(".mp4", ignoreCase = true) ||
            cleanPath.endsWith(".m4v", ignoreCase = true) ||
            cleanPath.endsWith(".webm", ignoreCase = true) ||
            cleanPath.endsWith(".mkv", ignoreCase = true)
    }

    // ────────── MolyStream / Vidmoly / OK.ru / Sibnet / Mail.ru / VK ──────────

    /**
     * `king.php` içinden çıkan `/embed/{id}` oynatıcı sayfasından doğrudan HLS
     * master akışı veren `/embed/sheila/{id}` uç noktasını üretir.
     */
    fun molyStreamSheilaUrl(url: String): String? {
        if (!isMolyStreamHost(url) || isMolyStreamStreamUrl(url)) return null
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (!Regex("""/embed/[^/]+/?$""", RegexOption.IGNORE_CASE).containsMatchIn(path)) return null
        return url.replaceFirst(Regex("""/embed/""", RegexOption.IGNORE_CASE), "/embed/sheila/")
    }

    /** `/embed/sheila/{id}` akış adresinden üst oynatıcı sayfası `/embed/{id}` adresini üretir. */
    fun molyStreamPlayerPageUrl(url: String): String? {
        if (!isMolyStreamStreamUrl(url)) return null
        return url.replaceFirst(Regex("""/embed/sheila/""", RegexOption.IGNORE_CASE), "/embed/")
    }

    /** `dbx.molystream.org` erişilemediğinde `www.molystream.org` yedek aynasını üretir. */
    fun molyStreamMirrorUrl(url: String): String? {
        val host = normalizedHost(url) ?: return null
        return when {
            host.equals("dbx.molystream.org", ignoreCase = true) ->
                url.replaceFirst(Regex("""dbx\.molystream\.org""", RegexOption.IGNORE_CASE), "www.molystream.org")
            host.equals("www.molystream.org", ignoreCase = true) ->
                url.replaceFirst(Regex("""www\.molystream\.org""", RegexOption.IGNORE_CASE), "dbx.molystream.org")
            else -> null
        }
    }

    fun isMolyStreamStreamUrl(url: String): Boolean {
        if (!isMolyStreamHost(url)) return false
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        return Regex("""/embed/sheila/[^/]+/?$""", RegexOption.IGNORE_CASE).containsMatchIn(path)
    }

    fun isMolyStreamHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return Regex("""(?:^|\.)molystream\.(?:org|net|com)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(host)
    }

    fun isVidmolyHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return Regex("""(?:^|\.)(?:vidmoly\.[a-z]{2,6}|moly\.to)$""", RegexOption.IGNORE_CASE)
            .containsMatchIn(host)
    }

    fun isOkRuHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return host == "ok.ru" || host.endsWith(".ok.ru") ||
            host == "odnoklassniki.ru" || host.endsWith(".odnoklassniki.ru")
    }

    fun isSibnetHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return host == "sibnet.ru" || host.endsWith(".sibnet.ru")
    }

    fun isMailRuHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return host == "mail.ru" || host.endsWith(".mail.ru")
    }

    fun isVkHost(url: String): Boolean {
        val host = normalizedHost(url) ?: return false
        return host == "vk.com" || host.endsWith(".vk.com") ||
            host == "vkvideo.ru" || host.endsWith(".vkvideo.ru")
    }

    /** Bilinen taşınmış embed domainlerini ve kısa yolları kanonik oynatıcı adresine çevirir. */
    fun normalizeEmbedUrl(url: String): String {
        var normalized = if (url.startsWith("//")) "https:$url" else url.trim()
        val host = normalizedHost(normalized) ?: return normalized

        if (isVidmolyHost(normalized)) {
            if (normalized.contains("/w/")) {
                val base = normalized.substringBefore('?')
                    .replaceFirst("/w/", "/embed-")
                    .let { if (it.endsWith(".html")) it else "$it.html" }
                val query = normalized.substringAfter('?', "")
                normalized = if (query.isNotEmpty()) "$base?$query" else base
            }
            if (host == "vidmoly.me") {
                normalized = normalized.replaceFirst(Regex("""vidmoly\.me""", RegexOption.IGNORE_CASE), "vidmoly.net")
            }
        } else if (isOkRuHost(normalized)) {
            normalized = normalized
                .replaceFirst(Regex("""^http://""", RegexOption.IGNORE_CASE), "https://")
                .replace("/video/", "/videoembed/")
        } else if (host in setOf("dood.la", "dood.wf", "dood.pm", "dood.to", "dood.watch", "dood.sh", "dood.so")) {
            normalized = normalized.replaceFirst(Regex(Regex.escape(host), RegexOption.IGNORE_CASE), "doodstream.com")
        } else if (host in setOf("uqload.co", "uqload.com", "uqload.io", "uqload.to")) {
            normalized = normalized.replaceFirst(Regex(Regex.escape(host), RegexOption.IGNORE_CASE), "uqload.vc")
        }
        return normalized
    }

    fun okRuVideoId(url: String): String? =
        Regex("""(?:videoembed|video)/(\d{6,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)

    /**
     * OK.ru (`Odnok`) hem `videoembed` HTML (`data-options` içinde `&quot;` / `\u0026` kaçışlı)
     * hem de `POST /dk?cmd=videoPlayerMetadata&mid=<id>` JSON yanıtlarını Jackson kullanmadan çözer.
     */
    fun parseOkRuStreams(body: String): List<OkRuStream> {
        if (body.isBlank()) return emptyList()
        val unescaped = body
            .replace("\\&quot;", "\"")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&amp;", "&")
            .let { unescapeJson(unescapeJson(it)) }
            .replace("\\/", "/")

        val results = LinkedHashMap<String, OkRuStream>()

        for (hlsKey in listOf("ondemandHls", "hlsManifestUrl", "hlsMasterPlaylistUrl")) {
            Regex(""""$hlsKey"\s*:\s*"(https?:[^"]+)"""").findAll(unescaped).forEach { m ->
                val hlsUrl = m.groupValues[1].trim()
                if (hlsUrl.isNotBlank()) {
                    results.putIfAbsent(hlsUrl, OkRuStream(hlsUrl, "HLS", isHls = true))
                }
            }
        }

        val videosBlock = extractArray(unescaped, Regex(""""videos"\s*:\s*\["""))
        if (videosBlock != null) {
            objectPattern.findAll(videosBlock).forEach { objMatch ->
                val obj = objMatch.value
                val rawName = jsonStringField(obj, "name").orEmpty()
                var rawUrl = jsonStringField(obj, "url").orEmpty().trim()
                if (rawUrl.startsWith("//")) rawUrl = "https:$rawUrl"
                if (!rawUrl.startsWith("http", ignoreCase = true)) return@forEach
                if (jsonStringField(obj, "disallowed") == "true") return@forEach

                val qualityLabel = mapOkRuQuality(rawName)
                val isHls = rawUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true)
                results.putIfAbsent(rawUrl, OkRuStream(rawUrl, qualityLabel, isHls))
            }
        }

        return results.values.toList()
    }

    private fun mapOkRuQuality(rawName: String): String =
        when (rawName.trim().lowercase(Locale.ROOT)) {
            "mobile" -> "144p"
            "lowest" -> "240p"
            "low"    -> "360p"
            "sd"     -> "480p"
            "hd"     -> "720p"
            "full"   -> "1080p"
            "quad"   -> "1440p"
            "ultra"  -> "2160p"
            else     -> rawName.trim()
        }

    fun sibnetShellUrl(url: String): String? {
        if (!isSibnetHost(url)) return null
        if (url.contains("shell.php", ignoreCase = true)) return url
        val id = Regex("""(?:videoid=|/video)(\d{3,})""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return null
        return "https://video.sibnet.ru/shell.php?videoid=$id"
    }

    fun parseSibnetUrl(body: String, baseUrl: String = "https://video.sibnet.ru/"): String? {
        val rawPath = Regex("""player\.src\(\s*\[\s*\{\s*src\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.get(1)
            ?: Regex("""["'](/videos/[^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)
            ?: return null
        return resolveUrl(rawPath.replace("\\/", "/"), baseUrl)
    }

    fun parseMailRuStreams(body: String): List<MailRuStream> {
        val block = extractArray(body, Regex(""""videos"\s*:\s*\[""")) ?: return emptyList()
        val streams = LinkedHashMap<String, MailRuStream>()
        objectPattern.findAll(block).forEach { match ->
            val obj = match.value
            var url = jsonStringField(obj, "url").orEmpty().replace("\\/", "/").trim()
            if (url.startsWith("//")) url = "https:$url"
            if (!url.startsWith("http", ignoreCase = true)) return@forEach
            val key = jsonStringField(obj, "key").orEmpty().trim()
            streams.putIfAbsent(url, MailRuStream(url, key))
        }
        return streams.values.toList()
    }

    fun parseVkStreams(body: String): List<VkStream> {
        val cleaned = body.replace("\\/", "/")
        val out = LinkedHashMap<String, VkStream>()
        Regex(""""hls"\s*:\s*"(https?:[^"]+)"""").findAll(cleaned).forEach { m ->
            out.putIfAbsent(m.groupValues[1], VkStream(m.groupValues[1], "HLS", isHls = true))
        }
        for (height in listOf(2160, 1440, 1080, 720, 480, 360, 240)) {
            Regex(""""url$height"\s*:\s*"(https?:[^"]+)"""").findAll(cleaned).forEach { m ->
                out.putIfAbsent(m.groupValues[1], VkStream(m.groupValues[1], "${height}p", isHls = false))
            }
        }
        return out.values.toList()
    }

    // ────────── HLS & Arama JSON yardımcıları ──────────

    fun isHlsManifest(body: String): Boolean {
        val header = body.removePrefix("\uFEFF").trimStart()
        return header.startsWith("#EXTM3U", ignoreCase = true) &&
            (header.length == 7 || header[7].isWhitespace())
    }

    /** MolyStream has been observed returning a plain HTTPS URL from its stream route. */
    fun plainHttpsTarget(body: String): String? {
        val candidate = body.removePrefix("\uFEFF").trim()
        if (!candidate.startsWith("https://", ignoreCase = true)) return null
        if (candidate.any { it.isWhitespace() || it.code < 0x20 || it == 0x27.toChar() || it == 0x22.toChar() }) {
            return null
        }
        return candidate.takeIf { resolveUrl(it, it) != null }
    }

    /**
     * Master M3U8 listesindeki HER `#EXT-X-STREAM-INF` varyantını gerçek çözünürlüğüyle
     * (`RESOLUTION=WxH`) ayrı kaynak olarak çözer.
     */
    fun hlsVariants(body: String, baseUrl: String): List<HlsVariant> {
        if (!isHlsManifest(body)) return emptyList()
        val ordered = LinkedHashMap<String, Int>()
        var pendingHeight = 0
        body.replace("\uFEFF", "").lines().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) ->
                    pendingHeight = Regex("""RESOLUTION\s*=\s*\d+x(\d+)""", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                line.startsWith("#") || line.isBlank() -> Unit
                else -> {
                    resolveUrl(line, baseUrl)?.let { ordered.putIfAbsent(it, pendingHeight) }
                    pendingHeight = 0
                }
            }
        }
        return ordered.map { HlsVariant(it.key, it.value) }
    }

    /**
     * `dwls_search` yanıtı: hem düz JSON dizisi `[{post_title, permalink, attachment_thumbnail}]`
     * hem de `{results:[...]}` biçimini Jackson kullanmadan (`KTS-CSP-9110` koruması) ayrıştırır.
     */
    fun dwlsSearchResults(json: String): List<DwlsResult> {
        val trimmed = json.trimStart()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return emptyList()
        return Regex("""\{[^{}]*}""").findAll(json).mapNotNull { match ->
            val obj = match.value
            val title = Jsoup.parse(jsonStringField(obj, "post_title").orEmpty()).text().trim()
            val permalink = jsonStringField(obj, "permalink").orEmpty().trim()
            if (title.isBlank() || permalink.isBlank()) return@mapNotNull null
            DwlsResult(
                title = title,
                url = permalink.replace("\\/", "/"),
                thumbnail = jsonStringField(obj, "attachment_thumbnail").orEmpty().replace("\\/", "/"),
            )
        }.toList()
    }

    private fun jsonStringField(obj: String, key: String): String? {
        val pattern = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"")
        val raw = pattern.find(obj)?.groupValues?.get(1) ?: return null
        return unescapeJson(raw)
    }

    private fun unescapeJson(raw: String): String {
        if (!raw.contains('\\')) return raw
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            if (c != '\\' || i + 1 >= raw.length) {
                sb.append(c)
                i++
                continue
            }
            when (val n = raw[i + 1]) {
                'n' -> sb.append('\n')
                't' -> sb.append('\t')
                'r' -> sb.append('\r')
                'b' -> sb.append('\b')
                'f' -> sb.append('\u000C')
                'u' -> {
                    val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                    val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                    if (code != null) {
                        sb.append(code.toChar())
                        i += 6
                        continue
                    }
                    sb.append("\\u")
                    i += 2
                    continue
                }
                else -> sb.append(n)
            }
            i += 2
        }
        return sb.toString()
    }

    /**
     * Küçük afişleri (`...-50x50.jpg` veya `.../50x50/...`) kartlarda kullanılan `200x290`
     * boyutuna yükseltir. Bölüm yatay küçük resimlerine (`220x140`, `300x81`) dokunmaz.
     */
    fun upscaleThumbnail(url: String): String {
        if (url.isBlank()) return url
        val dashReplaced = Regex("""-(?:50x50|60x60|75x75|100x100|150x150)(?=\.[a-z]{3,4}(?:[?#]|$))""", RegexOption.IGNORE_CASE)
            .replace(url, "-200x290")
        if (dashReplaced != url) return dashReplaced
        return Regex("""(?i)/\d{2,4}x\d{2,4}/""").replace(url) { match ->
            if (match.value.contains("200x290", ignoreCase = true)) match.value else "/200x290/"
        }
    }

    /**
     * Bölüm listesi `article.grid-box` dışındaki temalarda da çalışsın diye
     * `-sezon-N-bolum-M(-izle)` kalıbına uyan tüm çapaları toplar.
     */
    fun episodeAnchors(document: Document, seriesUrl: String? = null): List<EpisodeAnchor> {
        val all = LinkedHashMap<String, EpisodeAnchor>()
        stripChrome(document).select("a[href]").forEach { anchor ->
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href") }.trim()
            val (season, episode) = episodeNumbers(href) ?: return@forEach
            all.putIfAbsent(href, EpisodeAnchor(href, season, episode))
        }

        val base = seriesUrl?.let { seriesSlugBase(it) } ?: return all.values.toList()
        val own = all.values.filter { episode ->
            lastSegment(episode.url).startsWith("$base-", ignoreCase = true)
        }
        return if (own.isNotEmpty()) own else all.values.toList()
    }

    fun episodeNumbers(href: String): Pair<Int, Int>? {
        val legacy = Regex("""-sezon-(\d+)-bolum-(\d+)(?:-izle)?/?""", RegexOption.IGNORE_CASE).find(href)
        if (legacy != null) {
            val season = legacy.groupValues[1].toIntOrNull() ?: return null
            val episode = legacy.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        val current = Regex("""(?:^|-)(\d+)-sezon-(\d+)-bolum(?:-|$)""", RegexOption.IGNORE_CASE)
            .find(lastSegment(href)) ?: return null
        val season = current.groupValues[1].toIntOrNull() ?: return null
        val episode = current.groupValues[2].toIntOrNull() ?: return null
        return season to episode
    }

    private fun lastSegment(url: String): String =
        url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')

    /** `/diziler/a-gentleman-in-moscow-hd-3/` -> `a-gentleman-in-moscow`. */
    fun seriesSlugBase(seriesUrl: String): String? {
        var slug = runCatching { URI(seriesUrl).path.orEmpty() }.getOrDefault("")
            .trim('/').substringAfterLast('/')
            .takeIf { it.isNotBlank() } ?: return null
        val suffix = Regex("""-(?:izle|hd)(?:-\d+)?$""", RegexOption.IGNORE_CASE)
        while (suffix.containsMatchIn(slug)) {
            slug = suffix.replace(slug, "")
        }
        return slug.takeIf { it.isNotBlank() }
    }

    /** JWPlayer yapılandırması çözülemediğinde açık medya/sheila URL'lerini tarar. */
    fun fallbackMediaUrls(html: String): List<String> {
        val out = LinkedHashSet<String>()
        Regex(
            """["']?(https?:\\?/\\?/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']?""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { out.add(it.groupValues[1].replace("\\/", "/")) }
        Regex(
            """["']?(https?:\\?/\\?/[^"'\s<>]*(?:master\.txt|/embed/sheila/)[^"'\s<>]*)["']?""",
            RegexOption.IGNORE_CASE,
        ).findAll(html).forEach { out.add(it.groupValues[1].replace("\\/", "/")) }
        return out.toList()
    }

    fun isSupportedPlayerUrl(url: String, siteUrl: String): Boolean {
        val path = runCatching { URI(url).path.orEmpty() }.getOrDefault("")
        if (!knownPlayerPath.containsMatchIn(path)) return false

        val host = normalizedHost(url) ?: return false
        val siteHost = normalizedHost(siteUrl) ?: return false
        val sameSite = host == siteHost || host.removePrefix("www.") == siteHost.removePrefix("www.")
        return sameSite || (isMolyStreamHost(url) && path.contains("/embed/", ignoreCase = true))
    }

    fun resolveUrl(rawUrl: String?, baseUrl: String): String? {
        val raw = rawUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw == "#" || raw.equals("about:blank", ignoreCase = true) ||
            raw.startsWith("javascript:", ignoreCase = true) || raw.startsWith("data:", ignoreCase = true)
        ) {
            return null
        }

        val resolved = runCatching {
            URI(baseUrl).resolve(raw.replace(" ", "%20")).toASCIIString()
        }.getOrNull() ?: return null
        if (!resolved.startsWith("https://", ignoreCase = true) &&
            !resolved.startsWith("http://", ignoreCase = true)
        ) return null
        return resolved
    }

    private fun extractArray(source: String, marker: Regex): String? {
        val match = marker.find(source) ?: return null
        val start = source.indexOf('[', match.range.first)
        if (start < 0) return null

        var depth = 0
        var quote: Char? = null
        var escaped = false
        for (index in start until source.length) {
            val character = source[index]
            if (quote != null) {
                if (escaped) {
                    escaped = false
                } else if (character == '\\') {
                    escaped = true
                } else if (character == quote) {
                    quote = null
                }
                continue
            }

            when (character) {
                '\'', '"', '`' -> quote = character
                '[' -> depth += 1
                ']' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start + 1, index)
                }
            }
        }
        return null
    }

    private fun iframeUrls(elements: List<Element>, pageUrl: String): List<String> =
        elements.mapNotNull { iframe ->
            val candidates = listOf("src", "data-src", "data-lazy-src", "data-litespeed-src")
                .map { iframe.attr(it) }
            candidates.firstNotNullOfOrNull { candidate -> resolveUrl(candidate, pageUrl) }
        }.filterNot { it.contains("\${") || it.contains("{{") }.distinct()

    private fun mediaSourceFromObject(sourceObject: String): MediaSource? {
        val fileMatch = fileProperty.find(sourceObject) ?: return null
        val url = normalizeJsLiteral(fileMatch.groupValues[1].ifBlank { fileMatch.groupValues[2] })
        if (url.isBlank()) return null

        val kind = propertyValue(kindProperty, sourceObject).orEmpty()
        val type = propertyValue(typeProperty, sourceObject).orEmpty()
        val label = propertyValue(labelProperty, sourceObject).orEmpty()
        return MediaSource(url, type, kind, label)
    }

    private fun subtitleTrackFromObject(sourceObject: String): SubtitleTrack? {
        val fileMatch = fileProperty.find(sourceObject) ?: return null
        val url = normalizeJsLiteral(fileMatch.groupValues[1].ifBlank { fileMatch.groupValues[2] })
        if (url.isBlank()) return null

        val kind = propertyValue(kindProperty, sourceObject).orEmpty()
        val isSubtitleKind = kind.equals("captions", ignoreCase = true) ||
            kind.equals("subtitles", ignoreCase = true)
        val extension = url.substringBefore('?').substringBefore('#').substringAfterLast('.', "")
        val isSubtitleFile = extension.equals("vtt", ignoreCase = true) ||
            extension.equals("srt", ignoreCase = true) ||
            extension.equals("ass", ignoreCase = true)
        if (kind.isNotBlank() && !isSubtitleKind) return null
        if (!isSubtitleKind && !isSubtitleFile) return null

        val language = propertyValue(languageProperty, sourceObject).orEmpty()
        val label = propertyValue(labelProperty, sourceObject)
            ?.takeIf { it.isNotBlank() }
            ?: language.takeIf { it.isNotBlank() }
            ?: "Altyazı"
        return SubtitleTrack(url, label, kind, language)
    }

    private fun normalizeJsLiteral(value: String): String {
        val backslash = 92.toChar().toString()
        return value
            .replace(backslash + "/", "/")
            .replace(backslash + "u0026", "&", ignoreCase = true)
            .replace(backslash + "x26", "&", ignoreCase = true)
            .replace("&amp;", "&", ignoreCase = true)
    }

    private fun propertyValue(regex: Regex, source: String): String? {
        val match = regex.find(source) ?: return null
        return match.groupValues[1].ifBlank { match.groupValues[2] }
    }

    private fun normalizedHost(url: String): String? =
        runCatching { URI(url).host?.lowercase() }.getOrNull()
}
