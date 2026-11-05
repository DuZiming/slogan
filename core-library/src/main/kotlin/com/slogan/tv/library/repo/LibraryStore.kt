package com.slogan.tv.library.repo

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSourceConfig
import com.slogan.tv.library.source.SourceType

/**
 * 媒体库持久化接口（spec §5.4"已刮削媒体离线完全可用"的存储层）。
 * 驱动由构造方注入：JVM 用 JdbcSqliteDriver，Android 侧计划 4 注入 AndroidDriver。
 */
interface LibraryStore {
    suspend fun saveSources(configs: List<MediaSourceConfig>)

    suspend fun loadSources(): List<MediaSourceConfig>

    /** 按源全量替换该源的扫描结果（重复扫描不产生重复行）。 */
    suspend fun saveScanResult(sourceId: String, files: List<MediaFile>, bdmvDirs: List<MediaFile>)

    suspend fun loadAllFiles(): List<MediaFile>

    suspend fun loadAllBdmvDirs(): List<MediaFile>

    suspend fun recordSnapshotTime(epochMs: Long)

    suspend fun lastSnapshotTime(): Long?
}

/** 内存实现：默认兜底与测试用。 */
class InMemoryLibraryStore : LibraryStore {
    private var sources: List<MediaSourceConfig> = emptyList()
    private val filesBySource = LinkedHashMap<String, Pair<List<MediaFile>, List<MediaFile>>>()
    private var snapshotTime: Long? = null

    override suspend fun saveSources(configs: List<MediaSourceConfig>) {
        sources = configs
        // 整体替换语义：移除源的媒体行级联清除（与 SqlDelight 实现一致）
        val ids = configs.map { it.id }.toSet()
        filesBySource.keys.retainAll(ids)
    }

    override suspend fun loadSources(): List<MediaSourceConfig> = sources

    override suspend fun saveScanResult(sourceId: String, files: List<MediaFile>, bdmvDirs: List<MediaFile>) {
        filesBySource[sourceId] = files to bdmvDirs
    }

    override suspend fun loadAllFiles(): List<MediaFile> = filesBySource.values.flatMap { it.first }

    override suspend fun loadAllBdmvDirs(): List<MediaFile> = filesBySource.values.flatMap { it.second }

    override suspend fun recordSnapshotTime(epochMs: Long) {
        snapshotTime = epochMs
    }

    override suspend fun lastSnapshotTime(): Long? = snapshotTime
}

/** SQLDelight 实现：真 SQLite 持久化。JDBC 为阻塞 IO，统一派发到 Dispatchers.IO（评审 I5）。 */
class SqlDelightLibraryStore(
    private val db: com.slogan.tv.library.db.LibraryDatabase,
) : LibraryStore {

    override suspend fun saveSources(configs: List<MediaSourceConfig>) = withContextIO {
        db.sourcesQueries.transaction {
            configs.forEach { c ->
                db.sourcesQueries.insertSource(
                    id = c.id,
                    name = c.name,
                    type = c.type.name,
                    host = c.host,
                    port = c.port?.toLong(),
                    share_root = c.shareRoot ?: "",
                    username = c.username,
                    domain = c.domain,
                )
            }
            // 整体替换语义（评审 I4）：移除不再出现的源，并级联清其媒体行
            db.sourcesQueries.deleteSourcesNotIn(configs.map { it.id })
            db.mediaFilesQueries.deleteOrphanFiles()
        }
    }

    override suspend fun loadSources(): List<MediaSourceConfig> = withContextIO {
        db.sourcesQueries.selectAllSources().executeAsList().map { row ->
            MediaSourceConfig(
                id = row.id,
                name = row.name,
                type = runCatching { SourceType.valueOf(row.type) }.getOrDefault(SourceType.SMB),
                host = row.host,
                port = row.port?.toInt(),
                shareRoot = row.share_root,
                username = row.username,
                domain = row.domain,
            )
        }
    }

    override suspend fun saveScanResult(sourceId: String, files: List<MediaFile>, bdmvDirs: List<MediaFile>) =
        withContextIO {
            db.mediaFilesQueries.transaction {
                db.mediaFilesQueries.deleteBySource(sourceId)
                files.forEach { f -> insert(sourceId, f, isBdmv = false) }
                bdmvDirs.forEach { f -> insert(sourceId, f, isBdmv = true) }
            }
        }

    private fun insert(sourceId: String, f: MediaFile, isBdmv: Boolean) {
        db.mediaFilesQueries.insertFile(
            source_id = sourceId,
            path = f.path,
            name = f.name,
            size_bytes = f.sizeBytes,
            mtime = f.lastModifiedEpochMs,
            relative_path = f.relativePath,
            is_bdmv = if (isBdmv) 1L else 0L,
        )
    }

    override suspend fun loadAllFiles(): List<MediaFile> = withContextIO {
        db.mediaFilesQueries.selectAllFiles().executeAsList().map { it.toMediaFile() }
    }

    override suspend fun loadAllBdmvDirs(): List<MediaFile> = withContextIO {
        db.mediaFilesQueries.selectAllBdmv().executeAsList().map { it.toMediaFile() }
    }

    private fun com.slogan.tv.library.db.Media_files.toMediaFile() = MediaFile(
        sourceId = source_id,
        path = path,
        name = name,
        sizeBytes = size_bytes,
        lastModifiedEpochMs = mtime,
        relativePath = relative_path,
    )

    override suspend fun recordSnapshotTime(epochMs: Long) = withContextIO {
        db.snapshotQueries.insertSnapshot(epochMs)
    }

    override suspend fun lastSnapshotTime(): Long? = withContextIO {
        db.snapshotQueries.lastSnapshotTime().executeAsOneOrNull()
    }

    private suspend fun <T> withContextIO(block: () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }
}
