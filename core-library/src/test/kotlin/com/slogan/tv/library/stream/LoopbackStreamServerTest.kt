package com.slogan.tv.library.stream

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LoopbackStreamServerTest {

    private val server = LoopbackStreamServer()
    private val port = server.start()

    private class BytesSource(private val bytes: ByteArray) : StreamSource {
        override val lengthBytes: Long = bytes.size.toLong()
        override fun open(offset: Long): InputStream = ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
    }

    private val payload = ByteArray(1000) { (it % 251).toByte() }

    private fun urlFor(path: String) = URL("http://127.0.0.1:$port/$path")

    private fun conn(path: String, method: String = "GET", rangeHeader: String? = null): HttpURLConnection {
        val c = urlFor(path).openConnection() as HttpURLConnection
        c.requestMethod = method
        rangeHeader?.let { c.setRequestProperty("Range", it) }
        return c
    }

    @AfterTest
    fun tearDown() {
        server.stop()
    }

    @Test
    fun `full get returns all bytes`() {
        val url = server.register("m1", BytesSource(payload))
        val c = conn("m1")
        assertEquals(200, c.responseCode)
        assertEquals(1000L, c.contentLengthLong)
        assertTrue(c.inputStream.readBytes().contentEquals(payload))
    }

    @Test
    fun `range middle returns 206 with exact slice`() {
        server.register("m1", BytesSource(payload))
        val c = conn("m1", rangeHeader = "bytes=100-199")
        assertEquals(206, c.responseCode)
        assertEquals("bytes 100-199/1000", c.getHeaderField("Content-Range"))
        val body = c.inputStream.readBytes()
        assertEquals(100, body.size)
        assertTrue(body.contentEquals(payload.copyOfRange(100, 200)))
    }

    @Test
    fun `suffix range returns last n bytes`() {
        server.register("m1", BytesSource(payload))
        val c = conn("m1", rangeHeader = "bytes=-50")
        assertEquals(206, c.responseCode)
        assertTrue(c.inputStream.readBytes().contentEquals(payload.copyOfRange(950, 1000)))
    }

    @Test
    fun `head returns headers without body`() {
        server.register("m1", BytesSource(payload))
        val c = conn("m1", method = "HEAD")
        assertEquals(200, c.responseCode)
        assertEquals(1000L, c.contentLengthLong)
        assertEquals(0, c.inputStream.readBytes().size)
    }

    @Test
    fun `unknown id returns 404`() {
        assertEquals(404, conn("nope").responseCode)
    }

    @Test
    fun `unregister makes id 404`() {
        server.register("m1", BytesSource(payload))
        assertEquals(200, conn("m1").responseCode)
        server.unregister("m1")
        assertEquals(404, conn("m1").responseCode)
    }

    @Test
    fun `register returns loopback url only`() {
        val url = server.register("m1", BytesSource(payload))
        assertTrue(url.startsWith("http://127.0.0.1:"), "只绑回环: $url")
    }

    @Test
    fun `concurrent reads work`() {
        server.register("m1", BytesSource(payload))
        val conns = (1..4).map { conn("m1", rangeHeader = "bytes=${it * 100}-${it * 100 + 99}") }
        conns.forEachIndexed { i, c ->
            assertEquals(206, c.responseCode)
            assertTrue(c.inputStream.readBytes().contentEquals(payload.copyOfRange(i * 100 + 100, i * 100 + 200)))
        }
    }

    @Test
    fun `stop closes port`() {
        server.register("m1", BytesSource(payload))
        server.stop()
        assertFailsWith<Exception> { conn("m1").responseCode }
    }
}
