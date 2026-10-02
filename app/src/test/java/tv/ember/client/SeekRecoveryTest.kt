package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.player.SeekRecovery

class SeekRecoveryTest {
    @Test fun aFrozenReadyClockRecoversOnceEvenWhileTheNetworkContinues() {
        val recovery = SeekRecovery()
        recovery.begin(120_000, 1000)
        assertFalse(recovery.shouldRecover(120_000, 1000, true, true))
        assertFalse(recovery.shouldRecover(120_000, 7999, true, true))
        assertTrue(recovery.shouldRecover(120_000, 8000, true, true))
        assertFalse(recovery.shouldRecover(120_000, 80_000, true, true))
        recovery.begin(150_000, 90_000)
        assertFalse(recovery.shouldRecover(150_000, 90_000, true, true))
        assertTrue(recovery.shouldRecover(150_000, 97_000, true, true))
    }
    @Test fun pauseAndAudioFocusSuppressionDoNotTriggerRecovery() {
        val recovery = SeekRecovery()
        recovery.begin(10_000, 0)
        for (now in 0L..30_000 step 1000) assertFalse(recovery.shouldRecover(10_000, now, false, true))
        assertFalse(recovery.shouldRecover(10_000, 31_000, true, true))
        assertTrue(recovery.shouldRecover(10_000, 38_000, true, true))
    }
    @Test fun waitingForNetworkBufferingDoesNotConsumeTheRecoveryAttempt() {
        val recovery = SeekRecovery()
        recovery.begin(60_000, 0)
        for (now in 0L..60_000 step 1000) assertFalse(recovery.shouldRecover(60_000, now, true, false))
        assertFalse(recovery.shouldRecover(60_000, 61_000, true, true))
        assertTrue(recovery.shouldRecover(60_000, 68_000, true, true))
    }
    @Test fun sustainedPlaybackAndStopDisarmTheSeekWatch() {
        val recovery = SeekRecovery()
        recovery.begin(60_000, 0)
        for (now in 1000L..5000 step 1000) assertFalse(recovery.shouldRecover(60_000+now, now, true, true))
        assertFalse(recovery.shouldRecover(65_000, 50_000, true, true))
        recovery.begin(80_000, 60_000); recovery.clear()
        assertFalse(recovery.shouldRecover(80_000, 160_000, true, true))
    }
    @Test fun aFrozenVideoFrameRecoversEvenWhenTheAudioClockMoves() {
        val recovery = SeekRecovery()
        recovery.begin(60_000, 0)
        assertFalse(recovery.shouldRecover(61_000, 1000, true, true, 1))
        assertFalse(recovery.shouldRecover(62_000, 2000, true, true, 1))
        assertFalse(recovery.shouldRecover(68_000, 8000, true, true, 1))
        assertTrue(recovery.shouldRecover(69_000, 9000, true, true, 1))
    }
}
