package com.slogan.tv.library.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PassthroughPolicyTest {

    private val caps = AudioCapabilities(setOf("ac3", "eac3", "dts"))

    @Test
    fun `auto with support and matching codec passes through`() {
        val d = PassthroughPolicy.resolve(PassthroughMode.AUTO, caps, "dts")
        assertTrue(d.usePassthrough)
        assertEquals(setOf("dts"), d.spdifCodecs)
    }

    @Test
    fun `auto without device support falls back to pcm`() {
        val d = PassthroughPolicy.resolve(PassthroughMode.AUTO, caps, "truehd")
        assertFalse(d.usePassthrough)
        assertTrue(d.spdifCodecs.isEmpty())
        assertTrue(d.reason.contains("不支持"))
    }

    @Test
    fun `auto without file codec info stays pcm`() {
        val d = PassthroughPolicy.resolve(PassthroughMode.AUTO, caps, null)
        assertFalse(d.usePassthrough, "文件音轨未知时保守走 PCM")
    }

    @Test
    fun `force passthrough keeps spdif even without support`() {
        val d = PassthroughPolicy.resolve(PassthroughMode.FORCE_PASSTHROUGH, caps, "truehd")
        assertTrue(d.usePassthrough, "强制模式尊重用户意愿")
        assertEquals(setOf("truehd"), d.spdifCodecs)
        assertTrue(d.reason.contains("设备未报告支持"))
    }

    @Test
    fun `force pcm never passes through`() {
        val d = PassthroughPolicy.resolve(PassthroughMode.FORCE_PCM, caps, "dts")
        assertFalse(d.usePassthrough)
    }

    @Test
    fun `codec name normalization`() {
        val d = PassthroughPolicy.resolve(PassthroughMode.AUTO, caps, "dts-hd") // 归一到 dts 家族
        assertTrue(d.usePassthrough)
    }
}
