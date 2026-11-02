package com.hexated

import org.junit.Assert.*
import org.junit.Test

class AnizmPlaybackPolicyTest {
    @Test
    fun `extractor headers override defaults without case-sensitive duplicates`() {
        val headers = AnizmPlaybackPolicy.mergeHeaders(
            mapOf("Referer" to "https://www.yourupload.com/embed/abc", "User-Agent" to "Default"),
            mapOf("referer" to "https://override.example/", "user-agent" to "HostUA", "Origin" to "https://yourupload.com")
        )
        assertEquals("https://override.example/", AnizmPlaybackPolicy.header(headers, "Referer"))
        assertEquals("HostUA", AnizmPlaybackPolicy.header(headers, "USER-AGENT"))
        assertEquals(1, headers.keys.count { it.equals("referer", true) })
    }

    @Test
    fun `drive html is not a video and protected responses are unknown`() {
        assertEquals(AnizmDriveKind.MEDIA, AnizmPlaybackPolicy.classifyDrive(206, "video/mp4", null, byteArrayOf(0, 1, 2)))
        assertEquals(AnizmDriveKind.HTML, AnizmPlaybackPolicy.classifyDrive(200, "application/octet-stream", "attachment", "<!DOCTYPE html><html>quota</html>".toByteArray()))
        assertEquals(AnizmDriveKind.HTML, AnizmPlaybackPolicy.classifyDrive(200, "text/html", null, "<form>warning</form>".toByteArray()))
        assertEquals(AnizmDriveKind.UNKNOWN, AnizmPlaybackPolicy.classifyDrive(403, "text/html", null, "forbidden".toByteArray()))
        assertEquals(AnizmDriveKind.DEAD, AnizmPlaybackPolicy.classifyDrive(404, null, null, byteArrayOf()))
    }

    @Test
    fun `download urls are recognized without confusing preview or untrusted host`() {
        assertTrue(AnizmPlaybackPolicy.isDriveDownload("https://drive.google.com/uc?id=A&confirm=token&uuid=uuid"))
        assertTrue(AnizmPlaybackPolicy.isDriveDownload("https://drive.usercontent.google.com/download?id=A&confirm=token"))
        assertFalse(AnizmPlaybackPolicy.isDriveDownload("https://drive.google.com/file/d/A/preview"))
        assertFalse(AnizmPlaybackPolicy.isDriveHost("https://drive.google.com.attacker.test/uc?id=A"))
    }

    @Test
    fun `explicit origin and cookie are never replaced by defaults`() {
        val headers = AnizmPlaybackPolicy.mergeHeaders(
            mapOf("Origin" to "https://default.example", "Cookie" to "default=1"),
            mapOf("origin" to "https://host.example", "COOKIE" to "host=2")
        )
        assertEquals("https://host.example", AnizmPlaybackPolicy.header(headers, "Origin"))
        assertEquals("host=2", AnizmPlaybackPolicy.header(headers, "Cookie"))
        assertEquals(1, headers.keys.count { it.equals("origin", true) })
        assertEquals(1, headers.keys.count { it.equals("cookie", true) })
    }

    @Test
    fun `host matching rejects suffix and userinfo tricks`() {
        assertFalse(AnizmPlaybackPolicy.isDriveHost("https://drive.google.com.attacker.test/uc"))
        assertFalse(AnizmPlaybackPolicy.isDriveHost("https://attacker.test@drive.google.com/uc"))
        assertFalse(AnizmPlaybackPolicy.isOkRuHost("https://notok.ru/video/1"))
        assertFalse(AnizmPlaybackPolicy.isSendvidHost("https://sendvid.com.attacker.test/a"))
        assertTrue(AnizmPlaybackPolicy.isOkRuHost("https://m.ok.ru/video/1"))
        assertTrue(AnizmPlaybackPolicy.isSendvidHost("https://sendvid.com/embed/a"))
    }

    @Test
    fun `drive streaming manifests are recognized even without file extension`() {
        assertEquals(AnizmDriveKind.HLS, AnizmPlaybackPolicy.classifyDrive(
            200, "application/octet-stream", null, "#EXTM3U\n#EXT-X-STREAM-INF\n".toByteArray()))
        assertEquals(AnizmDriveKind.HLS, AnizmPlaybackPolicy.classifyDrive(
            206, "application/vnd.apple.mpegurl", null, byteArrayOf()))
        assertEquals(AnizmDriveKind.DASH, AnizmPlaybackPolicy.classifyDrive(
            200, "application/dash+xml", null, "<MPD></MPD>".toByteArray()))
    }

    @Test
    fun `small valid media is not dead and server errors remain uncertain`() {
        assertEquals(AnizmDriveKind.MEDIA, AnizmPlaybackPolicy.classifyDrive(
            200, "video/mp4", null, ByteArray(32)))
        for (status in listOf(403, 408, 429, 500, 503)) {
            assertEquals(AnizmDriveKind.UNKNOWN, AnizmPlaybackPolicy.classifyDrive(status, null, null, byteArrayOf()))
        }
        assertEquals(AnizmDriveKind.HTML, AnizmPlaybackPolicy.classifyDrive(
            200, "application/octet-stream", "attachment", "\uFEFF <!-- notice --> <html>quota</html>".toByteArray()))
    }

    @Test
    fun `sendvid embed identity is derived only from real host and id`() {
        assertEquals("https://sendvid.com/embed/abc123", AnizmPlaybackPolicy.sendvidEmbed("https://sendvid.com/abc123.mp4"))
        assertEquals("https://sendvid.com/embed/abc123", AnizmPlaybackPolicy.sendvidEmbed("https://sendvid.com/embed/abc123?x=1"))
        assertNull(AnizmPlaybackPolicy.sendvidEmbed("https://sendvid.com.attacker.test/abc123"))
        assertNull(AnizmPlaybackPolicy.sendvidEmbed("https://sendvid.com/playlist"))
    }
}
