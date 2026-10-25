package com.nikyokki

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class HDFilmIzlePlayerParserTest {
    @Test
    fun collectsLazyAndRegularPlayerIframesWithEpisodeRefererBase() {
        val pageUrl = "https://www.hdfilmizle.to/dizi/pluribus/sezon-1/bolum-1/"
        val document = Jsoup.parse(
            """
                <iframe src="about:blank" data-src="/embed/vidrame/episode-1"></iframe>
                <iframe src="https://player.example/embed/episode-1"></iframe>
                <iframe data-src="https://player.example/embed/episode-1"></iframe>
                <iframe data-src="#" src="https://player.example/embed/fallback"></iframe>
                <iframe src="//cdn.example/video-frame"></iframe>
                <iframe src="https://www.youtube-nocookie.com/embed/trailer"></iframe>
                <iframe src="about:blank"></iframe>
            """.trimIndent(),
            pageUrl,
        )

        assertEquals(
            listOf(
                "https://www.hdfilmizle.to/embed/vidrame/episode-1",
                "https://player.example/embed/episode-1",
                "https://player.example/embed/fallback",
                "https://cdn.example/video-frame",
            ),
            HDFilmIzlePlayerParser.iframeUrls(document, pageUrl),
        )
    }
}
