# BronyaTV 1.7.0

Play/Pause now has the same diameter as the other controls and sits at the center of a symmetric seven-button row. Home, category, search and related-title cards use 16:9 artwork; the Home hero is more compact so its first two rows fit the TV screen.

- Live search waits 300 ms while typing; remote/IME submission is immediate. Full-width characters and whitespace are normalized. Cancelled or obsolete responses cannot replace the latest query, and repeat submission reuses the current result. Default search ordering comes from Emby; explicit sort and page conditions stay on the server.
- Genre/year options load when filters are opened. Recent page returns reuse data for 15 seconds while applying current playback progress; Refresh always contacts the server. Card queries omit unnecessary descriptions and cast lists. Identical image URLs share encoded disk data across display sizes, within the existing memory and concurrency limits.
- Screenshots and test artwork now use six real public-domain classic-film stills, with source/license pages and hashes in [tests/media](../../tests/media/README.md). These images and generated test clips are not included in the APK.
- Existing playback, versions, external-player support, audio/subtitles, language settings and remote Back navigation are retained. There are no volume shortcuts or top-left Back buttons.

Verification details are in [VALIDATION.md](VALIDATION.md). Screenshots are English by default. Physical TV, production Emby, high-bitrate hardware and HDMI/HDR output need separate device validation.

## 简体中文

播放/暂停与其他按钮同尺寸，七个按钮围绕屏幕中心对称排列；各页卡片统一为 16:9，主页主推荐区更紧凑。

搜索支持输入停顿后自动更新、确认键立即提交、中文/全角规范化和过期请求取消，筛选与排序继续交由 Emby。减少页面返回、筛选选项和图片加载的重复工作。截图改用六部公共领域电影的真实剧照，附来源、许可和校验值；测试素材不打包进 APK。

保留现有播放核心、中英文设置、音轨字幕及遥控器操作。验证范围和真机未验证项见 [VALIDATION.md](VALIDATION.md)。
