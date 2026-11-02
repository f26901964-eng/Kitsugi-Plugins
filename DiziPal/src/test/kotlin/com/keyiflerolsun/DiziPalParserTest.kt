package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dizipal sözleşmesinin **ölçülmüş** örneklerle doğrulanması.
 *
 * Örnekler sitenin kendisinden alındı (`tools/dizipal_dump.py` /
 * `tools/dizipal_player_probe.py`) ve RSC kaçışları korunarak saklandı; böylece
 * hem `unescapeRsc` hem ayrıştırıcı hem de şifre çözme aynı testte sınanıyor.
 */
class DiziPalParserTest {

    private fun fixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(name)
        assertNotNull("eksik örnek: $name", stream)
        return stream!!.bufferedReader().readText()
    }

    @Test
    fun `oynatıcı nesnesi ayrıştırılır`() {
        val player = DiziPalParser.parsePlayer(fixture("player-gassal-4x1.rsc.html"))

        assertNotNull("player nesnesi bulunamadı", player)
        player!!
        assertEquals("episode", player.kind)
        assertEquals(400473L, player.contentId)
        assertEquals("Gassal 4. Sezon 1. Bölüm", player.title)
        assertEquals("Altyazısız", player.language)

        val source = player.sources.firstOrNull()
        assertNotNull("kaynak listesi boş", source)
        assertEquals("PUB+ · Altyazısız", source!!.label)
        assertEquals("1080P", source.quality)
        assertEquals("/bolum/gassal-4x1", source.href)
        assertEquals(false, source.dub)
    }

    @Test
    fun `şifreli adres sitenin algoritmasıyla çözülür`() {
        val player = DiziPalParser.parsePlayer(fixture("player-gassal-4x1.rsc.html"))!!
        val target = DiziPalParser.decryptPlayer(player)

        assertEquals("//sn.dplayer82.site/iframe.php?v=e7ca258235a732527eb60a47639601dc", target)
    }

    @Test
    fun `bölüm listesi sezon ve numaralarla gelir`() {
        val episodes = DiziPalParser.parseSeasons(fixture("seasons-gassal.rsc.html"))

        assertEquals(8, episodes.size)
        assertEquals(1, episodes.first().season)
        assertEquals(1, episodes.first().episode)
        assertEquals("Ben, Gassal", episodes.first().title)
        assertEquals("/bolum/gassal-1x1-c09", episodes.first().href)
        assertEquals(34, episodes.first().duration)

        val secondSeason = episodes.filter { it.season == 2 }
        assertEquals(4, secondSeason.size)
        assertEquals(4, secondSeason.mapNotNull { it.episode }.maxOrNull())
    }

    @Test
    fun `son bölümler akışı dizi adresine çevrilir`() {
        val feed = DiziPalParser.parseEpisodeFeed(fixture("feed-items.rsc.html"))

        assertTrue("akış boş", feed.size >= 3)
        val first = feed.first()
        assertEquals("/series/gassal", first.href)
        assertTrue("bölüm etiketi yok: ${first.title}", first.title.contains("·"))
        assertTrue(first.title.startsWith("Gassal"))
    }

    @Test
    fun `kart akışı başlık ve afiş verir`() {
        val cards = DiziPalParser.parseCards(fixture("cards-popular.rsc.html"))

        assertEquals(2, cards.size)
        assertEquals("Nocturne", cards.first().title)
        assertEquals("/series/nocturne", cards.first().href)
        assertTrue(cards.first().poster?.startsWith("https://") == true)
        assertEquals(2026, cards.first().year)
        assertEquals(false, cards.first().isMovie)
    }

    @Test
    fun `film oynatıcısı kaynak listesiyle gelir`() {
        val player = DiziPalParser.parsePlayer(fixture("player-movie-nocturnal.rsc.html"))

        assertNotNull(player)
        assertEquals("movie", player!!.kind)
        assertEquals(17618L, player.contentId)
        assertEquals(2, player.sources.size)
        assertEquals("/movies/nocturnal?lang=2347", player.sources[1].href)
        assertEquals(true, player.sources[1].dub)
        assertEquals("//four.dplayer82.site/iframe.php?v=f56dce5b9aaf497f9f1696eab0745cca", DiziPalParser.decryptPlayer(player))
    }

    @Test
    fun `dizi künyesi ve türler okunur`() {
        val html = fixture("series-slots.rsc.html")
        val slots = DiziPalParser.parseSlots(html)

        assertEquals("Gassal", slots["title"])
        assertEquals("2024", slots["mjYear"])
        assertEquals("tabii", slots["mjPlatform"])
        assertTrue(slots["mjPoster"]?.startsWith("https://images.cdnhipter.xyz/") == true)
        assertTrue((slots["mjOverview"]?.length ?: 0) > 50)
        assertTrue(slots["mjOverview"]!!.contains("Beni Kim Yıkayacak?"))

        assertEquals(listOf("Komedi", "Dram"), DiziPalParser.parseGenres(html))
    }

    @Test
    fun `sayfalama adresleri okunur`() {
        val links = DiziPalParser.pageHrefs(fixture("feed-items.rsc.html"))

        assertEquals(listOf("/a", "/b?sayfa=2"), links)
    }

    @Test
    fun `oynatıcı html'inden m3u8 ve altyazı çıkar`() {
        val body = """
            <html><body>
            <script>
            var player = jwplayer("v").setup({
              sources: [{file: "https:\/\/cdn.example.com\/hls\/master.m3u8?token=abc"}],
              tracks: [{file: "https:\/\/cdn.example.com\/sub\/tr.vtt", label: "Türkçe"}]
            });
            </script>
            <track kind="captions" src="https://cdn.example.com/sub/en.vtt" srclang="en">
            </body></html>
        """.trimIndent()

        assertEquals(
            listOf("https://cdn.example.com/hls/master.m3u8?token=abc"),
            DiziPalParser.mediaUrls(body)
        )

        val subtitles = DiziPalParser.subtitleTracks(body)
        assertTrue(subtitles.any { it.first == "en" && it.second.endsWith("en.vtt") })
        assertTrue(subtitles.any { it.second.endsWith("tr.vtt") })
    }

    @Test
    fun `reklam akışını atlar`() {
        val body = """
            <script>
            {vast: "https://ads.example.com/preroll/master.m3u8"}
            {file: "https://cdn.example.com/hls/dizi/master.m3u8"}
            </script>
        """.trimIndent()

        assertEquals(listOf("https://cdn.example.com/hls/dizi/master.m3u8"), DiziPalParser.mediaUrls(body))
    }

    @Test
    fun `kaçışlı rsc metni çözülür`() {
        val raw = """\"player\":{\"kind\":\"episode\"},\"x\":\"a\/b"""

        assertEquals(""""player":{"kind":"episode"},"x":"a/b""", DiziPalParser.unescapeRsc(raw))
    }

    @Test
    fun `base64 çözücü standart girdiyi çözer`() {
        val decoded = DiziPalParser.base64Decode("aHR0cHM6Ly9leGFtcGxlLmNvbQ==")

        assertEquals("https://example.com", decoded?.toString(Charsets.UTF_8))
    }

    @Test
    fun `bozuk şifreli veri null döner`() {
        val player = DiziPalParser.parsePlayer(fixture("player-gassal-4x1.rsc.html"))!!
        val broken = player.copy(ciphertext = "AAA=", iv = "zz", salt = "00")

        assertEquals(null, DiziPalParser.decryptPlayer(broken))
    }
}
