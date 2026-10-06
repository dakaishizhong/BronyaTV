# BronyaTV 1.7.4

- 优化 MP4 / MKV 原文件多路取流，改善高码率视频的持续下载。
- 改善磁盘缓存与播放取流的协同，减少重复请求和缓存等待。
- 优化起播及跳转后的取流衔接，保留不支持 Range 时的单路回退。
- 完善性能 HUD 的连接、缓存及加载状态显示。

**升级说明：1.7.4 使用新签名，旧版本需先卸载再安装。卸载会清除本地登录、设置和缓存。**

## English

- Improve sustained fetching for high-bitrate MP4 / MKV original files.
- Improve cooperation between playback and disk caching, reducing duplicate requests and cache waits.
- Improve fetching during startup and after seeking, preserving single-stream fallback for servers without Range support.
- Improve connection, cache and loading status in the performance HUD.

**Installation: 1.7.4 uses a new signing key. Uninstall the earlier version first; this clears its local login, settings and cache.**
