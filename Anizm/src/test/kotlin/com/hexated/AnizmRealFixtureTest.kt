package com.hexated

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

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
 * kanıtla kilitlemek. Sentetik seçici testleri [AnizmSiteTest]'tedir.
 */
class AnizmRealFixtureTest {

    private val base = "https://anizm.com.tr"
    private val episodeUrl = "$base/one-piece-1-bolum-izle"

    private fun live(name: String): String =
        requireNotNull(javaClass.getResourceAsStream("/live/$name")) { "fixture yok: $name" }
            .bufferedReader().use { it.readText() }

    /** Ağ çağrılarını kaydeden sahte HTTP yüzeyi — yalnızca XHR uçlarını yanıtlar. */
    private class RecordingHttp(
        private val responder: (suspend (String) -> String?)
    ) : AnizmHttp {
        val xhrCalls = mutableListOf<String>()
        override suspend fun get(url: String, referer: String?): String? = null
        override suspend fun getXhr(url: String, referer: String?): String? {
            xhrCalls += url
            return responder(url)
        }
        override suspend fun location(url: String, referer: String?): String? = null
        override suspend fun getWith(url: String, extra: Map<String, String>): String? = null
        override suspend fun postJson(url: String, json: String, referer: String?): String? = null
        override suspend fun postForm(
            url: String,
            form: Map<String, String>,
            referer: String?
        ): String? = null
    }

    private fun streams(http: AnizmHttp) =
        AnizmStreams("Anizm", { base }, http)

    // ------------------------------------------------------------------
    // Bölüm sayfası → çevirmen düğümleri
    // ------------------------------------------------------------------

    @Test
    fun realEpisodePageExposesExactlyThreeV3Translators() {
        val doc = Jsoup.parse(live("anizm_episode_onepiece_1.html"), episodeUrl)
        val buttons = streams(RecordingHttp { null }).translatorButtons(doc, episodeUrl)

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
        val doc = Jsoup.parse(html, "$base/test-1-bolum-izle")
        val buttons = streams(RecordingHttp { null })
            .translatorButtons(doc, "$base/test-1-bolum-izle")

        assertEquals(1, buttons.size)
        assertTrue(buttons.containsKey("$base/bolum/555/cevirmen/777"))
    }

    // ------------------------------------------------------------------
    // Çevirmen XHR'ları → 33 video adayının TAMAMI
    // ------------------------------------------------------------------

    @Test
    fun allThirtyThreeVideoCandidatesAreCollectedFromRealTranslatorJson() = runBlocking {
        val doc = Jsoup.parse(live("anizm_episode_onepiece_1.html"), episodeUrl)
        val http = RecordingHttp { url ->
            when {
                url.endsWith("/cevirmen/18853") -> live("anizm_cevirmen_18853.json")
                url.endsWith("/cevirmen/83191") -> live("anizm_cevirmen_83191.json")
                url.endsWith("/cevirmen/109648") -> live("anizm_cevirmen_109648.json")
                else -> null
            }
        }
        val sources = streams(http).collectV3(doc, episodeUrl, base)

        // 24 + 6 + 3 = 33 — tek bir kaynak bile düşmemeli.
        assertEquals(33, sources.size)
        assertEquals(33, sources.map { it.url }.distinct().size)
        assertTrue(sources.all { it.url.contains("/bolum/18853/video/") })
        assertTrue(sources.all { it.url.substringAfterLast("/video/").all(Char::isDigit) })

        // Her üç fansub da temsil ediliyor.
        assertEquals(
            setOf("Güncellenecek", "VictoriaSubs", "SomeSubs"),
            sources.map { it.fansub }.toSet()
        )
        // Gerçek host adları korunuyor (sonraki çözüm aşamasının etiketi).
        val hosts = sources.map { it.host }.toSet()
        listOf(
            "Aincrad (Reklamsız)", "GDrive", "VidBM", "Sibnet", "SendVid",
            "SuperVideo", "Abyss", "UQload", "DoodStream", "Voe"
        ).forEach { assertTrue("host eksik: $it", hosts.contains(it)) }

        // KÖR TAHMİN KALDIRILDI: uydurma /cevirmen/1|2|3 isteği atılmamalı.
        val fabricated = http.xhrCalls.filter {
            Regex("/cevirmen/[123]$").containsMatchIn(it)
        }
        assertTrue("uydurma çevirmen isteği: $fabricated", fabricated.isEmpty())
        assertEquals(3, http.xhrCalls.size)
    }

    @Test
    fun singleTranslatorPageNeverGuessesTranslatorIds() = runBlocking {
        val html = """
            <html><body>
              <a href="$base/raporver/bolum/555/video/0">rapor</a>
              <button class="v3-translator-button" data-translator-id="777"
                      data-v3-translator data-sources="$base/bolum/555/cevirmen/777"
                      title="TekSub"></button>
            </body></html>
        """.trimIndent()
        val pageUrl = "$base/test-1-bolum-izle"
        val doc = Jsoup.parse(html, pageUrl)
        val http = RecordingHttp {
            """{"success":true,"videos":[{"id":42,"name":"GDrive","watchTogether":false}]}"""
        }
        val sources = streams(http).collectV3(doc, pageUrl, base)

        assertEquals(1, sources.size)
        assertEquals("$base/bolum/555/video/42", sources.single().url)
        assertEquals(listOf("$base/bolum/555/cevirmen/777"), http.xhrCalls)
    }

    // ------------------------------------------------------------------
    // Detay sayfası → bölüm ızgarası (1175 bölüm, tiresiz URL'ler dahil)
    // ------------------------------------------------------------------

    @Test
    fun realDetailPageParsesEveryEpisodeIncludingDashlessUrls() {
        val pageUrl = "$base/one-piece"
        val doc = Jsoup.parse(live("anizm_detail_onepiece.html"), pageUrl)
        val episodes = AnizmSite.parseEpisodes(doc, base, pageUrl)

        assertEquals(1175, episodes.size)
        val numbers = episodes.mapNotNull { it.number }
        // 5 birleşik bölüm ("215-216. Bölüm" gibi) kasıtlı olarak 0 numara alır;
        // normal bölümler 1..1181 aralığında, eksik numaralar (filler atlamaları) mümkün.
        val merged = numbers.filter { it == 0 }
        assertEquals(5, merged.size)
        val regular = numbers.filter { it > 0 }
        assertEquals(1, regular.minOrNull())
        assertEquals(1181, regular.maxOrNull())
        assertEquals(regular.size, regular.distinct().size)
        // Tiresiz One Piece URL'leri (`-41bolum-izle`) kaybolmamalı.
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
            // Paketli belirteçler (örn. 1P, x47) açık metne dönmüş olmalı.
            assertFalse(unpacked.contains("eval(function(p,a,c,k,e,d)"))
        }
    }

    @Test
    fun packerReturnsNullForPlainHtml() {
        assertNull(AnizmPacker.unpack(live("anizm_search_naruto.html")))
        assertNull(AnizmPacker.firePlayerKey(null))
        assertNull(AnizmPacker.firePlayerKey("düz metin"))
    }
}
