package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.network.StreamPolicy
import tv.ember.client.player.SeekPolicy
import tv.ember.client.player.BufferPolicy
import tv.ember.client.settings.BufferPreferences

class ExperiencePolicyTest {
    private val heap=256L*1024*1024
    @Test fun autoConnectionsFollowBitrateAndPreserveManualChoice() {
        assertEquals(1,StreamPolicy.create(0,2_000_000,heap,0,false).connections)
        assertEquals(2,StreamPolicy.create(0,8_000_000,heap,0,false).connections)
        assertEquals(4,StreamPolicy.create(0,80_000_000,heap,0,false).connections)
        assertEquals(8,StreamPolicy.create(8,2_000_000,heap,0,false).connections)
        assertEquals(1,StreamPolicy.create(8,80_000_000,heap,0,true).connections)
        val tight=StreamPolicy.create(8,80_000_000,heap,heap-1024,false)
        assertEquals(1,tight.connections);assertEquals(128,tight.budgetBytes)
    }
    @Test fun configuredShortAndLongSeekClampToPlayableTimeline() {
        assertEquals(20_000,SeekPolicy.target(10_000,100_000,1,0,10))
        assertEquals(40_000,SeekPolicy.target(10_000,100_000,1,5,10))
        assertEquals(55_000,SeekPolicy.target(10_000,100_000,1,12,10,45))
        assertEquals(99_999,SeekPolicy.target(90_000,100_000,1,12,60))
        assertEquals(0,SeekPolicy.target(5_000,100_000,-1,0,10))
        assertEquals(Long.MAX_VALUE,SeekPolicy.target(Long.MAX_VALUE-100,-1,1,12,60))
    }
    @Test fun explicitTimeAcceptsHoursAndMinutesAndRejectsMalformedValues() {
        assertEquals(5_025_000L,SeekPolicy.parseTime("01:23:45"))
        assertEquals(1_425_000L,SeekPolicy.parseTime("23:45"))
        assertEquals("1:23:45",SeekPolicy.time(5_025_000))
        for(input in listOf("1:60","1:99:00","-1:30","12","1:2:3:4","9999999999999999:00"))
            assertNull(input,SeekPolicy.parseTime(input))
    }
    @Test fun automaticCacheStartsQuicklyAndBackwardRetentionSharesMemoryBudget() {
        val normal=BufferPolicy.create(BufferPreferences(),heap,bitrate=8_000_000)
        assertEquals(2000,normal.startMs);assertEquals(5000,normal.backBufferMs)
        val high=BufferPolicy.create(BufferPreferences(backBufferSeconds=30),heap,bitrate=100_000_000)
        assertTrue(high.backBufferMs<=high.targetBytes.toLong()/4*8000/100_000_000)
        assertEquals(0,BufferPolicy.create(BufferPreferences(),heap,lowMemory=true,bitrate=8_000_000).backBufferMs)
        assertEquals(0,BufferPolicy.create(BufferPreferences(),heap).backBufferMs)
    }
}
