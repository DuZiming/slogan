package com.slogan.tv.library.repo

import com.slogan.tv.library.group.LibraryGrouper
import com.slogan.tv.library.model.LibraryEntry
import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.ScanResult
import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * 媒体库仓库：并行扫描全部源，归组为快照并缓存（spec §6：扫描在可取消的后台服务执行）。
 * 持久化（可选注入 store）：
 * - refresh() 各源独立捕获异常（失败源 → ErrorHint + 清除该源数据，健康源照常入库）；
 * - restore() 不触发扫描，从持久层装配最近快照——NAS 离线启动也能看到库（spec §5.4 离线可用）。
 */
class LibraryRepository(
    private val sources: List<MediaSource>,
    private val store: LibraryStore? = null,
) {

    private var cached: LibrarySnapshot? = null

    suspend fun refresh(callback: (ScanUpdate) -> Unit = {}): LibrarySnapshot {
        val results: List<Pair<MediaSource, ScanResult?>> = coroutineScope {
            sources.map { source ->
                async {
                    try {
                        source to source.scan(callback)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        callback(ScanUpdate.ErrorHint(source.id, e.message ?: "扫描失败：${source.name}"))
                        source to null
                    }
                }
            }.map { it.await() }
        }

        val healthy = results.mapNotNull { (_, r) -> r }
        val files = healthy.flatMap { it.files }
        val bdmvDirs = healthy.flatMap { it.bdmvDirs }

        store?.let { s ->
            results.forEach { (source, r) ->
                if (r != null) s.saveScanResult(source.id, r.files, r.bdmvDirs)
                else s.saveScanResult(source.id, emptyList(), emptyList()) // 失败源清旧数据，防止"幽灵文件"
            }
        }

        val snapshot = LibrarySnapshot(
            entries = LibraryGrouper.group(files, bdmvDirs),
            generatedAtEpochMs = System.currentTimeMillis(),
        )
        cached = snapshot
        store?.recordSnapshotTime(snapshot.generatedAtEpochMs)
        return snapshot
    }

    /** 从持久层装配最近快照；无持久层或从未扫描过返回 null。不触发任何网络/磁盘扫描。 */
    suspend fun restore(): LibrarySnapshot? {
        val s = store ?: return null
        val generatedAt = s.lastSnapshotTime() ?: return null
        val files: List<MediaFile> = s.loadAllFiles()
        val bdmvDirs: List<MediaFile> = s.loadAllBdmvDirs()
        val snapshot = LibrarySnapshot(
            entries = LibraryGrouper.group(files, bdmvDirs),
            generatedAtEpochMs = generatedAt,
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
