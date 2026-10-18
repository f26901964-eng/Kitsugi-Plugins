// ! HDFilmCehennemi — embed (oynatici) adres cozumleyici testleri.
//
// ! Bu testler "0 kaynak" hatasinin UCUNCULERINE karsi regresyondur:
//!
// ! 1) /video/{id}/ yaniti JSON'dur ve icinde KACISLI SLASH vardir
//!    (PHP json_encode varsayilaninda '/' -> '\/'). Eski regex
//!    (data-src=\\?"([^"\\]+)\") kacisli slash'te durdugu icin HIC ESLESMIYORDU.
//!
// ! 2) normalizeEmbed kendi host'unu eliyordu; oysa oynatici (/rplayer/...)
//!    site-icidir.
//!
// ! 3) Harici iframe adresindeki ?rapidrame_id=<id> site-ici /rplayer/<id>/
//!    adresine cevrilmiyordu (ve sondaki '/' yoksa sunucu 301 donduruyor).
//!
// ! Fixture: src/test/resources/fixtures/video_api_response.json

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmbedResolverTest {

    private val mainUrl = "https://www.hdfilmcehennemi.nl"
    private val embedUrl = "https://www.hdfilmcehennemi.nl/rplayer/nmlen28ewqyo/"

    private fun loadFixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/fixtures/$name")
        if (stream != null) return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        val file = File("src/test/resources/fixtures/$name")
        if (file.exists()) return file.readText(Charsets.UTF_8)
        error("Fixture bulunamadı: $name")
    }

    @Test
    fun `kacisli slashli json yanitindan embed adresi bulunur`() {
        val raw = loadFixture("video_api_response.json")
        val found = EmbedResolver.extractEmbedUrl(raw)
        assertNotNull("JSON yanitindan embed adresi bulunamadi", found)
        assertEquals(embedUrl, found)
    }

    @Test
    fun `eski regex bu yantida hic eslesmiyordu`() {
        // ! Regresyon kaniti: eski kalip gercek JSON'da null donuyordu; bu
        // ! yuzden uygulama "0 kaynak" gosteriyordu.
        val raw = loadFixture("video_api_response.json")
        val old = Regex("""data-src=\\?"([^"\\]+)\"""")
        assertNull("Eski regex artik eslesmemeliydi (duzeltildi)", old.find(raw))
    }

    @Test
    fun `site ici oynatici adresi elenmez`() {
        // ! Kendi host'unda ama oynatici yolu (/rplayer/) → KORUNMALI.
        val normalized = EmbedResolver.normalizeEmbed(embedUrl, null, mainUrl)
        assertEquals(embedUrl, normalized)
    }

    @Test
    fun `kendi hostundaki oynatici olmayan adres elenir`() {
        // ! Sonsuz dongu koruması: film sayfasının kendisi kaynak degildir.
        assertNull(EmbedResolver.normalizeEmbed("$mainUrl/insidious-out-of-the-further-23/", null, mainUrl))
    }

    @Test
    fun `gorsel adresleri kaynak sayilmaz`() {
        assertNull(EmbedResolver.normalizeEmbed("https://cdn.example.com/kapak.jpg", null, mainUrl))
    }

    @Test
    fun `rapidrame id site ici oynatici adresine cevrilir`() {
        val iframe = "https://harici-ornek.com/player/?rapidrame_id=abc123&x=1"
        assertEquals("$mainUrl/rplayer/abc123/", EmbedResolver.toPlayerUrl(iframe, mainUrl))
    }

    @Test
    fun `oynatici adresi sonunda egik cizgi alir`() {
        // ! Sondaki / yoksa sunucu 301 yonlendiriyor ve sayfa bos donuyor.
        assertEquals(
            "https://harici-ornek.com/rplayer/xyz/",
            EmbedResolver.toPlayerUrl("https://harici-ornek.com/rplayer/xyz", mainUrl)
        )
    }

    @Test
    fun `oynatici yollari taninir`() {
        assertTrue(EmbedResolver.isPlayerPath("$mainUrl/rplayer/abc/"))
        assertTrue(EmbedResolver.isPlayerPath("$mainUrl/playerr/abc/"))
        assertTrue(EmbedResolver.isPlayerPath("$mainUrl/embed/abc"))
        assertTrue(!EmbedResolver.isPlayerPath("$mainUrl/insidious-out-of-the-further-23/"))
    }

    @Test
    fun `düz html de desteklenir`() {
        // ! JSON olmayan yanıt (bazı durumlarda) doğrudan taranır.
        val html = "<iframe data-src=\"$embedUrl\" width=\"100%\"></iframe>"
        assertEquals(embedUrl, EmbedResolver.extractEmbedUrl(html))
    }
}
