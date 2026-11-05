package com.slogan.tv.library.group

import com.slogan.tv.library.model.LibraryEntry
import com.slogan.tv.library.model.MediaFile
import com.slogan.tv.library.model.QualityToken
import com.slogan.tv.library.parser.FilenameParser
import com.slogan.tv.library.parser.ParsedFileName

/**
 * 归组器（spec §5.4）：电影永不合并（同名不同版本各自成条目）；
 * 剧集按 title.lowercase().trim() 归组，season null 归入 0 季；
 * 集内按集号升序、同集多文件按文件名排序。
 */
object LibraryGrouper {

    fun group(
        files: List<MediaFile>,
        parse: (String) -> ParsedFileName = FilenameParser::parse,
    ): List<LibraryEntry> {
        val movies = mutableListOf<LibraryEntry.Movie>()
        val series = LinkedHashMap<String, Pair<String, MutableMap<Int, MutableList<LibraryEntry.Episode>>>>()

        for (file in files) {
            val parsed = parse(file.name)
            val episodes = parsed.episodes
            if (!parsed.isTvShow || episodes == null) {
                movies += LibraryEntry.Movie(file, parsed)
                continue
            }
            val entry = series.getOrPut(seriesKey(parsed, file)) {
                displayTitle(parsed, file) to mutableMapOf()
            }
            val seasons = entry.second
            for (ep in episodes) {
                seasons.getOrPut(parsed.season ?: 0) { mutableListOf() }
                    .add(LibraryEntry.Episode(file, parsed, parsed.season, ep))
            }
        }

        val seriesEntries = series.map { (_, value) ->
            val (title, seasons) = value
            LibraryEntry.Series(
                title = title,
                seasons = seasons.toSortedMap().mapValues { (_, eps) ->
                    eps.sortedWith(compareBy({ it.episode }, { it.file.name }))
                },
            )
        }
        return movies + seriesEntries
    }

    private val SEASON_DIR_PATTERN =
        Regex("""(?i)^(season[ _.\-]?\d{1,2}|s\d{1,2}|第\d{1,2}季|\d{1,2})$""")

    /**
     * Review I5：文件名解析失败（usedFallback）时，用相对路径里的真实文件夹名当剧集标题——
     * "绝命毒师/Season 1/S01E01.mkv" 不再裂成 N 个独集条目；季目录（Season 1/S01/第2季…）不算标题。
     */
    internal fun rescueSeriesTitle(file: MediaFile, parsed: ParsedFileName): String? {
        if (!parsed.usedFallback) return null
        val rel = file.relativePath ?: return null
        for (dir in rel.split('/').dropLast(1).asReversed()) {
            if (SEASON_DIR_PATTERN.matches(dir)) continue
            if (dir.any { it.isLetter() }) return dir
        }
        return null
    }

    private fun displayTitle(parsed: ParsedFileName, file: MediaFile): String =
        rescueSeriesTitle(file, parsed) ?: parsed.title

    private fun seriesKey(parsed: ParsedFileName, file: MediaFile): String =
        displayTitle(parsed, file).lowercase().trim()

    /**
     * 重载：BDMV 原盘文件夹（bdmvDirs 的 name 形如 "让子弹飞.2010/BDMV"）识别为
     * 单个 Movie，强制带 BLURAY_DISC 标记；STREAM 内部文件由扫描器不递归（Task 9）。
     */
    fun group(files: List<MediaFile>, bdmvDirs: List<MediaFile>): List<LibraryEntry> {
        val bdmvMovies = bdmvDirs.map { dir ->
            val folderName = dir.name.removeSuffix("/BDMV").removeSuffix("\\BDMV")
            val parsed = FilenameParser.parse(folderName)
            LibraryEntry.Movie(
                file = dir,
                parsed = parsed.copy(qualityTokens = parsed.qualityTokens + QualityToken.BLURAY_DISC),
            )
        }
        return group(files) + bdmvMovies
    }
}
