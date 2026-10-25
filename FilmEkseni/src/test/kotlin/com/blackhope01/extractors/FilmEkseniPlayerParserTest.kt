package com.blackhope01.extractors

import com.blackhope01.FilmEkseniConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
    fun providerDomainIsDefinedLocally() {
        assertEquals("https://filmekseni.vip", FilmEkseniConfig.MAIN_URL)
    }

    @Test
    fun returnsNoMasterPlaylistWhenPlayerMarkupHasNoM3u8() {
        assertNull(FilmEkseniPlayerParser.masterPlaylistUrl("jwplayer.setup({file: 'video.mp4'})"))
    }
}
