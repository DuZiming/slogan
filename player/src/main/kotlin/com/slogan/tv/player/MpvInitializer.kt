package com.slogan.tv.player

import android.content.Context
import com.slogan.tv.player.core.MpvCore

/** libmpv 探测（LGPL 自建内核）。 */
object MpvInitializer {
    fun probeVersion(context: Context): String? = runCatching {
        val mpv = MpvCore.create(context) ?: return null
        try {
            mpv.init()
            mpv.getPropertyString("mpv-version")
        } finally {
            mpv.destroy()
        }
    }.getOrNull()
}
