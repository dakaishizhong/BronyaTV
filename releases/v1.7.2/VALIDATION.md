# BronyaTV 1.7.2 验证记录

- 日期：2026-10-06 UTC；versionCode 16；Android 6.0+。
- 正式 APK：7,238,790 bytes；SHA-256 `b75db23311a5cc2b0a478360b079c3a09b7f0a18de2e1c69de89633319fd7617`。
- Release 签名 SHA-256：`7754dee23fe2711aa94c7a27b5e38df1a4c66f362469a69ce4d5dd1758eee212`，与 1.7.1 相同，可覆盖升级。
- Release 经 R8 与资源压缩；签名及 16 KiB 对齐校验通过；Lint Debug 无错误。

## 实测结果

99 项 Debug JVM 用例通过（失败/错误/跳过均为 0），包含 Range、接收预算、缓存与清单地址分类。6 项 Android 仪器用例通过，最终日志 `player-172-verified-device.log` 为 `OK (6 tests)`，耗时 127.698 s。

| 检查 | 结果 |
| --- | --- |
| 主播放按钮 | 暂停/恢复改变实际 Media3 playWhenReady 与图标 |
| 章节与时间轴 | 跳到 20/40 秒、返回开头与时间轴跳转；末章节禁用下一章节 |
| 音轨/字幕 | 静音/关闭改变实际 trackSelectionParameters，按钮显示相应状态；自动恢复取消禁用 |
| 画幅 | 点击循环 0/4/3，PlayerView 与保存设置一致，无二级弹窗 |
| HUD | 开关同时改变持久设置、选中状态与 21 项实际指标面板 |
| 同一设置面板 | 倍速、画幅、字幕大小与接收路数直接选中；更改路数后保留暂停及 1.25× 倍速 |
| 磁盘预读 | 重定向视频实际峰值 3 条 TCP；面板显示独立前台/预读模式及缓存命中 |
| 重定向与拖动 | 首次及偏移 12,345 bytes 的数据与原文件完全一致；峰值 4 TCP；保持原视频 URI |
| 遥控器与布局 | 原生上下左右/OK/Back，设置侧栏返回准确恢复原控件；中心播放按钮及 44/56/44 dp 比例通过 |

原生遥控器逐键记录：{'playerAllDirectionsReachEveryControlAndReturnFromPanels': 41, 'settingsQuickControlsTilesEditorAndSidebarUseDirections': 20}。这些导航检查不使用 RequestFocus/performClick/performScrollTo。独立功能检查为覆盖每个实际参数，使用定位/滚动辅助，再发送原生 OK 并核对真实播放器；两种覆盖范围均在测试源码中明确。

限速 JVM 源记录：`Throttled fixture: single=1299 ms, four=350 ms, peak TCP connections=4`。此对照仅用于验证独立连接并发和精确字节，不代表真实服务器或电视的速度。

## 范围

仪器检查运行于 1280×720 的 Android 23 软件模拟器、Debug 应用与真实 Media3 播放，片源为文档《沙丘 2》图片生成的 90 秒 VP8 视频。未连接用户真实 Emby 服务器；未实测电视硬解、HDR/杜比输出或未安装的外部播放器。正式 APK 构建/签名/对齐通过；仪器运行记录属于相同功能源码的 Debug 构建。

性能面板输出实测值，系统未提供的色彩/频率保持未提供状态。完整截图和原生逐键记录见 Release 附件 `BronyaTV-1.7.2-remote-validation.zip`。私有签名文件不在公开源码或附件中。
