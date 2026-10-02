package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.cache.DiskCachePlan
import tv.ember.client.cache.PrefetchWindow
import tv.ember.client.network.StreamPolicy
import tv.ember.client.player.BufferPolicy
import tv.ember.client.settings.BufferPreferences

class DiskCachePolicyTest {
    private val mib = DiskCachePlan.MIB
    @Test fun highBitrateReadAheadCanExceedHeapWithoutAllocatingThatHeap() {
        val disk = DiskCachePlan.create(-1, 4_000*mib, 0, 50_000_000, 60)
        assertEquals(512*mib,disk.capacityBytes)
        assertEquals(375_000_000L,disk.aheadBytes)
        val ram = BufferPolicy.create(BufferPreferences(),256*mib,32*mib,bitrate=50_000_000,diskBuffering=true)
        assertTrue(disk.aheadBytes>256*mib)
        assertTrue(ram.targetBytes<=64*mib)
        assertEquals(0,ram.backBufferMs)
        assertEquals(5000,ram.startMs)
        assertEquals(5000,ram.rebufferMs)
    }
    @Test fun diskCapacityLeavesSystemSpaceAndCreditsExistingCacheFiles() {
        val disk=DiskCachePlan.create(2048,300*mib,200*mib,50_000_000,60)
        assertEquals(244*mib,disk.capacityBytes)
        assertTrue(disk.aheadBytes<=disk.capacityBytes*3/4)
        assertFalse(DiskCachePlan.create(512,300*mib,0,50_000_000,60).enabled)
        assertFalse(DiskCachePlan.create(0,4000*mib,0,50_000_000,60).enabled)
    }
    @Test fun explicitHugeDiskBudgetsAndUnknownBitrateRemainBounded() {
        val big=DiskCachePlan.create(8192,20_000*mib,0,1_000_000_000,300)
        assertEquals(8192*mib,big.capacityBytes)
        assertEquals(big.capacityBytes*3/4,big.aheadBytes)
        val unknown=DiskCachePlan.create(-1,4000*mib,0,0,60)
        assertEquals(64*mib,unknown.aheadBytes)
    }
    @Test fun diskAndForegroundReceiveQueuesShareTheOriginalMemoryBudget() {
        val old=StreamPolicy.create(0,50_000_000,256*mib,32*mib,false)
        val disk=StreamPolicy.create(0,50_000_000,256*mib,32*mib,false,true)
        assertTrue(disk.budgetBytes.toLong()*2<=old.budgetBytes)
        assertEquals(8,disk.connections)
        assertEquals(1,StreamPolicy.create(1,50_000_000,256*mib,32*mib,false,true).connections)
        assertEquals(1,StreamPolicy.create(0,50_000_000,256*mib,32*mib,true,true).connections)
    }
    @Test fun windowsSkipTheCachedPrefixAndStopAtTheActualFileEnd() {
        assertEquals(PrefetchWindow(7*mib,8*mib),PrefetchWindow.next(5*mib,60*mib,100*mib,2*mib))
        assertEquals(PrefetchWindow(99*mib,mib),PrefetchWindow.next(99*mib,60*mib,100*mib,0))
        assertNull(PrefetchWindow.next(100*mib,60*mib,100*mib,0))
        assertNull(PrefetchWindow.next(5*mib,60*mib,-1,60*mib))
    }
    @Test fun unknownLengthAndExtremeOffsetsNeverOverflow() {
        assertEquals(PrefetchWindow(50*mib,8*mib),PrefetchWindow.next(50*mib,60*mib,-1,0))
        assertEquals(PrefetchWindow(Long.MAX_VALUE-100,100),PrefetchWindow.next(Long.MAX_VALUE-100,60*mib,-1,0))
    }
}
