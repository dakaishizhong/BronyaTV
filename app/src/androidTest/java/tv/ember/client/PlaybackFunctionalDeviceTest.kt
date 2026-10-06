package tv.ember.client

import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Protocol
import okhttp3.Request
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.i18n.*
import tv.ember.client.monitor.HttpTransportMonitor
import tv.ember.client.network.*
import tv.ember.client.player.PlaybackActivity
import java.io.ByteArrayOutputStream
import java.io.File

/** Verify effects on the real player and network, beyond focus/visual changes. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class PlaybackFunctionalDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val app get()=context.applicationContext as BronyaApp
    private val server get()=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
    private val folder get()=File(context.getExternalFilesDir(null),"player-172").apply { mkdirs() }
    private fun ready(tag:String) { compose.waitUntil(90000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun key(code:Int) { instrumentation.sendKeyDownUpSync(code);compose.waitForIdle() }
    private fun click(tag:String) {
        if(tag.startsWith("player_option_") || tag.startsWith("player_menu_")) compose.onNodeWithTag("player_options").performScrollToNode(hasTestTag(tag))
        ready(tag)
        if(tag.startsWith("dialog_option_")) compose.onNodeWithTag(tag).performScrollTo()
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle();key(KeyEvent.KEYCODE_DPAD_CENTER)
    }
    private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun player(scenario:ActivityScenario<PlaybackActivity>,block:(Player)->Unit) { scenario.onActivity { block(views(it.window.decorView).filterIsInstance<PlayerView>().first().player!!) } }
    private fun waitPlayer(scenario:ActivityScenario<PlaybackActivity>) { compose.waitUntil(90000) { var ready=false;scenario.onActivity { ready=views(it.window.decorView).filterIsInstance<PlayerView>().first().player?.playbackState==Player.STATE_READY };ready } }
    private fun capture(name:String) { instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> File(folder,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() } }
    private fun fixture(query:String) { HttpClient.api.newCall(Request.Builder().url("$server/fixture/control?$query").build()).execute().use { check(it.isSuccessful) } }
    @Before fun setup() {
        fixture("fail=0&chapters=1&redirect_video=1&range_delay=0.06&small_catalog=1")
        AppLanguage.save(context,"zh");app.sessions.save(Session(server,"fixture-token","u1","CinemaMaster"))
        app.settings.diskCacheMb=0;app.settings.streamConnections=4;app.settings.osd=false
        app.settings.resizeMode=0;app.settings.introSeconds=0;app.settings.outroSeconds=0
        app.settings.subtitleLanguage="zh"
    }
    @After fun reset() { app.settings.osd=false;fixture("fail=0&small_catalog=1") }
    private fun launch()=ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","vp8"))

    @Test fun everyVisibleControlChangesPlayerStateAndUnavailableChapterIsDisabled() {
        launch().use { scenario ->
            waitPlayer(scenario);key(KeyEvent.KEYCODE_MEDIA_PAUSE);key(KeyEvent.KEYCODE_DPAD_UP)
            compose.onNodeWithTag("player_exit").assertDoesNotExist()
            click("next_section");player(scenario) { assertTrue(it.currentPosition in 19500..20500) }
            click("previous_section");player(scenario) { assertTrue(it.currentPosition<500) }
            click("next_section");click("next_section");player(scenario) { assertTrue(it.currentPosition in 39500..40500) }
            compose.onNodeWithTag("next_section").assertIsNotEnabled()
            click("play_pause");player(scenario) { assertTrue(it.playWhenReady) }
            click("play_pause");player(scenario) { assertFalse(it.playWhenReady) }
            click("player_audio");click("dialog_option_1")
            player(scenario) { assertTrue(C.TRACK_TYPE_AUDIO in it.trackSelectionParameters.disabledTrackTypes) }
            compose.onNodeWithTag("player_audio").assertContentDescriptionEquals(Tr.text(UiText.PLAYER_AUDIO_LABEL,Tr.text(UiText.MUTE_234)))
            click("player_audio");click("dialog_option_0")
            player(scenario) { assertFalse(C.TRACK_TYPE_AUDIO in it.trackSelectionParameters.disabledTrackTypes) }
            click("player_subtitles");click("dialog_option_1")
            player(scenario) { assertTrue(C.TRACK_TYPE_TEXT in it.trackSelectionParameters.disabledTrackTypes) }
            compose.onNodeWithTag("player_subtitles").assertContentDescriptionEquals(Tr.text(UiText.PLAYER_SUBTITLE_LABEL,Tr.text(UiText.OFF_187)))
            click("player_subtitles");click("dialog_option_0")
            player(scenario) { assertFalse(C.TRACK_TYPE_TEXT in it.trackSelectionParameters.disabledTrackTypes) }
            listOf(4,3,0).forEach { expected -> click("player_aspect");assertEquals(expected,app.settings.resizeMode);scenario.onActivity { assertEquals(expected,views(it.window.decorView).filterIsInstance<PlayerView>().first().resizeMode) };compose.onNodeWithTag("dialog_option_0").assertDoesNotExist() }
            click("playback_timeline");key(KeyEvent.KEYCODE_DPAD_RIGHT);player(scenario) { assertTrue(it.currentPosition>49000) }
            click("player_hud");ready("player_hud_panel");compose.onNodeWithTag("player_hud").assertIsSelected()
            listOf(UiText.HUD_CPU,UiText.HUD_MEMORY,UiText.HUD_CONNECTIONS,UiText.HUD_FOREGROUND,UiText.HUD_PREFETCH,UiText.HUD_HTTP,UiText.HUD_VIDEO_DECODER).forEach { compose.onNodeWithText(Tr.text(it)).assertExists() }
            compose.waitUntil(5000) { compose.onAllNodesWithText(Tr.text(UiText.WAITING_029),substring=true).fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty() }
            android.os.SystemClock.sleep(1800) // Capture after the native Toast feedback has gone.
            capture("player-hud-controls");click("player_hud");compose.onNodeWithTag("player_hud_panel").assertDoesNotExist();compose.onNodeWithTag("player_hud").assertIsNotSelected()
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun settingsApplyInOnePanelAndDiskModeKeepsRealParallelConnections() {
        app.settings.diskCacheMb=512
        launch().use { scenario ->
            waitPlayer(scenario);key(KeyEvent.KEYCODE_MEDIA_PAUSE);key(KeyEvent.KEYCODE_DPAD_UP)
            scenario.onActivity { activity ->
                val field=PlaybackActivity::class.java.getDeclaredField("transport").apply { isAccessible=true }
                val transport=field.get(activity) as HttpTransportMonitor
                assertTrue("Real parallel TCP transfers must overlap",transport.peakConnections.get()>=3)
                println("DEVICE parallel peak TCP=${transport.peakConnections.get()}")
            }
            key(KeyEvent.KEYCODE_MENU)
            click("player_option_speed_3");player(scenario) { assertEquals(1.25f,it.playbackParameters.speed,.01f) };compose.onNodeWithTag("player_option_speed_3").assertIsSelected()
            click("player_option_aspect_1");assertEquals(4,app.settings.resizeMode);compose.onNodeWithTag("player_option_aspect_1").assertIsSelected()
            click("player_option_subtitle_size_2");assertEquals(120,app.settings.subtitleScale);compose.onNodeWithTag("player_option_subtitle_size_2").assertIsSelected()
            click("player_option_connections_2");assertEquals(2,app.settings.streamConnections);waitPlayer(scenario)
            compose.onNodeWithTag("player_option_connections_2").assertIsSelected();player(scenario) { assertFalse(it.playWhenReady);assertEquals(1.25f,it.playbackParameters.speed,.01f) }
            click("player_menu_hud");compose.onNodeWithTag("player_menu_hud").assertIsSelected();assertTrue(app.settings.osd)
            capture("one-panel-settings");key(KeyEvent.KEYCODE_BACK)
            ready("player_hud_panel");capture("disk-parallel-hud")
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }

    @Test fun redirectedRangeReopensAfterSeekWithoutFallingBackOrChangingBytes() {
        val url="$server/Videos/demo/stream.webm?MediaSourceId=vp8"
        val expected=HttpClient.playback.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.bytes() }
        val monitor=HttpTransportMonitor();val client=HttpClient.playback.newBuilder().dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
            .protocols(listOf(Protocol.HTTP_1_1)).eventListenerFactory(monitor).build()
        val status=RangePlaybackStatus(4,512*1024)
        val source=RangePlaybackDataSource(OkHttpDataSource.Factory(client),client,url,emptyMap(),status)
        fun read(position:Long):ByteArray {
            source.open(DataSpec.Builder().setUri(url).setPosition(position).build())
            assertEquals("Redirect must preserve the URI reused by progressive seeks",url,source.uri.toString())
            val out=ByteArrayOutputStream();val buffer=ByteArray(7919)
            while(true) { val n=source.read(buffer,0,buffer.size);if(n==C.RESULT_END_OF_INPUT) break;out.write(buffer,0,n) }
            source.close();return out.toByteArray()
        }
        try {
            assertArrayEquals(expected,read(0));assertArrayEquals(expected.copyOfRange(12345,expected.size),read(12345))
            assertFalse(status.rangeUnsupported);assertTrue("Independent requests must use multiple TCP connections",monitor.peakConnections.get()>=3)
            println("DEVICE redirect seek: exact bytes=${expected.size}, peak TCP=${monitor.peakConnections.get()}, mode=${status.mode}")
        } finally { source.close();client.dispatcher.executorService.shutdown();client.connectionPool.evictAll() }
    }
}
