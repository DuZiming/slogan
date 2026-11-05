package com.slogan.tv.library.parser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EpisodePatternTest {

    @Test
    fun `sxe pattern`() {
        val r = FilenameParser.parse("Show.S01E05.1080p.mkv")
        assertTrue(r.isTvShow)
        assertEquals(1, r.season)
        assertEquals(5..5, r.episodes)
    }

    @Test
    fun `multi episode`() {
        val r = FilenameParser.parse("Show.S01E05E06.1080p.mkv")
        assertEquals(5..6, r.episodes)
        val r2 = FilenameParser.parse("Show.E05-E06.mkv")
        assertEquals(5..6, r2.episodes)
    }

    @Test
    fun `chinese season episode`() {
        val r = FilenameParser.parse("三体.第2季第5集.4K.mkv")
        assertTrue(r.isTvShow)
        assertEquals(2, r.season)
        assertEquals(5..5, r.episodes)
        assertEquals("三体", r.title)
    }
}
