package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TrAnimeIzle başlık eşleştirme testleri (ağsız).
 *
 * Senaryolar gerçek kullanıcı akışından: Kitsugi başlığı İngilizce gönderiyor
 * ("The Apothecary Diaries Season 3"), site ise Türkçe/romanize adlar ve
 * "3. Sezon 12. Bölüm İzle" sonekleri kullanıyor.
 */
class TrAnimeIzleMatchTest {

    // ------------------------------------------------------------------
    // Normalizasyon
    // ------------------------------------------------------------------

    @Test
    fun foldHandlesTurkishCharactersAndPunctuation() {
        assertEquals("istanbul da 3 sezon", TrAnimeIzleMatch.fold("İstanbul'da 3. Sezon"))
        assertEquals("turkce altyazili", TrAnimeIzleMatch.fold("Türkçe Altyazılı"))
        assertEquals("soguk gunler", TrAnimeIzleMatch.fold("SOĞUK GÜNLER"))
        assertEquals("", TrAnimeIzleMatch.fold("   "))
    }

    @Test
    fun cleanTitleStripsSiteSuffixesButKeepsSeason() {
        assertEquals("One Piece", TrAnimeIzleMatch.cleanTitle("One Piece 1120. Bölüm İzle"))
        assertEquals(
            "The Apothecary Diaries 3. Sezon",
            TrAnimeIzleMatch.cleanTitle("The Apothecary Diaries 3. Sezon 12. Bölüm İzle")
        )
        assertEquals("Bleach", TrAnimeIzleMatch.cleanTitle("Bleach - TrAnimeIzle"))
        assertEquals("Jujutsu Kaisen", TrAnimeIzleMatch.cleanTitle("Jujutsu Kaisen Final İzle"))
        assertEquals("Naruto", TrAnimeIzleMatch.cleanTitle("Naruto (Türkçe Altyazılı)"))
        assertNull(TrAnimeIzleMatch.cleanTitle("   "))
        assertNull(TrAnimeIzleMatch.cleanTitle(null))
    }

    @Test
    fun seasonAndEpisodeExtraction() {
        assertEquals(3, TrAnimeIzleMatch.seasonOf("The Apothecary Diaries Season 3"))
        assertEquals(3, TrAnimeIzleMatch.seasonOf("The Apothecary Diaries 3. Sezon 12. Bölüm"))
        assertEquals(4, TrAnimeIzleMatch.seasonOf("Attack on Titan 4th Season"))
        assertNull(TrAnimeIzleMatch.seasonOf("One Piece"))

        assertEquals(1120, TrAnimeIzleMatch.episodeOf("One Piece 1120. Bölüm"))
        assertEquals(12, TrAnimeIzleMatch.episodeOf("Bölüm 12"))
        assertEquals(7, TrAnimeIzleMatch.episodeOf("Episode 7"))
        assertNull(TrAnimeIzleMatch.episodeOf("One Piece"))
    }

    @Test
    fun urlHelpers() {
        val base = "https://www.tranimeizle.io"
        assertEquals(1120, TrAnimeIzleMatch.episodeFromUrl("$base/one-piece-1120-bolum-izle"))
        assertNull(TrAnimeIzleMatch.episodeFromUrl("$base/one-piece-izle"))
        assertEquals("$base/one-piece", TrAnimeIzleMatch.seriesUrlOf("$base/one-piece-1120-bolum-izle"))
        assertEquals("one-piece", TrAnimeIzleMatch.seriesKey("$base/one-piece-1120-bolum-izle"))
        assertEquals("one-piece", TrAnimeIzleMatch.seriesKey("$base/one-piece-izle"))
        assertEquals("one-piece", TrAnimeIzleMatch.seriesKey("$base/animeizle/one-piece-anime-izle-2"))
        assertEquals("One Piece", TrAnimeIzleMatch.titleFromSlug("$base/one-piece-izle"))
    }

    // ------------------------------------------------------------------
    // Skor / sıralama
    // ------------------------------------------------------------------

    @Test
    fun scoreIsLanguageAndSuffixTolerant() {
        assertTrue(
            TrAnimeIzleMatch.score(
                "The Apothecary Diaries Season 3",
                "The Apothecary Diaries 3. Sezon 12. Bölüm İzle"
            ) > 0.9
        )
        assertTrue(
            TrAnimeIzleMatch.score("Attack on Titan", "Attack on Titan 4. Sezon") > 0.9
        )
        // Küçük yazım farkı (Shippuden / Shippuuden) eşleşmeli.
        assertTrue(TrAnimeIzleMatch.score("Naruto Shippuden", "Naruto Shippuuden") > 0.8)
        // Farklı animeler düşük skor almalı.
        assertTrue(TrAnimeIzleMatch.score("One Piece", "Bleach") < 0.2)
    }

    @Test
    fun queryVariantsCoverSeasonAndColonForms() {
        val variants = TrAnimeIzleMatch.queryVariants("The Apothecary Diaries Season 3")
        assertTrue(variants.contains("The Apothecary Diaries Season 3"))
        assertTrue(variants.any { it.equals("The Apothecary Diaries", ignoreCase = true) })

        val colon = TrAnimeIzleMatch.queryVariants("Naruto Shippuden: The Movie")
        assertTrue(colon.any { it.equals("Naruto Shippuden", ignoreCase = true) })

        assertEquals(emptyList<String>(), TrAnimeIzleMatch.queryVariants("a"))
    }

    @Test
    fun rankPutsBestMatchFirstAndFiltersNoise() {
        val candidates = listOf(
            Triple("Bleach", "u1", emptyList<String>()),
            Triple("One Piece 1120. Bölüm İzle", "u2", emptyList<String>()),
            Triple("One Piece Film: Red", "u3", emptyList<String>())
        )
        val ranked = TrAnimeIzleMatch.rank("One Piece", candidates)
        assertEquals("u2", ranked.first().first)
        assertTrue(ranked.none { it.first == "u1" })
    }

    @Test
    fun rankFallsBackToTopThreeWhenNothingPassesThreshold() {
        val candidates = listOf(
            Triple("Naruto", "u1", emptyList<String>()),
            Triple("Bleach", "u2", emptyList<String>())
        )
        val ranked = TrAnimeIzleMatch.rank("Death Note", candidates)
        assertTrue(ranked.size in 1..3)
        assertTrue(ranked.all { it.second >= 0.0 })
    }

    @Test
    fun invalidTitlesRejectedButSingleWordNamesKept() {
        assertTrue(TrAnimeIzleMatch.isInvalidTitle("İzle"))
        assertTrue(TrAnimeIzleMatch.isInvalidTitle("arama"))
        assertTrue(TrAnimeIzleMatch.isInvalidTitle("1120"))
        assertTrue(!TrAnimeIzleMatch.isInvalidTitle("Bleach"))
        assertTrue(!TrAnimeIzleMatch.isInvalidTitle("Monster"))
    }
}
