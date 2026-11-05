package com.slogan.tv.library.stream

import com.slogan.tv.library.model.MediaFile
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PlaybackUriResolverTest {

    private val server = LoopbackStreamServer()
    private val payload = ByteArray(500) { (it % 7).toByte() }

    private val fakeRemote: suspend (MediaFile) -> StreamSource? = { _ ->
        object : StreamSource {
            override val lengthBytes = payload.size.toLong()
            override fun open(offset: Long): InputStream =
                ByteArrayInputStream(payload, offset.toInt(), payload.size - offset.toInt())
        }
    }
    private val localOnly: suspend (MediaFile) -> StreamSource? = { null }

    private fun file(sourceId: String, path: String, relativePath: String? = path) =
        MediaFile(sourceId, path, path.substringAfterLast('/'), 1L, 2L, relativePath)

    @AfterTest
    fun tearDown() = server.stop()

    @Test
    fun `local file returns path directly`() = runBlocking {
        val resolver = PlaybackUriResolver(server, localOnly)
        assertEquals("/storage/a.mkv", resolver.resolve(file("local", "/storage/a.mkv")))
    }

    @Test
    fun `remote file returns loopback url serving bytes`() = runBlocking {
        val resolver = PlaybackUriResolver(server, fakeRemote)
        val url = resolver.resolve(file("nas", "media/a.mkv"))
        assertTrue(url.startsWith("http://127.0.0.1:"), "应是回环 URL: $url")

        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        assertEquals(200, conn.responseCode)
        assertTrue(conn.inputStream.readBytes().contentEquals(payload))
    }

    @Test
    fun `same file resolves to same url`() = runBlocking {
        val resolver = PlaybackUriResolver(server, fakeRemote)
        val f = file("nas", "media/a.mkv")
        assertEquals(resolver.resolve(f), resolver.resolve(f))
    }

    @Test
    fun `different files get different urls`() = runBlocking {
        val resolver = PlaybackUriResolver(server, fakeRemote)
        assertNotEquals(resolver.resolve(file("nas", "media/a.mkv")), resolver.resolve(file("nas", "media/b.mkv")))
    }
}
