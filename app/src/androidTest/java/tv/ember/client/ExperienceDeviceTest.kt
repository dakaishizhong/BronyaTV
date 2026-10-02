package tv.ember.client

import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
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
import tv.ember.client.network.HttpClient
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.ui.LoginActivity
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class ExperienceDeviceTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as BronyaApp
    private fun children(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { children(view.getChildAt(it)) } else emptyList()
    private fun await(check: ()->Boolean) {
        val end=SystemClock.elapsedRealtime()+30_000
        while(SystemClock.elapsedRealtime()<end) { if(check()) return;Thread.sleep(150) }
        fail("Condition did not become true")
    }
    @Before fun setup() {
        app.sessions.save(Session("http://10.0.2.2:8765","fixture-token","u1","Demo TV"))
        app.settings.streamConnections=4;app.settings.seekSeconds=10
        app.settings.subtitleLanguage="zh";app.settings.audioLanguage=""
        HttpClient.api.newCall(Request.Builder().url("http://10.0.2.2:8765/fixture/control?fail=0").build()).execute().close()
    }
    @Test fun tokenModeUsesVisibleTokenFieldsAndLogoutKeepsOnlyLoginHints() {
        app.sessions.clear()
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            scenario.onActivity { a ->
                val views=children(a.window.decorView)
                val fields=views.filterIsInstance<EditText>()
                assertEquals("http://10.0.2.2:8765",fields[0].text.toString())
                assertEquals("Demo TV",fields[1].text.toString())
                assertTrue(fields[2].text.isEmpty());assertTrue(fields[3].text.isEmpty())
                views.filterIsInstance<Button>().first { it.text=="Token 登录" }.performClick()
                assertFalse(fields[1].isShown);assertTrue(fields[3].isShown)
                fields[3].setText("fixture-token")
                views.filterIsInstance<Button>().first { it.text=="连接服务器" }.performClick()
            }
            await { app.sessions.load()?.token=="fixture-token" }
        }
    }
    @Test fun repeatedRemoteKeysSeekOnceAndBackCancelsPreview() {
        val intent=Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","mp4")
        ActivityScenario.launch<PlaybackActivity>(intent).use { scenario ->
            await { var ready=false;scenario.onActivity { a -> ready=children(a.window.decorView).filterIsInstance<PlayerView>().first().player?.playbackState==Player.STATE_READY };ready }
            val seeks=AtomicInteger()
            var origin=0L
            scenario.onActivity { a ->
                val p=children(a.window.decorView).filterIsInstance<PlayerView>().first().player!!
                p.pause();origin=p.currentPosition
                p.addListener(object: Player.Listener {
                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                        if(reason==Player.DISCONTINUITY_REASON_SEEK) seeks.incrementAndGet()
                    }
                })
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
                assertEquals(0,seeks.get())
                assertTrue(children(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains("松开跳转") })
                a.onBackPressedDispatcher.onBackPressed()
                assertFalse(children(a.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.toString().contains("松开跳转") })
                repeat(3) { a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)) }
                assertEquals(0,seeks.get())
                a.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
            }
            await { var ready=false;scenario.onActivity { a ->
                val p=children(a.window.decorView).filterIsInstance<PlayerView>().first().player!!
                ready=p.playbackState==Player.STATE_READY && p.currentPosition>=origin+30_000
            };ready }
            assertEquals(1,seeks.get())
        }
    }
}
