package com.slogan.tv.library.source

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.parser.FilenameParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * 本地目录媒体源（spec §6：minSdk 23 → 全部使用 java.io.File，禁用 java.nio.file）。
 * 跳过隐藏目录/文件、系统目录（回收站等）、BDMV 整棵子树、0 字节文件。
 * 扫描在 Dispatchers.IO 上执行并协作取消（spec §5.4 可取消的后台扫描）。
 */
class LocalMediaSource(
    override val id: String,
    override val name: String,
    private val root: File,
) : MediaSource {

    override suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult =
        withContext(Dispatchers.IO) {
            val files = mutableListOf<MediaFile>()
            val bdmvDirs = mutableListOf<MediaFile>()
            var skipped = 0
            var scanned = 0

            if (!root.exists()) {
                callback(ScanUpdate.ErrorHint(root.path, "path does not exist"))
                return@withContext ScanResult(files, bdmvDirs, skipped)
            }

            root.walkTopDown()
                .onEnter { dir ->
                    when {
                        dir == root -> true
                        dir.name.equals("BDMV", ignoreCase = true) -> {
                            val parent = dir.parentFile ?: dir
                            bdmvDirs += MediaFile(
                                sourceId = id,
                                path = parent.absolutePath,
                                name = parent.name + "/BDMV",
                                sizeBytes = 0L,
                                lastModifiedEpochMs = dir.lastModified(),
                                relativePath = rel(parent) + "/BDMV",
                            )
                            skipped++
                            false
                        }
                        isSystemOrHiddenDir(dir.name) -> {
                            skipped++
                            false
                        }
                        else -> true
                    }
                }
                .forEach { f ->
                    if (!f.isFile) return@forEach
                    scanned++
                    val ext = f.name.substringAfterLast('.', "").lowercase()
                    when {
                        f.name.startsWith(".") -> skipped++
                        ext in FilenameParser.VIDEO_EXTENSIONS -> {
                            if (f.length() == 0L) {
                                skipped++ // 白名单扩展但 0 字节：残缺下载，跳过
                            } else {
                                files += MediaFile(
                                    sourceId = id,
                                    path = f.absolutePath,
                                    name = f.name,
                                    sizeBytes = f.length(),
                                    lastModifiedEpochMs = f.lastModified(),
                                    relativePath = rel(f),
                                )
                            }
                        }
                        // 非白名单扩展（txt/jpg 等）：静默忽略，不计 skipped
                    }
                    if (scanned % PROGRESS_EVERY == 0) {
                        coroutineContext.ensureActive()
                        callback(ScanUpdate.Progress(scanned, files.size))
                    }
                }

            callback(ScanUpdate.Progress(scanned, files.size))
            ScanResult(files = files, bdmvDirs = bdmvDirs, skippedCount = skipped)
        }

    private fun rel(f: File): String = root.relativeToOrSelf(f).invariantSeparatorsPath

    private fun isSystemOrHiddenDir(name: String): Boolean =
        name.startsWith(".") || name.lowercase() in SYSTEM_DIRS

    private companion object {
        const val PROGRESS_EVERY = 100

        /** Windows/NAS 系统目录（小写比较）：回收站、缩略图、卷信息、丢失簇。 */
        val SYSTEM_DIRS = setOf(
            "\$recycle.bin", "recycler", "recycled",
            "system volume information",
            "#recycle", "#recyclebin", "@eadir", "@recycle",
            "lost+found",
        )
    }
}
