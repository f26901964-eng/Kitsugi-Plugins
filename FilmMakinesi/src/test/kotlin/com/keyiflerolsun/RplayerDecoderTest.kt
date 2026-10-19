package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RplayerDecoderTest {
    private fun loadFixture(): String {
        val stream = javaClass.getResourceAsStream("/fixtures/rplayer_page.html")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/fixtures/rplayer_page.html")
        check(file.exists()) { "Rplayer fixture bulunamadı" }
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
}
