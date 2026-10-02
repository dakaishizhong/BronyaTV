# BronyaTV (Ember TV)

轻量 Android TV Emby 播放客户端。Kotlin + Leanback + AndroidX Media3 ExoPlayer。最低 Android 6.0 / API 23，目标 API 36。Release 开启 R8 和资源压缩。

## 安装与使用

当前版本为 1.0.3。发布 APK 可在仓库 Releases 中提供；本地构建产物位于 `artifacts/EmberTV-1.0.3-release.apk`。通用 Java/Kotlin APK，没有限定 ABI 的原生库，同一个 APK 可在 `armeabi-v7a` 和 `arm64-v8a` Android TV 安装。

电视开启开发者选项和 ADB 调试，在配对/连接设备后运行：

```bash
adb connect <电视IP>:5555
adb install -r artifacts/EmberTV-1.0.3-release.apk
```

也可把 APK 复制到电视，用系统安装器安装，按系统提示允许该安装器安装未知来源应用。应用会出现在 Android TV 启动器中。

1.0.1 修复厂商电视桌面和系统安装器的启动兼容性：同时提供普通 `MAIN/LAUNCHER` 与 TV `MAIN/LEANBACK_LAUNCHER` 入口，并允许没有声明 Leanback 系统特性的电视安装。旧版只有 TV 入口，使用普通 Android 启动接口的桌面/安装器无法找到应用。新版本沿用原签名，可直接覆盖 1.0.0，保留登录和设置。电视桌面刷新应用列表后，在“全部应用/我的应用”查找 Ember TV；是否自动固定到桌面首页由该桌面决定。Android 13 验证范围和 Skyworth 实机确认状态见 `docs/LAUNCH-FIX-1.0.1.md`。

1.0.2 修正播放地址：优先使用 PlaybackInfo 返回的 DirectStreamUrl，按 AddApiKeyToDirectStreamUrl 添加认证参数，保留服务器签名、RequiredHttpHeaders 和端口；没有直连地址时使用所请求影片的 ID、片源原始容器生成 `original.<容器>?Static=true` URL，并以 MediaSourceId 选择版本。HTTP 协议片源支持服务器返回的远程 Path；不会仅因为 CDN 地址包含 `master.m3u8` 或 `transcod` 就丢弃它。遇到视频 HTTP 401/403/404/410 会重新获取一次播放地址并保留位置；404/410 重新获取后仍不可用时，再通过 Emby 原文件接口请求同一个片源一次。不会直接改写 CDN 签名 URL 或把所有 MP4 改成 MKV。持续失败时显示具体状态，避免反复请求。播放菜单“重新连接”也重新获取地址。失败的外置字幕会跳过，继续播放视频。

1.0.3 增加可选的 2/4/8 路独立 TCP 分段接收，用于高延迟、单连接接收窗口受限的链路。设置 →“网络接收缓冲”保留系统自动，设置 →“分段接收”选择 4 路；退出播放后重新打开同一片源，必要时比较 8 路。设置默认单连接。HTTP/1.1 分段预取按位置有序交给 Media3，校验 Content-Range、大小及服务器提供的 ETag/Last-Modified；不支持 Range 时回退单连接，片源变化时停止合并。额外预取上限为 16MiB、最大堆的八分之一、剩余堆的八分之一中的最小值；低内存时使用单连接。只在播放期间使用内存。

播放菜单新增“播放诊断详情”，普通模式即可打开、刷新及复制。提供片源容器/Profile/位深/帧率、实际视频和音频输入格式、实际 MediaCodec 与系统声明的硬件/软件属性、输出帧率/丢帧/音频欠载、缓冲次数和趋势、当前/均值/峰值下载速率、预取与播放内存、实际 HTTP 协议/IP/响应时间/Content-Range/同时连接数，以及可读取的系统 TCP 配置。不可读的内核数值明确显示受限；SO_RCVBUF 系统报告值与 TCP 广告窗口分开说明。复制报告隐藏全部 URL 查询值、用户信息和片段。

输入完整 Emby 地址，例如 `https://server.example.com/emby`；反向代理路径会保留。支持用户名/密码或 Token。用户 Token 会通过服务器 `/Sessions?DeviceId=…` 发现用户身份；服务器 API Key 需要指定用户 ID。密码不保存，会话用 Android Keystore 的 AES-GCM 加密，系统备份关闭。再次打开应用自动使用保存的会话。

遥控器方向键移动焦点，确定键打开分类/详情、选择版本或操作播放器；播放器菜单键打开播放选项。没有菜单键的遥控器可在播放控制栏显示时选中顶部“播放选项”。播放控制栏可暂停、快进、快退、调整进度及字幕。设置中的“关于 Ember TV”连续按 7 次启用本次进程的高级调试模式，再点击调试开关可关闭。

## 已实现

- 服务器首页：继续观看、最近添加、服务器分类；分类/剧集/季导航、搜索、40 项分页、影片详情、海报及片源版本选择。
- Direct Play 优先：协商明确 `EnableTranscoding=false`、`EnableDirectStream=false`；静态原文件播放。接收 CDN URL 和必要 HTTP 请求头。跨域重定向移除服务器认证头。
- Media3：MP4/MKV、H.264/H.265；HDR 信息交给设备视频解码器和 Surface；硬件解码优先，初始化失败尝试其他 MediaCodec，终止解码错误后尝试设备的软件 MediaCodec。
- 内嵌/外置 SRT、ASS/SSA 字幕；播放时手动切换视频、音频、字幕轨道；显示标题、播放进度和音频信息；上报 Emby 开始/进度/停止，保留继续观看位置。
- 自定义 `TvLoadControl`：自动、低延迟、平衡、大缓存；512MB/1GB/2GB 请求上限；5/15/30/60 秒预缓冲；重缓冲恢复、指数退避重连、网络恢复时立即重试，保持原播放位置。
- TCP 接收缓冲：系统自动（默认）或 256/512/1024/2048/4096KB，在新播放连接上设置 SO_RCVBUF，无需 root；OSD 显示最近连接当前系统报告值与请求值。Linux 手动设置会关闭该连接自动调节，1.0.3 首次升级时将旧手动值恢复自动，之后仍可手动选择。
- 内置播放器及安装检测后的 VLC、MX Player 免费/专业版、Just Player 调用；默认选择及每次播放选择。
- 默认关闭的半透明性能 OSD，约每秒刷新；CPU 使用率/可读频率、系统已用/可用内存、APP PSS、视频码率/分辨率/编码、音频编码、缓冲时间/内存、解码方式、实际传输速度、网络状态。
- 隐藏调试显示取流主机/文件、HTTP 状态和解码器；完整诊断在普通播放菜单中查看，地址查询值全部遮盖。
- 没有后台服务、下载、设备存储扫描、离线缓存、播放列表、元数据抓取、NAS 管理或本地媒体库；海报只使用有界内存缓存。

## 缓冲与兼容性边界

缓存大小是**请求上限**，不是预分配容量。实际播放缓冲不超过请求值、剩余堆空间的三分之一、应用最大堆的四分之一；低内存设备降至堆的八分之一。按需分配，达到安全字节上限时允许提前开始播放，避免选 60 秒预缓冲后一直无法开始。设置页和 OSD 显示实际安全上限。不存在用于补足 1GB/2GB 的磁盘/离线缓存。

设置 →“网络接收缓冲”调节每条播放 TCP 连接的接收缓冲，改变后重新打开视频生效。这与 Media3 的预缓冲时间、视频内存缓冲是不同设置。SO_RCVBUF 是普通应用可以设置的建议值，系统上限和实现可能使报告值小于或大于请求值；不会修改内核全局参数。默认使用系统自动，手动设置会关闭该连接的 TCP 自动调节，受系统上限限制。分段接收通过多条独立 TCP 连接提高总在途数据量，单条连接的内核上限仍由系统控制。应用不能在无 root 条件下修改全局 TCP 上限。手动调节不保证提高速度，也不能修复 HTTP 410。HTTPS 默认端口为 443，无需手动补 `:443`；服务器返回非默认端口时原样使用。

H.265、4K、HDR 输出及硬件解码性能受电视解码器、显示器和 Android 固件限制。软件降级使用**系统提供的 MediaCodec**，没有捆绑 FFmpeg/libavcodec，不能为缺少对应软件解码器的设备凭空增加解码能力；这种情况可改用已安装的 VLC/MX/Just Player。ASS 使用 Media3 的字幕能力，复杂排版、特效和字体附件不等同于完整 libass 渲染。服务器明确禁止 Direct Play、仅允许转码，或需要开启直播会话的片源，会显示错误，不自动启动转码。

CPU 全局统计或频率读取受系统限制时显示 APP CPU 使用率或“受限”，不显示假数据。片源总码率取服务器声明值，轨道格式取实际解码输入；下载速度来自实际 HTTP 数据读取计数，含分段预取和等待时间。缓冲充足时播放器会停止取流，0 MB/s 不代表断网。HTTP 请求至响应头时间包含服务器处理等时间，不能作为 RTT。外部播放器是否接受自定义 HTTP 请求头、字幕和返回进度由对应播放器决定；本客户端没有接管外部播放器 OSD/轨道/进度上报。

1.0.3 验证结果见 `docs/NETWORK-FIX-1.0.3.md`，1.0.2 播放地址修复见 `docs/PLAYBACK-FIX-1.0.2.md`，初版范围见 `docs/TEST-REPORT.md`。用户已确认 Skyworth A6E 实机启动、登录及片源播放恢复；系统自动和请求 4096KB 均约 2.7MB/s，服务端观测约 520KB 广告窗口与约 180ms 延迟。1.0.3 的多连接方案已在模拟器与测试服务器验证，尚需用户电视和实际链路测量速度；测试限速服务的改善比例不能直接当作电视结果。未直接访问用户的第三方服务，也未验证实体 ARM 电视的 4K/HDR 输出。

## 构建

Linux x86_64 一键准备环境（需要可用 sudo 和网络）：

```bash
bash scripts/bootstrap.sh
bash scripts/build_release.sh
```

环境会安装 JDK、Android SDK/Build Tools，Gradle Wrapper 按校验和下载 Gradle 8.13，依赖自动下载。`scripts/configure_resources.py` 读取 CPU affinity、cgroup 配额和实际内存，生成并行、构建缓存、配置缓存、Worker/JVM/Kotlin 设置；Gradle 缓存及临时目录置于项目的 `tools/`，当前工作盘是 NVMe。

包内不含发布私钥。首次在另一台机器运行 bootstrap 会生成新签名；若要覆盖安装此次发布的 APK，需安全保留当前服务器的 `signing/ember-release.p12` 和 `signing.properties`，沿用同一签名。签名信息不会放入源码压缩包。

## 测试

```bash
source scripts/env.sh
./gradlew testDebugUnitTest lintDebug lintRelease
sudo apt-get install -y ffmpeg python3-pil
python3 scripts/create_test_assets.py
python3 tests/mock_emby.py
```

在另一个终端启动 Android TV 模拟器后：

```bash
source scripts/env.sh
sdkmanager 'emulator' 'system-images;android-30;android-tv;x86'
printf 'no\n' | avdmanager create avd -n EmberTV_API30 -k 'system-images;android-30;android-tv;x86' --device tv_1080p
emulator -avd EmberTV_API30 -no-window -no-audio -gpu swiftshader -memory 2048
./gradlew connectedDebugAndroidTest
```

测试服务监听 8765，模拟器通过 `10.0.2.2` 访问。测试资源由 FFmpeg 生成，不随 APK 打包。真实 Emby 集成测试按需通过 `-Pandroid.testInstrumentationRunnerArguments.class=tv.ember.client.RealEmbyServerTest -Pandroid.testInstrumentationRunnerArguments.realEmby=true` 启用，使用本地独立的官方服务器测试实例与生成的视频。未运行该实例时，该测试跳过；不影响客户端连接用户服务器。

## 源码结构

`app/src/main/java/tv/ember/client/` 按职责分包：`data/` 模型及加密会话、`network/` HTTP、`emby/` Emby 接口、`player/` 播放/缓冲/重试/外部调用、`monitor/` CPU/内存/网络/播放器统计、`settings/` 配置、`ui/` 电视界面。

## 研究资料

实现参考 [Android TV 创建应用与启动器要求](https://developer.android.com/training/tv/get-started/create)、[TV 大屏交互与焦点指南](https://developer.android.com/develop/adaptive-apps/guides/tv/build-adaptive-apps-for-tv)、[Media3 官方发布说明](https://developer.android.com/jetpack/androidx/releases/media3)、[Media3 支持格式](https://developer.android.com/media/media3/exoplayer/supported-formats)、[AGP 8.13 兼容矩阵](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Emby PlaybackInfo 协议](https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html)、[静态视频接口](https://dev.emby.media/reference/RestAPI/VideoService/getVideosByIdStream.html)、[Emby Session 用户识别](https://dev.emby.media/reference/RestAPI/SessionsService/getSessions.html)、[Emby 字幕接口](https://dev.emby.media/reference/RestAPI/SubtitleService.html)。

TCP 行为参考 [Linux 内核 TCP 参数说明](https://docs.kernel.org/networking/ip-sysctl.html#tcp-variables)；SO_RCVBUF 的手动设置会关闭该连接的自动接收缓冲调节，不能绕过系统 rmem_max。
