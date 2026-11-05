package com.slogan.tv.library.repo

import com.slogan.tv.library.group.LibraryGrouper
import com.slogan.tv.library.model.LibraryEntry
import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 媒体库仓库：并行扫描全部源，归组为快照并缓存（spec §6：扫描在可取消的后台服务执行）。
 */
class LibraryRepository(private val sources: List<MediaSource>) {

    private var cached: LibrarySnapshot? = null

    suspend fun refresh(callback: (ScanUpdate) -> Unit = {}): LibrarySnapshot {
        val results = coroutineScope {
            sources.map { source -> async { source.scan(callback) } }.map { it.await() }
        }
        val files = results.flatMap { it.files }
        val bdmvDirs = results.flatMap { it.bdmvDirs }

        val snapshot = LibrarySnapshot(
            entries = LibraryGrouper.group(files, bdmvDirs),
            generatedAtEpochMs = System.currentTimeMillis(),
        )
        cached = snapshot
        return snapshot
    }

    fun snapshot(): LibrarySnapshot? = cached
}

data class LibrarySnapshot(
    val entries: List<LibraryEntry>,
    val generatedAtEpochMs: Long,
) {
    fun movies(): List<LibraryEntry.Movie> = entries.filterIsInstance<LibraryEntry.Movie>()

    fun series(): List<LibraryEntry.Series> = entries.filterIsInstance<LibraryEntry.Series>()

    /** 按最近修改时间降序（剧集取其最新一集的文件时间）。 */
    fun recentlyAdded(n: Int): List<LibraryEntry> =
        entries
            .sortedByDescending { entry ->
                when (entry) {
                    is LibraryEntry.Movie -> entry.file.lastModifiedEpochMs
                    is LibraryEntry.Series ->
                        entry.seasons.values.flatten().maxOf { it.file.lastModifiedEpochMs }
                    is LibraryEntry.Episode -> entry.file.lastModifiedEpochMs
                }
            }
            .take(n)
}
