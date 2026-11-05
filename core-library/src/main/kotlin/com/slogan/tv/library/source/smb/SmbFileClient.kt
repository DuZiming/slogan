package com.slogan.tv.library.source.smb

/**
 * SMB 文件访问接口（六边形隔离层）：真实实现 SmbjClient 保持薄，
 * 扫描逻辑全部依赖本接口，测试用 Fake 驱动（CI 无 NAS）。
 * 路径约定：shareRoot 起始、'/' 分隔，如 "media/movies"。
 */
interface SmbFileClient {
    /** 列出目录直系子项；目录不存在返回空列表；通信类错误抛 SmbCommunicationException。 */
    suspend fun list(dir: String): List<SmbEntry>

    fun close()
}

data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

/** SMB 通信错误分类（错误文案映射见 SmbMediaSource）。 */
class SmbCommunicationException(
    message: String,
    val kind: Kind,
) : Exception(message) {
    enum class Kind { AUTH, TIMEOUT, PROTOCOL, IO }
}
