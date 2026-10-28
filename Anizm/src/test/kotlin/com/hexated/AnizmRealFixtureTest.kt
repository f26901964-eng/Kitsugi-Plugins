package com.hexated

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GERÇEK site dökümleriyle (26.10.2026, anizm.com.tr canlı yakalama) regresyon
 * testleri. Fixture'lar `src/test/resources/live/` altındadır ve CI deposunun
 * `site-dump-data` dalındaki ağsız kopyalardır:
 *
 *  - anizm_episode_onepiece_1.html : One Piece 1. bölüm (567 KB, 3 çevirmen)
 *  - anizm_detail_onepiece.html    : One Piece detay (1175 bölüm kaydı)
 *  - anizm_home.html               : ana sayfa (v3 kartlar)
 *  - anizm_search_naruto.html      : /arama?q=naruto sonuçları
 *  - anizm_cevirmen_*.json         : gerçek çevirmen XHR yanıtları (33 kaynak)
 *  - anizmplayer_*.html            : gerçek packed FirePlayer oynatıcı sayfaları
 *
 * Amaç: "bölüm açıldığında hiçbir fansub/kaynak eksik düşmesin" kabul kriterini
 * kanıtla kilitlemek. Testlerin TAMAMI ağsızdır; suspend orkestrasyonunun canlı
 * doğrulaması LiveChainProbeTest'te (LIVE_PROBE=1) koşar.
 */
class AnizmRealFixtureTest {

    private val base = "https://anizm.com.tr"
    private val episodeUrl = "$base/one-piece-1-bolum-izle"

    private fun live(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/live/$name")) { "fixture yok: $name" }
            .bufferedReader().use { it.readText() }

    /** Saf ayrıştırma testleri HTTP çağırmaz; yine de kurucu bir yüzey ister. */
    private val streams = AnizmStreams("Anizm", { base }, NoopHttp)

    private object NoopHttp : AnizmHttp {
        override suspend fun get(url: String, referer: String?): String? = null
        override suspend fun getXhr(url: String, referer: String?): String? = null
        override suspend fun location(url: String, referer: String?): String? = null
        override suspend fun getWith(url: String, extra: Map<String, String>): String? = null
        override suspend fun postJson(url: String, json: String, referer: String?): String? = null
        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            referer: String?
        ): String? = null
    }

    // ------------------------------------------------------------------
    // Bölüm sayfası → çevirmen düğümleri
    // ------------------------------------------------------------------

    @Test
    fun realEpisodePageExposesExactlyThreeV3Translators() {
        val doc = Jsoup.parse(live("anizm_episode_onepiece_1.html"), episodeUrl)
        val buttons = streams.translatorButtons(doc, episodeUrl)

        assertEquals(3, buttons.size)
        val expected = listOf(
            "$base/bolum/18853/cevirmen/18853" to "Güncellenecek",
            "$base/bolum/18853/cevirmen/83191" to "VictoriaSubs",
            "$base/bolum/18853/cevirmen/109648" to "SomeSubs"
        )
        for ((url, name) in expected) {
            val t = buttons[url]
            assertNotNull("$url düğümü bulunamadı", t)
            assertEquals(name, t!!.name)
        }
    }

    @Test
    fun missingDataSourcesIsRebuiltFromDataTranslatorId() {
        // data-sources BOŞ; bolum kimliği raporlama bağlantısından, çevirmen
        // kimliği data-translator-id'den gelmeli — tahmin değil, kanıt.
        val html = """
            <html><body>
              <a id="v3-report" href="$base/raporver/bolum/555/video/0">rapor</a>
              <button class="v3-translator-button" data-translator-id="777"
                      data-v3-translator title="TekSub" aria-pressed="false"></button>
            </body></html>
        """.trimIndent()
        val pageUrl = "$base/test-1-bolum-izle"
        val doc = Jsoup.parse(html, pageUrl)
        val buttons = streams.translatorButtons(doc, pageUrl)

        assertEquals(1, buttons.size)
        assertTrue(buttons.containsKey("$base/bolum/555/cevirmen/777"))
    }

    @Test
    fun translatorButtonsNeverFabricateNumericIds() {
        // Tek gerçek düğüm varken 1..3 gibi uydurma kimlik ÜRETİLMEMELİ.
        val html = """
            <html><body>
              <a href="$base/raporver/bolum/555/video/0">rapor</a>
              <button class="v3-translator-button" data-translator-id="777"
                      data-v3-translator data-sources="$base/bolum/555/cevirmen/777"
                      title="TekSub"></button>
            </body></html>
        """.trimIndent()
        val pageUrl = "$base/test-1-bolum-izle"
        val buttons = streams.translatorButtons(Jsoup.parse(html, pageUrl), pageUrl)

        assertEquals(1, buttons.size)
        assertEquals("$base/bolum/555/cevirmen/777", buttons.keys.single())
    }

    // ------------------------------------------------------------------
    // Çevirmen XHR'ları → 33 video adayının TAMAMI (saf ayrıştırma)
    // ------------------------------------------------------------------

    @Test
    fun allThirtyThreeVideoCandidatesAreParsedFromRealTranslatorJson() {
        data class Case(val file: String, val cid: String, val fansub: String, val count: Int)
        val cases = listOf(
            Case("anizm_cevirmen_18853.json", "18853", "Güncellenecek", 24),
            Case("anizm_cevirmen_83191.json", "83191", "VictoriaSubs", 6),
            Case("anizm_cevirmen_109648.json", "109648", "SomeSubs", 3)
        )
        val all = mutableListOf<AnizmStreams.Source>()
        for (case in cases) {
            val sourcesUrl = "$base/bolum/18853/cevirmen/${case.cid}"
            val sources = streams.parseTranslatorResponse(sourcesUrl, case.fansub, live(case.file))
            assertEquals("${case.cid} kaynak sayısı", case.count, sources.size)
            assertTrue(sources.all { it.fansub == case.fansub })
            all += sources
        }

        // 24 + 6 + 3 = 33 — tek bir kaynak bile düşmemeli.
        assertEquals(33, all.size)
        assertEquals(33, all.map { it.url }.distinct().size)
        assertTrue(all.all { it.url.contains("/bolum/18853/video/") })
        assertTrue(all.all { it.url.substringAfterLast("/video/").all(Char::isDigit) })

        // Her üç fansub da temsil ediliyor.
        assertEquals(setOf("Güncellenecek", "VictoriaSubs", "SomeSubs"), all.map { it.fansub }.toSet())
        // Gerçek host adları korunuyor (sonraki çözüm aşamasının etiketi).
        val hosts = all.map { it.host }.toSet()
        listOf(
            "Aincrad (Reklamsız)", "GDrive", "VidBM", "Sibnet", "SendVid",
            "SuperVideo", "Abyss", "UQload", "DoodStream", "Voe"
        ).forEach { assertTrue("host eksik: $it", hosts.contains(it)) }
        // Kör tahmin kaldırıldı: hiçbir aday uydurma /cevirmen/1|2|3 yolu içermez.
        assertTrue(all.none { Regex("/cevirmen/[123]$").containsMatchIn(it.url) })
    }

    @Test
    fun singleVideoTranslatorResponseBuildsCanonicalVideoUrl() {
        val body = """{"success":true,"videos":[{"id":42,"name":"GDrive","watchTogether":false}]}"""
        val sources = streams.parseTranslatorResponse(
            "$base/bolum/555/cevirmen/777", "TekSub", body
        )
        assertEquals(1, sources.size)
        assertEquals("$base/bolum/555/video/42", sources.single().url)
        assertEquals("GDrive", sources.single().host)
    }

    // ------------------------------------------------------------------
    // Detay sayfası → bölüm ızgarası (1175 bölüm, tiresiz URL'ler dahil)
    // ------------------------------------------------------------------

    @Test
    fun realDetailPageParsesEveryEpisodeIncludingDashlessAndMergedUrls() {
        val pageUrl = "$base/one-piece"
        val doc = Jsoup.parse(live("anizm_detail_onepiece.html"), pageUrl)
        val episodes = AnizmSite.parseEpisodes(doc, base, pageUrl)

        // 1170 tekil + 5 birleşik bölüm (215-216 vb.) = 1175; birleşik olanlar
        // artık hedef seriye dahil edilip listeden DÜŞMÜYOR.
        assertEquals(1175, episodes.size)
        val numbers = episodes.mapNotNull { it.number }
        // 5 birleşik bölüm ("215-216. Bölüm") kasıtlı olarak 0 numara alır.
        assertEquals(5, numbers.filter { it == 0 }.size)
        val regular = numbers.filter { it > 0 }
        assertEquals(1, regular.minOrNull())
        // Sitenin iç sırası (data-episode-number) kayık: etiket/URL 1180'de bitiyor.
        assertEquals(1180, regular.maxOrNull())
        assertEquals(regular.size, regular.distinct().size)
        // Tiresiz One Piece URL'si (`-41bolum-izle`) kaybolmamalı.
        val ep41 = episodes.first { it.number == 41 }
        assertEquals("$base/one-piece-41bolum-izle", ep41.url)
        // Sıralı (0'lar başta, sonra numara sırası).
        assertEquals(numbers, numbers.sorted())
    }

    @Test
    fun realDetailPageExposesTitleAndLargePoster() {
        val pageUrl = "$base/one-piece"
        val doc = Jsoup.parse(live("anizm_detail_onepiece.html"), pageUrl)
        val detail = AnizmSite.parseDetail(doc, base, pageUrl)

        assertTrue(detail.title.contains("One Piece", ignoreCase = true))
        assertNotNull(detail.poster)
        assertTrue(detail.poster!!.startsWith("http"))
        assertTrue(detail.poster!!.contains("/storage/pcovers/"))
        assertEquals(1175, detail.episodes.size)
    }

    // ------------------------------------------------------------------
    // Ana sayfa ve arama kartları
    // ------------------------------------------------------------------

    @Test
    fun realHomePageCardsAreAllCollected() {
        val doc = Jsoup.parse(live("anizm_home.html"), "$base/")
        val cards = AnizmSite.parseCards(doc, "$base/")

        // 65 benzersiz article (39 seri + 18 bölüm kartı + 8 hero slaytı);
        // seri URL'sine indirgenip tekilleşince 55 kart kalır. Hero slaytları
        // sayfa düzenine göre değişebileceğinden alt/üst sınırla doğrulanır.
        assertTrue("kart sayısı: ${cards.size}", cards.size in 51..65)
        val cyberpunk = cards.first { it.url.endsWith("/cyberpunk-edgerunners-2") }
        assertTrue(cyberpunk.poster!!.startsWith("http"))
        assertTrue(cyberpunk.poster!!.contains("l.webp"))
        // Bölüm kartı seri sayfasına eşlenir, bölüm ipucu korunur.
        val sirotan = cards.first { it.url.endsWith("/sirotan") }
        assertEquals(4, sirotan.episodeHint)
    }

    @Test
    fun realSearchPageCardsKeepScoreAndPoster() {
        val doc = Jsoup.parse(live("anizm_search_naruto.html"), "$base/arama?q=naruto")
        val cards = AnizmSite.parseCards(doc, "$base/arama?q=naruto")

        assertEquals(11, cards.size)
        val boruto = cards.first { it.name.contains("Boruto") }
        assertEquals(6.0, boruto.score)
        assertEquals("$base/storage/pcovers/9092l.webp", boruto.poster)
        assertTrue(boruto.url.startsWith("$base/"))
    }

    // ------------------------------------------------------------------
    // Packed FirePlayer (Dean Edwards) açıcı
    // ------------------------------------------------------------------

    @Test
    fun packedFirePlayerPagesUnpackToTheCorrectHash() {
        val hashes = listOf(
            "002d860cc21d0ab584a0e7f0dd3a9d49",
            "053b800a27404b77a2068da3169e36dd",
            "094b7cd1111ed23f5b64a54744cb25b7",
            "09d1d2fa86fbc25c85269b522d96b9a9"
        )
        for (hash in hashes) {
            val page = live("anizmplayer_$hash.html")
            val unpacked = AnizmPacker.unpack(page)
            assertNotNull("packed açılamadı: $hash", unpacked)
            assertTrue(unpacked!!.contains("FirePlayer"))
            assertTrue(unpacked.contains("jwPlayer"))
            assertEquals(hash, AnizmPacker.firePlayerKey(unpacked))
            // Paketli çağrı imzası açık metne dönmüş olmalı.
            assertTrue(!unpacked.contains("eval(function(p,a,c,k,e,d)"))
        }
    }

    @Test
    fun packerReturnsNullForPlainHtml() {
        assertNull(AnizmPacker.unpack(live("anizm_search_naruto.html")))
        assertNull(AnizmPacker.firePlayerKey(null))
        assertNull(AnizmPacker.firePlayerKey("düz metin"))
    }
}
