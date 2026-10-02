package tv.ember.client

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import tv.ember.client.player.PlaybackActivity

/** Opt in only when the isolated official Emby Server is running on the build host. */
@RunWith(AndroidJUnit4::class)
class RealEmbyServerTest {
    private fun all(view: View): List<View> = listOf(view) + if(view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun officialServerLoginBrowseAndAllThreeDirectPlayVersions() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("realEmby") == "true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val app=context.applicationContext as BronyaApp
        val session=app.api.login("http://10.0.2.2:8097/emby", "embertest", "Ember-Test-Only-2026")
        app.sessions.save(session)
        app.settings.receiveBufferKb=0
        app.settings.streamConnections=InstrumentationRegistry.getArguments().getString("connections")?.toIntOrNull() ?: 1
        app.api.validate(session)
        val folders=app.api.views(session); assertTrue(folders.isNotEmpty())
        val items=app.api.items(session,folders.first().id); assertEquals(1,items.total)
        var content=items.items.first()
        repeat(6) { if(content.isFolder) content=app.api.items(session,content.id).items.first() }
        assertFalse(content.isFolder)
        val item=app.api.detail(session,content.id)
        val info=app.api.playbackInfo(session,item.id); assertEquals(3,info.versions.size)
        for(version in info.versions) {
            val spec=app.api.playbackSpec(session,item.id,version,info.playSessionId)
            assertTrue(spec.url.contains("Static=true")); assertFalse(spec.url.contains("transcod",true))
            ActivityScenario.launch<PlaybackActivity>(Intent(context,PlaybackActivity::class.java).putExtra("item_id",item.id).putExtra("source_id",version.id)).use { scenario ->
                val end=System.currentTimeMillis()+30000
                var played=false
                while(System.currentTimeMillis()<end && !played) {
                    scenario.onActivity { a -> val p=all(a.window.decorView).filterIsInstance<PlayerView>().first().player; played=p?.playbackState==Player.STATE_READY && p.currentPosition>500 }
                    if(!played) Thread.sleep(200)
                }
                assertTrue("Real server version ${version.name} must play",played)
            }
        }
    }
}
