# 当前示例资源

新演示统一使用用户提供的 Compose TV 工程文档中的资源，原始标题、简介、评分、制作公司、片源说明、演员、剧集、用户和图片 URL 保存在 [cinema-reference.json](cinema-reference.json)。文档使用的 Unsplash 摄影是其示意配图。

在工程根目录执行 `python3 scripts/create_test_assets.py`，下载文档中的海报、背景、剧集图片和演员头像，生成本地图片与短播放测试片。生成文件存放于忽略的 `tests/assets/`，不进入 APK。测试视频使用文档配图、生成音调和测试字幕，实际长度和编码以测试视频为准。

执行 `python3 tests/mock_emby.py` 启动本地接口。CinemaMaster、Sarah 的演示密码为 `demo`，Kids 无密码；旧自动化登录 `demo/demo` 仍映射到 CinemaMaster。接口支持 `/Users/Public`、各用户媒体库、搜索、图片、选定版本播放和 Range 请求。演示目录用 `/fixture/control?small_catalog=1` 切换；45 条合成目录仅用于分页和筛选回归。

原 [SOURCES.json](SOURCES.json) 和公有领域剧照是 1.7.0 及此前版本的历史验证素材。当前素材生成脚本和接口不再使用它们。
