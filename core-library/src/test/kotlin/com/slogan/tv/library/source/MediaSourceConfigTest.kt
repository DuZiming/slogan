package com.slogan.tv.library.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaSourceConfigTest {

    @Test
    fun `valid smb config has no errors and default port`() {
        val c = MediaSourceConfig(id = "s1", name = "NAS", type = SourceType.SMB, host = "192.168.1.10", shareRoot = "media")
        assertEquals(emptyList(), c.validate())
    }

    @Test
    fun `smb missing share root reports error`() {
        val c = MediaSourceConfig(id = "s1", name = "NAS", type = SourceType.SMB, host = "192.168.1.10")
        assertTrue(c.validate().any { it.contains("shareRoot") || it.contains("共享") })
    }

    @Test
    fun `smb missing host reports error`() {
        val c = MediaSourceConfig(id = "s1", name = "NAS", type = SourceType.SMB, shareRoot = "media")
        assertTrue(c.validate().any { it.contains("host") || it.contains("主机") })
    }

    @Test
    fun `local config must not carry smb host`() {
        val c = MediaSourceConfig(id = "l1", name = "U盘", type = SourceType.LOCAL, host = "192.168.1.10", shareRoot = "D:/media")
        assertTrue(c.validate().any { it.contains("host") })
    }

    @Test
    fun `local config requires root path`() {
        val c = MediaSourceConfig(id = "l1", name = "U盘", type = SourceType.LOCAL)
        assertTrue(c.validate().any { it.contains("shareRoot") || it.contains("路径") })
    }

    @Test
    fun `local config with root path is valid`() {
        val c = MediaSourceConfig(id = "l1", name = "U盘", type = SourceType.LOCAL, shareRoot = "D:/media")
        assertEquals(emptyList(), c.validate())
    }
}
