// ! HDFilmCehennemi'nin /rplayer/ sayfasındaki paketlenmiş oynatıcı çözücüsü.
//
// ! NEDEN SAF KOTLIN?
// ! Bu dosya android.* bağımlılığı İÇERMEZ; böylece gerçek siteden alınmış
// ! örnek (fixture) üzerinde birim testiyle doğrulanabilir. Testler:
// !   HDFilmCehennemi/src/test/kotlin/com/keyiflerolsun/RplayerDecoderTest.kt
//
// ! SİTE YAPISI (canlıdan doğrulanmış, tahmin değil):
// !   /rplayer/<hash>/ sayfası JW Player kurar ve kaynak adresi şu biçimdedir:
//!     var configs = {
//!       //sources: [{file:atob(file_link)}],   ← decoy (yorumda)
//!       sources: [{file:s_X2kgHSLHfHs}],        ← gerçek: aşağıdaki paketli çağrı
//!       tracks: [{ "file":"\/srt\/00035\/..._Turkish.vtt", "language":"tr",
//!                  "kind":"captions" }],
//!       type: "hls", ...
//!     }
//!     var s_X2kgHSLHfHs = dc_V3LHCHN84DH(["PT1yZmhRYWNaZ", ...]);
//!
//!   dc_<rastgele> fonksiyonu "Dean Edwards packer" (a=62) ile paketlenmiş
//!   bir kaynaktır. İçindeki işlemler (atob / reverse / harf-kaydırma) SAYI ve
//!   SIRASI itilere göre DEĞİŞİR; son adım daima sayısal bayt-kaydırmadır ve
//!   kaydırma sabiti (magic/offset) her yüklemede farklıdır. Sayfada ayrıca
//!   d1x/d2x/d3x gibi TUZAK (decoy) fonksiyonlar bulunur (ör. atob('...')).
//!
//!   Bu yüzden: işlemler metin içindeki KESİN kalıplarla bulunur, tuzaklar
//!   sayıma katılmaz, magic/offset kaynaktan okunur ve sonuç bir URL olarak
//!   doğrulanır. Doğrulama başarısız olursa çoklu strateji denenir.

package com.keyiflerolsun

object RplayerDecoder {

    /** Altyazı kaydı (rplayer sayfasındaki `tracks:` dizisinden). */
    data class Track(
        val file: String,
        val label: String,
        val language: String,
        val kind: String
    )

    private const val ALPHA = "0123456789abcdefghijklmnopqrstuvwxyz"

    // ! Varsayılanlar: kaynaktan okunamazsa kullanılır (nuvio/hayalet referansı).
    private const val DEFAULT_MAGIC = 399756995L
    private const val DEFAULT_OFFSET = 5

    // ! eval(function(p,a,c,k,e,d){ ... }('P',A,C,'K|K|K'.split('|'),0,{}))
    private val PACKER_RE = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{(.*?)\}\('(.*?)',(\d+),(\d+),'(.*?)'\.split\('\|'\),0,\{\}\)""",
        RegexOption.DOT_MATCHES_ALL
    )

    // ! dc_XXXX(["parça1","parça2",...])
    private val ARRAY_CALL_RE = Regex(
        """=\s*[A-Za-z0-9_$]+\(\[\s*((?:"[^"]*"\s*,\s*)*"[^"]*")\s*\]\)"""
    )
    private val SINGLE_QUOTED_ARRAY_CALL_RE = Regex(
        """=\s*[A-Za-z0-9_$]+\(\[\s*((?:'[^']*'\s*,\s*)*'[^']*')\s*\]\)"""
    )
    private val DOUBLE_QUOTED_RE = Regex(""""([^"]*)"""")
    private val SINGLE_QUOTED_RE = Regex("""'([^']*)'""")

    // ! İşlem kalıpları — KAYNAK SIRASI önemlidir, tuzak fonksiyonlar eşleşmez.
    private val OP_REVERSE_RE = Regex("""result\s*=\s*result\.split\(''\)\.reverse\(\)\.join\(''\)""")
    private val OP_ATOB_RE = Regex("""result\s*=\s*atob\(result\)""")
    private val OP_ROT_RE = Regex(
        """result\s*=\s*result\.replace\(/\[a-zA-Z\]/g,function\(\w\)\{[^}]*?\((\w+)\s*-\s*(\w+)\s*([+-])\s*(\d+)"""
    )

    // ! magic/offset: charCode=((charCode-(2833890617%(i+15)))%256+256)%256
    private val MAGIC_RE = Regex("""\((\d+)%\(i\+(\d+)\)""")

    // ! Altyazı: tracks: [{...}]
    private val TRACKS_RE = Regex("""tracks\s*:\s*\[(.*?)\]""", RegexOption.DOT_MATCHES_ALL)
    private val TRACK_BLOCK_RE = Regex("""\{[^{}]*\}""")
    private val FIELD_FILE_RE = Regex(""""file"\s*:\s*"([^"]+)"""")
    private val FIELD_LABEL_RE = Regex(""""label"\s*:\s*"([^"]*)"""")
    private val FIELD_LANGUAGE_RE = Regex(""""language"\s*:\s*"([^"]*)"""")
    private val FIELD_KIND_RE = Regex(""""kind"\s*:\s*"([^"]*)"""")

    private sealed class Op {
        object Atob : Op()
        object Reverse : Op()
        class Rot(val shift: Int) : Op()
    }

    // ── Genel giriş noktası ────────────────────────────────────────────────

    /**
     * Sayfadaki ŞİFRELİ/PAKETLİ kaynağı çözer (eval packer ve file_link yolları).
     *
     * ! `extractStreamUrl`den farkı: düz (şifresiz) m3u8 ARAMAZ. Eklentiler
     * ! contentUrl/master.txt geri dönüşünden ÖNCE bunu çağırır; böylece
     * ! sayfada şifreli bir blok varken JSON-LD'deki bayat contentUrl
     * ! "çözülmüş kaynak" sanılıp oynatıcıya gönderilmez.
     *
     * @return çözülemezse null
     */
    fun extractPackedStreamUrl(html: String): String? {
        if (html.isBlank()) return null

        // ! 1) Sayfadaki paketlenmiş eval bloklarını sırayla dene.
        for (block in PACKER_RE.findAll(html).map { m ->
            Triple(
                m.groupValues[2],                 // p
                m.groupValues[3].toIntOrNull() ?: 62, // a
                m.groupValues[4].toIntOrNull() ?: 0   // c
            ) to m.groupValues[5].split("|")
        }.toList()) {
            val (packed, a, c) = block.first
            val dictionary = block.second
            val unpacked = packerUnpack(unescapeJsSingleQuoted(packed), a, c, dictionary)
            val decoded = decodeUnpacked(unpacked)
            if (decoded != null) return decoded
        }

        // ! 2) file_link = "..." biçimi (paketlenmemiş ama base64/şifreli) — yedek yol.
        FILE_LINK_RE.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            val parts = quotedValues(raw)
            if (parts.isNotEmpty()) {
                decodeCandidates(parts, DEFAULT_MAGIC, DEFAULT_OFFSET)
                    .firstOrNull { isValidStreamUrl(it) }?.let { return it }
            }
        }
        return null
    }

    /**
     * rplayer sayfasından gerçek akış adresini (master m3u8) çözer.
     * ! Önce şifreli kaynak, sonra düz adres (son çare).
     * @return çözülemezse null
     */
    fun extractStreamUrl(html: String): String? {
        extractPackedStreamUrl(html)?.let { return it }

        // ! Son çare: sayfada düz bir m3u8/master.txt adresi var mı?
        PLAIN_STREAM_RE.find(html)?.groupValues?.getOrNull(1)?.let { raw ->
            val url = raw.replace("\\/", "/").trim()
            if (isValidStreamUrl(url)) return url
        }
        return null
    }

    /** Sayfada şifreli/obfüskeli oynatıcı scripti var mı? */
    fun hasPackedPlayer(html: String): Boolean =
        html.contains("function dc_") || html.contains("eval(function")

    /** rplayer sayfasındaki altyazı kayıtlarını döndürür. */
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

    // ── Paketlenmiş kaynağın çözülmesi ─────────────────────────────────────

    private fun decodeUnpacked(unpacked: String): String? {
        val parts = extractParts(unpacked) ?: return null
        val ops = parseOps(unpacked)
        val (magic, offset) = parseMagic(unpacked)

        // ! Ana yol: kaynaktan okunan işlemler + kaynaktan okunan magic/offset.
        val text = applyOps(parts.joinToString(""), ops)
        val shifted = byteShift(text, magic, offset)
        if (isValidStreamUrl(shifted)) return shifted

        // ! Yedek: işlem sırası/çeşidi farklıysa çoklu strateji dene.
        return decodeCandidates(parts, magic, offset).firstOrNull { isValidStreamUrl(it) }
    }

    /** Kaynaktaki parça dizisini ("...", "...") çıkarır. */
    private fun extractParts(unpacked: String): List<String>? {
        ARRAY_CALL_RE.find(unpacked)?.groupValues?.getOrNull(1)?.let { raw ->
            val values = DOUBLE_QUOTED_RE.findAll(raw).map { it.groupValues[1] }.toList()
            if (values.isNotEmpty()) return values
        }
        SINGLE_QUOTED_ARRAY_CALL_RE.find(unpacked)?.groupValues?.getOrNull(1)?.let { raw ->
            val values = SINGLE_QUOTED_RE.findAll(raw).map { it.groupValues[1] }.toList()
            if (values.isNotEmpty()) return values
        }
        return null
    }

    /**
     * İşlemleri kaynak sırasına göre toplar.
     * ! Tuzak fonksiyonlar (d2x içindeki atob('...') gibi) KESİN kalıplara
     * ! uymadığı için sayıma katılmaz.
     */
    private fun parseOps(unpacked: String): List<Op> {
        val found = mutableListOf<Pair<Int, Op>>()
        OP_REVERSE_RE.findAll(unpacked).forEach { m ->
            found.add(m.range.first to Op.Reverse)
        }
        OP_ATOB_RE.findAll(unpacked).forEach { m ->
            found.add(m.range.first to Op.Atob)
        }
        OP_ROT_RE.findAll(unpacked).forEach { m ->
            val sign = m.groupValues[3]
            val number = m.groupValues[4].toIntOrNull() ?: return@forEach
            val shift = if (sign == "-") (26 - number) % 26 else number
            found.add(m.range.first to Op.Rot(shift))
        }
        return found.sortedBy { it.first }.map { it.second }
    }

    /** Kaynaktan magic/offset okur; bulunamazsa varsayılana düşer. */
    private fun parseMagic(unpacked: String): Pair<Long, Int> {
        val m = MAGIC_RE.find(unpacked)
        if (m != null) {
            val magic = m.groupValues[1].toLongOrNull()
            val offset = m.groupValues[2].toIntOrNull()
            if (magic != null && offset != null) return magic to offset
        }
        return DEFAULT_MAGIC to DEFAULT_OFFSET
    }

    private fun applyOps(text: String, ops: List<Op>): String {
        var result = text
        for (op in ops) {
            result = when (op) {
                is Op.Reverse -> result.reversed()
                is Op.Atob -> decodeBase64(result) ?: return ""
                is Op.Rot -> rotLetters(result, op.shift)
            }
        }
        return result
    }

    /** Son adım: sayısal bayt kaydırma. */
    private fun byteShift(text: String, magic: Long, offset: Int): String {
        val out = StringBuilder(text.length)
        for (i in text.indices) {
            val shift = (magic % (i + offset).toLong()).toInt()
            var code = text[i].code - shift
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
                    c in 'a'..'z' -> {
                        val v = c.code - 'a'.code + shift
                        (((v % 26) + 26) % 26 + 'a'.code).toChar()
                    }
                    c in 'A'..'Z' -> {
                        val v = c.code - 'A'.code + shift
                        (((v % 26) + 26) % 26 + 'A'.code).toChar()
                    }
                    else -> c
                }
            )
        }
        return out.toString()
    }

    /**
     * Çeşitli işlem sıralarıyla çoklu strateji dener.
     * ! Önce kaynaktan okunan magic/offset, sonra eski sabit (399756995 / 5).
     */
    private fun decodeCandidates(parts: List<String>, magic: Long, offset: Int): List<String> {
        val joined = parts.joinToString("")
        val out = mutableListOf<String>()

        // ! Sitenin kullandığı sıralar (atob → reverse → rot → atob vb.).
        val orders = listOf(
            listOf(Op.Atob, Op.Reverse, Op.Rot(0), Op.Atob),
            listOf(Op.Atob, Op.Reverse, Op.Atob),
            listOf(Op.Reverse, Op.Atob, Op.Atob),
            listOf(Op.Atob, Op.Atob, Op.Reverse),
            listOf(Op.Reverse, Op.Atob),
            listOf(Op.Atob, Op.Reverse)
        )
        for (order in orders) {
            for (rotShift in intArrayOf(15, 13, 8, 21, 5, 3, 12, 7, 17, 25, 1, 22)) {
                val ops = order.map { if (it is Op.Rot) Op.Rot(rotShift) else it }
                val text = applyOps(joined, ops)
                out.add(byteShift(text, magic, offset))
                out.add(byteShift(text, DEFAULT_MAGIC, DEFAULT_OFFSET))
            }
        }
        return out
    }

    // ── Yardımcılar ────────────────────────────────────────────────────────

    private val FILE_LINK_RE = Regex("""file_link\s*=\s*"([^"]+)" """.trim())
    private val PLAIN_STREAM_RE = Regex("""(https?://[^\s"'<>]+\.(?:m3u8|mp4)(?:\?[^\s"'<>]*)?)""")

    private fun quotedValues(raw: String): List<String> {
        val doubles = DOUBLE_QUOTED_RE.findAll(raw).map { it.groupValues[1] }.toList()
        if (doubles.isNotEmpty()) return doubles
        return SINGLE_QUOTED_RE.findAll(raw).map { it.groupValues[1] }.toList()
    }

    /** Çözülen adres gerçekten bir yayın adresi mi? */
    fun isValidStreamUrl(value: String?): Boolean {
        if (value.isNullOrBlank() || value.length < 10) return false
        for (c in value) {
            if (c.code <= 8 || (c.code in 14..31)) return false
        }
        if (value.startsWith("http://") || value.startsWith("https://")) return true
        return value.contains(".m3u8") || value.contains("master.txt") || value.contains(".mp4")
    }

    /**
     * Base64 çözer.
     * ! java.util.Base64 Android'de API 26+ gerektirir (minSdk 21) ve
     * ! android.util.Base64 saf-Kotlin testini engellerdi; bu yüzden elle
     * ! çözülüyor.
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

    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Dean Edwards packer açma (a=62 varyantı). */
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

    private fun toChar(x: Int): Char {
        return if (x > 35) (x + 29).toChar() else ALPHA[x]
    }

    private fun unescapeJsSingleQuoted(s: String): String {
        return s.replace("\\\\", "\u0000").replace("\\'", "'").replace("\u0000", "\\")
    }
}
