package com.slogan.tv.player

import android.content.Context
import dev.jdtech.mpv.MPVLib

/**
 * libmpv 初始化与探测。内核为社区预编译 AAR（计划 3 选型 C），本对象是唯一触碰
 * MPVLib 创建/销毁细节的地方之一（另一个是 MpvEngine）。
 */
object MpvInitializer {

    /** 创建一次性实例读取 mpv 版本号；任何失败返回 null（不抛——内核不可用时上层兜底）。 */
    fun probeVersion(context: Context): String? = runCatching {
        val mpv = MPVLib.create(context) ?: return null
        try {
            mpv.init()
            mpv.getPropertyString("mpv-version")
        } finally {
            mpv.destroy()
        }
    }.getOrNull()
}
