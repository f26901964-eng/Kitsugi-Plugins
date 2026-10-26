package com.nikyokki

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.URI

/**
 * HDFilmİzle sayfalarından ve oynatıcı (Vidrame / Vidmoxy / SetPlay / FastPlay)
 * HTML'lerinden tüm video kaynaklarını çıkaran saf ayrıştırıcı.
 *
 * Tarayıcı gözlemleri ve referans uygulamalar (anthology, anthology-dav,
 * HdFilmizleLife) doğrultusunda dört kaynak biçimi bir arada desteklenir:
 *  1. Sayfadaki `let parts = [{name, lang, data:"<iframe ...>"}]` blokları
 *  2. Statik `<iframe src|data-src=...>` etiketleri
 *  3. Vidrame XOR / EE.dd ile karıştırılmış `sources` tanımları
 *  4. SetPlay `SPG.cerceve` köprüleri ve FastPlay `/manifests/` çıktıları
 *
 * Ağ çağrısı içermez; tüm fonksiyonlar JVM birim testleriyle sınanabilir.
 */
object HDFilmIzlePlayerParser {

    private val mapper = ObjectMapper()

    /** Sayfadan türetilmiş tek bir oynatıcı çerçevesi. */
    data class PageFrame(
        val url: String,
        val lang: String = "",
        val playerName: String = "",
    )

    /** Çözülmüş bir oynatıcı kaynağı (henüz ağ doğrulaması yapılmamış ham hali). */
    data class JsSource(
        val url: String,
        val type: String = "m3u8",
        val label: String = "",
        val quality: Int = 0,
    )

    data class JsTrack(
        val label: String,
        val url: String,
        val language: String = "",
    )

    data class AjaxPlayerConfig(
        val nonces: List<String>,
        val postIds: List<String>,
        val playerNames: List<String>,
    )

    private val junkFrame = Regex("""(?i)(about:blank|youtube\.com|youtube-nocookie|youtu\.be|google\.com|dailymotion\.com|trailer)""")
    private val iframeRegex = Regex("""(?i)<iframe[^>]+(?:src|data-src)\s*=\s*["']([^"']+)["']""")
    private val partsMarker = Regex("""(?:let|var|const)\s+parts\s*=\s*\[""")
    private val xorCallRegex = Regex("""\(\s*\[\s*((?:-?\d+\s*,?\s*)+)\]\s*,\s*["']([^"']+)["']\s*\)""")
    private val xorVarRegex = Regex("""var\s+d\s*=\s*\[([\d,\s-]+)\][\s\S]*?var\s+k\s*=\s*["']([^"']+)["']""")
    private val eeCallRegex = Regex("""(?:EE\.dd|ee)\s*\(\s*["']([A-Za-z0-9+/_=\-.]+)["']\s*\)""")
    private val spgRegex = Regex(
        """SPG\.cerceve\(\s*(?:["'][^"']*["']\s*,\s*)?["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)"""
    )
    private val stfKopruRegex = Regex("""STF_KOPRU\s*=\s*\{[\s\S]*?src\s*:\s*["']([^"']+)["']""")
    private val fastplayManifestRegex = Regex("""(?:src|stream)\s*:\s*["'](/manifests/[^"']+)["']""")
    private val spRegex = Regex("""["']sp["']\s*:\s*["']([^"']*)["']""")
    private val spTRegex = Regex("""["']spT["']\s*:\s*(\d+)""")
    private val subtitlesBlockRegex = Regex("""subtitles\s*:\s*(\[[\s\S]*?\])""")
    private val genericMediaRegex = Regex("""["']?(https?:\\?/\\?/[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']?""", RegexOption.IGNORE_CASE)
    private val trackTagRegex = Regex("""(?is)<track[^>]+(?:src|file)\s*=\s*["']([^"']+)["'][^>]*>""")
    private val subUrlRegex = Regex("""["']?(https?:\\?/\\?/[^"'\s<>]+?\.(?:vtt|srt)(?:\?[^"'\s<>]*)?)["']?""", RegexOption.IGNORE_CASE)
    private val nonceRegex = Regex("""data-nonce\s*=\s*["']([a-z0-9]+)["']""")
    private val videoNonceRegex = Regex("""video\s*:\s*["']([a-z0-9]+)["']""")
    private val postIdRegex = Regex("""data-post-id\s*=\s*["'](\d+)["']""")
    private val playerNameRegex = Regex("""data-player-name\s*=\s*["']([^"']+?)["']""")

    // ───────────────────────────── Sayfa seviyesi ─────────────────────────────

    /** Bir izleme sayfasının tüm HTML'inden oynatıcı çerçevelerini sırayla çıkarır. */
    fun pageFrames(html: String, baseUrl: String): List<PageFrame> {
        val ordered = LinkedHashMap<String, PageFrame>()

        fun add(rawUrl: String, lang: String = "", playerName: String = "") {
            val url = resolveUrl(rawUrl.replace("\\/", "/").replace("\\", ""), baseUrl) ?: return
            if (junkFrame.containsMatchIn(url)) return
            // JS tarafında doldurulması gereken ama ham HTML'de ölü kalan şablonlar
            // (ör. ".../player/${src}") gerçek çerçeve değildir; yanında gelen
            // statik iframe'ler (vidrame vb.) zaten ayrı toplanır.
            if (url.contains("\${") || url.contains("{{")) return
            ordered.putIfAbsent(url, PageFrame(url, lang, playerName))
        }

        // 1) parts blokları — her bir parça farklı bir oynatıcı/ses sürümüdür, hepsi alınır.
        partsMarkers(html).forEach { block ->
            for (frame in partFrames(block)) {
                add(frame.url, frame.lang, frame.playerName)
            }
        }

        // 2) Statik iframe etiketleri (src ve data-src).
        iframeRegex.findAll(html).forEach { add(it.groupValues[1]) }

        // 3) Script içinde düz metin duran oynatıcı URL'leri (güvenlik ağı).
        Regex("""["'](https?:\\?/\\?/[^"']*(?:vidrame|vidmoxy|vidmoly|setplay|fastplay|stfplay)[^"']*)["']""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { add(it.groupValues[1]) }

        return ordered.values.toList()
    }

    /** "let parts = [...]" işaretinin ardından gelen dengeli JSON dizilerini bulur. */
    private fun partsMarkers(html: String): List<String> {
        val blocks = mutableListOf<String>()
        var cursor = 0
        while (true) {
            val marker = partsMarker.find(html, cursor) ?: break
            val bracketStart = html.indexOf('[', marker.range.first)
            val slice = balancedSlice(html, bracketStart, '[', ']') ?: break
            blocks.add(slice)
            cursor = bracketStart + slice.length
        }
        return blocks
    }

    /** `parts` JSON dizisini ayrıştırır; her elemanın `data` alanından iframe çıkarır. */
    fun partFrames(jsonArrayText: String): List<PageFrame> {
        val out = mutableListOf<PageFrame>()
        val node = runCatching { mapper.readTree(jsonArrayText) }.getOrNull()
        if (node != null && node.isArray) {
            node.forEach { element ->
                val data = element.path("data").asText("")
                val src = iframeRegex.find(data)?.groupValues?.get(1)
                    ?: Regex("""(?:src|data-src)\s*=\s*\\?["']([^"']+)\\?["']""").find(data)?.groupValues?.get(1)
                if (!src.isNullOrBlank()) {
                    out.add(
                        PageFrame(
                            url = src.replace("\\/", "/"),
                            lang = element.path("lang").asText(""),
                            playerName = element.path("name").asText(""),
                        )
                    )
                }
            }
            if (out.isNotEmpty()) return out
        }

        // Jackson başarısız olursa kaçış karakterli alanları elle yakala.
        Regex("""\{[^{}]*?"data"\s*:\s*"((?:\\.|[^"\\])*)"[^{}]*?\}""").findAll(jsonArrayText).forEach { match ->
            val data = match.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
            val src = iframeRegex.find(data)?.groupValues?.get(1) ?: return@forEach
            val lang = Regex("""["']lang["']\s*:\s*["']([^"']*)["']""").find(match.value)?.groupValues?.get(1).orEmpty()
            val name = Regex("""["']name["']\s*:\s*["']([^"']*)["']""").find(match.value)?.groupValues?.get(1).orEmpty()
            out.add(PageFrame(src, lang, name))
        }
        return out
    }

    /** admin-ajax `get_video_url` çağrısı için sayfadaki nonce/post/oynatıcı izleri. */
    fun ajaxPlayerConfig(html: String): AjaxPlayerConfig {
        val nonces = LinkedHashSet<String>()
        nonceRegex.findAll(html).forEach { nonces.add(it.groupValues[1]) }
        videoNonceRegex.findAll(html).forEach { nonces.add(it.groupValues[1]) }
        val postIds = LinkedHashSet<String>().apply {
            postIdRegex.findAll(html).forEach { add(it.groupValues[1]) }
        }
        val names = LinkedHashSet<String>().apply {
            playerNameRegex.findAll(html).forEach { add(it.groupValues[1]) }
        }
        return AjaxPlayerConfig(nonces.toList(), postIds.toList(), names.toList())
    }

    // ─────────────────────── Vidrame / Vidmoxy sayfaları ───────────────────────

    /**
     * Bir Vidrame oynatıcı sayfasındaki TÜM kaynakları çözer. Tek sayfada birden
     * çok XOR çağrısı, EE.dd çağrısı ya da düz URL bulunabilir; hiçbiri atlanmaz.
     */
    fun vidrameSources(script: String): List<JsSource> {
        val out = LinkedHashMap<String, JsSource>()

        fun accept(url: String?, type: String = "m3u8", label: String = "") {
            val clean = url?.takeIf { it.startsWith("http", true) }?.replace("\\/", "/") ?: return
            val previous = out[clean]
            // Aynı URL önce etiketsiz, sonra etiketli bulunursa etiket/kaliteyi koru.
            if (previous == null || (previous.quality == 0 && qualityFromLabel(label) > 0)) {
                out[clean] = JsSource(clean, normalizeType(type), label, qualityFromLabel(label))
            }
        }

        // 1) (function(a,b){...})([sayılar], "anahtar") XOR çağrıları
        xorCallRegex.findAll(script).forEach { match ->
            accept(decodeXorPayload(match.groupValues[1], match.groupValues[2]))
        }
        // 2) var d = [...]; ... var k = "..." formu
        xorVarRegex.findAll(script).forEach { match ->
            accept(decodeXorPayload(match.groupValues[1], match.groupValues[2]))
        }
        // 3) EE.dd / ee çağrıları
        eeCallRegex.findAll(script).forEach { match ->
            accept(HDFilmIzlePlayerDecoders.decodeEeDd(match.groupValues[1]))
        }
        // 4) sources: [ {file, type, label} ] blokları — dosya ifadeleri ayrıca çözülür
        sourceObjects(script).forEach { obj ->
            val type = Regex("""type\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1) ?: "m3u8"
            val label = Regex("""label\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1).orEmpty()
            val quoted = Regex("""file\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1)
            val ee = eeCallRegex.find(obj)?.let { HDFilmIzlePlayerDecoders.decodeEeDd(it.groupValues[1]) }
            val xor = xorCallRegex.find(obj)?.let { decodeXorPayload(it.groupValues[1], it.groupValues[2]) }
            accept(ee ?: xor ?: quoted, type, label)
        }
        // 5) Güvenlik ağı: sayfada açıkça duran medya URL'leri
        genericMediaRegex.findAll(script).forEach { accept(it.groupValues[1]) }

        return out.values.toList()
    }

    /** Vidmoxy sayfaları EE.dd (base64 → ters → ROT13) ve düz `sources` kullanır. */
    fun vidmoxySources(script: String): List<JsSource> {
        val out = LinkedHashMap<String, JsSource>()
        fun accept(url: String?, type: String = "m3u8", label: String = "") {
            val clean = url?.takeIf { it.startsWith("http", true) }?.replace("\\/", "/") ?: return
            val previous = out[clean]
            if (previous == null || (previous.quality == 0 && qualityFromLabel(label) > 0)) {
                out[clean] = JsSource(clean, normalizeType(type), label, qualityFromLabel(label))
            }
        }
        eeCallRegex.findAll(script).forEach { accept(HDFilmIzlePlayerDecoders.decodeEeDd(it.groupValues[1])) }
        sourceObjects(script).forEach { obj ->
            val type = Regex("""type\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1) ?: "m3u8"
            val label = Regex("""label\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1).orEmpty()
            val quoted = Regex("""file\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1)
            val ee = eeCallRegex.find(obj)?.let { HDFilmIzlePlayerDecoders.decodeEeDd(it.groupValues[1]) }
            accept(ee ?: quoted, type, label)
        }
        genericMediaRegex.findAll(script).forEach { accept(it.groupValues[1]) }
        return out.values.toList()
    }

    private fun decodeXorPayload(numbersText: String, key: String): String? {
        val numbers = numbersText.split(",").mapNotNull { it.trim().toIntOrNull() }
        if (numbers.isEmpty()) return null
        return HDFilmIzlePlayerDecoders.decodeVidrameXor(numbers, key).takeIf { it.startsWith("http", true) }
    }

    /** `sources:` / `file:` (veya `sources =`) anahtarından sonra gelen dengeli dizi içindeki nesneleri böler. */
    fun sourceObjects(script: String): List<String> {
        val result = mutableListOf<String>()
        Regex("""\b(?:sources|file)\s*[:=]\s*\[""").findAll(script).forEach { marker ->
            val start = script.indexOf('[', marker.range.first)
            val arrayText = balancedSlice(script, start, '[', ']') ?: return@forEach
            var depth = 0
            val obj = StringBuilder()
            for (ch in arrayText) {
                when (ch) {
                    '{' -> { depth++; obj.append(ch) }
                    '}' -> {
                        depth--; obj.append(ch)
                        if (depth == 0) {
                            result.add(obj.toString())
                            obj.clear()
                        }
                    }
                    else -> if (depth > 0) obj.append(ch)
                }
            }
        }
        return result
    }

    // ───────────────────────── Altyazı parçaları ─────────────────────────

    /**
     * Oynatıcı sayfasındaki altyazıları toplar: `tracks`/`configs.tracks`
     * nesneleri (düz `file` ya da XOR `fx`), `<track>` etiketleri ve açık
     * .vtt/.srt bağlantıları. Küçük resim/önizleme parçaları elenir.
     */
    fun tracks(script: String, baseUrl: String): List<JsTrack> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<JsTrack>()

        fun add(rawUrl: String, label: String = "", language: String = "") {
            val url = resolveUrl(rawUrl.replace("\\/", "/"), baseUrl) ?: return
            val lower = url.lowercase()
            if (lower.contains("thumb") || lower.contains("sprite") || lower.contains("preview")) return
            if (seen.add(url)) {
                out.add(JsTrack(label.ifBlank { "Türkçe" }, url, language))
            }
        }

        // tracks / configs.tracks blokları — fx iç içe dizileri nedeniyle dengeli dilim şart.
        Regex("""(?:tracks|configs)\s*:\s*\[""").findAll(script).forEach { marker ->
            val start = script.indexOf('[', marker.range.first)
            val block = balancedSlice(script, start) ?: return@forEach
            balancedObjects(block).forEach { obj ->
                if (obj.contains("thumbnails", true)) return@forEach
                val label = Regex("""label\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1).orEmpty()
                val lang = Regex("""(?:lang|srclang)\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1).orEmpty()
                val plain = Regex("""file\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1)
                val fxNumbers = Regex("""fx\s*:\s*\{[\s\S]*?d\s*:\s*\[([\d,\s-]+)\][\s\S]*?k\s*:\s*["']([^"']+)["']""")
                    .find(obj)?.let { decodeXorPayload(it.groupValues[1], it.groupValues[2]) }
                (fxNumbers ?: plain)?.let { add(it, label, lang) }
            }
        }

        trackTagRegex.findAll(script).forEach { match ->
            val tag = match.value
            val label = Regex("""(?i)label\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1).orEmpty()
            val lang = Regex("""(?i)(?:srclang|lang)\s*=\s*["']([^"']*)["']""").find(tag)?.groupValues?.get(1).orEmpty()
            add(match.groupValues[1], label, lang)
        }
        subUrlRegex.findAll(script).forEach { add(it.groupValues[1]) }
        return out
    }

    // ───────────────────── SetPlay / FastPlay köprüleri ─────────────────────

    /** SetPlay sayfasındaki SPG.cerceve çiftlerini çözülmüş hedef URL'lere dönüştürür. */
    fun setPlayTargets(script: String): List<String> {
        val out = LinkedHashSet<String>()
        spgRegex.findAll(script).forEach { match ->
            HDFilmIzlePlayerDecoders.decodeSpg(match.groupValues[1], match.groupValues[2])
                ?.takeIf { it.startsWith("http", true) }
                ?.let { out.add(it.replace("\\/", "/")) }
        }
        stfKopruRegex.findAll(script).forEach { match ->
            match.groupValues[1].replace("\\/", "/").takeIf { it.startsWith("http", true) || it.startsWith("/") }
                ?.let { out.add(it) }
        }
        return out.toList()
    }

    /** FastPlay sayfasındaki manifest yolunu (ör. /manifests/...) döndürür. */
    fun fastplayManifestPath(html: String): String? =
        fastplayManifestRegex.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")

    fun fastplaySpToken(html: String): Pair<String, Long>? {
        val sp = spRegex.find(html)?.groupValues?.get(1) ?: return null
        val spT = spTRegex.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        return sp to spT
    }

    /** FastPlay `subtitles: [...]` bloğunu ayrıştırır; küçük resim parçalarını atlar. */
    fun fastplaySubtitles(html: String, baseUrl: String): List<JsTrack> {
        val marker = subtitlesBlockRegex.find(html) ?: return emptyList()
        val start = html.indexOf('[', marker.range.first)
        val block = balancedSlice(html, start) ?: return emptyList()
        val out = mutableListOf<JsTrack>()
        balancedObjects(block).forEach { obj ->
            if (obj.contains("thumbnails", true)) return@forEach
            val file = Regex("""file\s*:\s*["']([^"']+)["']""").find(obj)?.groupValues?.get(1) ?: return@forEach
            val label = Regex("""label\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1).orEmpty()
            val lang = Regex("""(?:lang|srclang)\s*:\s*["']([^"']*)["']""").find(obj)?.groupValues?.get(1).orEmpty()
            resolveUrl(file.replace("\\/", "/"), baseUrl)?.let { out.add(JsTrack(label.ifBlank { "Türkçe" }, it, lang)) }
        }
        return out
    }

    /** Bilinen özel çözücüsü olmayan sayfalar için genel medya taraması. */
    fun genericSources(html: String, baseUrl: String): List<JsSource> {
        val seen = LinkedHashSet<String>()
        val out = mutableListOf<JsSource>()
        genericMediaRegex.findAll(html).forEach { match ->
            resolveUrl(match.groupValues[1].replace("\\/", "/"), baseUrl)?.let { url ->
                if (seen.add(url)) {
                    val type = if (url.substringBefore('?').lowercase().endsWith(".mp4")) "mp4" else "m3u8"
                    out.add(JsSource(url, type))
                }
            }
        }
        return out
    }

    // ───────────────────────── Yardımcılar ─────────────────────────

    fun resolveUrl(raw: String, baseUrl: String): String? {
        val cleaned = raw.trim().replace("&amp;", "&")
        if (cleaned.isBlank() || cleaned.startsWith("data:") || cleaned.startsWith("javascript:")) return null
        return when {
            cleaned.startsWith("http://", true) || cleaned.startsWith("https://", true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> runCatching {
                val base = URI(baseUrl)
                "${base.scheme}://${base.authority}$cleaned"
            }.getOrNull()
            else -> runCatching { URI(baseUrl).resolve(cleaned).toString() }.getOrNull()
        }
    }

    /** Bir blok içindeki tüm üst seviye `{...}` nesnelerini (iç içe olanlar dahil) böler. */
    fun balancedObjects(block: String): List<String> {
        val out = mutableListOf<String>()
        var index = 0
        while (index < block.length) {
            if (block[index] == '{') {
                val slice = balancedSlice(block, index, '{', '}')
                if (slice == null) {
                    index++
                } else {
                    out.add(slice)
                    index += slice.length
                }
            } else {
                index++
            }
        }
        return out
    }

    /** [start] konumundaki açma parantezinden eşleşen kapatma parantezine kadar dilimler. */
    fun balancedSlice(text: String, start: Int, open: Char = '[', close: Char = ']'): String? {
        if (start !in text.indices || text[start] != open) return null
        var depth = 0
        var inString: Char? = null
        var escaped = false
        for (i in start until text.length) {
            val ch = text[i]
            if (inString != null) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == inString -> inString = null
                }
                continue
            }
            when (ch) {
                '\'', '"' -> inString = ch
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    fun normalizeType(raw: String): String = when {
        raw.contains("mp4", true) -> "mp4"
        raw.contains("dash", true) -> "dash"
        else -> "m3u8"
    }

    fun qualityFromLabel(label: String): Int =
        Regex("""(\d{3,4})\s*[pP]""").find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    /** parts içindeki dil kodunu kullanıcıya gösterilecek etikete çevirir. */
    fun languageLabel(lang: String): String = when (lang.lowercase().trim()) {
        "dual", "tr-dublaj", "trdublaj", "dublaj" -> "TR Dublaj"
        "tr", "tur", "turkce", "türkçe" -> "Türkçe"
        "en", "eng" -> "English"
        "sub", "altyazili", "altyazılı" -> "TR Altyazı"
        else -> lang.ifBlank { "" }
    }
}
