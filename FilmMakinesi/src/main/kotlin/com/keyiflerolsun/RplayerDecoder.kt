// ! FilmMakinesi / HDFilmCehennemi ortak oynatıcı çözücüsü (paketli JS).
//
// ! NEDEN SIFIRDAN YAZILDI?
// ! Eski çözücü, sayfadaki JavaScript'i TAM METİN kalıplarıyla arıyordu:
//!
//!     result = result.split('').reverse().join('')
//!     result = atob(result)
//!     (o - base + 15) % 26
//!
//! ! Site tarafında tek bir boşluk, tek bir değişken adı (result → r, i → j)
//! ! ya da işlem sırası değiştiğinde bu kalıplar tutmuyor; çözücü sessizce
//! ! null dönüyor ve uygulama "0 kaynak" gösteriyordu.
//
// ! YENİ YAKLAŞIM (yapısal):
//!   1) Sayfadaki TÜM `eval(function(p,a,c,k,e,d){...}(...))` blokları açılır
//!      (Dean Edwards packer, a/c parametreleri kaynaktan okunur).
//!   2) `sources:[{file:VAR}]` hangi değişkeni istiyorsa o değişkenin
//!      `var VAR = fn(["parça","parça",...])` çağrısı bulunur.
//!   3) İŞLEMLER, çağrılan fn'in GÖVDESİNDEN sırayla çıkarılır; kalıplar
//!      değişken adına ve boşluğa duyarsızdır:
//!         .reverse()          → ters çevir
//!         atob(...) / btoa(...)→ base64 çöz / kodla
//!         (x - 65|97 + N) % 26 → harf kaydırma (rot)
//!         MAGIC % (i ± N)      → sayısal bayt kaydırma (son adım)
//!   4) Üretilen adres `http(s)://…` doğrulamasından geçmezse, bilinen işlem
//!      sıraları ve kaydırma yönleri sınırlı sayıda denenir.
//
// ! Bu dosya android.* bağımlılığı İÇERMEZ; fixture'lar üzerinde birim
// ! testiyle doğrulanır (src/test/.../RplayerDecoderTest.kt).

package com.keyiflerolsun

object RplayerDecoder {

    /** Altyazı kaydı (oynatıcı sayfasındaki `tracks:` dizisinden). */
    data class Track(
        val file: String,
        val label: String,
        val language: String,
        val kind: String
    )

    private const val ALPHA = "0123456789abcdefghijklmnopqrstuvwxyz"

    // ! Kaynaktan okunamazsa kullanılan eski sabitler (geriye dönük uyumluluk).
    private const val DEFAULT_MAGIC = 399756995L
    private const val DEFAULT_OFFSET = 5

    // ! eval(function(p,a,c,k,e,d){ ... }('PAYLOAD',62,77,'a|b|c'.split('|'),0,{}))
    private val PACKER_RE = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{(.*?)\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    // ! `var X = fn(["a","b"])` / `X=fn(['a','b'])` → [değişken, fonksiyon, içerik]
    private val ARRAY_CALL_RE = Regex(
        """([A-Za-z0-9_$]+)\s*=\s*([A-Za-z0-9_$]+)\(\s*\[\s*((?:"[^"]*"|'[^']*')(?:\s*,\s*(?:"[^"]*"|'[^']*'))*)\s*\]\s*\)"""
    )
    private val QUOTED_RE = Regex("\"([^\"]*)\"|'([^']*)'")

    private val SOURCES_FILE_RE = Regex(
        """sources\s*:\s*\[\s*\{\s*file\s*:\s*([A-Za-z0-9_$]+)""",
        RegexOption.IGNORE_CASE
    )
    private val VAR_ASSIGN_RE = Regex(
        """(?:var|let|const)\s+([A-Za-z0-9_$]+)\s*=\s*([A-Za-z0-9_$]+)\("""
    )

    // ── İşlem kalıpları (boşluğa ve değişken adına duyarsız) ───────────────
    private val OP_REVERSE_RE = Regex("""\.reverse\s*\(\s*\)""")
    private val OP_ATOB_RE = Regex("""(?<![\w$])atob\s*\(""")
    private val OP_BTOA_RE = Regex("""(?<![\w$])btoa\s*\(""")
    private val OP_REPLACE_RE = Regex("""\.replace\s*\(""")

    // ! Harf kaydırma (rot) SAYFADA EN AZ ÜÇ FARKLI BİÇİMDE yazılıyor:
    //!   (o - base + 15) % 26 + base            ← klasik
    //!   (o - 65 + 15) % 26 + 65
    //!   (c<='Z'?90:122) >= (c=c.charCodeAt(0)+13) ? c : c-26   ← ROT13, % YOK
    //! Bu yüzden yalnızca "kaydırma miktarı" okunur; uygulama harf aralığına
    //! göre yapıldığından base (65/97) hesaba katılmaz.
    private val ROT_PATTERNS = listOf(
        Regex("""[A-Za-z_$][\w$]*\s*-\s*(?:base|65|97)\s*\+\s*(\d+)"""),
        Regex("""[A-Za-z_$][\w$]*\s*=\s*[A-Za-z_$][\w$]*\s*\.charCodeAt\(\s*0\s*\)\s*\+\s*(\d+)"""),
        Regex("""charCodeAt\(\s*0\s*\)\s*\+\s*(\d+)"""),
        Regex("""\+\s*(\d+)\s*\)\s*%\s*26""")
    )

    // ! charCode = ((charCode - (2833890617 % (i + 15))) % 256 + 256) % 256
    // ! charCode = ((charCode - (2833890617 % (i + 15))) % 256 + 256) % 256
    // ! Döngü değişkeni her zaman `i` değildir (j, k, n…); ofsetin işareti de
    // ! `i - 15` olabilir. Bayt kaydırmasının YÖNÜ ise `charCode` ile parantez
    // ! arasındaki işarettir — ikisi ayrı ayrı okunur.
    private val SHIFT_RE = Regex(
        """(\d{5,13})\s*%\s*\(\s*[A-Za-z_$][\w$]*\s*([+-])\s*(\d+)\s*\)"""
    )
    private val SHIFT_SIGN_RE = Regex("""([+-])\s*\(*\s*\d{5,13}\s*%""")

    // ── Altyazı kalıpları ─────────────────────────────────────────────────
    private val TRACKS_RE = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_BLOCK_RE = Regex("""\{[^{}]*\}""")
    private val FIELD_FILE_RE = Regex("""\"file\"\s*:\s*\"([^\"]+)\"""")
    private val FIELD_LABEL_RE = Regex("""\"label\"\s*:\s*\"([^\"]*)\"""")
    private val FIELD_LANGUAGE_RE = Regex("""\"language\"\s*:\s*\"([^\"]*)\"""")
    private val FIELD_KIND_RE = Regex("""\"kind\"\s*:\s*\"([^\"]*)\"""")

    private val FILE_LINK_RE = Regex("""file_link\s*=\s*\"([^\"]+)\"""")
    private val PLAIN_STREAM_RE = Regex(
        """(https?://[^\s\"'<>]+(?:\.m3u8|\.mp4|master\.txt)(?:\?[^\s\"'<>]*)?)""",
        RegexOption.IGNORE_CASE
    )

    private sealed class Op {
        object Atob : Op()
        object Btoa : Op()
        object Reverse : Op()
        class Rot(val shift: Int) : Op()
    }

    // ── Genel giriş noktaları ─────────────────────────────────────────────

    /**
     * Sayfadaki ŞİFRELİ/PAKETLİ kaynağı çözer.
     * ! Düz (şifresiz) m3u8 ARAMAZ: JSON-LD'deki bayat `contentUrl`
     * ! "çözülmüş kaynak" sanılmamalı.
     */
    fun extractPackedStreamUrl(html: String): String? {
        if (html.isBlank()) return null
        val normalized = unescapeHtml(html)

        // 0) Bilinen `dc_*` oynatıcı biçimleri — doğrudan ve kesin.
        decodeKnownPlayer(normalized)?.let { return it }

        // 1) Paketlenmiş bloklar + ham metin, sırayla taranır.
        val sources = mutableListOf<String>()
        PACKER_RE.findAll(normalized).forEach { match ->
            val packed = unescapeJsSingleQuoted(match.groupValues[2])
            val base = match.groupValues[3].toIntOrNull() ?: 62
            val count = match.groupValues[4].toIntOrNull() ?: 0
            val dictionary = match.groupValues[5].split("|")
            sources.add(packerUnpack(packed, base, count, dictionary))
        }
        sources.add(normalized)

        for (source in sources) {
            val fnName = sourcesFileVariable(source)?.let { variableFunction(source, it) }
                ?: calledFunctionName(source)
            val body = fnName?.let { functionBody(source, it) }
            // ! Sayfadaki alakasız diziler (reklam kimlikleri vb.) için tam
            // ! deneme yapılmasın: yalnızca ilk 4 dizge dizisi denenir.
            val payloads = payloads(source, preferVariable = sourcesFileVariable(source))
            for (parts in payloads.take(4)) {
                decodePayload(parts, body, source)?.let { return it }
            }
        }

        // 2) file_link = "…" (paketlenmemiş ama base64/şifreli) — yedek yol.
        FILE_LINK_RE.find(normalized)?.groupValues?.getOrNull(1)?.let { raw ->
            val values = QUOTED_RE.findAll(raw).mapNotNull { it.groupValues[1].ifBlank { it.groupValues[2] } }
                .toList()
            if (values.isNotEmpty()) {
                decodePayload(values, null, normalized)?.let { return it }
            }
        }
        return null
    }

    /** rplayer/embed sayfasından gerçek akış adresini çözer. */
    fun extractStreamUrl(html: String): String? {
        extractPackedStreamUrl(html)?.let { return it }
        val normalized = unescapeHtml(html)
        PLAIN_STREAM_RE.find(normalized)?.groupValues?.getOrNull(1)?.let { raw ->
            val url = raw.replace("\\/", "/").trim()
            if (isValidStreamUrl(url)) return url
        }
        return null
    }

    /** Sayfada şifreli/obfüskeli oynatıcı scripti var mı? */
    fun hasPackedPlayer(html: String): Boolean =
        html.contains("function dc_") || html.contains("eval(function") ||
            html.contains("sources:") || html.contains("file_link")

    /** Oynatıcı sayfasındaki altyazı kayıtlarını döndürür. */
    fun extractTracks(html: String): List<Track> {
        val raw = TRACKS_RE.find(html)?.groupValues?.getOrNull(1) ?: return emptyList()
        val out = mutableListOf<Track>()
        TRACK_BLOCK_RE.findAll(raw).forEach { block ->
            val file = FIELD_FILE_RE.find(block.value)?.groupValues?.get(1) ?: return@forEach
            val kind = FIELD_KIND_RE.find(block.value)?.groupValues?.get(1).orEmpty()
            if (kind.isNotBlank() && kind != "captions" && kind != "subtitles") return@forEach
            out.add(
                Track(
                    file = file.replace("\\/", "/"),
                    label = FIELD_LABEL_RE.find(block.value)?.groupValues?.get(1).orEmpty(),
                    language = FIELD_LANGUAGE_RE.find(block.value)?.groupValues?.get(1).orEmpty(),
                    kind = kind
                )
            )
        }
        return out
    }

    /**
     * Oynatıcı sayfasındaki `<track src="…" kind="captions" srclang="tr">`
     * etiketlerini altyazıya çevirir.
     *
     * ! Canlı embed sayfasında altyazılar bu biçimde geliyor ve adresler
     * ! GÖRELİDİR (`/vtt/…vtt`); embed host'una göre çözülür. Eski kod
     * ! yalnızca `tracks: [...]` JSON'unu okuyordu, bu yüzden Türkçe altyazı
     * ! listede hiç görünmüyordu.
     */
    fun extractTrackTags(html: String, baseUrl: String): List<Track> {
        val out = mutableListOf<Track>()
        TRACK_TAG_RE.findAll(html).forEach { match ->
            val tag = match.value
            val file = attribute(tag, "src") ?: return@forEach
            val kind = attribute(tag, "kind").orEmpty()
            if (kind.isNotBlank() && kind != "captions" && kind != "subtitles") return@forEach
            val absolute = absolutize(file, baseUrl) ?: return@forEach
            out.add(
                Track(
                    file = absolute,
                    label = attribute(tag, "label").orEmpty(),
                    language = attribute(tag, "srclang").orEmpty(),
                    kind = kind.ifBlank { "captions" }
                )
            )
        }
        return out.distinctBy { it.file }
    }

    private val TRACK_TAG_RE = Regex("""<track\b[^>]*>""", RegexOption.IGNORE_CASE)

    private fun attribute(tag: String, name: String): String? {
        val match = Regex("""\b$name\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE).find(tag)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun absolutize(raw: String, base: String): String? = runCatching {
        when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> java.net.URI(base).resolve(raw).toString()
        }
    }.getOrNull()

    /**
     * Sitenin hazır `dc_*` oynatıcı biçimlerini DOĞRUDAN çözer.
     *
     * ! Yapısal çözücü sayfadaki işlemleri "tahmin eder"; buradaki üç biçim
     * ! ise sitenin bilinen oynatıcı aileleridir (Kekik/HDFilmCehennemi
     * ! kaynaklarından birebir):
     * !   dc_hello("base64")   → base64 → ters → base64 → son '+'/' '/'|' sonrası
     * !   dc_xxx(["a","b"...]) → ROT20 → base64 → base64 → XOR akışı (acc)
     * !   dc_xxx(["a","b"...]) → base64 → ters → ROT13 → magic % (i+n) çıkarma
     * ! Üçü de http(s) doğrulamasından geçmezse sonuç yok sayılır.
     */
    fun decodeKnownPlayer(html: String): String? {
        if (html.isBlank()) return null
        val normalized = unescapeHtml(html)
        val script = StringBuilder(normalized)
        PACKER_RE.findAll(normalized).forEach { match ->
            script.append('\n').append(
                packerUnpack(
                    unescapeJsSingleQuoted(match.groupValues[2]),
                    match.groupValues[3].toIntOrNull() ?: 62,
                    match.groupValues[4].toIntOrNull() ?: 0,
                    match.groupValues[5].split("|")
                )
            )
        }
        val text = script.toString()

        DC_HELLO_RE.find(text)?.groupValues?.getOrNull(1)?.let { encoded ->
            dcHello(encoded)?.let { return it }
        }

        DC_PARTS_RE.findAll(text).forEach { match ->
            val parts = QUOTED_RE.findAll(match.groupValues[1])
                .mapNotNull { it.groupValues[1].ifBlank { it.groupValues[2] } }
                .toList()
            if (parts.isEmpty()) return@forEach
            val joined = parts.joinToString("")
            dcAcc(joined, text)?.let { return it }
            dcNew(joined, text)?.let { return it }
        }
        return null
    }

    private val DC_HELLO_RE = Regex("""dc_hello\(\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
    private val DC_PARTS_RE = Regex("""dc_[A-Za-z0-9_$]*\(\s*\[(.*?)\]\s*\)""", RegexOption.DOT_MATCHES_ALL)

    /** `dc_hello`: base64 → ters → base64 → son ayırıcıdan sonrası. */
    private fun dcHello(encoded: String): String? {
        val once = decodeBase64(encoded) ?: return null
        val twice = decodeBase64(once.reversed()) ?: return null
        val link = when {
            twice.contains('+') -> twice.substringAfterLast('+')
            twice.contains(' ') -> twice.substringAfterLast(' ')
            twice.contains('|') -> twice.substringAfterLast('|')
            else -> twice
        }
        return cleanDecoded(link)
    }

    /** ROT20 → base64 → base64 → XOR akışı (`var acc = 141`, `acc + 6`). */
    private fun dcAcc(joined: String, script: String): String? {
        val rotated = StringBuilder(joined.length)
        for (c in joined) {
            rotated.append(
                when (c) {
                    in 'a'..'z' -> ('a'.code + (c.code - 'a'.code + 20) % 26).toChar()
                    in 'A'..'Z' -> ('A'.code + (c.code - 'A'.code + 20) % 26).toChar()
                    else -> c
                }
            )
        }
        val first = decodeBase64(rotated.toString()) ?: return null
        val second = decodeBase64(first) ?: return null
        val cipher = decodeBase64(second) ?: return null
        val start = ACC_START_RE.find(script)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 141
        val step = ACC_STEP_RE.find(script)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 6
        var acc = start
        val out = StringBuilder(cipher.length)
        for (c in cipher) {
            val value = c.code and 0xFF
            acc = (acc + step) and 0xFF
            out.append(((value xor acc) and 0xFF).toChar())
            acc = (acc + value) and 0xFF
        }
        return cleanDecoded(out.toString())
    }

    /**
     * base64 / ters / ROT → `magic % (i + n)` çıkarma.
     *
     * ! Sıra sitenin sürümüne göre değişiyor: canlı embed sayfası
     * ! `reverse → atob → rot13` yazarken eski oynatıcı `atob → reverse → rot13`
     * ! yazıyor. Hangi sıranın tuttuğu ancak denemeyle anlaşılır; sonuç
     * ! http(s) doğrulamasından geçmezse sıradaki denenir.
     */
    private fun dcNew(joined: String, script: String): String? {
        val (magic, offset, sign) = parseShift(script)
        val orders = listOf(
            listOf<Op>(Op.Reverse, Op.Atob, Op.Rot(13)),
            listOf<Op>(Op.Atob, Op.Reverse, Op.Rot(13)),
            listOf<Op>(Op.Reverse, Op.Atob, Op.Rot(15)),
            listOf<Op>(Op.Reverse, Op.Rot(13), Op.Atob),
            listOf<Op>(Op.Reverse, Op.Rot(15), Op.Atob),
            listOf<Op>(Op.Atob, Op.Reverse, Op.Rot(15))
        )
        for (ops in orders) {
            val text = applyOps(joined, ops) ?: continue
            if (text.isBlank()) continue
            for (s in shiftSigns(sign)) {
                cleanDecoded(byteShift(text, magic, offset, s))?.let { return it }
                if (magic != DEFAULT_MAGIC || offset != DEFAULT_OFFSET) {
                    cleanDecoded(byteShift(text, DEFAULT_MAGIC, DEFAULT_OFFSET, s))?.let { return it }
                }
            }
        }
        return null
    }

    private val ACC_START_RE = Regex("""acc\s*=\s*(\d{1,6})(?!\s*[+-])""")
    private val ACC_STEP_RE = Regex("""acc\s*\+\s*(\d{1,6})""")

    /** Çözülen metinden geçerli http(s) adresini ayıklar. */
    private fun cleanDecoded(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val index = raw.indexOf("http")
        val candidate = if (index >= 0) raw.substring(index) else raw
        val cut = candidate.indexOfFirst { it == ' ' || it == '"' || it == '\'' || it == '<' || it == '>' || it.code < 32 }
        val url = (if (cut >= 0) candidate.substring(0, cut) else candidate).trim()
        return url.takeIf { isValidStreamUrl(it) }
    }

    // ── Kaynak tarama ─────────────────────────────────────────────────────

    /** `sources:[{file:VAR}]` → VAR */
    private fun sourcesFileVariable(source: String): String? =
        SOURCES_FILE_RE.find(source)?.groupValues?.getOrNull(1)

    /** `var VAR = fn(` → fn */
    private fun variableFunction(source: String, variable: String): String? {
        VAR_ASSIGN_RE.findAll(source).forEach { match ->
            if (match.groupValues[1] == variable || match.groupValues[2] == variable) {
                return match.groupValues[2]
            }
        }
        return null
    }

    /** Payload dizisini taşıyan çağrının fonksiyon adı. */
    private fun calledFunctionName(source: String): String? {
        ARRAY_CALL_RE.find(source)?.let { match ->
            val variable = match.groupValues[1]
            variableFunction(source, variable)?.let { return it }
            return match.groupValues[2]
        }
        return null
    }

    /**
     * Sayfadaki dizge dizilerini döndürür.
     * ! `sources:[{file:VAR}]` ile belirtilen değişken ÖNE alınır; aksi halde
     * ! sayfadaki alakasız diziler (ör. reklam kimlikleri) önce denenip
     * ! yanlış sonuç üretebilir.
     */
    private fun payloads(source: String, preferVariable: String?): List<List<String>> {
        val preferred = mutableListOf<List<String>>()
        val others = mutableListOf<List<String>>()

        ARRAY_CALL_RE.findAll(source).forEach { match ->
            val variable = match.groupValues[1]
            val raw = match.groupValues[3]
            val values = QUOTED_RE.findAll(raw).mapNotNull {
                it.groupValues[1].takeIf { v -> v.isNotEmpty() } ?: it.groupValues[2]
            }.toList()
            if (values.isEmpty()) return@forEach
            if (preferVariable != null && variable == preferVariable) preferred.add(values)
            else others.add(values)
        }
        return preferred + others
    }

    /** `function NAME(...) { ... }` gövdesini (süslü parantez eşleşmesiyle) döndürür. */
    private fun functionBody(source: String, name: String): String? {
        val start = source.indexOf("function $name(")
        if (start < 0) return null
        val open = source.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        return source.substring(open)
    }

    // ── Çözme ─────────────────────────────────────────────────────────────

    private fun decodePayload(parts: List<String>, body: String?, whole: String): String? {
        val joined = parts.joinToString("")
        // ! Gerçek payload her zaman base64 parçalarından oluşur ve kısadır;
        // ! 16 karakterden kısa diziler oynatıcıya ait değildir.
        if (joined.length < 16) return null
        if (parts.any { it.length < 3 }) return null

        val (magic, offset, sign) = parseShift(body ?: whole)
        val detected = detectOps(body)

        // 1) Kaynaktan okunan işlemler + kaynaktan okunan sabitler.
        if (detected.isNotEmpty()) {
            applyOps(joined, detected)?.let { text ->
                for (s in shiftSigns(sign)) {
                    val out = byteShift(text, magic, offset, s)
                    if (isValidStreamUrl(out)) return out
                }
                // ! Kaydırma sabiti okunamadıysa eski sabitlerle de dene.
                for (s in shiftSigns(sign)) {
                    val out = byteShift(text, DEFAULT_MAGIC, DEFAULT_OFFSET, s)
                    if (isValidStreamUrl(out)) return out
                }
            }
        }

        // 2) Yedek: bilinen işlem sıraları × kaydırma miktarları.
        val orders = listOf(
            // ! Canlı arşiv sayfalarında görülen biçimler (bkz. fixture).
            listOf<Op>(Op.Reverse, Op.Atob, Op.Rot(13)),
            listOf<Op>(Op.Reverse, Op.Rot(13), Op.Atob),
            listOf<Op>(Op.Atob, Op.Reverse, Op.Rot(13)),
            listOf<Op>(Op.Atob, Op.Reverse, Op.Rot(15), Op.Atob),
            listOf<Op>(Op.Atob, Op.Reverse, Op.Atob),
            listOf<Op>(Op.Reverse, Op.Atob, Op.Atob),
            listOf<Op>(Op.Atob, Op.Atob, Op.Reverse),
            listOf<Op>(Op.Reverse, Op.Atob),
            listOf<Op>(Op.Atob, Op.Reverse)
        )
        for (order in orders) {
            for (rotShift in intArrayOf(15, 13, 8, 21, 5, 3, 12, 7, 17, 25, 1, 22)) {
                val ops = order.map { if (it is Op.Rot) Op.Rot(rotShift) else it }
                val text = applyOps(joined, ops) ?: continue
                for (s in shiftSigns(sign)) {
                    byteShift(text, magic, offset, s).let { if (isValidStreamUrl(it)) return it }
                    byteShift(text, DEFAULT_MAGIC, DEFAULT_OFFSET, s).let {
                        if (isValidStreamUrl(it)) return it
                    }
                }
            }
        }
        return null
    }

    private fun shiftSigns(preferred: Char): List<Char> =
        if (preferred == '+') listOf('+', '-') else listOf('-', '+')

    /**
     * Gövdedeki işlemleri KAYNAK SIRASINA göre toplar.
     * ! Tuzak fonksiyonlar (ör. `function d1x(){return atob('...')}`) ayrı
     * ! gövdelerde durur; burada yalnızca çağrılan fonksiyonun gövdesi
     * ! taranır. Yine de `function atob(` tanımı çağrı sayılmaz.
     */
    private fun detectOps(body: String?): List<Op> {
        if (body.isNullOrBlank()) return emptyList()
        val found = mutableListOf<Pair<Int, Op>>()

        OP_REVERSE_RE.findAll(body).forEach { found.add(it.range.first to Op.Reverse) }
        OP_ATOB_RE.findAll(body).forEach {
            val before = body.substring(maxOf(0, it.range.first - 9), it.range.first)
            if (!before.endsWith("function ")) found.add(it.range.first to Op.Atob)
        }
        OP_BTOA_RE.findAll(body).forEach {
            val before = body.substring(maxOf(0, it.range.first - 9), it.range.first)
            if (!before.endsWith("function ")) found.add(it.range.first to Op.Btoa)
        }
        // ! Harf kaydırma: `replace(...)` bloğunun içinde aranır. Aynı blokta
        // ! iki ayrı dal (büyük/küçük harf) olsa bile TEK işlem sayılır;
        // ! aksi halde döndürme iki kez uygulanıp adres bozulurdu.
        OP_REPLACE_RE.findAll(body).forEach { match ->
            val block = body.substring(match.range.first, minOf(match.range.first + 400, body.length))
            val shift = ROT_PATTERNS.asSequence()
                .mapNotNull { pattern -> pattern.find(block)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                .firstOrNull()
            if (shift != null) found.add(match.range.first to Op.Rot(shift))
        }

        return found.sortedBy { it.first }.map { it.second }
    }

    /**
     * Kaynaktan magic/offset ve bayt kaydırma YÖNÜNÜ okur.
     * @return (magic, offset, charCode'un önündeki işaret)
     */
    private fun parseShift(text: String): Triple<Long, Int, Char> {
        val sign = SHIFT_SIGN_RE.find(text)?.groupValues?.getOrNull(1)?.firstOrNull() ?: '-'
        val match = SHIFT_RE.find(text)
        if (match != null) {
            val magic = match.groupValues[1].toLongOrNull()
            val offsetSign = match.groupValues[2].firstOrNull() ?: '+'
            val offsetValue = match.groupValues[3].toIntOrNull()
            if (magic != null && offsetValue != null) {
                val offset = if (offsetSign == '-') -offsetValue else offsetValue
                return Triple(magic, offset, sign)
            }
        }
        return Triple(DEFAULT_MAGIC, DEFAULT_OFFSET, sign)
    }

    private fun applyOps(text: String, ops: List<Op>): String? {
        var result = text
        for (op in ops) {
            result = when (op) {
                is Op.Reverse -> result.reversed()
                is Op.Atob -> decodeBase64(result) ?: return null
                is Op.Btoa -> encodeBase64(result)
                is Op.Rot -> rotLetters(result, op.shift)
            }
        }
        return result
    }

    /** Son adım: sayısal bayt kaydırma (mod 256). */
    private fun byteShift(text: String, magic: Long, offset: Int, sign: Char): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val divisor = (i + offset).toLong()
            if (divisor == 0L) return ""
            val shift = (magic % divisor).toInt()
            var code = if (sign == '+') text[i].code + shift else text[i].code - shift
            code = ((code % 256) + 256) % 256
            out.append(code.toChar())
        }
        return out.toString()
    }

    private fun rotLetters(text: String, shift: Int): String {
        if (shift == 0) return text
        val out = StringBuilder(text.length)
        for (c in text) {
            out.append(
                when {
                    c in 'a'..'z' -> (((c.code - 'a'.code + shift) % 26 + 26) % 26 + 'a'.code).toChar()
                    c in 'A'..'Z' -> (((c.code - 'A'.code + shift) % 26 + 26) % 26 + 'A'.code).toChar()
                    else -> c
                }
            )
        }
        return out.toString()
    }

    // ── Yardımcılar ───────────────────────────────────────────────────────

    /** Çözülen adres gerçekten bir yayın adresi mi? */
    fun isValidStreamUrl(value: String?): Boolean {
        if (value.isNullOrBlank() || value.length < 12) return false
        for (c in value) {
            if (c.code <= 8 || (c.code in 14..31) || c.code == 127) return false
        }
        val lower = value.trim().lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        val host = lower.substringAfter("://").substringBefore('/').substringBefore('?')
        return host.isNotBlank() && host.contains('.') && !host.contains(' ')
    }

    private fun unescapeHtml(html: String): String = html
        .replace("\\/", "/")
        .replace("\\u002f", "/", ignoreCase = true)
        .replace("\\u003a", ":", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("&amp;", "&")

    /**
     * Base64 çözer.
     * ! java.util.Base64 Android'de API 26+; android.util.Base64 ise saf
     * ! Kotlin testini engellerdi. Bu yüzden elle çözülüyor.
     */
    private fun decodeBase64(value: String): String? {
        return try {
            val clean = value.trim().replace("-", "+").replace("_", "/")
            val out = java.io.ByteArrayOutputStream(clean.length * 3 / 4 + 3)
            var buffer = 0
            var bits = 0
            for (c in clean) {
                if (c == '=') break
                val index = BASE64_ALPHABET.indexOf(c)
                if (index < 0) continue
                buffer = (buffer shl 6) or index
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    out.write((buffer shr bits) and 0xFF)
                }
            }
            String(out.toByteArray(), Charsets.ISO_8859_1)
        } catch (e: Exception) {
            null
        }
    }

    private fun encodeBase64(value: String): String {
        val bytes = value.toByteArray(Charsets.ISO_8859_1)
        val out = StringBuilder()
        var index = 0
        while (index < bytes.size) {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = if (index + 1 < bytes.size) bytes[index + 1].toInt() and 0xFF else -1
            val b2 = if (index + 2 < bytes.size) bytes[index + 2].toInt() and 0xFF else -1
            out.append(BASE64_ALPHABET[b0 shr 2])
            out.append(
                BASE64_ALPHABET[if (b1 >= 0) ((b0 and 0x03) shl 4) or (b1 shr 4) else (b0 and 0x03) shl 4]
            )
            out.append(
                when {
                    b1 < 0 -> '='
                    b2 < 0 -> BASE64_ALPHABET[(b1 and 0x0F) shl 2]
                    else -> BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)]
                }
            )
            out.append(
                when {
                    b2 < 0 -> '='
                    else -> BASE64_ALPHABET[b2 and 0x3F]
                }
            )
            index += 3
        }
        return out.toString()
    }

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Dean Edwards packer açma (a/c kaynaktan gelir). */
    private fun packerUnpack(p: String, a: Int, c: Int, dictionary: List<String>): String {
        var out = p
        var i = c
        while (i > 0) {
            i--
            if (i < dictionary.size && dictionary[i].isNotEmpty()) {
                val token = encode(i, a)
                out = Regex("\\b" + Regex.escape(token) + "\\b").replace(out, dictionary[i])
            }
        }
        return out
    }

    private fun encode(num: Int, a: Int): String {
        val sb = StringBuilder()
        var n = num
        while (n >= a) {
            sb.append(toChar(n % a))
            n /= a
        }
        sb.append(toChar(n))
        return sb.reverse().toString()
    }

    private fun toChar(x: Int): Char = if (x > 35) (x + 29).toChar() else ALPHA[x]

    private fun unescapeJsSingleQuoted(s: String): String =
        s.replace("\\\\", "\u0000").replace("\\'", "'").replace("\u0000", "\\")
}
