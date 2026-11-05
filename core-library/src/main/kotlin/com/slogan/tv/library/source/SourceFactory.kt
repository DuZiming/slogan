package com.slogan.tv.library.source

import com.slogan.tv.library.source.smb.SmbFileClient
import com.slogan.tv.library.source.smb.SmbjClient
import com.slogan.tv.library.source.smb.SmbMediaSource
import java.io.File

/**
 * 源工厂：配置 → MediaSource 实例。
 * smbClientFactory 缝隙默认给 SmbjClient（测试注入录制型实现）。
 * NFS 的实现在 :player 模块（libnfs JNI），经 nfsSourceCreator 注入；
 * core 保持纯 JVM，未注入时创建 NFS 源抛 IllegalStateException。
 */
class SourceFactory(
    private val credentialCipher: CredentialCipher,
    private val passwordStore: (String) -> String?,
    private val smbClientFactory: suspend (MediaSourceConfig, String?) -> SmbFileClient =
        { config, password -> SmbjClient(config, password) },
    private val nfsSourceCreator: ((MediaSourceConfig) -> MediaSource)? = null,
) {
    fun create(config: MediaSourceConfig): MediaSource {
        val errors = config.validate()
        require(errors.isEmpty()) { "媒体源配置无效：${errors.joinToString("；")}" }
        val root = config.normalizedShareRoot!!
        return when (config.type) {
            SourceType.LOCAL ->
                LocalMediaSource(config.id, config.name, File(root))
            SourceType.SMB ->
                SmbMediaSource(config.id, config.name, root) {
                    val password = passwordStore(config.id)?.let { credentialCipher.decrypt(it) }
                    smbClientFactory(config, password)
                }
            SourceType.NFS ->
                nfsSourceCreator?.invoke(config)
                    ?: error("NFS 源需要注入工厂（:player 模块的 createNfsSource）")
        }
    }
}
