package com.hexated

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AnizmDriveResponseTest {
    private fun response(type: String, size: Long, read: AtomicLong, closed: AtomicBoolean): Response {
        val buffer = Buffer().write(ByteArray(8192) { 'x'.code.toByte() })
        val source = object : ForwardingSource(buffer) {
            override fun read(sink: Buffer, byteCount: Long): Long {
                val n = super.read(sink, byteCount)
                if (n > 0) read.addAndGet(n)
                return n
            }
            override fun close() { closed.set(true); super.close() }
        }
        val body = object : okhttp3.ResponseBody() {
            override fun contentType() = type.toMediaType()
            override fun contentLength() = size
            override fun source(): BufferedSource = source.buffer()
        }
        return Response.Builder().request(Request.Builder().url("https://drive.usercontent.google.com/download?id=A").build())
            .protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .header("Content-Type", type).body(body).build()
    }

    @Test
    fun `range ignored for binary still reads only a bounded prefix and closes`() {
        val read = AtomicLong(); val closed = AtomicBoolean()
        val result = inspectDriveResponse(response("video/mp4", 100_000_000, read, closed))
        assertEquals(AnizmDriveKind.MEDIA, result.kind)
        assertTrue("read=${read.get()}", read.get() <= 8192)
        assertTrue(closed.get())
    }

    @Test
    fun `HTML body is bounded and closed`() {
        val read = AtomicLong(); val closed = AtomicBoolean()
        val result = inspectDriveResponse(response("text/html", 100_000_000, read, closed))
        assertEquals(AnizmDriveKind.HTML, result.kind)
        assertTrue(read.get() <= 262144 + 8192)
        assertTrue((result.body?.length ?: 0) <= 262144)
        assertTrue(closed.get())
    }
}
