package com.slogan.tv.player.nfs

import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.MediaSourceConfig
import com.slogan.tv.library.source.nfs.NfsDirEntry
import com.slogan.tv.library.source.nfs.NfsFileClient
import com.slogan.tv.library.source.nfs.NfsMediaSource
import com.slogan.tv.library.source.nfs.NfsReadHandle
import com.slogan.tv.library.stream.StreamSource
import java.io.InputStream

/**
 * JNI NfsClient → core 的 NfsFileClient 适配器。
 * libnfs 挂载后路径相对 mount 点：把 core 的 export 前缀路径（"/export/Movies"）
 * 剥成挂载内相对路径（"Movies"）。
 */
class NfsClientAdapter(
    private val client: NfsClient,
    private val export: String,
) : NfsFileClient {

    private fun inMount(dir: String): String =
        dir.removePrefix(export).removePrefix("/")

    override suspend fun connect(host: String, export: String, user: String?, password: String?): Boolean =
        client.connect(host, export, user, password)

    override suspend fun list(dir: String): List<NfsDirEntry>? =
        client.list(inMount(dir))?.map { e ->
            NfsDirEntry(e.name, e.isDirectory, e.sizeBytes, e.mtimeSec)
        }

    override fun openRead(path: String): NfsReadHandle? {
        val h = client.openRead(inMount(path)) ?: return null
        return object : NfsReadHandle {
            override val sizeBytes: Long? = h.size().takeIf { it >= 0 }
            override fun readAt(buffer: ByteArray, offset: Long): Int = h.readAt(buffer, offset)
            override fun close() = h.close()
        }
    }

    override fun close() = client.disconnect()
}

/**
 * NFS 播放流桥：每次 open 建独立连接与句柄（天然支持 Range 随机读）。
 * length 探测一次（独立短连接）。
 */
class NfsStreamSource(
    private val host: String,
    private val export: String,
    private val pathInExport: String,
) : StreamSource {

    private val relative = pathInExport.removePrefix(export).removePrefix("/")

    override val lengthBytes: Long? = runCatching {
        val probe = NfsClient()
        try {
            if (!probe.connect(host, export, null, null)) return@runCatching null
            val h = probe.openRead(relative) ?: return@runCatching null
            try {
                h.size().takeIf { s -> s >= 0 }
            } finally {
                h.close()
            }
        } finally {
            probe.disconnect()
        }
    }.getOrNull()

    override fun open(offset: Long): InputStream = object : InputStream() {
        private val client = NfsClient()
        private val handle = runCatching {
            if (client.connect(host, export, null, null)) {
                client.openRead(relative)
            } else {
                null
            }
        }.getOrNull()
        private var pos = offset

        override fun read(): Int {
            val buf = ByteArray(1)
            val n = handle?.readAt(buf, pos) ?: -1
            if (n <= 0) return -1
            pos += n
            return buf[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val buf = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            val n = handle?.readAt(buf, pos) ?: -1
            if (n <= 0) return -1
            if (buf !== b) System.arraycopy(buf, 0, b, off, n)
            pos += n
            return n
        }

        override fun close() {
            handle?.close()
            client.disconnect()
        }
    }
}

/** :player 侧 NFS 源工厂（注入 SourceFactory.nfsSourceCreator）。 */
fun createNfsSource(config: MediaSourceConfig): MediaSource {
    val host = config.host!!
    val export = "/" + (config.normalizedShareRoot ?: "")
    return NfsMediaSource(config.id, config.name, host, export) {
        NfsClientAdapter(NfsClient(), export)
    }
}

val nfsSourceCreator: (MediaSourceConfig) -> MediaSource = ::createNfsSource
