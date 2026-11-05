package com.slogan.tv.library.source.smb

import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmbErrorSemanticsTest {

    private suspend fun hintsFor(kind: SmbCommunicationException.Kind): List<String> {
        val fake = FakeSmbFileClient(mapOf("media" to emptyList()))
        fake.failWith("media", SmbCommunicationException("raw msg", kind))
        val hints = mutableListOf<String>()
        SmbMediaSource("nas", "NAS", "media") { fake }
            .scan { u -> if (u is ScanUpdate.ErrorHint) hints += u.message }
        return hints
    }

    @Test
    fun `auth failure message`() = runBlocking<Unit> {
        assertEquals(listOf("认证失败，请检查账号密码"), hintsFor(SmbCommunicationException.Kind.AUTH))
    }

    @Test
    fun `timeout failure message`() = runBlocking<Unit> {
        assertEquals(listOf("连接超时，请检查主机地址与网络"), hintsFor(SmbCommunicationException.Kind.TIMEOUT))
    }

    @Test
    fun `protocol failure mentions smb1`() = runBlocking<Unit> {
        assertTrue(hintsFor(SmbCommunicationException.Kind.PROTOCOL).single().contains("SMB1"))
    }

    @Test
    fun `io failure keeps raw message`() = runBlocking<Unit> {
        assertEquals(listOf("raw msg"), hintsFor(SmbCommunicationException.Kind.IO))
    }
}
