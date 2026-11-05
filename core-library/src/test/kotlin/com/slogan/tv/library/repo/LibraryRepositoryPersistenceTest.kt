package com.slogan.tv.library.repo

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.ScanResult
import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LibraryRepositoryPersistenceTest {

    private class FailingSource(
        override val id: String,
        override val name: String,
    ) : MediaSource {
        override suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult =
            throw java.io.IOException("nas offline")
    }

    private class StaticSource(
        override val id: String,
        override val name: String,
        private val fileName: String,
    ) : MediaSource {
        override suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult = ScanResult(
            files = listOf(MediaFile(id, "/$fileName", fileName, 1L, 2L, relativePath = fileName)),
            bdmvDirs = emptyList(),
            skippedCount = 0,
        )
    }

    @Test
    fun `healthy sources survive a failing source`() = runBlocking<Unit> {
        val repo = LibraryRepository(
            sources = listOf(
                FailingSource("nas", "NAS"),
                StaticSource("usb", "U盘", "ok.mkv"),
            ),
            store = InMemoryLibraryStore(),
        )
        val hints = mutableListOf<ScanUpdate.ErrorHint>()

        val snap = repo.refresh { u -> if (u is ScanUpdate.ErrorHint) hints += u }

        assertEquals(1, hints.size)
        assertEquals("nas", hints.single().path)
        assertEquals(listOf("ok.mkv"), snap.movies().map { it.file.name })
    }

    @Test
    fun `restore returns persisted snapshot without scanning`() = runBlocking<Unit> {
        val store = InMemoryLibraryStore()
        LibraryRepository(listOf(StaticSource("usb", "U盘", "a.mkv")), store).refresh()
        store.lastSnapshotTime() // 确认已记录

        // 换一个“会失败”的源集合模拟 NAS 离线启动：restore 不触发任何 scan
        val repo = LibraryRepository(listOf(FailingSource("usb", "U盘")), store)
        val restored = repo.restore()

        assertNotNull(restored)
        assertEquals(listOf("a.mkv"), restored.movies().map { it.file.name })
        assertNotNull(restored.generatedAtEpochMs)
    }

    @Test
    fun `restore with empty store returns null`() = runBlocking<Unit> {
        val repo = LibraryRepository(emptyList(), InMemoryLibraryStore())
        assertNull(repo.restore())
    }

    @Test
    fun `store null keeps plan-1 behavior`() = runBlocking<Unit> {
        val repo = LibraryRepository(listOf(StaticSource("usb", "U盘", "a.mkv")))
        val snap = repo.refresh()
        assertEquals(1, snap.movies().size)
        assertEquals(snap, repo.snapshot())
    }
}
