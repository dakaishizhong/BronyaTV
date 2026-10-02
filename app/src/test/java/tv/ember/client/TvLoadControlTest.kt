package tv.ember.client

import androidx.media3.common.C
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.player.BufferPolicy
import tv.ember.client.player.TvLoadControl

class TvLoadControlTest {
    private fun parameters(bufferedUs: Long, speed: Float = 1f) = LoadControl.Parameters(
        PlayerId.UNSET, Timeline.EMPTY, MediaPeriodId("test"), 0, bufferedUs, speed,
        true, false, C.TIME_UNSET, C.TIME_UNSET
    )
    private fun fullControl(): TvLoadControl {
        val control = TvLoadControl(BufferPolicy(30_000, 90_000, 2000, 2000, 1_048_576, 5000))
        control.onPrepared(PlayerId.UNSET)
        repeat(16) { control.getAllocator(PlayerId.UNSET).allocate() }
        control.markSeek()
        return control
    }
    @Test fun retainedMemoryAloneCannotStartPlaybackAfterSeek() {
        val control = fullControl()
        assertFalse(control.shouldStartPlayback(parameters(0)))
        assertFalse(control.shouldStartPlayback(parameters(50_000)))
        assertTrue(control.shouldStartPlayback(parameters(500_000)))
    }
    @Test fun anEmptyForwardBufferMayUseOnlyABoundedRecoveryReserve() {
        val control = fullControl()
        assertTrue(control.shouldContinueLoading(parameters(0)))
        repeat(4) { control.getAllocator(PlayerId.UNSET).allocate() }
        assertFalse(control.shouldContinueLoading(parameters(0)))
    }
    @Test fun loadingStopsAtTheTargetOnceThereIsPlayableForwardMedia() {
        val control = fullControl()
        assertFalse(control.shouldContinueLoading(parameters(500_000)))
        assertTrue(control.shouldStartPlayback(parameters(500_000)))
    }
    @Test fun playbackSpeedScalesTheRequiredForwardBuffer() {
        val control = fullControl()
        assertFalse(control.shouldStartPlayback(parameters(500_000, 2f)))
        assertTrue(control.shouldContinueLoading(parameters(500_000, 2f)))
        assertTrue(control.shouldStartPlayback(parameters(1_000_000, 2f)))
    }
}
