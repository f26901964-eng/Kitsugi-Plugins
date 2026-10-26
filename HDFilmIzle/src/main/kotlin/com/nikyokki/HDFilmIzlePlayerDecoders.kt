package com.nikyokki

/**
 * HDFilmİzle oynatıcı ailesinde (Vidrame, Vidmoxy, SetPlay/FastPlay) görülen
 * istemci tarafı çözme algoritmalarının saf Kotlin karşılıkları.
 *
 * Bu nesne hiçbir ağ çağrısı ya da Android bağımlılığı içermez; tüm fonksiyonlar
 * yalnızca String/Number üzerinde çalıştığı için JVM birim testleriyle doğrulanabilir.
 * Referanslar:
 *  - Vidrame XOR: anthology `play/vidrame.js` ve anthology-dav `DecodeVidrameXOR`
 *  - Vidmoxy EE.dd: anthology `play/vidmoxy.js` (base64url → ters çevir → ROT13)
 *  - SPG.cerceve: anthology `play/setplay.js` ve anthology-dav `SPGDecode`
 *  - X-Sp imzası: anthology `play/fastplay.js` (FNV-1a tabanlı)
 */
object HDFilmIzlePlayerDecoders {

    // java.util.Base64 Android API 26 gerektirdiği için minSdk 21 uyumlu saf çözücü.
    private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** Base64 ya da Base64URL içeriğini, eksik "=" dolgusunu tamamlayarak çözer. */
    fun base64Decode(raw: String): ByteArray? {
        val clean = raw.trim().replace("\n", "").replace("\r", "")
        if (clean.isEmpty()) return null
        val normalized = clean.replace('-', '+').replace('_', '/')
        val padded = when (normalized.length % 4) {
            2 -> normalized + "=="
            3 -> normalized + "="
            else -> normalized
        }
        return runCatching {
            val output = ByteArray(padded.length * 3 / 4)
            var outIndex = 0
            var bitBuffer = 0
            var bits = 0
            for (ch in padded) {
                if (ch == '=') break
                val value = BASE64_ALPHABET.indexOf(ch)
                if (value < 0) return@runCatching null
                bitBuffer = (bitBuffer shl 6) or value
                bits += 6
                if (bits >= 8) {
                    bits -= 8
                    output[outIndex++] = ((bitBuffer shr bits) and 0xFF).toByte()
                }
            }
            output.copyOf(outIndex)
        }.getOrNull()
    }

    fun String.rot13(): String {
        val sb = StringBuilder(length)
        for (ch in this) {
            sb.append(
                when (ch) {
                    in 'A'..'Z' -> 'A' + (ch - 'A' + 13) % 26
                    in 'a'..'z' -> 'a' + (ch - 'a' + 13) % 26
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /**
     * Vidrame/Vidmoxy `EE.dd("...")` / `ee("...")` çağrıları.
     * Çağrı sarmalı varsa kaldırılır, ardından base64 → ters çevirme → ROT13 uygulanır.
     */
    fun decodeEeDd(tokenOrCall: String): String? {
        val token = Regex("""(?:EE\.dd|ee)\s*\(\s*["']([A-Za-z0-9+/_=\-.]+)["']\s*\)""")
            .find(tokenOrCall)?.groupValues?.get(1)
            ?: tokenOrCall.trim().trim('"', '\'')
        val bytes = base64Decode(token) ?: return null
        return String(bytes).reversed().rot13().ifBlank { null }
    }

    /**
     * Vidrame XOR çözücüsü. Sayfa kodundaki biçimler:
     *   (function(a,b){...})([102, 70, ...], "gizliAnahtar")
     *   var d = [102, 70, ...]; ... var k = "gizliAnahtar";
     *   fx: { d: [ ... ], k: "..." }   // altyazı parçaları
     *
     * Doğrulanmış formül (anthology / anthology-dav ile birebir):
     *   char = d[i] XOR key[i % key.length] XOR ((i * 17 + 13) & 255)
     */
    fun decodeVidrameXor(data: List<Int>, key: String): String {
        if (data.isEmpty() || key.isEmpty()) return ""
        val out = CharArray(data.size)
        for (i in data.indices) {
            val mixed = data[i] xor key[i % key.length].code xor ((i * 17 + 13) and 0xFF)
            out[i] = (mixed and 0xFF).toChar()
        }
        return String(out)
    }

    /**
     * SetPlay `SPG.cerceve(n, o)` çözücüsü: iki base64 parça XOR'lanır,
     * çıktı "|" karakterinden bölünür ve ilk parça (genellikle URL) alınır.
     */
    fun decodeSpg(n: String, o: String): String? {
        val a = base64Decode(n.replace("\\/", "/").replace("\\", "")) ?: return null
        val b = base64Decode(o.replace("\\/", "/").replace("\\", "")) ?: return null
        if (a.isEmpty() || b.isEmpty()) return null
        val out = ByteArray(a.size) { i ->
            (a[i].toInt() and 0xFF xor (b[i % b.size].toInt() and 0xFF)).toByte()
        }
        return String(out).substringBefore('|').ifBlank { null }
    }

    /** 32-bit FNV-1a özetini küçük harf onaltılık (hex) dize olarak döndürür. */
    fun fnv1aHex(text: String): String {
        var hash = 2166136261L
        for (ch in text) {
            hash = hash xor (ch.code and 0xFFFF).toLong()
            hash = (hash * 16777619L) and 0xFFFFFFFFL
        }
        return java.lang.Long.toHexString(hash)
    }

    /**
     * FastPlay/SetPlay `X-Sp` başlığı: "<zaman>.<rastgele base36>.<fnv1a özeti>".
     * [randomBase36] testlerde sabitlenebilsin diye dışarıdan verilir; üretimde
     * [randomBase36Token] kullanılır.
     */
    fun buildXSp(sp: String, epochSeconds: Long, randomBase36: String = randomBase36Token()): String {
        return "$epochSeconds.$randomBase36.${fnv1aHex("$sp|$epochSeconds|$randomBase36")}"
    }

    fun randomBase36Token(): String =
        java.lang.Long.toString(java.util.concurrent.ThreadLocalRandom.current().nextLong(0L, 2176782336L), 36)
}
