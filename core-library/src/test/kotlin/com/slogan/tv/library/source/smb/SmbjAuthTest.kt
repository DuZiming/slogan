package com.slogan.tv.library.source.smb

import com.slogan.tv.library.source.MediaSourceConfig
import com.slogan.tv.library.source.SourceType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 评审 C1：匿名共享（username=null）必须走 smbj 的 anonymous()，null 会 NPE。 */
class SmbjAuthTest {

    @Test
    fun `null username builds anonymous context`() {
        val config = MediaSourceConfig("s1", "NAS", SourceType.SMB, host = "h", shareRoot = "media")
        val ctx = SmbjClient.buildAuthContext(config, password = null)
        assertTrue(ctx.isAnonymous)
        assertEquals("", ctx.username)
    }

    @Test
    fun `blank username builds anonymous context`() {
        val config = MediaSourceConfig("s1", "NAS", SourceType.SMB, host = "h", shareRoot = "media", username = "  ")
        val ctx = SmbjClient.buildAuthContext(config, password = null)
        assertTrue(ctx.isAnonymous)
    }

    @Test
    fun `present username kept with password`() {
        val config = MediaSourceConfig("s1", "NAS", SourceType.SMB, host = "h", shareRoot = "media", username = "u")
        val ctx = SmbjClient.buildAuthContext(config, password = "p")
        assertEquals("u", ctx.username)
        assertEquals("p", String(ctx.password))
    }
}
