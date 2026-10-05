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
    private fun assertDocumentGeometry() {
        focus("playback_timeline") // Measure the reference sizes without focus enlargement.
        fun bounds(tag: String)=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val surface=bounds("playback_surface");val unit=surface.height/540f
        val play=bounds("play_pause");val previous=bounds("previous_section");val next=bounds("next_section")
        assertEquals("Play must stay at the screen's horizontal center",surface.center.x,play.center.x,1f)
        assertEquals(56*unit,play.width,1f);assertEquals(play.width,play.height,1f)
        listOf(previous,next).forEach { assertEquals(44*unit,it.width,1f);assertEquals(it.width,it.height,1f);assertEquals(play.center.y,it.center.y,1f) }
        assertEquals(20*unit,play.left-previous.right,1f);assertEquals(20*unit,next.left-play.right,1f)
        assertEquals(28*unit,surface.bottom-play.bottom,1f)
        val timeline=bounds("playback_timeline")
        assertEquals(48*unit,timeline.left-surface.left,1f);assertEquals(48*unit,surface.right-timeline.right,1f)
        assertEquals(6*unit,timeline.height,1f);assertEquals(16*unit,play.top-timeline.bottom,1f)
        val parameters=listOf("player_subtitles","player_audio","player_aspect").map { bounds(it) }
        // Font line height and each padding edge round separately on lower-resolution output.
        parameters.forEach { assertEquals("Text control height",32*unit,it.height,2f);assertEquals(play.center.y,it.center.y,1f) }
        parameters.zipWithNext().forEach { (left,right) -> assertEquals(10*unit,right.left-left.right,1f) }
        assertEquals(48*unit,surface.right-parameters.last().right,1f)
        assertTrue("Track labels must never overlap transport controls",parameters.first().left-next.right>=20*unit-1f)
        val hud=bounds("player_hud");val exit=bounds("player_exit")
        listOf(hud,exit).forEach { assertEquals(36*unit,it.top-surface.top,1f);assertEquals(32*unit,it.height,2f) }
        assertEquals(36*unit,surface.right-exit.right,1f);assertEquals(12*unit,exit.left-hud.right,1f)
        println("Player geometry: screen=${surface.width}x${surface.height}, playCenter=${play.center}, circles=${previous.width}/${play.width}/${next.width}, textHeight=${parameters.first().height}, parameterGap=${parameters[1].left-parameters[0].right}")
        focus("play_pause")
        val focused=bounds("play_pause")
        assertEquals("Focus enlargement must preserve the screen center",surface.center.x,focused.center.x,1f)
        // TV Surface enlarges its drawing layer; its layout bounds remain unchanged.
        assertEquals("Focus must preserve the reference layout width",play.width,focused.width,1f)
    }
    @Before fun setup() {
        tv.ember.client.i18n.AppLanguage.save(context,InstrumentationRegistry.getArguments().getString("testLanguage") ?: "en")
        (context.applicationContext as BronyaApp).apply {
            sessions.save(Session(InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765","fixture-token","u1","CinemaMaster"))
            settings.diskCacheMb=0;settings.introSeconds=0;settings.outroSeconds=0
        }
    }
    @Test fun documentControlsOperateTheRealPlayerAndTrackPanels() {
        ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id","vp8")).use { scenario ->
            fun player(block: (Player)->Unit) { scenario.onActivity { block(descendants(it.window.decorView).filterIsInstance<PlayerView>().first().player!!) } }
            compose.waitUntil(90000) { var ready=false;scenario.onActivity { ready=descendants(it.window.decorView).filterIsInstance<PlayerView>().firstOrNull()?.player?.playbackState==Player.STATE_READY };ready }
            key(KeyEvent.KEYCODE_DPAD_UP);assertDocumentGeometry()
            val center=compose.onNodeWithTag("play_pause").fetchSemanticsNode().boundsInRoot
            val previous=compose.onNodeWithTag("previous_section").fetchSemanticsNode().boundsInRoot
            val next=compose.onNodeWithTag("next_section").fetchSemanticsNode().boundsInRoot
            assertTrue("Document has a larger primary control",center.width>previous.width)
            assertEquals(previous.width,next.width,1f)
            assertEquals(center.center.x-previous.center.x,next.center.x-center.center.x,1f)
            assertEquals(center.center.y,previous.center.y,1f);assertEquals(center.center.y,next.center.y,1f)
            compose.onNodeWithTag("previous_section").assertIsNotEnabled();compose.onNodeWithTag("next_section").assertIsNotEnabled()
            listOf("player_sources","player_rewind","player_forward","player_diagnostics","player_more").forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
            key(KeyEvent.KEYCODE_DPAD_CENTER);player { assertFalse(it.playWhenReady) }
            key(KeyEvent.KEYCODE_DPAD_CENTER);player { assertTrue(it.playWhenReady) }
            key(KeyEvent.KEYCODE_DPAD_CENTER);player { assertFalse(it.playWhenReady) }
            key(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("next_section").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("player_subtitles").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_RIGHT);compose.onNodeWithTag("player_audio").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER);ready("dialog_option_0")
            click("dialog_option_1");player { assertTrue(androidx.media3.common.C.TRACK_TYPE_AUDIO in it.trackSelectionParameters.disabledTrackTypes) }
            focus("player_aspect");key(KeyEvent.KEYCODE_DPAD_CENTER);ready("dialog_option_0");click("dialog_option_1")
            assertEquals(4,(context.applicationContext as BronyaApp).settings.resizeMode)
            focus("playback_timeline");var before=0L;player { before=it.currentPosition };key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.waitUntil(30000) { var sought=false;player { sought=it.currentPosition>before+3000 };sought }
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("play_pause").assertIsFocused()
            focus("player_hud");key(KeyEvent.KEYCODE_DPAD_CENTER);ready("player_hud_panel")
            key(KeyEvent.KEYCODE_DPAD_CENTER);compose.onNodeWithTag("player_hud_panel").assertDoesNotExist()
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
