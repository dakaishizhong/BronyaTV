# Ember TV 1.0.2 播放地址与接收缓冲修复

2026-10-02。APK：EmberTV-1.0.2-release.apk；包名 tv.ember.client；versionCode 3；沿用 1.0.0/1.0.1 签名，可覆盖安装并保留电视上的登录和设置。

APK SHA-256：`8a6e214147d05ea50904fa42fecb46688de17bd456da3125540a1e0f4818b513`。

## 用户实机证据

Skyworth A6E / Android 13 已确认 1.0.1 显示图标、登录、主页、观看记录和版本选择正常；部分视频 HTTP 206 可播放，部分返回 HTTP 410。用户进一步确认其他客户端可播放失败片源，并发现失败链接使用 stream.mp4、正常链接使用 original.mkv。1.0.2 尚待该电视和第三方服务的复测；本报告中的成功结果来自本地测试服务与独立的官方 Emby Server。

## 修复行为

- 优先使用最新 PlaybackInfo 的 DirectStreamUrl，保留签名查询参数和 RequiredHttpHeaders；根据 AddApiKeyToDirectStreamUrl 添加 URL 认证，不覆盖已有认证参数。HTTP 远程片源可使用服务器提供的 Path。保留协议、有效端口、反向代理前缀，支持 //cdn 形式地址。
- 不再凭 CDN 地址包含 master.m3u8 或 transcod 就丢弃它；显式 TranscodingUrl 不作为直连地址。协商仍禁止转码。
- 没有直连地址时，请求 Videos/{影片ID}/original.{原始容器}，以 MediaSourceId 选择原片源，明确 Static=true。影片 ID 来自 PlaybackInfo 请求，不使用 MediaSource.ItemId 替换。
- HTTP 401/403/404/410 重新获取一次播放地址并保留播放位置。404/410 刷新后仍不可用时，尝试同一片源的 Emby 原文件接口一次；不改写 CDN 签名地址，不把所有 MP4 改成 MKV。重复失败显示具体 HTTP 状态；自动刷新间隔至少 60 秒，防止循环。菜单“重新连接”重新获取地址。
- 失败的外置字幕会跳过并继续播放视频。
- 设置 → 网络接收缓冲：系统自动（默认）或 256/512/1024/2048/4096KB。通过普通 Socket 的 SO_RCVBUF，在建立播放连接前设置，不需要 root；不修改全局内核参数。改变后重新打开视频生效。OSD 同时显示请求值与设置连接时系统报告值，系统可以限制实际大小。

HTTPS 未指定端口时默认使用 443，无需追加 :443；非默认端口会保留。stream.mp4 本身并不能证明发生转码，关键是 Static、服务器返回的地址与原片源容器。[HTTPS 规范](https://www.rfc-editor.org/rfc/rfc9110.html#name-https-uri-scheme)、[Emby PlaybackInfo](https://dev.emby.media/reference/RestAPI/MediaInfoService/postItemsByIdPlaybackinfo.html)、[Emby 视频静态接口](https://dev.emby.media/reference/RestAPI/VideoService/getVideosByIdStreamByContainer.html)。

## 验证结果

| 验证 | 结果 |
|---|---|
| 单元测试 | 25/25 通过：地址认证、ID/版本、签名 URL、容器与原文件恢复、HTTPS/443/8443、缓冲策略等 |
| Android TV API 33 | 20/20 通过，0 跳过：真实 MP4/MKV 播放，410 刷新并保留位置，失败 stream.mp4 恢复为所选 original.mkv，永久 403 有界刷新，字幕失败恢复，接收缓冲、登录与启动兼容等 |
| Android TV API 30 | 2/2 通过，0 跳过：HEVC 10-bit 实际播放，以及官方 Emby 登录/浏览/MP4、MKV、HDR 三种版本原文件播放 |
| 官方 Emby Server 4.10.1.0 | 三种片源的 stream 与 original 接口共 6 个 Range 请求均返回 206，文件头和容器一致 |
| 签名 Release 实际 UI | 从 1.0.1 覆盖安装成功；登录、选择失败片源、410 后恢复 original.mkv、READY/206、接收缓冲设置和返回设置后保留数值均通过 |
| 普通应用接收缓冲 | UID 10101，所有手动档位均可调用；本地系统报告 512KB，包括请求 1024KB 的真实播放连接。此数值不代表用户电视的上限 |
| 构建与检查 | Debug/Release lint 通过；Release R8/资源压缩；v1/v2 签名和 zipalign 验证通过 |

API 33 模拟器不支持测试样片的 HEVC Main10/HDR profile，首次完整回归的这一项失败于硬件和系统软件解码器能力，证据保留在 playback-fix-1.0.2/api33-first-run.*。没有改变或削弱该用例；随后在已支持该样片的 API 30 TV 环境使用最终代码验证通过。API 30 首次启动后网络尚未就绪导致连接失败，待网络就绪重跑 2 项均通过。

正式 APK 的界面自动化初次执行遇到不可点击的首页预览标题以及 API 33 的 resumed Activity 字段名称差异；修正测试脚本使用遥控器导航和兼容查询后，以重新清空的模拟器应用数据完成上述全部 Release UI 流程。没有为此改变应用代码。

构建日志、JUnit XML、签名、lint、原文件接口结果、正式 APK 播放截图和 OSD 证据位于 playback-fix-1.0.2/。测试媒体由 FFmpeg 生成，不包含于 APK。模拟测试验证的是恢复逻辑和播放过程，不等同于用户第三方服务或实体 ARM 电视 4K/HDR 实测。
