package com.slogan.tv.library.model

import com.slogan.tv.library.parser.ParsedFileName

/** 媒体源里的一个物理文件（本地路径或网络源内路径）。 */
data class MediaFile(
    val sourceId: String,
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
)

/** 媒体库条目：电影平铺；剧集归组为 Series，Episode 嵌在 seasons 内。 */
sealed interface LibraryEntry {

    data class Movie(
        val file: MediaFile,
        val parsed: ParsedFileName,
    ) : LibraryEntry

    data class Series(
        val title: String,
        val seasons: Map<Int, List<Episode>>,
    ) : LibraryEntry

    data class Episode(
        val file: MediaFile,
        val parsed: ParsedFileName,
        val season: Int?,
        val episode: Int,
    ) : LibraryEntry
}
