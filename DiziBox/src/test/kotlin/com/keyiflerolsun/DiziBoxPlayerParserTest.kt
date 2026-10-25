package com.keyiflerolsun

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiziBoxPlayerParserTest {
    private val episodeUrl = "https://www.dizibox.live/pluribus-1-sezon-1-bolum-1/"

    @Test
    fun findsCurrentPlayerIframesAndAlternatePages() {
        val document = Jsoup.parse(
            """
                <div id="video-area">
                    <iframe data-src="/player/king/king.php?v=primary"></iframe>
                    <iframe src="https://www.dizibox.live/player/moly/moly.php?h=secondary"></iframe>
                </div>
                <select class="woca-linkpages-dd">
                    <option value="/pluribus-1-sezon-1-bolum-1/2">MolyStream</option>
                    <option value="javascript:void(0)">Invalid</option>
                </select>
                <div class="video-toolbar">
                    <select>
                        <option value="/pluribus-1-sezon-1-bolum-1/3">Legacy player</option>
                    </select>
                </div>
            """.trimIndent(),
            episodeUrl,
        )

        assertEquals(
            listOf(
                "https://www.dizibox.live/player/king/king.php?v=primary",
                "https://www.dizibox.live/player/moly/moly.php?h=secondary",
            ),
            DiziBoxPlayerParser.playerIframes(document, episodeUrl),
        )
        assertEquals(
            listOf(
                "https://www.dizibox.live/pluribus-1-sezon-1-bolum-1/2",
                "https://www.dizibox.live/pluribus-1-sezon-1-bolum-1/3",
            ),
            DiziBoxPlayerParser.alternatePlayerPages(document, episodeUrl),
        )
    }

    @Test
    fun recognizesEncryptedPlayerPayloadAndHlsSources() {
        val script = """
            CryptoJS . AES . decrypt ( "U2FsdGVkX1ciphertext==" , "player-password" )
            jwplayer("player").setup({ sources: [{ file: "https://cdn.example/video.m3u8", type: "application/x-mpegURL" }] });
        """.trimIndent()

        val encrypted = DiziBoxPlayerParser.encryptedPayload(script)
        assertNotNull(encrypted)
        assertEquals("U2FsdGVkX1ciphertext==", encrypted?.ciphertext)
        assertEquals("player-password", encrypted?.password)

        val source = DiziBoxPlayerParser.mediaSources(script).single()
        assertEquals("https://cdn.example/video.m3u8", source.url)
        assertTrue(DiziBoxPlayerParser.isHls(source))
    }

    @Test
    fun recognizesCurrentMolyStreamEmbedAsHlsAndSkipsSubtitles() {
        val html = """
            jwplayer("player").setup({
                sources: [
                    { file: "https://dbx.molystream.org/embed/sheila/abc", type: "application/x-mpegURL" },
                    { file: "https://cdn.example/subtitles.vtt", kind: "captions", type: "text/vtt" }
                ]
            });
        """.trimIndent()

        val sources = DiziBoxPlayerParser.mediaSources(html)
        assertEquals(1, sources.size)
        assertTrue(DiziBoxPlayerParser.isHls(sources.single()))
    }

    @Test
    fun resolvesRelativeUrlsAndLimitsManualResolutionToObservedPlayers() {
        val frame = DiziBoxPlayerParser.resolveUrl("../player/king/king.php?v=1", episodeUrl)
        assertEquals("https://www.dizibox.live/player/king/king.php?v=1", frame)
        assertTrue(DiziBoxPlayerParser.isSupportedPlayerUrl(frame!!, "https://www.dizibox.live"))
        assertTrue(
            DiziBoxPlayerParser.isSupportedPlayerUrl(
                "https://dbx.molystream.org/embed/sheila/abc",
                "https://www.dizibox.live",
            ),
        )
        assertFalse(
            DiziBoxPlayerParser.isSupportedPlayerUrl(
                "https://unrelated.example/player/king/king.php?v=1",
                "https://www.dizibox.live",
            ),
        )
    }
}
