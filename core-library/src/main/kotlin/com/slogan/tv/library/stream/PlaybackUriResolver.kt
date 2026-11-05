package com.slogan.tv.library.stream

import com.slogan.tv.library.model.MediaFile
import java.util.concurrent.ConcurrentHashMap

/**
 * 播放地址解析（spec §5.6 播放入口）：
 * - 本地文件（streamSourceOf 返回 null）→ 直接返回文件路径；
 * - 远端文件（SMB/NFS）→ 注册到回环流媒体服务器，返回 127.0.0.1 URL；
 *   同一文件重复解析复用同一 URL/注册项。
 */
class PlaybackUriResolver(
    private val server: LoopbackStreamServer,
    private val streamSourceOf: suspend (MediaFile) -> StreamSource?,
) {
    private val registered = ConcurrentHashMap<String, String>()

    suspend fun resolve(file: MediaFile): String {
        val source = streamSourceOf(file) ?: return file.path

        registered[file.path]?.let { return it }
        val id = "${file.sourceId}:${file.path}"
        val url = server.register(id, source)
        registered[file.path] = url
        return url
    }

    /** 播放会话结束时清注册（server.stop 也会清）。 */
    fun clear() {
        registered.keys.clear()
    }
}
