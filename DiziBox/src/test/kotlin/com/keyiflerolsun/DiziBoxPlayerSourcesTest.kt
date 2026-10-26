package com.keyiflerolsun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026 canlı zincirinden (Saloo/burak aynı gün fork'ları, nuvio testleri)
 * doğrulanmış ek ayrıştırıcıların birim testleri.
 */
class DiziBoxPlayerSourcesTest {

    private val base = "https://www.dizibox.live/ornek-1-sezon-1-bolum-1/"

    @Test
    fun hlsVariantsEmitEveryResolutionAndResolveRelativeUris() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=1400000,RESOLUTION=842x480
            /hls/480p/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2500000
            https://cdn.example/hls/auto/index.m3u8
        """.trimIndent()

        val variants = DiziBoxPlayerParser.hlsVariants(playlist, "https://dbx.molystream.org/embed/sheila/abc")
        assertEquals(3, variants.size)
        assertEquals(360, variants[0].height)
        assertEquals("https://dbx.molystream.org/embed/sheila/360p/index.m3u8", variants[0].url)
        assertEquals(480, variants[1].height)
        assertEquals("https://dbx.molystream.org/hls/480p/index.m3u8", variants[1].url)
        assertEquals(0, variants[2].height)
        assertEquals("https://cdn.example/hls/auto/index.m3u8", variants[2].url)

        // Manifest olmayan gövde boş döner.
        assertTrue(DiziBoxPlayerParser.hlsVariants("<html>challenge</html>", base).isEmpty())
    }

    @Test
    fun dwlsSearchParsesAjaxJsonAndSkipsEmptyRows() {
        val json = """
            {"results":[
              {"post_title":"Örnek Dizi","permalink":"https:\/\/www.dizibox.live\/ornek-dizi\/",
               "attachment_thumbnail":"https:\/\/www.dizibox.live\/wp-content\/50x50\/kapak.jpg"},
              {"post_title":"","permalink":""}
            ]}
        """.trimIndent()

        val results = DiziBoxPlayerParser.dwlsSearchResults(json)
        assertEquals(1, results.size)
        assertEquals("Örnek Dizi", results.single().title)
        assertEquals("https://www.dizibox.live/ornek-dizi/", results.single().url)
        assertEquals(
            "https://www.dizibox.live/wp-content/200x290/kapak.jpg",
            DiziBoxPlayerParser.upscaleThumbnail(results.single().thumbnail),
        )
    }

    @Test
    fun archiveBoxLinkAndEpisodeAnchorFallbacks() {
        val document = Jsoup.parse(
            """
            <div id="archive-box">
              <a class="archive-title" href="/yeni-arsiv/ornek-1-sezon-1-bolum-1/">Taşındı</a>
            </div>
            <div id="seasons-list">
              <a href="/ornek-1-sezon-1-bolum-2-izle/">2. Bölüm</a>
              <a href="/ornek-1-sezon-2-bolum-1-izle/">Sezon 2 açılışı</a>
              <a href="/iletisim/">İlgisiz</a>
            </div>
            """.trimIndent(),
            base,
        )

        assertEquals(
            "https://www.dizibox.live/yeni-arsiv/ornek-1-sezon-1-bolum-1/",
            DiziBoxPlayerParser.archiveBoxLink(document, base),
        )
        assertNull(DiziBoxPlayerParser.archiveBoxLink(Jsoup.parse("<div></div>"), base))

        val anchors = DiziBoxPlayerParser.episodeAnchors(document)
        // Arşiv yönlendirme linki de sezon/bölüm kalıbına uyduğu için dahildir.
        assertEquals(3, anchors.size)
        assertEquals(1 to 1, anchors[0].season to anchors[0].episode)
        assertEquals(1 to 2, anchors[1].season to anchors[1].episode)
        assertEquals(2 to 1, anchors[2].season to anchors[2].episode)
    }

    @Test
    fun wmodeOpaqueIsIdempotentAndScopedToWrapperPhp() {
        assertEquals(
            "https://www.dizibox.live/player/king/king.php?v=abc&wmode=opaque",
            DiziBoxPlayerParser.withWOpaque("https://www.dizibox.live/player/king/king.php?v=abc"),
        )
        val already = "https://www.dizibox.live/player/moly/moly.php?wmode=opaque&h=xyz"
        assertEquals(already, DiziBoxPlayerParser.withWOpaque(already))
        val untouched = "https://dbx.molystream.org/embed/sheila/abc"
        assertEquals(untouched, DiziBoxPlayerParser.withWOpaque(untouched))
    }

    @Test
    fun fallbackMediaUrlsCoversPlaylistsSheilaAndMasterTxt() {
        val html = """
            var broken = "https://cdn.example/subtitles/tr.vtt";
            var hls = "https:\/\/cdn.example\/hls\/master.m3u8?token=1";
            var sheila = 'https://dbx.molystream.org/embed/sheila/ep-9?ref=x';
            var txt = "https://dbx.molystream.org/play/master.txt?id=7";
        """.trimIndent()

        val urls = DiziBoxPlayerParser.fallbackMediaUrls(html)
        assertTrue(urls.contains("https://cdn.example/hls/master.m3u8?token=1"))
        assertTrue(urls.contains("https://dbx.molystream.org/embed/sheila/ep-9?ref=x"))
        assertTrue(urls.contains("https://dbx.molystream.org/play/master.txt?id=7"))
        assertTrue(urls.none { it.endsWith(".vtt") })
    }
}
