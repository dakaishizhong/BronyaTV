package tv.ember.client

import android.content.Context
import android.app.ActivityManager
import android.view.KeyEvent
import android.view.View
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.ui.SettingsActivity
import tv.ember.client.data.Session

@RunWith(AndroidJUnit4::class)
class SettingsComposeDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    @Before fun setup() {
        val context=instrumentation.targetContext
        tv.ember.client.i18n.AppLanguage.save(context,"en")
        (context.applicationContext as BronyaApp).sessions.save(Session(
            InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765","fixture-token","u1","Demo TV"))
    }
    @Test fun dpadTraversesBothRowsOpensComposeEditorAndRestoresFocus() {
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            fun key(code: Int) { instrumentation.sendKeyDownUpSync(code);compose.waitForIdle() }
            fun focused(index: Int) { compose.onNodeWithTag("settings_tile_$index").assertIsFocused() }
            focused(0);key(KeyEvent.KEYCODE_DPAD_RIGHT);focused(1)
            key(KeyEvent.KEYCODE_DPAD_RIGHT);focused(2)
            key(KeyEvent.KEYCODE_DPAD_CENTER)
            compose.onNodeWithTag("disk_capacity").assertExists()
            key(KeyEvent.KEYCODE_BACK);focused(2)
            key(KeyEvent.KEYCODE_DPAD_DOWN);focused(5)
            key(KeyEvent.KEYCODE_DPAD_LEFT);focused(4)
            key(KeyEvent.KEYCODE_DPAD_LEFT);focused(3)
            key(KeyEvent.KEYCODE_DPAD_UP);focused(0)
            key(KeyEvent.KEYCODE_DPAD_LEFT)
            compose.onNodeWithTag("nav_Settings").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onAllNodes(hasTestTag("settings_tile_0") or hasTestTag("settings_tile_3"))
                .fetchSemanticsNodes().let { nodes -> assertTrue(nodes.any { it.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.Focused) { false } }) }
            val manager=instrumentation.targetContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            println("Heap evaluation: normal=${manager.memoryClass} MiB, large=${manager.largeMemoryClass} MiB, runtime=${Runtime.getRuntime().maxMemory()/1048576} MiB")
        }
    }
}
