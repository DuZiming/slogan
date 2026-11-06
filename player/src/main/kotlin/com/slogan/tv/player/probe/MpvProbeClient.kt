package com.slogan.tv.player.probe

import android.content.Context
import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.probe.ProbeClient
import com.slogan.tv.library.probe.ProbeInfo
import com.slogan.tv.player.core.MpvCore

/**
 * libmpv 懒探测：独立 MPVLib 实例 loadfile(pause=yes, vo=null) → 轮询属性 → 释放。
 * 3 秒超时兜底返回空 ProbeInfo（上层用文件名 Token 兜底）。
 */
class MpvProbeClient(
    private val context: Context,
    private val timeoutMs: Long = 3_000,
) : ProbeClient {

    override suspend fun probe(mediaFile: MediaFile): ProbeInfo {
        val empty = ProbeInfo(null, null, null, emptyList(), 0, null)
        val mpv = runCatching { MpvCore.create(context) }.getOrNull() ?: return empty
        try {
            mpv.setOptionString("vo", "null")
            mpv.setOptionString("ao", "null")
            mpv.setOptionString("pause", "yes")
            mpv.setOptionString("idle", "yes")
            mpv.init()
            mpv.command("loadfile", mediaFile.path, "replace")

            // 轮询 duration 直到非空或超时（文件装载完成的标志）
            val deadline = System.currentTimeMillis() + timeoutMs
            var duration: Double? = null
            while (System.currentTimeMillis() < deadline) {
                duration = runCatching { mpv.getPropertyDouble("duration") }.getOrNull()
                if (duration != null && duration > 0) break
                Thread.sleep(50)
            }
            if (duration == null || duration <= 0) return empty

            val width = runCatching { mpv.getPropertyInt("width") }.getOrNull()
            val height = runCatching { mpv.getPropertyInt("height") }.getOrNull()
            val hdr = runCatching { mpv.getPropertyString("video-params/hdr-metadata") }.getOrNull()
            val durationMs = (duration * 1000).toLong()

            val audioCodecs = mutableListOf<String>()
            var subCount = 0
            val trackCount = runCatching { mpv.getPropertyInt("track-list/count") }.getOrNull() ?: 0
            for (n in 0 until trackCount) {
                val type = runCatching { mpv.getPropertyString("track-list/$n/type") }.getOrNull()
                val codec = runCatching { mpv.getPropertyString("track-list/$n/codec") }.getOrNull()
                when (type) {
                    "audio" -> codec?.let { audioCodecs += it }
                    "sub" -> subCount++
                }
            }
            return ProbeInfo(
                width = width,
                height = height,
                hdrFormat = hdr?.takeIf { it.isNotBlank() },
                audioCodecs = audioCodecs,
                subtitleTrackCount = subCount,
                durationMs = durationMs,
            )
        } finally {
            runCatching { mpv.destroy() }
        }
    }
}
