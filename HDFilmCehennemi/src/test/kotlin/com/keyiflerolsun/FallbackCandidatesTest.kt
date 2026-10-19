// ! HDFilmCehennemi — yedek aday seçimi testleri.
//
// ! CANLI SAYFA KANITI (bölüm sayfası, 2026):
// ! Sayfanın JSON-LD'sinde contentUrl VAR ama VİDEO DEĞİL — afiş:
// !   "thumbnail":{"@type":"ImageObject",
// !                "contentUrl":"https://.../uploads/poster/supernatural.jpg"}
// ! Afiş 200 döndüğü için eski kod onu "canlı yedek" sanıp oynatıcıya
// ! video diye veriyordu → "video verisi gelmiyor".
// !
// ! Sayfadaki tek gerçek kaynak gömülü iframe'dir:
// !   <div class="video-container" hidden>
// !     <iframe class="close" data-src="https://hdfilmcehennemi.mobi/video/embed/ID/?rapidrame_id=...">
// ! Adres mutlaktır ve host'u mainUrl'den farklıdır → AYNEN korunmalı.

package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackCandidatesTest {

    private val realPage = """
        <html><head>
        <script type="application/ld+json">
        {
          "@context": "https://schema.org",
          "@type": "TVEpisode",
          "name": "Bölüm 3",
          "thumbnail": {
            "@type": "ImageObject",
            "contentUrl": "https://www.hdfilmcehennemi.nl/uploads/poster/supernatural-5-3.jpg"
          }
        }
        </script>
        </head><body>
        <iframe data-src="https://ads.example.net/player/ad"></iframe>
        <div class="hdmv-play-container">
          <div class="video-container" hidden>
            <iframe class="close" data-src="https://hdfilmcehennemi.mobi/video/embed/4U1b90inBvN/?rapidrame_id=mhvkp8ijb531" scrolling="no" allowfullscreen="true"></iframe>
          </div>
        </div>
        <div class="alternative-links" data-lang="dual">
          <button class="alternative-link" data-active="1" data-video="279345">Close</button>
          <button class="alternative-link" data-video="234503">Rapidrame</button>
        </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `afis contentUrl aday olmaz`() {
        val candidates = FallbackCandidates.plainCandidates(realPage)
        assertTrue("afiş aday listesine girmemeli", candidates.none { it.endsWith(".jpg") })
        assertTrue(FallbackCandidates.contentUrls(realPage).isEmpty())
    }

    @Test
    fun `gorsel uzantilari elenir`() {
        assertTrue(FallbackCandidates.isImageUrl("https://x/a.PNG?w=300"))
        assertTrue(FallbackCandidates.isImageUrl("data:image/png;base64,AAA"))
        assertFalse(FallbackCandidates.isImageUrl("https://cdn.x/master.txt?t=1"))
        assertFalse(FallbackCandidates.isImageUrl("https://cdn.x/live/index.m3u8"))
    }

    @Test
    fun `thumbnail baglamindaki contentUrl elenir`() {
        // Uzantı gizlenmiş olsa da thumbnail/ImageObject bloğundaki adres video değildir.
        val html = """{"thumbnail":{"@type":"ImageObject","contentUrl":"https://x/img?id=9"}}"""
        assertTrue(FallbackCandidates.contentUrls(html).isEmpty())
    }

    @Test
    fun `gercek video contentUrl korunur`() {
        val html = """{"@type":"VideoObject","contentUrl":"https://cdn.x/hls/master.m3u8?token=abc"}"""
        assertEquals(
            listOf("https://cdn.x/hls/master.m3u8?token=abc"),
            FallbackCandidates.contentUrls(html)
        )
    }

    @Test
    fun `oynatici kapsayicisi iframe data-src adayi olarak korunur`() {
        val pageUrl = "https://www.hdfilmcehennemi.nl/dizi/supernatural/sezon-5/bolum-3-hd1/"
        val doc = org.jsoup.Jsoup.parse(realPage, pageUrl)
        val expected = "https://hdfilmcehennemi.mobi/video/embed/4U1b90inBvN/?rapidrame_id=mhvkp8ijb531"
        val raw = EmbedResolver.preferredPlayerDataSrcs(doc).firstOrNull()
        assertEquals("oynatici iframe'i ilk tercih olmali", expected, raw)

        // ! normalizeEmbed adresi ÇEVİRMEMELİ (rplayer'a dönüştürmemeli) ve
        // ! farklı host olduğu için elemeli değil, geçirmeli.
        val normalized = EmbedResolver.normalizeEmbed(raw!!, pageUrl, "https://www.hdfilmcehennemi.nl")
        assertEquals(expected, normalized)
    }
}
