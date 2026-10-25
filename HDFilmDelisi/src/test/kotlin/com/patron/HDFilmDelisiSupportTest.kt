package com.patron

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HDFilmDelisiSupportTest {
    @Test
    fun siteDomainIsDefinedLocally() {
        assertEquals("https://hdfilmdelisi.one", HDFilmDelisiConfig.MAIN_URL)
    }

    @Test
    fun titleSimilarityIsCaseInsensitiveAndHandlesEmptyTitles() {
        assertEquals(100, TmdbHelper.similarityRatio("Film", "film"))
        assertEquals(66, TmdbHelper.similarityRatio("abc", "abd"))
        assertEquals(100, TmdbHelper.similarityRatio("", ""))
        assertEquals(0, TmdbHelper.similarityRatio("", "abc"))
    }

    @Test
    fun subtitleLanguageFilterAcceptsTurkishAndEnglishOnly() {
        assertTrue(isTargetLanguage("Türkçe"))
        assertTrue(isTargetLanguage("Turkish"))
        assertTrue(isTargetLanguage("English"))
        assertFalse(isTargetLanguage("French"))
    }

    @Test
    fun extractsVidModyPlayerUrlFromPlainOrEscapedHtml() {
        assertEquals(
            "https://player.vidmody.com/embed/abc_123?token=x&quality=1080",
            HDFilmDelisiPlayerParser.extractVidModyUrl(
                "data-src=\"https://player.vidmody.com/embed/abc_123?token=x&quality=1080\""
            ),
        )
        assertEquals(
            "https://player.vidmody.com/embed/abc123",
            HDFilmDelisiPlayerParser.extractVidModyUrl(
                """playerUrl: "https:\/\/player.vidmody.com\/embed\/abc123"""
            ),
        )
    }

    @Test
    fun extractsHlsUrlFromQuotedOrJavaScriptPlayerConfiguration() {
        assertEquals(
            "https://media.example/hls/master.m3u8?token=abc",
            HDFilmDelisiPlayerParser.extractHlsUrl(
                """{file: 'https://media.example/hls/master.m3u8?token=abc'}"""
            ),
        )
        assertEquals(
            "https://media.example/hls/master.m3u8",
            HDFilmDelisiPlayerParser.extractHlsUrl(
                """{"file": "https:\/\/media.example\/hls\/master.m3u8"}"""
            ),
        )
    }
}
