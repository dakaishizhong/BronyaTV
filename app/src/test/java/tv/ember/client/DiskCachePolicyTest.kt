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
        assertEquals(1152*mib,disk.capacityBytes)
        assertEquals(1024*mib,disk.aheadBytes)
        val ram = BufferPolicy.create(BufferPreferences(),256*mib,32*mib,bitrate=50_000_000,diskBuffering=true)
        assertTrue(disk.aheadBytes>256*mib)
        assertTrue(ram.targetBytes in (64*mib).toInt()..(128*mib).toInt())
        assertEquals(0,ram.backBufferMs)
        assertEquals(5000,ram.startMs)
        assertEquals(5000,ram.rebufferMs)
    }
    @Test fun diskCapacityLeavesSystemSpaceAndCreditsExistingCacheFiles() {
        val disk=DiskCachePlan.create(2048,300*mib,200*mib,50_000_000,60)
        assertEquals(244*mib,disk.capacityBytes)
        assertTrue(disk.aheadBytes<disk.capacityBytes)
        assertFalse(DiskCachePlan.create(512,300*mib,0,50_000_000,60).enabled)
        assertFalse(DiskCachePlan.create(0,4000*mib,0,50_000_000,60).enabled)
    }
    @Test fun explicitHugeDiskBudgetsAndUnknownBitrateRemainBounded() {
        val big=DiskCachePlan.create(8192,20_000*mib,0,1_000_000_000,300)
        assertEquals(8320*mib,big.capacityBytes)
        assertEquals(8192*mib,big.aheadBytes)
        val unknown=DiskCachePlan.create(-1,4000*mib,0,0,60)
        assertEquals(1024*mib,unknown.aheadBytes)
    }
    @Test fun diskAndForegroundShareOneWindowIncludingAllStorageCopies() {
        val disk=StreamPolicy.create(0,50_000_000,256*mib,32*mib,false,true)
        assertTrue(disk.budgetBytes<=32*mib)
        assertTrue(disk.budgetBytes<=32*mib)
        assertEquals(disk.budgetBytes,disk.aheadWindowBytes)
        assertTrue(disk.aheadWindowBytes>=disk.connections*disk.chunkBytes)
        assertEquals(8,disk.connections)
        assertEquals(1,StreamPolicy.create(1,50_000_000,256*mib,32*mib,false,true).connections)
        assertEquals(1,StreamPolicy.create(0,50_000_000,256*mib,32*mib,true,true).connections)
    }
    @Test fun sharedPipelineKeepsTwoWorkerWavesAndProtectsSmallHeaps() {
        for(heap in listOf(64,128,256,512)) for(used in listOf(0,32,48)) for(lanes in listOf(1,2,4,8)) for(disk in listOf(false,true)) {
            val plan=StreamPolicy.create(lanes,80_000_000,heap*mib,used*mib,false,disk)
            assertTrue(plan.budgetBytes<=32*mib)
            if(plan.connections>1) {
                assertTrue(plan.aheadWindowBytes>=(if(disk) plan.connections else 2*plan.connections)*plan.chunkBytes)
                assertEquals(plan.budgetBytes,plan.aheadWindowBytes)
            }
        }
        val low=StreamPolicy.create(8,80_000_000,256*mib,32*mib,true,true)
        assertEquals(1,low.connections);assertTrue(low.budgetBytes<=4*mib)
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
    @Test fun selectableDiskSizesAreForwardTargetsAndTheLegacyAutoDefaultMigratesToOneGiB() {
        assertEquals(listOf(0,512,1024,2048,4096,8192),DiskCachePlan.sizesMb)
        for(size in DiskCachePlan.sizesMb.filter { it>0 }) {
            for(bitrate in listOf(0L,20_000_000L,50_000_000L,80_000_000L,100_000_000L)) {
                val plan=DiskCachePlan.create(size,20_000*mib,0,bitrate,15)
                assertEquals(size*mib,plan.aheadBytes)
                assertTrue(plan.capacityBytes>plan.aheadBytes)
            }
        }
        assertEquals(1024*mib,DiskCachePlan.create(-1,20_000*mib,0,50_000_000,60).aheadBytes)
    }

}
