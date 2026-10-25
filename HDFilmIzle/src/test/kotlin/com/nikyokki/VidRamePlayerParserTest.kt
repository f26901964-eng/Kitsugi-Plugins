package com.nikyokki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VidRamePlayerParserTest {
    @Test
    fun parsesEveryObservedSourceAndCaptionsTrack() {
        val script = """
            player.setup({
                sources: [
                    { file: EE.dd("encoded-720"), type: "application/x-mpegURL", label: "720p" },
                    { "file": EE.dd("encoded-1080"), "type": "application/x-mpegURL", "label": "1080p" }
                ]
            });
            configs.tracks = [
                { file: "/subs/tr.vtt", label: "Türkçe", kind: "captions", language: "tr" },
                { file: "https://cdn.example/chapters.vtt", label: "Chapters", kind: "chapters" }
            ];
        """.trimIndent()

        val sources = VidRamePlayerParser.videoSources(script)
        assertEquals(2, sources.size)
        assertEquals("EE.dd(\"encoded-720\")", sources[0].file)
        assertEquals("application/x-mpegURL", sources[0].type)
        assertEquals("720p", sources[0].label)
        assertEquals("1080p", sources[1].label)

        val tracks = VidRamePlayerParser.subtitleTracks(script)
        assertEquals(1, tracks.size)
        assertEquals("/subs/tr.vtt", tracks.single().file)
        assertEquals("Türkçe", tracks.single().label)
        assertEquals("captions", tracks.single().kind)
        assertEquals("tr", tracks.single().language)
        assertEquals(
            "https://vidrame.pro/subs/tr.vtt",
            VidRamePlayerParser.resolveUrl(tracks.single().file, "https://vidrame.pro/video/episode-1"),
        )
    }

    @Test
    fun missingOrMalformedTracksDoNotDiscardTheVideoSource() {
        val scriptWithoutTracks = """
            player.setup({ sources: [{ file: EE.dd("encoded"), type: "application/x-mpegURL" }] });
        """.trimIndent()
        val malformedTracks = """
            player.setup({ sources: [{ file: EE.dd("encoded"), type: "application/x-mpegURL" }] });
            configs.tracks = [
        """.trimIndent()

        assertEquals(1, VidRamePlayerParser.videoSources(scriptWithoutTracks).size)
        assertTrue(VidRamePlayerParser.subtitleTracks(scriptWithoutTracks).isEmpty())
        assertEquals(1, VidRamePlayerParser.videoSources(malformedTracks).size)
        assertTrue(VidRamePlayerParser.subtitleTracks(malformedTracks).isEmpty())
    }

    @Test
    fun ignoresOtherTrackKindsAndResolvesProtocolRelativeSubtitleUrls() {
        val script = """
            configs.tracks = [
                { file: "//cdn.example/tr.srt", label: "Türkçe", kind: "Subtitles" },
                { file: "https://cdn.example/audio.mp3", label: "Audio", kind: "audio" }
            ];
        """.trimIndent()

        val tracks = VidRamePlayerParser.subtitleTracks(script)
        assertEquals(1, tracks.size)
        assertEquals(
            "https://cdn.example/tr.srt",
            VidRamePlayerParser.resolveUrl(tracks.single().file, "https://vidrame.pro/embed/1"),
        )
    }
}
