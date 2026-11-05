package com.slogan.tv.library.source

import com.slogan.tv.library.model.MediaFile
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LocalMediaSourceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** TemporaryFolder.newFile 创建的是 0 字节文件，会被扫描器的 0 字节规则跳过——视频夹具必须带内容。 */
    private fun mediaFile(relPath: String) {
        tmp.newFile(relPath).writeBytes(byteArrayOf(1))
    }

    @Test
    fun `scans nested dirs for whitelisted extensions`() = runBlocking {
        tmp.newFolder("a", "b")
        tmp.newFolder("sub")
        mediaFile("a/b/movie.mkv")
        mediaFile("sub/ep.mp4")
        tmp.newFile("note.txt")
        tmp.newFile("poster.jpg")

        val src = LocalMediaSource("local", "Local", tmp.root)
        val r = src.scan()

        assertEquals(2, r.files.size)
        assertEquals(0, r.skippedCount)
    }

    @Test
    fun `skips hidden and bdmv internals`() = runBlocking {
        tmp.newFolder(".hidden")
        tmp.newFile(".hidden/x.mkv")
        tmp.newFolder("Movie", "BDMV", "STREAM")
        mediaFile("Movie/BDMV/STREAM/00001.m2ts")

        val src = LocalMediaSource("local", "Local", tmp.root)
        val r = src.scan()

        assertTrue(r.files.isEmpty(), "hidden mkv and BDMV internals must not appear")
        assertEquals(1, r.bdmvDirs.size)
        assertEquals("Movie/BDMV", r.bdmvDirs.single().name)
        assertEquals(2, r.skippedCount)
    }

    @Test
    fun `unreadable entry reports hint not crash`() = runBlocking {
        val src = LocalMediaSource("local", "Local", File(tmp.root, "nope"))
        val hints = mutableListOf<ScanUpdate.ErrorHint>()

        val r = src.scan { u -> if (u is ScanUpdate.ErrorHint) hints += u }

        assertTrue(r.files.isEmpty())
        assertEquals(1, hints.size)
    }

    @Test
    fun `progress callback reports found count`() = runBlocking {
        mediaFile("m1.mkv")
        mediaFile("m2.mkv")
        val src = LocalMediaSource("local", "Local", tmp.root)
        val updates = mutableListOf<ScanUpdate>()
        src.scan { updates += it }
        val lastProgress = updates.filterIsInstance<ScanUpdate.Progress>().maxByOrNull { it.scanned }
        assertEquals(2, lastProgress?.found)
    }

    @Test
    fun `system dirs and hidden files skipped`() = runBlocking {
        tmp.newFolder("\$RECYCLE.BIN")
        tmp.newFile("\$RECYCLE.BIN/deleted.mkv")
        tmp.newFolder("System Volume Information")
        tmp.newFile("System Volume Information/x.mkv")
        tmp.newFile(".hidden.mkv")
        mediaFile("ok.mkv")

        val src = LocalMediaSource("local", "Local", tmp.root)
        val r = src.scan()

        assertEquals(listOf("ok.mkv"), r.files.map { it.name })
        assertEquals(3, r.skippedCount)
    }

    @Test
    fun `zero byte files skipped`() = runBlocking {
        tmp.newFile("empty.mkv")

        val src = LocalMediaSource("local", "Local", tmp.root)
        val r = src.scan()

        assertTrue(r.files.isEmpty())
        assertEquals(1, r.skippedCount)
    }
}
