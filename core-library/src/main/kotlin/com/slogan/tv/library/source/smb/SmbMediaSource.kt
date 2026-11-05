package com.slogan.tv.library.source.smb

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.source.MediaSource
import com.slogan.tv.library.source.ScanRules
import com.slogan.tv.library.source.ScanResult
import com.slogan.tv.library.source.ScanUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * SMB 媒体源（spec §5.4 SMB 为主力源）：遍历 shareRoot，
 * 过滤规则与 LocalMediaSource 完全一致（ScanRules + BDMV 占位）。
 * 显式栈迭代；目录级失败 → ErrorHint + 跳过该子树继续；协作取消。
 */
class SmbMediaSource(
    override val id: String,
    override val name: String,
    private val shareRoot: String,
    private val clientFactory: suspend () -> SmbFileClient,
) : MediaSource {

    override suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            val files = mutableListOf<MediaFile>()
            val bdmvDirs = mutableListOf<MediaFile>()
            var skipped = 0
            var scanned = 0
            var fatalError: String? = null

            val client = try {
                clientFactory()
            } catch (e: SmbCommunicationException) {
                val msg = describe(e)
                callback(ScanUpdate.ErrorHint(shareRoot, msg))
                return@withContext ScanResult(files, bdmvDirs, skipped, fatalError = msg)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                callback(ScanUpdate.ErrorHint(shareRoot, msg))
                return@withContext ScanResult(files, bdmvDirs, skipped, fatalError = msg)
            }

            try {
                val pending = ArrayDeque<String>()
                pending.addLast(shareRoot)
                while (pending.isNotEmpty() && fatalError == null) {
                    coroutineContext.ensureActive()
                    val dir = pending.removeLast()
                    val entries = try {
                        client.list(dir)
                    } catch (e: SmbCommunicationException) {
                        val msg = describe(e)
                        callback(ScanUpdate.ErrorHint(dir, msg))
                        if (dir == shareRoot) fatalError = msg
                        continue // 该子树不可达，跳过（栈空则自然结束）
                    } catch (e: Exception) {
                        // 兜底：任何意外异常（评审 I3）→ 提示并跳过，绝不冲出 scan
                        val msg = e.message ?: e.javaClass.simpleName
                        callback(ScanUpdate.ErrorHint(dir, msg))
                        if (dir == shareRoot) fatalError = msg
                        continue
                    }
                    for (entry in entries) {
                        val childPath = if (dir.isEmpty()) entry.name else "$dir/${entry.name}"
                        if (!entry.isDirectory) scanned++ // 与 Local 扫描器口径一致：只计文件
                        when {
                            entry.isDirectory && entry.name.equals("BDMV", ignoreCase = true) -> {
                                val parentName = dir.substringAfterLast('/')
                                bdmvDirs += MediaFile(
                                    sourceId = id,
                                    path = dir,
                                    name = "$parentName/BDMV",
                                    sizeBytes = 0L,
                                    lastModifiedEpochMs = entry.lastModifiedEpochMs,
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
                                lastModifiedEpochMs = entry.lastModifiedEpochMs,
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

    /** 错误分类 → 用户可读文案（spec：SMB1 明确不支持、错误不静默）。 */
    internal fun describe(e: SmbCommunicationException): String = when (e.kind) {
        SmbCommunicationException.Kind.AUTH -> "认证失败，请检查账号密码"
        SmbCommunicationException.Kind.TIMEOUT -> "连接超时，请检查主机地址与网络"
        SmbCommunicationException.Kind.PROTOCOL ->
            "协议协商失败（该设备可能仅支持 SMB1，slogan 暂不支持 SMB1）"
        SmbCommunicationException.Kind.IO -> e.message ?: "IO 错误"
    }

    private companion object {
        const val PROGRESS_EVERY = 100
    }
}
