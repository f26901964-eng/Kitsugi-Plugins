// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Dizipal'in **ölçülmüş** Next.js/RSC sözleşmesini ayrıştıran saf yardımcılar.
 *
 * Burada Android/CloudStream bağımlılığı yok: aynı fonksiyonlar JVM birim
 * testlerinde (`src/test`) doğrudan çalıştırılır. Ölçüm kayıtları:
 * `tools/dizipal_dump.py`, `tools/dizipal_player_probe.py`.
 */
object DiziPalParser {

    // Ölçüm tarihi: 03.11.2026 (dizipal_dump + dizipal_player_probe)

    private const val PBKDF2_ITERATIONS = 999
    private const val PBKDF2_LENGTH     = 32

    // ── RSC kaçış çözme ──────────────────────────────────────────────────────

    /** `self.__next_f` akışındaki `\"`, `\\/`, `\n` kaçışlarını çözer. */
    fun unescapeRsc(html: String): String {
        val out = StringBuilder(html.length)
        var index = 0

        while (index < html.length) {
            val char = html[index]

            if (char != '\\' || index == html.length - 1) {
                out.append(char)
                index++
                continue
            }

            when (val next = html[index + 1]) {
                '"', '/', '\\' -> {
                    out.append(next)
                    index += 2
                }
                'n' -> {
                    out.append('\n')
                    index += 2
                }
                'r' -> {
                    out.append('\r')
                    index += 2
                }
                't' -> {
                    out.append('\t')
                    index += 2
                }
                'u' -> {
                    val hex = html.substring(index + 2, minOf(html.length, index + 6))
                    if (hex.length == 4 && hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                        out.append(hex.toInt(16).toChar())
                        index += 6
                    } else {
                        out.append(char)
                        index++
                    }
                }
                else -> {
                    out.append(char)
                    index++
                }
            }
        }

        return out.toString()
    }

    // ── Oynatıcı ─────────────────────────────────────────────────────────────

    /** Bölüm/film sayfasındaki `"player":{…}` nesnesini çıkarır. */
    fun parsePlayer(html: String): DiziPalPlayer? {
        val plain = unescapeRsc(html)
        var index = 0
        var first: DiziPalPlayer? = null

        while (true) {
            val key = plain.indexOf("\"player\":{", index)
            if (key < 0) break

            val parsed = parsePlayerAt(plain, key + "\"player\":".length)
            if (parsed != null) {
                // * Şifreli gövde taşıyan nesne gerçek oynatıcıdır; ilkini de yedek tut.
                if (parsed.ciphertext != null && parsed.key != null) return parsed
                if (first == null) first = parsed
            }

            index = key + 1
        }

        return first
    }

    private fun parsePlayerAt(plain: String, start: Int): DiziPalPlayer? {
        val block = jsonObjectAt(plain, start) ?: return null

        val sourcesBlock = Regex("\"sources\":\\[").find(block)?.let { jsonArrayAt(block, it.range.last) } ?: "[]"
        val sources = Regex("\\{[^{}]*}").findAll(sourcesBlock).map { match ->
            val source = match.value
            DiziPalSource(
                label   = stringField(source, "label"),
                quality = stringField(source, "quality"),
                href    = stringField(source, "href"),
                dub     = Regex("\"dub\":(true|false)").find(source)?.groupValues?.get(1)?.toBooleanStrictOrNull(),
            )
        }.toList()

        val enc = Regex("\"enc\":\\{").find(block)?.let { jsonObjectAt(block, it.range.last) } ?: ""

        return DiziPalPlayer(
            kind       = stringField(block, "kind"),
            contentId  = Regex("\"contentId\":(\\d+)").find(block)?.groupValues?.get(1)?.toLongOrNull(),
            title      = stringField(block, "title"),
            language   = stringField(block, "language"),
            sources    = sources,
            ciphertext = stringField(enc, "ciphertext"),
            iv         = stringField(enc, "iv"),
            salt       = stringField(enc, "salt"),
            key        = stringField(block, "k"),
            // ! `dub` alanı `"enc"` nesnesinden ÖNCE geliyor; `stringField` gövdenin
            // ! tamamında aradığı için burada ayrıca okunuyor.
            dub        = Regex("\"dub\":(true|false)").find(
                block.substringBefore("\"enc\":{"),
            )?.groupValues?.get(1)?.toBooleanStrictOrNull(),
        )
    }

    // ── Arama sonuçları (`results.dizi / film / oyuncu`) ─────────────────────

    /**
     * Arama sayfasının RSC yükündeki `results` nesnesini ayrıştırır.
     *
     * Ölçüm (2026-11-04, `search-the-mentalist.html`): arama sayfası kartları
     * `{"id":"dizi-8797","kind":"dizi","t":"The Mentalist","year":2008,"g":"Suç",
     *   "img":…,"r":"8.2","meta":"2008 · Suç","sub":"Dizi · 2008 · Suç",
     *   "href":"/series/the-mentalist"}` biçiminde ve **`t` her zaman `href`'ten
     * ÖNCE değil** — bu yüzden `parseCards()` kalıbı bu sayfada 0 eşleşme veriyordu.
     *
     * `dizi`, `film` ve `oyuncu` ayrı dizilerdir; üçü de döndürülür.
     */
    fun parseSearchResults(html: String): List<DiziPalCard> {
        val plain = unescapeRsc(html)
        val key   = plain.indexOf("\"results\":{")
        if (key < 0) return emptyList()

        val block = jsonObjectAt(plain, key + "\"results\":".length) ?: return emptyList()
        val cards = mutableListOf<DiziPalCard>()

        for (bucket in listOf("dizi", "film", "oyuncu")) {
            val bucketKey = "\"$bucket\":["
            var index     = 0

            while (true) {
                val found = block.indexOf(bucketKey, index)
                if (found < 0) break

                val array = jsonArrayAt(block, found + bucketKey.length - 1)
                if (array == null) {
                    index = found + 1
                    continue
                }

                Regex("\\{[^{}]*}").findAll(array).forEach { match ->
                    val row  = match.value
                    val href = stringField(row, "href") ?: return@forEach
                    if (!href.contains("/series/") && !href.contains("/movies/")) return@forEach

                    val title = stringField(row, "t") ?: stringField(row, "name") ?: return@forEach

                    cards.add(
                        DiziPalCard(
                            title    = title,
                            href     = href,
                            poster   = stringField(row, "img") ?: stringField(row, "thumb"),
                            year     = Regex("\"year\":(\\d{4})").find(row)?.groupValues?.get(1)?.toIntOrNull()
                                ?: Regex("(19|20)\\d{2}").find(stringField(row, "meta").orEmpty())
                                    ?.value?.toIntOrNull(),
                            meta     = stringField(row, "meta"),
                            isMovie  = href.contains("/movies/"),
                            kind     = stringField(row, "kind") ?: bucket,
                            score    = stringField(row, "r"),
                            genre    = stringField(row, "g"),
                            subtitle = stringField(row, "sub"),
                        ),
                    )
                }

                index = found + bucketKey.length
            }
        }

        return cards.distinctBy { it.href }
    }

    // ── Kaynak etiketi / dil çıkarımı ─────────────────────────────────────────

    /**
     * Kaynağın görünen adı. Altyazı/dublaj bilgisi **en başta** ve yapısal olarak
     * yer alır: `Dublaj · 1080P`, `Altyazı · 1080P`, `Altyazı · Dizipal`.
     *
     * Ölçüm: `sources[].dub` alanı gerçek (dump'ta 2 kaynak: biri `dub:false`,
     * biri `dub:true`); `label` alanı da "PUB+ · Türkçe Dublaj" biçiminde.
     */
    fun sourceLabel(source: DiziPalSource, playerLanguage: String?): String {
        val kind = when {
            source.dub == true                                   -> "Dublaj"
            source.dub == false                                  -> "Altyazı"
            languageHint(source.href, source.label, playerLanguage) == "dublaj" -> "Dublaj"
            languageHint(source.href, source.label, playerLanguage) == "altyazi" -> "Altyazı"
            else                                                 -> null
        }

        // ? Etiketteki ham "Türkçe Dublaj" / "Türkçe Altyazı" ifadesi tekrarlanmasın.
        val cleanLabel = source.label
            ?.replace(Regex("""(?i)\s*[·|\-]?\s*türkçe\s*(dublaj|altyazı|altyazi)\s*"""), " ")
            ?.replace(Regex("""\s+"""), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val quality = source.quality?.trim()?.takeIf { it.isNotBlank() }

        return listOfNotNull(kind, quality ?: cleanLabel ?: playerLanguage?.trim()?.takeIf { it.isNotBlank() })
            .joinToString(" · ")
            .ifBlank { "Dizipal" }
    }

    /** `?lang=dublaj` / `?lang=altyazi`, etiket metni ve oynatıcı dili → "dublaj"/"altyazi"/null. */
    fun languageHint(href: String?, label: String?, playerLanguage: String?): String? {
        val hrefLang = href?.substringAfter("lang=", "")?.substringBefore('&')?.trim()?.lowercase()
        if (hrefLang == "dublaj" || hrefLang == "altyazi") return hrefLang

        val haystack = listOfNotNull(label, playerLanguage).joinToString(" ").lowercase()
        return when {
            haystack.contains("dublaj") -> "dublaj"
            haystack.contains("altyaz") -> "altyazi"
            else                        -> null
        }
    }

    // ── Sayfalama (pageHref yoksa) ────────────────────────────────────────────

    /**
     * `pageHref` dizisi artık hiçbir sayfada yok (4 dump'ta 0 eşleşme).
     *
     * ! ESKİ SÜRÜM YANLIŞ POZİTİF VERİYORDU: işaret listesinde çıplak "Sonraki" /
     * ! "Daha fazla" metni vardı. Oysa bu kelimeler **duyuru bandında** geçiyor:
     * !   "Sonraki adresimiz dizipal1595.com olacaktır"
     * ! Ölçüm (4 dump): `Sonraki` 2-3 kez var, `sayfa=`/`page=`/`rel="next"` **0**.
     * ! Sonuç: hasNextPage her sayfada `true` dönüyordu → sonsuz sayfalama.
     * ! Artık yalnız gerçek sayfalama sinyalleri aranır, metin işaretleri kaldırıldı.
     */
    fun hasNextPage(html: String, nextPage: Int): Boolean {
        if (nextPage < 2) return false

        // * Yalnız yapısal (URL/rel) sinyaller — çıplak metin yok.
        val markers = listOf(
            "sayfa=$nextPage", "page=$nextPage", "?sayfa=$nextPage", "&sayfa=$nextPage",
            "rel=\"next\"", "rel='next'",
        )
        if (markers.any { html.contains(it) }) return true

        // RSC içinde `"page":N` / `"currentPage":N` sayaçları da kabul edilir.
        val plain = unescapeRsc(html)
        return Regex("\"(?:page|currentPage|pageNo)\":\\s*$nextPage\\b").containsMatchIn(plain) ||
            Regex("\"hasMore\":true").containsMatchIn(plain)
    }

    /**
     * Sitenin `2vttktfk_mjfx.js` içindeki çözmesiyle **birebir** aynı:
     * PBKDF2-HMAC-SHA512(k, salt, 999, 32) → AES-256-CBC(iv) → oynatıcı adresi.
     */
    fun decryptPlayer(player: DiziPalPlayer): String? {
        val ciphertext = player.ciphertext ?: return null
        val iv         = player.iv?.hexToBytes() ?: return null
        val salt       = player.salt?.hexToBytes() ?: return null
        val password   = player.key ?: return null

        return try {
            val key       = pbkdf2Sha512(password.toByteArray(Charsets.UTF_8), salt, PBKDF2_ITERATIONS, PBKDF2_LENGTH)
            val encrypted = base64Decode(ciphertext) ?: return null
            val cipher    = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            val plain = String(cipher.doFinal(encrypted), Charsets.UTF_8).trim()

            if (plain.length < 2048 && Regex("^(https?:)?//[^\\s\"'<>\\\\]+$").matches(plain)) plain else null
        } catch (error: Exception) {
            null
        }
    }

    // ── Dizi detayı ──────────────────────────────────────────────────────────

    /** `"seasons":[{…"episodes":[…]}]` → bölüm listesi. */
    fun parseSeasons(html: String): List<DiziPalEpisodeRef> {
        val plain = unescapeRsc(html)
        val key   = plain.indexOf("\"seasons\":[")
        if (key < 0) return emptyList()

        val seasonsBlock = jsonArrayAt(plain, key + "\"seasons\":".length) ?: return emptyList()
        val episodes     = mutableListOf<DiziPalEpisodeRef>()

        val seasonHeads = Regex("\"no\":(\\d+),\"label\":\"[^\"]*\"[^{]*?\"episodes\":").findAll(seasonsBlock)
        for (head in seasonHeads) {
            val seasonNo = head.groupValues[1].toIntOrNull() ?: continue
            val tail     = seasonsBlock.substring(head.range.last + 1)
            val array    = jsonArrayAt(tail, 0) ?: continue

            Regex("\\{[^{}]*}").findAll(array).forEach { match ->
                val row   = match.value
                val href  = stringField(row, "href") ?: return@forEach
                if (!href.contains("/bolum/") && !href.contains("/film")) return@forEach

                episodes.add(
                    DiziPalEpisodeRef(
                        title    = stringField(row, "title") ?: href.substringAfterLast("/"),
                        href     = href,
                        season   = seasonNo,
                        episode  = Regex("\"n\":(\\d+)").find(row)?.groupValues?.get(1)?.toIntOrNull(),
                        duration = Regex("\"min\":(\\d+)").find(row)?.groupValues?.get(1)?.toIntOrNull(),
                    )
                )
            }
        }

        return episodes.distinctBy { it.href }
    }

    /** Detay sayfasının RSC `slots` alanından başlık/yıl/konu/türler. */
    fun parseSlots(html: String): Map<String, String> {
        val plain = unescapeRsc(html)
        val slots = linkedMapOf<String, String>()

        for (field in listOf("mjTitle", "mjYear", "mjOverview", "mjPoster", "mjCreature", "mjPlatform", "mjLanguage", "mjGenreText")) {
            valueOf(plain, field)?.let { slots[field] = it }
        }
        Regex("\"title\":\"((?:[^\"\\\\]|\\\\.)*)\",\"altTitle\"").find(plain)
            ?.let { slots["title"] = it.groupValues[1].replace("\\\"", "\"") }

        return slots
    }

    /** `"mjGenres":[{"label":"Komedi","href":"/kategori/komedi"}]` → tür adları. */
    fun parseGenres(html: String): List<String> {
        val plain = unescapeRsc(html)
        val block = Regex("\"mjGenres\":\\[").find(plain)?.let { jsonArrayAt(plain, it.range.last) } ?: return emptyList()

        return Regex("\"label\":\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    // ── Listeler ─────────────────────────────────────────────────────────────

    /** `"pageHref":["/trendler","/trendler?sayfa=2"]` → sayfa adresleri. */
    fun pageHrefs(html: String): List<String> {
        val plain = unescapeRsc(html)
        val block = Regex("\"pageHref\":\\[").find(plain)?.let { jsonArrayAt(plain, it.range.last) } ?: return emptyList()

        return Regex("\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList()
    }

    /** Kart akışı: `{"t":"Nocturne","href":"/series/nocturne","img":"…"}`. */
    fun parseCards(html: String): List<DiziPalCard> {
        val plain = unescapeRsc(html)
        val cards = mutableListOf<DiziPalCard>()

        Regex("\"t\":\"((?:[^\"\\\\]|\\\\.)*)\",\"href\":\"(/(?:series|movies)/[^\"]+)\"")
            .findAll(plain)
            .forEach { match ->
                val href = match.groupValues[2]
                val window = plain.substring(match.range.first, minOf(plain.length, match.range.last + 420))
                val img    = Regex("\"img\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
                val year   = Regex("\"year\":\"?(\\d{4})\"?").find(window)?.groupValues?.get(1)?.toIntOrNull()
                val meta   = Regex("\"meta\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)

                cards.add(
                    DiziPalCard(
                        title   = match.groupValues[1].replace("\\\"", "\""),
                        href    = href,
                        poster  = img,
                        year    = year,
                        meta    = meta,
                        isMovie = href.contains("/movies/"),
                    )
                )
            }

        return cards.distinctBy { it.href }
    }

    /** "Son Bölümler" akışı: `{"t":"…","href":"/bolum/…","sHref":"/series/…","ep":"…"}`. */
    fun parseEpisodeFeed(html: String): List<DiziPalCard> {
        val plain = unescapeRsc(html)
        val feed  = mutableListOf<DiziPalCard>()

        Regex("\"t\":\"((?:[^\"\\\\]|\\\\.)*)\",\"href\":\"/bolum/[^\"]+\",\"sHref\":\"(/(?:series|movies)/[^\"]+)\"")
            .findAll(plain)
            .forEach { match ->
                val window  = plain.substring(match.range.first, minOf(plain.length, match.range.last + 480))
                val episode = Regex("\"ep\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
                val img     = Regex("\"img\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
                    ?: Regex("\"wide\":\"([^\"]+)\"").find(window)?.groupValues?.get(1)
                val title   = match.groupValues[1].replace("\\\"", "\"")

                feed.add(
                    DiziPalCard(
                        title   = if (episode != null) "$title · $episode" else title,
                        href    = match.groupValues[2],
                        poster  = img?.substringBefore("#"),
                        year    = null,
                        meta    = episode,
                        isMovie = match.groupValues[2].contains("/movies/"),
                    )
                )
            }

        return feed.distinctBy { it.href }
    }

    // ── Oynatıcı sayfası ─────────────────────────────────────────────────────

    //: Reklam/pre-roll akışı izleri — bunlar bölümün kendisi değil.
    private val AD_HINTS = listOf("vast", "preroll", "pre-roll", "adserver", "doubleclick",
        "googlesyndication", "/ads/", "ad_" , "banner", "jingle", "promo")

    /** Oynatıcı HTML'inde geçen m3u8/mp4 adresleri (kaçışlı `/` dahil). */
    fun mediaUrls(body: String): List<String> {
        val text = body.replace("\\/", "/")
        val m3u8 = Regex("""(?:https?:)?//[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*""").findAll(text).map { it.value }.toList()
        if (m3u8.isNotEmpty()) return withoutAds(m3u8).distinct()

        val mp4 = Regex("""(?:https?:)?//[^\s"'<>\\]+\.mp4[^\s"'<>\\]*""").findAll(text).map { it.value }.toList()
        return withoutAds(mp4).distinct()
    }

    private fun withoutAds(urls: List<String>): List<String> {
        val clean = urls.filter { url -> AD_HINTS.none { hint -> url.lowercase().contains(hint) } }

        return if (clean.isNotEmpty()) clean else urls
    }

    /** `<iframe src>` adresleri. */
    fun iframeUrls(body: String): List<String> {
        return Regex("<iframe[^>]*>", RegexOption.IGNORE_CASE)
            .findAll(body.replace("\\/", "/"))
            .mapNotNull { tag -> Regex("src\\s*=\\s*\"([^\"]+)\"").find(tag.value)?.groupValues?.get(1) }
            .filter { it.isNotBlank() && !it.startsWith("about:") }
            .distinct()
            .toList()
    }

    /** Altyazı izleri: `<track src=…>` ve `"subtitle":"…vtt"` biçimleri. */
    fun subtitleTracks(body: String): List<Pair<String, String>> {
        val text  = body.replace("\\/", "/")
        val found = mutableListOf<Pair<String, String>>()

        Regex("<track[^>]*>", RegexOption.IGNORE_CASE).findAll(text).forEach { match ->
            val tag  = match.value
            val src  = Regex("src\\s*=\\s*\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@forEach
            val lang = Regex("(?:srclang|label)\\s*=\\s*\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: "tr"
            found.add(lang to src)
        }

        // JSON / oynatıcı yapılandırması: {"file":"…vtt","label":"Türkçe"}
        Regex("(?:file|src|url|subtitle)\\s*[:=]\\s*[\"']([^\"']+\\.(?:vtt|srt)[^\"']*)[\"']").findAll(text).forEach { match ->
            val window = text.substring(maxOf(0, match.range.first - 240), match.range.first)
            val lang = Regex("(?:srclang|subtitle_lang|label|lang|language)\\s*[:=]\\s*[\"']([^\"']+)[\"']")
                .find(window)?.groupValues?.get(1) ?: "tr"

            found.add(lang to match.groupValues[1])
        }

        return found.distinctBy { it.second }
    }

    /** `<script>` içine gömülü base64 (`atob("…")`) yapılandırmalarını çözer. */
    fun decodedBlobs(body: String): List<String> {
        return Regex("atob\\(\\s*[\"']([A-Za-z0-9+/=]{24,})[\"']\\s*\\)").findAll(body)
            .mapNotNull { base64Decode(it.groupValues[1])?.toString(Charsets.UTF_8) }
            .toList()
    }

    // ── JSON tarayıcıları ────────────────────────────────────────────────────

    /** `start` konumundaki `[` ile başlayan diziyi (dizeleri atlayarak) döndürür. */
    fun jsonArrayAt(text: String, start: Int): String? {
        var index  = start
        var depth  = 0
        var inText = false
        var escape = false

        while (index < text.length) {
            val char = text[index]

            if (inText) {
                when {
                    escape      -> escape = false
                    char == '\\' -> escape = true
                    char == '"'  -> inText = false
                }
            } else {
                when (char) {
                    '"' -> inText = true
                    '[' -> depth++
                    ']' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }

            index++
        }

        return null
    }

    /** `start` konumundaki `{` ile başlayan nesneyi döndürür. */
    fun jsonObjectAt(text: String, start: Int): String? {
        var index  = start
        var depth  = 0
        var inText = false
        var escape = false

        while (index < text.length) {
            val char = text[index]

            if (inText) {
                when {
                    escape      -> escape = false
                    char == '\\' -> escape = true
                    char == '"'  -> inText = false
                }
            } else {
                when (char) {
                    '"' -> inText = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, index + 1)
                    }
                }
            }

            index++
        }

        return null
    }

    /** `"alan":"değer"` (kaçış destekli). */
    fun stringField(text: String, field: String): String? {
        val pattern = Regex("\"${Regex.escape(field)}\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        val raw     = pattern.find(text)?.groupValues?.get(1) ?: return null

        return raw.replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\").ifBlank { null }
    }

    private fun valueOf(text: String, field: String): String? = stringField(text, field)

    // ── Kripto / kodlama ─────────────────────────────────────────────────────

    fun pbkdf2Sha512(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac    = Mac.getInstance("HmacSHA512")
        mac.init(SecretKeySpec(password, "HmacSHA512"))
        val result = ByteArray(length)
        var offset = 0
        var block  = 1

        while (offset < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))

            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (index in t.indices) t[index] = (t[index].toInt() xor u[index].toInt()).toByte()
            }

            val take = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, take)
            offset += take
            block++
        }

        return result
    }

    /** Android + JVM ortak base64 çözücü (android.util.Base64'e bağlı kalmadan). */
    fun base64Decode(value: String): ByteArray? {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val clean    = value.filter { it in alphabet || it == '=' || it == '-' || it == '_' }
            .replace('-', '+')
            .replace('_', '/')

        val output = java.io.ByteArrayOutputStream()
        var buffer  = 0
        var bits    = 0

        for (char in clean) {
            if (char == '=') break
            val index = alphabet.indexOf(char)
            if (index < 0) continue

            buffer = (buffer shl 6) or index
            bits  += 6

            if (bits >= 8) {
                bits -= 8
                output.write((buffer shr bits) and 0xFF)
            }
        }

        return if (output.size() == 0) null else output.toByteArray()
    }

    fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
        } catch (error: NumberFormatException) {
            null
        }
    }
}
