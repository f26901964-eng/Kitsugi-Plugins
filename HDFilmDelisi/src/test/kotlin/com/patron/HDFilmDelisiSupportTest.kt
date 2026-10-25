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
    fun subtitleLanguageAliasesAreNormalizedAndOtherLanguagesArePreserved() {
        assertTrue(isTargetLanguage("Türkçe"))
        assertTrue(isTargetLanguage("Turkish"))
        assertTrue(isTargetLanguage("English"))
        assertFalse(isTargetLanguage("French"))
        assertEquals("Turkish", getLanguage("tr-TR"))
        assertEquals("English", getLanguage("eng"))
        assertEquals("fr", getLanguage("fr"))
    }
}
