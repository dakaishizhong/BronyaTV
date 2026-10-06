# BronyaTV 1.7.4

重构原文件多路取流与磁盘缓存协同，改善高码率 MP4 / MKV 在有 RTT、TTFB 抖动和单连接吞吐限制时的持续下载。

**升级说明：1.7.4 使用新签名，所有旧版本需先卸载再安装。卸载会清除旧版本地登录、设置和缓存。后续沿用此签名的版本可覆盖安装。**

- 独立 Range worker 在分片完成后立即领取后续任务；下载与播放器顺序消费解耦，乱序完成的数据按 offset 输出。
- 根据码率、连接数及堆内存余量调整分片与提前窗口，下载数据及保留的写盘副本合计最多 32 MiB。
- 验证首个 206 响应头后并发启动后续请求，保留严格 Range、总长度、ETag / Last-Modified 校验和单路回退。
- 前台与磁盘共用下载器；异步写盘、复用缓存，后台使用剩余容量，避免反复取消或重复下载。seek 及时取消旧位置请求。
- HUD 增加加载需求、活跃 HTTP Range、窗口占用、分片、缓存命中及请求取消信息。
- 保留 Media3 播放、音轨与字幕、外部字幕、HDR / Dolby Vision、进度上报、换源及外部播放器。HLS / DASH 继续使用原生数据源。

本地合成数据的真实 HTTP/1.1 测试覆盖 96 组网络条件及 9 组 256 MiB 延长测试；最终均通过 SHA-256 校验且零断粮。50 / 80 / 100 Mbps 消费模型达到约 50 / 80 / 100 Mbps。[测试报告](https://github.com/dakaishizhong/BronyaTV/blob/main/docs/validation/range-pipeline-2026-10-06/report.json)、原始结果和复测脚本已公开；这些结果不代表实体电视或公网 CDN 验证。

## English

**Installation: 1.7.4 uses a new signing key. Uninstall any earlier version first; this clears its local login, settings and cache. Later versions using this key can update 1.7.4 directly.**

Independent range workers refill a bounded forward window as requests complete, while Media3 consumes bytes in offset order. Adaptive chunk sizing, strict source validation and a shared downloader for playback and asynchronous disk storage reduce idle gaps, repeated cancellations and duplicate requests. Seek cancels obsolete work promptly; the HUD exposes loading demand and range/cache diagnostics.

The final synthetic HTTP/1.1 matrix covers 96 network scenarios plus nine 256 MiB runs, with exact SHA-256 verification and zero underruns. The 50 / 80 / 100 Mbps consumer models delivered approximately their target rates. Published results are local simulations; physical TV and public CDN paths were not measured.
