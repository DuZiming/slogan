package com.slogan.tv.library.source.smb

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FakeSmbFileClientTest {

    @Test
    fun `list returns preset entries`() = runBlocking {
        val fake = FakeSmbFileClient(
            mapOf(
                "share" to listOf(SmbEntry("电影.mkv", false, 100L, 1L)),
            ),
        )
        val entries = fake.list("share")
        assertEquals(1, entries.size)
        assertEquals("电影.mkv", entries.single().name)
    }

    @Test
    fun `unknown dir returns empty`() = runBlocking {
        val fake = FakeSmbFileClient(emptyMap())
        assertTrue(fake.list("nope").isEmpty())
    }

    @Test
    fun `injected exception is thrown`() = runBlocking {
        val fake = FakeSmbFileClient(emptyMap())
        fake.failWith("share", SmbCommunicationException("bad credentials", SmbCommunicationException.Kind.AUTH))
        assertFailsWith<SmbCommunicationException> { fake.list("share") }
    }
}
