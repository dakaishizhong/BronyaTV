package tv.ember.client

import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.cache.PlaybackCacheEvictor
import java.io.File
import java.lang.reflect.Proxy

class PlaybackCacheEvictorTest {
    private class Fixture(capacity:Long) {
        val policy=PlaybackCacheEvictor(capacity)
        val removed=mutableListOf<CacheSpan>()
        val cache:Cache=Proxy.newProxyInstance(Cache::class.java.classLoader,arrayOf(Cache::class.java)) { _,method,args ->
            when(method.name) {
                "removeSpan" -> { val span=args!![0] as CacheSpan;removed+=span;policy.onSpanRemoved(cache,span);null }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Cache
        fun span(position:Long,time:Long)=CacheSpan("video",position,100,time,File("span-$position"))
        fun add(position:Long,time:Long)=span(position,time).also { policy.onSpanAdded(cache,it) }
    }
    @Test fun capacityGrowthDoesNotDiscardSpansAndReductionEvictsOldest() {
        val f=Fixture(300)
        val old=f.add(0,1);f.add(100,2);f.add(200,3)
        f.policy.resize(f.cache,400);assertTrue(f.removed.isEmpty())
        f.policy.resize(f.cache,250);assertEquals(listOf(old),f.removed)
    }
    @Test fun playbackTouchesProtectRecentlyReadBytesFromEviction() {
        val f=Fixture(300)
        val first=f.add(0,1);val second=f.add(100,2);f.add(200,3)
        f.policy.onSpanTouched(f.cache,first,f.span(0,10))
        f.add(300,4)
        assertEquals(listOf(second),f.removed)
    }
    @Test fun writingAFragmentReservesCapacityAndClearEvictsEverySpan() {
        val f=Fixture(300)
        val first=f.add(0,1);f.add(100,2);f.add(200,3)
        f.policy.onStartFile(f.cache,"video",300,100)
        assertEquals(listOf(first),f.removed)
        f.policy.resize(f.cache,0)
        assertEquals(3,f.removed.size)
    }
    @Test fun playedSpansYieldBeforeTheCurrentForwardWindowEvenAfterRecentReads() {
        val f=Fixture(300)
        val ahead=f.add(100,1);f.add(200,2);val played=f.add(0,10)
        f.policy.setPlaybackWindow(f.cache,"video",100)
        f.add(300,3)
        assertEquals(listOf(played),f.removed)
        assertFalse(f.removed.contains(ahead))
    }

}
