# R4 原生电视遥控器验证

基于 BronyaTV 1.7.0（`f62825b`）及当前 Cinema UI 修改，使用 Android API 23 模拟器实际安装 R4 Debug APK。测试数据和图片来自用户文档中的《沙丘 2》等影视样例及 CinemaMaster / Sarah / Kids 用户。

## 结果与修复

1280 × 720 下四项设备用例全部通过，共 99 次原生按键操作；1920 × 1080 下额外复跑登录用例，18 次按键操作全部通过。

| 页面路径 | 720p 按键次数 | 实测覆盖 | 结果 |
| --- | ---: | --- | --- |
| 主页、侧栏、详情 | 20 | 侧栏上下、向右进入卡片、货架上下与卡片左右、确认进入详情、片源左右、返回恢复卡片焦点 | 通过 |
| 登录 | 18 | 用户卡片上下、凭据与登录按钮上下、两栏左右、确认选择 Sarah、进入编辑、返回退出编辑后立即向左切换用户 | 通过 |
| 播放器 | 45 | 中央与参数按钮左右、进度条与顶部按钮上下、确认暂停/恢复和 HUD 开关、字幕/音轨/画幅面板上下、返回恢复发起按钮、时间轴左右跳转 | 通过 |
| 设置 | 16 | 快捷设置与分类卡片上下左右、确认进入编辑、首项向上返回当前标签、返回卡片、侧栏与内容左右切换 | 通过 |

测试发现并修复两个实际问题：

- 登录输入框在导航时吞掉左右键作为光标操作，导致无法向左选中用户卡片。现在导航模式下左右键移动焦点，确认进入编辑后保留光标操作，返回退出编辑后可立即继续导航。修复位于 `TvCompose.kt` 的 `TvField`，未调整登录布局或增加控件。
- 设置编辑页首项向上会按几何距离跳到最右标签。现在首项的向上目标明确指向当前设置标签，修复位于 `SettingsActivity.kt`。

播放器上一项/下一项在单影片样例中不可执行，但 TV Material 仍允许其获得遥控器焦点；用例验证了这一实际顺序及禁用状态。播放器遍历开始时先暂停，避免软件模拟器执行耗时使 90 秒测试片播放完毕，再验证真实暂停/恢复和跳转。

## 测试方法

新增 `app/src/androidTest/java/tv/ember/client/NativeRemoteNavigationDeviceTest.kt`。每次导航均调用 Android `Instrumentation.sendKeyDownUpSync`，发送方向键、确认键及返回键；随后读取实际 Compose 焦点并断言目标，没有用程序请求焦点、语义点击或程序滚动代替按键。

使用的 Android keycode：上 19、下 20、左 21、右 22、确认 23、返回 4。每次按键后的焦点保存在文本记录中；选定位置通过 Android 实际屏幕截图保存 PNG。

模态选择面板打开时，Compose 语义可能同时保留后方发起按钮的焦点信息；用例断言前方选项获得焦点，并通过原生上下键验证前方窗口接收操作。

最终设备记录：

- `logs/remote-dpad-r4-final-720-device.log`：`OK (4 tests)`，236.699 秒。
- `logs/remote-dpad-r4-final-1080-login.log`：`OK (1 test)`，18.36 秒。

复现前需启动 `tests/mock_emby.py`，安装应用及 `assembleDebugAndroidTest` 生成的测试 APK，并执行端口反向转发。完整 720p 命令：

```bash
adb -s emulator-5556 reverse tcp:8765 tcp:8765
adb -s emulator-5556 shell wm size 1280x720
adb -s emulator-5556 shell am instrument -w -r \
  -e class tv.ember.client.NativeRemoteNavigationDeviceTest \
  -e captureFolder remote-dpad-r4-720 \
  -e fixtureServer http://127.0.0.1:8765 \
  tv.ember.client.test/androidx.test.runner.AndroidJUnitRunner
```

1080p 登录补测先执行 `wm size reset`，将 class 参数改为 `tv.ember.client.NativeRemoteNavigationDeviceTest#loginProfilesAndCredentialsAreReachableWithDirections`，captureFolder 改为 `remote-dpad-r4-1080`。

## 构建与交付

最终生产代码通过以下检查，记录见 `logs/remote-dpad-final-validation.log`：

```bash
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug lintRelease \
  assembleDebug assembleDebugAndroidTest minifyReleaseWithR8 --console=plain
```

Debug、Release 各 98 项 JVM 测试通过；两种构建 lint 均零错误、89 个警告；Debug APK、测试 APK 构建及 Release R8 检查通过。后续仅调整播放器用例的初始暂停步骤，并重新编译测试 APK，记录见 `logs/remote-dpad-paused-test-build.log`。

R4 APK 的 v1/v2 签名及 16 KiB zip 对齐验证通过。8 个原生库与原 1.7.0 Release 逐字节一致；示例 JSON 和生成测试片未进入 APK。

- 安装包：`artifacts/BronyaTV-1.7.0-cinema-ui-r4-debug.apk`，18,944,985 字节，Debug 签名。
- SHA-256：`0753dceaa051ef2f38a38804094fc5d6ecb813055b2bb918f5c5e2fabde74157`。
- 证据目录：`artifacts/remote-dpad-r4/`，包含 720p 的 7 张截图及 4 份逐键记录、1080p 登录的 1 张截图及 1 份逐键记录。
- 截图与记录包：`artifacts/BronyaTV-1.7.0-cinema-ui-r4-remote-validation.zip`，另附本文与最终设备运行日志。

上述结果来自模拟器，覆盖表中列出的路径；尚未在实体电视遥控器上运行。R2、R3、R4 的测试包与截图保留历史记录。正式发布版本已递增为 1.7.1 / versionCode 15，按用户授权使用新签名；安装与正式包验证见 [1.7.1 发布记录](../releases/v1.7.1/VALIDATION.md)。
