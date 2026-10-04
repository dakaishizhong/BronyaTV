package tv.ember.client

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
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
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.ui.*

@RunWith(AndroidJUnit4::class)
class VisualNavigationDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as BronyaApp
    private val fixtureServer get()=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
    private fun nodes(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { nodes(v.getChildAt(it)) } else emptyList()
    private fun await(block: ()->Boolean) { val end=SystemClock.elapsedRealtime()+60000;while(SystemClock.elapsedRealtime()<end) { if(block()) return;Thread.sleep(100) };fail("UI condition timed out") }
    private fun ready(tag: String) { compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun click(tag: String) { ready(tag);compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() };compose.waitForIdle();compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() } }
    private fun focus(tag: String) { ready(tag);compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() };compose.waitForIdle() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code);compose.waitForIdle() }
    private fun capture(name: String) {
        if(InstrumentationRegistry.getArguments().getString("screenshots")!="true") return
        instrumentation.waitForIdleSync();Thread.sleep(500)
        val bitmap=instrumentation.uiAutomation.takeScreenshot() ?: return
        java.io.File(context.getExternalFilesDir(null),"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Before fun setup() {
        tv.ember.client.i18n.AppLanguage.save(context,"en")
        app.sessions.save(Session(fixtureServer,"fixture-token","u1","Demo TV"));app.settings.introSeconds=0;app.settings.outroSeconds=0
    }
    @Test fun languageSelectionPersistsAcrossRecreatedScreens() {
        ActivityScenario.launch(SettingsActivity::class.java).use {
            click("settings_tile_4");click("interface_language");click("dialog_option_1")
            compose.waitUntil(60000) { compose.onAllNodesWithText("界面语言",substring=true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("zh",tv.ember.client.i18n.AppLanguage.read(context));click("interface_language");click("dialog_option_0")
            compose.waitUntil(60000) { compose.onAllNodesWithText("Interface language",substring=true).fetchSemanticsNodes().isNotEmpty() }
            assertEquals("en",tv.ember.client.i18n.AppLanguage.read(context))
        }
        ActivityScenario.launch(MainActivity::class.java).use { ready("nav_Home");compose.onNodeWithTag("nav_Home").assertExists() }
    }
    @Test fun searchFiltersAndHistoryUseRealServerResults() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_film3");click("nav_Search")
            compose.onNodeWithTag("search_query").performTextReplacement("Trip");click("search_submit")
            ready("media_demo");compose.onNodeWithTag("media_demo").assertExists();compose.onAllNodesWithTag("media_film0").assertCountEquals(0)
            compose.onNodeWithTag("filter__Trip").assertExists();capture("search")
        }
    }
    @Test fun sidebarCanOpenFavoritesAndReturnHomeWithRemoteFocus() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_film3");focus("nav_Home");key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("nav_Movies").assertIsFocused()
            click("nav_Favorites");ready("media_demo");key(KeyEvent.KEYCODE_BACK);ready("hero_details");compose.onNodeWithTag("nav_Home").assertExists()
        }
    }
    @Test fun homeFitsFiveCardsAndRemoteFocusStaysOnArtwork() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_film3")
            listOf("demo","film0","film1","film2","film3","film4").forEach { id ->
                val rect=compose.onNodeWithTag("artwork_$id",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
                assertEquals(16.0/9.0,rect.width.toDouble()/rect.height,.06)
                assertTrue(rect.right<=context.resources.displayMetrics.widthPixels);assertTrue(rect.bottom<=context.resources.displayMetrics.heightPixels)
                listOf("media_title_$id","media_info_$id").forEach { tag ->
                    compose.onNodeWithTag(tag,useUnmergedTree=true).assertIsDisplayed()
                    val text=compose.onNodeWithTag(tag,useUnmergedTree=true).fetchSemanticsNode()
                    assertEquals("Both rows must show complete card captions",text.size.height.toFloat(),text.boundsInRoot.height,1f)
                }
            }
            focus("media_film0");val before=compose.onNodeWithTag("media_film0").fetchSemanticsNode().boundsInRoot
            repeat(4) { key(KeyEvent.KEYCODE_DPAD_RIGHT) };compose.onNodeWithTag("media_film4").assertIsFocused()
            val after=compose.onNodeWithTag("media_film0").fetchSemanticsNode().boundsInRoot;assertEquals(before,after)
            key(KeyEvent.KEYCODE_MENU);ready("dialog_option_0");key(KeyEvent.KEYCODE_BACK)
            focus("media_demo");val rowTop=compose.onNodeWithTag("media_film0").fetchSemanticsNode().boundsInRoot.top
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("home_library_movies").assertIsFocused();key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("media_film0").assertIsFocused()
            assertEquals(rowTop,compose.onNodeWithTag("media_film0").fetchSemanticsNode().boundsInRoot.top,1f)
            key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("media_demo").assertIsFocused();capture("home")
        }
    }
    @Test fun detailsAndSettingsKeepPrimaryActionsReachable() {
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","demo")).use {
            ready("detail_play");compose.waitUntil(30000) { compose.onNodeWithTag("detail_play").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] };compose.onNodeWithTag("detail_play").assertIsFocused();compose.onNodeWithTag("version_mp4").assertExists();capture("detail")
        }
        app.settings.diskCacheMb=0
        ActivityScenario.launch(SettingsActivity::class.java).use {
            ready("settings_tile_2");capture("settings");click("settings_tile_2");click("disk_capacity");ready("dialog_option_0");key(KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("disk_capacity").assertIsFocused()
        }
    }
    @Test fun playerShortcutKeepsPlaybackRunningAndOpensSubtitles() {
        val source=InstrumentationRegistry.getArguments().getString("playbackSource") ?: "mp4"
        ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id",source)).use { scenario ->
            await { var ok=false;scenario.onActivity { a -> ok=nodes(a.window.decorView).filterIsInstance<PlayerView>().first().player?.playbackState==Player.STATE_READY };ok }
            key(KeyEvent.KEYCODE_DPAD_UP);ready("player_subtitles");capture("player")
            var position=0L;scenario.onActivity { a -> position=nodes(a.window.decorView).filterIsInstance<PlayerView>().first().player!!.currentPosition }
            key(KeyEvent.KEYCODE_DPAD_UP);ready("player_subtitles");click("player_subtitles");ready("dialog_option_0");key(KeyEvent.KEYCODE_BACK)
            // Cancel returns to the options menu; the next Back returns to playback.
            ready("dialog_option_0");key(KeyEvent.KEYCODE_BACK)
            await { var advanced=false;scenario.onActivity { a -> advanced=nodes(a.window.decorView).filterIsInstance<PlayerView>().first().player!!.currentPosition>position+500 };advanced }
        }
    }
    @Test fun unreachableServerShowsRetryWithoutCrashingHome() {
        app.sessions.save(Session("http://127.0.0.1:9","fixture-token","u1","Demo TV"))
        ActivityScenario.launch(MainActivity::class.java).use { ready("browse_retry");compose.onNodeWithTag("browse_retry").assertExists() }
    }
}
