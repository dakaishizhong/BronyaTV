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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.*
import okhttp3.Request
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.data.*
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.ui.*

@RunWith(AndroidJUnit4::class)
class ComposeMigrationDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as BronyaApp
    private val server get()=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
    private val session get()=Session(server,"fixture-token","u1","Demo TV")
    private fun ready(tag: String) { compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun click(tag: String) { ready(tag);compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() };compose.waitForIdle();compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() } }
    private fun pick(tag: String,index: Int) { click("picker_$tag");click("dialog_option_$index") }
    private fun focus(tag: String) { ready(tag);compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() } }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code);compose.waitForIdle() }
    private fun request(path: String)=tv.ember.client.network.HttpClient.api.newCall(Request.Builder().url(server+path).build()).execute().use { it.body!!.string() }
    private fun views(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
    private fun resumed(): Activity? { var current: Activity?=null;instrumentation.runOnMainSync { current=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() };return current }
    private fun capture(name: String) {
        if(InstrumentationRegistry.getArguments().getString("screenshots")!="true") return
        Thread.sleep(500);instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.getExternalFilesDir(null),"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Before fun setup() { tv.ember.client.i18n.AppLanguage.save(context,"en");app.sessions.save(session);app.settings.introSeconds=0;app.settings.outroSeconds=0;request("/fixture/control?fail=0") }
    @After fun reset() { request("/fixture/control?fail=0") }
    @Test fun loginUsesComposeFieldsAndDoesNotRestorePassword() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            ready("login_server");compose.onNodeWithTag("login_server").performTextReplacement(server)
            compose.onNodeWithTag("login_username").performTextReplacement("demo")
            focus("login_server");key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("login_username").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("login_password").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("login_reveal").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("login_connect").assertIsFocused()
            compose.onNodeWithTag("login_password").performTextReplacement("demo");scenario.recreate()
            ready("login_password");assertEquals("",compose.onNodeWithTag("login_password").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text)
            capture("login");compose.onNodeWithTag("login_password").performTextReplacement("demo");click("login_connect")
            ready("hero_details");assertEquals("u1",app.sessions.load()!!.userId)
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() } }
        }
    }
    @Test fun detailSelectedVersionIsNegotiatedAtPlayAndNeverPromptsAgain() {
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","demo")).use {
            ready("version_vp8");click("version_vp8");click("detail_play")
            compose.waitUntil(60000) { resumed() is PlaybackActivity }
            val end=SystemClock.elapsedRealtime()+60000;var ready=false
            while(SystemClock.elapsedRealtime()<end && !ready) { instrumentation.runOnMainSync {
                val a=ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlaybackActivity>().firstOrNull()
                ready=a?.let { views(it.window.decorView).filterIsInstance<PlayerView>().first().player?.playbackState==Player.STATE_READY } ?: false
                if(ready) assertEquals("vp8",a!!.intent.getStringExtra("source_id"))
            };if(!ready) Thread.sleep(100) }
            assertTrue("Selected VP8 must reach ready",ready)
            val requests=JSONObject(request("/fixture/status")).getJSONArray("playback")
            assertEquals("vp8",requests.getJSONObject(requests.length()-1).getString("MediaSourceId"))
            compose.onAllNodesWithTag("dialog_option_0").assertCountEquals(0)
            instrumentation.runOnMainSync { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).forEach { it.finish() } }
            ready("detail_play");compose.waitUntil(30000) { compose.onNodeWithTag("detail_play").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] };compose.onNodeWithTag("detail_play").assertIsFocused()
        }
    }
    @Test fun detailRecreationRestoresChosenVersionFocus() {
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","demo")).use { scenario ->
            ready("version_vp8");click("version_vp8");scenario.recreate();ready("version_vp8")
            compose.waitUntil(30000) { compose.onNodeWithTag("version_vp8").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused] }
            compose.onNodeWithTag("version_vp8").assertIsFocused()
        }
    }
    @Test fun unavailableSelectedVersionDoesNotPlayADifferentSource() {
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","demo")).use {
            ready("version_vp8");click("version_vp8");request("/fixture/control?missing_source=vp8");click("detail_play")
            compose.waitUntil(60000) { compose.onAllNodesWithText("no longer available",substring=true).fetchSemanticsNodes().isNotEmpty() }
            assertTrue(resumed() is DetailActivity);compose.onNodeWithTag("version_vp8").assertExists()
        }
    }
    @Test fun categoryFilteringPagingAndFolderReturnRestoreState() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready("media_film3");click("nav_Movies");ready("media_film0")
            compose.onAllNodesWithTag("picker_genre").assertCountEquals(0);capture("category")
            click("browse_filters");ready("picker_genre");pick("genre",2);ready("media_film1")
            compose.waitUntil(60000) { compose.onAllNodesWithTag("media_film0").fetchSemanticsNodes().isEmpty() }
            pick("year",1);pick("watch",1);ready("media_film9")
            pick("sort",1);ready("media_film39");focus("media_film39")
            click("media_film39");ready("detail_play");key(KeyEvent.KEYCODE_BACK);ready("media_film39");compose.onNodeWithTag("media_film39").assertIsFocused()
            pick("genre",0);pick("year",0);pick("watch",0)
            compose.waitUntil(60000) { compose.onAllNodesWithTag("page_next").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false };click("page_next");ready("media_film0")
            compose.onNodeWithTag("page_previous").assertIsEnabled();focus("media_film0");click("media_film0");ready("detail_play");key(KeyEvent.KEYCODE_BACK)
            ready("media_film0");compose.onNodeWithTag("media_film0").assertIsFocused();compose.onNodeWithTag("page_previous").assertIsEnabled()
            scenario.recreate();ready("media_film0");compose.onNodeWithTag("picker_genre").assertExists();compose.onNodeWithTag("media_film0").assertIsFocused();compose.onNodeWithTag("page_previous").assertIsEnabled()
            click("filter__movies");compose.waitUntil(60000) { compose.onAllNodesWithTag("page_next").fetchSemanticsNodes().singleOrNull()?.config?.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)==false };click("page_next");ready("media_collection");click("media_collection");ready("media_film0");key(KeyEvent.KEYCODE_BACK);ready("media_collection")
        }
    }
    @Test fun horizontalHomePositionAndFocusReturnAfterDetails() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_film3");focus("media_film3");repeat(3) { key(KeyEvent.KEYCODE_DPAD_RIGHT) }
            compose.onNodeWithTag("media_film6").assertIsFocused();val before=compose.onNodeWithTag("media_film6").fetchSemanticsNode().boundsInRoot
            click("media_film6");ready("detail_play");key(KeyEvent.KEYCODE_BACK);ready("media_film6")
            compose.onNodeWithTag("media_film6").assertIsFocused();assertEquals(before,compose.onNodeWithTag("media_film6").fetchSemanticsNode().boundsInRoot)
        }
    }
    @Test fun homeUsesActualServerLibrariesAsDirectSections() {
        request("/fixture/control?view_type=UserView")
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("home_library_movies");compose.onAllNodesWithText("Recently added").assertCountEquals(0)
            compose.onNodeWithTag("home_library_movies").assertTextContains("Cinema",substring=true)
            click("home_library_movies");ready("media_film0");compose.onAllNodesWithTag("picker_genre").assertCountEquals(0)
            key(KeyEvent.KEYCODE_BACK);ready("home_library_movies");compose.onNodeWithTag("home_library_movies").assertIsFocused()
        }
    }
    @Test fun tvLibraryKeepsSeriesNavigationAcrossSeasonsAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("home_library_movies");compose.onNodeWithTag("home_rows").performScrollToNode(hasTestTag("home_library_tv"))
            click("home_library_tv");ready("media_series");compose.onNodeWithTag("nav_Series").assertIsSelected()
            click("media_series");ready("media_season1");compose.onNodeWithTag("nav_Series").assertIsSelected()
            key(KeyEvent.KEYCODE_BACK);ready("media_series");compose.onNodeWithTag("media_series").assertIsFocused()
            key(KeyEvent.KEYCODE_BACK);ready("home_library_tv");compose.onNodeWithTag("home_library_tv").assertIsFocused()
        }
    }
    @Test fun imagesAreSizedSharedBoundedAndClearedIndependentlyOfMetadata()=runBlocking {
        val cache=app.imageCache;cache.clear();val initial=cache.snapshot()
        val item=VideoItem("demo","A Trip to the Moon","Movie",imageTag="cache-test")
        val url=app.api.imageUrl(session,item,160)
        val values=coroutineScope { (0 until 16).map { async { cache.load(session,url,160,90) } }.awaitAll() }
        assertNotNull(values.first());assertTrue(values.all { it===values.first() });assertTrue(values.first()!!.width<=320);assertTrue(values.first()!!.height<=180)
        assertEquals(1,cache.snapshot().networkRequests-initial.networkRequests)
        val encodedSize=cache.usedBytes()
        cache.load(session,url,80,45)
        assertEquals("A second display size reuses the same encoded image",1,cache.snapshot().networkRequests-initial.networkRequests)
        assertEquals("Display sizes do not duplicate disk data",encodedSize,cache.usedBytes())
        val quota=app.settings.imageCacheMb.toLong()*1048576;assertTrue(cache.usedBytes() in 1..quota)
        cache.trim(true);cache.load(session,url,160,90);assertEquals(1,cache.snapshot().networkRequests-initial.networkRequests);assertTrue(cache.snapshot().diskHits>initial.diskHits)
        cache.load(session,url+"&tag=new",160,90);assertEquals(2,cache.snapshot().networkRequests-initial.networkRequests)
        cache.load(session.copy(userId="u2"),url,160,90);assertEquals(3,cache.snapshot().networkRequests-initial.networkRequests)
        app.api.detail(session,"demo");cache.playback(true);assertTrue(cache.snapshot().memoryBudget<=3*1048576)
        assertTrue(cache.snapshot().peakRequests<=3);cache.clear();assertEquals(0L,cache.usedBytes());assertNotNull(app.api.cachedDetail(session,"demo"))
        assertEquals(0L,app.api.cachedDetail(session,"demo")!!.resumeTicks)
        println("Image cache: ${cache.snapshot()}, decoded=${values.first()!!.width}x${values.first()!!.height}, coalesced observers=16")
        cache.playback(false)
    }
}
