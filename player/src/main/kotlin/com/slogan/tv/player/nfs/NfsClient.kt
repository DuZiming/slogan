package com.slogan.tv.player.nfs

/** NFS 目录项（与 SmbEntry 对齐的最小字段）。 */
data class NfsEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val mtimeSec: Long,
)

/** NFS 文件读取句柄。 */
class NfsFileHandle internal constructor(
    private val client: NfsClient,
    private val handle: Long,
) {
    fun size(): Long = client.nativeSize(client.context.get(), handle)

    /** 从 offset 读满 buf（返回实际读取数，0=EOF，-1=错误）。 */
    fun readAt(buffer: ByteArray, offset: Long): Int =
        client.nativeReadAt(client.context.get(), handle, buffer, offset)

    internal fun close() = client.nativeCloseFile(client.context.get(), handle)
}

/**
 * libnfs JNI 客户端：JNI 层不抛异常（失败=false/null/-1），Kotlin 侧转可读错误。
 */
class NfsClient {
    internal val context = java.util.concurrent.atomic.AtomicLong(0)

    private companion object {
        /** 与 nfs_jni.c 的 FIELD_SEP（\x1F）一致。 */
        const val FIELD_SEP = ""
    }

    init {
        System.loadLibrary("slogan-nfs")
    }

    /** 连接并挂载导出目录；成功 true。重复调用会先断开旧连接。 */
    fun connect(host: String, export: String, user: String? = null, password: String? = null): Boolean {
        disconnect()
        val ctx = nativeConnect(host, export, user, password)
        context.set(ctx)
        return ctx != 0L
    }

    fun list(dir: String): List<NfsEntry>? {
        val raw = nativeList(context.get(), dir) ?: return null
        if (raw.isEmpty()) return emptyList()
        return raw.split('\n').filter { it.isNotEmpty() }.map { row ->
            val f = row.split(FIELD_SEP)
            NfsEntry(
                name = f.getOrElse(0) { "" },
                isDirectory = f.getOrElse(1) { "0" } == "1",
                sizeBytes = f.getOrElse(2) { "0" }.toLongOrNull() ?: 0L,
                mtimeSec = f.getOrElse(3) { "0" }.toLongOrNull() ?: 0L,
            )
        }
    }

    fun openRead(path: String): NfsFileHandle? {
        val h = nativeOpen(context.get(), path)
        return if (h != 0L) NfsFileHandle(this, h) else null
    }

    fun disconnect() {
        val ctx = context.getAndSet(0)
        if (ctx != 0L) nativeDestroy(ctx)
    }

    // —— native 声明 ——
    private external fun nativeConnect(host: String, export: String, user: String?, password: String?): Long
    private external fun nativeDestroy(ctx: Long)
    private external fun nativeList(ctx: Long, dir: String): String?
    private external fun nativeOpen(ctx: Long, path: String): Long
    internal external fun nativeCloseFile(ctx: Long, handle: Long)
    internal external fun nativeSize(ctx: Long, handle: Long): Long
    internal external fun nativeReadAt(ctx: Long, handle: Long, buf: ByteArray, offset: Long): Int
}
