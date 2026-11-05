# Slogan 计划 3/6：播放内核（libmpv + NFS + 音频直通探测）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 电视上真正播片：`:player` 模块集成 libmpv（社区预编译起步，接口可换自编译），打通本地/SMB/NFS 三路播放（SMB/NFS 经内部回环流媒体服务器），音频直通"能力探测 + PCM 保底 + 实验开关"，FFprobe 语义的懒探测（ProbeClient 实现），并交付 adb 真机验收工具。

**Architecture:** 三层——`core-library`（纯 JVM：回环流媒体服务器、URL 路由、直通能力矩阵、NFS 协议接口）→ `:player`（Android 库：MPVLib 封装成 PlayerEngine、libnfs JNI、AudioCapabilityProbe、调试验收页）。**内核选型 C**：`dev.jdtech.mpv:libmpv`（Findroid 团队，MIT wrapper）起步；PlayerEngine 是唯一与内核对话的层，将来换自编译 libmpv 零上层改动。SMB/NFS 播放统一走 127.0.0.1 回环 HTTP（Range 支持），不写 mpv 定制 JNI。

**Tech Stack:** `dev.jdtech.mpv:libmpv`（Maven Central AAR）、smbj（读流）、libnfs（WSL+NDK 自编，arm64-v8a + armeabi-v7a，LGPL 动态链接）、kotlinx-coroutines。新增 Android 库模块 `:player`。

**Spec:** `docs/superpowers/specs/2026-11-05-slogan-tv-player-design.md`（§5.6 播放核心与音频直通、§6 技术栈；NFS=libnfs 的既定决策在本计划落地）

## 范围裁定

- **内核来源（产品负责人已选 C）**：预编译 `dev.jdtech.mpv:libmpv` + 自建 libnfs。Task 1 验证 AAR 实际内容（ABI/mpv 版本/MPVLib API 面），不符预期则升级为 WSL 自编 libmpv（B 路线，工作量另计）。
- **SMB/NFS 播放走回环 HTTP** 而非 mpv stream_cb 定制 JNI：MPVLib（预编译 wrapper）未必暴露 stream_cb；回环服务器纯 JVM 可 TDD、对任意源通用。代价：一次内存拷贝 + loopback，A55 实测无感（验收项）。
- **音频直通分级**：v1 = 能力探测（AudioManager 上报的编码集）+ 设置三态（自动/强制直通/强制 PCM）→ 映射 mpv `--audio-spdif`/`--ao` 参数；**mpv 安卓端对 IEC61937 的真实支持度未知**，以 A55+AVR 真机验收为准，不达标则直通标记"实验性"且默认自动回退 PCM（spec §5.6"能通则通"）。
- **LGPL 合规**：libmpv/libnfs 动态链接、不修改；开源声明清单随计划 6 发版打包，本计划先把清单文件生成好。

## Global Constraints

- 继承计划 1/2 全部约束（minSdk 23、core-library 纯 JVM、无互联网、conventional commits）
- 回环流媒体服务器只绑 `127.0.0.1`，无鉴权（本机唯一消费者），生命周期跟随播放会话
- libnfs 产物双 ABI（arm64-v8a、armeabi-v7a）；JNI 代码自写（libnfs 是 LGPL，动态链接 + 我们不闭源其源码）
- 不得把 GPL 代码引入仓库（mpv-android 的 GPL 部分、Findroid 应用代码都不抄；只消费其 MIT 的 AAR）
- 设备相关测试用 connectedAndroidTest（A55: `adb 192.168.1.12:5555`，已授权）；CI（无设备）只跑 JVM 测试 + assemble
- 引擎所有回调不得阻塞主线程；Surface 生命周期与引擎释放必须配对（防 ANR/泄漏）

## Review Focus（易咬人的输入，钉死在对应任务）

1. 播放中网络抖动/NAS 掉线 → mpv 缓冲或转 Error 状态，App 不崩不 ANR → Task 2/6
2. 4K REMUX 大文件随机 seek（HTTP Range 从中段拉）→ 回环服务器正确响应 206 与新区间 → Task 5
3. 直通开启但电视不支持 → 自动回退 PCM 且设置页可见当前模式 → Task 4
4. NFS 中文/空格目录名、匿名与账密挂载 → 扫描与播放均不乱 → Task 7/8
5. 快速进出播放（Surface 销毁/重建、连播切换）→ 无 native 崩溃、无句柄泄漏 → Task 2/10

---

### Task 1: `:player` 模块骨架 + libmpv AAR 接入验证

**Files:**
- Create: `player/build.gradle.kts`、`player/src/main/AndroidManifest.xml`、`player/src/main/kotlin/com/slogan/tv/player/MpvInitializer.kt`
- Modify: `settings.gradle.kts`（include）、`gradle/libs.versions.toml`、`:app` 依赖 `:player`
- Test: `player/src/androidTest/kotlin/com/slogan/tv/player/MpvVersionInstrumentedTest.kt`

**Interfaces:**
- Produces: `:player` 模块（com.android.library，namespace `com.slogan.tv.player`，minSdk 23）；`object MpvInitializer { fun clientApiVersion(): String; fun init(context: Context) }`
- Consumes: `dev.jdtech.mpv:libmpv`（版本取当前稳定，记入 catalog）

- [ ] **Step 1: 下载并验尸 AAR**（本地一次性脚本，不入库）：解包检查 `jniLibs` ABI 集、libmpv.so 依赖的 so、MPVLib 类的方法面（javap）；记录 mpv 版本号到任务台账——**不符预期（缺 arm64/MPVLib 面太窄）→ 升级为 B 路线并停计划重议**
- [ ] **Step 2: 模块接线**（catalog + settings + :app 依赖）
- [ ] **Step 3: 写设备测试**（MPVLib 创建 → 读 `mpv-version` 属性 → 断言非空且含 "mpv"）
- [ ] **Step 4: A55 上跑**：`./gradlew :player:connectedDebugAndroidTest`（adb 已连 192.168.1.12:5555）— Expected: PASS
- [ ] **Step 5: Commit** — `feat(player): module skeleton with prebuilt libmpv`

### Task 2: PlayerEngine 接口 + MpvEngine 基础播放

**Files:**
- Create: `player/src/main/kotlin/com/slogan/tv/player/engine/PlayerEngine.kt`（接口 + EngineState）
- Create: `player/src/main/kotlin/com/slogan/tv/player/engine/MpvEngine.kt`
- Test: `player/src/androidTest/kotlin/com/slogan/tv/player/engine/MpvEngineInstrumentedTest.kt`（设备）

**Interfaces:**
- Produces: `sealed interface EngineState { Idle; Preparing; Playing(positionMs, durationMs); Paused(positionMs, durationMs); Buffering(positionMs); Ended; Error(message) }`
- Produces: `interface PlayerEngine { val state: StateFlow<EngineState>; fun attach(surface: Surface); fun detach(); fun load(url: String, options: PlaybackOptions); fun play(); fun pause(); fun seekTo(positionMs: Long); fun stop(); fun release() }`
- Produces: `data class PlaybackOptions(hwdec: Boolean = true, audioSpdif: Set<String> = emptySet(), ao: String? = null, startMs: Long? = null)`（直通参数由 Task 4 的能力矩阵生成）
- Produces: `class MpvEngine(context: Context) : PlayerEngine`（MPVLib 封装：事件→StateFlow 映射、命令异步化）

- [ ] **Step 1: 写设备测试**（assets 放一个 ~5s 的 H.264 样片：load→等 Playing→pause→seek→Playing 断言位置/时长>0→stop→再 load→release；快速重建一次 attach/detach）——RED：接口未定义编译失败
- [ ] **Step 2: A55 跑确认 RED**
- [ ] **Step 3: 实现 MpvEngine**（MPVLib init/命令/observe_property→state；所有 MPVLib 调用在专用单线程派发器）
- [ ] **Step 4: A55 跑确认 GREEN + `./gradlew build` JVM 侧全绿**
- [ ] **Step 5: Commit** — `feat(player): player engine interface and mpv implementation`

### Task 3: 轨道 API（音轨/字幕轨列举与切换）

**Files:**
- Modify: `PlayerEngine.kt`、`MpvEngine.kt`
- Test: 追加设备测试

**Interfaces:**
- Produces: `data class TrackInfo(id: Int, kind: TrackKind, title: String?, language: String?, codec: String?, isDefault: Boolean, isSelected: Boolean)` + `enum class TrackKind { AUDIO, SUB, VIDEO }`
- Produces: `fun tracks(): List<TrackInfo>`、`fun selectTrack(kind: TrackKind, id: Int?)`（null=禁用，字幕关）

- [ ] **Step 1: 设备测试**（样片含 2 条音轨+2 条字幕：断言轨道数与选择切换生效）— RED
- [ ] **Step 2: A55 确认 RED**
- [ ] **Step 3: 实现**（mpv `track-list` 属性解析 + `aid/sid` 设置）
- [ ] **Step 4: A55 确认 GREEN**
- [ ] **Step 5: Commit** — `feat(player): track listing and selection`

### Task 4: 音频能力探测 + 直通设置矩阵

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/audio/AudioCapabilities.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/audio/PassthroughPolicy.kt`
- Create: `player/src/main/kotlin/com/slogan/tv/player/audio/AndroidAudioCapabilities.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/audio/PassthroughPolicyTest.kt`（纯 JVM）

**Interfaces:**
- Produces: `data class AudioCapabilities(supportedEncodings: Set<String>)`（如 "ac3","eac3","dts","truehd"）
- Produces: `enum class PassthroughMode { AUTO, FORCE_PASSTHROUGH, FORCE_PCM }`
- Produces: `object PassthroughPolicy { fun resolve(mode: PassthroughMode, caps: AudioCapabilities, fileAudioCodec: String?): PassthroughDecision }` + `data class PassthroughDecision(usePassthrough: Boolean, spdifCodecs: Set<String>, ao: String?, reason: String)`
- Produces: `class AndroidAudioCapabilities(context: Context) : () -> AudioCapabilities`（AudioManager/TV 输入能力查询；查不到给保守空集）

- [ ] **Step 1: 写失败测试**（AUTO+支持 dts+文件 dts→直通 dts；AUTO+不支持→PCM+reason；FORCE_PASSTHROUGH+不支持→仍直通但 reason 标记风险；FORCE_PCM→永不直通；文件无音轨信息→AUTO 保守 PCM）— Expected: FAIL
- [ ] **Step 2: 跑确认 RED**
- [ ] **Step 3: 实现**（矩阵纯函数 + 安卓探测薄层）
- [ ] **Step 4: 跑确认 GREEN**
- [ ] **Step 5: Commit** — `feat(audio): passthrough capability matrix and policy`

### Task 5: 回环流媒体服务器（Range/206）

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/stream/LoopbackStreamServer.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/stream/StreamSource.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/stream/LoopbackStreamServerTest.kt`（纯 JVM，用 HttpURLConnection 打回环）

**Interfaces:**
- Produces: `interface StreamSource { val lengthBytes: Long?; fun open(offset: Long): InputStream }`（smbj/libnfs 读桥实现于 Task 6/8）
- Produces: `class LoopbackStreamServer { fun start(port: Int = 0): Int; fun register(id: String, source: StreamSource): String /* http://127.0.0.1:p/id */; fun unregister(id: String); fun stop() }`（ServerSocket(127.0.0.1)，单线程 accept + 每连接协程；GET/HEAD；Range: bytes=a-b 与 suffix → 206/Content-Range；无 Range→200；来源关闭→404）

- [ ] **Step 1: 写失败测试**（注册 fake source→200 全量读一致；Range 中段→206 且字节区间正确；多段连接并发；HEAD；404；stop 后端口拒绝）— Expected: FAIL
- [ ] **Step 2: 跑确认 RED**
- [ ] **Step 3: 实现**（ServerSocket + 手写最小 HTTP/1.1 应答，只支持必需方法）
- [ ] **Step 4: 跑确认 GREEN + 全量 JVM**
- [ ] **Step 5: Commit** — `feat(stream): loopback streaming server with range support`

### Task 6: SMB 播放桥（smbj 读 → 回环流）

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/stream/SmbStreamSource.kt`
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/stream/PlaybackUriResolver.kt`
- Test: `core-library/src/test/kotlin/com/slogan/tv/library/stream/PlaybackUriResolverTest.kt`

**Interfaces:**
- Produces: `class SmbStreamSource(open: suspend (offset: Long) -> InputStream) : StreamSource`（smbj `DiskShare.open(path, setReadonly).inputStream` + skip）
- Produces: `class PlaybackUriResolver(server: LoopbackStreamServer, clientProvider: suspend (MediaFile) -> StreamSource) { suspend fun resolve(file: MediaFile): String }`——本地文件直返 `file.path`；远端注册回环流返回 URL（同 path 重复注册复用）
- Consumes: `MediaFile`（sourceId 区分本地/远端）

- [ ] **Step 1: 写失败测试**（local MediaFile→原路径；SMB MediaFile→注册得 127.0.0.1 URL；同文件复用同 URL；未注册路径→resolve 抛可读错误）— Expected: FAIL
- [ ] **Step 2: 跑确认 RED** → **Step 3: 实现** → **Step 4: GREEN**
- [ ] **Step 5: Commit** — `feat(stream): smb playback bridge via loopback`

### Task 7: libnfs 构建 + JNI 客户端

**Files:**
- Create: `tools/build-libnfs.sh`（WSL 内执行：clone libnfs → NDK 交叉编译双 ABI → 产 .so 到 `player/src/main/jniLibs/<abi>/`）
- Create: `player/src/main/kotlin/com/slogan/tv/player/nfs/NfsClient.kt`（JNI）
- Create: `player/src/main/cpp/CMakeLists.txt` + `nfs_jni.c`（自写薄 JNI：nfs_init_context/mount/list/read/close）
- Create: `THIRD_PARTY_LICENSES.md`（libmpv LGPL-2.1+、libnfs LGPL-2.1/BSD、smbj Apache-2.0 声明）
- Test: `player/src/androidTest/kotlin/com/slogan/tv/player/nfs/NfsClientInstrumentedTest.kt`（设备，用可用的公开 NFS 无依赖冒烟：本地 context 加载 .so 成功 + 错误路径返回 null 不崩）

**Interfaces:**
- Produces: `class NfsClient { fun connect(host: String, export: String, user: String?, password: String?): Boolean; fun list(dir: String): List<NfsEntry>?; fun openRead(path: String): NfsFileHandle?; fun close() }`（native 异常一律转 false/null——JNI 不抛）
- Produces: `data class NfsEntry(name, isDirectory, sizeBytes, mtimeSec)` + `class NfsFileHandle { fun readAt(buf: ByteArray, offset: Long): Int; fun size(): Long; fun close() }`

- [ ] **Step 1: WSL 跑构建脚本产 .so**（NDK 用 Android SDK 的 ndk-bundle？——先 `sdkmanager --install "ndk;27.x"`；记录所用 NDK/版本入台账）
- [ ] **Step 2: 写设备测试**（加载 .so、connect 到不存在的主机→false 不崩；list 未连接→null）— RED（JNI 未写）
- [ ] **Step 3: 实现 JNI 薄层** → **Step 4: A55 确认 GREEN**（真实 NFS 冒烟留验收清单：需用户 NAS 开 NFS 导出）
- [ ] **Step 5: Commit** — `feat(nfs): libnfs cross-compile and jni client`

### Task 8: NFS 媒体源（扫描 + 播放）

**Files:**
- Create: `core-library/src/main/kotlin/com/slogan/tv/library/source/nfs/NfsFileClient.kt`（接口，镜像 SmbFileClient）
- Create: `player/src/main/kotlin/com/slogan/tv/player/nfs/NfsMediaSource.kt`（:player 侧实现 MediaSource）
- Modify: `SourceType`（+NFS）、`MediaSourceConfig.validate()`、`SourceFactory`（NFS 分支——工厂挪到 :player 或注入 NfsFactory lambda，保 core 纯 JVM）
- Test: `core-library` 增 Fake NfsFileClient 扫描测试（复用 ScanRules 断言风格）+ Config/Factory 测试

**Interfaces:**
- Produces: `enum class SourceType { LOCAL, SMB, NFS }`（DB type 列 TEXT 兼容）
- Produces: `class NfsMediaSource(id, name, host, export, client: NfsFileClient) : MediaSource`（规则与 SMB 扫描器一致）
- Consumes: `ScanRules`、`ScanResult(fatalError)`

- [ ] **Step 1: 写失败测试**（NFS config 校验：必填 host+shareRoot(=export)；Fake 树扫描规则与 SMB 同套断言；Factory NFS 分支产出 NfsMediaSource）— RED
- [ ] **Step 2: 跑确认 RED** → **Step 3: 实现**（含 PlaybackUriResolver 的 NFS 流桥：NfsFileHandle→StreamSource）→ **Step 4: GREEN + build**
- [ ] **Step 5: Commit** — `feat(nfs): nfs media source scan and playback`

### Task 9: ProbeClient 实现（libmpv 懒探测）

**Files:**
- Create: `player/src/main/kotlin/com/slogan/tv/player/probe/MpvProbeClient.kt`
- Test: `player/src/androidTest/.../MpvProbeClientInstrumentedTest.kt`

**Interfaces:**
- Consumes: `ProbeClient/ProbeInfo`（计划 1 定义的 SPI）
- Produces: `class MpvProbeClient(context: Context) : ProbeClient`——独立 MPVLib 实例 loadfile(pause=yes, 匿名 surface 不 attach)→读 width/height/audio-params/duration/sub-track count→释放；超时 3s 兜底返回空 ProbeInfo

- [ ] **Step 1: 设备测试**（样片 probe：width/height/durationMs 正确；不存在的文件→空 ProbeInfo 不崩）— RED
- [ ] **Step 2: A55 确认 RED** → **Step 3: 实现** → **Step 4: A55 GREEN**
- [ ] **Step 5: Commit** — `feat(probe): mpv-based lazy probe client`

### Task 10: 调试播放页（真机验收工具）+ 验收清单

**Files:**
- Create: `app/src/debug/kotlin/com/slogan/tv/app/DebugPlayerActivity.kt`（SurfaceView + MpvEngine 全屏；intent extra：url、直通模式）
- Modify: debug Manifest（exported，供 adb 唤起）
- Create: `docs/superpowers/acceptance/plan3-device-checklist.md`（真机清单：本地 4K HDR 片、SMB REMUX 随机 seek、直通三态 × AVR 实听、NFS 中文目录、快速进出 ×10、断网恢复）
- Test: 人工验收（adb am start 唤起）+ connectedAndroidTest 全套跑一遍

**Interfaces:**
- Produces: 验收工具与清单（产品负责人可自行 `adb shell am start -n com.slogan.tv/.app.DebugPlayerActivity --es url ...`）

- [ ] **Step 1: 实现 DebugPlayerActivity**（复用 MpvEngine；显示当前 EngineState/直通决策 overlay）
- [ ] **Step 2: `./gradlew build` 全绿 + connectedAndroidTest 全绿**
- [ ] **Step 3: 用 A55 实测一轮清单中可自动化的项，结果记台账**
- [ ] **Step 4: Commit** — `feat(app): debug player activity and device acceptance checklist`

---

## Self-Review 记录

- **Spec 覆盖**：§5.6"mpv 内核/格式通吃/字幕全档"→T1-T3；"音频直通探测+回退+手动锁"→T4（三态=手动锁的载体）；SMB/NFS 播放→T5-T8；ProbeClient→T9；spec §5.4"画质/规格探测"的文件头部分闭环。原盘蓝光菜单仍按计划 1 裁定后置。
- **Review Focus**：5 条钉死在 T2/4/5/7/8/10。
- **类型一致**：`EngineState/PlaybackOptions/TrackInfo/StreamSource/LoopbackStreamServer/PlaybackUriResolver/NfsFileClient/SourceType(+NFS)/ProbeClient` 各定义一次；core 与 :player 的依赖方向单向（:player → core）。
- **风险显式**：T1 是整份计划的闸门（AAR 验尸不过 → 停下来换 B 路线重议）；直通真机效果不达标不阻塞合并（按"实验性"处理），但验收清单必填 AVR 实听结论。
- **比例**：10 任务，设备测试为主、JVM 测试覆盖纯逻辑层。
