package com.slogan.tv.library.group

import com.slogan.tv.library.model.LibraryEntry
import com.slogan.tv.library.model.MediaFile
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryGrouperTest {

    private fun mf(name: String) =
        MediaFile(sourceId = "local", path = "/media/$name", name = name, sizeBytes = 1L, lastModifiedEpochMs = 1L)

    @Test
    fun `movies never merge`() {
        val e = LibraryGrouper.group(listOf(mf("X.2009.mkv"), mf("X.2009.2160p.REMUX.mkv")))
        assertEquals(2, e.filterIsInstance<LibraryEntry.Movie>().size)
    }

    @Test
    fun `episodes grouped by series then season`() {
        val e = LibraryGrouper.group(
            listOf(mf("三体.S01E01.mkv"), mf("三体.S01E02.mkv"), mf("三体.S02E01.mkv")),
        )
        val s = e.single() as LibraryEntry.Series
        assertEquals(setOf(1, 2), s.seasons.keys)
        assertEquals(2, s.seasons.getValue(1).size)
    }

    @Test
    fun `series sorted episodes`() {
        val e = LibraryGrouper.group(listOf(mf("三体.S01E02.mkv"), mf("三体.S01E01.mkv")))
        val s = e.single() as LibraryEntry.Series
        assertEquals(listOf(1, 2), s.seasons.getValue(1).map { it.episode })
    }
}
