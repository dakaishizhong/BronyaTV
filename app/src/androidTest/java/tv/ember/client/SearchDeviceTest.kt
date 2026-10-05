package tv.ember.client

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.data.Session
import tv.ember.client.ui.MainActivity
import java.net.URL

@RunWith(AndroidJUnit4::class)
class SearchDeviceTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val server get()=InstrumentationRegistry.getArguments().getString("fixtureServer") ?: "http://10.0.2.2:8765"
    private fun request(path: String)=URL(server+path).readText()
    private fun status()=JSONObject(request("/fixture/status"))
    private fun ready(tag: String) { compose.waitUntil(60000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() } }
    private fun click(tag: String) { ready(tag);compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick) { it() } }
    private fun key(code:Int) { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code);compose.waitForIdle() }
    @Before fun setup() {
        tv.ember.client.i18n.AppLanguage.save(context,"en")
        context.getSharedPreferences("search_history",0).edit().clear().commit()
        (context.applicationContext as BronyaApp).sessions.save(Session(server,"fixture-token","u1","CinemaMaster"))
        request("/fixture/control?fail=0&reset_search=1&slow_search=oppenheimer")
    }
    @After fun reset() { request("/fixture/control?fail=0") }
    @Test fun blankSearchDoesNotFetchFacetsAndLiveQueriesNormalizeKeyboardWidth() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_film3");val facets=status().getInt("facet_requests");key(KeyEvent.KEYCODE_MENU);click("dialog_option_2");ready("search_query")
            assertEquals(0,status().getJSONArray("searches").length());assertEquals(facets,status().getInt("facet_requests"))
            compose.onNodeWithTag("search_query").performTextReplacement("　Ｄｕｎｅ　")
            ready("media_demo");compose.onNodeWithTag("media_film0").assertDoesNotExist()
            assertEquals("dune",status().getJSONArray("searches").getJSONObject(0).getString("term"))
            compose.onNodeWithTag("filter__Dune").assertDoesNotExist() // History is only saved on explicit submission.
            click("search_submit");compose.onNodeWithTag("filter__Dune").assertExists()
            assertEquals("Submission reuses the live result",1,status().getJSONArray("searches").length())
            compose.onNodeWithTag("search_query").performTextReplacement("");compose.waitForIdle()
            compose.onNodeWithTag("media_demo").assertDoesNotExist();assertEquals(1,status().getJSONArray("searches").length())
            compose.onNodeWithTag("search_query").performTextReplacement("沙丘");ready("media_demo")
            assertEquals("沙丘",status().getJSONArray("searches").getJSONObject(1).getString("term"))
        }
    }
    @Test fun slowObsoleteResultsCannotReplaceANewerQueryAndScopesStayOnServer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            ready("media_film3");key(KeyEvent.KEYCODE_MENU);click("dialog_option_2");ready("search_query")
            compose.onNodeWithTag("search_query").performTextReplacement("Oppenheimer")
            compose.waitUntil(15000) { status().getJSONArray("searches").length()>0 }
            val start=SystemClock.elapsedRealtime()
            compose.onNodeWithTag("search_query").performTextReplacement("Dune");ready("media_demo")
            println("Live replacement search, local fixture: ${SystemClock.elapsedRealtime()-start} ms (includes emulator rendering and 300 ms debounce)")
            Thread.sleep(2200);compose.onNodeWithTag("media_demo").assertExists();compose.onNodeWithTag("media_film0").assertDoesNotExist()
            click("filter__Movie");ready("media_demo")
            val searches=status().getJSONArray("searches");assertEquals("Movie",searches.getJSONObject(searches.length()-1).getString("types"))
        }
    }
    @Test fun recentScreenResumeReusesMetadataWithoutLosingFocusedCard() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready("media_film3")
            compose.onNodeWithTag("media_film0").performSemanticsAction(SemanticsActions.RequestFocus) { it() };compose.waitForIdle()
            Thread.sleep(1500);val before=status().getInt("view_requests");val times=mutableListOf<Long>()
            repeat(3) {
                val start=SystemClock.elapsedRealtime();scenario.moveToState(Lifecycle.State.CREATED);scenario.moveToState(Lifecycle.State.RESUMED)
                ready("media_film0");compose.onNodeWithTag("media_film0").assertIsFocused();times+=SystemClock.elapsedRealtime()-start
            }
            assertEquals("Recent resumes do not refetch libraries",before,status().getInt("view_requests"))
            println("Recent home resume, local emulator: $times ms; library requests=$before (unchanged)")
        }
    }
}
