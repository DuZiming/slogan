package com.slogan.tv.library.source

/** 媒体源类型（NFS 属后续计划）。 */
enum class SourceType { LOCAL, SMB }

/**
 * 媒体源配置（spec §5.4 设置页：媒体源管理）。
 * 密码不入本模型——单独经 CredentialCipher 存取（见 Credentials.kt）。
 */
data class MediaSourceConfig(
    val id: String,
    val name: String,
    val type: SourceType,
    val host: String? = null,
    val port: Int? = null,
    val shareRoot: String? = null,
    val username: String? = null,
    val domain: String? = null,
) {
    /** SMB 有效端口（缺省 445）。 */
    val effectivePort: Int
        get() = port ?: 445

    /** 字段校验，返回中文错误文案；空列表 = 通过。 */
    fun validate(): List<String> = buildList {
        when (type) {
            SourceType.SMB -> {
                if (host.isNullOrBlank()) add("SMB 源必须填写主机地址 host")
                if (shareRoot.isNullOrBlank()) add("SMB 源必须填写共享目录 shareRoot")
                if (port != null && (port !in 1..65535)) add("端口必须在 1..65535")
            }
            SourceType.LOCAL -> {
                if (host != null) add("本地源不应填写主机地址 host")
                if (shareRoot != null) add("本地源不应填写共享目录 shareRoot")
            }
        }
    }
}
