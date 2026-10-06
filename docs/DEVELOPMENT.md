# 开发 BronyaTV

## 构建

Linux x86_64、JDK 17、Android SDK 36 / Build Tools 36.0.0。使用 Kotlin、Compose for TV / TV Material、Media3 和 OkHttp。

```bash
bash scripts/bootstrap.sh
bash scripts/build_release.sh
```

签名文件由构建脚本在本地准备，不提交到仓库。发布包启用 R8 和资源压缩。`signing.properties` 指定本地签名密钥；已有发布升级应沿用原密钥。应用内部包名保持 `tv.ember.client`，对外名称为 BronyaTV。

1.7.4 使用新的正式签名；更早版本需要先卸载。后续版本沿用 1.7.4 密钥。请离线保存私有签名备份，密钥及密码不得提交源码或上传 GitHub Releases。

后续 Git 作者与提交者使用 `dakaishizhong` 及 GitHub 隐私邮箱。准备并验证 `releases/vX.Y.Z/` 文件后，推送发布提交与标签，再由该账号创建对应发行版。`release-apk.yml` 只向该账号创建的发行版上传附件；不存在发行版时等待账号创建，不自动创建 bot 署名的发行版。更新 `releases/CURRENT_VERSION` 后，可在 Actions 手动运行此工作流，或执行 `gh workflow run release-apk.yml --ref main` 上传当前版本。

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

原文件磁盘缓存使用全局单实例 SimpleCache、SQLite 索引和可调整容量的 LRU 淘汰器。前台与提前缓存共用 Range 调度器和下载数据；`DiskPrefetcher` 负责异步存储，不再另建下载器。前台不等待磁盘写锁，缺失或锁定部分立即读取网络；已有验证通过的缓存优先复用。后台只有前台未使用的连接和磁盘提前窗口空间时才领取任务，前台需求增加时停止领取新任务，当前任务可完成后暂停。seek、切集和换源立即取消旧位置请求，并使旧代次写入失效。

提前量按服务器码率和设置的秒数计算，最多占磁盘容量的四分之三；未知码率使用 64 MiB。自动容量最多 512 MiB，按可回收缓存与剩余空间缩减，配置时保留 256 MiB，写入时空间低于 64 MiB 停止预取。预取失败会退避，播放仍可读取缓存或原网络；不支持 Range 的服务不持续预取。HLS / DASH 列表不使用此原文件缓存。

磁盘模式下普通码率的自动内存目标为最多 64 MiB，48 Mbps 及以上为最多 128 MiB，均受堆余量限制。Range 数据和保留的磁盘写入副本共用最多 32 MiB 预算；分片和前向窗口根据码率、连接数及可用堆空间缩减，低内存设备降为单路。自动连接数在高码率原文件上可选 8 路；手动设置仍优先。自动预缓冲与重缓冲在高码率、非低内存状态下提高至至少 5 秒。每次创建播放器使用独立缓存标识，标识不包含账号或签名链接；不跨播放器重建复用文件，旧缓存由 LRU 或手动清理移除。

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


## 1.6.0 界面与数据缓存

全部应用页面、侧栏、设置编辑、播放控制和弹窗正文使用 Compose TV，播放器仅保留 PlayerView 的 Surface/字幕互操作。原取流、解码、缓存预取、续播和字幕核心继续使用。`TvDialog` 使用 AndroidX ComponentDialog 提供生命周期与窗口返回分发，遥控器重复键过滤和系统返回手势共用 dispatcher。

首页以最近播放/继续观看开头，随后展示 Emby User Views 返回的前六个实际媒体库及各自 ParentId 范围的最新内容，按服务器顺序排列，标题直接进入原目录；没有返回媒体库时才使用全局最近添加。其余媒体库保留入口。电影/剧集页默认只有影片和相应服务器媒体库入口，筛选收进一个可展开的“筛选与排序”入口，选择即更新，不要求额外应用。播放进度条下采用圆形图标，固定尺寸避免焦点引起布局跳动。

媒体库来自 [UserViews](https://dev.emby.media/reference/RestAPI/UserViewsService/getUsersByUseridViews.html)，分区内容来自支持 ParentId 的 [Latest](https://dev.emby.media/reference/RestAPI/UserLibraryService/getUsersByUseridItemsLatest.html)。媒体库入口直接使用返回的 ID 查询，不依赖聚合视图 Type 是否等于 CollectionFolder。

分类能力来自 Emby 官方接口：[ItemsService](https://dev.emby.media/reference/RestAPI/ItemsService/getUsersByUseridItems.html)、[Genres](https://dev.emby.media/reference/RestAPI/GenresService/getGenres.html)、[Years](https://dev.emby.media/reference/RestAPI/TagService/getYears.html)。使用 ParentId、IncludeItemTypes、Genres、Years、IsPlayed、Filters、SortBy、SortOrder、StartIndex 和 Limit，服务器完成条件与分页。Genres/Years 按用户、媒体库与媒体类型查询并分页获取，不支持的端点不展示相应选项。无筛选的文件夹查询不强制递归，保留媒体库层级。

`ImageCache` 独立磁盘 LRU 默认 256 MiB，支持 0 或 64–1024 MiB；按实际尺寸和 ImageTag 加载，三路并发并合并相同请求，离开页面后取消无观察者工作。停止的页面释放 bitmap 引用并取消请求；返回时从内存/磁盘恢复。解码内存为 heap/32，限制 2–12 MiB，播放时最多 3 MiB，内存压力时最多 2 MiB。`MetadataStore` 独立 24 MiB，四路并发合并请求，仅允许描述性字段；UserData、临时地址、鉴权头和播放会话均不落盘。两个缓存均按规范化服务器与用户命名空间隔离。动态进度取服务器最新数据，停止报告与刷新之间由 30 秒、最多 128 条的内存记录衔接。

新增 `BrowseCacheTest` 覆盖服务器请求、分页、缓存隔离/淘汰、敏感字段排除、请求合并取消与临时进度。`ComposeMigrationDeviceTest` 检查登录密码不恢复、版本直选/失效处理、分类分页和文件夹返回、横向焦点恢复、图片尺寸和缓存独立清理。既有界面测试已改用 Compose semantics，PlayerView 测试仍检查真实播放器。上述界面/剧集/按键检查也接受 `playbackSource=vp8`，用于 SSE 限制的软件模拟器；它不代替 H.264/HDR 真机验证。

片尾回归还修正了精确 EOF 的 Range 416：仅在服务器返回合法 `Content-Range: bytes */N` 且请求位置等于 N 时按空读取成功处理；未知长度和越界位置仍失败，与 Media3 HTTP 数据源语义一致。`ParallelRangeReaderTest` 覆盖两类情况，原分段接收实现和解码二进制保持。

外部字幕文本与 PlaybackInfo 使用控制传输，视频前台和预取仍共享原有 1/2/4/8 媒体取流预算。这避免单连接视频响应在缓冲暂停时长期占用唯一许可，导致已选外部字幕无法加载；`TvIntegrationTest` 验证单连接/四连接 MP4 的真实 SRT cue、音轨切换与生命周期。媒体流总并发策略没有放宽。

本地签名包流程可用 `python3 scripts/compose_release_smoke.py --apk releases/v1.6.0/BronyaTV-1.6.0-release.apk` 在专用测试模拟器上复现；默认 serial 为 emulator-5556，需先启动 tests/mock_emby.py。脚本在该模拟器卸载测试包并安装签名包，通过原生按键检查登录、分类、版本直选、播放 Menu/Back、设置和搜索，将英文截图保存到 artifacts/compose-release。TV Material 按钮通过 OK 激活；输入框 Up/Down 显式调用 Compose 焦点移动，避免编辑器截获方向键。

## 播放控件视觉回归（1.6.1）

`PlaybackUiDeviceTest` 在真实 Media3 播放期间检查播放/暂停键位于屏幕正中、左右键遍历六个控件、焦点不会改变按钮位置、OK 暂停/继续和进度条跳转。移除扬声器快捷入口后，音轨仍可从播放菜单选择。详情及全部设置编辑类别没有左上角返回按钮，遥控器 Back 返回后恢复原焦点。

签名包原生输入检查及英文截图（脚本需要 Python 3 和 Pillow）：

```bash
python3 scripts/compose_release_smoke.py \
  --apk releases/v1.6.1/BronyaTV-1.6.1-release.apk --version 1.6.1 \
  --output artifacts/compose-release-1.6.1
```

脚本会在专用模拟器卸载旧的测试安装，再安装签名包。使用本地 Emby 测试服务，不代表实体电视或生产服务器验证。


## 1.7.0 搜索、页面复用与公开测试素材

视频卡片统一 16:9；播放栏七个同尺寸按钮以播放/暂停为中心排列，两侧各三个，DPAD 按视觉顺序移动，无音量快捷按钮或页面左上角返回按钮。播放诊断仍可从 More 进入，也可使用控制栏信息图标。

`SearchInput` 合并 300 ms 内的输入，确认与 IME Done 立即提交；NFKC 规范化全角字符并合并空白，保留中文、重音及词边界。空查询不请求影片或全局筛选；过期查询取消并检查请求版本。使用官方 ItemsService 的 SearchTerm，未选排序时省略可选 SortBy/SortOrder，由上游决定搜索顺序，不猜测 Relevance 枚举。显式排序及类型/年份等条件保留到每一页。类型/年份只在展开筛选后获取，同一媒体库/类型复用选项。

返回 15 秒内完成加载的页面时，保留卡片、滚动和焦点并应用实时进度；超时后台刷新，菜单刷新不受限制。列表 Fields 仅请求 MediaSources,Genres，详情仍获取完整元数据。ImageCache 将编码图片磁盘键与显示尺寸区分，同一图片 URL 的多个显示尺寸共用一份编码数据，内存解码键仍包含尺寸；保持三路有界加载及原内存预算。

公开测试图片、来源、许可、SHA-256 见 [tests/media](../tests/media/README.md)。`python3 scripts/create_test_assets.py` 无需下载即可生成慢缩放视频、双音轨和 SRT/ASS；素材指纹改变时替换旧生成文件。`scripts/create_dts_test_asset.py` 同样使用公共领域电影剧照，音频为原始测试信号。HEVC 的 PQ 标记用于路径回归，不作为 HDR 效果参考片。

`SearchInputTest` 覆盖输入合并、即时提交、取消、中文/全角和服务器排序分页；`SearchDeviceTest` 检查空页不请求筛选、自动搜索、过期慢响应、历史、媒体范围及页面复用。`PlaybackUiDeviceTest` 同时检查按钮直径、轴线与镜像间距。签名包检查脚本示例：`python3 scripts/compose_release_smoke.py --apk releases/v1.7.0/BronyaTV-1.7.0-release.apk --version 1.7.0`。

## 1.7.1 Cinema UI 与遥控器修复

当前界面遵循用户提供的 Cinema UI 文档。主页无播放/详情操作栏，主页及分类无右上角按钮；侧栏展开时移动内容。登录保留用户卡片及凭据表单；播放器采用 44/56/44 基准 dp 的三个圆形按钮，播放按钮横向居中，字幕/音轨/画幅独立靠右。详细实现和原生逐键结果见 [Cinema UI](CINEMA-UI-VALIDATION.md) 与 [遥控器验证](REMOTE-DPAD-VALIDATION.md)。

后续示例统一使用 [cinema-reference.json](../tests/media/cinema-reference.json)。第一次准备素材时 `scripts/create_test_assets.py` 下载文档中的图片，再生成测试视频；已有素材复用本地文件，测试资源不进入 APK。

1.7.1 使用新的正式签名。私有密钥及 `signing.properties` 不上传 GitHub，后续版本必须保留并沿用。当前原生遥控器测试使用 Debug 应用和匹配的设备测试 APK：

```bash
adb reverse tcp:8765 tcp:8765
adb shell am instrument -w -r \
  -e class tv.ember.client.NativeRemoteNavigationDeviceTest \
  -e fixtureServer http://127.0.0.1:8765 \
  tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

正式签名包与 Debug 包签名不同，使用独立测试模拟器安装。1.7.1 正式包已完成安装、登录与实际播放检查，完整范围见 [发布验证](../releases/v1.7.1/VALIDATION.md)。历史版本脚本保留历史验证用途。


## 1.7.2 播放按钮和多路验证

示例服务器继续使用设计文档资源。启动 `tests/mock_emby.py`，安装 `assembleDebug` 与 `assembleDebugAndroidTest` 输出，执行 `adb -s emulator-5556 reverse tcp:8765 tcp:8765` 后：

```sh
adb -s emulator-5556 shell am instrument -w -r \
  -e class 'tv.ember.client.PlaybackFunctionalDeviceTest,tv.ember.client.NativeRemoteNavigationDeviceTest#playerAllDirectionsReachEveryControlAndReturnFromPanels,tv.ember.client.NativeRemoteNavigationDeviceTest#settingsQuickControlsTilesEditorAndSidebarUseDirections,tv.ember.client.PlaybackUiDeviceTest#documentControlsOperateTheRealPlayerAndTrackPanels' \
  -e fixtureServer http://127.0.0.1:8765 -e testLanguage zh \
  -e captureFolder player-172-remote \
  tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

新增功能用例检查实际 Media3 状态、章节和时间轴位置、音轨/字幕禁用与恢复、画幅循环、HUD 开关、同一面板内参数保存、原位置重连接收路数、磁盘预读与重定向后的字节一致性。原生遥控器用例检查主控制和设置的上下左右、Back 与侧栏焦点恢复。`ExperiencePolicyTest` 另覆盖重定向清单的基址分类；`ParallelRangeReaderTest` 覆盖真实限速连接和精确字节。

正式 1.7.2 APK 沿用 1.7.1 私有签名，覆盖升级无需卸载。签名文件不得提交或公开发布。
