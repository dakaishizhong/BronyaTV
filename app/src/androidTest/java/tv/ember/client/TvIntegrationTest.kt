package tv.ember.client

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.common.Player
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.MimeTypes
import androidx.lifecycle.Lifecycle
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.network.HttpClient
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.ui.*

@RunWith(AndroidJUnit4::class)
class TvIntegrationTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun text(value: String)=compose.onAllNodesWithText(value,substring=true).fetchSemanticsNodes().isNotEmpty()
    private fun click(tag: String) { compose.waitUntil(30000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() };compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() } }
    private val fixtureServer get()=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val app get() = context.applicationContext as BronyaApp
    private fun children(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun views(a: android.app.Activity) = children(a.window.decorView)
    private fun fixture(url: String) { HttpClient.api.newCall(Request.Builder().url("$fixtureServer$url").build()).execute().close() }
    private fun await(timeout: Long = 30000, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeout
        while(System.currentTimeMillis() < end) { if(check()) return; Thread.sleep(200) }
        fail("Condition did not become true within $timeout ms")
    }
    @Before fun setup() {
        tv.ember.client.i18n.AppLanguage.save(context,"zh")
        app.sessions.save(Session(fixtureServer, "fixture-token", "u1", "Demo TV"))
        // Keep transport/retry tests independent of the previous disk-prefetch device scenarios.
        app.settings.diskCacheMb=0;app.settings.osd = false; app.settings.debugEnabled = false; app.settings.receiveBufferKb = 0; app.settings.streamConnections=InstrumentationRegistry.getArguments().getString("connections")?.toIntOrNull() ?: 1
        fixture("/fixture/control?fail=0")
    }
    @Test fun encryptedSessionSurvivesReloadWithoutPlaintext() {
        assertEquals("fixture-token", app.sessions.load()?.token)
        assertEquals(app.sessions.load(),tv.ember.client.data.SessionStore(context).load())
        val stored = context.getSharedPreferences("session", 0).getString("encrypted", "")!!
        assertFalse(stored.contains("fixture-token")); assertFalse(stored.contains("http://"))
    }
    @Test fun homeDisplaysServerContentAndHasRemoteFocus() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { text("Ocean of light") && text("After the horizon") }
            compose.onNodeWithTag("nav_首页").assertIsFocused();compose.onNodeWithTag("nav_设置").assertExists()
        }
    }
    @Test fun usernamePasswordLoginPersistsAuthenticatedSession() {
        app.sessions.clear()
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            compose.waitUntil(30000) { compose.onAllNodesWithTag("login_password").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("login_server").performTextReplacement(fixtureServer);compose.onNodeWithTag("login_username").performTextReplacement("demo");compose.onNodeWithTag("login_password").performTextReplacement("demo");click("login_connect")
            await { app.sessions.load()?.userId=="u1" }
        }
    }
    private fun playback(source: String, fail: Int = 0, osd: Boolean = false) {
        fixture("/fixture/control?fail=$fail"); app.settings.osd=osd
        val intent = Intent(context, PlaybackActivity::class.java).putExtra("item_id", "demo").putExtra("source_id", source).putExtra("position_ms", 10000L)
        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            await(if(fail>0) 90000 else 30000) {
                var ready=false
                scenario.onActivity { a -> val p=views(a).filterIsInstance<PlayerView>().first().player; ready=p?.playbackState==Player.STATE_READY && p.currentPosition>=10000 }
                ready
            }
            var position=0L
            scenario.onActivity { a ->
                val p=views(a).filterIsInstance<PlayerView>().first().player!!
                position=p.currentPosition
                assertTrue(p.currentTracks.groups.any { it.type==androidx.media3.common.C.TRACK_TYPE_VIDEO })
                assertTrue(p.currentTracks.groups.any { it.type==androidx.media3.common.C.TRACK_TYPE_AUDIO })
                if(source=="mkv") assertTrue(p.currentTracks.groups.any { it.type==androidx.media3.common.C.TRACK_TYPE_TEXT })
                if(source in listOf("mp4", "mkv")) {
                    val textGroups=p.currentTracks.groups.filter { it.type==C.TRACK_TYPE_TEXT }
                    if(source=="mkv") assertEquals(2,textGroups.size)
                    // Media3 pre-parses text into application/x-media3-cues. ASS is the fixture's last subtitle track.
                    val sub=if(source=="mkv") textGroups.last() else textGroups.first()
                    p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(sub.mediaTrackGroup, 0)).build()
                }
            }
            await(10000) { var advanced=false; scenario.onActivity { a -> advanced=views(a).filterIsInstance<PlayerView>().first().player!!.currentPosition>position+500 }; advanced }
            if(source in listOf("mp4", "mkv")) await(10000) {
                var subtitle=false
                scenario.onActivity { a -> subtitle=views(a).filterIsInstance<PlayerView>().first().player!!.currentCues.cues.any { it.text.toString().contains(if(source=="mp4") "SRT subtitle" else "ASS subtitle") } }
                subtitle
            }
            if(source=="mp4") {
                scenario.onActivity { a ->
                    val p=views(a).filterIsInstance<PlayerView>().first().player!!
                    val group=p.currentTracks.groups.first { g -> g.type==C.TRACK_TYPE_AUDIO && (0 until g.length).any { g.getTrackFormat(it).language=="zh" || g.getTrackFormat(it).language=="zho" } }
                    val index=(0 until group.length).first { group.getTrackFormat(it).language in listOf("zh","zho") }
                    p.trackSelectionParameters=p.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup,index)).build()
                }
                await(10000) { var selected=false; scenario.onActivity { a -> val p=views(a).filterIsInstance<PlayerView>().first().player!!; selected=p.currentTracks.groups.any { g -> g.type==C.TRACK_TYPE_AUDIO && (0 until g.length).any { g.isTrackSelected(it) && g.getTrackFormat(it).language in listOf("zh","zho") } } }; selected }
                scenario.moveToState(Lifecycle.State.CREATED)
                scenario.onActivity { a -> assertNull(views(a).filterIsInstance<PlayerView>().first().player) }
                scenario.moveToState(Lifecycle.State.RESUMED)
                await(15000) { var ready=false; scenario.onActivity { a -> val p=views(a).filterIsInstance<PlayerView>().first().player; ready=p?.playbackState==Player.STATE_READY && p.currentPosition>10000 }; ready }
            }
            if(osd) await(15000) {
                text("缓冲内存") && (app.settings.streamConnections==1 || text(if(source=="norange") "单连接" else "独立 TCP ${app.settings.streamConnections} 路分段")) &&
                    (source!="queryauth" || (text("TCP 接收") && text("请求 1024KB") && !text("等待连接")))
            }
        }
    }
    @Test fun mp4DirectPlaybackWithExternalSubtitleAndOsd() = playback("mp4", osd=true)
    @Test fun mkvDirectPlaybackWithEmbeddedSubtitles() = playback("mkv")
    @Test fun fourConnectionsKeepMp4AudioSubtitlesAndLifecyclePlaybackWorking() {
        app.settings.streamConnections=4;playback("mp4",osd=true)
    }
    @Test fun eightConnectionsKeepMkvSubtitlesWorking() {
        app.settings.streamConnections=8;playback("mkv",osd=true)
    }
    @Test fun fourConnectionsPreserve410OriginalFileRecovery() {
        app.settings.streamConnections=4;playback("originalfallback",osd=true)
    }
    @Test fun serverIgnoringRangesStillPlaysThroughSingleConnectionFallback() {
        app.settings.streamConnections=4;playback("norange",osd=true)
    }
    @Test fun seekingWithFourConnectionsPreservesPlaybackAndDisplaysSourceAndDecoderDetails() {
        app.settings.streamConnections=4;app.settings.osd=true
        val intent=Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","mp4")
        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            await { var ready=false;scenario.onActivity { a -> ready=views(a).filterIsInstance<PlayerView>().first().player?.playbackState==Player.STATE_READY };ready }
            scenario.onActivity { a -> views(a).filterIsInstance<PlayerView>().first().player!!.seekTo(65000) }
            await { var ready=false;scenario.onActivity { a ->
                val p=views(a).filterIsInstance<PlayerView>().first().player!!;ready=p.playbackState==Player.STATE_READY && p.currentPosition>=65000
            };ready && text("独立 TCP 4 路分段") && text("片源 MP4") && text("解码输入 video/avc") }
        }
    }
    @Test fun hevc10BitDirectPlayback() = playback("hevc")
    @Test fun recoversFromRepeatedHttp503AtSavedPosition() = playback("mp4", fail=5)
    @Test fun directStreamRequiringQueryAuthenticationActuallyPlaysWithReceiveBuffer() {
        app.settings.receiveBufferKb=1024
        playback("queryauth", osd=true)
    }
    @Test fun rejectedHttp410UrlIsRefreshedAndPlaybackPositionIsPreserved() {
        playback("refreshurl")
        val state=HttpClient.api.newCall(Request.Builder().url("$fixtureServer/fixture/status").build()).execute().use { org.json.JSONObject(it.body!!.string()) }
        assertEquals(2,state.getInt("url_generation"))
    }
    @Test fun rejectedStreamMp4FallsBackToTheSelectedOriginalMkvAndKeepsPosition() = playback("originalfallback")
    @Test fun permanentHttp403IsShownNumericallyAndRefreshIsBounded() {
        fixture("/fixture/control?fail=10&status=403")
        val intent=Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","mp4")
        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            await { text("HTTP 403") && text("拒绝访问") }
        }
        val state=HttpClient.api.newCall(Request.Builder().url("$fixtureServer/fixture/status").build()).execute().use { org.json.JSONObject(it.body!!.string()) }
        assertEquals("One failed request followed by one refresh; no retry loop",8,state.getInt("fail"))
    }
    @Test fun inaccessibleExternalSubtitleDoesNotPreventVideoPlayback() {
        fixture("/fixture/control?subtitle_fail=403")
        val intent=Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","mp4").putExtra("position_ms",10000L)
        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            await { var playing=false; scenario.onActivity { a -> val p=views(a).filterIsInstance<PlayerView>().first().player; playing=p?.playbackState==Player.STATE_READY && p.currentPosition>10500 };playing }
        }
    }
    @Test fun softwareDecoderFallbackPreservesPlaybackAfterInjectedDecoderFailure() {
        val intent=Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","mp4").putExtra("position_ms",10000L)
        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            fun playing():Boolean { var ready=false; scenario.onActivity { a -> val p=views(a).filterIsInstance<PlayerView>().first().player; ready=p?.playbackState==Player.STATE_READY && p.currentPosition>=10000 }; return ready }
            await { playing() }
            scenario.onActivity { it.onPlayerError(androidx.media3.common.PlaybackException("Injected decoder failure",null,androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) }
            await { playing() }
        }
    }
    @Test fun restoredSessionValidatesOnDevice() = runBlocking { app.api.validate(app.sessions.load()!!) }
    @Test fun debugModeIsHiddenUntilSevenRemoteClicks() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            assertFalse(app.settings.debugEnabled);click("settings_tile_5");repeat(7) { click("about") }
            assertTrue(app.settings.debugEnabled);compose.onNodeWithTag("debug").assertExists()
        }
    }
}
