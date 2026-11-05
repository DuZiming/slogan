package com.slogan.tv.library.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class CredentialsTest {

    private val key1 = ByteArray(32) { it.toByte() }
    private val key2 = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `aes round trip`() {
        val cipher = AesCredentialCipher { key1 }
        val secret = "p@ss中文密码!16"
        assertEquals(secret, cipher.decrypt(cipher.encrypt(secret)))
    }

    @Test
    fun `cipher text differs from plain`() {
        val cipher = AesCredentialCipher { key1 }
        assertNotEquals("secret", cipher.encrypt("secret"))
    }

    @Test
    fun `random iv makes cipher texts differ`() {
        val cipher = AesCredentialCipher { key1 }
        assertNotEquals(cipher.encrypt("same"), cipher.encrypt("same"))
    }

    @Test
    fun `wrong key fails to decrypt`() {
        val c1 = AesCredentialCipher { key1 }
        val c2 = AesCredentialCipher { key2 }
        val encrypted = c1.encrypt("secret")
        assertFailsWith<Exception> { c2.decrypt(encrypted) }
    }

    @Test
    fun `plain cipher is passthrough`() {
        assertEquals("abc", PlainCredentialCipher.encrypt("abc"))
        assertEquals("abc", PlainCredentialCipher.decrypt("abc"))
    }
}
