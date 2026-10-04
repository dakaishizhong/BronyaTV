# BronyaTV 1.6.0

- Home starts with recent playback and Continue watching, then shows up to six actual Emby libraries in server order. Each heading opens its original library directly; remaining libraries stay accessible below. Library names and contents come from the server.
- Movies and Series open directly onto their content and server-library tabs. One optional **Filter & sort** panel replaces the multiple rows of category buttons. Genre, year, watch status, sorting and pagination execute on the server.
- Playback controls use centered circular icons, a larger pause button, a cyan focus ring and a label for the focused action. Existing playback, tracks, seeking, sources and episode actions remain available.
- All active pages, sidebar navigation, settings editors, playback controls and dialog content now use Kotlin and Compose for TV / TV Material. PlayerView continues to provide the video Surface and subtitles. Back navigation restores focus, filters and scroll position.
- Details show the server's media versions inline. Play/Resume freshly validates the selected source; external players and source switching during playback remain supported.
- **Image cache** has a configurable 0/64–1024 MiB disk limit, usage and clear controls. Images load at display dimensions with ImageTag invalidation and shared requests. Descriptive metadata has its own 24 MiB persistent cache, isolated by server and user, excluding progress, credentials, temporary URLs and playback sessions.
- Exact end-of-file Range 416 responses now end playback cleanly when their Content-Range proves the requested position equals file length. External subtitle documents use control transport, avoiding starvation behind a paused single video connection.
- Login/search/settings text fields explicitly route Up/Down to remote focus navigation; large multiline titles use matching line spacing.
- English remains the default; English/Simplified Chinese switching and the existing playback core, shared media connection limit, seek-only recovery policy and hardware/software audio fallback remain intact.

See [VALIDATION.md](VALIDATION.md) for checks, measurements and unverified hardware paths. Screenshots use an English local mock Emby server with real playable test fixtures; production library names and metadata are not replaced.
