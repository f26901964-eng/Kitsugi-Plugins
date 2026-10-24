package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RplayerDecoderTest {
    private fun loadFixture(name: String = "rplayer_page.html"): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/fixtures/$name")
        check(file.exists()) { "Fixture bulunamadı: $name" }
        return file.readText(Charsets.UTF_8)
    }

    @Test
    fun `paketli rplayer kaynagi ve imzali query aynen korunur`() {
        val result = RplayerDecoder.extractStreamUrl(loadFixture())
        assertNotNull("Paketli rplayer kaynağı çözülemedi", result)
        assertEquals(
            "https://s231.rapidrame.com/hls2/01/00035/nmlen28ewqyo_,l,n,.urlset/master.m3u8" +
                "?t=CgmeX3U5UWKNAoShv2EAf7n9L2MeIzSLNOyxm1joICo&s=1783956042&e=86400&f=179989" +
                "&srv=s430&i=0.0&sp=0&p1=s231&p2=s231",
            result
        )
    }

    @Test
    fun `paketli cozucu contentUrl'i cozmus saymaz`() {
        // ! dc_ çözülemediğinde bayat contentUrl'in kaynak sanılmasını engeller.
        val html = """
            <html><body>
            <script>function dc_TEST(){ /* sifreli govde */ }</script>
            <script type="application/ld+json">{"contentUrl":"https://cdn.example.com/eski/master.m3u8"}</script>
            </body></html>
        """.trimIndent()
        assertNull(RplayerDecoder.extractPackedStreamUrl(html))
        assertTrue(RplayerDecoder.hasPackedPlayer(html))
        assertEquals("https://cdn.example.com/eski/master.m3u8", RplayerDecoder.extractStreamUrl(html))
    }

    @Test
    fun `rplayer altyazilari da cikarilir`() {
        val tracks = RplayerDecoder.extractTracks(loadFixture())
        assertEquals(1, tracks.size)
        assertEquals("/srt/00035/nmlen28ewqyo_Turkish.vtt", tracks.single().file)
    }

    // ! FilmMakinesi'nin closeload embed'i ile HDFilmCehennemi'nin mobi
    // ! embed'i AYNI oynatıcı ailesini kullanır; gerçek arşiv sayfalarıyla
    // ! iki farklı söz dizimi de çözülmelidir.
    @Test
    fun `arsiv closeload varyant 1 cozulur`() {
        val html = loadFixture("embed_closeload_1.html")
        assertTrue(RplayerDecoder.hasPackedPlayer(html))
        assertEquals(
            "https://balancehls12.playmix.uno/hls/insideout2015bluray1080pdualmp4-STE1E4AiQ5O.mp4/master.txt",
            RplayerDecoder.extractPackedStreamUrl(html)
        )
    }

    @Test
    fun `arsiv closeload varyant 2 cozulur`() {
        assertEquals(
            "https://hls12.playmix.uno/hls/theskeletonkey-2005-bluray-trdualmp4-WM13Evo0UqR.mp4/master.txt",
            RplayerDecoder.extractStreamUrl(loadFixture("embed_closeload_2.html"))
        )
    }
}
