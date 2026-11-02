package com.kraptor

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **Gerçek site HTML'i** ile çözümleyici testleri.
 *
 * Fixture'lar cihazdan alınan sayfa dökümlerinden (28.10.2026) birebir
 * kırpılmıştır — sentetik değil. Seçiciler bozulursa bu testler kırılır.
 */
class TrAnimeIzleRealMarkupTest {

    private val base = "https://www.tranimeizle.live"
    private val seriesUrl = "$base/anime/attack-on-titan-izle"

    @Test
    fun cardsComeFromDataHrefAndResolveToSeriesPages() {
        val doc = Jsoup.parse(CARD_HTML, base)
        val cards = TrAnimeIzleSite.parseCards(doc, base, 20)
        assertEquals(2, cards.size)
        val first = cards.first()
        assertEquals("$base/anime/pan-dorobou-izle", first.url)
        assertEquals("Pan Dorobou", first.name)
        assertTrue(first.poster!!.contains("static.tranimeizle.top/animes/5608/medium.jpeg"))
    }

    @Test
    fun detailMetadataUsesInvertedDefinitionList() {
        val doc = Jsoup.parse(DETAIL_HTML, seriesUrl)
        val detail = TrAnimeIzleSite.parseDetail(doc, base, seriesUrl)
        assertEquals("Attack on Titan", detail.title)
        assertEquals("Dizi", detail.typeText)
        assertEquals(25, detail.totalEpisodes)
        assertEquals(24, detail.durationMinutes)
        assertEquals(2013, detail.year)
        assertTrue(detail.tags.contains("Aksiyon"))
        assertTrue(detail.fansubs.any { it.contains("TR Altyaz") })
        assertTrue(detail.plot!!.contains("devler"))
    }

    @Test
    fun episodesComeFromDataSlugCards() {
        val doc = Jsoup.parse(DETAIL_HTML, seriesUrl)
        val detail = TrAnimeIzleSite.parseDetail(doc, base, seriesUrl)
        assertEquals(listOf(1, 2, 3), detail.episodes.map { it.number })
        assertEquals("$base/attack-on-titan-1-bolum-izle", detail.episodes.first().url)
        assertEquals("1. Bölüm", detail.episodes.first().name)
    }

    @Test
    fun episodePageYieldsIdAndFansubs() {
        val doc = Jsoup.parse(EPISODE_HTML, "$base/attack-on-titan-1-bolum-izle")
        assertEquals(566, TrAnimeIzleSite.episodeIdFrom(EPISODE_HTML, doc))
        assertEquals(listOf(302), TrAnimeIzleSite.fansubsFrom(doc).map { it.id })
    }

    @Test
    fun captchaPageIsDetectedAndThemeIsRead() {
        assertTrue(TrCaptcha.isChallenge(CAPTCHA_HTML))
        assertEquals("dark", TrCaptcha.theme(CAPTCHA_HTML))
        assertTrue(!TrCaptcha.isChallenge("<html><body>normal sayfa</body></html>"))
    }

    companion object {
        /** Ana sayfa kartı (birebir). */
        private const val CARD_HTML = """<div class="flx-block" data-href="/pan-dorobou-5-bolum-izle">
<a class="news-image" href="/pan-dorobou-5-bolum-izle">
<img alt="" class="img-responsive" src="https://static.tranimeizle.top/animes/5608/medium.jpeg"/>
</a>
<div class="reatting">
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
</div>
<div class="shadow-overlay">
</div>
<div class="bar"><h4>Pan Dorobou 5. Bölüm İzle</h4><span class="info-chip pull-right"><a class="text-white-imp" href="/pan-dorobou-5-bolum-izle">Anime İzle</a></span><span class="info-chip">BÖL 5 / 0</span></div>
</div>
<div class="flx-block" data-href="/from-far-away-5-bolum-izle">
<a class="news-image" href="/from-far-away-5-bolum-izle">
<img alt="" class="img-responsive" src="https://static.tranimeizle.top/animes/5629/medium.jpeg"/>
</a>
<div class="reatting">
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
</div>
<div class="shadow-overlay">
</div>
<div class="bar"><h4>From Far Away 5. Bölüm İzle</h4><span class="info-chip pull-right"><a class="text-white-imp" href="/from-far-away-5-bolum-izle">Anime İzle</a></span><span class="info-chip">BÖL 5 / 0</span></div>
</div>"""

        /** Detay sayfası: etiket/değer çiftleri + bölüm kartları. */
        private const val DETAIL_HTML = """<h1>Attack on Titan İzle</h1>
<div class="poster"><figure class="social-icon m-0">
<img alt="" class="img-responsive" src="https://static.tranimeizle.top/animes/37/large.jpeg"/>
<div>
<a href="#">
<i class="fa fa-facebook"></i>
</a>
<a href="#">
<i class="fa fa-twitter"></i>
</a>
<a href="#">
<i class="fa fa-google-plus"></i>
</a>
<a class="hidden-xs" href="#">
<i class="fa fa-linkedin"></i>
</a>
<a class="hidden-xs" href="#">
<i class="fa fa-pinterest-p"></i>
</a>
</div>
</figure></div>
<div class="col-xs-12"><dl></dl></div>
<dl><dd>Diğer İsimleri</dd><dt>
                                            進撃の巨人
                                        </dt><dd>Anime Türü</dd><dt>
<div class="tags-inner">
<a class="ui tag genre" href="/animeizle/aksiyon-anime-izle-1">Aksiyon</a>
<a class="ui tag genre" href="/animeizle/dram-anime-izle-1">Dram</a>
<a class="ui tag genre" href="/animeizle/fantastik-anime-izle-1">Fantastik</a>
<a class="ui tag genre" href="/animeizle/shounen-anime-izle-1">Shounen</a>
<a class="ui tag genre" href="/animeizle/super-guc-anime-izle-1">Süper Güç</a>
</div>
</dt><dd>Anime Tipi</dd><dt>
                                                Dizi
                                            </dt><dd>Bölüm Sayısı</dd><dt>
                                                25 / 25
                                            </dt><dd>Başlangıç Tarihi</dd><dt>
                                                07 Nis 2013
                                            </dt><dd>Eklenme Tarihi</dd><dt>
                                                23 Kas 2018
                                            </dt><dd>Puanlama ( OY : 174 )</dd><dt>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
<i class="fa fa-star"></i>
</dt><dd>Bölüm Süresi</dd><dt>
                                                24 dk
                                            </dt><dd>Bitiş Tarihi</dd><dt>
                                                29 Eyl 2013
                                            </dt><dd>Yapım yılı</dd><dt>
                                                2013
                                            </dt><dd>Fansublar</dd><dt>
<ul class="td-category">
<li>
<a class="post-category" href="https://www.tranimeizle.io/" rel="nofollow">TR Altyazılı</a>
</li>
<li>
<a class="post-category" rel="nofollow">Peace Fansub</a>
</li>
</ul>
</dt></dl>
<div class="p-10">
<span class="text-accent headerSized">Anime Konusu</span>
<p>Yüzyıllar evvel insanoğlu devler tarafından yok olmanın eşiğine getirilmiştir. Devler tıpkı anlatılagelmiş hikayelerdeki gibi, uzun, zekadan yoksun görünen, insanları yiyen ve de en kötüsü bunu beslenmek için değil zevk için yapan varlıklardır. Az sayıdaki insan, en uzun devlerden bile yüksek duvarlar inşa ederek çevreledikleri bir şehrin koruması altında hayatta kalmıştır. Şu anki zaman diliminde ise 100 yılı aşkın bir süredir tek bir dev dahi şehre ayak basmamıştır. Henüz erginliğe erişmemiş bir çocuk olan Eren ile sütkardeşi Mikasa, bir anda ortaya çıkan inanılmaz büyük bir devin saldırısı sonucu şehrin surlarının yıkılmasına tanık olurlar. Nispeten daha küçük olan devler surlarda açılan gedikten şehre akın ederler ve bu iki çocuk dehşet içerisinde annelerinin bir dev tarafından canlı canlı yenilişini izler. Eren bütün devleri öldürüp insanlığın intikamını alacağına yemin eder.</p>
                Attack on Titan izle, Attack on Titan hd izle, Attack on Titan 1080p izle, Attack on Titan türkçe, shingeki no kyojin izle,  AoT izle
            </div>
<p class="animeDetail-desc">Toplam <span>25</span> / 25 ( 24 dk )</p>
<div class="animeDetail-items"><ol><li class="episodeBtn" data-slug="attack-on-titan-1-bolum-izle">
<p class="title">
<span>Attack on Titan 1. Bölüm İzle</span>
<small class="author">
<br/>
                                            06 Nis 2013
                                        </small>
</p>
<div class="imgContainer">
<img alt="Attack on Titan 1. Bölüm İzle" class="thumb" src="https://static.tranimeizle.top/images/animes/covers/0469713d-f376-4263-ad02-8956adf98274.jpg"/>
</div>
</li><li class="episodeBtn" data-slug="attack-on-titan-2-bolum-izle">
<p class="title">
<span>Attack on Titan 2. Bölüm İzle</span>
<small class="author">
<br/>
                                            13 Nis 2013
                                        </small>
</p>
<div class="imgContainer">
<img alt="Attack on Titan 2. Bölüm İzle" class="thumb" src="https://static.tranimeizle.top/images/animes/covers/0469713d-f376-4263-ad02-8956adf98274.jpg"/>
</div>
</li><li class="episodeBtn" data-slug="attack-on-titan-3-bolum-izle">
<p class="title">
<span>Attack on Titan 3. Bölüm İzle</span>
<small class="author">
<br/>
                                            20 Nis 2013
                                        </small>
</p>
<div class="imgContainer">
<img alt="Attack on Titan 3. Bölüm İzle" class="thumb" src="https://static.tranimeizle.top/images/animes/covers/0469713d-f376-4263-ad02-8956adf98274.jpg"/>
</div>
</li></ol></div>"""

        /** Bölüm sayfası: EpisodeId + fansub seçici + initialize çağrısı. */
        private const val EPISODE_HTML = """<html><body><input id="EpisodeId" name="EpisodeId" type="hidden" value="566"/><div class="btn btn-default fansubSelector" data-fad="TR Altyazılı" data-fid="302" data-furl="https://www.tranimeizle.io/">TR Altyazılı</div><script>animeWatch.initialize(37, 566, 302, "TR Altyaz&#x131;l&#x131;", "https://www.tranimeizle.io/")</script></body></html>"""

        /** Bot Kontrol sayfası: captcha-holder + iconCaptcha yapılandırması. */
        private const val CAPTCHA_HTML = """<html><body><div class="captcha-holder"></div><script>${'$'}('.captcha-holder').iconCaptcha({
            captchaTheme: ["dark"], // Select the theme(s) of the Captcha(s). Available: light, dark
            captchaFontFamily: '', // Change the font family of the captcha. Leaving it blank will add the default font to the end of the <body> tag.
            captchaClickDelay: 500, // The delay during which the user can't select an image.
            captchaHoverDetection: true, // Enable or disable the cursor hover detection.
            enableLoadingAnimation: true, // Enable of disable the fake loading animation. Doesn't actually do anything other than look nice.
            loadingAnimationDelay: 1500, // How long the fake loading animation should play.
            showCredits: 'disabled', // Show, hide or disable the credits element. Valid values: 'show', 'hide', 'disabled' (please leave it enabled).
            requestIconsDelay: 1500, // How long should the script wait before requesting the hashes and icons? (to prevent a high(er) CPU usage during a DDoS attack)
            validationPath: '/api/Captcha/', // The path to the Captcha validation file.
            messages: { // You can put whatever message you want in the captcha.
                header: "Devam edebilmek için farklı olanı seçiniz.",
                correct: {
                    top: "İyi seyirler!",
                    bottom: "Doğrulamanız için teşekkürler."
                },
                incorrect: {
                    top: "Oops!",
                    bottom: "Doğrulama yapılamadı, tekrar deneyiniz."
                }
            }
        }).bind('success.iconCaptcha', function (e, id) {
            window.location.replace('%2Fanime%2Fattack-on-titan-izle');
        });</script></body></html>"""
    }
}
