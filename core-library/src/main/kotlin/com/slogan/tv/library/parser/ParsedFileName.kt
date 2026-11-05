package com.slogan.tv.library.parser

import com.slogan.tv.library.model.QualityToken

/**
 * 文件名解析结果。未解析出的字段为 null / 空集。
 *
 * @param title         清洗后的展示标题
 * @param year          1900..2099 的年份，找不到为 null
 * @param season        剧集季号（1 起）；无季信息为 null
 * @param episodes      集号范围（多集文件为区间）；非剧集为 null
 * @param qualityTokens 画质/音轨标记
 * @param isTvShow      是否识别为剧集
 * @param usedFallback  解析放弃、回退为原始文件名时为 true（供 UI 加"未识别"角标）
 */
data class ParsedFileName(
    val title: String,
    val year: Int?,
    val season: Int?,
    val episodes: IntRange?,
    val qualityTokens: Set<QualityToken>,
    val isTvShow: Boolean,
    val usedFallback: Boolean,
)
