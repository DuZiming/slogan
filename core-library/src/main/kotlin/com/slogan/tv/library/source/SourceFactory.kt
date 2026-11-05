package com.slogan.tv.library.source

import com.slogan.tv.library.source.smb.SmbFileClient
import com.slogan.tv.library.source.smb.SmbjClient
import com.slogan.tv.library.source.smb.SmbMediaSource
import java.io.File

/**
 * 源工厂：配置 → MediaSource 实例。
 * smbClientFactory 缝隙默认给 SmbjClient（测试注入录制型实现）。
 */
class SourceFactory(
    private val credentialCipher: CredentialCipher,
    private val passwordStore: (String) -> String?,
    private val smbClientFactory: suspend (MediaSourceConfig, String?) -> SmbFileClient =
        { config, password -> SmbjClient(config, password) },
) {
    fun create(config: MediaSourceConfig): MediaSource =
        when (config.type) {
            SourceType.LOCAL ->
                LocalMediaSource(config.id, config.name, File(config.shareRoot!!))
            SourceType.SMB ->
                SmbMediaSource(config.id, config.name, config.shareRoot!!) {
                    val password = passwordStore(config.id)?.let { credentialCipher.decrypt(it) }
                    smbClientFactory(config, password)
                }
        }
}
