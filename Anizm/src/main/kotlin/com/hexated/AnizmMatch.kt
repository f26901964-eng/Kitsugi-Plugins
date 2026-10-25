package com.hexated

/**
 * Anizm — başlık eşleştirme çekirdeği (saf Kotlin, Android/jsoup bağımlılığı YOK).
 *
 * NEDEN VAR: Kitsugi, kaynakları ararken başlığı **kendi** kaynağından (TMDB/AniList/MAL)
 * üretir. Bu başlıklar çoğu zaman İngilizce veya "the apothecary diaries season 3" gibi
 * nitelikli olur; Anizm ise Türkçe/romanize adlar kullanır ("The Apothecary Diaries 3. Sezon").
 * Site araması ham metinle çalıştığı için bu fark doğrudan 0 sonuç demekti.
 *
 * Çözüm iki katmanlı:
 *  1. [queryVariants] — tek bir başlıktan siteye gönderilecek birden fazla varyant üretir
 *     (sezon eki atılmış, iki nokta sonrası atılmış, aksanları sadeleştirilmiş...).
 *  2. [score]/[rank] — dönen kartları hem Türkçe aksan hem kelime öbeği düzeyinde
 *     puanlayıp sıralar. Böylece uygulamanın kendi başlık eşleştiricisi doğru kartı
 *     listenin başında bulur.
 *
 * Fonksiyonlar bilinçli olarak yan etkisiz ve deterministiktir: `AnizmMatchTest` bunları
 * cihaz/derleme olmadan doğrular.
 */
internal object AnizmMatch {

    /** Eşleştirmede anlam taşımayan site/içerik gürültüsü. */
    private val NOISE = setOf(
        "izle", "izleme", "watch", "full", "hd", "hdfull", "fullhd", "turkce", "altyazili",
        "dublaj", "dublajli", "subbed", "dubbed", "anime", "animesi", "bolum", "episode", "ep",
        "final", "tum", "bolumler", "sezon", "season", "ova", "ona", "oad", "movie", "film",
        "indir", "download", "online", "1080p", "720p", "480p", "360p", "4k", "hdrip", "bluray",
        "fragman", "teaser", "turk", "ceviri", "altyazi", "anizm", "puffytr", "tr"
    )

    private val YEAR_RE = Regex("\\b(?:19|20)\\d{2}\\b")

    /**
     * Not: desenler hem ham ("Season 3") hem katlanmış ("sezon 3") metinde çalışmalı,
     * bu yüzden hepsi IGNORE_CASE ile derlenir.
     */
    private val SEASON_RES = listOf(
        Regex("\\b(\\d{1,2})\\s*(?:st|nd|rd|th)?\\s*\\.?\\s*(?:sezon|season)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:sezon|season)\\s*[:.\\-]?\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:sezon|season)\\s+(?:final|son)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bs\\s*(\\d{1,2})\\b", RegexOption.IGNORE_CASE)
    )

    private val EPISODE_RES = listOf(
        Regex("\\b(\\d{1,4})\\s*\\.\\s*b[öo]l[üu]m\\b", RegexOption.IGNORE_CASE),
        Regex("\\bb[öo]l[üu]m\\s*[:.\\-]?\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(?:episode|ep|bolum)\\s*[:.\\-]?\\s*(\\d{1,4})\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(\\d{1,4})\\s*b[öo]l[üu]m\\b", RegexOption.IGNORE_CASE)
    )

    private val MULTI_SPACE = Regex("\\s+")

    // ------------------------------------------------------------------
    // Normalizasyon
    // ------------------------------------------------------------------

    /**
     * Türkçe/aksana duyarsız katlama: küçük harfe indirir, Türkçe karakterleri
     * ASCII karşılığına çevirir, harf/rakam dışındaki her şeyi boşluğa çevirir.
     * ("İstanbul'da 3. Sezon" → "istanbul da 3 sezon")
     */
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

    /** Eşleştirme için gürültüden arındırılmış biçim (sezon/bölüm ekleri dahil). */
    fun stripForMatch(raw: String): String {
        var text = fold(raw)
        if (text.isEmpty()) return ""
        text = YEAR_RE.replace(text, " ")
        for (re in SEASON_RES) text = re.replace(text, " ")
        for (re in EPISODE_RES) text = re.replace(text, " ")
        val kept = text.split(' ').filter { it.isNotEmpty() && it !in NOISE }
        return kept.joinToString(" ").trim()
    }

    // ------------------------------------------------------------------
    // Sezon / bölüm çıkarımı
    // ------------------------------------------------------------------

    /** Başlıktan sezon numarası ("3. Sezon", "Season 2", "4th Season", "S5"). */
    fun seasonOf(raw: String): Int? {
        val text = fold(raw)
        if (text.isEmpty()) return null
        for (re in SEASON_RES) {
            val m = re.find(text) ?: continue
            val value = m.groupValues.getOrNull(1)?.toIntOrNull()
            if (value != null && value in 1..99) return value
        }
        return null
    }

    /** Metinden bölüm numarası ("12. Bölüm", "Bölüm 12", "Episode 12", "Ep 12"). */
    fun episodeOf(raw: String): Int? {
        val text = fold(raw)
        if (text.isEmpty()) return null
        for (re in EPISODE_RES) {
            val m = re.find(text) ?: continue
            val value = m.groupValues.getOrNull(1)?.toIntOrNull()
            if (value != null && value > 0) return value
        }
        return null
    }

    // ------------------------------------------------------------------
    // Benzerlik
    // ------------------------------------------------------------------

    private fun tokens(value: String): Set<String> =
        value.split(' ').filter { it.isNotEmpty() && it.length > 1 }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var common = 0
        for (token in a) if (b.contains(token)) common++
        val union = a.size + b.size - common
        return if (union <= 0) 0.0 else common.toDouble() / union
    }

    /** 0..1 arası düzenleme benzerliği (kısa metinlerde kararlı). */
    fun editRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val x = if (a.length > 120) a.substring(0, 120) else a
        val y = if (b.length > 120) b.substring(0, 120) else b
        val previous = IntArray(y.length + 1) { it }
        val current = IntArray(y.length + 1)
        for (i in 1..x.length) {
            current[0] = i
            for (j in 1..y.length) {
                val cost = if (x[i - 1] == y[j - 1]) 0 else 1
                val deletion = previous[j] + 1
                val insertion = current[j - 1] + 1
                val substitution = previous[j - 1] + cost
                current[j] = minOf(deletion, insertion, substitution)
            }
            System.arraycopy(current, 0, previous, 0, current.size)
        }
        val distance = previous[y.length]
        return 1.0 - distance.toDouble() / maxOf(x.length, y.length)
    }

    /** İki başlığın benzerliği (0..1). Gürültü ve sezon/bölüm ekleri yok sayılır. */
    fun score(a: String, b: String): Double {
        val x = stripForMatch(a)
        val y = stripForMatch(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val xc = " $x "
        val yc = " $y "
        if (xc.contains(yc) || yc.contains(xc)) {
            val shorter = minOf(x.length, y.length).toDouble()
            val longer = maxOf(x.length, y.length).toDouble()
            return 0.80 + 0.20 * (shorter / longer)
        }
        val tx = tokens(x)
        val ty = tokens(y)
        val common = tx.count { ty.contains(it) }
        if (common > 0 && (common == tx.size || common == ty.size)) {
            // Kısa başlık uzun başlığın içinde geçiyor ("naruto" ⊂ "naruto shippuuden").
            // Uzunluk oranıyla ölçeklenir; aksi hâlde 1 kelimelik sorgu, içinde o kelime
            // geçen HER başlığa aynı puanı verirdi.
            val small = minOf(tx.size, ty.size).toDouble()
            val large = maxOf(tx.size, ty.size).toDouble()
            return 0.55 + 0.40 * (small / large)
        }
        val jac = jaccard(tx, ty)
        val ratio = editRatio(x, y)
        return maxOf(jac * 0.95, ratio * 0.90)
    }

    /** Bir sonucun, verilen sorgu varyantlarına göre en iyi puanı. */
    fun bestScore(names: List<String>, queries: List<String>): Double {
        var best = 0.0
        for (query in queries) {
            for (name in names) {
                val value = score(query, name)
                if (value > best) best = value
                if (best >= 1.0) return 1.0
            }
        }
        return best
    }

    /**
     * Sonuçları puanlayıp sıralar. `nameOf` bir öğenin karşılaştırılacak tüm adlarını
     * (ana ad + alternatif adlar + Japonca ad) döndürebilir.
     */
    fun <T> rank(
        items: List<T>,
        queries: List<String>,
        nameOf: (T) -> List<String>,
        limit: Int = 40
    ): List<T> {
        if (items.isEmpty() || queries.isEmpty()) return items.take(limit)
        val scored = items.map { item -> item to bestScore(nameOf(item), queries) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
        val ranked = if (scored.isEmpty()) items.take(limit) else scored.map { it.first }
        return ranked.take(limit)
    }

    // ------------------------------------------------------------------
    // Sorgu varyantları
    // ------------------------------------------------------------------

    /**
     * Tek başlıktan siteye gönderilebilecek varyantlar (en umut verici önce).
     * Varyant sayısı bilinçli olarak 4 ile sınırlı: her varyant bir ağ isteği demek
     * ve uygulama aramaya sınırlı süre veriyor.
     */
    fun queryVariants(raw: String): List<String> {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return emptyList()
        val out = LinkedHashSet<String>()

        fun add(value: String?) {
            val v = value?.trim() ?: return
            if (v.length >= 2 && out.none { it.equals(v, ignoreCase = true) }) out.add(v)
        }

        add(trimmed)
        // "Naruto Shippuden: The Movie" → "Naruto Shippuden"
        if (trimmed.contains(':')) add(trimmed.substringBefore(':'))

        // Sezon eki atılmış: "The Apothecary Diaries Season 3" → "The Apothecary Diaries"
        val season = seasonOf(trimmed)
        if (season != null) {
            var without = trimmed
            for (re in SEASON_RES) without = re.replace(without, " ")
            add(MULTI_SPACE.replace(without, " ").trim())
        }

        // Aksan sadeleştirme: "Türkçe Altyazılı" → "turkce altyazili" (site HTML'i ASCII'de
        // de arama yapabildiği için yedek olarak değerli).
        val folded = MULTI_SPACE.replace(
            fold(trimmed).split(' ').filter { it.isNotEmpty() }.joinToString(" "),
            " "
        )
        if (folded.length >= 3 && !folded.equals(trimmed, ignoreCase = true)) add(folded)

        // Gürültü atılmış ad ("... İzle", "... Full HD")
        val stripped = stripForMatch(trimmed)
        if (stripped.length >= 3) add(stripped)

        return out.take(4)
    }

    /** Kart adındaki tipik site eklerini temizler (" İzle", " - Anizm.TV", "Final"). */
    fun cleanTitle(raw: String?): String? {
        var title = raw?.trim() ?: return null
        if (title.isEmpty()) return null
        title = title.replace(Regex("(?i)\\s*-\\s*anizm(?:\\.tv)?\\s*$"), "").trim()
        title = title.replace(Regex("(?i)\\s*-\\s*puffytr\\s*$"), "").trim()
        // Türkçe noktalı İ, IGNORE_CASE ile eşleşmediği için karakter sınıfı açıkça yazılır.
        title = title.replace(Regex("\\s*-?\\s*[İiIı]zle\\s*$"), "").trim()
        title = title.replace(Regex("(?i)\\s*\\(?(?:t[üu]rk[çc]e\\s+)?(?:altyaz[ıi]l[ıi]|dublajl[ıi])\\)?\\s*$"), "").trim()
        title = title.replace(Regex("(?i)\\s+final\\s*$"), "").trim()
        // Bölüm eki: "12. Bölüm", "Bölüm 12", "12. Bölüm Final"
        title = title.replace(Regex("(?i)\\s*\\b\\d{1,4}\\s*\\.\\s*b[öo]l[üu]m\\b.*$"), "").trim()
        title = title.replace(Regex("(?i)\\s*\\bb[öo]l[üu]m\\s*\\d{1,4}\\b.*$"), "").trim()
        return title.ifBlank { null }
    }

    /** Sadece harf/rakam/tire içeren yol parçası mı? (slug doğrulaması) */
    fun isSlug(value: String): Boolean = value.isNotEmpty() && value.all { ch ->
        ch in 'a'..'z' || ch in '0'..'9' || ch == '-'
    }

    /** "naruto" + "-bolum" gibi eklerden arındırılmış seri yolu. */
    fun seriesSlugOf(pathOrUrl: String): String? {
        val path = pathOrUrl.substringAfter("://", pathOrUrl)
            .substringAfter("/", "")
            .substringBefore("?")
            .substringBefore("#")
            .trim('/')
            .lowercase()
        if (path.isEmpty()) return null
        val stripped = EPISODE_PATH.replace(path, "$1").trim('/')
        val finalSlug = stripped.removeSuffix("-izle")
        return finalSlug.ifBlank { null }
    }

    private val EPISODE_PATH = Regex("^(.*?)-(\\d{1,4})-bolum(?:-[a-z0-9]+)*$", RegexOption.IGNORE_CASE)
}
