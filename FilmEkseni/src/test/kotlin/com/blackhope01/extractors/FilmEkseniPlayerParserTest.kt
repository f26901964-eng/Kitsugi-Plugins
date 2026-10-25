package com.blackhope01.extractors

import com.blackhope01.FilmEkseniConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmEkseniPlayerParserTest {
    @Test
    fun parsesMasterPlaylistAndTurkishSubtitleFromPlayerMarkup() {
        val html = """
            jwplayer("player").setup({
              "file": "https:\/\/media.example\/hls\/master.m3u8?token=abc&amp;x=1",
              "tracks": [
                { "file": "https:\/\/media.example\/subs\/tr.vtt", "label": "Türkçe" }
              ]
            });
        """.trimIndent()

        assertEquals(
            "https://media.example/hls/master.m3u8?token=abc&x=1",
            FilmEkseniPlayerParser.masterPlaylistUrl(html),
        )
        assertEquals(
            listOf(SubtitleTrack("Türkçe", "https://media.example/subs/tr.vtt")),
            FilmEkseniPlayerParser.subtitles(html),
        )
    }

    @Test
    fun mediaFiles_collectsAllQualitiesAndFormats() {
        val html = """
            sources: [
              { file: "https:\/\/media.example\/720\/index.m3u8", label: "720p" },
              { file: "https:\/\/media.example\/1080\/index.m3u8", label: "1080p" },
              { file: "https:\/\/media.example\/direct.mp4", label: "MP4" }
            ]
        """.trimIndent()

        val files = FilmEkseniPlayerParser.mediaFiles(html)
        assertEquals(3, files.size)
        assertTrue(files.contains("https://media.example/direct.mp4"))
        assertTrue(files.all { !it.contains("\\/") })
    }

    @Test
    fun iframeSources_supportsDataSrcAndSrc() {
        val template = """
            <div><iframe data-src="https:\/\/eksenload.top\/embed\/abc" allowfullscreen></iframe></div>
            <iframe src="https:\/\/vidmoly.net\/w\/xyz"></iframe>
        """.trimIndent()

        val sources = FilmEkseniPlayerParser.iframeSources(template)
        assertEquals(
            listOf("https://eksenload.top/embed/abc", "https://vidmoly.net/w/xyz"),
            sources,
        )
    }

    @Test
    fun providerDomainIsDefinedLocally() {
        assertEquals("https://filmekseni.vip", FilmEkseniConfig.MAIN_URL)
    }

    @Test
    fun returnsNoMasterPlaylistWhenPlayerMarkupHasNoM3u8() {
        assertNull(FilmEkseniPlayerParser.masterPlaylistUrl("jwplayer.setup({file: 'video.mp4'})"))
    }

    @Test
    fun subtitlesHandleInverseKeyOrder() {
        val html = """
            tracks: [
              { label: "English", file: "https:\/\/media.example\/en.vtt" },
              { file: "https:\/\/media.example\/tr.vtt", label: "Türkçe" }
            ]
        """.trimIndent()

        val tracks = FilmEkseniPlayerParser.subtitles(html)
        assertEquals(2, tracks.size)
        assertEquals("English", tracks[0].label)
        assertEquals("https://media.example/tr.vtt", tracks[1].url)
    }

    @Test
    fun hlsGroupsIncludeEverySubtitleAndAudioRendition() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Türkçe",LANGUAGE="tr",URI="audio/tr.m3u8"
            #EXT-X-MEDIA:URI='audio/en.m3u8',NAME='English',LANGUAGE='en',GROUP-ID='aud',TYPE='AUDIO'
            #EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID="sub",NAME="Türkçe",LANGUAGE="tr",URI="subs/tr.vtt"
            #EXT-X-MEDIA:URI="https://cdn.example/subs/en.vtt",NAME="English",LANGUAGE="en",GROUP-ID="sub",TYPE="SUBTITLES"
            #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=1280x720
            720/index.m3u8
        """.trimIndent()
        val base = "https://media.example/player/master.m3u8"

        assertTrue(FilmEkseniPlayerParser.hasHlsAudioRenditions(master))
        assertTrue(FilmEkseniPlayerParser.hasHlsSubtitleRenditions(master))
        val audio = FilmEkseniPlayerParser.hlsAudioTracks(master, base)
        assertEquals(2, audio.size)
        assertEquals("Türkçe", audio[0].label)
        assertEquals("https://media.example/player/audio/tr.m3u8", audio[0].url)
        assertEquals("https://media.example/player/audio/en.m3u8", audio[1].url)

        val subtitles = FilmEkseniPlayerParser.hlsSubtitles(master, base)
        assertEquals(2, subtitles.size)
        assertEquals("https://media.example/player/subs/tr.vtt", subtitles[0].url)
        assertEquals("https://cdn.example/subs/en.vtt", subtitles[1].url)
    }

    @Test
    fun parsesNestedSubtitleObjectsAndKeepsUnknownLanguages() {
        val config = """
            player.setup({
              sources: [{ file: "https://media.example/master.m3u8" }],
              tracks: [{ label: "Français", srclang: "fr", kind: "captions", file: "https://media.example/fr.vtt" }]
            });
        """.trimIndent()

        val subtitles = FilmEkseniPlayerParser.subtitles(config)
        assertEquals(1, subtitles.size)
        assertEquals("Français", subtitles.single().label)
        assertEquals("fr", subtitles.single().language)
        assertEquals("https://media.example/fr.vtt", subtitles.single().url)
    }

    @Test
    fun mediaFilesFindsDashAndCommonDirectVideoFormats() {
        val config = """
            sources: [
              { file: "https://media.example/stream.mpd" },
              { file: "https://media.example/video.webm" },
              { file: "https://media.example/video.m4v" }
            ]
        """.trimIndent()

        assertEquals(
            listOf(
                "https://media.example/stream.mpd",
                "https://media.example/video.webm",
                "https://media.example/video.m4v",
            ),
            FilmEkseniPlayerParser.mediaFiles(config),
        )
    }

    @Test
    fun resolvesRelativeMediaAndHlsRenditionUrls() {
        val base = "https://eksenload.top/embed/abc/index.html"
        assertEquals("https://eksenload.top/embed/abc/master.m3u8", FilmEkseniPlayerParser.resolveUrl(base, "master.m3u8"))
        assertEquals("https://cdn.example/video.mp4", FilmEkseniPlayerParser.resolveUrl(base, "https://cdn.example/video.mp4"))
    }
}
