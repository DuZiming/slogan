package com.slogan.tv.library.source

/** 媒体源类型（NFS 扫描/播放在计划 3 落地）。 */
enum class SourceType { LOCAL, SMB, NFS }

/**
 * 媒体源配置（spec §5.4 设置页：媒体源管理）。
 * shareRoot 双语义：LOCAL = 文件系统根路径；SMB = 共享内起始路径（首段为共享名，如 "media" 或 "media/movies"）。
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

    /**
     * shareRoot 归一化（评审 I6）：trim、反斜杠→正斜杠、去首尾斜杠。
     * Windows 粘贴的 \\NAS\media\movies、/media/、media\ 等形态统一为 "media/movies"。
     */
    val normalizedShareRoot: String?
        get() = shareRoot
            ?.trim()
            ?.replace('\\', '/')
            ?.trim('/')
            ?.takeIf { it.isNotEmpty() }

    /** 字段校验，返回中文错误文案；空列表 = 通过。 */
    fun validate(): List<String> = buildList {
        when (type) {
            SourceType.SMB, SourceType.NFS -> {
                if (host.isNullOrBlank()) add("${type.name} 源必须填写主机地址 host")
                if (normalizedShareRoot == null) add("${type.name} 源必须填写共享目录 shareRoot")
                if (port != null && (port !in 1..65535)) add("端口必须在 1..65535")
            }
            SourceType.LOCAL -> {
                if (host != null) add("本地源不应填写主机地址 host")
                if (normalizedShareRoot == null) add("本地源必须填写根路径 shareRoot")
            }
        }
    }
}
