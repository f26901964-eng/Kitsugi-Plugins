package com.hexated

import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Gerçek extractor zincirinin ağsız davranış testleri; URL/oturum sözleşmesi. */
class AnizmStreamBehaviorTest {
    private class FakeHttp : AnizmHttp {
        val pages = mutableMapOf<String, String>()
        val drive = mutableMapOf<String, AnizmDriveResponse>()
        val requests = mutableListOf<String>()
        override suspend fun get(url: String, referer: String?): String? {
            requests += url
            return pages[url]
        }
        override suspend fun inspectDrive(url: String, referer: String): AnizmDriveResponse {
            requests += url
            return drive[url] ?: AnizmDriveResponse(AnizmDriveKind.UNKNOWN)
        }
        override suspend fun getXhr(url: String, referer: String?) = null
        override suspend fun location(url: String, referer: String?) = null
        override suspend fun getWith(url: String, extra: Map<String, String>) = null
        override suspend fun postJson(url: String, json: String, referer: String?) = null
        override suspend fun postForm(url: String, form: Map<String, String>, referer: String?): String? {
            requests += url
            return pages[url]
        }
    }

    private val http = FakeHttp()
    private val streams = AnizmStreams("Anizm", { "https://anizm.example" }, http)
    private val out = mutableListOf<ExtractorLink>()
    private val callback: (ExtractorLink) -> Unit = { out += it }
    private suspend fun resolve(url: String) =
        streams.resolveTarget(url, "https://anizm.example/episode", "Test", {}, callback, 0)

    @Test
    fun `aincrad signed master is emitted intact without variant expansion`() = runBlocking {
        val master = "https://anizmplayer.com/cdn/hls/abc/master.m3u8?md5=a%2Fb%2Bc&expires=123&audio=tr"
        http.pages["https://anizmplayer.com/player/index.php?data=abcdef12&do=getVideo"] =
            """{"hls":true,"securedLink":"${master.replace("&", "\\u0026")}","videoSource":"https://anizmplayer.com/cdn/hls/abc/master.txt"}"""
        assertTrue(resolve("https://anizmplayer.com/video/abcdef12"))
        assertEquals(1, out.size)
        assertEquals(master, out[0].url)
        assertEquals(ExtractorLinkType.M3U8, out[0].type)
        assertEquals("https://anizmplayer.com/", out[0].referer)
        assertEquals(AnizmPlaybackPolicy.USER_AGENT, AnizmPlaybackPolicy.header(out[0].headers, "User-Agent"))
        assertFalse(http.requests.any { it.endsWith("master.txt") })
    }

    @Test
    fun `okru keeps HLS master and progressive alternatives with embed origin`() = runBlocking {
        http.pages["https://ok.ru/dk?cmd=videoPlayerMetadata&mid=123456"] = """
            {"hlsManifestUrl":"https://okcdn.ru/video.m3u8?sig=a%2Bb","videos":[
              {"name":"720p","url":"https://okcdn.ru/720.mp4?sig=c"},
              {"name":"1080p","url":"https://okcdn.ru/1080.mp4?sig=d"}]}
        """.trimIndent()
        assertTrue(resolve("https://ok.ru/videoembed/123456"))
        assertEquals(3, out.size)
        assertEquals("https://okcdn.ru/video.m3u8?sig=a%2Bb", out[0].url)
        assertEquals(ExtractorLinkType.M3U8, out[0].type)
        assertTrue(out.all { it.referer == "https://ok.ru/" })
        assertTrue(out.all { AnizmPlaybackPolicy.header(it.headers, "Origin") == "https://ok.ru" })
    }

    @Test
    fun `sendvid embed resolves MP4 instead of requiring HLS meta`() = runBlocking {
        http.pages["https://sendvid.com/embed/abc123"] = """
            <meta property="og:video:secure_url" content="https://sendvid.com/abc123.mp4?token=x">
            <script>var video_source = "https://sendvid.com/abc123.mp4?token=x";</script>
        """.trimIndent()
        assertTrue(resolve("https://sendvid.com/abc123"))
        assertEquals(1, out.size)
        assertEquals("https://sendvid.com/abc123.mp4?token=x", out[0].url)
        assertEquals("https://sendvid.com/embed/abc123", out[0].referer)
        assertEquals("https://sendvid.com", AnizmPlaybackPolicy.header(out[0].headers, "Origin"))
    }

    @Test
    fun `other sendvid links remain independent and direct MP4 keeps original referer`() = runBlocking {
        assertTrue(resolve("https://sendvid.com/first.mp4"))
        assertTrue(resolve("https://sendvid.com/second.mp4"))
        assertEquals(2, out.size)
        assertEquals("https://anizm.example/episode", out[0].referer)
        assertEquals("https://anizm.example/episode", out[1].referer)
        assertTrue(out.all { AnizmPlaybackPolicy.header(it.headers, "Origin") == "https://sendvid.com" })
    }

    @Test
    fun `sendvid error HTML does not become a media link`() = runBlocking {
        http.pages["https://sendvid.com/embed/missing1"] = "<html><body>Video not found</body></html>"
        assertFalse(resolve("https://sendvid.com/missing1"))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `yourupload extractor metadata and every audio track survive`() = runBlocking {
        val audio = listOf(
            newAudioFile("https://cdn.example/tr.m4a") { headers = mapOf("Cookie" to "audio=1", "Referer" to "https://www.yourupload.com/embed/abc") },
            newAudioFile("https://cdn.example/en.m4a") { headers = mapOf("Cookie" to "audio=2") }
        )
        val link = newExtractorLink("Yourupload", "720p", "https://vidcache.net/a/video.mp4?token=keep", ExtractorLinkType.VIDEO) {
            referer = "https://www.yourupload.com/embed/abc"
            quality = 720
            headers = mapOf("cookie" to "session=keep", "user-agent" to "HostUA")
            extractorData = "interceptor-state"
            audioTracks = audio
        }
        assertTrue(streams.emitExtractorLinks(listOf(link), "YourUpload", "https://anizm.example/episode", callback))
        val result = out.single()
        assertEquals(link.referer, result.referer)
        assertEquals("https://www.yourupload.com", AnizmPlaybackPolicy.header(result.headers, "Origin"))
        assertEquals("session=keep", AnizmPlaybackPolicy.header(result.headers, "Cookie"))
        assertEquals("HostUA", AnizmPlaybackPolicy.header(result.headers, "User-Agent"))
        assertEquals("interceptor-state", result.extractorData)
        assertEquals(audio, result.audioTracks)
        assertEquals(720, result.quality)
    }

    @Test
    fun `dedup distinguishes headers type audio and interceptor but suppresses exact duplicate`() = runBlocking {
        suspend fun link(referer: String, type: ExtractorLinkType = ExtractorLinkType.VIDEO,
                 cookie: String = "a", audio: List<AudioFile> = emptyList(), data: String? = null) =
            newExtractorLink("X", "X", "https://cdn.example/video.mp4", type) {
                this.referer = referer
                this.headers = mapOf("Cookie" to cookie)
                this.audioTracks = audio
                this.extractorData = data
            }
        val base = link("https://one.example/")
        val audio = listOf(newAudioFile("https://cdn.example/audio.m4a"))
        val links = listOf(
            base, base,
            link("https://two.example/"),
            link("https://one.example/", cookie = "b"),
            link("https://one.example/", type = ExtractorLinkType.M3U8),
            link("https://one.example/", audio = audio),
            link("https://one.example/", data = "refresh")
        )
        streams.emitExtractorLinks(links, "X", "https://anizm.example/", callback)
        assertEquals(6, out.size)
    }

    @Test
    fun `drive confirmation flow preserves query and emits final media URL`() = runBlocking {
        val original = "https://drive.google.com/uc?id=FILE123&export=download&confirm=old&uuid=keep"
        val confirmed = "https://drive.usercontent.google.com/download?id=FILE123&export=download&confirm=new&uuid=keep"
        val finalUrl = "https://drive.usercontent.google.com/download?id=FILE123&confirm=new&signature=final"
        http.drive[original] = AnizmDriveResponse(AnizmDriveKind.HTML,
            "<form action='https://drive.usercontent.google.com/download?id=OLD'><input name='id' value='FILE123'><input name='export' value='download'><input name='confirm' value='new'><input name='uuid' value='keep'></form>")
        http.drive[confirmed] = AnizmDriveResponse(AnizmDriveKind.MEDIA, finalUrl = finalUrl)
        assertTrue(resolve(original))
        assertEquals(finalUrl, out.single().url)
        assertEquals(listOf(original, confirmed), http.requests)
    }

    @Test
    fun `drive quota and confirmed missing file emit nothing`() = runBlocking {
        val quota = "https://drive.google.com/uc?id=QUOTA1&export=download"
        http.drive[quota] = AnizmDriveResponse(AnizmDriveKind.HTML, "<html>Download quota exceeded</html>")
        assertFalse(resolve(quota))
        val missing = "https://drive.google.com/uc?id=MISSING&export=download"
        http.drive[missing] = AnizmDriveResponse(AnizmDriveKind.DEAD)
        assertFalse(resolve(missing))
        assertTrue(out.isEmpty())
    }

    @Test
    fun `drive access uncertainty keeps explicit link but does not invent one`() = runBlocking {
        val explicit = "https://drive.google.com/uc?id=EXPLICIT&confirm=t"
        assertFalse(resolve("https://drive.google.com/file/d/NEWFILE/preview"))
        assertTrue(resolve(explicit))
        assertEquals(explicit, out.single().url)
    }

    @Test
    fun `extensionless drive HLS response gets M3U8 type`() = runBlocking {
        val url = "https://drive.usercontent.google.com/download?id=HLSFILE&export=download"
        http.drive[url] = AnizmDriveResponse(AnizmDriveKind.HLS)
        assertTrue(resolve(url))
        assertEquals(ExtractorLinkType.M3U8, out.single().type)
    }
}
