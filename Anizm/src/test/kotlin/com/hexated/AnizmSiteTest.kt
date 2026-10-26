package com.hexated

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anizm HTML çözümleyici testleri.
 *
 * Fixture'lar **sentetiktir** (canlı site yanıtı değil): seçiciler 2026-10 itibarıyla
 * çalışan iki bağımsız istemcinin (Aniyomi `src/tr/anizm`, CloudStream
 * `SalooRepo/Anizm`) kullandığı yapılara göre kurulmuştur. Amaç, site teması
 * değiştiğinde hangi çıktının bozulduğunu ağ olmadan görebilmektir.
 */
class AnizmSiteTest {

    private val base = "https://puffytr.com"

    // ------------------------------------------------------------------
    // Kart çözümleme
    // ------------------------------------------------------------------

    @Test
    fun parsesLatestEpisodeCardsAndMapsThemToSeriesPages() {
        val html = """
            <html><body>
              <div id="episodesMiddle">
                <div class="posterBlock">
                  <a href="/tensei-shitara-slime-datta-ken-4th-season-24-bolum-final-izle">
                    <img src="/storage/pcovers/placeholder.gif" data-src="/storage/pcovers/slime4.jpg">
                    <div class="title">Tensei shitara Slime Datta Ken 4th Season İzle</div>
                    <div class="truncateText">24. Bölüm</div>
                  </a>
                </div>
                <div class="posterBlock">
                  <a href="/one-piece-1071-bolum">
                    <img data-src="/storage/pcovers/op.jpg">
                    <h5 class="animeTitle">One Piece</h5>
                  </a>
                </div>
              </div>
              <a href="/kategoriler/2">Aksiyon</a>
              <a href="/giris-yap">Giriş Yap</a>
              <a href="/takvim">Takvim</a>
            </body></html>
        """.trimIndent()

        val cards = AnizmSite.parseCards(Jsoup.parse(html, "$base/anime-izle"), "$base/anime-izle")

        assertEquals(2, cards.size)
        val slime = cards.first { it.name.contains("Slime") }
        assertEquals("Tensei shitara Slime Datta Ken 4th Season", slime.name)
        assertEquals("$base/tensei-shitara-slime-datta-ken-4th-season", slime.url)
        assertEquals("$base/storage/pcovers/slime4.jpg", slime.poster)
        assertEquals(24, slime.episodeHint)

        val op = cards.first { it.name == "One Piece" }
        assertEquals("$base/one-piece", op.url)
        assertEquals(1071, op.episodeHint)
    }

    @Test
    fun parsesCatalogCardsFromUnknownThemeByGenericAnchorScan() {
        val html = """
            <html><body>
              <div class="v3-catalog-card">
                <a href="/bleach">
                  <img src="/storage/pcovers/bleach.jpg" alt="Bleach - Anizm.TV">
                  <h3>Bleach</h3>
                  <span class="v3-catalog-score">8.2</span>
                </a>
              </div>
            </body></html>
        """.trimIndent()

        val cards = AnizmSite.parseCards(Jsoup.parse(html, "$base/arama?q=bleach"), "$base/arama?q=bleach")

        assertEquals(1, cards.size)
        assertEquals("Bleach", cards.first().name)
        assertEquals("$base/bleach", cards.first().url)
        assertEquals(8.2, cards.first().score!!, 0.001)
    }

    @Test
    fun cardFromJsonFieldsBuildsPosterPathAndSkipsBrokenSlug() {
        val card = AnizmSite.cardFromJsonFields(
            base = base,
            title = "Shingeki no Kyojin",
            slug = "shingeki-no-kyojin",
            poster = "aot.jpg",
            year = 2013,
            score = 8.9,
            altNames = listOf("Attack on Titan")
        )
        assertNotNull(card)
        assertEquals("$base/shingeki-no-kyojin", card!!.url)
        assertEquals("$base/storage/pcovers/aot.jpg", card.poster)
        assertEquals(listOf("Attack on Titan"), card.altNames)

        assertNull(
            AnizmSite.cardFromJsonFields(base, "X", null, null, null, null, emptyList())
        )
        assertNull(
            AnizmSite.cardFromJsonFields(base, "X", "   ", null, null, null, emptyList())
        )
    }

    // ------------------------------------------------------------------
    // URL kuralları
    // ------------------------------------------------------------------

    @Test
    fun convertsEpisodeUrlsToSeriesUrls() {
        assertEquals(
            "$base/persona-5-the-animation",
            AnizmSite.toSeriesUrl("$base/persona-5-the-animation-1-bolum")
        )
        assertEquals(
            "$base/tensei-shitara-slime-datta-ken-4th-season",
            AnizmSite.toSeriesUrl("$base/tensei-shitara-slime-datta-ken-4th-season-24-bolum-final-izle")
        )
        assertNull(AnizmSite.toSeriesUrl("$base/one-piece"))
    }

    @Test
    fun readsEpisodeNumberFromUrl() {
        assertEquals(1, AnizmSite.episodeNumberFromUrl("$base/ghost-22-1-bolum-izle"))
        assertEquals(24, AnizmSite.episodeNumberFromUrl("$base/slime-24-bolum-final-izle"))
        assertNull(AnizmSite.episodeNumberFromUrl("$base/one-piece"))
    }

    @Test
    fun recognisesSiteHostsAndRejectsForeignOnes() {
        assertTrue(AnizmSite.isSiteUrl("$base/one-piece"))
        assertTrue(AnizmSite.isSiteUrl("https://anizm.net/one-piece"))
        assertTrue(!AnizmSite.isSiteUrl("https://example.com/one-piece"))
        assertEquals("puffytr.com", AnizmSite.hostOf("https://www.puffytr.com/x"))
    }

    // ------------------------------------------------------------------
    // Detay sayfası
    // ------------------------------------------------------------------

    @Test
    fun parsesDetailAndOnlyKeepsOwnEpisodes() {
        val pageUrl = "$base/bleach"
        val html = """
            <html><head>
              <meta property="og:image" content="https://cdn.example/og.jpg">
            </head><body>
              <h1 class="anizm_pageTitle">Bleach</h1>
              <div class="infoPosterImg"><img src="/storage/pcovers/bleach.jpg"></div>
              <div class="infoDesc">Bir lise öğrencisi ruhları korumaya başlar.</div>
              <div class="anizm_boxContent">
                <span class="dataTitle">Yapım Yılı</span><span>2004</span>
                <span class="dataTitle">Stüdyo</span><span>Studio Pierrot</span>
                <div class="dataValue"><a class="ui label" href="/kategoriler/2">Aksiyon</a></div>
                <div class="dataValue"><a class="ui label" href="/kategoriler/11">Doğaüstü Güçler</a></div>
              </div>
              <div class="episodeListTabContent">
                <div><a href="/bleach-2-bolum">2. Bölüm</a></div>
                <div><a href="/bleach-1-bolum">1. Bölüm</a></div>
                <div><a href="/bleach-1-bolum">1. Bölüm</a></div>
              </div>
              <div class="related"><a href="/naruto-5-bolum">Naruto 5. Bölüm</a></div>
            </body></html>
        """.trimIndent()
        val doc = Jsoup.parse(html, pageUrl)

        val detail = AnizmSite.parseDetail(doc, pageUrl, pageUrl)

        assertEquals("Bleach", detail.title)
        assertEquals("$base/storage/pcovers/bleach.jpg", detail.poster)
        assertTrue(detail.plot!!.contains("lise öğrencisi"))
        assertEquals(2004, detail.year)
        assertEquals(listOf("Aksiyon", "Doğaüstü Güçler"), detail.tags)
        assertEquals(2, detail.episodes.size)
        assertEquals(listOf(1, 2), detail.episodes.map { it.number })
        assertTrue(detail.episodes.all { it.url.contains("/bleach-") })
    }

    @Test
    fun episodesAreFoundInKnownContainersEvenWhenUrlHasNoNumber() {
        // Aniyomi seçicisi: "div.episodeListTabContent div > a" — bağlantı /bolum/{id}
        // biçiminde olabilir; numara ETİKETTEN okunmalı.
        val pageUrl = "$base/shingeki-no-kyojin"
        val html = """
            <html><body>
              <div class="episodeListTabContent">
                <div><a href="/bolum/9182">3. Bölüm</a></div>
                <div><a href="/bolum/9181">2. Bölüm</a></div>
                <div><a href="/bolum/9180">1. Bölüm</a></div>
              </div>
            </body></html>
        """.trimIndent()

        val episodes = AnizmSite.parseEpisodes(Jsoup.parse(html, pageUrl), base, pageUrl)
        assertEquals(3, episodes.size)
        assertEquals(listOf(1, 2, 3), episodes.map { it.number })
        assertTrue(episodes.all { it.url.startsWith("$base/bolum/") })
    }

    @Test
    fun detailWithoutEpisodesIsTreatedAsMovieByCaller() {
        val pageUrl = "$base/bleach-movie"
        val html = """
            <html><body>
              <h1 class="anizm_pageTitle">Bleach: Memories of Nobody İzle</h1>
              <div class="infoDesc">Film özeti.</div>
            </body></html>
        """.trimIndent()
        val detail = AnizmSite.parseDetail(Jsoup.parse(html, pageUrl), pageUrl, pageUrl)
        assertEquals("Bleach: Memories of Nobody", detail.title)
        assertTrue(detail.episodes.isEmpty())
    }

    // ------------------------------------------------------------------
    // v3 (2026-10) sözleşmesi — fixture'lı referans istemciden doğrulandı
    // ------------------------------------------------------------------

    @Test
    fun readsV3EpisodeSuffixes() {
        // "bolum"dan önce tire olmayan 53 One Piece bölümü
        assertEquals(41, AnizmSite.episodeNumberFromUrl("$base/one-piece-41bolum-izle"))
        assertEquals("$base/one-piece", AnizmSite.toSeriesUrl("$base/one-piece-41bolum-izle"))
        // final eki
        assertEquals(28, AnizmSite.episodeNumberFromUrl("$base/sousou-no-frieren-28-bolum-final-izle"))
        assertEquals("$base/sousou-no-frieren", AnizmSite.toSeriesUrl("$base/sousou-no-frieren-28-bolum-final-izle"))
        // çift bölüm YALNIZCA etiketle ayrılır (yoksa "ghost-22-1-bolum" gibi
        // seri adı sayıyla biten URL'ler yanlış bölünür)
        assertEquals(
            "$base/one-piece",
            AnizmSite.toSeriesUrl("$base/one-piece-215-216-bolum-izle", "215-216. Bölüm")
        )
        assertEquals("$base/ghost-22", AnizmSite.toSeriesUrl("$base/ghost-22-1-bolum-izle", "1. Bölüm"))
    }

    @Test
    fun upgradesCoverToLargeVersion() {
        assertEquals("$base/storage/pcovers/123l.webp", AnizmSite.largeCover("$base/storage/pcovers/123.webp"))
        assertEquals("$base/storage/pcovers/123l.webp", AnizmSite.largeCover("$base/storage/pcovers/123.webp?v=2"))
        // kapak olmayan adresler dokunulmadan kalır
        assertEquals("$base/upload/assets/x.webp", AnizmSite.largeCover("$base/upload/assets/x.webp"))
    }

    @Test
    fun resolvesDeferredRelativePosterToAbsoluteLarge() {
        // v3 ana sayfa: adres `data-v3-deferred-src` içinde ve GÖRELİ gelebilir
        val img = Jsoup.parse(
            """<a class="v3-catalog-poster" href="/sousou-no-frieren"><img data-v3-deferred-src="/storage/pcovers/4321.webp" alt="Sousou no Frieren posteri"></a>""",
            "$base/"
        ).selectFirst("img")
        assertEquals("$base/storage/pcovers/4321l.webp", AnizmSite.imageUrl(img, base))
    }

    @Test
    fun invalidTitlesAreRejected() {
        assertTrue(AnizmSite.isInvalidTitle(null))
        assertTrue(AnizmSite.isInvalidTitle("İzle"))
        assertTrue(AnizmSite.isInvalidTitle("12"))
        assertTrue(AnizmSite.isInvalidTitle("kategoriler"))
        assertTrue(!AnizmSite.isInvalidTitle("One Piece"))
    }
}
