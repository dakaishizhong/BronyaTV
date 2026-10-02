# BronyaTV 1.4.0

Refreshes Home, Details, Playback, Settings, and Search with dark movie backdrops, cyan remote focus, sidebar navigation, and landscape cards.

- English is the default interface. Switch to Simplified Chinese on the sign-in screen or under **Settings → Interface & diagnostics → Interface language**. The choice is saved; server titles and descriptions remain unchanged.
- Home adds a large movie backdrop, playback and details actions, resume progress, and remaining time on cards.
- Movies, Series, and Favorites query the server directly. Inline search supports media-type filters and recent searches.
- Details shows server ratings, genres, source quality, cast portraits, and related titles, with resume, restart, source selection, and external player actions.
- Settings opens with six panels linking to the existing playback, remote, network, cache, audio, and subtitle controls.
- Playback uses a bottom gradient, cyan timeline, and round buttons, with source, subtitle, and audio shortcuts.
- Fixes a home-screen crash when parallel server requests fail; the screen now offers Retry and Sign in again.
- Missing backdrops fall back to posters. Quality and audio badges reflect server metadata. Image loading keeps bounded cache sizes and concurrency.
- The repository README defaults to English, with a Chinese version and English screenshots.

Keeps the DTS / TrueHD software audio decoding, disk read-ahead cache, continuous episode playback, and diagnostics from 1.3.0.

## Installation

**This release uses a new APK signing key and cannot update version 1.3.0 or earlier in place.** Uninstall the old app, install `BronyaTV-1.4.0-release.apk`, and sign in again. Uninstalling removes the old login and settings. Supports Android 6.0 and later.

**本版本使用新签名。请先卸载旧版，再安装并重新登录。**

Build and device validation are recorded in [VALIDATION.md](https://github.com/dakaishizhong/BronyaTV/blob/v1.4.0/releases/v1.4.0/VALIDATION.md).
