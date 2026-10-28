package com.hexated

/**
 * Dean Edwards (packer) `eval(function(p,a,c,k,e,d){…}('…',a,c,'k1|k2…'.split('|'),0,{}))`
 * açıcısı — saf Kotlin, Android/jsoup bağımlılığı YOK, birim testleri ağsız koşar.
 *
 * NEDEN VAR: anizmplayer.com (Aincrad/FirePlayer) oynatıcı sayfaları akış adresini
 * düz HTML'de taşımaz; sayfadaki TEK packed script açıldığında
 *
 *     function fireload(source=null){FirePlayer("<32 hex hash>",{ … })}
 *
 * çıkar. Bağımsız üç istemci de aynı yolu kullanır (kanıt):
 *  - CloudStream `Saloo1575/SalooRepo` Anizm: unpackPackedEvalJs → FirePlayer anahtarı
 *  - `falsisdev/anthology` providers/anizm.js: unpackDeanEdwards → FirePlayer
 *  - Aniyomi `yuzono/anime-extensions`: URL hash'i ile POST (yedek olarak)
 *
 * Eklenti öncelikle URL hash'i ile doğrudan `getVideo` POST'u atar; bu uç bir
 * güvenlik duvarı yüzünden cevap vermediğinde paketli sayfa açılarak anahtar
 * buradan çıkarılır (hash yoldan farklılaşsa bile zincir çalışmaya devam eder).
 */
internal object AnizmPacker {

    /** Packed çağrı imzası: `eval(function(p,a,c,k,e,d)` veya `…(p,a,c,k,e,r)`. */
    private val packedStart = Regex(
        """eval\(function\(p,a,c,k,e,[rd]\)\s*\{""",
        RegexOption.IGNORE_CASE
    )

    /** Çağrı kuyruğu: `,'sözlük'.split('|')` (tırnak işareti geri referansla eşlenir). */
    private val tailRegex = Regex(
        """^\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\3\s*[,\)]""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private const val PAYLOAD_END_MARKER = "return p}('"

    /**
     * Belgedeki TÜM packed çağrıları açıp birleştirir; packed çağrı yoksa `null`.
     * Tek bir sayfada birden fazla paket (reklam + oynatıcı) olabilir.
     */
    fun unpack(html: String?): String? {
        if (html.isNullOrBlank() || !packedStart.containsMatchIn(html)) return null
        val out = StringBuilder()
        for (start in packedStart.findAll(html)) {
            val marker = html.indexOf(PAYLOAD_END_MARKER, start.range.first)
            if (marker < 0) continue
            val payloadStart = marker + PAYLOAD_END_MARKER.length
            // Açış tırnağı HANGİSİYSE yalnızca o kapatır: paket genellikle tek
            // tırnakla sarılır ve gövdesinde ham çift tırnaklar ({"R":…}) bulunur.
            // İşaretçi (`}('`) açış tırnağını da içerdiğinden tırnak bir önceki
            // karakterdedir.
            val opening = html.getOrNull(payloadStart - 1) ?: continue
            if (opening != '\'' && opening != '"') continue
            var i = payloadStart
            val payloadBuf = StringBuilder()
            while (i < html.length) {
                val ch = html[i]
                if (ch == '\\' && i + 1 < html.length) {
                    payloadBuf.append(ch)
                    payloadBuf.append(html[i + 1])
                    i += 2
                    continue
                }
                if (ch == opening) break
                payloadBuf.append(ch)
                i++
            }
            if (i >= html.length) continue
            val tail = tailRegex.find(html.substring(i + 1)) ?: continue
            val radix = tail.groupValues[1].toIntOrNull() ?: continue
            val count = tail.groupValues[2].toIntOrNull() ?: continue
            if (radix !in 2..62 || count <= 0 || count > 100_000) continue
            val keywords = tail.groupValues[4].split('|')
            var payload = payloadBuf.toString()
                .replace("\\x27", "'")
                .replace("\\x22", "\"")
                .replace("\\\\", "\\")
            // Packer sözlüğü sondan başa uygulanır (önce uzun/özel anahtarlar).
            for (n in (count - 1) downTo 0) {
                val key = baseN(n, radix)
                val value = keywords.getOrNull(n)?.takeIf { it.isNotEmpty() } ?: key
                if (value != key) {
                    payload = Regex("""\b""" + Regex.escape(key) + """\b""").replace(payload, value)
                }
            }
            out.append(payload).append('\n')
        }
        return out.toString().ifBlank { null }
    }

    /** Açılmış betikteki `FirePlayer("…")` ilk argümanı (oynatıcı veri anahtarı). */
    fun firePlayerKey(unpacked: String?): String? {
        if (unpacked.isNullOrBlank()) return null
        return Regex("""FirePlayer\(\s*['"]([A-Za-z0-9_-]{6,64})['"]""")
            .find(unpacked)?.groupValues?.getOrNull(1)
    }

    /**
     * `value` sayısını packer'ın taban gösterimine çevirir (radix 10..62;
     * alfasayısal takım: 0-9a-z ardından büyük harfler).
     */
    private fun baseN(value: Int, radix: Int): String {
        if (radix == 10) return value.toString()
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var current = value
        val sb = StringBuilder()
        do {
            sb.insert(0, chars[current % radix])
            current /= radix
        } while (current > 0)
        return sb.toString()
    }
}
