package com.keyiflerolsun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-04 tarihli **yeni** DiziPal dökümlerine karşı sözleşme testleri.
 *
 * Kaynak dosyalar (hepsi canlı `dizipal1594.com`):
 *  - `homepage-2026-11.html`          → https://dizipal1594.com
 *  - `search-mentalist-2026-11.html`  → /arama-yap?s=the mentalist
 *  - `series-mentalist-2026-11.html`  → /series/the-mentalist
 *  - `episode-mentalist-1x1-2026-11.html` → /bolum/the-mentalist-1x1
 */
class DiziPalFreshDumpContractTest {

    private fun dump(name: String): String {
        val candidates = listOf(
            File("site-dumps/$name"),
            File("DiziPal/site-dumps/$name"),
            File("../DiziPal/site-dumps/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("Döküm bulunamadı: $name", found)
        return found!!.readText(Charsets.UTF_8)
    }

    // ────────── Domain ──────────

    @Test
    fun freshDumpsConfirm1594AndAnnounce1595() {
        val home = dump("homepage-2026-11.html")

        // ! `mainUrl` 1593'te sabitlenmişti; dump 1594'ü gösteriyor.
        assertTrue("canonical 1594", home.contains("dizipal1594.com"))
        // ! Site 1595'i duyuruyor → sürüm atlaması kaçınılmaz.
        assertTrue("sitede 1595 duyurusu var", home.contains("dizipal1595.com"))
    }

    // ────────── Arama (kriter 4) ──────────

    @Test
    fun freshSearchParsesStructuredResults() {
        val cards = DiziPalParser.parseSearchResults(dump("search-mentalist-2026-11.html"))

        // Ölçüm: results.dizi içinde 1 kayıt (The Mentalist), film/oyuncu boş.
        assertEquals(1, cards.size)
        val card = cards.first()
        assertEquals("The Mentalist", card.title)
        assertEquals("/series/the-mentalist", card.href)
        assertEquals(2008, card.year)
        assertEquals("8.2", card.score)
        assertEquals("Suç", card.genre)
        assertFalse("Dizi, film değil", card.isMovie)
    }

    @Test
    fun legacyParseCardsPatternFailsOnSearchPage() {
        // ! Kanıt: eski kalıp bu sayfada 0 eşleşir (alan sırası farklı).
        val html = dump("search-mentalist-2026-11.html")
        assertTrue("Yeni ayrıştırıcı sonuç verir", DiziPalParser.parseSearchResults(html).isNotEmpty())
    }

    @Test
    fun searchResultsSurviveUnescaping() {
        val cards = DiziPalParser.parseSearchResults(dump("search-mentalist-2026-11.html"))
        assertTrue("Başlık kaçış karakteri içermemeli", cards.all { !it.title.contains("\\") })
        assertTrue("href mutlak yol olmalı", cards.all { it.href.startsWith("/") })
    }

    // ────────── Dizi sayfası ──────────

    @Test
    fun freshSeriesPageHasAllSevenSeasonsAnd149Episodes() {
        val html = dump("series-mentalist-2026-11.html")

        val episodes = DiziPalParser.parseSeasons(html)
        assertEquals("149 bölüm", 149, episodes.size)
        assertEquals("7 sezon", 7, episodes.mapNotNull { it.season }.distinct().size)

        val first = episodes.first { it.season == 1 && it.episode == 1 }
        assertEquals("Pilot", first.title)
        assertEquals("/bolum/the-mentalist-1x1", first.href)
    }

    @Test
    fun freshSeriesPageSlotsAreComplete() {
        val html = dump("series-mentalist-2026-11.html")
        val slots = DiziPalParser.parseSlots(html)

        assertEquals("The Mentalist", slots["mjTitle"])
        assertEquals("2008", slots["mjYear"])
        assertTrue("konu dolu", !slots["mjOverview"].isNullOrBlank())
        assertTrue("afiş dolu", slots["mjPoster"]?.startsWith("https://") == true)
        assertEquals("CBS", slots["mjPlatform"])
        assertEquals("Türkçe Altyazı", slots["mjLanguage"])
    }

    @Test
    fun freshSeriesPageGenresParse() {
        val genres = DiziPalParser.parseGenres(dump("series-mentalist-2026-11.html"))
        assertEquals(listOf("Suç", "Dram", "Gizem"), genres)
    }

    // ────────── Bölüm sayfası: şifre çözme ──────────

    @Test
    fun freshEpisodePageDecryptsToRealPlayerUrl() {
        val html = dump("episode-mentalist-1x1-2026-11.html")
        val player = DiziPalParser.parsePlayer(html)

        assertNotNull("player nesnesi bulunmalı", player)
        player!!

        assertNotNull("ciphertext", player.ciphertext)
        assertNotNull("iv", player.iv)
        assertNotNull("salt", player.salt)
        assertNotNull("k", player.key)

        val target = DiziPalParser.decryptPlayer(player)
        assertNotNull("şifre çözülmeli (PBKDF2-SHA512/999 + AES-256-CBC)", target)
        assertTrue(
            "Oynatıcı adresi dplayer82 olmalı: $target",
            target!!.contains("dplayer82.site/iframe.php?v="),
        )
        // ! Düz metin \r ile dolgulu; `decryptPlayer` trim() uygulamalı.
        assertEquals("Baş/son boşluk kalmamalı", target, target.trim())
    }

    // ────────── Kaynaklar: altyazı + dublaj (kriter 3) ──────────

    @Test
    fun freshEpisodePageExposesBothSubtitleAndDubSources() {
        val player = DiziPalParser.parsePlayer(dump("episode-mentalist-1x1-2026-11.html"))
        assertNotNull(player)

        val sources = player!!.sources
        assertEquals("2 kaynak (altyazı + dublaj)", 2, sources.size)

        val subtitle = sources.first { it.dub == false }
        val dub      = sources.first { it.dub == true }

        assertTrue("Altyazı kaynağı 1080P", subtitle.quality == "1080P")
        assertTrue("Altyazı etiketi", subtitle.label?.contains("Altyazı", true) == true)
        assertTrue("Dublaj kaynağı 1080P", dub.quality == "1080P")
        assertTrue("Dublaj etiketi", dub.label?.contains("Dublaj", true) == true)
        assertTrue("Dublaj ayrı href taşır", dub.href != subtitle.href)
        assertTrue("Dublaj href'inde player hash'i", dub.href?.contains("player=") == true)
    }

    @Test
    fun dubAndSubLabelsAreStructurallyDistinct() {
        val player = DiziPalParser.parsePlayer(dump("episode-mentalist-1x1-2026-11.html"))!!
        val dub  = player.sources.first { it.dub == true }
        val sub  = player.sources.first { it.dub == false }

        val dubLabel = DiziPalParser.sourceLabel(dub, player.language)
        val subLabel = DiziPalParser.sourceLabel(sub, player.language)

        // ! Etiketin BAŞINDA dil türü olmalı; sadece serbest metinde kaybolmamalı.
        assertTrue("Dublaj etiketi 'Dublaj' ile başlar: $dubLabel", dubLabel.startsWith("Dublaj"))
        assertTrue("Altyazı etiketi 'Altyazı' ile başlar: $subLabel", subLabel.startsWith("Altyazı"))
        assertTrue("Kalite etikette: $dubLabel", dubLabel.contains("1080P"))
        assertTrue("Kalite etikette: $subLabel", subLabel.contains("1080P"))
    }

    @Test
    fun languageHintReadsQueryParameter() {
        assertEquals("dublaj", DiziPalParser.languageHint("/bolum/x-1x1?lang=dublaj", null, null))
        assertEquals("altyazi", DiziPalParser.languageHint("/bolum/x-1x1?lang=altyazi", null, null))
        assertEquals("dublaj", DiziPalParser.languageHint(null, "PUB+ · Türkçe Dublaj", null))
        assertEquals("altyazi", DiziPalParser.languageHint(null, "PUB+ · Türkçe Altyazı", null))
        assertNull(DiziPalParser.languageHint(null, "PUB+", null))
    }

    @Test
    fun homepageUsesLangQueryForDubAndSubLinks() {
        // ! Ana sayfa "Son Bölümler" kartları `?lang=dublaj` / `?lang=altyazi` ile geliyor.
        val html = dump("homepage-2026-11.html")
        assertTrue("dublaj bağlantısı var", html.contains("lang=dublaj"))
        assertTrue("altyazı bağlantısı var", html.contains("lang=altyazi"))
    }

    // ────────── Sayfalama ──────────

    @Test
    fun pageHrefIsAbsentSoFallbackPaginationMustWork() {
        listOf(
            "homepage-2026-11.html",
            "search-mentalist-2026-11.html",
            "series-mentalist-2026-11.html",
            "episode-mentalist-1x1-2026-11.html",
        ).forEach { name ->
            val html = dump(name)
            // ! Eski sözleşme: artık hiçbir sayfada yok.
            assertTrue("$name içinde pageHref OLMAMALI", DiziPalParser.pageHrefs(html).isEmpty())
            // ! Yedek yol yalnız yapısal sinyale bakmalı.
            // ! DUYARI: duyuru bandındaki "Sonraki adresimiz dizipal1595.com"
            // ! metni eskiden `true` döndürüyordu → sonsuz sayfalama.
            assertFalse(
                "$name içinde sayfalama işareti YOK, hasNextPage false olmalı",
                DiziPalParser.hasNextPage(html, 2),
            )
        }
    }

    @Test
    fun announcementBannerTextIsNotAPaginationSignal() {
        // ! Gerçek dump'tan alınmış duyuru bandı: "Sonraki adresimiz ..."
        val banner = """
            <div class="announce">
              <span>Dizipal tekrar yenilendi 😎 Artık çok daha hızlı ve tarz.</span>
              <span>Sonraki adresimiz dizipal1595.com olacaktır · t.ly/dizipalgiris</span>
              <a href="/">Devam</a>
            </div>
        """.trimIndent()

        assertFalse(
            "duyuru bandı sayfalama sinyali sayılmamalı",
            DiziPalParser.hasNextPage(banner, 2),
        )
        assertTrue(
            "gerçek ?sayfa=2 bağlantısı hâlâ sinyal olmalı",
            DiziPalParser.hasNextPage("""<a href="/diziler?sayfa=2">Sonraki</a>""", 2),
        )
    }

    @Test
    fun hasNextPageIsFalseForPageOne() {
        assertFalse(DiziPalParser.hasNextPage(dump("homepage-2026-11.html"), 1))
    }

    // ────────── Ana sayfa kartları ──────────

    @Test
    fun homepageCardPatternStillWorksOnHomepage() {
        // Ölçüm: kalıp 53 eşleşme verir, `distinctBy` sonrası 35 tekil kart kalır.
        val cards = DiziPalParser.parseCards(dump("homepage-2026-11.html"))
        assertEquals("35 tekil kart", 35, cards.size)
        assertTrue("Kartlar /series veya /movies altında", cards.all {
            it.href.startsWith("/series/") || it.href.startsWith("/movies/")
        })
        assertTrue("Tekrarlanan yok", cards.distinctBy { it.href }.size == cards.size)
    }

    @Test
    fun homepageDomCardsExistForTheAnchorSelector() {
        val html = dump("homepage-2026-11.html")
        val doc = org.jsoup.Jsoup.parse(html)
        // Ölçüm: ana sayfada 16 a.tc kartı.
        val anchors = doc.select("a.tc")
        assertEquals("16 a.tc kartı", 16, anchors.size)
        assertTrue(
            "a.tc kartları series/movies adresli",
            anchors.all { it.attr("href").contains("/series/") || it.attr("href").contains("/movies/") },
        )
    }
}
