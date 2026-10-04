package tv.ember.client

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
import tv.ember.client.ui.DetailActivity
import tv.ember.client.ui.SettingsActivity

/** Remote behavior and screen geometry, including the original Media3 player beneath Compose. */
@RunWith(AndroidJUnit4::class)
class PlaybackUiDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private fun descendants(view: View): List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()
    private fun ready(tag: String) { compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun focus(tag: String) { ready(tag);compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() };compose.waitForIdle() }
    private fun key(code: Int) { instrumentation.sendKeyDownUpSync(code);compose.waitForIdle() }
    private fun click(tag: String) { focus(tag);key(KeyEvent.KEYCODE_DPAD_CENTER) }
    @Before fun setup() {
        tv.ember.client.i18n.AppLanguage.save(context,"en")
        (context.applicationContext as BronyaApp).apply {
            sessions.save(Session(InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765","fixture-token","u1","Demo TV"))
            settings.diskCacheMb=0;settings.introSeconds=0;settings.outroSeconds=0
        }
    }
    @Test fun playPauseIsExactlyCenteredAndRemoteControlsOperateThePlayer() {
        ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","vp8")).use { scenario ->
            fun player(block: (Player)->Unit) { scenario.onActivity { block(descendants(it.window.decorView).filterIsInstance<PlayerView>().first().player!!) } }
            val deadline=SystemClock.elapsedRealtime()+60000
            while(SystemClock.elapsedRealtime()<deadline) { var ready=false;scenario.onActivity { ready=descendants(it.window.decorView).filterIsInstance<PlayerView>().firstOrNull()?.player?.playbackState==Player.STATE_READY };if(ready) break;Thread.sleep(100) }
            player { assertEquals(Player.STATE_READY,it.playbackState) }
            key(KeyEvent.KEYCODE_DPAD_UP);focus("play_pause")
            val viewport=compose.onNodeWithTag("playback_surface",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
            val center=compose.onNodeWithTag("play_pause").fetchSemanticsNode().boundsInRoot
            assertEquals("Play/Pause must be centered on screen",viewport.center.x,center.center.x,1f)
            println("Playback geometry: viewport=${viewport.width}x${viewport.height}, playPauseCenterX=${center.center.x}, diameter=${center.width}")
            compose.onNodeWithTag("player_audio").assertDoesNotExist()
            key(KeyEvent.KEYCODE_DPAD_CENTER);player { assertFalse("OK pauses the real player",it.playWhenReady) }
            key(KeyEvent.KEYCODE_DPAD_LEFT);compose.onNodeWithTag("player_rewind").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_LEFT);compose.onNodeWithTag("player_sources").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_LEFT);compose.onNodeWithTag("player_sources").assertIsFocused()
            repeat(2) { key(KeyEvent.KEYCODE_DPAD_RIGHT) };compose.onNodeWithTag("play_pause").assertIsFocused()
            assertEquals("Focus must not move or resize the main control",center,compose.onNodeWithTag("play_pause").fetchSemanticsNode().boundsInRoot)
            key(KeyEvent.KEYCODE_DPAD_CENTER);player { assertTrue("OK resumes the real player",it.playWhenReady) }
            key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("playback_timeline").assertIsFocused()
            var before=0L;player { before=it.currentPosition }
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.waitUntil(30000) { var sought=false;player { sought=it.currentPosition>before+3000 };sought }
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("play_pause").assertIsFocused()
            listOf("player_forward","player_subtitles","player_more").forEach { key(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag(it).assertIsFocused() }
            key(KeyEvent.KEYCODE_DPAD_CENTER);ready("dialog_option_0")
            compose.onNodeWithText("Audio tracks").assertExists()
            // Audio track selection remains available through More; there is no speaker shortcut.
            compose.onNodeWithText("Audio tracks").performSemanticsAction(SemanticsActions.OnClick) { it() }
            compose.onAllNodesWithText("Audio tracks").assertCountEquals(1)
            key(KeyEvent.KEYCODE_BACK);ready("dialog_option_0");key(KeyEvent.KEYCODE_BACK)
            scenario.onActivity { assertFalse(it.isFinishing) }
        }
    }
    @Test fun detailAndEverySettingsEditorUseRemoteBackWithoutHeaderBackButtons() {
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","demo")).use {
            ready("detail_play");compose.onNodeWithTag("detail_back").assertDoesNotExist()
            focus("detail_play");compose.onNodeWithTag("detail_play").assertIsFocused()
        }
        ActivityScenario.launch(SettingsActivity::class.java).use {
            // Six overview tiles cover every settings category, including account actions.
            repeat(6) { index ->
                ready("settings_tile_$index");click("settings_tile_$index")
                compose.onNodeWithTag("settings_back").assertDoesNotExist()
                key(KeyEvent.KEYCODE_BACK);compose.onNodeWithTag("settings_tile_$index").assertIsFocused()
            }
        }
    }
}
