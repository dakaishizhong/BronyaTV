# BronyaTV 1.3.0 验证

| 范围 | 结果 |
| --- | --- |
| 单元测试 | 67 项通过，0 失败、0 错误、0 跳过；新增磁盘容量与存储保留空间、提前窗口、EOF 边界、LRU 淘汰、回退保留及内存预算检查 |
| Android TV 模拟器 | API 33 上 3 项专项检查通过：真实 SimpleCache / SQLite 文件预取、缓存命中、向前与向后跳转、清理；单连接忽略 Range 的识别及字幕长度隔离；AVC / DTS 核心音轨的软件解码、PCM 输出与快进后继续播放 |
| 正式签名包 | 在 API 33 模拟器从 1.2.1 覆盖升级，保留登录和 4 路接收设置；磁盘容量与提前量调整、重启保存通过；实际播放有磁盘写入与缓存命中读取，退出播放后清空缓存显示占用为零 |
| 正式构建 | Release R8、资源压缩、APK 构建通过；应用与音频模块 Release lint 无错误 |
| 音频原生库 | 从保留的 FFmpeg 6.1.4 源码与 Media3 1.11.1 JNI 构建 ARM 32/64 位、x86、x86_64；仅启用 TrueHD / MLP / DTS (`dca`)，GPL 和 nonfree 组件关闭 |
| JNI 打包 | APK 包含四种 ABI 的 libffmpegJNI.so，与构建文件逐字节一致；R8 保留 JNI 查找的类名、原生方法及输出缓冲回调描述符；第三方许可随 APK 打包 |
| 内存页对齐 | 四种 ABI 的 ELF LOAD 段均为 16 KiB 对齐；APK 通过 zipalign -P 16 检查 |
| 签名 | APK 签名验证通过，发布证书与旧版一致；版本 1.3.0 / versionCode 8 |

本次未进行实体电视的 DTS-HD MA、DTS:X、HDMI 声道或高码率 4K 网络实测。生成的 DTS 核心测试片不能覆盖所有 DTS 变体；模拟器检查不代表实体设备的网络吞吐或解码性能。
TrueHD / MLP / DTS 使用软件解码和 PCM 输出，不保留 TrueHD Atmos 或 DTS:X 对象元数据。

复现步骤见 [开发说明](../../docs/DEVELOPMENT.md) 和 [音频模块](../../decoder-ffmpeg/README.md)。
发布包哈希见 [SHA256SUMS](SHA256SUMS)，改动见 [发布说明](RELEASE-NOTES.md)。
