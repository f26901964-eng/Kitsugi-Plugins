package com.hexated

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/** Katalog/arama kartı. */
internal data class AnizmCard(
    val name: String,
    val url: String,
    val poster: String? = null,
    val score: Double? = null,
    /** Kart üzerinde yazan son bölüm numarası (varsa) — "Sub: 12" rozeti için. */
    val episodeHint: Int? = null,
    /** Aynı animenin alternatif adları (JSON aramasında dolu gelir). */
    val altNames: List<String> = emptyList()
)

/** Bölüm satırı. */
internal data class AnizmEpisode(
    val url: String,
    val number: Int?,
    val name: String?,
    val date: String? = null,
    val poster: String? = null
)

/** Detay sayfası çıktısı. */
internal data class AnizmDetail(
    val title: String,
    val poster: String?,
    val plot: String?,
    val tags: List<String>,
    val year: Int?,
    val score: Double?,
    val studio: String?,
    val typeText: String?,
    val episodes: List<AnizmEpisode>
)

/**
 * Anizm (puffytr.com / anizm.net ailesi) HTML çözümleyicisi.
 *
 * TASARIM KURALI: Her alan için **birden fazla seçici** denenir ve hiçbiri tek başına
 * zorunlu değildir. Site teması (v2 "anizm_pageTitle" ↔ v3 "v3-catalog-card") değişse
 * bile eklenti veri üretmeye devam eder. Seçiciler, 2026-10 itibarıyla çalışan iki
 * bağımsız istemciden alınmıştır:
 *  - Aniyomi `yuzono/anime-extensions` (src/tr/anizm, 2026-10-25 güncellemesi)
 *  - CloudStream `Saloo1575/SalooRepo` (Anizm, 2026-10-15) + `hexated` orijinali
 *
 * Ağ/IO YOKTUR: bu dosya saf ayrıştırmadır, birim testleri ağ olmadan koşar.
 */
internal object AnizmSite {

    /** Anime slug'ı OLMAYAN yollar. */
    private val BLOCKED_PATHS = setOf(
        "kategoriler", "temalar", "profil", "arama", "giris-yap", "kayit-ol", "girisyap", "uyeol",
        "listeler", "bolum", "player", "raporver", "storage", "upload", "assets", "js", "css",
        "images", "api", "arama-sonuclari", "hakkimizda", "iletisim", "sss", "duyurular",
        "gizlilik", "kullanim-sartlari", "cerez", "destek", "kurallar", "haber", "blog", "tv",
        "yayin", "takvim", "sozluk", "fansublar", "fansub", "manga", "discord", "ara", "search",
        "sifremi-unuttum", "premium", "profil-ayarlari", "random", "sasirt-beni", "tavsiye-robotu"
    )

    /** Bu eklentinin kabul ettiği host işaretleri (site domain değiştirdiğinde de çalışır). */
    private val SITE_HOST_MARKERS = listOf("puffytr", "anizm", "anizle")

    private val EPISODE_PATH = Regex("^(.*?)-(\\d{1,4})-bolum(?:-[a-z0-9]+)*$", RegexOption.IGNORE_CASE)

    private val IMAGE_ATTRS = listOf("data-src", "data-original", "data-lazy-src", "data-url", "src")

    // ------------------------------------------------------------------
    // URL yardımcıları
    // ------------------------------------------------------------------

    /** Göreli/tam adresi mutlak adrese çevirir; geçersizse null. */
    fun absolute(base: String, href: String?): String? {
        val raw = href?.trim() ?: return null
        if (raw.isEmpty() || raw.startsWith("#") || raw.startsWith("data:")) return null
        val lower = raw.lowercase()
        if (lower.startsWith("javascript:") || lower.startsWith("mailto:") || lower.startsWith("tel:")) return null
        return try {
            URI(base).resolve(raw).toString()
        } catch (_: Exception) {
            if (lower.startsWith("http")) raw else null
        }
    }

    fun hostOf(url: String): String = try {
        (URI(url).host ?: "").lowercase().removePrefix("www.")
    } catch (_: Exception) {
        ""
    }

    fun isSiteUrl(url: String): Boolean {
        val host = hostOf(url)
        if (host.isEmpty()) return false
        return SITE_HOST_MARKERS.any { host.contains(it) }
    }

    /** "/one-piece" veya "one-piece" — karşılaştırma için tek biçim. */
    fun seriesPathOf(url: String): String? {
        val path = url.substringAfter("://", url)
            .substringAfter("/", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .lowercase()
        if (path.isEmpty()) return null
        val stripped = EPISODE_PATH.replace(path, "$1").trim('/')
        val normalised = stripped.removeSuffix("-izle").trim('-')
        return normalised.ifBlank { null }
    }

    /** Bölüm URL'sinden numara ("-12-bolum"). */
    fun episodeNumberFromUrl(url: String): Int? {
        val path = url.substringAfter("://", url).substringAfter("/", "").substringBefore("?")
        val m = EPISODE_PATH.find(path) ?: return null
        return m.groupValues.getOrNull(2)?.toIntOrNull()
    }

    /** Bölüm URL'sini seri (detay) URL'sine indirger. Bölüm değilse null. */
    fun toSeriesUrl(url: String): String? {
        val path = url.substringAfter("://", url).substringAfter("/", "").substringBefore("?")
        val m = EPISODE_PATH.find(path) ?: return null
        val prefix = url.substringBefore("://") + "://" + url.substringAfter("://").substringBefore("/")
        // "-izle" ile biten detay adresleri (…/tsuki-ga-kirei-izle) korunur.
        val seriesPath = m.groupValues[1]
        return "$prefix/$seriesPath"
    }

    fun isEpisodeUrl(url: String): Boolean = episodeNumberFromUrl(url) != null

    /** Slug'dan okunabilir başlık (son çare). */
    fun titleFromSlug(url: String): String {
        val slug = url.substringAfterLast("/").substringBefore("?").removeSuffix(".html")
        val cleaned = EPISODE_PATH.replace(slug, "$1").removeSuffix("-izle")
        return cleaned.split('-', '_')
            .filter { it.isNotBlank() }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    /**
     * Başlık gerçekten bir içerik adı mı?
     *
     * DİKKAT: burada "slug gibi görünüyorsa ele" kuralı **yoktur** — "Bleach",
     * "Naruto", "Monster" gibi tek kelimelik gerçek adlar da slug biçimindedir.
     * Yalnızca menü/gezinme etiketleri ve salt sayılar elenir.
     */
    fun isInvalidTitle(title: String?): Boolean {
        val t = title?.trim() ?: return true
        if (t.length < 2) return true
        val key = AnizmMatch.fold(t)
        if (key.isEmpty()) return true
        if (key in BLOCKED_TITLES) return true
        if (key.matches(Regex("^\\d{1,4}$"))) return true
        if (key.matches(Regex("^\\d{1,4} bolum$"))) return true
        return false
    }

    /** Menü/gezinme etiketleri (fold edilmiş biçimde). */
    private val BLOCKED_TITLES = setOf(
        "izle", "bolum", "giris yap", "kayit ol", "kategoriler", "temalar", "profil", "hesabim",
        "anasayfa", "devamini oku", "hepsini izle", "ilk bolumu izle", "tum bolumler", "fragman",
        "menu", "ara", "arama", "son eklenenler", "yeni bolumler", "populer animeler", "fansublar",
        "manga", "takvim", "discord", "sasirt beni", "tavsiye robotu", "cikis yap", "ayarlar",
        "bildirimler", "mesajlar", "paylas", "rapor ver", "oy ver"
    )

    // ------------------------------------------------------------------
    // Kart çözümleme (arama / ana sayfa)
    // ------------------------------------------------------------------

    /** Bir <img> öğesinden kullanılabilir poster adresi (lazy-load öznitelikleri dahil). */
    fun imageUrl(img: Element?): String? {
        if (img == null) return null
        for (attr in IMAGE_ATTRS) {
            val value = img.attr(attr).trim()
            if (value.isNotEmpty() && !value.startsWith("data:") &&
                !value.contains("placeholder") && !value.endsWith(".svg")
            ) {
                return img.absUrl(attr).ifBlank { value }
            }
        }
        val srcset = img.attr("srcset").trim()
        if (srcset.isNotEmpty()) {
            return srcset.split(',').firstOrNull()?.trim()?.substringBefore(' ')
        }
        return null
    }

    private fun bestImage(el: Element): String? {
        val img = el.selectFirst("img") ?: el.parents().firstOrNull { it.selectFirst("img") != null }
            ?.selectFirst("img")
            ?: return null
        return imageUrl(img)
    }

    private fun titleOf(el: Element): String? {
        val candidates = listOfNotNull(
            el.selectFirst("h1, h2, h3, h4, h5, h6")?.text(),
            el.selectFirst("span.title, div.title, .animeName, .slideAnimeTitle, .anime-title, .card-title")?.text(),
            el.attr("title"),
            el.selectFirst("img")?.attr("alt")
        )
        for (candidate in candidates) {
            val cleaned = AnizmMatch.cleanTitle(candidate) ?: continue
            if (!isInvalidTitle(cleaned)) return cleaned
        }
        return null
    }

    private fun cardFromAnchor(anchor: Element, base: String): AnizmCard? {
        val href = absolute(base, anchor.attr("href").ifBlank { anchor.attr("data-href") }) ?: return null
        if (!isSiteUrl(href)) return null

        val path = href.substringAfter("://", "").substringAfter("/", "").substringBefore("?").trim('/')
        val firstSegment = path.substringBefore('/').lowercase()
        if (firstSegment.isEmpty() || AnizmMatch.isSlug(firstSegment).not()) return null
        if (BLOCKED_PATHS.contains(firstSegment)) return null
        if (href.contains("/profil/") || href.contains("/fansub/") || href.contains("/bolum/")) return null

        val seriesUrl = toSeriesUrl(href) ?: href
        val name = titleOf(anchor) ?: AnizmMatch.cleanTitle(titleFromSlug(seriesUrl)) ?: return null
        if (isInvalidTitle(name)) return null

        val scoreText = anchor.selectFirst(".v3-catalog-score, .score, .rating, rating")?.text()
            ?: anchor.attr("data-score")
        val score = scoreText?.replace(',', '.')?.let { text ->
            Regex("\\d{1,2}(?:\\.\\d)?").find(text)?.value?.toDoubleOrNull()
        }?.takeIf { it > 0.0 && it <= 10.0 }

        val hint = AnizmMatch.episodeOf(anchor.text()) ?: episodeNumberFromUrl(href)

        return AnizmCard(
            name = name,
            url = seriesUrl,
            poster = bestImage(anchor),
            score = score,
            episodeHint = hint
        )
    }

    /**
     * Sayfadaki katalog kartlarını döndürür. Sıra: bilinen kart yapıları → genel
     * bağlantı taraması. Aynı animenin tekrarı ayıklanır.
     */
    fun parseCards(doc: Document, base: String, limit: Int = 60): List<AnizmCard> {
        val out = LinkedHashMap<String, AnizmCard>()

        fun take(anchor: Element?) {
            if (anchor == null || out.size >= limit) return
            val card = cardFromAnchor(anchor, base) ?: return
            if (!out.containsKey(card.url)) out[card.url] = card
        }

        // 1) "Son eklenenler" listesi (div#episodesMiddle div.posterBlock > a)
        for (a in doc.select("div#episodesMiddle div.posterBlock > a, div.posterBlock > a")) take(a)
        // 2) Anasayfa carousel'i (a.slideAnimeLink)
        for (a in doc.select("a.slideAnimeLink, div.popularAnimeCarousel a")) take(a)
        // 3) Kategori/tema kartları
        for (card in doc.select("div.anime-card, div.cat-card, div.ui.card, div.animeCard, article.anime")) {
            take(card.selectFirst("a.anime-title, a.image, a[href]"))
        }
        // 4) Genel: görsel taşıyan anime bağlantıları (tema değişimine dayanıklı yedek)
        if (out.size < 5) {
            for (a in doc.select("a[href]")) {
                if (out.size >= limit) break
                if (a.selectFirst("img") == null && a.selectFirst("h1, h2, h3, h4, h5, h6") == null) continue
                take(a)
            }
        }
        return out.values.toList()
    }

    /** Arama sonuç sayfasındaki JSON'u (searchAnime / getAnimeListForSearch) karta çevirir. */
    fun cardFromJsonFields(
        base: String,
        title: String?,
        slug: String?,
        poster: String?,
        year: Int?,
        score: Double?,
        altNames: List<String>
    ): AnizmCard? {
        val cleanSlug = slug?.trim()?.trim('/') ?: return null
        if (cleanSlug.isEmpty()) return null
        val name = AnizmMatch.cleanTitle(title) ?: AnizmMatch.cleanTitle(titleFromSlug(cleanSlug)) ?: return null
        if (isInvalidTitle(name)) return null
        val url = if (cleanSlug.startsWith("http")) cleanSlug else "$base/${cleanSlug.removePrefix("/")}"
        val posterUrl = poster?.trim()?.takeIf { it.isNotEmpty() }
            ?.let { if (it.startsWith("http")) it else "$base/storage/pcovers/$it" }
        return AnizmCard(
            name = name,
            url = url,
            poster = posterUrl,
            score = score?.takeIf { it > 0.0 && it <= 10.0 },
            episodeHint = null,
            altNames = altNames
        )
    }

    // ------------------------------------------------------------------
    // Detay sayfası
    // ------------------------------------------------------------------

    private fun firstText(doc: Document, vararg selectors: String): String? {
        for (selector in selectors) {
            val text = doc.selectFirst(selector)?.text()?.trim()
            if (!text.isNullOrBlank()) return text
        }
        return null
    }

    private fun meta(doc: Document, vararg selectors: String): String? {
        for (selector in selectors) {
            val value = doc.selectFirst(selector)?.attr("content")?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun yearFrom(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        return Regex("\\b(19\\d{2}|20\\d{2})\\b").find(text)?.value?.toIntOrNull()
    }

    fun parseDetail(doc: Document, base: String, pageUrl: String): AnizmDetail {
        val rawTitle = firstText(
            doc,
            "h1.anizm_pageTitle",
            "h2.anizm_pageTitle",
            "h1.animeName",
            "h1.anime-title",
            "h1.title",
            "div.animeDetail-title h1",
            "h1"
        ) ?: meta(doc, "meta[property=og:title]", "meta[name=twitter:title]")
            ?: doc.selectFirst("title")?.text()

        val title = AnizmMatch.cleanTitle(rawTitle)
            ?: AnizmMatch.cleanTitle(titleFromSlug(pageUrl))
            ?: "Anizm"

        val poster = imageUrl(
            doc.selectFirst("div.infoPosterImg > img, div.infoPosterImg img, img.animePoster, .anizm_poster img")
                ?: doc.selectFirst("div.poster img, .poster-image img, img.img-fluid, article img")
        ) ?: meta(doc, "meta[itemprop=image]", "meta[property=og:image]", "meta[property=og:image:secure_url]")

        val plot = firstText(
            doc,
            "div.infoDesc",
            "div.anizm_conText",
            "div.animeDescription",
            "div.anime-description",
            "div.ozet",
            "p.text-muted"
        ) ?: meta(doc, "meta[itemprop=description]", "meta[property=og:description]", "meta[name=description]")

        val tags = LinkedHashSet<String>()
        doc.select(
            "span.dataValue span.ui.label, span.dataValue span.tag span.label, " +
                "a.ui.label[href*=/kategoriler/], a[href*=/temalar/], a.genre, div.tags a"
        ).forEach { element ->
            val text = element.text().trim()
            if (text.length in 2..40 && !isInvalidTitle(text)) tags.add(text)
        }

        val infoText = doc.body()?.text() ?: ""
        val year = yearFrom(firstText(doc, "span.dataTitle:contains(Yapım Yılı) + span"))
            ?: yearFrom(Regex("(?i)yap[ıi]m\\s*y[ıi]l[ıi]\\s*[:\\-]?\\s*(\\d{4})").find(infoText)?.value)
            ?: yearFrom(meta(doc, "meta[property=og:title]"))

        val scoreRaw = firstText(
            doc,
            "span.dataTitle:contains(Puan) + span",
            "span.dataValue:contains(Puan)",
            "div.star span",
            "span.malPoint"
        )
        val score = scoreRaw?.let { Regex("\\d{1,2}(?:[.,]\\d)?").find(it)?.value?.replace(',', '.')?.toDoubleOrNull() }
            ?.takeIf { it > 0.0 && it <= 10.0 }

        val studio = firstText(doc, "span.dataTitle:contains(Stüdyo) + span", "span.dataTitle:contains(Studyosu) + span")
        val typeText = firstText(doc, "span.dataTitle:contains(Tür) + span", "span.dataTitle:contains(Tip) + span")

        return AnizmDetail(
            title = title,
            poster = poster,
            plot = plot?.take(4000),
            tags = tags.toList(),
            year = year,
            score = score,
            studio = studio,
            typeText = typeText,
            episodes = parseEpisodes(doc, base, pageUrl)
        )
    }

    /**
     * Bölüm listesi. Sayfa hangi temada olursa olsun (v2 "episodeListTabContent",
     * v3 "animeDetail-items") tek kural uygulanır: bağlantının yolundan bölüm numarası
     * çıkarılabiliyorsa ve yol **bu animenin** slug'ıyla başlıyorsa bölümdür.
     */
    fun parseEpisodes(doc: Document, base: String, pageUrl: String): List<AnizmEpisode> {
        val targetSeries = seriesPathOf(pageUrl)
        val strict = mutableListOf<AnizmEpisode>()
        val loose = mutableListOf<AnizmEpisode>()
        val seen = HashSet<String>()

        for (anchor in doc.select("a[href]")) {
            val url = absolute(base, anchor.attr("href")) ?: continue
            if (!isSiteUrl(url)) continue
            val number = episodeNumberFromUrl(url) ?: continue
            if (!seen.add(url)) continue

            val label = anchor.text().trim()
            val name = label.takeIf { it.isNotBlank() && it.length <= 120 && AnizmMatch.cleanTitle(it) != null }
                ?: label.takeIf { it.isNotBlank() && it.length <= 120 }
                ?: "$number. Bölüm"
            val date = anchor.selectFirst("small, .date, .episode-date, time")?.text()?.trim()
            val poster = imageUrl(anchor.selectFirst("img"))

            val episode = AnizmEpisode(
                url = url,
                number = number,
                // "2. Bölüm" gibi bilgi taşımayan etiketler temizlenince boşalır;
                // o durumda ham etiket korunur (oynatıcıda "2. Bölüm" görünür).
                name = AnizmMatch.cleanTitle(name) ?: name,
                date = date,
                poster = poster
            )
            loose.add(episode)
            val series = seriesPathOf(url)
            if (targetSeries == null || series == null || series == targetSeries) strict.add(episode)
        }

        val chosen = if (strict.isNotEmpty()) strict else loose
        return chosen.distinctBy { it.url }.sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.url }))
    }
}
