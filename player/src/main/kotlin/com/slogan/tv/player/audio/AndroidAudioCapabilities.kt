package com.slogan.tv.player.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import com.slogan.tv.library.audio.AudioCapabilities

/**
 * 安卓侧能力探测薄层：TV 设备的 HDMI 输出支持哪些编码。
 * 查询不可得时返回保守空集（全部走 PCM——永远有声音）。
 */
class AndroidAudioCapabilities(
    private val context: Context,
) : () -> AudioCapabilities {

    override fun invoke(): AudioCapabilities {
        val encodings = runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            devices
                .flatMap { device ->
                    if (device.supportsEncodings != null) device.supportsEncodings.toList() else emptyList()
                }
                .mapNotNull { encoding -> encoding.toCodecName() }
                .toSet()
        }.getOrDefault(emptySet())
        return AudioCapabilities(encodings)
    }

    private fun Int.toCodecName(): String? = when (this) {
        AudioFormat.ENCODING_AC3 -> "ac3"
        AudioFormat.ENCODING_E_AC3 -> "eac3"
        0x05000000 + 0x00000100 /* AudioFormat.ENCODING_DTS, API34 常量 */ -> "dts"
        0x05000000 + 0x00000200 /* ENCODING_DTS_HD */ -> "dts"
        0x05000000 + 0x00000300 /* ENCODING_DOLBY_TRUEHD */ -> "truehd"
        else -> null
    }
}
