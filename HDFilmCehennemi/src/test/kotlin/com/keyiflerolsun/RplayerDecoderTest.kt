// ! HDFilmCehennemi — rplayer paketli kaynak çözücü testleri.
//
// ! Bu testler CANLI SİTEDEN alınmış fixture üzerinde çalışır.
// ! Amaç: "0 kaynak" hatasının (embed bulunamaması) regresyonunu yakalamak.
// ! Kaynak: src/test/resources/fixtures/rplayer_page.html
//
// ! Not: Sitenin paketleme parametreleri HER YÜKLEMEDE değişiyor (işlem
//! sırası, tuzak fonksiyonlar, bayt-kaydırma sabiti). Bu yüzden çözücü
//! sabitlere değil, kaynaktan okunan kalıplara dayanıyor.

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RplayerDecoderTest {

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/fixtures/$name")
        if (file.exists()) return file.readText(Charsets.UTF_8)
        error("Fixture bulunamadı: $name")
    }

    /** Canlı siteden alınmış örnekte çözülmesi beklenen master m3u8. */
    private val expectedMaster =
        "https://s231.rapidrame.com/hls2/01/00035/nmlen28ewqyo_,l,n,.urlset/master.m3u8" +
            "?t=CgmeX3U5UWKNAoShv2EAf7n9L2MeIzSLNOyxm1joICo&s=1783956042&e=86400&f=179989" +
            "&srv=s430&i=0.0&sp=0&p1=s231&p2=s231"

    @Test
    fun `paketli kaynak dogru master adresine cozulur`() {
        val html = loadFixture("rplayer_page.html")
        val url = RplayerDecoder.extractStreamUrl(html)
        assertNotNull("Master adresi cozulemedi", url)
        assertEquals(expectedMaster, url)
    }

    @Test
    fun `paketli cozucu fixture kaynagini cozer`() {
        val html = loadFixture("rplayer_page.html")
        assertEquals(expectedMaster, RplayerDecoder.extractPackedStreamUrl(html))
        assertTrue("Paketli oynatici taninmali", RplayerDecoder.hasPackedPlayer(html))
    }

    @Test
    fun `paketli cozucu contentUrl'i cozmus saymaz`() {
        // ! Gerçek hata zinciri: dc_ çözülemedi → JSON-LD'deki bayat contentUrl
        // ! "kaynak" sanıldı → uygulama 404 linki oynatıcıya verdi.
        val html = """
            <html><body>
            <script>function dc_TEST(){ /* sifreli govde */ }</script>
            <script type="application/ld+json">{"contentUrl":"https://cdn.example.com/eski/master.m3u8"}</script>
            </body></html>
        """.trimIndent()
        assertNull(
            "Paketli cozucu duz contentUrl'i kaynak saymamali",
            RplayerDecoder.extractPackedStreamUrl(html)
        )
        assertTrue(RplayerDecoder.hasPackedPlayer(html))
        // ! Düz adres yalnızca son çare fonksiyonunda bulunur; eklentiler bu
        // ! fonksiyonu şifreli blok varken kullanmaz.
        assertEquals("https://cdn.example.com/eski/master.m3u8", RplayerDecoder.extractStreamUrl(html))
    }

    @Test
    fun `turkce altyazi kaydi okunur`() {
        val html = loadFixture("rplayer_page.html")
        val tracks = RplayerDecoder.extractTracks(html)
        assertEquals("Altyazi sayisi 1 olmali", 1, tracks.size)
        val track = tracks.first()
        assertEquals("tr", track.language)
        assertEquals("captions", track.kind)
        assertEquals("/srt/00035/nmlen28ewqyo_Turkish.vtt", track.file)
    }

    @Test
    fun `cozulen adres gecerli bir yayin adresidir`() {
        val html = loadFixture("rplayer_page.html")
        val url = RplayerDecoder.extractStreamUrl(html)
        assertTrue("Cozulen adres gecersiz: $url", RplayerDecoder.isValidStreamUrl(url))
    }

    @Test
    fun `bos sayfa guvenli sekilde null doner`() {
        assertEquals(null, RplayerDecoder.extractStreamUrl(""))
        assertEquals(null, RplayerDecoder.extractStreamUrl("<html></html>"))
        assertTrue(RplayerDecoder.extractTracks("<html></html>").isEmpty())
    }

    // ── Gerçek arşiv oynatıcı sayfaları ──────────────────────────────────
    // ! hdfilmcehennemi.mobi/video/embed/… sayfalarından alınmış GERÇEK
    // ! bloklardır. İkisi de aynı işlemi farklı söz dizimiyle yazıyor:
    // !   varyant 1: reverse → atob → ROT13 (mod YOK) → bayt kaydırma
    // !   varyant 2: reverse → ROT13 → atob → bayt kaydırma
    // ! Eski çözücü yalnızca "%26'lı" rot biçimini tanıdığı için ikisinde de
    // ! null dönüyordu; bu testler o regresyonu yakalar.

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
    fun `track etiketlerinden altyazi cozulur`() {
        // ! Canlı embed sayfasında altyazılar `tracks:` JSON'u değil,
        // ! göreli adresli `<track src=...>` etiketleriyle geliyor.
        val tracks = RplayerDecoder.extractTrackTags(loadFixture("embed_closeload_1.html"), "https://hdfilmcehennemi.mobi/video/embed/STE1E4AiQ5O/")
        assertEquals(3, tracks.size)
        assertEquals("https://hdfilmcehennemi.mobi/vtt/STE1E4AiQ5O-tr-7854660-insideout2015bluray1080pdualtr.vtt", tracks.last().file)
        assertEquals("Turkish", tracks.last().label)
        assertEquals("tr", tracks.last().language)
    }

    @Test
    fun `arsiv closeload varyant 2 cozulur`() {
        val html = loadFixture("embed_closeload_2.html")
        assertEquals(
            "https://hls12.playmix.uno/hls/theskeletonkey-2005-bluray-trdualmp4-WM13Evo0UqR.mp4/master.txt",
            RplayerDecoder.extractStreamUrl(html)
        )
    }
}
