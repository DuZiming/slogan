package com.slogan.tv.library.source

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanRulesTest {

    @Test
    fun `system and hidden dirs are skipped`() {
        assertEquals(true, ScanRules.isSkippedDir("\$RECYCLE.BIN"))
        assertEquals(true, ScanRules.isSkippedDir("System Volume Information"))
        assertEquals(true, ScanRules.isSkippedDir("#recycle"))
        assertEquals(true, ScanRules.isSkippedDir(".hidden"))
        assertEquals(false, ScanRules.isSkippedDir("普通目录"))
        assertEquals(false, ScanRules.isSkippedDir("Movies 4K"))
    }

    @Test
    fun `hidden or zero byte files are skipped`() {
        assertEquals(true, ScanRules.isHiddenOrEmptyFile(".x.mkv", 1L))
        assertEquals(true, ScanRules.isHiddenOrEmptyFile("x.mkv", 0L))
        assertEquals(false, ScanRules.isHiddenOrEmptyFile("x.mkv", 1L))
    }

    @Test
    fun `video whitelist case insensitive`() {
        assertEquals(true, ScanRules.isVideoFile("a.MKV"))
        assertEquals(true, ScanRules.isVideoFile("b.1080p.WEB-DL.mkv"))
        assertEquals(false, ScanRules.isVideoFile("a.txt"))
        assertEquals(false, ScanRules.isVideoFile("poster.jpg"))
        assertEquals(false, ScanRules.isVideoFile("noext"))
    }
}
