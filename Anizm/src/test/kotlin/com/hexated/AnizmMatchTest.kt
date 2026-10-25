package com.hexated

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anizm başlık eşleştirme testleri.
 *
 * Amaç: "kaynak ismi ile arama bulma" zincirinin çekirdeğini ağ olmadan doğrulamak.
 * Kitsugi başlıkları İngilizce/nitelikli gelir ("The Apothecary Diaries Season 3"),
 * Anizm ise Türkçe yazar ("The Apothecary Diaries 3. Sezon"). Bu dönüşüm bozulursa
 * eklenti 0 sonuç verir — testler tam olarak bu davranışı kilitler.
 */
class AnizmMatchTest {

    @Test
    fun foldNormalisesTurkishCharacters() {
        assertEquals("istanbul da 3 sezon", AnizmMatch.fold("İstanbul'da 3. Sezon"))
        assertEquals("ciglik soguk", AnizmMatch.fold("ÇIĞLIK ŞÖĞÜK"))
        assertEquals("naruto", AnizmMatch.fold("  NARUTO  "))
        assertEquals("", AnizmMatch.fold("   "))
    }

    @Test
    fun cleanTitleStripsSiteNoise() {
        assertEquals("Naruto", AnizmMatch.cleanTitle("Naruto İzle"))
        assertEquals("One Piece", AnizmMatch.cleanTitle("One Piece - Anizm.TV"))
        assertEquals("The Apothecary Diaries 3. Sezon", AnizmMatch.cleanTitle("The Apothecary Diaries 3. Sezon 3. Bölüm İzle"))
        assertEquals("Bleach", AnizmMatch.cleanTitle("Bleach 24. Bölüm Final İzle"))
        assertNull(AnizmMatch.cleanTitle("   "))
    }

    @Test
    fun seasonAndEpisodeExtraction() {
        assertEquals(3, AnizmMatch.seasonOf("The Apothecary Diaries 3. Sezon 3. Bölüm"))
        assertEquals(2, AnizmMatch.seasonOf("Attack on Titan Season 2"))
        assertEquals(4, AnizmMatch.seasonOf("Tensei shitara Slime Datta Ken 4th Season"))
        assertNull(AnizmMatch.seasonOf("One Piece"))

        assertEquals(12, AnizmMatch.episodeOf("Naruto 12. Bölüm"))
        assertEquals(7, AnizmMatch.episodeOf("Bölüm 7"))
        assertEquals(9, AnizmMatch.episodeOf("Episode 9"))
        assertNull(AnizmMatch.episodeOf("One Piece"))
    }

    @Test
    fun scoreMatchesAcrossTurkishAndEnglishSpellings() {
        val query = "The Apothecary Diaries Season 3"
        val turkish = "The Apothecary Diaries 3. Sezon"
        val unrelated = "One Piece"
        assertTrue(AnizmMatch.score(query, turkish) > 0.8)
        assertTrue(AnizmMatch.score(query, unrelated) < 0.4)
        assertEquals(1.0, AnizmMatch.score("Naruto", "NARUTO İzle"), 0.001)
    }

    @Test
    fun scoreHandlesContainment() {
        val score = AnizmMatch.score("Naruto", "Naruto Shippuuden")
        assertTrue("kısa ad uzun adın içinde: $score", score > 0.8)
    }

    @Test
    fun queryVariantsIncludeSeasonStrippedForm() {
        val variants = AnizmMatch.queryVariants("The Apothecary Diaries Season 3")
        assertTrue("ham hâli yok: $variants", variants.contains("The Apothecary Diaries Season 3"))
        assertTrue("sezon atılmış hâli yok: $variants", variants.any { it.equals("The Apothecary Diaries", true) })
        assertTrue("varyant sayısı 4'ü aşmamalı: $variants", variants.size <= 4)
        assertEquals(variants.distinct().size, variants.size)
    }

    @Test
    fun queryVariantsDropSubtitleAfterColon() {
        val variants = AnizmMatch.queryVariants("Naruto Shippuden: The Movie")
        assertTrue(variants.any { it.equals("Naruto Shippuden", true) })
    }

    @Test
    fun rankPutsBestCandidateFirst() {
        val names = listOf("Boruto: Naruto Next Generations", "Naruto", "Naruto Shippuuden")
        val ranked = AnizmMatch.rank(names, listOf("Naruto"), { listOf(it) })
        assertEquals("Naruto", ranked.first())
        assertTrue(ranked.size >= 2)
    }

    @Test
    fun rankUsesAlternativeNames() {
        data class Item(val primary: String, val alts: List<String>)
        val items = listOf(
            Item("Kusuriya no Hitorigoto", listOf("The Apothecary Diaries", "薬屋のひとりごと")),
            Item("One Piece", emptyList())
        )
        val ranked = AnizmMatch.rank(items, listOf("The Apothecary Diaries"), { listOf(it.primary) + it.alts })
        assertEquals("Kusuriya no Hitorigoto", ranked.first().primary)
    }

    @Test
    fun rankKeepsListWhenNothingMatches() {
        val names = listOf("One Piece", "Bleach")
        val ranked = AnizmMatch.rank(names, listOf("zzzzzzzz"), { listOf(it) })
        assertEquals(names, ranked)
    }

    @Test
    fun episodePathRecognition() {
        assertEquals("one-piece", AnizmMatch.seriesSlugOf("https://puffytr.com/one-piece"))
        assertEquals("one-piece", AnizmMatch.seriesSlugOf("https://puffytr.com/one-piece-1071-bolum"))
        assertEquals(
            "tensei-shitara-slime-datta-ken-4th-season",
            AnizmMatch.seriesSlugOf("https://puffytr.com/tensei-shitara-slime-datta-ken-4th-season-24-bolum-final-izle")
        )
        assertFalse(AnizmMatch.seriesSlugOf("https://puffytr.com/kategoriler/2")?.contains("bolum") == true)
    }
}
