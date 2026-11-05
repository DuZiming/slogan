package com.slogan.tv.library.repo

import com.slogan.tv.library.model.LibraryEntry
import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.ScanResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRepositoryTest {

    private class FakeSource(
        override val id: String,
        override val name: String,
        private val result: ScanResult,
    ) : MediaSource {
        var scanCount = 0
            private set

        override suspend fun scan(callback: (com.slogan.tv.library.source.ScanUpdate) -> Unit): ScanResult {
            scanCount++
            return result
        }
    }

    private fun mf(name: String, mtime: Long) =
        MediaFile("local", "/media/$name", name, sizeBytes = 1L, lastModifiedEpochMs = mtime)

    @Test
    fun `refresh aggregates sources into snapshot`() = runBlocking {
        val s1 = FakeSource(
            "s1", "One",
            ScanResult(
                files = listOf(mf("MovieA.2020.mkv", 100), mf("三体.S01E01.mkv", 100)),
                bdmvDirs = emptyList(), skippedCount = 0,
            ),
        )
        val s2 = FakeSource(
            "s2", "Two",
            ScanResult(
                files = listOf(mf("MovieB.2021.mkv", 300), mf("流浪地球.S01E01.mkv", 200)),
                bdmvDirs = emptyList(), skippedCount = 0,
            ),
        )
        val repo = LibraryRepository(listOf(s1, s2))

        val snap = repo.refresh()

        assertEquals(2, snap.movies().size)
        assertEquals(2, snap.series().size)
        val recent = snap.recentlyAdded(1)
        assertEquals(1, recent.size)
        assertEquals("MovieB", (recent.single() as LibraryEntry.Movie).parsed.title)
    }

    @Test
    fun `snapshot cached until refresh`() = runBlocking {
        val s = FakeSource(
            "s1", "One",
            ScanResult(files = listOf(mf("MovieA.2020.mkv", 1)), bdmvDirs = emptyList(), skippedCount = 0),
        )
        val repo = LibraryRepository(listOf(s))

        repo.refresh()
        repo.snapshot()
        repo.snapshot()

        assertEquals(1, s.scanCount)
    }
}
