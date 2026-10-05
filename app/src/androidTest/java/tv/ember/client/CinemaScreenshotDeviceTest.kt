package tv.ember.client

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Request
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.i18n.AppLanguage
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.ui.*
import java.io.File

/** Captures the running Android UI with the user's reference-document resources. */
@RunWith(AndroidJUnit4::class)
class CinemaScreenshotDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as BronyaApp
    private var keyboardMode=false
    private fun ready(tag: String) {
        compose.waitUntil(90000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
    }
    private fun focus(tag: String) {
        ready(tag)
        // Foundation clickables become remote-focusable after Android enters keyboard mode.
        if(!keyboardMode) {
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
            keyboardMode=true
        }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).assertIsFocused()
    }
    private fun click(tag: String) { focus(tag);instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);compose.waitForIdle() }
    private fun capture(name: String, settleMillis: Long=3000) {
        compose.waitForIdle();Thread.sleep(settleMillis)
        val screenshot=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folderName=InstrumentationRegistry.getArguments().getString("captureFolder") ?: "cinema-ui-r2"
        val folder=File(context.getExternalFilesDir(null),folderName).apply { mkdirs() }
        File(folder,"$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG,100,it) }
        screenshot.recycle();println("Captured $folderName/$name.png")
    }
    private fun views(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    @Test fun captureReferenceScreens() {
        val homeOnly=InstrumentationRegistry.getArguments().getString("homeOnly")=="true"
        val server=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
        tv.ember.client.network.HttpClient.api.newCall(Request.Builder().url("$server/fixture/control?small_catalog=1&fail=0").build()).execute().use { check(it.isSuccessful) }
        AppLanguage.save(context,"zh")
        app.sessions.save(Session(server,"fixture-token","u1","CinemaMaster"))
        app.settings.diskCacheMb=0;app.settings.osd=false;app.settings.introSeconds=0;app.settings.outroSeconds=0
        app.settings.resizeMode=androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        if(InstrumentationRegistry.getArguments().getString("playerOnly")=="true") {
            capturePlayerScreens();return
        }

        if(!homeOnly) ActivityScenario.launch(LoginActivity::class.java).use {
            ready("login_user_u3")
            compose.onNodeWithTag("login_reveal").assertDoesNotExist();compose.onNodeWithTag("interface_language").assertDoesNotExist()
            compose.onNodeWithTag("login_password").performTextReplacement("previous-password")
            click("login_user_u2")
            assertEquals("",compose.onNodeWithTag("login_password").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            click("login_user_u1");focus("login_connect");capture("login")
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_demo");focus("media_demo");capture("home")
            compose.onNodeWithTag("hero_play").assertDoesNotExist();compose.onNodeWithTag("hero_details").assertDoesNotExist()
            compose.onNodeWithTag("browse_menu").assertDoesNotExist();compose.onNodeWithTag("browse_filters").assertDoesNotExist()
            focus("media_demo");capture("home-focused")
            compose.onNodeWithTag("home_rows").performScrollToNode(hasTestTag("home_library_movies"))
            focus("media_film2");compose.onNodeWithTag("hero_title",useUnmergedTree=true).assertTextEquals("银翼杀手 2049")
            capture("home-bladerunner",10000)
            focus("nav_首页");capture("sidebar")
            val rail=compose.onNodeWithTag("navigation_sidebar",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val content=compose.onNodeWithTag("navigation_content",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val title=compose.onNodeWithTag("hero_title",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            assertTrue("Sidebar must occupy layout space",content.left>=rail.right-1f)
            assertTrue("Expanded sidebar must not obscure the title",title.left>=rail.right)
            click("nav_电影");ready("media_film0");focus("media_film0");capture("category")
            compose.onNodeWithTag("browse_menu").assertDoesNotExist();compose.onNodeWithTag("browse_filters").assertDoesNotExist()
        }
        if(homeOnly) return
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","film2")).use {
            ready("detail_play");focus("detail_play");capture("detail")
            compose.onNodeWithTag("detail_info").assertDoesNotExist();compose.onNodeWithTag("detail_player").assertDoesNotExist()
        }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            ready("quick_player_INTERNAL");focus("quick_player_INTERNAL");capture("settings")
        }
        capturePlayerScreens()
    }

    private fun capturePlayerScreens() {
        app.settings.osd=false
        ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","vp8")).use { scenario ->
            compose.waitUntil(90000) {
                var ready=false
                scenario.onActivity { activity -> ready=views(activity.window.decorView).filterIsInstance<PlayerView>().firstOrNull()?.player?.playbackState==Player.STATE_READY }
                ready
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_MEDIA_PAUSE)
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
            focus("play_pause");capture("player",1000)
            if(InstrumentationRegistry.getArguments().getString("playerOnly")=="true") {
                click("play_pause");capture("player-playing",1000)
                click("play_pause")
            }
            listOf("player_sources","player_rewind","player_forward","player_diagnostics","player_more").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            listOf("previous_section","next_section","player_subtitles","player_audio","player_aspect","player_hud","player_exit").forEach { compose.onNodeWithTag(it).assertExists() }
            click("player_hud");ready("player_hud_panel")
            compose.onNodeWithText("视频流格式").assertExists();compose.onNodeWithText("预载缓冲区").assertExists()
            capture("player-hud",1000)
            click("player_hud")
            click("player_audio");ready("dialog_option_0");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            click("player_aspect");ready("dialog_option_0");instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            click("player_subtitles");ready("dialog_option_0");capture("player-subtitles",1000)
        }
        app.settings.osd=false
    }
}
