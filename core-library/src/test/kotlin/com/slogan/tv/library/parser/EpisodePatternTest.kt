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

    @Test
    fun `season episode range with dash`() {
        val r = FilenameParser.parse("Westworld.S02E01-E03.720p.mkv")
        assertTrue(r.isTvShow)
        assertEquals(2, r.season)
        assertEquals(1..3, r.episodes)
        assertEquals("Westworld", r.title)
    }

    @Test
    fun `three adjacent episodes`() {
        val r = FilenameParser.parse("Show.S01E05E06E07.1080p.mkv")
        assertEquals(1, r.season)
        assertEquals(5..7, r.episodes)
    }

    @Test
    fun `adjacent episodes without season`() {
        val r = FilenameParser.parse("Show.E05E06.mkv")
        assertTrue(r.isTvShow)
        assertEquals(null, r.season)
        assertEquals(5..6, r.episodes)
    }

    @Test
    fun `year and episode together`() {
        val r = FilenameParser.parse("Fargo.2014.S01E05.720p.mkv")
        assertEquals(2014, r.year)
        assertEquals(1, r.season)
        assertEquals(5..5, r.episodes)
        assertEquals("Fargo", r.title)
    }

    @Test
    fun `chinese episode without season`() {
        val r = FilenameParser.parse("斗罗大陆.第01集.1080p.mkv")
        assertTrue(r.isTvShow)
        assertEquals(null, r.season)
        assertEquals(1..1, r.episodes)
        assertEquals("斗罗大陆", r.title)
    }

    @Test
    fun `chinese episode range`() {
        val r = FilenameParser.parse("三体.第2季第5-6集.4K.mkv")
        assertEquals(2, r.season)
        assertEquals(5..6, r.episodes)
        assertEquals("三体", r.title)
    }
}
