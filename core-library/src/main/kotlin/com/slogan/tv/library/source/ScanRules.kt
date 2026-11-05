package com.slogan.tv.library.source

import com.slogan.tv.library.parser.FilenameParser

/**
 * 扫描过滤规则（本地与 SMB 源共享，spec §5.4）：
 * 系统目录黑名单、点前缀隐藏项、0 字节白名单文件、视频扩展名白名单。
 */
object ScanRules {

    /** Windows/NAS 系统目录（小写比较）：回收站、缩略图、卷信息、丢失簇。 */
    internal val SYSTEM_DIRS = setOf(
        "\$recycle.bin", "recycler", "recycled",
        "system volume information",
        "#recycle", "#recyclebin", "@eadir", "@recycle",
        "lost+found",
    )

    fun isSkippedDir(name: String): Boolean =
        name.startsWith(".") || name.lowercase() in SYSTEM_DIRS

    fun isHiddenOrEmptyFile(name: String, sizeBytes: Long): Boolean =
        name.startsWith(".") || sizeBytes == 0L

    fun isVideoFile(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in FilenameParser.VIDEO_EXTENSIONS
    }
}
