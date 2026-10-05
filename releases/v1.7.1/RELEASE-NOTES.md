# BronyaTV 1.7.1

The Cinema UI update replaces the old player controls and follows the supplied design document. Play/Pause stays at the horizontal screen center; the expanding sidebar moves page content, and Home/category pages have no top-right action buttons.

- Home and related-title cards expand on focus, updating the backdrop and title metadata. Home has no Play/Details action row.
- Sign-in shows public users and the document's credential form. Direction keys move between profiles and inputs; OK starts editing, and Back restores navigation. No language or password-visibility controls were added.
- The player uses previous chapter/episode, Play/Pause and next chapter/episode circles at 44/56/44 base dp with 20 dp gaps. Subtitle, audio and aspect controls are separate on the right. HUD shows actual playback data.
- The first editable setting returns to the current category tab on Up. Native remote tests cover Home/details, sign-in, playback, settings, panels and Back focus restoration.
- Examples use the document's Dune: Part Two, Oppenheimer, Interstellar, Blade Runner 2049, Severance and CinemaMaster/Sarah/Kids resources. Generated demo media is excluded from the APK.

**Installation:** 1.7.1 uses a new release signing key. Uninstall 1.7.0 or any earlier installation before installing this APK and signing in again. Uninstalling removes the old app data. Future releases can update 1.7.1 when signed with this new key.

The APK is a minified, resource-shrunk Release build for Android 6.0+ with four native ABIs. Existing decoding libraries match 1.7.0 byte for byte. See [VALIDATION.md](https://github.com/dakaishizhong/BronyaTV/blob/v1.7.1/releases/v1.7.1/VALIDATION.md) for the exact checks and limits.

## 简体中文

根据参考文档重新实现 Cinema UI：侧栏展开时移动正文，主页不显示播放/详情操作栏，主页及分类右上角不添加按钮；登录保留文档中的用户卡片、凭据输入与登录按钮。

播放器替换旧七按钮栏，使用 44/56/44 基准 dp 的三个圆形按钮及 20 dp 间距，播放/暂停固定于屏幕横向中心；字幕、音轨、画幅独立靠右，HUD 使用实际播放数据。修复登录输入框左右导航及退出编辑后的焦点切换、设置首项向上跳错标签的问题。

**安装注意：1.7.1 更换正式签名，需要先卸载 1.7.0 或更早版本，再安装并重新登录。卸载会删除旧版应用数据；后续沿用此新签名可覆盖升级。**

提供正式 Release APK、SHA-256 校验文件及遥控器实测截图/逐键记录。示例统一采用用户文档中的影视和用户资源，生产数据仍来自真实 Emby 服务器。
