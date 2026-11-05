package com.slogan.tv.library.parser

import com.slogan.tv.library.model.QualityToken

/**
 * 影视文件名解析器（spec §5.4：文件名清洗、年份/季集提取、标记识别）。
 */
object FilenameParser {

    private val VIDEO_EXTENSIONS =
        setOf("mkv", "mp4", "avi", "mov", "flv", "ts", "m2ts", "wmv", "webm", "iso")
    private val YEAR_REGEX = Regex("(?:19|20)\\d{2}")

    /**
     * 发行标签词典（token 边界匹配，大小写不敏感）。
     * 命中第一个标签即认定其后全部为"标签区"（含尾部 -GROUP 组名），
     * 一并丢弃；标题区保持原样——保护 Spider-Man 这类带连字符的真实片名。
     */
    internal val RELEASE_TAG_REGEX = Regex(
        "(?<!\\w)(" +
            listOf(
                // 分辨率/来源
                "(?:2160|1080|720|480)[pi]", "4k", "uhd",
                "web[.\\-]?dl", "webrip", "webdl", "brrip", "bdrip", "hdrip", "hdtv", "dvdrip",
                "blu[.\\-]?ray", "bluray", "bdremux",
                // 编码
                "x264", "x265", "h[.]?26[45]", "hevc", "avc", "xvid", "divx", "av1",
                "10bit", "8bit", "hi10p",
                // 音频
                "aac(?:[.\\d]*)", "flac", "ac3", "eac3", "ddp(?:[.\\d]*)", "dd[+]?(?:[.\\d]*)",
                "truehd", "dts(?:[.\\-]?hd)?(?:[.\\-]?ma)?", "atmos",
                // HDR/规格
                "hdr10[+]", "hdr", "sdr", "dolby[.]?vision", "dovi", "dv", "3d",
                // 版本标记
                "remux", "proper", "repack", "extended", "remastered", "unrated", "imax",
                "complete", "internal", "limited", "multi", "dual", "hybrid", "ws",
            ).joinToString("|") +
            ")(?!\\w)",
        RegexOption.IGNORE_CASE,
    )

    fun parse(fileName: String): ParsedFileName {
        val stem = stripExtension(fileName)
        val yearMatch = findYearToken(stem)

        val year = yearMatch?.value?.toInt()
        val titleStem =
            if (yearMatch != null) stem.removeRange(yearMatch.range) else stem

        return ParsedFileName(
            title = cleanTitle(titleStem),
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

    /** 标题清洗：首个发行标签之前的都算标题区，其后（含 -GROUP）整段丢弃。 */
    private fun cleanTitle(stem: String): String {
        val firstTag = RELEASE_TAG_REGEX.find(stem)
        val titleZone = if (firstTag != null) stem.substring(0, firstTag.range.first) else stem
        return basicClean(titleZone)
    }

    /** 初步清洗：分隔符归一为空格、折叠多空格、修剪首尾。 */
    private fun basicClean(raw: String): String =
        raw.replace('_', ' ')
            .replace('.', ' ')
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('(', ')', '（', '）')
            .trim()
}
