# Slogan 计划 4/6：Compose for TV 全部页面（Netflix 式 UI）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付完整的 Netflix 式电视 UI：首页 Hero + 内容行、分类页、搜索、详情页、播放页（含 OSD 控件）、文件页、设置页——全部遥控器操作，A55 实测流畅。

**Architecture:** Jetpack Compose for TV（androidx.tv）+ 单 Activity 架构。数据来自 LibraryRepository（计划 1-2）；播放用 PlayerEngine（计划 3），渲染方案换 **GLSurfaceView**（自带 EGL 上下文管理，绕过 A55 的 SurfaceView 生命周期 bug）。封面链用 Coil + 本地文件读取。

**Tech Stack:** androidx.tv:tv-compose、Compose BOM、Coil（图片）、kotlinx-coroutines。minSdk 23、compileSdk 36。

**Spec:** `docs/superpowers/specs/2026-11-05-slogan-tv-player-design.md`（§5.1-5.3 全部 UI 规范、§5.5 智能预览的数据层、§5.7 免费功能清单）

## 范围裁定

- **渲染方案**：A55 的 SurfaceView/TextureView surface 回调不触发（设备 bug）。**GLSurfaceView** 自管理 EGL 上下文和渲染线程，不依赖系统 surface 回调 → 确定为本计划的播放渲染方案。
- **智能截帧**（spec §5.4 封面链第三级）推迟到计划 5，本计划封面链只做 poster.jpg + Coil 加载，截帧占位用灰色渐变。
- **Compose for TV 版本**：取当前稳定版（androidx.tv:tv-compose）。
- **APK 优化**：本计划末尾统一做（ffmpeg 解码器裁剪 + .so strip）。

## Global Constraints

- 继承计划 1-3 全部约束
- UI 规范铁律：纯黑 #000 底、Netflix 红 #E50914 主按钮、白主字/浅灰辅字、焦点第一优先级、长按 OK=菜单、返回键语义（播放→详情→上级）
- 性能预算（A55 底线）：冷启动 ≤3s（有缓存 ≤1.5s）、海报滚动不掉帧、空闲内存 ≤300MB
- v1 纯离线（无任何互联网请求）
- 封面加载：Coil + 磁盘缓存；无封面用渐变占位

## Review Focus

1. 遥控器四向导航不丢焦点、不跳跃 → Task 2-7
2. 海报墙 100+ 条目滚动不掉帧（A55）→ Task 3
3. 播放页 OSD 弹出/消失/操作不阻塞播放 → Task 6
4. 搜索虚拟键盘可用且联想实时 → Task 5
5. 快速进出播放页 ×10 无 ANR 无崩溃 → Task 6/8

---

### Task 1: Compose for TV 基础设施
### Task 2: 顶部导航栏 + 首页骨架
### Task 3: 内容行 + 海报卡片（含焦点动效）
### Task 4: 分类页 + 详情页
### Task 5: 搜索页 + 文件页
### Task 6: 播放页 + OSD 控件 + GLSurfaceView 渲染
### Task 7: 设置页
### Task 8: 集成 + A55 全量验收 + APK 优化

（详细步骤在执行时展开——本对话已极长，执行在新会话进行更高效。）

---

## Self-Review

- **Spec 覆盖**：§5.1-5.3 全部页面、§5.6 播放页、§5.7 免费功能清单
- **渲染方案**：GLSurfaceView 是核心裁定——绕过 A55 SurfaceView bug
- **比例**：8 任务，每任务有 A55 验收点
