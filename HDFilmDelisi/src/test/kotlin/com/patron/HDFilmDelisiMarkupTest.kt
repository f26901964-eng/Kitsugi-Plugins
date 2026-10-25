package com.patron

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gerçek hdfilmdelisi.one sayfalarından yakalanmış HTML fixture'ları ile
 * Next.js/RSC yapısının doğru ayrıştırıldığını doğrular.
 */
class HDFilmDelisiMarkupTest {

    private val baseUrl = "https://hdfilmdelisi.one"

    private fun resource(name: String): String {
        val stream = javaClass.getResourceAsStream(name)
        assertNotNull("Fixture bulunamadı: $name", stream)
        return stream!!.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    @Test
    fun homePageCards_includeMoviesAndSeries() {
        val document = Jsoup.parse(resource("/hdfilmdelisi_home.html"), baseUrl)
        val cards = HDFilmDelisiMarkup.parseCards(document)

        assertTrue("Ana sayfada kart bulunamadı", cards.size >= 30)

        val first = cards.first()
        assertEquals("Saplantı", first.title)
        assertTrue(first.href.contains("/film/saplanti-obsession"))
        assertFalse(first.isSeries)
        assertNotNull(first.poster)

        val breakingBad = cards.firstOrNull { it.title == "Breaking Bad" }
        assertNotNull("Dizi kartı bulunamadı", breakingBad)
        assertTrue(breakingBad!!.isSeries)
        assertTrue(breakingBad.href.contains("/dizi/breaking-bad"))
    }

    @Test
    fun detailPage_metadata_isExtractedFromRealMarkup() {
        val document = Jsoup.parse(resource("/hdfilmdelisi_detail.html"), "$baseUrl/film/saplanti-obsession")
        val detail = HDFilmDelisiMarkup.parseDetail(document)

        assertEquals("Saplantı", detail.title)
        assertEquals(2025, detail.year)
        assertEquals(8.1, detail.rating ?: 0.0, 0.001)
        assertEquals(108, detail.duration)
        assertNotNull(detail.poster)
        assertTrue(detail.poster!!.contains("/uploads/posters/"))
        assertNotNull(detail.plot)
        assertTrue(detail.tags.contains("Gerilim"))
        assertTrue(detail.tags.contains("Korku"))
        // Navigasyon menüsündeki tüm türler karışmamalı
        assertTrue("İçerik türleri menü ile kirlenmiş", detail.tags.size < 10)

        val curry = detail.actors.firstOrNull { it.name == "Curry Barker" }
        assertNotNull("Oyuncu bulunamadı", curry)
        assertNotNull(curry!!.image)
        assertEquals("Yönetmen, Senarist", curry.role)
    }

    @Test
    fun detailPage_playerReferences_coverAllThreeSources() {
        val html = resource("/hdfilmdelisi_detail.html")
        val refs = HDFilmDelisiPlayerParser.playerRefs(html)
        val urls = refs.map { it.url }.toSet()

        assertTrue(urls.any { it.contains("player.vidmody.com/") })
        assertTrue(urls.any { it.endsWith(".mp4") && it.contains("/vids/") })
        assertTrue(urls.any { it.contains("/embed/") })
        assertTrue(refs.none { it.url.contains("\\") })
        assertTrue(refs.none { it.url.contains("\"") })
    }

    @Test
    fun fallbackSlugs_areDeduplicatedAndTyped() {
        val html = """
            something "/film/abc-123" x "/dizi/breaking-bad" y "/film/abc-123"
        """.trimIndent()
        val slugs = HDFilmDelisiMarkup.fallbackSlugs(html, baseUrl)
        assertEquals(2, slugs.size)
        assertTrue(slugs.any { it.isSeries && it.href.endsWith("/dizi/breaking-bad") })
    }
}
