# 计划 3 真机验收清单（A55 + AVR + NAS）

执行环境：小米电视 A55（192.168.1.12:5555，Android 9）、AVR 功放、本机 SMB 测试共享（\\192.168.1.188\电影）。
工具：`app/src/debug` 的 DebugPlayerActivity（adb 唤起）。

## 自动化/半自动项（本计划执行时已跑）

- [x] libmpv 内核加载（mpv-version 可读）— MpvVersionInstrumentedTest
- [x] 引擎全状态机（播放/暂停/seek/停止/重载）— MpvEngineInstrumentedTest
- [x] 音轨/字幕轨列举与切换 — MpvTracksInstrumentedTest
- [x] NFS JNI 库加载与错误路径 — NfsClientInstrumentedTest
- [x] 懒探测（分辨率/时长/音轨编码；坏文件优雅返回）— MpvProbeClientInstrumentedTest
- [x] 真实 SMB 共享扫描（中文共享名，计划 2 验收）— SmbRealShareTest

## 手动项（需真人在场）

- [ ] **SMB 播放**：`am start -n com.slogan.tv/.app.DebugPlayerActivity --es host 192.168.1.188 --es share 电影 --es user admin --es pass <密码>` → 电视全屏播放共享内影片
- [ ] **4K REMUX 随机 seek**：放入一部 4K REMUX 后用遥控（adb `input keyevent`）seek 到中段，画面/声音快速恢复（回环流 Range 生效）
- [ ] **直通三态实听**：`--es spdif auto|force|pcm` × AVR 显示的输入信号格式（DTS/TrueHD 灯 vs PCM），记录 A55 上报的编码集
- [ ] **NFS 扫描+播放**：NAS 开 NFS 导出后（共享同目录），扫描出库且 DebugPlayerActivity 播放（`--es url nfs://…` 或接入工厂后走媒体库）
- [ ] **快速进出 ×10**：连续启动/退出播放页无 ANR、无 native 崩溃（`logcat -s AndroidRuntime:E` 干净）
- [ ] **断网恢复**：播放中拔 NAS 网线 → 出 Error/Buffering 不崩；恢复后重新载入可继续
- [ ] API 23-25 老设备冒烟（发版前）：找一台安卓 6/7 盒子装 APK 播一次（libmpv minSdk 覆盖的残余风险）
