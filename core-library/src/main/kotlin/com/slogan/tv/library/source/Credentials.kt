package com.slogan.tv.library.source

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** SMB 登录凭据（密码只在内存中经 Cipher 转换，不进 MediaSourceConfig）。 */
data class SmbCredentials(
    val username: String?,
    val domain: String?,
    val password: String?,
)

/** 凭据加密接口：默认实现必须加密，Plain 仅限测试。 */
interface CredentialCipher {
    fun encrypt(plain: String): String

    fun decrypt(cipher: String): String
}

/** 直通实现——仅测试用。 */
object PlainCredentialCipher : CredentialCipher {
    override fun encrypt(plain: String): String = plain
    override fun decrypt(cipher: String): String = cipher
}

/**
 * AES/GCM 实现：随机 12 字节 IV 前置密文，整体 Base64。
 * 密钥由 keyProvider 注入（32 字节；Android 侧计划 4 接 Keystore 派生）。
 */
class AesCredentialCipher(private val keyProvider: () -> ByteArray) : CredentialCipher {

    override fun encrypt(plain: String): String {
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyProvider(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    override fun decrypt(cipherText: String): String {
        val all = Base64.getDecoder().decode(cipherText)
        require(all.size > IV_LEN) { "cipher text too short" }
        val iv = all.copyOfRange(0, IV_LEN)
        val encrypted = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyProvider(), "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_LEN = 12
        const val TAG_BITS = 128
        val random = SecureRandom()
    }
}
