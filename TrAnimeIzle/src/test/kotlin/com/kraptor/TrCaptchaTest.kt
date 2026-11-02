package com.kraptor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IconCaptcha v2 mantığı — ağsız, saf Kotlin testleri.
 *
 * Yanıt biçimleri sitenin kullandığı IconCaptcha sürümlerinden alınmıştır;
 * v2.5.0 istemcisi `{cID, rT:1, tM}` gönderip **sayı anahtarlı nesne**
 * (`{"0":"hash",…}`) alır, bazı sürümler dizi döndürür.
 */
class TrCaptchaTest {

    @Test
    fun hashesAreReadFromPlainArray() {
        assertEquals(
            listOf("h1", "h2", "h3", "h4", "h5"),
            TrCaptcha.hashes("""["h1","h2","h3","h4","h5"]""")
        )
    }

    @Test
    fun hashesAreReadFromNumericKeyedObject() {
        assertEquals(
            listOf("a", "b", "c"),
            TrCaptcha.hashes("""{"0":"a","2":"c","1":"b"}""")
        )
    }

    @Test
    fun hashesAreReadFromWrappedPayloads() {
        assertEquals(listOf("x", "y"), TrCaptcha.hashes("""{"data":["x","y"]}"""))
        assertEquals(listOf("q1", "q2"), TrCaptcha.hashes("""{"hashes":{"0":"q1","1":"q2"}}"""))
        assertEquals(
            listOf("k1", "k2", "k3"),
            TrCaptcha.hashes("""{"success":true,"icons":["k1","k2","k3"],"cID":0}""")
        )
    }

    @Test
    fun brokenBodyYieldsNoHashes() {
        assertTrue(TrCaptcha.hashes("bozuk { json").isEmpty())
        assertTrue(TrCaptcha.hashes("").isEmpty())
        assertTrue(TrCaptcha.hashes(null).isEmpty())
    }

    @Test
    fun oddOneOutIsTheUniqueImage() {
        val common = ByteArray(300) { 7 }
        val different = ByteArray(300) { 9 }
        val hashes = listOf("h0", "h1", "h2", "h3", "h4")
        val icons = listOf(common, common, different, common, common)
        assertEquals("h2", TrCaptcha.oddOneOut(hashes, icons))
    }

    @Test
    fun oddOneOutGivesUpWhenImagesAreAllDifferent() {
        val hashes = listOf("h0", "h1", "h2", "h3", "h4")
        val icons = hashes.mapIndexed { index, _ -> ByteArray(300 + index * 200) { index.toByte() } }
        assertNull(TrCaptcha.oddOneOut(hashes, icons))
    }

    @Test
    fun oddOneOutNeedsEveryIcon() {
        val common = ByteArray(300) { 7 }
        val hashes = listOf("h0", "h1", "h2")
        assertNull(TrCaptcha.oddOneOut(hashes, listOf(common, null, common)))
    }

    @Test
    fun themeIsReadFromPageConfiguration() {
        val page = """iconCaptcha({ captchaTheme: ["dark"], validationPath: '/api/Captcha/' })"""
        assertEquals("dark", TrCaptcha.theme(page))
        assertNull(TrCaptcha.theme("<html></html>"))
    }

    @Test
    fun onlyRealGateMarkersCount() {
        assertTrue(TrCaptcha.isChallenge("<div class='captcha-holder'></div>"))
        assertTrue(TrCaptcha.isChallenge("<script>\$('.x').iconCaptcha({})</script>"))
        // Site HER sayfada `/js/icon-captcha.min.js` yüklüyor — bu ipucu sayılmaz.
        assertTrue(!TrCaptcha.isChallenge("<script src='/js/icon-captcha.min.js'></script>"))
        assertTrue(!TrCaptcha.isChallenge("<html>normal</html>"))
    }
}
