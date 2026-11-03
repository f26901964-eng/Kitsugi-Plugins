package com.keyiflerolsun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

class DiziBoxSiteDumpsContractTest {

    private fun readSiteDump(fileName: String): String {
        val candidates = listOf(
            File("site-dumps/$fileName"),
            File("DiziBox/site-dumps/$fileName"),
            File("../DiziBox/site-dumps/$fileName"),
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("Site dökümü bulunamadı: $fileName", found)
        return found!!.readText(Charsets.UTF_8)
    }

    // ────────── 1. Gerçek Site Dökümü Sözleşme Testleri (site-dumps/*.html) ──────────

    @Test
    fun homepageDumpExtractsEpisodeAndSeriesCardsWithoutFlagTrapOrHeaderLeak() {
        val html = readSiteDump("homepage.html")
        val baseUrl = "https://www.dizibox.live/"
        val doc = Jsoup.parse(html, baseUrl)

        val cards = DiziBoxPlayerParser.parseListingCards(doc, baseUrl)
        assertTrue("Ana sayfa kartları boş olmamalı", cards.size >= 20)

        // 1) A-Z alfabetik menüdeki ~3000 iskelet link sızmamalı
        assertTrue(cards.size < 100)

        // 2) Bölüm kartlarında ilk <img> olan altyazi.png / dublaj.png ASLA afiş seçilmemeli
        cards.forEach { card ->
            val poster = card.posterUrl.orEmpty()
            assertFalse("Dil bayrağı afiş olarak seçildi: ${card.title} -> $poster", poster.contains("altyazi.png"))
            assertFalse("Dil bayrağı afiş olarak seçildi: ${card.title} -> $poster", poster.contains("dublaj.png"))
        }

        // 3) Bölüm kartı: "Tracker 4.Sezon 5.Bölüm" yatay kapak görseliyle gelmeli
        val trackerEp = cards.firstOrNull {
            it.url == "https://www.dizibox.live/tracker-4-sezon-5-bolum-izle/"
        }
        assertNotNull("Tracker bölüm kartı bulunmalı", trackerEp)
        assertEquals("Tracker 4.Sezon 5.Bölüm", trackerEp!!.title)
        assertEquals(
            "https://www.dizibox.live/wp-content/uploads/kapaklar/tracker-220x140.jpg",
            trackerEp.posterUrl,
        )
        assertTrue(trackerEp.isEpisode)

        // 4) Yeni diziler vitrini: VisionQuest IMDb puanı, yılı ve dikey afişiyle gelmeli
        val visionQuest = cards.firstOrNull {
            it.url == "https://www.dizibox.live/diziler/visionquest/"
        }
        assertNotNull("VisionQuest dizi kartı bulunmalı", visionQuest)
        assertEquals("VisionQuest", visionQuest!!.title)
        assertEquals("8.0", visionQuest.imdbScore)
        assertEquals(2026, visionQuest.year)
        assertEquals(
            "https://www.dizibox.live/wp-content/uploads/afisler/visionquest-200x290.jpg",
            visionQuest.posterUrl,
        )
    }

    @Test
    fun seriesVisionQuestDumpParsesFullMetadataActiveSeasonAndSkipsRedundantRefetch() {
        val html = readSiteDump("series-visionquest.html")
        val seriesUrl = "https://www.dizibox.live/diziler/visionquest/"
        val doc = Jsoup.parse(html, seriesUrl)

        val detail = DiziBoxPlayerParser.parseSeriesDetail(doc, seriesUrl)
        assertNotNull(detail)
        detail!!

        assertEquals("VisionQuest", detail.title)
        assertEquals(seriesUrl, detail.canonicalUrl)
        assertEquals(
            "https://www.dizibox.live/wp-content/uploads/afisler/visionquest-200x290.jpg",
            detail.posterUrl,
        )
        assertEquals(2026, detail.year)
        assertEquals("8.0", detail.imdbScore)
        assertNotNull(detail.plot)
        assertTrue(detail.plot!!.startsWith("Yeniden canlandırılan Vision"))
        assertTrue(detail.tags.containsAll(listOf("Aksiyon", "Bilimkurgu", "Drama", "Gerilim")))
        assertTrue(detail.actors.contains("Emily Hampshire"))
        assertTrue(detail.actors.contains("Paul Bettany"))
        assertNotNull(detail.trailerUrl)
        assertTrue(detail.trailerUrl!!.contains("youtube.com/embed/oSVTAUKyrL8"))

        // Aktif 1. sezon zaten sayfada (`a.hover`); fazladan HTTP isteği atılmamalı!
        assertTrue("Pasif sezon listesi tek sezonlu dizide boş olmalı", detail.inactiveSeasonUrls.isEmpty())
        assertEquals(listOf("https://www.dizibox.live/sezonlar/visionquest-1-sezon/"), detail.allSeasonUrls)

        // 8 bölüm eksiksiz ve tarihli (14 Ekim 2026) ayrıştırılmalı
        assertEquals(8, detail.activeEpisodes.size)
        val ep1 = detail.activeEpisodes.first()
        assertEquals("https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/", ep1.url)
        assertEquals(1, ep1.season)
        assertEquals(1, ep1.episode)
        assertEquals(
            "https://www.dizibox.live/wp-content/uploads/kapaklar/visionquest-300x81.jpg",
            ep1.posterUrl,
        )
        assertNotNull(ep1.dateMillis)
        val parsedDate = Instant.ofEpochMilli(ep1.dateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
        assertEquals(2026, parsedDate.year)
        assertEquals(10, parsedDate.monthValue)
        assertEquals(14, parsedDate.dayOfMonth)
    }

    @Test
    fun episodeVisionQuestDumpResolvesArchiveLinkPlayerTabsAndBlocksSidebarPollution() {
        val html = readSiteDump("episode-visionquest-1-1.html")
        val episodeUrl = "https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/"
        val doc = Jsoup.parse(html, episodeUrl)

        // 1) Ana dizi sayfasına yönlendirme linki (#archive-box a.archive-title)
        assertEquals(
            "https://www.dizibox.live/diziler/visionquest/",
            DiziBoxPlayerParser.archiveBoxLink(doc, episodeUrl),
        )

        // 2) Bölüm sayfasındaki sidebar "Gündemdekiler" ve A-Z menü kart olarak sızmamalı
        val leakedCards = DiziBoxPlayerParser.parseListingCards(doc, episodeUrl)
        assertTrue("Sidebar veya üst menü sızmamalı: $leakedCards", leakedCards.isEmpty())

        // 3) Oynatıcı sekmeleri: DBX Pro, Moly+, Odnok sırasıyla ve etiketleriyle okunmalı
        val tabs = DiziBoxPlayerParser.playerTabs(doc, episodeUrl)
        assertEquals(3, tabs.size)
        assertEquals("DBX Pro", tabs[0].label)
        assertEquals("https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/", tabs[0].url)
        assertEquals("Moly+", tabs[1].label)
        assertEquals("https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/2/", tabs[1].url)
        assertEquals("Odnok", tabs[2].label)
        assertEquals("https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/3/", tabs[2].url)

        // 4) Ana iframe king.php olmalı ve wmode=opaque sorgunun başına eklenmeli
        val iframes = DiziBoxPlayerParser.playerIframes(doc, episodeUrl)
        assertEquals(1, iframes.size)
        assertTrue(iframes[0].startsWith("https://www.dizibox.live/player/king/king.php?v=7bc25b"))
        assertTrue(
            DiziBoxPlayerParser.withWOpaque(iframes[0])
                .startsWith("https://www.dizibox.live/player/king/king.php?wmode=opaque&v=7bc25b"),
        )

        // 5) Dizi sayfası ulaşılamazsa bölüm sayfası yedeği çalışmalı
        val fallback = DiziBoxPlayerParser.parseEpisodePageFallback(doc, episodeUrl)
        assertNotNull(fallback)
        assertEquals("VisionQuest", fallback!!.title)
        assertEquals(8, fallback.activeEpisodes.size)
    }

    // ────────── 2. Sayfalama, Arama ve Çok Sezonlu Dizi Sözleşmeleri ──────────

    @Test
    fun mainPageUrlStripsPage1ToAvoidRedirectAndPreservesQueryParams() {
        assertEquals(
            "https://www.dizibox.live/tum-bolumler/",
            DiziBoxPlayerParser.buildMainPageUrl("https://www.dizibox.live/tum-bolumler/page/SAYFA/", 1),
        )
        assertEquals(
            "https://www.dizibox.live/tum-bolumler/?tip=populer",
            DiziBoxPlayerParser.buildMainPageUrl("https://www.dizibox.live/tum-bolumler/page/SAYFA/?tip=populer", 1),
        )
        assertEquals(
            "https://www.dizibox.live/dizi-arsivi/page/3/",
            DiziBoxPlayerParser.buildMainPageUrl("https://www.dizibox.live/dizi-arsivi/page/SAYFA/", 3),
        )

        val pagedDoc = Jsoup.parse(
            """<div class="wp-pagenavi"><span class="current">1</span><a href="https://www.dizibox.live/dizi-arsivi/page/2/">2</a></div>""",
            "https://www.dizibox.live/dizi-arsivi/",
        )
        assertTrue(DiziBoxPlayerParser.hasNextPage(pagedDoc, 1))
        assertFalse(DiziBoxPlayerParser.hasNextPage(pagedDoc, 2))
    }

    @Test
    fun searchPagePrioritizesSeriesCardsOverEpisodeMatches() {
        val searchUrl = "https://www.dizibox.live/search/visionquest/"
        val html = """
            <main>
              <article class="detailed-article">
                <figure><img src="https://www.dizibox.live/wp-content/uploads/afisler/visionquest-200x290.jpg" class="afis"></figure>
                <h3><a href="https://www.dizibox.live/diziler/visionquest/">VisionQuest</a></h3>
                <span><b>8.0</b></span>
              </article>
              <section class="episodes">
                <article class="article-episode-card">
                  <a href="https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/" class="season-episode">1.Sezon 1.Bölüm</a>
                  <a href="https://www.dizibox.live/visionquest-1-sezon-1-bolum-izle/" class="post-title"><small>VisionQuest</small></a>
                </article>
              </section>
            </main>
        """.trimIndent()
        val cards = DiziBoxPlayerParser.parseSearchCards(Jsoup.parse(html, searchUrl), searchUrl)
        assertEquals(1, cards.size)
        assertEquals("https://www.dizibox.live/diziler/visionquest/", cards[0].url)
    }

    @Test
    fun multiSeasonSeriesMarksOnlyNonHoverSeasonsAsInactiveAndUpscalesThumbnails() {
        val seriesUrl = "https://www.dizibox.live/diziler/tracker-hd/"
        val html = """
            <div class="tv-overview">
              <h1><a href="$seriesUrl">Tracker</a></h1>
              <figure><img src="/wp-content/uploads/afisler/tracker-200x290.jpg" class="main-cover"></figure>
              <div id="seasons-list">
                <a class="label hover" href="https://www.dizibox.live/sezonlar/tracker-1-sezon/">1. Sezon</a>
                <a class="label" href="https://www.dizibox.live/sezonlar/tracker-2-sezon/">2. Sezon</a>
                <a class="label" href="https://www.dizibox.live/sezonlar/tracker-3-sezon/">3. Sezon</a>
              </div>
              <section id="category-posts">
                <article class="grid-box">
                  <div class="post-title"><a class="season-episode" href="https://www.dizibox.live/tracker-1-sezon-1-bolum-hd-izle/">1. Sezon 1. Bölüm</a></div>
                </article>
              </section>
            </div>
        """.trimIndent()
        val detail = DiziBoxPlayerParser.parseSeriesDetail(Jsoup.parse(html, seriesUrl), seriesUrl)!!
        assertEquals(1, detail.activeEpisodes.size)
        assertEquals(
            listOf(
                "https://www.dizibox.live/sezonlar/tracker-2-sezon/",
                "https://www.dizibox.live/sezonlar/tracker-3-sezon/",
            ),
            detail.inactiveSeasonUrls,
        )

        // WordPress -50x50.jpg ve /50x50/ büyütülmeli; yatay -220x140.jpg bozulmamalı
        assertEquals(
            "https://www.dizibox.live/wp-content/uploads/afisler/visionquest-200x290.jpg",
            DiziBoxPlayerParser.upscaleThumbnail("https://www.dizibox.live/wp-content/uploads/afisler/visionquest-50x50.jpg"),
        )
        assertEquals(
            "https://www.dizibox.live/wp-content/uploads/kapaklar/tracker-220x140.jpg",
            DiziBoxPlayerParser.upscaleThumbnail("https://www.dizibox.live/wp-content/uploads/kapaklar/tracker-220x140.jpg"),
        )
    }

    // ────────── 3. Kripto, Sarmalayıcı (king/moly/haydi) ve Çoklu Oynatıcı Testleri ──────────

    @Test
    fun pureJvmCryptoJsRoundtripAndWrapperUnpackersWorkWithoutAndroidRuntime() {
        val secretHtml = """<iframe src="https://dbx.molystream.org/embed/xyz987" allowfullscreen></iframe>"""
        val pass = "dbx_secret_key_2026"
        val encryptedJson = CryptoJS.encrypt(pass, secretHtml)

        // 1) Değişken üzerinden CryptoJS.AES.decrypt(encrypted, "pass")
        val kingHtml = """
            <script>
              var payload = '$encryptedJson';
              var out = CryptoJS.AES.decrypt(payload, "$pass").toString(CryptoJS.enc.Utf8);
              document.write(out);
            </script>
        """.trimIndent()
        val decodedKing = DiziBoxPlayerParser.decodeWrapperHtml(kingHtml)
        val nested = DiziBoxPlayerParser.nestedIframes(
            Jsoup.parse(decodedKing, "https://www.dizibox.live/player/king/king.php"),
            "https://www.dizibox.live/player/king/king.php",
        )
        assertEquals(listOf("https://dbx.molystream.org/embed/xyz987"), nested)

        // 1b) 3 argümanlı CryptoJSAesJson biçimi: CryptoJS.AES.decrypt('{"ct":...}', pass, {format: CryptoJSAesJson})
        val saltedBytes = CryptoJS.base64Decode(encryptedJson)
        val saltHex = saltedBytes.copyOfRange(8, 16).joinToString("") { "%02x".format(it) }
        val ctOnlyBase64 = CryptoJS.base64Encode(saltedBytes.copyOfRange(16, saltedBytes.size))
        val aesJsonScript = """
            <script>
              var raw = CryptoJS.AES.decrypt('{"ct":"$ctOnlyBase64","s":"$saltHex"}', '$pass', {format: CryptoJSAesJson}).toString(CryptoJS.enc.Utf8);
            </script>
        """.trimIndent()
        val decodedJson = DiziBoxPlayerParser.decodeWrapperHtml(aesJsonScript)
        assertTrue(decodedJson.contains("https://dbx.molystream.org/embed/xyz987"))

        // 2) Dean Edwards p,a,c,k,e,d Vidmoly betiği + İngilizce altyazı
        val packed = """eval(function(p,a,c,k,e,d){while(c--)if(k[c])p=p.replace(new RegExp('\\b'+c.toString(a)+'\\b','g'),k[c]);return p}('0.1({2:[{3:"4://5.6.7/8/9.a"}],b:[{3:"4://c.7/d.e",f:"g",h:"i"}]});',19,19,'player|setup|sources|file|https|box|vidmoly|biz|hls|master|m3u8|tracks|vidmoly|en|vtt|label|English|kind|captions'.split('|'),0,{}))"""
        val unpacked = DiziBoxPlayerParser.decodeWrapperHtml("<script>$packed</script>")
        val sources = DiziBoxPlayerParser.mediaSources(unpacked)
        val subs = DiziBoxPlayerParser.subtitleTracks(unpacked)
        assertEquals(1, sources.size)
        assertEquals("https://box.vidmoly.biz/hls/master.m3u8", sources[0].url)
        assertEquals(1, subs.size)
        assertEquals("English", subs[0].label)
        assertEquals("https://vidmoly.biz/en.vtt", subs[0].url)
    }

    @Test
    fun parsesOkRuSibnetMailRuVkMolyMirrorAndGeoBlockNotice() {
        // 1) OK.ru hem HTML data-options hem JSON metadata
        val okHtml = """<div data-module="OKVideo" data-options="{&quot;flashvars&quot;:{&quot;metadata&quot;:&quot;{\&quot;ondemandHls\&quot;:\&quot;https://vd.okcdn.ru/hls/master.m3u8?sig=1\\u0026ct=8\&quot;,\&quot;videos\&quot;:[{\&quot;name\&quot;:\&quot;full\&quot;,\&quot;url\&quot;:\&quot;https://vd.okcdn.ru/mp4/1080.mp4?sig=2\&quot;},{\&quot;name\&quot;:\&quot;hd\&quot;,\&quot;url\&quot;:\&quot;https://vd.okcdn.ru/mp4/720.mp4\&quot;}]}&quot;}}"></div>"""
        val okStreams = DiziBoxPlayerParser.parseOkRuStreams(okHtml)
        assertEquals(3, okStreams.size)
        assertTrue(okStreams.any { it.isHls && it.url == "https://vd.okcdn.ru/hls/master.m3u8?sig=1&ct=8" })
        assertTrue(okStreams.any { !it.isHls && it.qualityLabel == "1080p" })
        assertTrue(okStreams.any { !it.isHls && it.qualityLabel == "720p" })

        // 2) Sibnet bağıl akış adresi
        val sibnetBody = """player.src([{src: "/v/9a8b7c6d/123456.mp4", type: "video/mp4"}]);"""
        assertEquals(
            "https://video.sibnet.ru/v/9a8b7c6d/123456.mp4",
            DiziBoxPlayerParser.parseSibnetUrl(sibnetBody, "https://video.sibnet.ru/shell.php?videoid=123456"),
        )

        // 3) Mail.ru protokolsüz URL
        val mailBody = """{"videos":[{"key":"1080p","url":"//cdn.my.mail.ru/hv/abc.mp4"},{"key":"720p","url":"https://cdn.my.mail.ru/hv/720.mp4"}]}"""
        val mailStreams = DiziBoxPlayerParser.parseMailRuStreams(mailBody)
        assertEquals(2, mailStreams.size)
        assertEquals("https://cdn.my.mail.ru/hv/abc.mp4", mailStreams[0].url)

        // 4) VK Video
        val vkBody = """var playerParams = {"params":[{"hls":"https:\/\/vkvd1.mycdn.me\/src.m3u8","url720":"https:\/\/vkvd1.mycdn.me\/720.mp4"}]};"""
        val vkStreams = DiziBoxPlayerParser.parseVkStreams(vkBody)
        assertEquals(2, vkStreams.size)
        assertTrue(vkStreams.any { it.isHls })
        assertTrue(vkStreams.any { it.qualityLabel == "720p" })

        // 5) MolyStream sheila + ayna dönüşümleri
        assertEquals(
            "https://www.molystream.org/embed/sheila/abc123",
            DiziBoxPlayerParser.molyStreamMirrorUrl("https://dbx.molystream.org/embed/sheila/abc123"),
        )
        assertEquals(
            "https://dbx.molystream.org/embed/abc123",
            DiziBoxPlayerParser.molyStreamPlayerPageUrl("https://dbx.molystream.org/embed/sheila/abc123"),
        )

        // 6) Embed domain normalleştirme
        assertEquals(
            "https://vidmoly.net/embed-k9x2m.html",
            DiziBoxPlayerParser.normalizeEmbedUrl("https://vidmoly.me/w/k9x2m"),
        )
        assertEquals(
            "https://ok.ru/videoembed/18600675707439",
            DiziBoxPlayerParser.normalizeEmbedUrl("http://ok.ru/video/18600675707439"),
        )

        // 7) DBX Pro Türkiye dışı IP uyarısı tespiti
        val geoDoc = Jsoup.parse(
            """<div id="video-area">DBX Pro kaynakları telif hakları sebebiyle şu anda sadece Türkiye ziyaretçilerine açıktır.</div>""",
        )
        assertTrue(DiziBoxPlayerParser.isGeoBlockedVideoArea(geoDoc))
    }
}
