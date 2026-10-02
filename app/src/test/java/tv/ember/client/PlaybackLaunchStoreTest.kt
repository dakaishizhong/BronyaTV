package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.data.*

class PlaybackLaunchStoreTest {
    private val session=Session("https://example.test","token","u1","TV")
    private val item=VideoItem("v1","Movie","Movie")
    private val version=MediaVersion("source1","Original","mkv","","",true,80_000_000,emptyList(),emptyMap(),"",false)
    private val spec=PlaybackSpec("https://example.test/original.mkv?sig=private",emptyMap(),version,"play1")
    @Test fun launchHandoffIsSingleUseAndExpiresBeforeSignedLinkCanBecomeStale() {
        var now=1000L
        val store=PlaybackLaunchStore { now }
        store.put(session,item,spec)
        assertEquals(item to spec,store.take(session,item.id,version.id))
        assertNull(store.take(session,item.id,version.id))
        store.put(session,item,spec);now+=30_001
        assertNull(store.take(session,item.id,version.id))
    }
    @Test fun differentAccountOrSourceCannotUseAnotherLaunch() {
        val store=PlaybackLaunchStore()
        store.put(session,item,spec)
        assertNull(store.take(session.copy(token="new-token"),item.id,version.id))
        store.put(session,item,spec)
        assertNull(store.take(session,item.id,"source2"))
        store.put(session,item,spec);store.clear()
        assertNull(store.take(session,item.id,version.id))
    }
}
