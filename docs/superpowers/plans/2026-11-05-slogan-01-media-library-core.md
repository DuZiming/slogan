# Slogan 计划 1/6：工程骨架 + 媒体库核心 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立 Android 工程骨架，并交付纯 JVM 的媒体库核心：文件名解析、画质/音轨 Token 识别、剧集归组、BDMV 识别、本地目录扫描、库组装——为后续 SMB/NFS 源、播放器、UI 提供全部数据接口。

**Architecture:** 双模块：`:app`（Android/Compose TV 空壳，只验证可构建安装）+ `:core-library`（纯 JVM Kotlin，全部业务逻辑，TDD）。媒体库核心以 `MediaSource` SPI 屏蔽"本地/SMB/NFS"差异，本计划只实现 LocalMediaSource；`ProbeClient` SPI 屏蔽 FFprobe，本计划只定义接口（实现随播放内核计划引入）。

**Tech Stack:** Kotlin 2.0+（JVM toolchain 17）、Gradle (Kotlin DSL)、AGP 8.7+、JUnit4 + kotlin-test。版本取当前稳定版并记入 `gradle/libs.versions.toml`。

**Spec:** `docs/superpowers/specs/2026-11-05-slogan-tv-player-design.md`（已定稿；本计划实现其中 §5.4 媒体库构建、§6 架构要点的库部分）

## 计划序列（本仓库的完整路线）

1. **本计划** — 工程骨架 + 媒体库核心（纯 JVM）
2. SMB + NFS 媒体源（smbj / libnfs JNI，实现 MediaSource SPI）
3. 播放内核（libmpv 集成、FFprobe 实现 ProbeClient、音频直通与回退）
4. Compose for TV 全部页面（Netflix 规格 UI）
5. 智能预览与截帧（封面链、黑帧检测、预览取景）
6. 卡密授权系统（Ed25519 离线验证 + 解锁门控）+ 发版准备

## Global Constraints

- minSdk = 23（Android 6.0）；明确不支持 Android < 6（spec §6）
- applicationId = `com.slogan.tv`；包根 `com.slogan.tv`
- v1 不发起任何互联网请求（spec §1）；`android.permission.INTERNET` 仅为局域网 socket 预留，`:core-library` 不得依赖 Android 框架
- SMB1 不支持；本计划不涉及网络（spec §6）
- `:core-library` 测试必须纯 JVM 可跑（`./gradlew :core-library:test`），不需要设备/模拟器
- 提交粒度：每个任务至少一次提交，消息用 conventional commits（feat/test/chore）
- 视频扩展名白名单：`mkv, mp4, avi, mov, flv, ts, m2ts, wmv, webm, iso`（iso 视为原盘候选）

## Review Focus（易咬人的输入，测试钉死在对应任务）

1. 中英混合文件名 `沙丘2.2024.2160p.WEB-DL.HDR.mkv` → 标题保留中文"沙丘2"，标签全部剥离不残留 → Task 6
2. 多集合并文件 `Show.S01E05E06.1080p.mkv` / `E05-E06` → 识别为 5..6 两集范围，不裂成两个条目也不丢集 → Task 5
3. 同名不同版本 `X.2009.mkv` 与 `X.2009.2160p.REMUX.mkv` → 电影**永不合并**，各自成条目（剧集才归组） → Task 7
4. BDMV 文件夹 `电影名/BDMV/...` → 识别为一个原盘条目，绝不把 STREAM 里几千个 m2ts 当媒体文件 → Task 8
5. 极端文件名（纯数字 `2023.mp4`、单字符、无扩展名、超长名） → 不崩溃，回退为原始文件名显示（usedFallback=true） → Task 6/9

---

### Task 1: Gradle 工程骨架（app 空壳 + core-library 纯 JVM）

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`
- Create: `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/com/slogan/tv/app/MainActivity.kt`（Leanback 启动页，显示 "Slogan"）
- Create: `core-library/build.gradle.kts`, `core-library/src/main/kotlin/com/slogan/tv/library/.gitkeep`, `core-library/src/test/kotlin/com/slogan/tv/library/SanityTest.kt`

**Interfaces:**
- Produces: Gradle 项目结构；命令 `./gradlew build` 全绿。后续所有任务在此结构上开发。

- [ ] **Step 1: 创建全部构建文件与源码骨架**

版本决策：Kotlin/AGP/Gradle wrapper 取当前稳定版，写入 `gradle/libs.versions.toml`（版本目录统一管理）。`:app` 用 `com.android.application` + activity 依赖；`:core-library` 用 `kotlin("jvm")`，toolchain 17，`testImplementation(kotlin("test"))` + JUnit4 runner。gradle.properties 记 `org.gradle.jvmargs=-Xmx2g`。

- [ ] **Step 2: 写冒烟测试**

```kotlin
class SanityTest {
    @Test fun `module is alive`() { assertEquals(1, 1) }
}
```

- [ ] **Step 3: 验证构建**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL（含 :app:assembleDebug 与 :core-library:test）

- [ ] **Step 4: Commit**

```bash
git add -A && git commit -m "chore: project skeleton (app shell + core-library jvm module)"
```

---

### Task 2: 文件名解析 — 标题与年份

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/parser/ParsedFileName.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/parser/FilenameParser.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/parser/FilenameParserTest.kt`

**Interfaces:**
- Produces: `object FilenameParser { fun parse(fileName: String): ParsedFileName }`
- Produces: `data class ParsedFileName(title: String, year: Int?, season: Int?, episodes: IntRange?, qualityTokens: Set<QualityToken>, isTvShow: Boolean, usedFallback: Boolean)`（字段全集本任务创建，未解析的字段为 null/空集）

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `title and year from dotted name`() {
    val r = FilenameParser.parse("Inception.2010.mkv")
    assertEquals("Inception", r.title); assertEquals(2010, r.year)
}
@Test fun `chinese title untouched`() {
    val r = FilenameParser.parse("让子弹飞.2010.mkv")
    assertEquals("让子弹飞", r.title); assertEquals(2010, r.year)
}
@Test fun `no year leaves null`() {
    assertEquals(null, FilenameParser.parse("Something.mkv").year)
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `./gradlew :core-library:test --tests "com.slogan.tv.library.parser.FilenameParserTest"`
Expected: FAIL（FilenameParser 未定义）

- [ ] **Step 3: 实现 parse**

策略：先用扩展名剥离（最后一个 `.` 后缀在白名单内则去掉）；年份匹配 `(19|20)\d{2}`（独立 token 或括号包裹），从尾部向前找第一个；title 为剩余部分初步清洗（Task 3 深化）。

- [ ] **Step 4: 跑测试确认通过**

Run: 同 Step 2。Expected: PASS

- [ ] **Step 5: Commit** — `feat(parser): title and year extraction`

---

### Task 3: 发行标签剥离与清洗

**Files:**
- Modify: `core-library/src/main/kotlin/com/slogan/tv/library/parser/FilenameParser.kt`
- Test: 同文件追加用例

**Interfaces:**
- Produces: `FilenameParser.RELEASE_TAG_REGEX`（内部常量，供测试锚定行为）

- [ ] **Step 1: 写失败测试（先测用例）**

```kotlin
@Test fun `release tags stripped`() {
    assertEquals("Dune Part Two", FilenameParser.parse("Dune.Part.Two.2024.2160p.WEB-DL.DDP5.1.Atmos.HDR.H.265.mkv").title)
}
@Test fun `release group stripped`() {
    assertEquals("Oppenheimer", FilenameParser.parse("Oppenheimer.2023.2160p.BluRay.REMUX-GROUP1.mkv").title)
}
@Test fun `separators normalized`() {
    assertEquals("My Movie", FilenameParser.parse("My_Movie_2019.mp4").title)
}
```

- [ ] **Step 2: 跑测试确认失败** — Run 同 Task 2。Expected: FAIL（标签残留在 title）

- [ ] **Step 3: 实现**

策略：小写无副作用的标签词典正则（`2160p|1080p|720p|web-?dl|webrip|blu-?ray|bdrip|hdrip|remux|x264|x265|h\.?26[45]|hevc|avc|10bit|8bit|aac\d?\.?\d?|dd[p+]?\d\.?\d?|eac3|ac3|dts(-hd)?(\.ma)?|truehd|atmos|proper|repack|extended|remastered|_complete` 等）；分隔符 `[._]` → 空格；`-GROUP` 尾部组名剥离；多空格折叠；首尾修剪。

- [ ] **Step 4: 跑测试确认通过** — Run 同上。Expected: PASS

- [ ] **Step 5: Commit** — `feat(parser): release tag stripping and separator cleanup`

---

### Task 4: 画质/音轨 Token 识别

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/model/QualityToken.kt`
- Modify: `FilenameParser.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/parser/QualityTokenTest.kt`

**Interfaces:**
- Produces: `enum class QualityToken { FOUR_K, HDR10, HDR10_PLUS, DOLBY_VISION, REMUX, BLURAY_DISC, TRUEHD, DTS_HD_MA, DTS, EAC3, AC3, AAC, ATMOS }`
- Produces: `ParsedFileName.qualityTokens: Set<QualityToken>`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `4k hdr dv atmos detected`() {
    val r = FilenameParser.parse("Movie.2024.2160p.DoVi.HDR10Plus.TrueHD.Atmos.mkv")
    assertEquals(setOf(FOUR_K, DOLBY_VISION, HDR10_PLUS, TRUEHD, ATMOS), r.qualityTokens)
}
@Test fun `remux bluray`() {
    val r = FilenameParser.parse("M.2019.1080p.BluRay.REMUX.DTS-HD.MA.mkv")
    assertEquals(setOf(REMUX, BLURAY_DISC, DTS_HD_MA), r.qualityTokens)
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：Token→正则映射表（`2160p|2160i|4k|uhd`→FOUR_K；`dovi|dolby.?vision|dv\b`→DOLBY_VISION；`hdr10\+`→HDR10_PLUS；`hdr`→HDR10；等）。解析前先把文件名小写化后匹配；Token 结果独立于 title 清洗。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(parser): quality and audio token detection`

---

### Task 5: 剧集模式解析（S/E 与中文季集）

**Files:**
- Modify: `FilenameParser.kt`
- Test: 追加 `EpisodePatternTest` 用例

**Interfaces:**
- Produces: `ParsedFileName.isTvShow / season / episodes` 语义：`S01E05` → season=1, episodes=5..5；`S02E03E04`/`E03-E04` → episodes=3..4；无季有集 `E05` → season=null, episodes=5..5；中文 `第2季第5集` → season=2, episodes=5..5。isTvShow = episodes != null。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `sxe pattern`() {
    val r = FilenameParser.parse("Show.S01E05.1080p.mkv")
    assertTrue(r.isTvShow); assertEquals(1, r.season); assertEquals(5..5, r.episodes)
}
@Test fun `multi episode`() {
    val r = FilenameParser.parse("Show.S01E05E06.1080p.mkv")
    assertEquals(5..6, r.episodes)
    val r2 = FilenameParser.parse("Show.E05-E06.mkv")
    assertEquals(5..6, r2.episodes)
}
@Test fun `chinese season episode`() {
    val r = FilenameParser.parse("三体.第2季第5集.4K.mkv")
    assertTrue(r.isTvShow); assertEquals(2, r.season); assertEquals(5..5, r.episodes); assertEquals("三体", r.title)
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：季集模式优先于年份提取（`S01E05` 的 05 不能被当年份）；正则组：`[sS](\d{1,2})[eE](\d{1,3})(?:[eE](\d{1,3}))*`、`[eE](\d{1,3})\s*-\s*[eE](\d{1,3})`、`[eE](\d{1,3})`、`第(\d{1,2})季.*第(\d{1,3})[集话話]`；匹配到的模式片段从 title 中剔除。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(parser): tv episode patterns incl chinese`

---

### Task 6: 中文保留、混合名与回退策略

**Files:**
- Modify: `FilenameParser.kt`
- Test: 追加 `EdgeCaseTest` 用例（Review Focus #1 #5）

**Interfaces:**
- Produces: 回退契约——解析不出可读标题时（清洗后为空 / 纯符号 / 纯数字），`title = 去扩展名的原始文件名`，`usedFallback = true`，永不抛异常。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `mixed chinese english name`() {
    val r = FilenameParser.parse("沙丘2.2024.2160p.WEB-DL.HDR.mkv")
    assertEquals("沙丘2", r.title); assertEquals(2024, r.year); assertFalse(r.usedFallback)
}
@Test fun `numeric only falls back`() {
    val r = FilenameParser.parse("2023.mp4")
    assertEquals("2023", r.title); assertTrue(r.usedFallback)
}
@Test fun `blank after clean falls back`() {
    val r = FilenameParser.parse("2160p.WEB-DL.mkv")
    assertTrue(r.usedFallback)
}
@Test fun `never throws`() {
    assertDoesNotThrow { FilenameParser.parse("...") ; FilenameParser.parse("第季集.mkv") }
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：清洗后 title 为空或仅数字/符号 → 回退原始 stem；`usedFallback=true` 标记供 UI 加"未识别"角标。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(parser): mixed-name handling and safe fallback`

---

### Task 7: LibraryGrouper — 电影独立、剧集归组

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/model/LibraryEntry.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/group/LibraryGrouper.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/group/LibraryGrouperTest.kt`

**Interfaces:**
- Produces: `sealed interface LibraryEntry { data class Movie(file: MediaFile, parsed: ParsedFileName): LibraryEntry; data class Series(title: String, seasons: Map<Int, List<Episode>>): LibraryEntry; data class Episode(file: MediaFile, parsed: ParsedFileName, season: Int?, episode: Int): LibraryEntry }`（Episode 嵌在 Series.seasons 内，Movie 平铺）
- Produces: `data class MediaFile(sourceId: String, path: String, name: String, sizeBytes: Long, lastModifiedEpochMs: Long)`
- Produces: `object LibraryGrouper { fun group(files: List<MediaFile>, parse: (String) -> ParsedFileName = FilenameParser::parse): List<LibraryEntry> }`
- Consumes: Task 2–6 的 `ParsedFileName`

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `movies never merge`() {   // Review Focus #3
    val e = LibraryGrouper.group(listOf(mf("X.2009.mkv"), mf("X.2009.2160p.REMUX.mkv")))
    assertEquals(2, e.filterIsInstance<LibraryEntry.Movie>().size)
}
@Test fun `episodes grouped by series then season`() {
    val e = LibraryGrouper.group(listOf(mf("三体.S01E01.mkv"), mf("三体.S01E02.mkv"), mf("三体.S02E01.mkv")))
    val s = e.single() as LibraryEntry.Series
    assertEquals(setOf(1, 2), s.seasons.keys)
    assertEquals(2, s.seasons.getValue(1).size)
}
@Test fun `series sorted episodes`() {
    // S01E02 与 S01E01 输入乱序 → seasons[1] 内 episode 升序
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：isTvShow 按 `title.lowercase().trim()` 归组为 Series；season null 时归入 season 0；集内按 episode 升序、同集多文件按 name 排序；其余为 Movie。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(group): library grouper movies vs series`

---

### Task 8: BDMV 原盘识别

**Files:**
- Modify: `core-library/src/main/kotlin/com/slogan/tv/library/group/LibraryGrouper.kt`
- Test: 追加 `BdmvTest` 用例

**Interfaces:**
- Produces: `LibraryGrouper.group` 的重载 `fun group(files: List<MediaFile>, bdmvDirs: List<MediaFile>): List<LibraryEntry>`——`bdmvDirs` 为"包含 BDMV 子目录的文件夹"占位 MediaFile（name=文件夹名），识别为 `Movie(parsed=原盘标记)`，qualityTokens 含 `BLURAY_DISC`。

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `bdmv folder becomes one movie`() {   // Review Focus #4
    val e = LibraryGrouper.group(emptyList(), listOf(mf("让子弹飞.2010/BDMV")))
    val m = e.single() as LibraryEntry.Movie
    assertEquals("让子弹飞", m.parsed.title)
    assertTrue(m.parsed.qualityTokens.contains(QualityToken.BLURAY_DISC))
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：对占位项复用 FilenameParser.parse(name 去掉 `/BDMV` 尾巴)；STREAM 内部 m2ts 由扫描器层面不递归（Task 9 保证）。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(group): bluray bdmv folder recognition`

---

### Task 9: LocalMediaSource + 扫描器

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/MediaSource.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/LocalMediaSource.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/source/LocalMediaSourceTest.kt`（用 `@TempDir` 临时目录 + JUnit4 `TemporaryFolder`）

**Interfaces:**
- Produces: `interface MediaSource { val id: String; val name: String; suspend fun scan(callback: (ScanUpdate) -> Unit): ScanResult }`
- Produces: `data class ScanResult(files: List<MediaFile>, bdmvDirs: List<MediaFile>, skippedCount: Int)`
- Produces: `sealed interface ScanUpdate { data class Progress(scanned: Int, found: Int): ScanUpdate; data class ErrorHint(path: String, message: String): ScanUpdate }`
- Produces: `class LocalMediaSource(id: String, name: String, rootPath: Path) : MediaSource`
- Consumes: `MediaFile`（Task 7）

- [ ] **Step 1: 写失败测试（Review Focus #5）**

```kotlin
@Test fun `scans nested dirs for whitelisted extensions`() {
    // temp 树：a/b/movie.mkv、sub/ep.mp4、note.txt、poster.jpg
    // → files 含 2 项；txt/jpg 不算 skippedCount 也不算 files
}
@Test fun `skips hidden and bdmv internals`() {
    // .hidden/ 下的 mkv 不出现；Movie/BDMV/STREAM/00001.m2ts 不进 files，
    // 且 Movie/BDMV 作为 bdmvDirs 占位出现
}
@Test fun `unreadable entry reports hint not crash`() {
    // 用不可读文件/畸形路径触发 → ScanUpdate.ErrorHint 且 scan 正常返回
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：`java.nio.file.Files.walk`（try-with-resources 关闭流）；目录过滤 `.` 前缀；遇 `BDMV` 目录：登记占位、`skip` 整棵子树（walk filter 实现）；扩展名白名单见 Global Constraints；每个文件构造 MediaFile；IOException → ErrorHint 继续。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(source): local media source scanner`

---

### Task 10: LibraryRepository — 组装与查询

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/repo/LibraryRepository.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/probe/ProbeClient.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/repo/LibraryRepositoryTest.kt`

**Interfaces:**
- Consumes: `MediaSource`（Task 9）、`LibraryGrouper`（Task 7/8）
- Produces: `class LibraryRepository(private val sources: List<MediaSource>) { suspend fun refresh(callback: (ScanUpdate) -> Unit = {}): LibrarySnapshot; fun snapshot(): LibrarySnapshot? }`
- Produces: `data class LibrarySnapshot(entries: List<LibraryEntry>, generatedAtEpochMs: Long)`
- Produces: SPI 定义 `interface ProbeClient { suspend fun probe(mediaFile: MediaFile): ProbeInfo }` 与 `data class ProbeInfo(width: Int?, height: Int?, hdrFormat: String?, audioCodecs: List<String>, subtitleTrackCount: Int, durationMs: Long?)`——本计划只定义接口与 `NoopProbeClient`，实现属计划 3
- Produces: 便捷查询 `LibrarySnapshot.movies(): List<Movie>`、`.series(): List<Series>`、`.recentlyAdded(n: Int): List<LibraryEntry>`（按 lastModifiedEpochMs 降序）

- [ ] **Step 1: 写失败测试**

```kotlin
@Test fun `refresh aggregates sources into snapshot`() {
    // 两个 fake source（各 1 movie + 1 series 集）→ snapshot.entries 2 系列+2 电影
    // movies() = 2; series() = 2; recentlyAdded(1) 取 lastModified 最大者
}
@Test fun `snapshot cached until refresh`() {
    // 二次 snapshot() 不重扫（fake source 计数器证明只 scan 一次）
}
```

- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL

- [ ] **Step 3: 实现**

策略：repository 持各源 ScanResult → group(files, bdmvDirs) 合并；fake source 在测试里实现 MediaSource 即可（并行扫描用 `coroutineScope { sources.map { async } }`，结果按源顺序合并）。

- [ ] **Step 4: 跑测试确认通过** — Expected: PASS

- [ ] **Step 5: Commit** — `feat(repo): library repository with snapshot queries`

---

## Self-Review 记录

- **Spec 覆盖**：§5.4 的封面链/截帧/FFprobe 探测属计划 5 与 3（序列表已列）；本计划覆盖 5.4 中"文件名清洗/剧集归组/原盘识别/私人视频兜底(usedFallback→文件页)"。SQLite 持久化推迟到计划 2（与多源一起引入 Room），本计划内存快照够用。
- **Review Focus**：5 条全部钉死在 Task 5/6/7/8/9。
- **类型一致**：`ParsedFileName/QualityToken/MediaFile/LibraryEntry/MediaSource/ScanResult/ProbeInfo` 各定义一次，后续任务引用。
- **比例**：10 任务 × 5 步，无函数体转录。
