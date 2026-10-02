package tv.ember.client

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import tv.ember.client.player.BufferPolicy
import tv.ember.client.player.TvLoadControl

@RunWith(AndroidJUnit4::class)
class LoadControlDeviceTest {
    @Test fun actualAllocatorStopsAtCeilingAndStartsDrainingBeforeSixtySeconds() {
        val control=TvLoadControl(BufferPolicy(60000,120000,60000,15000,1048576))
        val id=PlayerId.UNSET
        control.onPrepared(id)
        assertEquals(0L,control.getBackBufferDurationUs(id)); assertFalse(control.retainBackBufferFromKeyframe(id))
        val allocator=control.getAllocator(id)
        val blocks=(0 until 16).map { allocator.allocate() }
        val params=LoadControl.Parameters(id,Timeline.EMPTY,MediaPeriodId("test"),0,1000000,1f,true,false,C.TIME_UNSET,C.TIME_UNSET)
        assertEquals(1048576,control.allocatedBytes)
        assertFalse(control.shouldContinueLoading(params)); assertTrue(control.shouldStartPlayback(params))
        blocks.forEach { allocator.release(it) }
        control.onStopped(id); control.onReleased(id)
    }
}
