package com.slogan.tv.library.probe

import com.slogan.tv.library.model.MediaFile

/**
 * 文件头探测 SPI（spec §5.4 画质/规格探测）。
 * 真实实现（FFprobe 系）随播放内核计划引入；本接口为占位契约。
 */
interface ProbeClient {
    suspend fun probe(mediaFile: MediaFile): ProbeInfo
}

data class ProbeInfo(
    val width: Int?,
    val height: Int?,
    val hdrFormat: String?,
    val audioCodecs: List<String>,
    val subtitleTrackCount: Int,
    val durationMs: Long?,
)

/** 无操作实现：未知字段全部为空。 */
object NoopProbeClient : ProbeClient {
    override suspend fun probe(mediaFile: MediaFile): ProbeInfo =
        ProbeInfo(null, null, null, emptyList(), 0, null)
}
