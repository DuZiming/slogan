package com.slogan.tv.library.parser

import com.slogan.tv.library.model.QualityToken

/**
 * 影视文件名解析器（spec §5.4：文件名清洗、年份/季集提取、标记识别）。
 */
object FilenameParser {

    internal val VIDEO_EXTENSIONS =
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

        // 季集提取优先于年份：S01E05 的数字不能被当年份
        val episode = findEpisodeInfo(stem)
        val stemNoEp = if (episode != null) stem.removeRange(episode.fragmentRange) else stem
        val yearMatch = findYearToken(stemNoEp)

        val year = yearMatch?.value?.toInt()
        val titleStem =
            if (yearMatch != null) stemNoEp.removeRange(yearMatch.range) else stemNoEp

        // 回退契约：清洗后为空 / 纯符号 / 纯数字 → 用原始文件名，标记未识别
        val cleaned = cleanTitle(titleStem)
        val readable = cleaned.any { it.isLetter() }
        val (title, usedFallback) =
            if (readable) cleaned to false else stem to true

        return ParsedFileName(
            title = title,
            year = year,
            season = episode?.season,
            episodes = episode?.episodes,
            qualityTokens = detectQualityTokens(stem),
            isTvShow = episode != null,
            usedFallback = usedFallback,
        )
    }

    internal data class EpisodeInfo(
        val season: Int?,
        val episodes: IntRange,
        val fragmentRange: IntRange,
    )

    /** 季集模式（按优先级）：S02E01-E03 / S01E05E06E07 / E05-E06 / E05E06E07 / 中文季集（含范围、无季、期号）。 */
    private fun findEpisodeInfo(stem: String): EpisodeInfo? {
        SE_RANGE.find(stem)?.let { m ->
            val season = m.groupValues[1].toInt()
            val start = m.groupValues[2].toInt()
            val end = m.groupValues[3].toInt()
            return EpisodeInfo(season, minOf(start, end)..maxOf(start, end), m.range)
        }
        SE_COMBINED.find(stem)?.let { m ->
            val season = m.groupValues[1].toInt()
            val start = m.groupValues[2].toInt()
            val end = m.groupValues[3].toIntOrNull() ?: start
            return EpisodeInfo(season, minOf(start, end)..maxOf(start, end), m.range)
        }
        E_RANGE.find(stem)?.let { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toInt()
            return EpisodeInfo(null, minOf(start, end)..maxOf(start, end), m.range)
        }
        E_COMBINED.find(stem)?.let { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toIntOrNull() ?: start
            return EpisodeInfo(null, minOf(start, end)..maxOf(start, end), m.range)
        }
        CN_SEASON_RANGE.find(stem)?.let { m ->
            val season = m.groupValues[1].toInt()
            val start = m.groupValues[2].toInt()
            val end = m.groupValues[3].toInt()
            return EpisodeInfo(season, minOf(start, end)..maxOf(start, end), m.range)
        }
        CN_SEASON_EPISODE.find(stem)?.let { m ->
            val season = m.groupValues[1].toInt()
            val ep = m.groupValues[2].toInt()
            return EpisodeInfo(season, ep..ep, m.range)
        }
        CN_RANGE.find(stem)?.let { m ->
            val start = m.groupValues[1].toInt()
            val end = m.groupValues[2].toInt()
            return EpisodeInfo(null, minOf(start, end)..maxOf(start, end), m.range)
        }
        CN_LONE.find(stem)?.let { m ->
            val ep = m.groupValues[1].toInt()
            return EpisodeInfo(null, ep..ep, m.range)
        }
        return null
    }

    private val SE_RANGE =
        Regex("(?<!\\w)[sS](\\d{1,2})[eE](\\d{1,3})\\s*-\\s*(?:[sS]\\d{1,2})?[eE](\\d{1,3})(?!\\w)")
    private val SE_COMBINED =
        Regex("(?<!\\w)[sS](\\d{1,2})[eE](\\d{1,3})(?:[eE](\\d{1,3}))*(?!\\w)")
    private val E_RANGE =
        Regex("(?<!\\w)[eE](\\d{1,3})\\s*-\\s*[eE](\\d{1,3})(?!\\w)")
    private val E_COMBINED =
        Regex("(?<!\\w)[eE](\\d{1,3})(?:[eE](\\d{1,3}))*(?!\\w)")
    private val CN_SEASON_RANGE =
        Regex("第(\\d{1,2})季.*?第(\\d{1,3})\\s*[-–~至]\\s*(\\d{1,3})[集话話期]")
    private val CN_SEASON_EPISODE =
        Regex("第(\\d{1,2})季.*?第(\\d{1,3})[集话話]")
    private val CN_RANGE =
        Regex("第(\\d{1,3})\\s*[-–~至]\\s*(\\d{1,3})[集话話期]")
    private val CN_LONE =
        Regex("第(\\d{1,3})[集期]")

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

    /** 画质/音轨 Token 识别规则：小写化后按 token 边界匹配，独立于标题清洗。 */
    private val TOKEN_RULES: List<Pair<Regex, QualityToken>> = listOf(
        "(?<!\\w)(?:2160[pi]|4k|uhd)(?!\\w)" to QualityToken.FOUR_K,
        "(?<!\\w)hdr10[+](?!\\w)|(?<!\\w)hdr10plus(?!\\w)" to QualityToken.HDR10_PLUS,
        "(?<!\\w)hdr10?(?![+p])(?!\\w)" to QualityToken.HDR10,
        "(?<!\\w)(?:dovi|dolby[.]?vision|dv)(?!\\w)" to QualityToken.DOLBY_VISION,
        "(?<!\\w)remux(?!\\w)" to QualityToken.REMUX,
        "(?<!\\w)blu[.]?ray(?!\\w)" to QualityToken.BLURAY_DISC,
        "(?<!\\w)truehd(?!\\w)" to QualityToken.TRUEHD,
        "(?<!\\w)dts(?:[.\\-]?hd)?[.\\-]?ma(?!\\w)" to QualityToken.DTS_HD_MA,
        "(?<!\\w)dts(?![.\\-]?(?:hd|ma))(?![.\\-]hd)(?!\\w)" to QualityToken.DTS,
        "(?<!\\w)(?:eac3|ddp(?:[.\\d]*)|dd[+](?:[.\\d]*))(?!\\w)" to QualityToken.EAC3,
        "(?<!\\w)ac3(?!\\w)" to QualityToken.AC3,
        "(?<!\\w)aac(?:[.\\d]+)?(?!\\w)" to QualityToken.AAC,
        "(?<!\\w)atmos(?!\\w)" to QualityToken.ATMOS,
    ).map { (pattern, token) -> Regex(pattern) to token }

    internal fun detectQualityTokens(stem: String): Set<QualityToken> =
        TOKEN_RULES.mapNotNull { (pattern, token) ->
            if (pattern.containsMatchIn(stem.lowercase())) token else null
        }.toSet()

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
