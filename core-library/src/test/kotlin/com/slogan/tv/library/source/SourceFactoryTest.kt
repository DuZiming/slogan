package com.slogan.tv.library.source

import com.slogan.tv.library.source.smb.SmbEntry
import com.slogan.tv.library.source.smb.SmbFileClient
import com.slogan.tv.library.source.smb.SmbMediaSource
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceFactoryTest {

    private var recordedPassword: String? = "NOT_INVOKED"

    private val recordingFactory: suspend (MediaSourceConfig, String?) -> SmbFileClient =
        { _, pwd ->
            recordedPassword = pwd
            object : SmbFileClient {
                override suspend fun list(dir: String) = emptyList<SmbEntry>()
                override fun close() = Unit
            }
        }

    @Test
    fun `local config creates local source with root`() {
        val factory = SourceFactory(PlainCredentialCipher, { null })
        val src = factory.create(
            MediaSourceConfig("l1", "U盘", SourceType.LOCAL, shareRoot = "D:/media"),
        )
        val local = src as LocalMediaSource
        assertEquals(File("D:/media"), local.root)
    }

    @Test
    fun `smb config creates smb source`() {
        val factory = SourceFactory(PlainCredentialCipher, { null }, smbClientFactory = recordingFactory)
        val src = factory.create(
            MediaSourceConfig("s1", "NAS", SourceType.SMB, host = "192.168.1.10", shareRoot = "media"),
        )
        assertTrue(src is SmbMediaSource)
    }

    @Test
    fun `smb password is decrypted before entering client`() = runBlocking<Unit> {
        val cipher = AesCredentialCipher { ByteArray(32) { 7 } }
        val encrypted = cipher.encrypt("secret-pw")
        val factory = SourceFactory(cipher, { encrypted }, smbClientFactory = recordingFactory)

        val src = factory.create(
            MediaSourceConfig("s1", "NAS", SourceType.SMB, host = "192.168.1.10", shareRoot = "media"),
        )
        src.scan() // 触发 clientFactory，密码此刻解密注入

        assertEquals("secret-pw", recordedPassword)
    }

    @Test
    fun `invalid config rejected with readable error`() {
        val factory = SourceFactory(PlainCredentialCipher, { null })
        val invalid = MediaSourceConfig("s1", "NAS", SourceType.SMB, host = null, shareRoot = null)
        val error = kotlin.test.assertFailsWith<IllegalArgumentException> { factory.create(invalid) }
        assertTrue(error.message!!.contains("host"))
    }
}
