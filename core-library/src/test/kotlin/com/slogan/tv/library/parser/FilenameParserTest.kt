package com.slogan.tv.library.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class FilenameParserTest {

    @Test
    fun `title and year from dotted name`() {
        val r = FilenameParser.parse("Inception.2010.mkv")
        assertEquals("Inception", r.title)
        assertEquals(2010, r.year)
    }

    @Test
    fun `chinese title untouched`() {
        val r = FilenameParser.parse("让子弹飞.2010.mkv")
        assertEquals("让子弹飞", r.title)
        assertEquals(2010, r.year)
    }

    @Test
    fun `no year leaves null`() {
        assertEquals(null, FilenameParser.parse("Something.mkv").year)
    }

    @Test
    fun `release tags stripped`() {
        assertEquals(
            "Dune Part Two",
            FilenameParser.parse("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.H.265.mkv").title,
        )
    }

    @Test
    fun `release group stripped`() {
        assertEquals(
            "Oppenheimer",
            FilenameParser.parse("Oppenheimer.2023.2160p.BluRay.REMUX-GROUP1.mkv").title,
        )
    }

    @Test
    fun `separators normalized`() {
        assertEquals("My Movie", FilenameParser.parse("My_Movie_2019.mp4").title)
    }
}
