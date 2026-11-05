package com.slogan.tv.library.parser

import com.slogan.tv.library.model.QualityToken

/**
 * 影视文件名解析器（spec §5.4：文件名清洗、年份/季集提取、标记识别）。
 */
object FilenameParser {

    private val VIDEO_EXTENSIONS =
        setOf("mkv", "mp4", "avi", "mov", "flv", "ts", "m2ts", "wmv", "webm", "iso")
    private val YEAR_REGEX = Regex("(?:19|20)\\d{2}")

    fun parse(fileName: String): ParsedFileName {
        val stem = stripExtension(fileName)
        val yearMatch = findYearToken(stem)

        val year = yearMatch?.value?.toInt()
        val titleStem =
            if (yearMatch != null) stem.removeRange(yearMatch.range) else stem

        return ParsedFileName(
            title = basicClean(titleStem),
            year = year,
            season = null,
            episodes = null,
            qualityTokens = emptySet(),
            isTvShow = false,
            usedFallback = false,
        )
    }

    /** 剥离白名单内的视频扩展名；不是视频扩展名则原样返回。 */
    internal fun stripExtension(name: String): String {
        val idx = name.lastIndexOf('.')
        if (idx <= 0) return name
        val ext = name.substring(idx + 1).lowercase()
        return if (ext in VIDEO_EXTENSIONS) name.substring(0, idx) else name
    }

    /** 取最后一个"位于 token 边界"的年份（独立 token 或括号包裹）。 */
    private fun findYearToken(stem: String): MatchResult? =
        YEAR_REGEX.findAll(stem)
            .filter { m -> atTokenBoundary(stem, m.range.first, m.range.last) }
            .lastOrNull()

    private fun atTokenBoundary(s: String, start: Int, end: Int): Boolean {
        val before = if (start == 0) null else s[start - 1]
        val after = if (end == s.length - 1) null else s[end + 1]
        val beforeOk = before == null || before in "._ -[(("
        val afterOk = after == null || after in "._ -)])"
        return beforeOk && afterOk
    }

    /** 初步清洗：分隔符归一为空格、折叠多空格、修剪首尾（发行标签剥离见 Task 3）。 */
    private fun basicClean(raw: String): String =
        raw.replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('(', ')', '（', '）')
            .trim()
}
