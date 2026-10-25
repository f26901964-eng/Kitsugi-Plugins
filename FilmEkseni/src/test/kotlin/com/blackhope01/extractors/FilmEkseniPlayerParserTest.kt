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
}
