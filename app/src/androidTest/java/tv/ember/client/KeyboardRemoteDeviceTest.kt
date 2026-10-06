package tv.ember.client

import android.content.Context
import android.provider.Settings
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.ui.LoginActivity
import java.io.File

/** Real IME window + remote events; text is committed by the TV keyboard, not test APIs. */
@RunWith(AndroidJUnit4::class)
class KeyboardRemoteDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext

    @Before fun setup() {
        val ime=Settings.Secure.getString(context.contentResolver,Settings.Secure.DEFAULT_INPUT_METHOD)
        assumeTrue("Enable LeanKeyboard to exercise real TV keyboard selection",ime?.contains("LeanbackImeService")==true)
        val server=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
        (context.applicationContext as BronyaApp).sessions.save(Session(server,"fixture-token","u1","CinemaMaster"))
        tv.ember.client.i18n.AppLanguage.save(context,"zh")
    }
    private fun text(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    private fun key(code:Int) { instrumentation.sendKeyDownUpSync(code);compose.waitForIdle() }
    private fun imeVisible(scenario:ActivityScenario<LoginActivity>):Boolean {
        var visible=false
        scenario.onActivity { visible=ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true }
        return visible
    }
    private fun waitIme(scenario:ActivityScenario<LoginActivity>,visible:Boolean) {
        try { compose.waitUntil(15000) { imeVisible(scenario)==visible } }
        catch(error:Throwable) { capture("ime-visibility-failure");throw error }
        instrumentation.uiAutomation.waitForIdle(500,5000)
        compose.waitForIdle()
    }
    private fun open(scenario:ActivityScenario<LoginActivity>,tag:String) {
        compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
        compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.waitForIdle()
        compose.onNodeWithTag(tag).performTextReplacement("")
        compose.waitForIdle();compose.onNodeWithTag(tag).assertIsFocused()
        scenario.onActivity { (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0) }
        waitIme(scenario,false)
        compose.onNodeWithTag(tag).assertIsFocused()
        key(KeyEvent.KEYCODE_DPAD_CENTER);waitIme(scenario,true)
        // The inset can arrive before the external IME finishes its first layout.
        instrumentation.uiAutomation.waitForIdle(500,5000)
        compose.onNodeWithTag(tag).assertIsFocused()
        capture("$tag-keyboard")
    }
    private fun confirm(tag:String):Char {
        val before=text(tag)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        try { compose.waitUntil(5000) { text(tag).length==before.length+1 } }
        catch(error:Throwable) {
            capture("$tag-commit-failure")
            throw AssertionError("IME commit length for $tag: before=${before.length}, after=${text(tag).length}; server/user/password lengths="+
                listOf("login_server","login_username","login_password").map { text(it).length },error)
        }
        compose.onNodeWithTag(tag).assertIsFocused()
        val after=text(tag)
        println("TV_IME $tag confirmed key; committed length=${after.length}, app focus retained")
        return after.last().lowercaseChar()
    }
    private fun select(scenario:ActivityScenario<LoginActivity>,tag:String,code:Int):Char {
        key(code);compose.onNodeWithTag(tag).assertIsFocused();assertTrue(imeVisible(scenario))
        println("TV_IME $tag ${KeyEvent.keyCodeToString(code)} selected keyboard key")
        return confirm(tag)
    }
    private fun capture(name:String) {
        val folder=File(context.getExternalFilesDir(null),"keyboard-1.7.3").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(folder,"$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        }
    }
    @Test fun accountDirectionsSelectKeyboardKeysAndBackRestoresPageNavigation() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            val tag="login_username";open(scenario,tag);capture("account-keyboard")
            val first=confirm(tag)
            val right=select(scenario,tag,KeyEvent.KEYCODE_DPAD_RIGHT)
            assertNotEquals("Right must select a different keyboard character",first,right)
            capture("account-keyboard-right")
            assertEquals(first,select(scenario,tag,KeyEvent.KEYCODE_DPAD_LEFT))
            val down=select(scenario,tag,KeyEvent.KEYCODE_DPAD_DOWN)
            assertNotEquals("Down must select a different keyboard character",first,down)
            assertEquals(first,select(scenario,tag,KeyEvent.KEYCODE_DPAD_UP))
            key(KeyEvent.KEYCODE_BACK);waitIme(scenario,false)
            compose.onNodeWithTag(tag).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("login_password").assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag(tag).assertIsFocused()
            key(KeyEvent.KEYCODE_DPAD_CENTER);waitIme(scenario,true)
            key(KeyEvent.KEYCODE_BACK);waitIme(scenario,false)
            key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("login_server").assertIsFocused()
            println("TV_IME Back closed keyboard twice; normal page Up/Down restored")
        }
    }
    @Test fun passwordAndServerUseKeyboardWithoutSwitchingFields() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            for(tag in listOf("login_password","login_server")) {
                open(scenario,tag);confirm(tag)
                for(code in listOf(KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_UP)) select(scenario,tag,code)
                assertEquals(5,text(tag).length)
                key(KeyEvent.KEYCODE_BACK);waitIme(scenario,false)
                compose.onNodeWithTag(tag).assertIsFocused()
            }
        }
    }
    @Test fun keysReturnedByImeCannotNavigateThePageBehindIt() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            val tag="login_username";open(scenario,tag)
            // Emulate an IME returning an unhandled key to the Activity. The normal
            // injected events in the other tests exercise the IME's selection path.
            for(code in listOf(KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER)) {
                scenario.onActivity {
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))
                }
                compose.waitForIdle();compose.onNodeWithTag(tag).assertIsFocused()
                assertTrue(imeVisible(scenario));assertEquals("",text(tag))
                println("TV_IME returned ${KeyEvent.keyCodeToString(code)}; app focus retained")
            }
            key(KeyEvent.KEYCODE_BACK);waitIme(scenario,false)
            key(KeyEvent.KEYCODE_DPAD_DOWN);compose.onNodeWithTag("login_password").assertIsFocused()
        }
    }
    @Test fun pendingKeyboardShowCannotMoveFocusBeforeItsInsetArrives() {
        ActivityScenario.launch(LoginActivity::class.java).use { scenario ->
            val tag="login_username"
            compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            compose.waitForIdle();compose.onNodeWithTag(tag).assertIsFocused()
            scenario.onActivity { (it.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.window.decorView.windowToken,0) }
            waitIme(scenario,false)
            // Both commands happen in one UI callback, before an IME inset or a
            // recomposition can arrive. A quick remote press must keep this editor.
            scenario.onActivity {
                for(code in listOf(KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_DPAD_UP)) {
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN,code))
                    it.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP,code))
                }
            }
            compose.waitForIdle();compose.onNodeWithTag(tag).assertIsFocused()
            waitIme(scenario,true)
            println("TV_IME pending keyboard show retained account focus before inset/recomposition")
            key(KeyEvent.KEYCODE_BACK);waitIme(scenario,false)
            key(KeyEvent.KEYCODE_DPAD_UP);compose.onNodeWithTag("login_server").assertIsFocused()
        }
    }
}
