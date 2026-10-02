package tv.ember.client

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.ui.MainActivity
import tv.ember.client.ui.DetailActivity
import tv.ember.client.ui.SettingsActivity
import tv.ember.client.player.PlaybackActivity
import androidx.media3.ui.PlayerView
import androidx.media3.common.Player

@RunWith(AndroidJUnit4::class)
class VisualNavigationDeviceTest {
    private val fixtureServer get()=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val app get()=context.applicationContext as BronyaApp
    private fun children(v: View): List<View> = listOf(v)+if(v is ViewGroup) (0 until v.childCount).flatMap { children(v.getChildAt(it)) } else emptyList()
    private fun await(check: ()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+120_000
        while(SystemClock.elapsedRealtime()<deadline) { if(check()) return;Thread.sleep(150) }
        fail("UI did not become ready")
    }
    private fun capture(name: String) {
        if(InstrumentationRegistry.getArguments().getString("screenshots")!="true") return
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Thread.sleep(300) // Allow RecyclerView removal and focus animations to finish.
        val bitmap=InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        val file=java.io.File(context.getExternalFilesDir(null),"$name.png")
        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
    }
    @Before fun setup() { tv.ember.client.i18n.AppLanguage.save(context,"en");app.sessions.save(Session(fixtureServer,"fixture-token","u1","Demo TV"));app.settings.osd=false }
    @Test fun languagePickerPersistsChineseAndReturnsToEnglish() {
        val language=tv.ember.client.i18n.AppLanguage
        fun choose(scenario: ActivityScenario<SettingsActivity>,index: Int) {
            await {
                var selected=false
                scenario.onActivity { a ->
                    val picker=a.supportFragmentManager.findFragmentByTag("language_picker") as? androidx.fragment.app.DialogFragment
                    val list=(picker?.dialog as? android.app.AlertDialog)?.listView
                    if(list!=null && list.childCount>index) {
                        selected=list.performItemClick(list.getChildAt(index),index,list.adapter.getItemId(index))
                    }
                }
                selected
            }
        }
        try {
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { a -> children(a.window.decorView).filterIsInstance<Button>().first { it.text=="Interface & diagnostics" }.performClick() }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("interface_language").performClick() }
                choose(scenario,1)
                await { var ready=false;scenario.onActivity { a -> ready=children(a.window.decorView).filterIsInstance<TextView>().any { it.text=="界面语言" } };ready }
                assertEquals("zh",language.read(context))
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { a -> assertTrue(children(a.window.decorView).filterIsInstance<Button>().any { it.text=="首页" }) }
            }
            ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
                scenario.onActivity { a -> children(a.window.decorView).filterIsInstance<Button>().first { it.text=="界面与诊断" }.performClick() }
                scenario.onActivity { it.window.decorView.findViewWithTag<View>("interface_language").performClick() }
                choose(scenario,0)
                await { var ready=false;scenario.onActivity { a -> ready=children(a.window.decorView).filterIsInstance<TextView>().any { it.text=="Interface language" } };ready }
                assertEquals("en",language.read(context))
            }
        } finally { language.save(context,"en") }
    }
    @Test fun searchUsesInlineInputAndReturnsMatchingMediaAndRecentQuery() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { var ready=false;scenario.onActivity { ready=children(it.window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Ocean of light") } };ready }
            capture("home")
            scenario.onActivity { a ->
                a.window.decorView.findViewWithTag<View>("nav_Search").performClick()
                val views=children(a.window.decorView)
                val field=views.filterIsInstance<EditText>().single();field.setText("Ocean")
                views.filterIsInstance<Button>().last { it.text=="Search" }.performClick()
            }
            await { var ready=false;scenario.onActivity { a ->
                val labels=children(a.window.decorView).filterIsInstance<TextView>().filter { it.isShown }
                ready=labels.any { it.text=="Search results" } && labels.any { it.text=="Ocean of light" } && labels.none { it.text.toString().startsWith("After the horizon") }
            };ready }
            scenario.onActivity { a ->
                val views=children(a.window.decorView).filter { it.isShown }
                assertTrue(views.filterIsInstance<TextView>().any { it.text=="Ocean of light" })
                assertFalse(views.filterIsInstance<TextView>().any { it.text.toString().startsWith("After the horizon") })
                assertTrue(views.filterIsInstance<Button>().any { it.text=="Ocean" })
                assertNotNull(a.currentFocus)
            }
            capture("search")
        }
    }
    @Test fun sidebarCanOpenFavoritesAndReturnHomeWithRemoteFocus() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { var ready=false;scenario.onActivity { ready=children(it.window.decorView).filterIsInstance<TextView>().any { it.text=="Ocean of light" } };ready }
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("nav_Home").requestFocus() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_DOWN)
            scenario.onActivity { assertEquals("nav_Movies",it.currentFocus?.tag) }
            scenario.onActivity { it.window.decorView.findViewWithTag<View>("nav_Favorites").performClick() }
            await { var ready=false;scenario.onActivity { ready=children(it.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("3 items") } };ready }
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            await { var ready=false;scenario.onActivity { ready=children(it.window.decorView).filterIsInstance<TextView>().any { it.text=="Ocean of light" } };ready }
            scenario.onActivity { assertNotNull(it.currentFocus) }
        }
    }
    @Test fun detailsAndSettingsKeepPrimaryActionsReachable() {
        ActivityScenario.launch<DetailActivity>(Intent(context,DetailActivity::class.java).putExtra("item_id","demo")).use { scenario ->
            await { var ready=false;scenario.onActivity { ready=children(it.window.decorView).filterIsInstance<Button>().any { it.text.toString().contains("Resume") } };ready }
            scenario.onActivity { a -> assertTrue(a.currentFocus is Button);assertTrue((a.currentFocus as Button).text.toString().contains("Resume")) }
            capture("detail")
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            capture("settings")
            scenario.onActivity { a ->
                children(a.window.decorView).filterIsInstance<Button>().first { it.text=="Network & cache" }.performClick()
                a.window.decorView.findViewWithTag<View>("disk_capacity").performClick()
            }
            scenario.onActivity { a -> assertTrue(a.currentFocus!=null) }
        }
    }
    @Test fun playerShortcutKeepsPlaybackRunningAndOpensSubtitles() {
        val source=InstrumentationRegistry.getArguments().getString("playbackSource") ?: "mp4"
        ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id","demo").putExtra("source_id",source)).use { scenario ->
            await { var ready=false;scenario.onActivity { a -> ready=children(a.window.decorView).filterIsInstance<PlayerView>().first().player?.playbackState==Player.STATE_READY };ready }
            scenario.onActivity { a -> children(a.window.decorView).filterIsInstance<PlayerView>().first().showController() }
            capture("player")
            var position=0L
            scenario.onActivity { a ->
                val view=children(a.window.decorView).filterIsInstance<PlayerView>().first()
                view.showController() // Screenshot capture can outlast the normal control auto-hide delay.
                val p=view.player!!;position=p.currentPosition
                val shortcut=a.findViewById<View>(R.id.bronya_subtitles);assertTrue(shortcut.isShown);shortcut.requestFocus();shortcut.performClick()
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            await { var advanced=false;scenario.onActivity { a -> advanced=children(a.window.decorView).filterIsInstance<PlayerView>().first().player!!.currentPosition>position+500 };advanced }
        }
    }

    @Test fun unreachableServerShowsRetryWithoutCrashingHome() {
        app.sessions.save(Session("http://127.0.0.1:9","fixture-token","u1","Demo TV"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await { var shown=false;scenario.onActivity { a -> shown=children(a.window.decorView).filterIsInstance<TextView>().any { it.text=="Unable to load" } };shown }
            scenario.onActivity { a -> assertTrue(children(a.window.decorView).filterIsInstance<TextView>().any { it.text=="Retry" }) }
        }
    }

}
