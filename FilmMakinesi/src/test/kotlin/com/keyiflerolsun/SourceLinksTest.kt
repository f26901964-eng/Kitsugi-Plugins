// ! FilmMakinesi — kaynak adresi testleri.
// ! Fixture: canlı bölüm sayfası (The Mentalist S4B19) HTML'inden alınan
// ! gerçek düğme/iframe değerleri.

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.jsoup.Jsoup

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
    fun `sayfadan data iframe script ve dogrudan yayin adaylari toplanir`() {
        val html = """
            <div class="video-parts">
              <a href="#" data-video_url="https://closeload.filmmakinesi.to/video/embed/abc/?imdb_id=tt123">Close</a>
            </div>
            <iframe data-src="https://filmmakinesi.de/rplayer/episode-1/"></iframe>
            <a href="/playerr/episode-2/">Alternate player</a>
            <video><source src="https://cdn.example.com/master.m3u8?token=abc"></video>
            <script>var stream = "https:\/\/cdn.example.com/series/episode.mp4?key=xyz";</script>
            <script>var player = {"file":"/hls/relative.m3u8?token=rel"};</script>
            <div data-video-id="not-a-url-id"></div>
            <script>var trailer = "https://www.youtube.com/embed/ignored";</script>
        """.trimIndent()

        val candidates = SourceLinks.pageCandidates(Jsoup.parse(html, pageUrl), pageUrl)

        assertTrue(candidates.contains("https://closeload.filmmakinesi.to/video/embed/abc/?imdb_id=tt123"))
        assertTrue(candidates.contains("https://filmmakinesi.de/rplayer/episode-1/"))
        assertTrue(candidates.contains("$mainUrl/playerr/episode-2/"))
        assertTrue(candidates.contains("https://cdn.example.com/master.m3u8?token=abc"))
        assertTrue(candidates.contains("https://cdn.example.com/series/episode.mp4?key=xyz"))
        assertTrue(candidates.contains("$mainUrl/hls/relative.m3u8?token=rel"))
        assertFalse(candidates.any { it.contains("not-a-url-id") || it.contains("youtube.com") })
    }

    @Test
    fun `direct stream uzantilari query ile taninir`() {
        assertTrue(SourceLinks.isDirectStreamUrl("https://cdn.example.com/master.m3u8?token=abc"))
        assertTrue(SourceLinks.isDirectStreamUrl("https://cdn.example.com/manifest.mpd?token=abc"))
        assertTrue(SourceLinks.isDirectStreamUrl("https://cdn.example.com/master.txt?token=abc"))
        assertFalse(SourceLinks.isDirectStreamUrl("https://cdn.example.com/embed/player.html"))
    }

    @Test
    fun `rplayer ve playerr yollari rapidrame cozucusune yonlenir`() {
        assertEquals(SourceLinks.Route.RAPID, SourceLinks.routeFor("https://filmmakinesi.de/rplayer/id/"))
        assertEquals(SourceLinks.Route.RAPID, SourceLinks.routeFor("https://filmmakinesi.de/playerr/id/"))
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
