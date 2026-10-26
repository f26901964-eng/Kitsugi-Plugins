package com.nikyokki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Saf ayrıştırıcı/çözücü birim testleri. Tüm vektörler bağımsız olarak Python ile
 * önceden hesaplanmış sabit değerlerdir; testler kendi kendini kanıtlamaz.
 */
class HDFilmIzlePlayerParserTest {

    private val pageUrl = "https://www.hdfilmizle.to/dizi/ornek/sezon-1/bolum-1/"

    // ───────────────────────── Decoder'lar ─────────────────────────

    @Test
    fun vidrameXorMatchesIndependentVector() {
        // plaintext: https://cdn.example/stream/master.m3u8, key: secret
        val data = listOf(
            22, 15, 56, 66, 71, 44, 47, 206, 149, 176, 188, 146, 207, 247, 249, 19,
            8, 54, 41, 26, 113, 116, 148, 133, 183, 190, 139, 199, 237, 253, 12, 28,
            60, 98, 71, 39, 119, 223,
        )
        assertEquals(
            "https://cdn.example/stream/master.m3u8",
            HDFilmIzlePlayerDecoders.decodeVidrameXor(data, "secret"),
        )
    }

    @Test
    fun eeDdDecodesBase64ReverseRot13() {
        // plaintext: https://cdn.example/v/audio-tr.m3u8
        val token = "OGgzei5lZy1idnFobi9pL3J5Y3pua3IuYXFwLy86ZmNnZ3U"
        assertEquals(
            "https://cdn.example/v/audio-tr.m3u8",
            HDFilmIzlePlayerDecoders.decodeEeDd("EE.dd(\"$token\")"),
        )
        // Sarmal olmadan da çalışmalı
        assertEquals(
            "https://cdn.example/v/audio-tr.m3u8",
            HDFilmIzlePlayerDecoders.decodeEeDd(token),
        )
    }

    @Test
    fun spgXorSplitsPipeSuffix() {
        val n = "A0QEAgZJWkRWEQEBAxkKSV4fGh5aHFEEER1cFAlTQUBGDxwMXh8AEBc="
        val o = "azBwcnVzdQ=="
        assertEquals("https://fastplay.mom/watch/abc123", HDFilmIzlePlayerDecoders.decodeSpg(n, o))
    }

    @Test
    fun fnv1aUsesKnownVectorsAndBuildsXSp() {
        assertEquals("811c9dc5", HDFilmIzlePlayerDecoders.fnv1aHex(""))
        assertEquals("e40c292c", HDFilmIzlePlayerDecoders.fnv1aHex("a"))
        val xSp = HDFilmIzlePlayerDecoders.buildXSp("sp", 1700000000L, "ab12")
        val expectedHash = HDFilmIzlePlayerDecoders.fnv1aHex("sp|1700000000|ab12")
        assertEquals("1700000000.ab12.$expectedHash", xSp)
    }

    // ───────────────────────── Sayfa çerçeveleri ─────────────────────────

    @Test
    fun pageFramesMergesPartsStaticIframesAndFiltersJunk() {
        val html = """
            <script>
            let parts = [
              {"id":35914,"video_id":503466,"episode_id":null,"name":"Vidrame","lang":"dual",
               "data":"<iframe src=\"https:\/\/vidrame.pro\/vr\/c97433f1\" width=\"100%\"><\/iframe>"},
              {"id":35915,"name":"Vidmoxy","lang":"sub",
               "data":"<iframe data-src=\"https:\/\/vidmoxy.net\/e\/xy90\"><\/iframe>"}
            ];
            </script>
            <iframe src="https://vidmoly.me/embed/abc"></iframe>
            <iframe src="/local/embed/77"></iframe>
            <iframe src="https://www.youtube-nocookie.com/embed/trailer"></iframe>
            <iframe src="about:blank"></iframe>
            <iframe src="https://vidmoly.me/embed/abc"></iframe>
        """.trimIndent()

        val frames = HDFilmIzlePlayerParser.pageFrames(html, pageUrl)

        assertEquals(
            listOf(
                "https://vidrame.pro/vr/c97433f1",
                "https://vidmoxy.net/e/xy90",
                "https://vidmoly.me/embed/abc",
                "https://www.hdfilmizle.to/local/embed/77",
            ),
            frames.map { it.url },
        )
        assertEquals("dual", frames[0].lang)
        assertEquals("Vidrame", frames[0].playerName)
        assertEquals("sub", frames[1].lang)
    }

    @Test
    fun partFramesManualFallbackWorksWhenJsonIsLoose() {
        val loose = """[{"name":"SetPlay","lang":"tr","data":"<iframe src=\"https:\/\/setplay.shop\/player\/stfplay.php?t=1\"><\/iframe>"}]"""
        val frames = HDFilmIzlePlayerParser.partFrames(loose)
        assertEquals(1, frames.size)
        assertEquals("https://setplay.shop/player/stfplay.php?t=1", frames.single().url)
        assertEquals("tr", frames.single().lang)
    }

    @Test
    fun ajaxPlayerConfigCollectsNoncesPostIdsAndNames() {
        val html = """
            <div data-nonce="abc123" data-post-id="503466" data-player-name="Vidrame">
            <script>var player = { video: "nonce2", post: 1 };</script>
        """.trimIndent()
        val config = HDFilmIzlePlayerParser.ajaxPlayerConfig(html)
        assertEquals(listOf("abc123", "nonce2"), config.nonces)
        assertEquals(listOf("503466"), config.postIds)
        assertEquals(listOf("Vidrame"), config.playerNames)
    }

    // ───────────────────────── Vidrame / Vidmoxy ─────────────────────────

    @Test
    fun vidrameSourcesDecodesEveryEncodingOnThePage() {
        // XOR çağrısı → master.m3u8 (yukarıdaki sabit vektör)
        val xorCall = "(function(a,b){var o=[];})([22,15,56,66,71,44,47,206,149,176,188,146,207,247,249,19,8,54,41,26,113,116,148,133,183,190,139,199,237,253,12,28,60,98,71,39,119,223], \"secret\");"
        // var d/k formu → 720p index
        val xorVar = "var d=[4,4,58,88,86,57,46,202,152,163,177,142,150,253,226,3,17,54,39,84,61,125,136,129,226,170,202,217,167,163,88,68,118,54,83,101,122,155,213,189,231,193,142]; var k=\"anahtar\";"
        val eeToken = "OGgzei5lZy1idnFobi9pL3J5Y3pua3IuYXFwLy86ZmNnZ3U"
        val script = """
            $xorCall
            $xorVar
            var sources = [
              { file: EE.dd("$eeToken"), type: "application/x-mpegURL", label: "1080p" },
              { file: "https://cdn.example/direct/film.mp4", type: "video/mp4", label: "480p" }
            ];
        """.trimIndent()

        val sources = HDFilmIzlePlayerParser.vidrameSources(script)
        val urls = sources.map { it.url }.toSet()
        assertTrue(urls.contains("https://cdn.example/stream/master.m3u8"))
        assertTrue(urls.contains("https://cdn2.example.net/hls/720/index.m3u8"))
        assertTrue(urls.contains("https://cdn.example/v/audio-tr.m3u8"))
        assertTrue(urls.contains("https://cdn.example/direct/film.mp4"))
        assertEquals(4, sources.size)
        assertEquals(1080, sources.first { it.url.endsWith("audio-tr.m3u8") }.quality)
        assertEquals("mp4", sources.first { it.url.endsWith("film.mp4") }.type)
    }

    @Test
    fun vidmoxySourcesDecodesEeDdAndPlainSources() {
        val eeToken = "OGgzei5lZy1idnFobi9pL3J5Y3pua3IuYXFwLy86ZmNnZ3U"
        val script = """
            var player = { sources: [{ file: ee("$eeToken"), type: "m3u8" }] };
        """.trimIndent()
        val sources = HDFilmIzlePlayerParser.vidmoxySources(script)
        assertEquals(
            listOf("https://cdn.example/v/audio-tr.m3u8"),
            sources.map { it.url },
        )
    }

    @Test
    fun tracksCoverPlainXorAndHtmlTagButDropThumbnails() {
        // fx vektörü → https://cdn.example/subs/track-tr.vtt (key: secret)
        val fx = listOf(
            22, 15, 56, 66, 71, 44, 47, 206, 149, 176, 188, 146, 207, 247, 249, 19,
            8, 54, 41, 26, 113, 117, 132, 147, 249, 167, 214, 203, 239, 229, 85, 13,
            60, 98, 92, 96, 118,
        ).joinToString(",")
        val script = """
            configs: { tracks: [
              { file: "https://cdn.example/subs/plain-tr.vtt", label: "Türkçe", kind: "captions" },
              { fx: { d: [$fx], k: "secret" }, label: "Türkçe (gizli)" },
              { file: "https://cdn.example/thumbs/preview-sprite.jpg", kind: "thumbnails" }
            ]}
            <track src="/subs/en.vtt" label="English" srclang="en">
        """.trimIndent()

        val tracks = HDFilmIzlePlayerParser.tracks(script, "https://vidrame.pro/vr/x")
        val urls = tracks.map { it.url }.toSet()
        assertTrue(urls.contains("https://cdn.example/subs/plain-tr.vtt"))
        assertTrue(urls.contains("https://cdn.example/subs/track-tr.vtt"))
        assertTrue(urls.contains("https://vidrame.pro/subs/en.vtt"))
        assertTrue(tracks.none { it.url.contains("thumb") })
    }

    // ───────────────────────── SetPlay / FastPlay ─────────────────────────

    @Test
    fun setPlayTargetsDecodeSpgAndKeepsRelativeStfKopru() {
        val n = "A0QEAgZJWkRWEQEBAxkKSV4fGh5aHFEEER1cFAlTQUBGDxwMXh8AEBc="
        val o = "azBwcnVzdQ=="
        val script = """
            SPG.cerceve('izleyici', '$n', '$o');
            var STF_KOPRU = { src: "/player/manifest.php?id=9" };
        """.trimIndent()
        val targets = HDFilmIzlePlayerParser.setPlayTargets(script)
        assertTrue(targets.contains("https://fastplay.mom/watch/abc123"))
        assertTrue(targets.contains("/player/manifest.php?id=9"))
    }

    @Test
    fun fastplayManifestSpTokenAndSubtitlesAreParsed() {
        val html = """
            var player = {
              src: "/manifests/session/abc/index.m3u8?token=t&amp;v=2",
              "sp": "sp-degeri", "spT": 1700000000,
              subtitles: [
                { file: "/subs/tr.vtt", label: "Türkçe", kind: "captions" },
                { file: "/thumbs/sprite.vtt", kind: "thumbnails" }
              ]
            };
        """.trimIndent()
        assertEquals(
            "/manifests/session/abc/index.m3u8?token=t&v=2",
            HDFilmIzlePlayerParser.fastplayManifestPath(html),
        )
        assertEquals("sp-degeri" to 1700000000L, HDFilmIzlePlayerParser.fastplaySpToken(html))
        val subs = HDFilmIzlePlayerParser.fastplaySubtitles(html, "https://fastplay.mom/player/x")
        assertEquals(1, subs.size)
        assertEquals("https://fastplay.mom/subs/tr.vtt", subs.single().url)
    }

    // ───────────────────────── Yardımcılar ─────────────────────────

    @Test
    fun balancedSliceHonorsStringsAndNesting() {
        val text = """prefix ["a]", ["nested", ["deep"]], "x"] suffix"""
        val start = text.indexOf('[')
        assertEquals("""["a]", ["nested", ["deep"]], "x"]""", HDFilmIzlePlayerParser.balancedSlice(text, start))
        assertNull(HDFilmIzlePlayerParser.balancedSlice("prefix", 0))
    }

    @Test
    fun languageAndQualityHelpers() {
        assertEquals("TR Dublaj", HDFilmIzlePlayerParser.languageLabel("dual"))
        assertEquals("TR Altyazı", HDFilmIzlePlayerParser.languageLabel("sub"))
        assertEquals("", HDFilmIzlePlayerParser.languageLabel(""))
        assertEquals(720, HDFilmIzlePlayerParser.qualityFromLabel("720p Auto"))
        assertEquals(0, HDFilmIzlePlayerParser.qualityFromLabel("Auto"))
    }
}
