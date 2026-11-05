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
}
