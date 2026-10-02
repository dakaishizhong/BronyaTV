# BronyaTV 1.0.1 启动兼容修复

版本号 1.0.1，versionCode 2，包名 tv.ember.client，沿用初版发布签名。

原版仅有 MAIN/LEANBACK_LAUNCHER，普通 Android 桌面和安装器的启动查询找不到入口。修复为同一 Activity 提供普通 LAUNCHER 和 TV LEANBACK_LAUNCHER 两个入口，支持默认隐式启动，并将 Leanback 系统特性标记为可选，显式提供图标、标签和 TV banner。

Android 13 AOSP 与 Android TV 模拟器各通过 3 项启动兼容测试，证据位于 launch-fix-1.0.1/。测试覆盖普通/TV 入口解析、默认安装器启动解析、缺少 Leanback 声明的兼容性和无会话时实际启动登录页。