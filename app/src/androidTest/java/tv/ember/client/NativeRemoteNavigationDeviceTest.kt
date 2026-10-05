package tv.ember.client

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Request
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TestName
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.i18n.*
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.ui.*
import java.io.File

/** Every navigation transition is driven by Android remote key events. */
@RunWith(AndroidJUnit4::class)
class NativeRemoteNavigationDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    @get:Rule val testName=TestName()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as BronyaApp
    private val trace=mutableListOf<String>()
    private val folder get()=File(context.getExternalFilesDir(null),InstrumentationRegistry.getArguments().getString("captureFolder") ?: "remote-dpad-r3").apply { mkdirs() }
    private fun nav(label:UiText)="nav_${Tr.text(label)}"
    private fun focused()=compose.onAllNodes(isFocused(),useUnmergedTree=true)
        .fetchSemanticsNodes(atLeastOneRootRequired=false).map { it.config.getOrElse(SemanticsProperties.TestTag) { "untagged" } }.distinct()
    private fun ready(tag:String) { compose.waitUntil(90000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun capture(name:String) {
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    private fun expect(vararg tags:String) {
        try { compose.waitUntil(4000) { focused().any { it in tags } } }
        catch(error:Throwable) {
            capture("failure-${testName.methodName}")
            throw AssertionError("Expected focus ${tags.toList()}, actual ${focused()}; route=${trace.joinToString(" -> ")}",error)
        }
        compose.onNodeWithTag(tags.first { it in focused() }).assertIsFocused()
    }
    private fun key(code:Int) {
        instrumentation.sendKeyDownUpSync(code);compose.waitForIdle()
        val line="${KeyEvent.keyCodeToString(code)}: ${focused()}"
        trace+=line;println("REMOTE ${testName.methodName} $line")
    }
    private fun step(code:Int,vararg tags:String) { key(code);expect(*tags) }
    @Before fun setup() {
        val server=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
        tv.ember.client.network.HttpClient.api.newCall(Request.Builder().url("$server/fixture/control?small_catalog=1&fail=0").build()).execute().use { check(it.isSuccessful) }
        AppLanguage.save(context,InstrumentationRegistry.getArguments().getString("testLanguage") ?: "zh")
        app.sessions.save(Session(server,"fixture-token","u1","CinemaMaster"));app.progress.clear()
        app.settings.diskCacheMb=0;app.settings.osd=false;app.settings.introSeconds=0;app.settings.outroSeconds=0
        app.settings.resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
    }
    @After fun saveTrace() { File(folder,"${testName.methodName}.txt").writeText(trace.joinToString("\n"));app.settings.osd=false }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun playerAllDirectionsReachEveryControlAndReturnFromPanels() {
        ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","vp8")).use { scenario ->
            fun player(block:(Player)->Unit) { scenario.onActivity { block(views(it.window.decorView).filterIsInstance<PlayerView>().first().player!!) } }
            compose.waitUntil(90000) {
                var ready=false
                scenario.onActivity { ready=views(it.window.decorView).filterIsInstance<PlayerView>().firstOrNull()?.player?.playbackState==Player.STATE_READY }
                ready
            }
            step(KeyEvent.KEYCODE_DPAD_UP,"play_pause")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"play_pause");player { assertFalse(it.playWhenReady) }
            step(KeyEvent.KEYCODE_DPAD_LEFT,"previous_section")
            compose.onNodeWithTag("previous_section").assertIsNotEnabled()
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"play_pause")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"next_section")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_subtitles")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_audio")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_aspect");capture("player-aspect-focus")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"player_audio")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"player_subtitles")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"next_section")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"play_pause")
            step(KeyEvent.KEYCODE_DPAD_UP,"playback_timeline")
            step(KeyEvent.KEYCODE_DPAD_UP,"player_hud")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_exit")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"player_hud");capture("player-hud-focus")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"player_hud");ready("player_hud_panel")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"player_hud");compose.onNodeWithTag("player_hud_panel").assertDoesNotExist()
            step(KeyEvent.KEYCODE_DPAD_DOWN,"playback_timeline")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"play_pause")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"play_pause");player { assertTrue(it.playWhenReady) }
            step(KeyEvent.KEYCODE_DPAD_CENTER,"play_pause");player { assertFalse(it.playWhenReady) }
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"next_section")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_subtitles")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"dialog_option_0")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"dialog_option_1")
            step(KeyEvent.KEYCODE_DPAD_UP,"dialog_option_0")
            step(KeyEvent.KEYCODE_BACK,"player_subtitles")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_audio")
            key(KeyEvent.KEYCODE_DPAD_CENTER);ready("dialog_option_0")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"dialog_option_1")
            step(KeyEvent.KEYCODE_DPAD_UP,"dialog_option_0")
            step(KeyEvent.KEYCODE_BACK,"player_audio")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"player_aspect")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"dialog_option_0")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"dialog_option_1")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"dialog_option_2")
            step(KeyEvent.KEYCODE_DPAD_UP,"dialog_option_1")
            step(KeyEvent.KEYCODE_BACK,"player_aspect")
            step(KeyEvent.KEYCODE_DPAD_UP,"playback_timeline")
            var before=0L;player { before=it.currentPosition }
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"playback_timeline")
            compose.waitUntil(30000) { var moved=false;player { moved=it.currentPosition>before+3000 };moved }
            step(KeyEvent.KEYCODE_DPAD_LEFT,"playback_timeline")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"play_pause");capture("player-play-focus")
            key(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("play_pause").assertDoesNotExist()
            step(KeyEvent.KEYCODE_DPAD_DOWN,"play_pause")
        }
    }

    @Test fun homeSidebarShelvesAndDetailUseOnlyRemoteKeys() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_demo");expect(nav(UiText.HOME_267))
            step(KeyEvent.KEYCODE_DPAD_UP,nav(UiText.LOGIN_SCREEN))
            step(KeyEvent.KEYCODE_DPAD_DOWN,nav(UiText.HOME_267))
            step(KeyEvent.KEYCODE_DPAD_DOWN,nav(UiText.MOVIES_316))
            step(KeyEvent.KEYCODE_DPAD_DOWN,nav(UiText.SERIES_317))
            step(KeyEvent.KEYCODE_DPAD_DOWN,nav(UiText.SETTINGS_268))
            step(KeyEvent.KEYCODE_DPAD_UP,nav(UiText.SERIES_317))
            step(KeyEvent.KEYCODE_DPAD_UP,nav(UiText.MOVIES_316))
            step(KeyEvent.KEYCODE_DPAD_UP,nav(UiText.HOME_267))
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"media_demo")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"home_library_movies","media_film0")
            if("home_library_movies" in focused()) step(KeyEvent.KEYCODE_DPAD_DOWN,"media_film0")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"media_film1")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"media_film0")
            step(KeyEvent.KEYCODE_DPAD_UP,"home_library_movies","media_demo")
            if("home_library_movies" in focused()) step(KeyEvent.KEYCODE_DPAD_UP,"media_demo")
            capture("home-card-focus")
            key(KeyEvent.KEYCODE_DPAD_CENTER);ready("detail_play");expect("detail_play")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"version_mp4")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"version_mkv")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"version_mp4")
            step(KeyEvent.KEYCODE_DPAD_UP,"detail_play")
            step(KeyEvent.KEYCODE_BACK,"media_demo")
            step(KeyEvent.KEYCODE_DPAD_LEFT,nav(UiText.HOME_267));capture("home-sidebar-focus")
        }
    }

    @Test fun loginProfilesAndCredentialsAreReachableWithDirections() {
        ActivityScenario.launch(LoginActivity::class.java).use {
            ready("login_user_u3");expect("login_username")
            step(KeyEvent.KEYCODE_DPAD_UP,"login_server")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"login_username")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"login_password")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"login_connect")
            step(KeyEvent.KEYCODE_DPAD_UP,"login_password")
            step(KeyEvent.KEYCODE_DPAD_UP,"login_username")
            step(KeyEvent.KEYCODE_DPAD_UP,"login_server")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"login_user_u1")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"login_user_u2")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"login_user_u2")
            compose.onNodeWithTag("login_username").assertTextEquals("Sarah")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"login_user_u3")
            step(KeyEvent.KEYCODE_DPAD_UP,"login_user_u2")
            step(KeyEvent.KEYCODE_DPAD_UP,"login_user_u1")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"login_server");capture("login-server-focus")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"login_server")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"login_server")
            step(KeyEvent.KEYCODE_BACK,"login_server")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"login_user_u1")
        }
    }

    @Test fun settingsQuickControlsTilesEditorAndSidebarUseDirections() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            ready("quick_player_INTERNAL");expect("quick_player_INTERNAL")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"quick_autonext")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"quick_osd")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"quick_autonext")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"settings_tile_0")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"settings_tile_1")
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"settings_tile_2")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"settings_tile_5")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"settings_tile_4")
            step(KeyEvent.KEYCODE_DPAD_LEFT,"settings_tile_3")
            step(KeyEvent.KEYCODE_DPAD_UP,"settings_tile_0");capture("settings-tile-focus")
            step(KeyEvent.KEYCODE_DPAD_CENTER,"tab0")
            step(KeyEvent.KEYCODE_DPAD_DOWN,"player")
            step(KeyEvent.KEYCODE_DPAD_UP,"tab0")
            step(KeyEvent.KEYCODE_BACK,"settings_tile_0")
            step(KeyEvent.KEYCODE_DPAD_LEFT,nav(UiText.SETTINGS_268))
            step(KeyEvent.KEYCODE_DPAD_RIGHT,"settings_tile_0","settings_tile_3")
        }
    }
}
