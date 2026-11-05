package com.slogan.tv.library.source

import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.parser.FilenameParser
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** 本地目录媒体源：walkFileTree，跳过隐藏目录与 BDMV 整棵子树。 */
class LocalMediaSource(
    override val id: String,
    override val name: String,
    private val rootPath: Path,
) : MediaSource {

    override suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult {
        val files = mutableListOf<MediaFile>()
        val bdmvDirs = mutableListOf<MediaFile>()
        var skipped = 0
        var scanned = 0

        val visitor = object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir == rootPath) return FileVisitResult.CONTINUE
                val dirName = dir.fileName?.toString() ?: return FileVisitResult.CONTINUE
                return when {
                    dirName.startsWith(".") -> {
                        skipped++
                        FileVisitResult.SKIP_SUBTREE
                    }
                    dirName.equals("BDMV", ignoreCase = true) -> {
                        val parent = dir.parent ?: dir
                        bdmvDirs += MediaFile(
                            sourceId = id,
                            path = parent.toString(),
                            name = (parent.fileName?.toString() ?: "") + "/BDMV",
                            sizeBytes = 0L,
                            lastModifiedEpochMs = attrs.lastModifiedTime().toMillis(),
                        )
                        skipped++
                        FileVisitResult.SKIP_SUBTREE
                    }
                    else -> FileVisitResult.CONTINUE
                }
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                scanned++
                val fileName = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                val ext = fileName.substringAfterLast('.', "").lowercase()
                if (ext in FilenameParser.VIDEO_EXTENSIONS) {
                    files += MediaFile(
                        sourceId = id,
                        path = file.toString(),
                        name = fileName,
                        sizeBytes = attrs.size(),
                        lastModifiedEpochMs = attrs.lastModifiedTime().toMillis(),
                    )
                }
                if (scanned % PROGRESS_EVERY == 0) {
                    callback(ScanUpdate.Progress(scanned, files.size))
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                callback(ScanUpdate.ErrorHint(file.toString(), exc.message ?: "unreadable"))
                return FileVisitResult.CONTINUE
            }
        }

        try {
            Files.walkFileTree(rootPath, visitor)
        } catch (e: IOException) {
            callback(ScanUpdate.ErrorHint(rootPath.toString(), e.message ?: "scan failed"))
        } catch (e: java.io.UncheckedIOException) {
            callback(ScanUpdate.ErrorHint(rootPath.toString(), e.message ?: "scan failed"))
        }
        callback(ScanUpdate.Progress(scanned, files.size))

        return ScanResult(files = files, bdmvDirs = bdmvDirs, skippedCount = skipped)
    }

    private companion object {
        const val PROGRESS_EVERY = 100
    }
}
