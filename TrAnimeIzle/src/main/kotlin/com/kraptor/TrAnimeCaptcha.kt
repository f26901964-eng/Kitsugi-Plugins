package com.kraptor

import java.security.MessageDigest

/**
 * IconCaptcha v2 doğrulamasının **saf** (ağsız, Android'siz) mantığı.
 *
 * Sitenin Bot Kontrol sayfası `iconCaptcha({…, validationPath:"/api/Captcha/"})`
 * çağırıyor; eklenti aynı protokolü HTTP ile uyguluyor:
 *
 * ```
 * POST  /api/Captcha/                       form: cID, rT=1, tM   → ikon kimlikleri
 * GET   /api/Captcha/?cid={cID}&hash={id}   ×5                      → 5 PNG
 * POST  /api/Captcha/                       form: cID, pC={id}, rT=2 → doğrulama
 * ```
 *
 * Beş ikonun dördü **aynı** görseldir, biri farklıdır; sunucu aynı görsel için
 * aynı baytları döndürdüğü için MD5 grupları ayrıştırmayı çözer.
 *
 * Bu dosya `org.json` kullanmaz: `org.json` çalışma zamanında Android'e aittir
 * ve JVM birim testlerinde "not mocked" istisnası fırlatır. Buradaki mini
 * ayrıştırıcı saf Kotlin'dir → testler gerçek yanıt biçimleriyle koşabilir.
 *
 * Yanıt biçimi sürümler arasında değişiyor:
 * * dizi: `["h1","h2","h3","h4","h5"]`
 * * sayı anahtarlı nesne: `{"0":"h1","1":"h2",…}`  ← v2.5.0'ın ürettiği biçim
 * * sarmalı nesne: `{"data":[…]}`, `{"hashes":{…}}`, `{"icons":[…]}`
 */
internal object TrCaptcha {

    /** Tanınan sarmal anahtarları (ilk bulunan kullanılır). */
    private val WRAPPER_KEYS = listOf(
        "data", "hashes", "icons", "result", "items", "tokens", "captcha", "images"
    )

    /** `rT=1` yanıtındaki ikon kimlikleri (sunucunun verdiği sıra korunur). */
    fun hashes(body: String?): List<String> {
        val raw = body?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()

        val node = runCatching { MiniJson(raw).parse() }.getOrNull()
        if (node != null) {
            val parsed = collect(node)
            if (!parsed.isNullOrEmpty()) return parsed
        }

        // Ayrıştırma başarısız olduysa: yanıtın içindeki hash benzeri dizgeler.
        // (Yalnızca son çare; sıralama bozulabilir ama çözüm yine de denenir.)
        return Regex("\"([A-Za-z0-9_+/-]{12,120})\"")
            .findAll(raw)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filterNot { it.all(Char::isDigit) }
            .distinct()
            .take(8)
            .toList()
    }

    private fun collect(node: Any?): List<String>? = when (node) {
        is List<*> -> node.mapNotNull { item ->
            (item as? String)?.trim()?.takeIf(String::isNotBlank)
        }.takeIf { it.isNotEmpty() }
        is Map<*, *> -> {
            val wrapped = WRAPPER_KEYS.firstNotNullOfOrNull { key -> node[key]?.let { collect(it) } }
            wrapped ?: numericOrdered(node)
        }
        is String -> listOf(node).takeIf { node.isNotBlank() }
        else -> null
    }

    /** `{"0":"a","1":"b"}` → anahtar sırasına göre değerler. */
    private fun numericOrdered(node: Map<*, *>): List<String>? {
        val entries = node.entries
            .mapNotNull { entry ->
                val index = (entry.key as? String)?.toIntOrNull() ?: return@mapNotNull null
                val value = (entry.value as? String)?.trim()?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
                index to value
            }
            .sortedBy { it.first }
        return entries.map { it.second }.takeIf { it.isNotEmpty() }
    }

    /**
     * "Farklı olan" ikonun kimliğini döndürür; ayırt edilemezse `null`.
     *
     * 1. **MD5**: 4 aynı + 1 farklı → tek kalan kazanır (kesin yöntem).
     * 2. Sunucu görsellere gürültü eklediyse boyut kümeleri denenir (kaba ama
     *    işe yarar: aynı görsel aynı boyutta kodlanır).
     * 3. Hiçbiri ayrışmazsa `null` — çağıran yeni bir captcha ister.
     */
    fun oddOneOut(hashes: List<String>, icons: List<ByteArray?>): String? {
        if (hashes.size < 3 || hashes.size != icons.size) return null
        val pairs = hashes.indices.mapNotNull { index ->
            val bytes = icons.getOrNull(index)?.takeIf { it.size > 64 } ?: return@mapNotNull null
            hashes[index] to bytes
        }
        if (pairs.size != hashes.size) return null

        uniqueBy(pairs) { md5Hex(it.second) }?.let { return it }
        for (bucket in listOf(16, 64, 256, 1024)) {
            uniqueBy(pairs) { (it.second.size / bucket).toString() }?.let { return it }
        }
        return null
    }

    private fun uniqueBy(
        pairs: List<Pair<String, ByteArray>>,
        key: (Pair<String, ByteArray>) -> String
    ): String? {
        val groups = pairs.groupBy(key)
        if (groups.size < 2) return null
        return groups.values.filter { it.size == 1 }.singleOrNull()?.first()?.first
    }

    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Sayfadaki `captchaTheme: ["dark"]` değeri (`tM` alanına gider).
     * Bulunamazsa `null`.
     */
    fun theme(html: String?): String? {
        val text = html ?: return null
        val match = Regex("""captchaTheme\s*:\s*\[\s*["'](\w+)["']""", RegexOption.IGNORE_CASE)
            .find(text) ?: return null
        return match.groupValues.getOrNull(1)?.lowercase()?.takeIf { it in setOf("dark", "light") }
    }

    /** Gövde gerçekten captcha kapısı mı? (yalnızca kesin işaretler) */
    fun isChallenge(html: String?): Boolean {
        val text = html ?: return false
        if (text.isEmpty() || text.length > 400_000) return false
        return text.contains("captcha-holder", true) ||
            text.contains("iconCaptcha(", false) ||
            text.contains("iconCaptcha (", false)
    }

    /**
     * Küçük, bağımlılıksız JSON okuyucu — yalnızca captcha yanıtları için.
     * Desteklenen tipler: nesne, dizi, dizge, sayı, `true/false/null`.
     * Bozuk gövdede `null` döner (istisna fırlatmaz).
     */
    private class MiniJson(private val text: String) {
        private var index = 0

        fun parse(): Any? {
            skipSpace()
            val value = readValue()
            skipSpace()
            return value
        }

        private fun readValue(): Any? {
            if (index >= text.length) return null
            return when (val char = text[index]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    if (char.isDigit() || char == '-' || char == '+') readNumber()
                    else null
                }
            }
        }

        private fun readObject(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            index++ // {
            skipSpace()
            if (peek() == '}') { index++; return out }
            while (index < text.length) {
                skipSpace()
                val key = if (peek() == '"') readString() else break
                skipSpace()
                if (peek() != ':') break
                index++
                skipSpace()
                out[key] = readValue()
                skipSpace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return out }
                    else -> return out
                }
            }
            return out
        }

        private fun readArray(): List<Any?> {
            val out = ArrayList<Any?>()
            index++ // [
            skipSpace()
            if (peek() == ']') { index++; return out }
            while (index < text.length) {
                skipSpace()
                out.add(readValue())
                skipSpace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return out }
                    else -> return out
                }
            }
            return out
        }

        private fun readString(): String {
            index++ // "
            val builder = StringBuilder()
            while (index < text.length) {
                when (val char = text[index++]) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (index >= text.length) break
                        when (val escaped = text[index++]) {
                            'n' -> builder.append('\n')
                            't' -> builder.append('\t')
                            'r' -> builder.append('\r')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'u' -> {
                                val hex = text.substring(index, minOf(index + 4, text.length))
                                index += hex.length
                                builder.append(hex.toIntOrNull(16)?.toChar() ?: ' ')
                            }
                            else -> builder.append(escaped)
                        }
                    }
                    else -> builder.append(char)
                }
            }
            return builder.toString()
        }

        private fun readNumber(): Double {
            val start = index
            while (index < text.length) {
                val char = text[index]
                if (char.isDigit() || char in "-+.eE") index++ else break
            }
            return text.substring(start, index).toDoubleOrNull() ?: 0.0
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            if (text.startsWith(word, index)) index += word.length
            return value
        }

        private fun peek(): Char = if (index < text.length) text[index] else '\u0000'

        private fun skipSpace() {
            while (index < text.length && text[index].isWhitespace()) index++
        }
    }
}
