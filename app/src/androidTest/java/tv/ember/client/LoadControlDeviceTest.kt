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
    @Test fun seekRebuffersAtShortThresholdAndBackwardBufferUsesConfiguredDuration() {
        val control=TvLoadControl(BufferPolicy(60000,120000,60000,5000,16*1048576,5000))
        val id=PlayerId.UNSET
        control.onPrepared(id)
        assertEquals(5_000_000L,control.getBackBufferDurationUs(id))
        assertTrue(control.retainBackBufferFromKeyframe(id))
        val timeline=androidx.media3.exoplayer.source.SinglePeriodTimeline(90_000_000,true,false,false,null,
            androidx.media3.common.MediaItem.fromUri("https://example.test/video.mp4"))
        val params=LoadControl.Parameters(id,timeline,MediaPeriodId(timeline.getUidOfPeriod(0)),0,1_200_000,1f,true,false,C.TIME_UNSET,C.TIME_UNSET)
        assertFalse(control.shouldStartPlayback(params))
        control.markSeek()
        assertTrue(control.shouldStartPlayback(params))
        assertFalse(control.shouldStartPlayback(params))
        control.onStopped(id);control.onReleased(id)
    }
    @Test fun actualAllocatorStopsAtCeilingAndStartsDrainingBeforeSixtySeconds() {
        val control=TvLoadControl(BufferPolicy(60000,120000,60000,15000,1048576))
        val id=PlayerId.UNSET
        control.onPrepared(id)
        assertEquals(0L,control.getBackBufferDurationUs(id)); assertFalse(control.retainBackBufferFromKeyframe(id))
        val allocator=control.getAllocator(id)
        val blocks=(0 until 16).map { allocator.allocate() }
        val timeline=androidx.media3.exoplayer.source.SinglePeriodTimeline(90_000_000,true,false,false,null,
            androidx.media3.common.MediaItem.fromUri("https://example.test/video.mp4"))
        val params=LoadControl.Parameters(id,timeline,MediaPeriodId(timeline.getUidOfPeriod(0)),0,1000000,1f,true,false,C.TIME_UNSET,C.TIME_UNSET)
        assertEquals(1048576,control.allocatedBytes)
        assertFalse(control.shouldContinueLoading(params)); assertTrue(control.shouldStartPlayback(params))
        blocks.forEach { allocator.release(it) }
        control.onStopped(id); control.onReleased(id)
    }
    @Test fun highBitrateAllocatorUsesItsBudgetWithHeapHeadroom() {
        val runtime=Runtime.getRuntime()
        val used=runtime.totalMemory()-runtime.freeMemory()
        val policy=BufferPolicy.create(tv.ember.client.settings.BufferPreferences(),runtime.maxMemory(),used,bitrate=80_000_000,diskBuffering=true)
        val control=TvLoadControl(policy);control.onPrepared(PlayerId.UNSET)
        val before=android.os.Debug.getRuntimeStat("art.gc.gc-count")
        val allocator=control.getAllocator(PlayerId.UNSET)
        var blocks=(0 until (policy.targetBytes+C.DEFAULT_BUFFER_SEGMENT_SIZE-1)/C.DEFAULT_BUFFER_SEGMENT_SIZE).map { allocator.allocate() }
        try {
            val afterUsed=runtime.totalMemory()-runtime.freeMemory()
            assertTrue(control.allocatedBytes>=policy.targetBytes)
            assertTrue("Leave room for UI and decoder growth",runtime.maxMemory()-afterUsed>32L*1048576)
            blocks.forEach(allocator::release)
            val steadyBefore=android.os.Debug.getRuntimeStat("art.gc.gc-count")
            blocks=List(blocks.size) { allocator.allocate() }
            val steadyAfter=android.os.Debug.getRuntimeStat("art.gc.gc-count")
            println("Allocator reuse GC count $steadyBefore -> $steadyAfter")
            println("High-bitrate allocator: target=${policy.targetBytes/1048576} MiB, heap used=${afterUsed/1048576}/${runtime.maxMemory()/1048576} MiB, GC count $before -> ${android.os.Debug.getRuntimeStat("art.gc.gc-count")}")
        } finally { blocks.forEach(allocator::release);control.onStopped(PlayerId.UNSET);control.onReleased(PlayerId.UNSET) }
    }
}
