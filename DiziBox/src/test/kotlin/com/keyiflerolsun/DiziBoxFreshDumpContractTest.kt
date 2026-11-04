package com.keyiflerolsun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-04 tarihli **yeni** site dökümlerine karşı sözleşme testleri.
 *
 * Kaynak dosyalar:
 *  - `homepage-2026-11.html`            → https://www.dizibox.live/
 *  - `search-mentalist-2026-11.html`    → https://www.dizibox.live/?s=the+mentalist
 *  - `series-mentalist-2026-11.html`    → /diziler/the-mentalist-izle-hd/
 *  - `episode-mentalist-1x1-2026-11.html` → /the-mentalist-1-sezon-1-bolum-izle/
 *
 * Her test, ölçülen gerçek sayıyı sabitler; böylece site yapısı sessizce
 * değişirse test kırılır.
 */
class DiziBoxFreshDumpContractTest {

    private val baseUrl = "https://www.dizibox.live"

    private fun dump(name: String): String {
        val candidates = listOf(
            File("site-dumps/$name"),
            File("DiziBox/site-dumps/$name"),
            File("../DiziBox/site-dumps/$name"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("Döküm bulunamadı: $name", found)
        return found!!.readText(Charsets.UTF_8)
    }

    // ────────── Ana sayfa ──────────

    @Test
    fun freshHomepageShelvesAreAllNonEmpty() {
        val url = "$baseUrl/"
        val doc = Jsoup.parse(dump("homepage-2026-11.html"), url)

        // Ölçülen değerler (2026-11-04): 10 / 6 / 10 / 30 / 12
        val recommended = DiziBoxPlayerParser.parseListingCards(doc, url, "recommended_series")
        val newSeries   = DiziBoxPlayerParser.parseListingCards(doc, url, "new-series")
        val popular     = DiziBoxPlayerParser.parseListingCards(doc, url, "popular-episodes")
        val latest      = DiziBoxPlayerParser.parseListingCards(doc, url, "latest-episodes")
        val best        = DiziBoxPlayerParser.parseListingCards(doc, url, "best-series")

        assertEquals("Beklenen / Eklenen Diziler", 10, recommended.size)
        assertEquals("Dikkat Çeken Yeni Diziler", 6, newSeries.size)
        assertEquals("Popüler Dizilerden Son Bölümler", 10, popular.size)
        assertEquals("Yeni Eklenen Bölümler", 30, latest.size)
        assertEquals("Efsane Diziler", 12, best.size)
    }

    @Test
    fun freshHomepageRecommendedSeriesShelfIsNowReachable() {
        val url = "$baseUrl/"
        val doc = Jsoup.parse(dump("homepage-2026-11.html"), url)

        // ! Bu vitrin `CHROME_SELECTOR` ile siliniyordu; artık ayrı ayrıştırıcı var.
        // Ölçüm: 40 <a>, 20 tekil dizi (aynı diziye 2 bağlantı: afiş + başlık).
        val cards = DiziBoxPlayerParser.parseSimpleSectionLinks(doc, url, "recommended-series")
        assertEquals("20 tekil dizi", 20, cards.size)
        assertTrue(
            "Dizi kartları /diziler/ altında olmalı",
            cards.any { it.url.contains("/diziler/") },
        )
        assertTrue("Başlıklar boş olmamalı", cards.all { it.title.isNotBlank() })
        assertTrue("Tekrarlanan kart olmamalı", cards.distinctBy { it.url }.size == cards.size)
        assertTrue(
            "Başlık IMDb puanı olmamalı",
            cards.none { card -> card.title.matches(Regex("""^\d+(?:[.,]\d+)?$""")) },
        )
    }

    @Test
    fun freshHomepageTranslationStatusShelfIsReachable() {
        val url = "$baseUrl/"
        val doc = Jsoup.parse(dump("homepage-2026-11.html"), url)

        // Ölçüm: 10 <a>, 5 tekil bölüm (afiş + başlık aynı adrese gidiyor).
        val cards = DiziBoxPlayerParser.parseSimpleSectionLinks(doc, url, "translation-status")
        assertEquals("5 tekil bölüm", 5, cards.size)
        assertTrue("Bölüm kartı olarak işaretlenmeli", cards.all { it.isEpisode })
        assertTrue("Bölüm adresi kalıbı", cards.all { it.url.contains("-bolum-") })
    }

    // ────────── Arama ──────────

    @Test
    fun freshSearchReturnsSeriesAndEpisodesNotJustTheSeries() {
        val url = "$baseUrl/?s=the+mentalist"
        val doc = Jsoup.parse(dump("search-mentalist-2026-11.html"), url)

        val cards = DiziBoxPlayerParser.parseSearchCards(doc, url)

        // ! Eskiden yalnızca 1 (dizi) dönüyordu; 29 bölüm kartı atılıyordu.
        assertEquals("Dizi + 29 bölüm = 30 kart", 30, cards.size)
        assertEquals("Tek dizi kartı", 1, cards.count { !it.isEpisode })
        assertEquals("29 bölüm kartı", 29, cards.count { it.isEpisode })
    }

    @Test
    fun freshSearchSeriesCardComesFirst() {
        val url = "$baseUrl/?s=the+mentalist"
        val doc = Jsoup.parse(dump("search-mentalist-2026-11.html"), url)

        val cards = DiziBoxPlayerParser.parseSearchCards(doc, url)
        assertTrue("Sıralama: önce dizi", !cards.first().isEpisode)
        assertTrue(
            "Dizi kartı doğru adrese gitmeli",
            cards.first().url.contains("/diziler/the-mentalist"),
        )
    }

    @Test
    fun freshSearchEpisodeCardsCarryParsedTitles() {
        val url = "$baseUrl/?s=the+mentalist"
        val doc = Jsoup.parse(dump("search-mentalist-2026-11.html"), url)

        val episodes = DiziBoxPlayerParser.parseSearchCards(doc, url).filter { it.isEpisode }
        assertTrue(
            "Bölüm başlıkları 'Sezon/Bölüm' içermeli",
            episodes.count { it.title.contains("Sezon", true) && it.title.contains("Bölüm", true) } >= 25,
        )
        assertTrue(
            "Bölüm adresleri -sezon-N-bolum-M kalıbında",
            episodes.all { Regex("""-\d+-sezon-\d+-bolum""").containsMatchIn(it.url) },
        )
    }

    // ────────── Dizi sayfası ──────────

    @Test
    fun freshSeriesPageExposesAllSevenSeasons() {
        val url = "$baseUrl/diziler/the-mentalist-izle-hd/"
        val doc = Jsoup.parse(dump("series-mentalist-2026-11.html"), url)

        val detail = DiziBoxPlayerParser.parseSeriesDetail(doc, url)
        assertNotNull("Dizi detayı ayrıştırılmalı", detail)
        detail!!

        assertEquals("The Mentalist", detail.title.trim())
        assertEquals("7 sezon bulunmalı", 7, detail.allSeasonUrls.size)
        assertTrue("IMDb puanı okunmalı", detail.imdbScore?.startsWith("8") == true)
        assertEquals("2008", detail.year)

        // Aktif sezon (1) sayfada gömülü: 23 bölüm
        assertEquals("Aktif sezon 23 bölüm", 23, detail.activeEpisodes.size)
        // Diğer 6 sezon ayrıca çekilmeli
        assertEquals("Çekilecek 6 sezon", 6, detail.inactiveSeasonUrls.size)
    }

    // ────────── Bölüm sayfası / oynatıcı ──────────

    @Test
    fun freshEpisodePageHasThreePlayerTabs() {
        val url = "$baseUrl/the-mentalist-1-sezon-1-bolum-izle/"
        val doc = Jsoup.parse(dump("episode-mentalist-1x1-2026-11.html"), url)

        val tabs = DiziBoxPlayerParser.playerTabs(doc, url)
        assertEquals("DBX Pro / Moly+ / Odnok", 3, tabs.size)
        assertEquals(listOf("DBX Pro", "Moly+", "Odnok"), tabs.map { it.label })
    }

    @Test
    fun freshEpisodePageHasSingleKingPlayerIframe() {
        val url = "$baseUrl/the-mentalist-1-sezon-1-bolum-izle/"
        val doc = Jsoup.parse(dump("episode-mentalist-1x1-2026-11.html"), url)

        val iframes = DiziBoxPlayerParser.playerIframes(doc, url)
        assertEquals(1, iframes.size)
        assertTrue(iframes.first().contains("/player/king/king.php?v="))
        assertTrue(
            "v= parametresi 64 hex karakter",
            Regex("""[?&]v=[0-9a-f]{64}""").containsMatchIn(iframes.first()),
        )
    }

    @Test
    fun freshEpisodePageArchiveBoxLinksToSeries() {
        val url = "$baseUrl/the-mentalist-1-sezon-1-bolum-izle/"
        val doc = Jsoup.parse(dump("episode-mentalist-1x1-2026-11.html"), url)

        val archive = DiziBoxPlayerParser.archiveBoxLink(doc, url)
        assertNotNull("archive-box bulunmalı", archive)
        assertTrue("Dizi sayfasına gitmeli", archive!!.contains("/diziler/the-mentalist"))
    }

    // ────────── Altyazı / dil rozeti (kriter 3) ──────────

    @Test
    fun freshEpisodePageExposesSubtitleBadge() {
        val url = "$baseUrl/the-mentalist-1-sezon-1-bolum-izle/"
        val doc = Jsoup.parse(dump("episode-mentalist-1x1-2026-11.html"), url)

        val badges = DiziBoxPlayerParser.languageBadges(doc, url)
        assertEquals("Tek dil rozeti (altyazı)", 1, badges.size)
        val badge = badges.first()
        assertTrue("Etiket 'Altyazı' içermeli", badge.label.contains("Altyazı", ignoreCase = true))
        assertTrue("İkon adresi altyazi.png", badge.iconUrl?.contains("altyazi.png") == true)
    }

    @Test
    fun freshDumpsHaveNoDubBadgeBecauseSiteIsSubtitleOnly() {
        // Ölçüm: 4 dump'ın HİÇBİRİNDE dublaj.png yok (site altyazılı yabancı dizi arşivi).
        val url = "$baseUrl/the-mentalist-1-sezon-1-bolum-izle/"
        val doc = Jsoup.parse(dump("episode-mentalist-1x1-2026-11.html"), url)
        assertTrue(
            "Dublaj rozeti üretilmemeli",
            DiziBoxPlayerParser.languageBadges(doc, url).none { it.label.contains("Dublaj", true) },
        )
    }

    // ────────── Tel seviyesi: çerez doğrulaması ──────────

    @Test
    fun forgedDbxuCookieIsGoneAndRealOnesRemain() {
        val cookies = DiziBoxWire.diziboxCookies()

        // ! `dbxu` sitede yok (4 dump'ta 0 eşleşme) — uydurmaydı, kaldırıldı.
        assertTrue("dbxu gönderilmemeli", "dbxu" !in cookies)
        // Bunlar site head'inde gerçekten set ediliyor.
        assertEquals("true", cookies["LockUser"])
        assertEquals("true", cookies["isTrustedUser"])
    }

    @Test
    fun homepageHeadReallySetsTheCookiesWeSend() {
        val html = dump("homepage-2026-11.html")
        assertTrue("isTrustedUser sitede set ediliyor", html.contains("isTrustedUser"))
        assertTrue("LockUser sitede set ediliyor", html.contains("LockUser"))
        assertTrue("dbxu sitede YOK", !html.contains("dbxu"))
    }

    // ────────── Domain ──────────

    @Test
    fun freshDumpsConfirmLiveDomain() {
        listOf(
            "homepage-2026-11.html",
            "search-mentalist-2026-11.html",
            "series-mentalist-2026-11.html",
            "episode-mentalist-1x1-2026-11.html",
        ).forEach { name ->
            val html = dump(name)
            assertTrue("$name www.dizibox.live kullanmalı", html.contains("www.dizibox.live"))
        }
    }
}
