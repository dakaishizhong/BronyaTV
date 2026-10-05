# 1.7.0 Cinema UI 修改版

基线提交：`f62825b`（BronyaTV 1.7.0）。参考为用户提供的《Emby_Android_TV_Compose_完整复刻工程实现.md》。保留应用标识、1.7.0 / versionCode 14、最低 API 23 和现有播放内核。

## 页面改动

- 采用文档中的深色背景、翠绿焦点、暖金评分、圆角卡片和背景暗角。
- 侧栏获焦时由 56 扩展至 152 个基准 dp，按文档显示登录、首页、电影、剧集、设置及版本。侧栏与内容采用同一 Row，展开时占用实际宽度并移动内容，不能覆盖标题和简介。
- 首页及相关影片货架的卡片由 140 扩展至 260 个基准 dp，高度保持 180；海报与背景交叉淡入，焦点切换同步更新首页背景和元数据。卡片展开后请求滚动以保持可见。分类和搜索继续采用固定网格。
- 首页背景覆盖上方约 67% 视口，元数据和可滚动货架分区。标题、原名、制作公司、评分和技术标签来自服务器。移除首页播放/继续播放、详情操作栏及右上角菜单按钮；分类页右上角筛选按钮同样移除，已有筛选、搜索及收藏通过遥控器 MENU 使用。
- 详情页调整为标题、简介、主要操作、片源卡片、演员和相关影片。片源卡片展示实际文件名、格式、分辨率、码率及文件大小；选择版本和播放地址即时协商继续使用原业务逻辑。
- 登录页恢复文档中的 EMBY CINEMA MAX、媒体库登录与账户标题、5:7 双栏、用户标签/描述/头像缩写、带图标的服务器/用户/密码输入框及底部居中的登录按钮。移除语言、显示密码和额外说明控件，仍使用 Emby `/Users/Public`、真实密码登录与选择用户时清空密码的逻辑。
- 设置首页增加可保存的播放器、内存缓存、自动下一集和性能监控快捷选项，保留完整设置分类和编辑界面。
- 播放器全面替换旧七按钮控制栏和旧 XML 控件：上一章节/集、播放/暂停、下一章节/集三个圆形按钮（44/56/44 基准 dp），右侧为字幕、音轨、画幅文字按钮。进度时间在进度条上方，显示实际播放时间与负数剩余时间；顶部仅保留文档明确提供的 HUD 与退出播放按钮。HUD 使用六行实际运行数据，选项面板关闭后直接返回播放器。详情仅保留文档中的立即播放按钮。

## 示例资源

后续示例约定写入根目录 [AGENTS.md](../AGENTS.md)。原始示例保存于 [cinema-reference.json](../tests/media/cinema-reference.json)，使用文档中的 5 个影视样例、2 张剧集图片、3 位演员配图和 CinemaMaster / Sarah / Kids 三个用户。素材生成与本地接口说明见 [CINEMA-REFERENCE.md](../tests/media/CINEMA-REFERENCE.md)。

素材已实际下载并生成播放测试片。本地验证覆盖三位用户登录和各自媒体库、影片/剧集/演员图片、中英文搜索、年份筛选及排序，以及视频 Range 返回 206、1024 字节和正确 Content-Range。实际播放测试片为合成短片。

## 已完成验证

执行：

```bash
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease assembleDebug assembleDebugAndroidTest minifyReleaseWithR8 --console=plain
```

- Debug、Release 各 98 项 JVM 测试通过，零失败、错误或跳过。
- Debug、Release lint 均零错误、89 个非阻断警告。
- Debug APK、设备测试 APK 及 Release R8 混淆检查通过。
- 测试 APK 的签名和 16 KiB zip 对齐检查通过。
- 1.7.0 Release 中的 8 个原生库与本次 Debug APK 对应文件逐字节一致。
- 演示图片、示例 JSON 和生成测试片不进入 APK。
- `git diff --check` 通过。

新增/更新的设备用例覆盖卡片展开与 Hero 同步、侧栏折叠、用户选择后密码清空、快捷设置持久化，以及原有版本选择、返回焦点和播放控制回归。这些用例已编译。

## 实际 UI 截图

已在 Android API 23 模拟器上安装并运行上述 Debug APK，使用 1920 × 1080 输出、文档中的示例图片和本地 Emby 接口截取实际页面。模拟器使用软件 CPU/GPU 渲染。

`CinemaScreenshotDeviceTest.captureReferenceScreens` 通过，捕获登录、首页、首页焦点展开、电影货架、侧栏展开、分类、详情、设置、播放器、六行 HUD 和字幕侧栏共 11 张 PNG。用例通过原生方向键进入遥控器焦点模式，断言目标获焦、电影货架更新首页标题、展开侧栏与内容的边界不重叠、首页/分类没有右上角按钮、登录没有语言/显示密码控件、选择用户清空密码，以及旧播放器按钮不存在、新按钮与真实选择面板可用。

另两项设备用例通过：

- `PlaybackUiDeviceTest.documentControlsOperateTheRealPlayerAndTrackPanels`：通过原生方向键经过下一章节、字幕和音轨按钮，验证真实播放器暂停/恢复、静音选择、画幅设置保存、时间轴跳转及 HUD 开关。TV Material 的禁用按钮仍可获得遥控器焦点，用例覆盖该焦点顺序。
- `EpisodeDeviceTest.manualNextAndPreviousReuseActivityAndCrossSeasonBoundary`：验证下一集跨季切换、片头位置、最后一集的下一集禁用状态及上一集返回。

截图和切集通过记录见 `logs/cinema-ui-r2-device-validation.log`；其中播放器用例首次对禁用按钮的焦点顺序作了错误假设，修正后单独复跑通过，记录见 `logs/cinema-ui-r2-player-validation.log`。三个针对性设备用例均已有通过结果。

截图位于 `artifacts/cinema-ui-r2-preview/cinema-ui-r2/`，打包文件为 `artifacts/BronyaTV-1.7.0-cinema-ui-r2-screenshots.zip`。复现用例：

```bash
adb reverse tcp:8765 tcp:8765
adb shell am instrument -w -r \
  -e class tv.ember.client.CinemaScreenshotDeviceTest \
  -e fixtureServer http://127.0.0.1:8765 \
  tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

需先启动本地 `tests/mock_emby.py` 并安装应用及 `assembleDebugAndroidTest` 生成的设备测试 APK。截图中的播放画面为文档《沙丘 2》示例图片生成的合成短片；片长、码率与分辨率反映实际测试文件。

## R3 播放器中心与按钮比例

修复底部按钮栏使用不对称左右区域分配宽度造成的偏移。三个圆形按钮独立锚定屏幕横向中心，参数按钮独立靠右；长音轨、字幕与画幅名称不会推动中央按钮或覆盖它。三个按钮保持 44/56/44 基准 dp、20 dp 间距，图标为按钮直径的一半；暂停、上一项和下一项改用文档 Material 图标的实心几何比例。

文字按钮改用 TV Surface，保留遥控器焦点、禁用状态和点击行为，并移除 TV Button 的默认最小尺寸。文字为 12 sp、16 sp 行高，内边距为水平 14 dp / 垂直 8 dp；关闭首尾行高裁剪，基准总高度为 32 dp。顶部 HUD / 退出播放间距为 12 dp，顶部与右侧边距 36 dp；底部参数间距为 10 dp、右侧边距 48 dp，底部圆形按钮边距 28 dp。焦点仍使用 1.045 倍绘制缩放，布局位置不变。

本版重新完成 Debug / Release 各 98 项 JVM 测试、两种构建的 lint 零错误（各 89 个警告）、Debug APK / 测试 APK 构建以及 Release R8 检查，记录见 `logs/cinema-ui-r3-final-validation.log`。签名、16 KiB zip 对齐、8 个原生库逐字节一致、示例数据未进入 APK 的检查通过。

`PlaybackUiDeviceTest.documentControlsOperateTheRealPlayerAndTrackPanels` 分别在 1080p 英文和 720p 中文下通过，验证屏幕中心、圆形直径、间距、进度条、顶部与右侧文字按钮边距，以及真实暂停/恢复、音轨、画幅、进度跳转、HUD 和禁用按钮的遥控器焦点顺序。圆形几何比较允许 1 像素误差；文字行高及两个内边距分别取整，允许 2 像素误差。

| 输出 | 播放按钮布局中心 | 普通状态直径（上一项 / 播放 / 下一项） | 文字按钮高度 | 参数间距 |
| --- | --- | --- | --- | --- |
| 1920 × 1080 | (960, 968) | 88 / 112 / 88 px | 64 px | 20 px |
| 1280 × 720 | (640.5, 645.5) | 59 / 75 / 59 px | 44 px | 13 px |

记录见 `logs/cinema-ui-r3-player-en-1080-pass.log`、`logs/cinema-ui-r3-player-zh-720-pass.log` 及 `logs/cinema-ui-r3-geometry.log`。1080p 实际截图中，获焦绿色圆形的可见中心仍为 (960, 968)，直径 116 px，与 112 × 1.045 的渲染值相符；TV Surface 的语义布局边界不包含绘制缩放，因此这一项另用截图像素验证。

本版播放器截图使用文档《沙丘 2》资源，采用默认适配画幅，包含暂停、播放、HUD 和字幕面板四张实际 PNG。文件位于 `artifacts/cinema-ui-r3-preview/cinema-ui-r3/`，截图包为 `artifacts/BronyaTV-1.7.0-cinema-ui-r3-screenshots.zip`；原 R2 截图与 APK 保留历史记录。只捕获本版播放器的命令为：

```bash
adb shell am instrument -w -r \
  -e class tv.ember.client.CinemaScreenshotDeviceTest \
  -e playerOnly true -e captureFolder cinema-ui-r3 \
  -e fixtureServer http://127.0.0.1:8765 \
  tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

R3 测试 APK：`artifacts/BronyaTV-1.7.0-cinema-ui-r3-debug.apk`，18,944,985 字节，SHA-256：`4606043b6af06608dc6f1fba0db347fa56a216b816a8d6758467bddc70b0d458`。

## R4 原生遥控器验证

新增逐键发送 Android 遥控器事件的设备用例，720p 下主页/详情、登录、播放器、设置四项全部通过（99 次操作），1080p 登录补测也通过。修复登录输入框导航模式下左右键被当成光标操作，以及设置编辑首项向上跳错标签的问题；未改变页面布局或增加按钮。完整路径、方法、逐键记录与截图见 [REMOTE-DPAD-VALIDATION.md](REMOTE-DPAD-VALIDATION.md)。

当前测试 APK：`artifacts/BronyaTV-1.7.0-cinema-ui-r4-debug.apk`，18,944,985 字节，SHA-256：`0753dceaa051ef2f38a38804094fc5d6ecb813055b2bb918f5c5e2fabde74157`。证据包为 `artifacts/BronyaTV-1.7.0-cinema-ui-r4-remote-validation.zip`。Debug / Release 各 98 项 JVM 测试、lint 零错误、Debug 与设备测试 APK 构建、Release R8 检查均通过，记录见 `logs/remote-dpad-final-validation.log`。

## 设备及签名限制

已完成上述截图、播放器、切集及原生遥控器模拟器用例，其余完整设备回归套件尚未执行。实体电视遥控器、动画观感、真实 Emby 和电视输出仍需设备验证。

R2–R4 验证时未配置原项目 Release 签名密钥，交付文件为 Debug 签名测试 APK。随后用户授权创建新密钥，正式发布 1.7.1 / versionCode 15，签名 Release 构建通过；安装前需卸载旧签名版本。正式包与完整验证见 [1.7.1 发布记录](../releases/v1.7.1/VALIDATION.md)。

R2 历史测试 APK：`artifacts/BronyaTV-1.7.0-cinema-ui-r2-debug.apk`，18,944,985 字节。上一版 APK 与截图保留原始记录。

SHA-256：`404bdebb6606695b2a47874a6b5d44d4c4fa137f85a694eed636af32c5d368ee`。
