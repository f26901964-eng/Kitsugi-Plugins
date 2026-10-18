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
}
