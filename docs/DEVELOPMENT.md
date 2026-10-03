# 开发 BronyaTV

## 构建

Linux x86_64、JDK 17、Android SDK 36 / Build Tools 36.0.0。使用 Kotlin、Compose for TV、Leanback、Media3 和 OkHttp。

```bash
bash scripts/bootstrap.sh
bash scripts/build_release.sh
```

签名文件由构建脚本在本地准备，不提交到仓库。发布包启用 R8 和资源压缩。`signing.properties` 指定本地签名密钥；已有发布升级应沿用原密钥。应用内部包名保持 `tv.ember.client`，对外名称为 BronyaTV。

TrueHD / MLP / DTS 的音频扩展包含已构建的四种 ABI 原生库，普通 APK 构建不需要 NDK。原生库采用 Media3 1.11.1 官方音频解码器和 FFmpeg 6.1.4，仅编译 TrueHD、MLP 与 DTS 的 `dca` 解码器；视频继续使用设备解码器。重新编译原生库时运行 `bash scripts/build_audio_decoders.sh`。脚本、NDK 版本、对应源码和许可见 [解码模块](../decoder-ffmpeg/README.md)。此兼容路径输出 PCM，不保留 TrueHD Atmos 或 DTS:X 对象元数据。

## 测试

```bash
source scripts/env.sh
python3 scripts/create_test_assets.py
python3 tests/mock_emby.py
./gradlew testDebugUnitTest lintDebug lintRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest
```

测试服务监听 8765，Android 模拟器通过 `10.0.2.2:8765` 访问。测试媒体由 FFmpeg 生成，账号是本地测试账号。官方 Emby 集成测试通过 `realEmby=true` 参数单独启用；默认跳过。

`EpisodeDeviceTest` 验证跨季切集、首尾跳过、取消片尾倒计时、自然连播、长按合并跳转以及实际视频和音频输出。`scripts/modern_release_smoke.py` 保留 1.2.0 的历史正式签名覆盖升级检查；1.4.0 使用新签名，需要卸载旧版。

磁盘与 DTS 的专项模拟器检查无需 Emby 测试服务。`DiskCacheDeviceTest` 验证真实 SimpleCache / SQLite 索引、文件预取、缓存命中、跳转复用和清空，以及单连接服务器忽略 Range 时的回退。`DtsAudioDeviceTest` 使用本地 AVC / DTS 文件验证 FFmpeg PCM 输出及快进恢复；未提供 `dtsFixture` 时跳过。启动模拟器后运行：

```bash
source scripts/env.sh
python3 scripts/create_dts_test_asset.py
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell -T "run-as tv.ember.client sh -c 'cat > cache/dts-avc.mkv'" < tests/assets/dts-avc.mkv
adb shell am instrument -w -r \
  -e class tv.ember.client.DiskCacheDeviceTest,tv.ember.client.DtsAudioDeviceTest \
  -e dtsFixture /data/user/0/tv.ember.client/cache/dts-avc.mkv \
  tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

调试包和发布包签名不同，应在独立测试模拟器安装调试包。此 DTS 测试使用生成的 DTS 核心音轨，不覆盖 DTS-HD MA、DTS:X、实体电视 HDMI 输出或高码率网络吞吐。

## 播放与诊断

快进后的恢复同时观察播放时钟和实际渲染帧，暂停、音频焦点抑制及正常网络缓冲不会触发恢复。检测到输出停滞时，每次跳转最多重建一次播放器并保留当前进度与播放状态。内存缓存达到目标而前向缓冲不足时，可临时使用目标容量四分之一、最多 4 MiB 的恢复余量；正常播放仍在目标容量处停止加载。

PlaybackInfo 提供片源版本和地址。接收服务端 URL、签名参数及必要请求头；静态原文件接口使用影片 ID 和 MediaSourceId。跨域重定向移除服务器认证头。分段接收使用独立 HTTP/1.1 连接，校验 Range、总长度及服务端文件版本信息，按序交给 Media3；不支持 Range 时回退单连接。协议参考 [Emby PlaybackInfo](https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html)。

原文件磁盘缓存使用全局单实例 SimpleCache、SQLite 索引和可调整容量的 LRU 淘汰器。独立预取线程按播放读取位置提前写入，单次请求最多 8 MiB，以 2 MiB 文件片段提交；前台 CacheDataSource 只读取缓存，缺失部分继续走原网络取流，避免前台对未下载文件的长期写锁阻塞预取。预取和前台使用独立 OkHttp Dispatcher，取消跳转前的预取不会取消当前播放。播放器关闭、切集或换源时先停止预取，再释放播放器。

提前量按服务器码率和设置的秒数计算，最多占磁盘容量的四分之三；未知码率使用 64 MiB。自动容量最多 512 MiB，按可回收缓存与剩余空间缩减，配置时保留 256 MiB，写入时空间低于 64 MiB 停止预取。预取失败会退避，播放仍可读取缓存或原网络；不支持 Range 的服务不持续预取。HLS / DASH 列表不使用此原文件缓存。

磁盘模式下普通码率的自动内存目标为最多 64 MiB，48 Mbps 及以上为最多 128 MiB，均受堆余量限制。分别限制前台与预取的分段接收队列，避免各自占用一整份内存预算。自动连接数在高码率原文件上可选 8 路；手动设置仍优先。自动预缓冲与重缓冲在高码率、非低内存状态下提高至至少 5 秒。每次创建播放器使用独立缓存标识，标识不包含账号或签名链接；不跨播放器重建复用文件，旧缓存由 LRU 或手动清理移除。

剧集相邻项从服务器的 Shows/{id}/Episodes 接口获取，保留服务端顺序并支持跨季，不使用影片名称猜测下一集。[Emby 剧集接口](https://dev.emby.media/reference/RestAPI/TvShowsService/getShowsByIdEpisodes.html)

诊断从 MediaCodec 输出格式、VideoSize、首帧事件和 AudioTrack 初始化配置采集实际播放数据。逐帧回调只在输出格式变化时解析，避免每帧分配。解码器输出尺寸与 Surface、显示模式分开；色彩传递、Dolby Vision 解码路径及 JOC 码流与显示器能力、接收设备最终模式分开。参考 [Media3 AnalyticsListener](https://developer.android.com/reference/androidx/media3/exoplayer/analytics/AnalyticsListener)、[AudioTrackConfig](https://developer.android.com/reference/androidx/media3/exoplayer/audio/AudioSink.AudioTrackConfig) 和 [Android HDR 播放](https://developer.android.com/media/grow/hdr-playback)。

界面使用深色影片背景、青色焦点、横向媒体卡片、固定导航侧栏和一致的间距，参考 [Apple 按钮设计](https://developer.apple.com/design/human-interface-guidelines/buttons) 与 [焦点导航](https://developer.apple.com/documentation/uikit/focus-based-navigation)，并保留 Android TV 原生遥控器交互。

## 源码

`app/src/main/java/tv/ember/client/` 按职责组织：`data` 模型与加密会话，`emby` 服务器接口，`network` 取流，`cache` 磁盘预取与淘汰，`player` 播放与内存缓冲，`monitor` 诊断，`settings` 设置，`ui` 界面。生命周期停止时释放播放器、网络任务和缓冲；海报使用限制并发的请求与有界内存缓存。

1.4.1 的导航、五列卡片边界、图片焦点、可见行位置及播放快捷入口检查见 `VisualNavigationDeviceTest`，搜索筛选和图片回退契约见 `BrowsePresentationTest`。可给 instrument 命令增加 `-e screenshots true`，将五个页面的实际截图保存在应用外部文件目录；内容来自本地测试服务器。

界面默认英文。`i18n/UiText.kt` 集中维护英文、简体中文文案，`{0}` 等参数在两种语言中保持一致；服务器名称、简介和输入的搜索词不翻译。`AppLanguage` 保存语言并给 Activity 设置对应资源配置，返回旧页面时自动重建；枚举标签使用 getter，避免切换后保留旧语言。XML 控件的辅助描述使用 `values` / `values-zh` 资源。`LanguageTest` 检查参数、格式说明符与动态内容保留，设备测试实际操作语言选择窗口并检查保存结果。

仅用于受限的软件模拟器：若系统 H.264 解码器出现 SSE 指令崩溃，可给 `VisualNavigationDeviceTest` 增加 `-e playbackSource vp8`，改用真实 VP8 / Vorbis 测试视频检查播放控制栏。默认仍使用 MP4 / H.264；此替代检查不能证明 H.264、HDR 或实体电视硬件输出已验证。

模拟器无法访问 `10.0.2.2` 时，可运行 `adb reverse tcp:8765 tcp:8765`，给上述三个界面测试类增加 `-e fixtureServer http://127.0.0.1:8765`。默认地址保持不变。


## 1.5.0 播放与 Compose 迁移

`TvLoadControl` 仅在 `markSeek()` 后使用短恢复阈值和有界 reserve，正常播放与 rebuffer 转发给 `DefaultLoadControl`。前台 `CacheDataSource` 为只读，不使用 `FLAG_BLOCK_ON_CACHE`；磁盘预取仍独占写入锁。`StreamTransferBudget` 在 HTTP 应用拦截器中统一限制前台、预取及单连接回退的总在途请求，前台等待时取消后台请求，后台按原重试策略恢复。

`ParallelRangeReader` 保留 Content-Range、长度及版本校验，按顺序输出已经接收的字节。生产分片最多 512 KiB，队列最多 N 个分片加一个正在消费的分片；完整分片缓冲循环复用，避免每次下载都分配大数组。两个取流队列仍共享原来最多 16 MiB 的内存预算。

高码率自动 allocator 目标为最多 128 MiB。预算从实际堆剩余空间扣除至少 48 MiB（或堆上限的 20%）的界面/解码器余量、16 MiB 取流队列及 8 MiB allocator 增长余量，并保留低内存降级。未启用 `android:largeHeap`：正常堆已可支持目标；增加堆标志不保证更多物理内存，且会改变低内存设备上的 GC 和进程回收行为。

音频 renderer 默认顺序为 MediaCodec、FFmpeg；TrueHD/MLP/DTS/DTS-HD 通过 sink 能力包装禁用该格式的 passthrough/offload，输出 PCM。厂商等价 MIME 名称加入查询，硬件 decoder 排在软件 decoder 前。音频 renderer 运行失败时保留播放位置/音轨设置，以 FFmpeg 优先重建一次；片源或剧集切换重置回退状态。诊断使用实际 decoder 初始化和 AudioTrack 输出事件，不根据源标签宣称硬解或 Atmos 输出。

`SettingsDashboard.kt` 是首个 Compose for TV 页面，使用 TV Material 按钮、显式三列 DPAD 焦点及 View 侧栏互操作。`SettingsActivity` 继续管理原有设置分类编辑页及持久化；返回总览恢复原卡片焦点。承载 Compose 的横向 LinearLayout 关闭 baseline 探测，避免首次测量产生无限高度。后续新增 TV 页面沿用 Compose，逐页替换现有 View/Leanback 页面，不改写播放器核心。

TrueHD/MLP 软件回退的 JNI 初始化会传入片源声道数与采样率；TrueHD seek 重建保留已解码的声道与采样率，避免单声道/立体声第一子流缺少 layout 而被丢弃。原有 Matroska 输入和 FFmpeg packet 读取逻辑保留。四种 ABI 均从现有 FFmpeg 6.1.4 源码重建。

`LosslessAudioDeviceTest` 强制设备 decoder 查询返回空，使用本地 TrueHD/DTS 音频验证 FFmpeg PCM 输出与 seek 后继续播放。安装上述调试 APK 后准备两个 12 秒测试文件；它们输出到被 Git 忽略的目录：

```bash
mkdir -p tests/assets
ffmpeg -y -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 -ac 2 -c:a truehd -strict -2 tests/assets/truehd.mka
ffmpeg -y -f lavfi -i sine=frequency=440:sample_rate=48000 -t 12 -ac 2 -c:a dca -strict -2 -b:a 1536k tests/assets/dts.mka
adb shell mkdir -p /sdcard/Android/data/tv.ember.client/files
adb push tests/assets/truehd.mka tests/assets/dts.mka /sdcard/Android/data/tv.ember.client/files/
adb shell am instrument -w -r -e class tv.ember.client.LosslessAudioDeviceTest tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

如设备的外部目录访问策略不同，可通过 `-e losslessFixtureBase <目录或 URL>` 指定包含两个文件的位置。此检查不代替 DTS-HD MA 或厂商硬解的真机验证。
