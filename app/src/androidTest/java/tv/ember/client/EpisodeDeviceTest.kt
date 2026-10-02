package tv.ember.client

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.monitor.PlayerStatsMonitor
import tv.ember.client.network.HttpClient
import tv.ember.client.player.PlaybackActivity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class EpisodeDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as BronyaApp
    private fun all(v:View):List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun player(a:PlaybackActivity)=all(a.window.decorView).filterIsInstance<PlayerView>().first().player!!
    private fun await(s:ActivityScenario<PlaybackActivity>,check:(PlaybackActivity)->Boolean) {
        val end=SystemClock.elapsedRealtime()+30_000
        while(SystemClock.elapsedRealtime()<end) {
            var ready=false;s.onActivity { ready=check(it) };if(ready) return;Thread.sleep(150)
        };fail("Playback condition did not become true")
    }
    private fun launch(id:String="ep1")=ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id",id).putExtra("source_id","mp4"))
    @Before fun setup() {
        tv.ember.client.i18n.AppLanguage.save(context,"zh")
        app.sessions.save(Session("http://10.0.2.2:8765","fixture-token","u1","Demo TV"))
        app.settings.streamConnections=4;app.settings.introSeconds=0;app.settings.outroSeconds=0;app.settings.autoNextEpisode=true
        HttpClient.api.newCall(Request.Builder().url("http://10.0.2.2:8765/fixture/control?fail=0").build()).execute().close()
    }
    @Test fun manualNextAndPreviousReuseActivityAndCrossSeasonBoundary() {
        launch("ep2").use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY && all(it.window.decorView).filterIsInstance<Button>().any { b -> b.text=="下一集" && b.visibility==View.VISIBLE } }
            s.onActivity { a -> player(a).pause();all(a.window.decorView).filterIsInstance<Button>().first { it.text=="下一集" }.performClick() }
            await(s) { it.intent.getStringExtra("item_id")=="ep3" && player(it).playbackState==Player.STATE_READY }
            s.onActivity { a ->
                assertEquals("ep3",player(a).currentMediaItem?.mediaId)
                assertTrue(player(a).currentPosition<5000)
            }
            await(s) { all(it.window.decorView).filterIsInstance<Button>().any { b -> b.text=="上一集" && b.visibility==View.VISIBLE } }
            s.onActivity { a ->
                assertEquals(View.GONE,all(a.window.decorView).filterIsInstance<Button>().first { it.text=="下一集" }.visibility)
                all(a.window.decorView).filterIsInstance<Button>().first { it.text=="上一集" }.performClick()
            }
            await(s) { it.intent.getStringExtra("item_id")=="ep2" && player(it).playbackState==Player.STATE_READY }
        }
    }
    @Test fun introRunsOnceAndDoesNotOverrideManualRewind() {
        app.settings.introSeconds=5
        launch().use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY && player(it).currentPosition>=5000 }
            s.onActivity { a -> player(a).pause();player(a).seekTo(0) }
            await(s) { player(it).playbackState==Player.STATE_READY && player(it).currentPosition<1000 }
            Thread.sleep(1200)
            s.onActivity { assertTrue(player(it).currentPosition<1000) }
        }
    }
    @Test fun configuredOutroEndsEpisodeWithoutAutoplayWhenDisabled() {
        app.settings.outroSeconds=10;app.settings.autoNextEpisode=false
        launch().use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY }
            s.onActivity { player(it).seekTo(player(it).duration-5000) }
            await(s) { player(it).playbackState==Player.STATE_ENDED }
            Thread.sleep(1000);s.onActivity { assertEquals("ep1",it.intent.getStringExtra("item_id")) }
        }
    }
    @Test fun outroCountdownCanBeCancelledAndNextEpisodeCanStillBeSelected() {
        app.settings.outroSeconds=15
        launch().use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY }
            s.onActivity { player(it).seekTo(player(it).duration-12000) }
            await(s) { all(it.window.decorView).filterIsInstance<TextView>().any { v -> v.text.toString().contains("秒后播放下一集") } }
            s.onActivity { a -> a.onBackPressedDispatcher.onBackPressed();player(a).pause() }
            Thread.sleep(5500)
            s.onActivity { a ->
                assertEquals("ep1",a.intent.getStringExtra("item_id"))
                assertFalse(all(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains("秒后播放下一集") })
                all(a.window.decorView).filterIsInstance<Button>().first { it.text=="下一集" }.performClick()
            }
            await(s) { it.intent.getStringExtra("item_id")=="ep2" && player(it).playbackState==Player.STATE_READY }
        }
    }
    @Test fun naturalEndAutoplaysNextButFinalEpisodeDoesNotLoop() {
        launch("ep2").use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY }
            s.onActivity { player(it).seekTo(player(it).duration) }
            await(s) { it.intent.getStringExtra("item_id")=="ep3" && player(it).playbackState==Player.STATE_READY }
            s.onActivity { player(it).seekTo(player(it).duration) }
            await(s) { player(it).playbackState==Player.STATE_ENDED }
            Thread.sleep(1200);s.onActivity { assertEquals("ep3",it.intent.getStringExtra("item_id")) }
        }
    }
    @Test fun longPressUsesConfiguredStepAndCommitsOnlyOnceOnRelease() {
        app.settings.seekSeconds=5;app.settings.longSeekSeconds=15
        launch().use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY }
            val seeks=AtomicInteger();var origin=0L
            s.onActivity { a ->
                val p=player(a);p.pause();origin=p.currentPosition
                p.addListener(object:Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition:Player.PositionInfo,newPosition:Player.PositionInfo,reason:Int) { if(reason==Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet() }
                })
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
            }
            Thread.sleep(500)
            s.onActivity { a ->
                a.dispatchKeyEvent(KeyEvent(0,SystemClock.uptimeMillis(),KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,1))
                assertEquals(0,seeks.get());a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
            }
            await(s) { player(it).playbackState==Player.STATE_READY && player(it).currentPosition>=origin+20000 }
            assertEquals(1,seeks.get())
        }
    }
    @Test fun diagnosticsUseActualRenderedDimensionsAndSystemAudioConfiguration() {
        launch().use { s ->
            await(s) { player(it).playbackState==Player.STATE_READY && player(it).currentPosition>1000 }
            s.onActivity { a ->
                val f=PlaybackActivity::class.java.getDeclaredField("stats").apply { isAccessible=true }
                val stats=f.get(a) as PlayerStatsMonitor
                val output=stats.outputSummary(player(a) as androidx.media3.exoplayer.ExoPlayer)
                assertTrue(output,output.contains("实际解码画面 640×360"));assertTrue(output,output.contains("已输出首帧"))
                assertTrue(output,output.contains("系统音频输出 PCM"));assertFalse(output,output.contains("1920×1080"))
            }
        }
    }
}
