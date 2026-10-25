package com.nikyokki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HDFilmIzleParserTest {
    @Test
    fun identifiesObservedSeriesAndMovieRoutes() {
        assertTrue(HDFilmIzleParser.isSeriesUrl("https://www.hdfilmizle.to/dizi/pluribus/"))
        assertTrue(HDFilmIzleParser.isSeriesUrl("https://www.hdfilmizle.to/dizi/pluribus/sezon-1/bolum-1/"))
        assertFalse(HDFilmIzleParser.isSeriesUrl("https://www.hdfilmizle.to/bicaklar-cekildi-olu-adamin-uyanisi/"))
    }

    @Test
    fun parsesObservedSeasonAndEpisodeRoute() {
        assertEquals(
            HDFilmIzleEpisode(
                url = "https://www.hdfilmizle.to/dizi/pluribus/sezon-1/bolum-1/",
                season = 1,
                episode = 1,
                name = "1. Sezon 1. Bölüm",
            ),
            HDFilmIzleParser.parseEpisodeUrl(
                "https://www.hdfilmizle.to/dizi/pluribus/sezon-1/bolum-1/",
            ),
        )
    }

    @Test
    fun rejectsSeriesDetailsAndMovieRoutesAsEpisodeLinks() {
        assertNull(HDFilmIzleParser.parseEpisodeUrl("https://www.hdfilmizle.to/dizi/pluribus/"))
        assertNull(HDFilmIzleParser.parseEpisodeUrl("https://www.hdfilmizle.to/bicaklar-cekildi-olu-adamin-uyanisi/"))
    }
}
