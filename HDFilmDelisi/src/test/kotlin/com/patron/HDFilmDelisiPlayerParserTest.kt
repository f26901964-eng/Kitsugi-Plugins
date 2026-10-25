package com.patron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HDFilmDelisiPlayerParserTest {

    @Test
    fun normalizeUrl_stripsRscAndJsonEscapes() {
        val raw = "https:\\/\\/hdfilmdelisi.one\\/vids\\/film_108.mp4\\"
        assertEquals(
            "https://hdfilmdelisi.one/vids/film_108.mp4",
            HDFilmDelisiPlayerParser.normalizeUrl(raw),
        )
    }

    @Test
    fun playerRefs_detectEscapedRscPayload() {
        val html = """
            \"videoUrl\":\"https:\/\/player.vidmody.com\/WkVoUmVrNTZ\"
            \"contentUrl\":\"https:\/\/hdfilmdelisi.one\/vids\/saplanti_108.mp4\"
            \"embedUrl\":\"https:\/\/hdfilmdelisi.one\/embed\/saplanti_108\"
        """.trimIndent()

        val refs = HDFilmDelisiPlayerParser.playerRefs(html)
        assertEquals(3, refs.size)
        assertEquals(PlayerVideoRef.Kind.VIDMODY, refs[0].kind)
        assertEquals("https://player.vidmody.com/WkVoUmVrNTZ", refs[0].url)
        assertTrue(refs.any { it.kind == PlayerVideoRef.Kind.DIRECT_MEDIA && it.url.endsWith(".mp4") })
        assertTrue(refs.any { it.kind == PlayerVideoRef.Kind.EMBED && it.url.contains("/embed/") })
    }

    @Test
    fun extractVarId_supportsDifferentDeclarations() {
        assertEquals(
            "abc123=",
            HDFilmDelisiPlayerParser.extractVarId("<script>var id = 'abc123=';</script>"),
        )
        assertEquals(
            "xyz9",
            HDFilmDelisiPlayerParser.extractVarId("<script>const id=\"xyz9\";</script>"),
        )
        assertNull(HDFilmDelisiPlayerParser.extractVarId("<html>no player</html>"))
    }

    @Test
    fun mediaFileFromScript_findsM3u8AndMp4() {
        assertEquals(
            "https://x/master.m3u8",
            HDFilmDelisiPlayerParser.mediaFileFromScript("""sources:[{"file":"https:\/\/x\/master.m3u8"}]"""),
        )
        assertEquals(
            "https://x/a.mp4?v=1",
            HDFilmDelisiPlayerParser.mediaFileFromScript("""{"file": "https://x/a.mp4?v=1"}"""),
        )
    }

    @Test
    fun hlsGroups_extractSubtitlesAndAudio_withRelativeUris() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Türkçe",DEFAULT=YES,URI="audio/tr.m3u8"
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="English",URI="https://cdn.example.com/audio/en.m3u8"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="Türkçe Altyazı",LANGUAGE="tr",URI="subs/tr.vtt"
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="English",LANGUAGE="en",URI="https://cdn.example.com/subs/en.vtt"
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1920x1080
            https://cdn.example.com/v/1080/index.m3u8
        """.trimIndent()
        val baseUrl = "https://vidmody.com/vs/abc123="

        val subtitles = HDFilmDelisiPlayerParser.hlsSubtitles(master, baseUrl)
        assertEquals(2, subtitles.size)
        assertEquals("Türkçe Altyazı", subtitles[0].label)
        assertEquals("https://vidmody.com/vs/subs/tr.vtt", subtitles[0].url)
        assertEquals("https://cdn.example.com/subs/en.vtt", subtitles[1].url)

        val audio = HDFilmDelisiPlayerParser.hlsAudioTracks(master, baseUrl)
        assertEquals(2, audio.size)
        assertEquals("Türkçe", audio[0].label)
        assertEquals("https://vidmody.com/vs/audio/tr.m3u8", audio[0].url)
        assertTrue(HDFilmDelisiPlayerParser.hasHlsAudioRenditions(master))
        assertTrue(HDFilmDelisiPlayerParser.hasHlsSubtitleRenditions(master))
        assertTrue(
            HDFilmDelisiPlayerParser.hasHlsAudioRenditions(
                "#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"aud\",NAME=\"In-band\"",
            ),
        )
    }

    @Test
    fun hlsGroups_supportAttributeOrderAndLanguageFallbacks() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID='subs',LANGUAGE='tr',URI='subs/tr.vtt'
            #EXT-X-MEDIA:URI="audio/en.m3u8",NAME="English",LANGUAGE="en",GROUP-ID="aud",TYPE="AUDIO"
        """.trimIndent()

        val subtitles = HDFilmDelisiPlayerParser.hlsSubtitles(master, "https://vidmody.com/vs/id/master.m3u8")
        assertEquals(1, subtitles.size)
        assertEquals("tr", subtitles.single().label)
        assertEquals("tr", subtitles.single().language)
        assertEquals("https://vidmody.com/vs/id/subs/tr.vtt", subtitles.single().url)

        val audio = HDFilmDelisiPlayerParser.hlsAudioTracks(master, "https://vidmody.com/vs/id/master.m3u8")
        assertTrue(HDFilmDelisiPlayerParser.hasHlsAudioRenditions(master))
        assertEquals(1, audio.size)
        assertEquals("English", audio.single().label)
        assertEquals("en", audio.single().language)
        assertEquals("https://vidmody.com/vs/id/audio/en.m3u8", audio.single().url)
    }

    @Test
    fun resolveUrl_handlesRelativeAndAbsolute() {
        val base = "https://vidmody.com/vs/abc"
        assertEquals("https://vidmody.com/vs/x.vtt", HDFilmDelisiPlayerParser.resolveUrl(base, "x.vtt"))
        assertEquals(
            "https://cdn.example.com/a.vtt",
            HDFilmDelisiPlayerParser.resolveUrl(base, "https://cdn.example.com/a.vtt"),
        )
    }
}
