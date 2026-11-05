# Slogan 计划 2/6：SMB 媒体源 + 持久化 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让媒体库"看到 NAS"：实现 SMB 媒体源（SMB2/2.1/3，凭据管理）与 SQLite 持久化（快照离线可用、断网可开），含部分失败语义与源配置管理。

**Architecture:** 六边形——`SmbFileClient` 接口隔离 smbj（网络 IO 无服务器不可测，fake 驱动全部扫描逻辑单测，smbj 适配器保持薄、真机验收）；扫描过滤规则提取为共享 `ScanRules`（Local/SMB 复用）；持久化走 `LibraryStore` 接口 + SQLDelight（纯 JVM 驱动真跑 sqlite 测试），`InMemoryStore` 兜底。凭据经 `CredentialCipher` 接口（Plain 仅测试，Android Keystore 实现属计划 4）。

**Tech Stack:** Kotlin/JVM（toolchain 17）、smbj 0.13.x（+ BouncyCastle 供 SMB3 加密）、SQLDelight 2.0.x（JVM sqlite 驱动）、kotlinx-coroutines。版本记入 `gradle/libs.versions.toml`。

**Spec:** `docs/superpowers/specs/2026-11-05-slogan-tv-player-design.md`（§5.4 媒体库构建、§6 架构要点；计划序列调整见下）

## 范围裁定（计划序列调整）

- **NFS 移至计划 3**：libnfs 是 C 库（Kodi 同款），需要 NDK/原生工具链——与 mpv 内核同一套基础设施，放计划 3 一起做。本计划只做 SMB（绝大多数国内 NAS 用户的协议）。spec §6 的"NFS=libnfs"决策不变，仅排期后移。
- **SQLDelight 替代 Room**（计划 1 self-review 曾提 Room）：Room 需 KSP/Android 工程形态，会破坏 `:core-library` 纯 JVM 可测性；SQLDelight 同样产出 SQLite 且纯 JVM 一等公民。spec 只钉了"本地 SQLite"，库选择是建造者决策。

## Global Constraints

- 继承计划 1 全部约束（minSdk 23、纯 JVM 测试、禁 java.nio.file 于公共 API、无互联网请求）
- SMB1 明确不支持：连接失败时 ErrorHint 说明，不静默（spec §6）
- 凭据不得明文持久化：`PlainCredentialCipher` 仅限测试，默认实现必须经 Cipher
- 网络源测试一律走 fake（CI 无 NAS）；smbj 适配器保持"薄到不值得单测"，真实 NAS 验收在 A55+NAS 环境
- 所有 SMB 路径/relativePath 用 `/` 分隔（与计划 1 约定一致）
- conventional commits；每任务至少一提交

## Review Focus（易咬人的输入，钉死在对应任务）

1. SMB 认证失败/主机超时 → 分类清晰的 ErrorHint，不崩不静默 → Task 6
2. NAS 关机/断网时启动 App：从持久化快照照常显示媒体库（"离线完全可用"的持久化语义） → Task 10
3. 重复扫描同一源 → 不产生重复行（upsert 语义，按 sourceId+path 唯一） → Task 9
4. SMB 目录/文件名含中文、空格、`$`、全角字符 → 扫描、relativePath、分组均不乱 → Task 5
5. 隐藏/系统目录/回收站/0 字节/BDMV 在 SMB 上与本地同等跳过 → Task 5

---

### Task 1: 提取共享扫描规则 ScanRules

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/ScanRules.kt`
- Modify: `core-library/src/main/kotlin/com/slogan/tv/library/source/LocalMediaSource.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/source/ScanRulesTest.kt`

**Interfaces:**
- Produces: `object ScanRules { fun isSkippedDir(name: String): Boolean; fun isHiddenOrEmptyFile(name: String, sizeBytes: Long): Boolean; fun isVideoFile(name: String): Boolean }`（规则值与 LocalMediaSource 现行完全一致：系统目录黑名单、`.` 前缀、BDMV 由调用方特判后转调 isSkippedDir 之外的占位逻辑不变）
- Consumes: `FilenameParser.VIDEO_EXTENSIONS`

- [ ] **Step 1: 写失败测试**（`isSkippedDir("$RECYCLE.BIN")==true`、`isSkippedDir("普通目录")==false`、`isHiddenOrEmptyFile(".x.mkv",1)==true`、`isHiddenOrEmptyFile("x.mkv",0)==true`、`isHiddenOrEmptyFile("x.mkv",1)==false`、`isVideoFile("a.MKV")==true`、`isVideoFile("a.txt")==false`）
- [ ] **Step 2: 跑测试确认失败** — Run: `./gradlew :core-library:test --tests "...ScanRulesTest"` — Expected: FAIL（未定义）
- [ ] **Step 3: 实现 ScanRules 并重构 LocalMediaSource 调用它**（行为不变，现有 LocalMediaSourceTest 全绿即证明等价）
- [ ] **Step 4: 跑全量测试** — Expected: PASS（含既有 LocalMediaSourceTest）
- [ ] **Step 5: Commit** — `refactor(source): extract shared scan rules`

### Task 2: 源配置模型 MediaSourceConfig

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/MediaSourceConfig.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/source/MediaSourceConfigTest.kt`

**Interfaces:**
- Produces: `data class MediaSourceConfig(id: String, name: String, type: SourceType, host: String? = null, port: Int? = null, shareRoot: String? = null, username: String? = null, domain: String? = null)` + `enum class SourceType { LOCAL, SMB }`
- Produces: `fun MediaSourceConfig.validate(): List<String>`（SMB 必填 host+shareRoot、port 缺省 445；LOCAL 无 host）——密码不入本模型（见 Task 3）

- [ ] **Step 1: 写失败测试**（SMB 全字段 → 空；SMB 缺 shareRoot → 报错；LOCAL 带 host → 报错；默认端口）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现** — 策略：纯字段校验，返回错误文案列表（中文）
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(source): media source config model`

### Task 3: 凭据与加密接口

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/Credentials.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/source/CredentialsTest.kt`

**Interfaces:**
- Produces: `data class SmbCredentials(username: String?, domain: String?, password: String?)`
- Produces: `interface CredentialCipher { fun encrypt(plain: String): String; fun decrypt(cipher: String): String }` + `object PlainCredentialCipher : CredentialCipher`
- Produces: `class AesCredentialCipher(keyProvider: () -> ByteArray) : CredentialCipher`（AES/GCM，IV 前置随机；密钥来源注入，Android 侧计划 4 接 Keystore）

- [ ] **Step 1: 写失败测试**（AES round-trip：encrypt→decrypt==原文；密文≠原文；两次 encrypt 同文不同密文（随机 IV）；错密钥 decrypt 抛异常；Plain 直通）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现**（javax.crypto，JVM/Android 通用）
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(source): credentials with aes-gcm cipher`

### Task 4: SmbFileClient 接口与测试 Fake

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/smb/SmbFileClient.kt`
- Create: `core-library/src/test/kotlin/com/slogan/tv/library/source/smb/FakeSmbFileClient.kt`

**Interfaces:**
- Produces: `interface SmbFileClient { suspend fun list(dir: String): List<SmbEntry>; fun close() }` + `data class SmbEntry(name: String, isDirectory: Boolean, sizeBytes: Long, lastModifiedEpochMs: Long)` + `class SmbCommunicationException(message: String, val kind: Kind) : Exception(message) { enum class Kind { AUTH, TIMEOUT, PROTOCOL, IO } }`
- Produces (test fixture): `FakeSmbFileClient(root: MutableMap<String, List<SmbEntry>>)`——内存目录树，支持按 dir 返回与预置异常

- [ ] **Step 1: 写失败测试**（Fake 行为契约：list 返回预置、未知 dir → 空、可注入异常）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现** — Fake 是测试设施，接口无逻辑
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(smb): file client interface and fake`

### Task 5: SmbMediaSource 扫描实现

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/smb/SmbMediaSource.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/source/smb/SmbMediaSourceTest.kt`

**Interfaces:**
- Consumes: `SmbFileClient`、`ScanRules`、`MediaFile`（含 relativePath）
- Produces: `class SmbMediaSource(id: String, name: String, shareRoot: String, clientFactory: suspend () -> SmbFileClient) : MediaSource`——递归遍历 shareRoot，规则与 Local 完全一致（ScanRules + BDMV 占位 `文件夹名/BDMV`），Dispatchers.IO + ensureActive，IOException/`SmbCommunicationException` → ErrorHint

- [ ] **Step 1: 写失败测试**（Review Focus 4/5：Fake 树含中文/空格/`$`/全角名、`.hidden`、`$RECYCLE.BIN`、`BDMV` 子树、0 字节、白名单外扩展 → files/bdmvDirs/skippedCount 断言；relativePath 以 `/` 拼）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现** — 策略：显式栈迭代（无递归深度问题）；目录访问异常 → ErrorHint 并跳过该子树继续
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(smb): smb media source scanner`

### Task 6: SMB 错误语义（认证/超时/协议）

**Files:**
- Modify: `SmbMediaSource.kt`
- Test: 追加 `SmbErrorSemanticsTest`

**Interfaces:**
- Produces: 错误契约——clientFactory/list 抛 `SmbCommunicationException(AUTH)` → ErrorHint("认证失败，请检查账号密码")；`TIMEOUT` → ("连接超时，请检查主机地址与网络")；`PROTOCOL`（含 SMB1 协商失败）→ ("该设备仅支持 SMB1，暂不支持")；其余 → 原始 message。scan 永不抛。

- [ ] **Step 1: 写失败测试**（三种 kind 断言 ErrorHint 文案前缀；IO kind 不中断其余目录）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现**（kind→文案映射；顶层异常与目录级异常分开处理）
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(smb): error semantics auth/timeout/protocol`

### Task 7: smbj 适配器与 SourceFactory

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/smb/SmbjClient.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/SourceFactory.kt`
- Modify: `core-library/build.gradle.kts`、`gradle/libs.versions.toml`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/source/SourceFactoryTest.kt`

**Interfaces:**
- Produces: `class SmbjClient(config: MediaSourceConfig, password: String?) : SmbFileClient`——smbj 真实现（薄：连接参数、context、list 映射；异常→SmbCommunicationException 分类）
- Produces: `class SourceFactory(private val credentialCipher: CredentialCipher, private val passwordStore: (String) -> String?) { fun create(config: MediaSourceConfig): MediaSource }`——LOCAL→LocalMediaSource，SMB→SmbMediaSource(SmbjClient)

- [ ] **Step 1: 写失败测试**（Factory：LOCAL config→LocalMediaSource 实例；SMB config→SmbMediaSource 实例；密码经 cipher.decrypt 后传入——用 Fake 校验注入的 clientFactory 闭包拿到解密后密码）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现**（smbj 适配器无单测——薄层，真机验收；依赖入 catalog）
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(smb): smbj adapter and source factory`

### Task 8: SQLDelight 库与 schema

**Files:**
- Create: `core-library/src/main/sqldelight/com/slogan/tv/library/db/sources.sq`、`mediaFiles.sq`、`snapshot.sq`
- Modify: `core-library/build.gradle.kts`（sqldelight 插件）、`settings.gradle.kts`（插件仓库）、`gradle/libs.versions.toml`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/db/DatabaseTest.kt`

**Interfaces:**
- Produces: `fun createLibraryDatabase(driver: SqlDriver): LibraryDatabase`（表：`sources(id,name,type,host,port,share_root,username,domain)`、`media_files(source_id,path,name,size_bytes,mtime,relative_path,PRIMARY KEY(source_id,path))`、`snapshot(id INTEGER PRIMARY KEY AUTOINCREMENT, generated_at)`）——驱动注入：JVM 测试用 `app.cash.sqldelight:sqlite-driver`，Android 侧计划 4 给 AndroidDriver
- Produces: upsert 语句（`media_files` 按 sourceId 全量替换 + 插入）

- [ ] **Step 1: 写失败测试**（createDatabase + 三表建表即通；executeAndReturnGeneratedKey 可插 snapshot 行）——RED 形态：类型/函数未定义编译失败
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现**（.sq 文件 + 插件配置；schema 版本 1）
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS（真 sqlite 文件跑在临时目录）
- [ ] **Step 5: Commit** — `feat(db): sqldelight schema v1`

### Task 9: LibraryStore 接口与两个实现

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/repo/LibraryStore.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/repo/LibraryStoreTest.kt`

**Interfaces:**
- Consumes: `LibrarySnapshot`、`MediaSourceConfig`
- Produces: `interface LibraryStore { suspend fun saveSources(configs: List<MediaSourceConfig>); suspend fun loadSources(): List<MediaSourceConfig>; suspend fun saveScanResult(sourceId: String, files: List<MediaFile>, bdmvDirs: List<MediaFile>); suspend fun loadAllFiles(): List<MediaFile>; suspend fun loadAllBdmvDirs(): List<MediaFile>; suspend fun recordSnapshotTime(epochMs: Long); suspend fun lastSnapshotTime(): Long? }`
- Produces: `class SqlDelightLibraryStore(db: LibraryDatabase) : LibraryStore`、`class InMemoryLibraryStore : LibraryStore`

- [ ] **Step 1: 写失败测试**（对两个实现跑同一套契约测试（参数化/基类）：sources round-trip；saveScanResult 两次同源 → 不重复（Review Focus 3）；不同源互不干扰；lastSnapshotTime null→值）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现**
- [ ] **Step 4: 跑测试确认通过** — Expected: PASS
- [ ] **Step 5: Commit** — `feat(repo): library store with sqldelight and in-memory impls`

### Task 10: Repository 持久化整合 + 部分失败语义

**Files:**
- Modify: `core-library/src/main/kotlin/com/slogan/tv/library/repo/LibraryRepository.kt`
- Test: 追加 `LibraryRepositoryPersistenceTest.kt`

**Interfaces:**
- Produces: `LibraryRepository` 扩展——构造参数追加 `private val store: LibraryStore? = null`；`refresh()`：各源独立捕获异常（失败源：ErrorHint + 不写入该源数据，成功源照常，快照仍生成并落库）；`suspend fun restore(): LibrarySnapshot?`（从 store 装配最近快照，NAS 离线时启动可用）
- Consumes: `LibraryStore`（Task 9）

- [ ] **Step 1: 写失败测试**（Review Focus 1/2：双源一抛异常→快照含健康源数据+ErrorHint；restore() 在不调用 refresh 时返回落库数据；store=null 时行为与计划 1 完全一致）
- [ ] **Step 2: 跑测试确认失败** — Expected: FAIL
- [ ] **Step 3: 实现**（每源 async 内 try/catch；失败源数据从 store 清除）
- [ ] **Step 4: 跑全量测试 + `./gradlew build`** — Expected: PASS / BUILD SUCCESSFUL
- [ ] **Step 5: Commit** — `feat(repo): persistence integration and partial-failure semantics`

---

## Self-Review 记录

- **Spec 覆盖**：§5.4"SMB 为主力源"落地（Task 5-7）；"已刮削媒体离线可用"落为持久化快照（Task 8-10）；"扫描在可取消后台服务"延续（IO+ensureActive）；SMB1 明确报错（Task 6）。NFS/进度回写 UI/多源管理 UI 不在本计划（计划 3/4）。
- **Review Focus**：5 条钉死在 Task 5/6/9/10。
- **类型一致**：`MediaFile/ScanUpdate/ScanResult/MediaSource/LibrarySnapshot` 全部复用计划 1 定义；新增类型各定义一次。
- **比例**：10 任务 × 5 步，无函数体转录。
