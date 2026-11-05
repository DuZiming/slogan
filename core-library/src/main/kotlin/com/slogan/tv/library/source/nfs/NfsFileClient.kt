package com.slogan.tv.library.source.nfs

/** NFS 目录项（与 SmbEntry 对齐的最小字段）。 */
data class NfsDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mtimeSec: Long,
)

/** NFS 文件读取句柄（桥到 StreamSource 供回环流媒体服务器使用）。 */
interface NfsReadHandle {
    val sizeBytes: Long?

    /** 从 offset 读入 buf，返回实际读取数（0=EOF，-1=错误）。 */
    fun readAt(buffer: ByteArray, offset: Long): Int

    fun close()
}

/**
 * NFS 文件访问接口（六边形隔离层，镜像 SmbFileClient）：
 * 真实实现（libnfs JNI 适配器）在 :player 模块，扫描逻辑全部依赖本接口。
 * 路径约定：'/' 分隔，从 export 根起（如 "/export/Movies"）。
 */
interface NfsFileClient {
    /** 连接并挂载 export；成功 true。 */
    suspend fun connect(host: String, export: String, user: String?, password: String?): Boolean

    /** 列目录；未连接/失败返回 null。 */
    suspend fun list(dir: String): List<NfsDirEntry>?

    fun openRead(path: String): NfsReadHandle?

    fun close()
}
