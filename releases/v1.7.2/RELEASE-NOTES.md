# BronyaTV 1.7.2

修复重定向片源拖动后可能绕过多路 Range 下载及磁盘缓存的问题，并补回此前 UI 没有显示的实际接收状态。

- 移除播放界面的退出按钮，使用遥控器返回键。
- 画幅按钮点击直接循环适配、裁切、拉伸；菜单面板内直接选择音轨、字幕、倍速、画幅、字幕大小和多路连接，减少二级菜单。
- 多路选择支持自动、1/2/4/8 路；播放时更改会在原位置重连，保留暂停状态、倍速和音轨选择。设置首页也提供直选。
- 设置侧栏返回原控件；短提示取消之前的提示，避免操作反馈排队滞留。
- 音轨/字幕禁用、画幅和 HUD 的按钮状态及时更新；没有可跳转目标时禁用章节按钮；播放结束后可按播放重新开始。
- 性能 HUD 从 6 项扩展至 21 项：解码器、实际色彩/音频输出、渲染帧率/丢帧、卡顿/欠载、CPU、系统/APP/Java 内存、下载速度/均值/峰值/总量、实际 TCP/峰值/连接上限、前台与磁盘预读模式、缓存命中、HTTP 状态/失败/首包时间等。
- 继续使用用户文档中的影视、用户和图片资源。

**安装：1.7.2 沿用 1.7.1 的正式签名，可直接覆盖升级。1.7.0 或更早版本需要先卸载再安装。**

## English

Keep the progressive video URI stable across redirects so seeking continues through the parallel Range and disk-cache paths. Remove the player exit button, cycle aspect on click, and apply common playback choices in one panel. Update button states immediately and disable chapter actions without a target. Expand the HUD to 21 actual decoder, system, network and cache metrics, including independent foreground and disk-prefetch receive modes.

1.7.2 uses the 1.7.1 release signing key and can update 1.7.1 directly. Older signing keys require uninstalling the old app first.
