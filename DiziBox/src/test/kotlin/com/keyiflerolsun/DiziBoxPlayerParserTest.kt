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
            jwplayer("player").setup({ sources: [{ file: "https://cdn.example/video.m3u8", type: "application/x-mpegURL", label: "1080p" }] });
        """.trimIndent()

        val encrypted = DiziBoxPlayerParser.encryptedPayload(script)
        assertNotNull(encrypted)
        assertEquals("U2FsdGVkX1ciphertext==", encrypted?.ciphertext)
        assertEquals("player-password", encrypted?.password)

        val source = DiziBoxPlayerParser.mediaSources(script).single()
        assertEquals("https://cdn.example/video.m3u8", source.url)
        assertEquals("1080p", source.label)
        assertTrue(DiziBoxPlayerParser.isHls(source))
    }

    @Test
    fun preservesExplicitSourceKindAndFiltersCaptionSources() {
        val html = """
            jwplayer("player").setup({
                sources: [
                    { file: "https://cdn.example/video.m3u8", type: "application/x-mpegURL", kind: "main", label: "720p" },
                    { file: "https://cdn.example/subtitles.vtt", type: "text/vtt", kind: "captions", label: "Türkçe" }
                ]
            });
        """.trimIndent()

        val sources = DiziBoxPlayerParser.mediaSources(html)
        assertEquals(1, sources.size)
        assertEquals("main", sources.single().kind)
        assertEquals("720p", sources.single().label)
        assertTrue(DiziBoxPlayerParser.isHls(sources.single()))
    }

    @Test
    fun doesNotInferMediaSourceKindFromItsUrl() {
        val source = DiziBoxPlayerParser.mediaSources(
            "file: \"https://cdn.example/caption.vtt\"",
        ).single()

        assertEquals("https://cdn.example/caption.vtt", source.url)
        assertEquals("", source.kind)
    }

    @Test
    fun extractsLabeledSubtitleTracksWithoutTreatingOtherVttTracksAsCaptions() {
        val html = """
            jwplayer("player").setup({
                tracks: [
                    { file: "https://cdn.example/tr.vtt", label: "Türkçe", kind: "captions", srclang: "tr" },
                    { file: "https://cdn.example/en.srt?token=1", label: "English" },
                    { file: "https://cdn.example/chapters.vtt", label: "Chapters", kind: "chapters" }
                ]
            });
        """.trimIndent()

        val tracks = DiziBoxPlayerParser.subtitleTracks(html)
        assertEquals(2, tracks.size)
        assertEquals("Türkçe", tracks[0].label)
        assertEquals("captions", tracks[0].kind)
        assertEquals("tr", tracks[0].language)
        assertEquals("English", tracks[1].label)
    }

    @Test
    fun normalizesMolyStreamSheilaStreamRouteToItsPlayerEmbedPage() {
        assertEquals(
            "https://dbx.molystream.org/embed/episode-123?token=abc",
            DiziBoxPlayerParser.normalizeMolyStreamEmbedUrl(
                "https://dbx.molystream.org/embed/sheila/episode-123?token=abc",
            ),
        )
        assertEquals(
            "https://player.example/embed/sheila/episode-123",
            DiziBoxPlayerParser.normalizeMolyStreamEmbedUrl(
                "https://player.example/embed/sheila/episode-123",
            ),
        )
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
