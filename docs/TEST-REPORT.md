# Ember TV 交付与测试记录

测试环境：Linux 云服务器，2 核/2 线程 AMD EPYC，15.6GiB 内存，NVMe。JDK 21、Gradle 8.13、AGP 8.13.2、Kotlin 2.3.21、SDK/Build Tools 36、Media3 1.11.1。Gradle Worker=2，堆=6078MB，Kotlin 堆=1919MB；Kotlin JVM 后端线程数与 Worker 数联动。并行构建、增量编译、构建缓存、配置缓存均开启，缓存及临时文件在 NVMe 工作盘。最终输出是独立签名、经过 R8/资源压缩的通用 Release APK。

## 最终结果

2026-10-02 最终构建成功，耗时 2 分 2 秒。15 项单元测试（5 项缓冲策略、10 项 Emby API）和 12 项 Android TV 设备测试全部通过，失败、错误、跳过均为 0。设备测试包含官方 Emby Server 4.10.1.0 的登录、Token 身份发现、浏览及 3 个版本的 Direct Play；另有连续 HTTP 503 恢复、字幕/音轨、软件解码降级注入和实际缓冲字节上限验证。XML 和完整设备日志在 `test-results/`。

最终签名 Release 在 Android TV 模拟器实际安装并验证登录、D-pad 导航、详情、3 个版本选择、内置播放、性能 OSD、隐藏调试与认证参数遮盖、双音轨菜单，以及 Just Player 交接。Just Player 的系统媒体会话报告 PLAYING，客户端 service 查询为 `(nothing)`。界面截图和 `test-results/release-ui-smoke.json` 保存了验证结果。动态 OSD 曾导致 UiAutomator 层级观察超时，随后分段完成层级与截图检查；该观察工具异常不是应用崩溃。

- APK：`EmberTV-1.0.0-release.apk`，2,357,302 字节（约 2.3MB）。
- SHA-256：`4688d5dbddf00a0f8c4a97fe8003c642d4735d8f5bb2e9d20cdb6ce8c134904c`。
- 签名：RSA 3072，v1/v2 验证通过；ZIP 对齐验证通过。
- 包名：`tv.ember.client`；版本：1.0.0；最低 API 23，目标 API 36；未打包原生 `.so`。
- 编译日志：`release-build.log`；Debug/Release lint 均通过（0 个错误）。

## 验证范围

| 要求 | 实现与证据 |
| --- | --- |
| 创建项目、安装环境、下载依赖、编译 Release | `app/` 项目及 Gradle Wrapper；`logs/environment-install.log`、SDK 安装日志、`release-build.log` |
| Android TV / D-pad / 大屏 / 海报 / 动画 | Leanback Rows/ImageCardView、焦点缩放、TV 启动器 banner；Release 遥控器测试及界面截图 |
| ARM32 / ARM64 | APK 内无 `.so` 或 ABI 安装限制；使用系统 MediaCodec，可在这两类 Android TV 安装。实体 ARM 设备尚未接入测试 |
| 密码、Token、保存状态、自动登录 | Emby API 契约测试、设备加密会话测试、真实 Emby Server 登录及 Token 测试；Release 实际登录 |
| 首页、列表、详情、播放地址、多个版本 | 服务器浏览/搜索/分页；MockWebServer 偏移/总数验证；真实 Emby 的 3 个版本、Release 版本选择截图 |
| 优先 Direct Play / 不主动转码 | PlaybackInfo 显式禁止转码；`Static=true` 地址；单元测试和官方 Emby Server 3 个版本真实播放 |
| MP4 / MKV / H.264 / H.265 / HDR | 设备实际解码 MP4 AVC、MKV AVC、MKV HEVC 10-bit/PQ；仅验证解码和播放，未验证实体电视的 HDR 亮度/色彩/4K性能 |
| 硬件解码与软件降级 | MediaCodec 按硬件优先排序并启用解码器 fallback；终止解码错误时重建为软件候选。模拟器实际使用 `OMX.google.h264.decoder`；注入解码错误后重新选择系统软件解码器并保持位置的设备测试通过；真实 ARM 硬件故障未做实机注入 |
| SRT / ASS | 外置 SRT 与 MKV 内嵌 ASS 实际 cue 文本断言；MKV 两个字幕轨道；复杂 ASS 特效不保证 |
| 标题、进度、字幕、音频信息 | Media3 控制栏/SubtitleView、标题栏和音频编码、轨道菜单；设备播放/字幕测试和 Release 截图 |
| 自动/低延迟/平衡/大缓存、512MB/1GB/2GB、5/15/30/60秒 | 参数组合测试覆盖全部 48 种；保存设置、`BufferPolicy` 安全内存上限、`TvLoadControl` 实际 allocator 上限测试 |
| 自动重连、恢复位置和缓冲 | 注入连续 5 次 HTTP 503，实际重新进入 READY 并从 ≥10秒位置继续；离开/返回页面释放并恢复播放器 |
| 视频/音频/字幕轨道 | 支持按类型手动覆盖/自动/关闭；设备测试实际字幕及中文音轨切换，Release 轨道菜单截图 |
| 内置 / VLC / MX / Just Player | 安装检测、显式包名 Intent；Release 实际安装 Just Player 0.217 并完成交接。VLC/MX 依包名及 Intent 契约实现，未安装这两者实测 |
| 性能 OSD | 仅播放页约每秒采样；CPU/频率/系统内存/APP PSS/码率/缓冲/分辨率/编码/解码/速度/连接状态；Release OSD 截图和设备 UI 断言 |
| 隐藏调试模式 | 关于页 7 次点击开启本次进程调试；显示 URL/CDN/HTTP/缓冲趋势/状态/掉帧/解码器，认证参数遮盖 |
| 轻量、没有后台服务、低内存、禁止无关功能 | Release 约 2.3MB；Manifest 不声明 service/存储访问；`logs/no-background-services.txt` 为 `(nothing)`；没有本地扫描、下载/离线缓存、播放列表、元数据抓取或 NAS 模块 |
| 可安装 APK、日志、功能、限制、安装方法 | APK、SHA256SUMS、构建日志、此报告、源码及 README |

## 限制

没有用户第三方服务的地址/账号，因此没有测试该提供商的 CDN、防盗链或特殊 API 扩展。官方 Emby Server 4.10.1.0 使用独立测试账号和 FFmpeg 生成的测试视频，模拟器为 Android TV 11 / API 30 x86 + KVM。

缓冲 512MB/1GB/2GB 是请求上限，实际按电视堆空间自动下调，并非保证每台电视可分配这么多 RAM。软件解码依赖系统 MediaCodec，没有捆绑通用 FFmpeg 解码器。外部播放器的 HTTP 头、字幕、继续位置和进度返回取决于其自身接口。CPU 受限时回退到 APP 使用率，频率不可读或报告零值时显示受限。OSD 的码率取当前轨道/服务器声明，不是对整个视频文件实时平均测量。

签名私钥保存在当前工作区 `signing/` 和 `signing.properties`，不会进入源码包；后续同包名覆盖更新须沿用此签名。
