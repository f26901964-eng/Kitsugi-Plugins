// ! FilmMakinesi — kaynak adresi testleri.
// ! Fixture: canlı bölüm sayfası (The Mentalist S4B19) HTML'inden alınan
// ! gerçek düğme/iframe değerleri.

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceLinksTest {

    private val mainUrl = "https://filmmakinesi.to"
    private val pageUrl = "https://filmmakinesi.to/dizi/the-mentalist-izle-2008-fm1/sezon-4/bolum-19/"

    private val closeUrl = "https://closeload.filmmakinesi.to/video/embed/sdKumgu2fZ0/?imdb_id=tt1196946"
    private val rapidUrl = "https://rapid.filmmakinesi.to/embed-h1j6349abwr5/"
    private val trailer = "https://www.youtube.com/embed/nn2Q69pSC_M"

    @Test
    fun `gercek kaynaklar aynen korunur`() {
        assertEquals(closeUrl, SourceLinks.normalize(closeUrl, mainUrl))
        assertEquals(rapidUrl, SourceLinks.normalize(rapidUrl, mainUrl))
    }

    @Test
    fun `kaynak dugmesi data-video_url degerini href yerine secer`() {
        assertEquals(rapidUrl, SourceLinks.partUrl(mainUrl, rapidUrl, "", "#", "", ""))
        assertEquals("$mainUrl/fallback", SourceLinks.partUrl(mainUrl, "#", "", "#", "/fallback", ""))
    }

    @Test
    fun `youtube fragmani kaynak sayilmaz`() {
        // ! JSON-LD'deki "video": {"embedUrl": youtube} tuzağı.
        assertNull(SourceLinks.normalize(trailer, mainUrl))
        assertFalse(SourceLinks.isUsable(trailer))
        assertFalse(SourceLinks.isUsable("https://youtu.be/xyz"))
    }

    @Test
    fun `hash ve javascript aday olmaz`() {
        assertNull(SourceLinks.normalize("#", pageUrl))
        assertNull(SourceLinks.normalize("javascript:;", pageUrl))
        assertNull(SourceLinks.normalize("JAVASCRIPT:;", pageUrl))
        assertFalse(SourceLinks.isUsable("#"))
    }

    @Test
    fun `goreli adres mutlaklastirilir`() {
        assertEquals("$mainUrl/embed/abc", SourceLinks.normalize("/embed/abc", mainUrl))
        assertEquals("$mainUrl/embed/abc", SourceLinks.normalize("embed/abc", mainUrl))
    }

    @Test
    fun `gorsel adres kaynak sayilmaz`() {
        assertFalse(SourceLinks.isUsable("https://filmmakinesi.to/uploads/postlar/afis/x.webp"))
    }

    @Test
    fun `rapid hostu rapidrame cozucusune gider`() {
        // ! Eskiden yalnızca "rapidvid"/"rapidrame" metni aranıyordu,
        // ! "rapid.filmmakinesi.to" hiçbir çözümleyiciye gitmiyordu.
        assertEquals(SourceLinks.Route.RAPID, SourceLinks.routeFor(rapidUrl))
        assertEquals(SourceLinks.Route.CLOSELOAD, SourceLinks.routeFor(closeUrl))
        assertEquals(SourceLinks.Route.RAPID, SourceLinks.routeFor("https://rapidvid.net/x"))
        assertEquals(SourceLinks.Route.RAPID, SourceLinks.routeFor("https://rplayer.site/abc/"))
        assertEquals(SourceLinks.Route.GENERIC, SourceLinks.routeFor("https://cdn.example.com/a/master.m3u8"))
    }
}
