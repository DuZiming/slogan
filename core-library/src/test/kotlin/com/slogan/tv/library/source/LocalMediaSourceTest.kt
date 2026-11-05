package com.slogan.tv.library.source

import com.slogan.tv.library.model.MediaFile
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMediaSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `scans nested dirs for whitelisted extensions`() = runBlocking {
        tmp.newFolder("a", "b")
        tmp.newFolder("sub")
        tmp.newFile("a/b/movie.mkv")
        tmp.newFile("sub/ep.mp4")
        tmp.newFile("note.txt")
        tmp.newFile("poster.jpg")

        val src = LocalMediaSource("local", "Local", tmp.root.toPath())
        val r = src.scan()

        assertEquals(2, r.files.size)
        assertEquals(0, r.skippedCount)
    }

    @Test
    fun `skips hidden and bdmv internals`() = runBlocking {
        tmp.newFolder(".hidden")
        tmp.newFile(".hidden/x.mkv")
        tmp.newFolder("Movie", "BDMV", "STREAM")
        tmp.newFile("Movie/BDMV/STREAM/00001.m2ts")

        val src = LocalMediaSource("local", "Local", tmp.root.toPath())
        val r = src.scan()

        assertTrue(r.files.isEmpty(), "hidden mkv and BDMV internals must not appear")
        assertEquals(1, r.bdmvDirs.size)
        assertEquals("Movie/BDMV", r.bdmvDirs.single().name)
        assertEquals(2, r.skippedCount)
    }

    @Test
    fun `unreadable entry reports hint not crash`() = runBlocking {
        val src = LocalMediaSource("local", "Local", tmp.root.toPath().resolve("nope"))
        val hints = mutableListOf<ScanUpdate.ErrorHint>()

        val r = src.scan { u -> if (u is ScanUpdate.ErrorHint) hints += u }

        assertTrue(r.files.isEmpty())
        assertEquals(1, hints.size)
    }

    @Test
    fun `progress callback reports found count`() = runBlocking {
        tmp.newFile("m1.mkv")
        tmp.newFile("m2.mkv")
        val src = LocalMediaSource("local", "Local", tmp.root.toPath())
        val updates = mutableListOf<ScanUpdate>()
        src.scan { updates += it }
        val lastProgress = updates.filterIsInstance<ScanUpdate.Progress>().maxByOrNull { it.scanned }
        assertEquals(2, lastProgress?.found)
    }
}
