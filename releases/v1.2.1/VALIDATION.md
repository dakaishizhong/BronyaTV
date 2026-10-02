# BronyaTV 1.2.1 验证

| 范围 | 结果 |
| --- | --- |
| 单元测试 | 58 项通过，0 失败、0 错误、0 跳过；新增实际分配器满载时的快进缓冲、暂停／音频焦点、网络等待、播放时钟和渲染帧停滞、一次性恢复及返回键重复事件回归 |
| 正式构建 | Release R8、资源压缩、APK 构建通过；应用与音频模块 Release lint 无错误 |
| 音频原生库 | 从保留的 FFmpeg 6.1.4 源码与 Media3 1.11.1 JNI 构建 ARM 32/64 位、x86、x86_64；仅启用 TrueHD / MLP，GPL 和 nonfree 组件关闭 |
| JNI 打包 | APK 包含四种 ABI 的 libffmpegJNI.so，与构建文件逐字节一致；R8 保留 JNI 查找的类名、原生方法与输出缓冲回调描述符；第三方许可随 APK 打包 |
| 内存页对齐 | 四种 ABI 的 ELF LOAD 段均为 16 KiB 对齐；APK 通过 zipalign -P 16 检查 |
| 签名 | APK 签名验证通过，发布证书与旧版一致；版本 1.2.1 / versionCode 7 |

本次未运行界面、模拟器播放、实体电视 TrueHD 或杜比音频服务器测试。
这些检查验证编译、打包和恢复策略，不能替代实体设备的播放兼容性验证。
TrueHD / MLP 使用软件解码和 PCM 输出，不保留 TrueHD Atmos 对象元数据。

构建与原生库复现步骤见 [开发说明](../../docs/DEVELOPMENT.md) 和 [音频模块](../../decoder-ffmpeg/README.md)。
发布包哈希见 [SHA256SUMS](SHA256SUMS)，改动见 [发布说明](RELEASE-NOTES.md)。
