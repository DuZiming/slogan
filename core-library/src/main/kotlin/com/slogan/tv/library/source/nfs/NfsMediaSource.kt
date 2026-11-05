package com.slogan.tv.library.source.nfs

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.ScanResult
import com.slogan.tv.library.source.ScanRules
import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * NFS 媒体源：与 SmbMediaSource 完全同规则（ScanRules + BDMV 占位 + fatalError 语义）。
 * export 为挂载点（如 "/volume1/media"），扫描从 export 根开始。
 */
class NfsMediaSource(
    override val id: String,
    override val name: String,
    private val host: String,
    private val export: String,
    private val clientFactory: suspend () -> NfsFileClient,
) : MediaSource {

    override suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            val files = mutableListOf<MediaFile>()
            val bdmvDirs = mutableListOf<MediaFile>()
            var skipped = 0
            var scanned = 0
            var fatalError: String? = null

            val client = clientFactory()
            try {
                if (!client.connect(host, export, null, null)) {
                    val msg = "NFS 挂载失败：$host$export"
                    callback(ScanUpdate.ErrorHint(export, msg))
                    return@withContext ScanResult(files, bdmvDirs, skipped, fatalError = msg)
                }

                val pending = ArrayDeque<String>()
                pending.addLast(export)
                while (pending.isNotEmpty() && fatalError == null) {
                    coroutineContext.ensureActive()
                    val dir = pending.removeLast()
                    val entries = client.list(dir)
                    if (entries == null) {
                        val msg = "NFS 列目录失败：$dir"
                        callback(ScanUpdate.ErrorHint(dir, msg))
                        if (dir == export) fatalError = msg
                        continue
                    }
                    for (entry in entries) {
                        val childPath = "$dir/${entry.name}"
                        if (!entry.isDirectory) scanned++
                        when {
                            entry.isDirectory && entry.name.equals("BDMV", ignoreCase = true) -> {
                                val parentName = dir.substringAfterLast('/')
                                bdmvDirs += MediaFile(
                                    sourceId = id,
                                    path = dir,
                                    name = "$parentName/BDMV",
                                    sizeBytes = 0L,
                                    lastModifiedEpochMs = entry.mtimeSec * 1000,
                                    relativePath = childPath,
                                )
                                skipped++
                            }
                            entry.isDirectory && ScanRules.isSkippedDir(entry.name) -> skipped++
                            entry.isDirectory -> pending.addLast(childPath)
                            entry.name.startsWith(".") -> skipped++
                            ScanRules.isVideoFile(entry.name) && entry.sizeBytes == 0L -> skipped++
                            ScanRules.isVideoFile(entry.name) -> files += MediaFile(
                                sourceId = id,
                                path = childPath,
                                name = entry.name,
                                sizeBytes = entry.sizeBytes,
                                lastModifiedEpochMs = entry.mtimeSec * 1000,
                                relativePath = childPath,
                            )
                            // 非白名单扩展：静默忽略
                        }
                        if (scanned % PROGRESS_EVERY == 0) {
                            callback(ScanUpdate.Progress(scanned, files.size))
                        }
                    }
                }
            } finally {
                client.close()
            }

            callback(ScanUpdate.Progress(scanned, files.size))
            ScanResult(files = files, bdmvDirs = bdmvDirs, skippedCount = skipped, fatalError = fatalError)
        }

    private companion object {
        const val PROGRESS_EVERY = 100
    }
}
