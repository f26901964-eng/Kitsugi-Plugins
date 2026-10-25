package com.kraptor

/**
 * TrAnimeIzle — başlık eşleştirme çekirdeği (saf Kotlin; Android/jsoup bağımlılığı YOK).
 *
 * NEDEN VAR: Kitsugi kaynağı kendi başlığıyla arar (TMDB/AniList/MAL → çoğu zaman
 * İngilizce: "Attack on Titan", "The Apothecary Diaries Season 3"). TrAnimeIzle ise
 * Türkçe/romanize adlar ve sonekler kullanır ("Attack on Titan 4. Sezon 28. Bölüm İzle").
 * Site araması düz metin karşılaştırması yaptığı için:
 *
 *  1. [queryVariants] tek başlıktan siteye gönderilecek birden çok varyant üretir
 *     (sezon eki atılmış, ":" sonrası atılmış, aksan sadeleştirilmiş, gürültü temizlenmiş).
 *  2. [score]/[rank] dönen kartları Türkçe aksan + kelime öbeği + yazım farkı düzeyinde
 *     puanlar; uygulamanın kendi eşleştiricisi doğru kartı ilk sırada bulur.
 *
 * Fonksiyonlar yan etkisiz ve deterministiktir → `TrAnimeIzleMatchTest` ağ olmadan doğrular.
 */
internal object TrAnimeIzleMatch {

    /** Eşleştirmede anlam taşımayan gürültü (site ekleri + kalite etiketleri). */
    private val NOISE = setOf(
        "izle", "izleme", "watch", "full", "hd", "hdfull", "fullhd", "turkce", "altyazili",
        "dublaj", "dublajli", "subbed", "dubbed", "anime", "animesi", "bolum", "bolumler",
        "episode", "ep", "final", "tum", "sezon", "season", "ova", "ona", "oad", "movie",
        "film", "indir", "download", "online", "1080p", "720p", "480p", "360p", "4k", "hdrip",
        "bluray", "fragman", "teaser", "tranimeizle", "tr", "yeni", "turk"
    )

    private val YEAR_RE = Regex("\\b(?:19|20)\\d{2}\\b")

    /**
     * Sezon desenleri. `IGNORE_CASE` Türkçe noktalı `İ`'yi katlamaz; bu yüzden
     * "sezon"/"season" kelimeleri ASCII yazılır ve metin önce [fold] edilir.
     */
    private val SEASON_RES = listOf(
        Regex("\\b(\\d{1,2})\\s*(?:st|nd|rd|th)?\\s*\\.?\\s*(?:sezon|season)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:sezon|season)\\s*[:.\\-]?\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE),
        Regex("\\bs\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    )

    /** Bölüm desenleri — "12. Bölüm", "Bölüm 12", "Episode 12", "12 Bölüm". */
    private val EPISODE_RES = listOf(
        Regex("\\b(\\d{1,4})\\s*\\b(?:bolum|episode|ep)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:bolum|episode|ep)\\s*[:.\\-]?\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE)
    )

    private val MULTI_SPACE = Regex("\\s+")

    /** Dizi/bölüm URL deseni: `slug-12-bolum-izle`, `slug-bolum-12`, `slug-1-sezon-2-bolum`. */
    private val PAGINATION = Regex("-\\d{1,3}$")

    private val EPISODE_PATH = Regex(
        "^(.*?)-(\\d{1,4})\\s*-?\\s*bolum(?:-.*)?$",
        RegexOption.IGNORE_CASE
    )

    // ------------------------------------------------------------------
    // Normalizasyon
    // ------------------------------------------------------------------

    /** Türkçe/aksana duyarsız katlama: küçük harf + ASCII + harf/rakam dışı → boşluk. */
    fun fold(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (raw in input) {
            val mapped = when (raw) {
                'ı', 'İ', 'I', 'î', 'ì', 'í' -> 'i'
                'ş', 'Ş' -> 's'
                'ğ', 'Ğ' -> 'g'
                'ü', 'Ü', 'û', 'ù', 'ú' -> 'u'
                'ö', 'Ö', 'ó', 'ò' -> 'o'
                'ç', 'Ç' -> 'c'
                'â', 'à', 'á', 'ä', 'å' -> 'a'
                'é', 'è', 'ê', 'ë' -> 'e'
                'ñ' -> 'n'
                'ý', 'ÿ' -> 'y'
                'ß' -> 's'
                else -> raw
            }
            val lower = mapped.lowercaseChar()
            if (lower.isLetterOrDigit()) sb.append(lower) else sb.append(' ')
        }
        return MULTI_SPACE.replace(sb.toString(), " ").trim()
    }

    /** Eşleştirme biçimi: yıl + sezon + bölüm + gürültü atılmış. */
    fun stripForMatch(raw: String): String {
        var text = fold(raw)
        if (text.isEmpty()) return ""
        text = YEAR_RE.replace(text, " ")
        for (re in SEASON_RES) text = re.replace(text, " ")
        for (re in EPISODE_RES) text = re.replace(text, " ")
        return text.split(' ').filter { it.isNotEmpty() && it !in NOISE }.joinToString(" ").trim()
    }

    /** Sezon numarası ("3. Sezon", "Season 2", "4th Season", "S5"). */
    fun seasonOf(raw: String): Int? {
        val text = fold(raw)
        if (text.isEmpty()) return null
        for (re in SEASON_RES) {
            val value = re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (value in 1..99) return value
        }
        return null
    }

    /** Bölüm numarası ("12. Bölüm", "Bölüm 12", "Episode 12"). */
    fun episodeOf(raw: String): Int? {
        val text = fold(raw)
        if (text.isEmpty()) return null
        for (re in EPISODE_RES) {
            val value = re.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: continue
            if (value > 0) return value
        }
        return null
    }

    /** Bölüm URL'sinden numara ("one-piece-1120-bolum-izle" → 1120). */
    fun episodeFromUrl(url: String): Int? {
        val path = pathOf(url)
        val m = EPISODE_PATH.find(path) ?: return null
        return m.groupValues.getOrNull(2)?.toIntOrNull()
    }

    /** Bölüm URL'sini seri URL'sine indirger; bölüm değilse null. */
    fun seriesUrlOf(url: String): String? {
        val path = pathOf(url)
        val m = EPISODE_PATH.find(path) ?: return null
        val prefix = if (url.contains("://")) url.substringBefore("://") + "://" +
            url.substringAfter("://").substringBefore("/") else ""
        val series = m.groupValues[1].trim('-')
        if (series.isEmpty()) return null
        return if (prefix.isEmpty()) "/$series" else "$prefix/$series"
    }

    /**
     * Karşılaştırma anahtarı: bölüm eki atılmış, "izle"/"-hd" sonekleri temizlenmiş yol.
     * Kategori/liste sayfalarında da çalışır ("/animeizle/aksiyon-anime-izle-2").
     */
    fun seriesKey(url: String): String {
        var path = pathOf(url).trim('/').lowercase()
        // Kategori/liste sayfaları: /animeizle/{tur}-anime-izle-2
        for (prefix in listOf("animeizle/", "anime/", "izle/")) {
            if (path.startsWith(prefix)) path = path.removePrefix(prefix)
        }
        // Sayfalama son eki ("-2") — bölüm ekiyle karışmasın diye ÖNCE atılır.
        path = PAGINATION.replace(path, "")
        EPISODE_PATH.find(path)?.let { path = it.groupValues[1] }
        for (suffix in listOf("-anime-izle", "-izle-hd", "-izle")) {
            if (path.endsWith(suffix)) {
                path = path.removeSuffix(suffix)
                break
            }
        }
        EPISODE_PATH.find(path)?.let { path = it.groupValues[1] }
        return path.trim('-')
    }

    private fun pathOf(url: String): String {
        val noScheme = if (url.contains("://")) url.substringAfter("://").substringAfter("/", "") else url
        return noScheme.substringBefore("?").substringBefore("#").trim('/')
    }

    /** Slug'dan okunabilir başlık (son çare). */
    fun titleFromSlug(url: String): String {
        val slug = pathOf(url).substringAfterLast('/')
            .removeSuffix(".html")
            .replace(EPISODE_PATH, "$1")
            .removeSuffix("-izle-hd")
            .removeSuffix("-izle")
        return slug.split('-', '_')
            .filter { it.isNotBlank() && it !in setOf("animeizle") }
            .joinToString(" ") { word -> word.replaceFirstChar { it.uppercaseChar() } }
    }

    // ------------------------------------------------------------------
    // Benzerlik
    // ------------------------------------------------------------------

    private fun tokens(value: String): Set<String> =
        value.split(' ').filter { it.isNotEmpty() }.toSet()

    /** Kısa tokenlarda yazım hatası toleransı (≤1 harf; uzun tokenlarda ≤2). */
    private fun editDistanceAtMost(a: String, b: String, max: Int): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > max) return false
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
                if (current[j] < rowMin) rowMin = current[j]
            }
            if (rowMin > max) return false
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length] <= max
    }

    private fun tokenMatches(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 5 || b.length < 5) return false
        val max = if (maxOf(a.length, b.length) >= 8) 2 else 1
        return editDistanceAtMost(a, b, max)
    }

    /**
     * İki başlık arasında 0.0–1.0 benzerlik.
     *
     *  - Tam eşleşme → 1.0
     *  - Biri diğerini içeriyor → uzunluk oranıyla ölçeklenir (kısa ad, uzun adı
     *    "yutar" ama tam eşleşme kadar güvenilir değildir)
     *  - Aksi halde token kesişimi (birebir + yazım toleranslı) Jaccard'a yakın
     */
    fun score(a: String, b: String): Double {
        val left = stripForMatch(a)
        val right = stripForMatch(b)
        if (left.isEmpty() || right.isEmpty()) return 0.0
        if (left == right) return 1.0
        if (left.contains(right) || right.contains(left)) {
            val small = minOf(left.length, right.length).toDouble()
            val large = maxOf(left.length, right.length).toDouble()
            return 0.55 + 0.40 * (small / large)
        }
        val aTokens = tokens(left)
        val bTokens = tokens(right)
        var matched = 0
        for (t in aTokens) {
            if (bTokens.any { tokenMatches(t, it) }) matched++
        }
        val union = aTokens.size + bTokens.size - matched
        if (union <= 0) return 0.0
        return (matched.toDouble() / union) * 0.95
    }

    /** Aramada gönderilecek varyantlar (en umut verici önce). */
    fun queryVariants(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return emptyList()
        val out = LinkedHashSet<String>()

        fun add(value: String?) {
            val v = value?.trim() ?: return
            if (v.length >= 2 && out.none { it.equals(v, ignoreCase = true) }) out.add(v)
        }

        add(trimmed)
        if (trimmed.contains(':')) add(trimmed.substringBefore(':'))
        // "One Piece 3. Sezon" → "One Piece"
        if (seasonOf(trimmed) != null) {
            var without = trimmed
            for (re in SEASON_RES) without = re.replace(without, " ")
            add(MULTI_SPACE.replace(without, " ").trim())
        }
        // Aksan sadeleştirme — site HTML'i ASCII aramada da eşleşir.
        val folded = fold(trimmed)
        if (folded.length >= 3 && !folded.equals(trimmed, ignoreCase = true)) add(folded)
        // Gürültü atılmış ad
        val stripped = stripForMatch(trimmed)
        if (stripped.length >= 3) add(stripped)
        return out.take(5)
    }

    /** Kart adındaki site soneklerini temizler ("… İzle", " - TrAnimeIzle", "Final"). */
    fun cleanTitle(raw: String?): String? {
        var title = raw?.trim() ?: return null
        if (title.isEmpty()) return null
        title = title.replace(Regex("(?i)\\s*-\\s*tranimeizle(?:\\.(?:io|live|top))?\\s*$"), "").trim()
        title = title.replace(Regex("\\s*-?\\s*[İiIı]zle(?:\\s*-?\\s*[Hh][Dd])?\\s*$"), "").trim()
        title = title.replace(
            Regex("(?i)\\s*\\(?(?:t[üu]rk[çc]e\\s+)?(?:altyaz[ıi]l[ıi]|dublajl[ıi])\\)?\\s*$"), ""
        ).trim()
        title = title.replace(Regex("(?i)\\s+final\\s*$"), "").trim()
        title = title.replace(Regex("(?i)\\s*\\b\\d{1,4}\\s*\\.\\s*b[öo]l[üu]m\\b.*$"), "").trim()
        title = title.replace(Regex("(?i)\\s*\\bb[öo]l[üu]m\\s*\\d{1,4}\\b.*$"), "").trim()
        return title.ifBlank { null }
    }

    /**
     * Metin gerçek bir içerik adı mı? (menü/gezinme etiketleri ve salt sayılar elenir)
     * Tek kelimelik gerçek adlar ("Bleach", "Monster") GEÇERLİDİR.
     */
    fun isInvalidTitle(raw: String?): Boolean {
        val title = raw?.trim() ?: return true
        if (title.length < 2) return true
        val key = fold(title)
        if (key.isEmpty()) return true
        if (key in BLOCKED_TITLES) return true
        if (key.matches(Regex("^\\d{1,4}$"))) return true
        if (key.matches(Regex("^\\d{1,4} bolum$"))) return true
        return false
    }

    private val BLOCKED_TITLES = setOf(
        "izle", "bolum", "giris", "giris yap", "kayit ol", "kategoriler", "profil", "hesabim",
        "anasayfa", "devamini oku", "hepsini izle", "ilk bolumu izle", "tum bolumler", "fragman",
        "menu", "ara", "arama", "son eklenenler", "yeni bolumler", "populer animeler",
        "anime listesi", "manga", "takvim", "discord", "cikis yap", "ayarlar", "bildirimler",
        "mesajlar", "paylas", "rapor ver", "oy ver", "devam et", "tumunu gor", "daha fazla"
    )

    /**
     * Kartları sorguya göre puanlayıp sıralar. `alternatives` verilirse (kart üzerindeki
     * alternatif adlar / İngilizce ad) en iyi skor kullanılır.
     *
     * Dönen liste: eşik üstü kartlar (skor azalan) + hiçbiri eşiği geçmediyse
     * en iyi 3 kart (site zaten sorguya göre döndürdüğü için tamamen boş bırakmak
     * "0 sonuç" demek olurdu).
     */
    fun rank(
        query: String,
        candidates: List<Triple<String, String, List<String>>>,
        threshold: Double = 0.45
    ): List<Pair<String, Double>> {
        val scored = candidates.map { (name, url, alternatives) ->
            val best = (listOf(name) + alternatives).maxOfOrNull { score(query, it) } ?: 0.0
            url to best
        }
        val above = scored.filter { it.second >= threshold }.sortedByDescending { it.second }
        if (above.isNotEmpty()) return above
        return scored.sortedByDescending { it.second }.take(3)
    }
}
