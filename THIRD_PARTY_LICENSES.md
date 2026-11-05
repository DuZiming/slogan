# 第三方开源组件声明（随应用分发）

Slogan 使用并感谢以下开源组件（按 Apache-2.0/LGPL 允许的方式动态链接或包含）：

| 组件 | 许可证 | 用途 | 来源 |
|---|---|---|---|
| libmpv（含 ffmpeg 套件） | LGPL-2.1+ / GPL 具见上游 | 播放内核（dev.jdtech.mpv:libmpv 预编译分发） | https://github.com/mpv-player/mpv |
| libnfs | LGPL-2.1 / BSD（双许可，取 LGPL 兼容路径） | NFS 客户端（动态链接，未修改；版本见 player/src/main/jniLibs/libnfs-version.txt） | https://github.com/sahlberg/libnfs |
| smbj | Apache-2.0 | SMB 客户端 | https://github.com/smbj/smbj |
| SQLDelight | Apache-2.0 | SQLite 持久化 | https://github.com/cashapp/sqldelight |
| kotlinx-coroutines | Apache-2.0 | 协程 | https://github.com/Kotlin/kotlinx.coroutines |
| Kotlin | Apache-2.0 | 语言与标准库 | https://kotlinlang.org |

LGPL 组件以动态链接（.so）方式使用，未做源码修改；应要求提供对应源码获取方式的上游链接。
