package com.slogan.tv.library.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EdgeCaseTest {

    @Test
    fun `mixed chinese english name`() {
        val r = FilenameParser.parse("沙丘2.2024.2160p.WEB-DL.HDR.mkv")
        assertEquals("沙丘2", r.title)
        assertEquals(2024, r.year)
        assertFalse(r.usedFallback)
    }

    @Test
    fun `numeric only falls back`() {
        val r = FilenameParser.parse("2023.mp4")
        assertEquals("2023", r.title)
        assertTrue(r.usedFallback)
    }

    @Test
    fun `blank after clean falls back`() {
        val r = FilenameParser.parse("2160p.WEB-DL.mkv")
        assertTrue(r.usedFallback)
    }

    @Test
    fun `never throws`() {
        try {
            FilenameParser.parse("...")
            FilenameParser.parse("第季集.mkv")
        } catch (e: Throwable) {
            throw AssertionError("parser threw for degenerate input", e)
        }
    }

    @Test
    fun `fullwidth parenthesized year`() {
        val r = FilenameParser.parse("沙丘（2024）.2160p.WEB-DL.mkv")
        assertEquals(2024, r.year)
        assertEquals("沙丘", r.title)
    }

    @Test
    fun `square bracket year`() {
        val r = FilenameParser.parse("阿凡达【2009】.mkv")
        assertEquals(2009, r.year)
        assertEquals("阿凡达", r.title)
    }
}
