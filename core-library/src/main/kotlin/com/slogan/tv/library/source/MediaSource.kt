package com.slogan.tv.library.source

import com.slogan.tv.library.model.MediaFile

/**
 * 媒体源 SPI（spec §6 架构要点）：本地目录 / SMB / NFS（后续计划）统一抽象。
 */
interface MediaSource {
    val id: String
    val name: String

    /**
     * 扫描源；callback 汇报进度与错误提示，永不抛异常（错误走 ErrorHint）。
     * 注意：源可能并行扫描（见 LibraryRepository），callback 可能从不同线程并发调用，需线程安全。
     */
    suspend fun scan(callback: (ScanUpdate) -> Unit = {}): ScanResult
}

/** 扫描结果：白名单文件 + BDMV 原盘占位（name 形如 "影片文件夹名/BDMV"）+ 跳过计数。 */
data class ScanResult(
    val files: List<MediaFile>,
    val bdmvDirs: List<MediaFile>,
    val skippedCount: Int,
    /** 连接级失败（认证/超时/根目录不可达）时非空：结果不代表库的真实状态，持久层应保留 last-good。 */
    val fatalError: String? = null,
)

sealed interface ScanUpdate {
    data class Progress(val scanned: Int, val found: Int) : ScanUpdate
    data class ErrorHint(val path: String, val message: String) : ScanUpdate
}
