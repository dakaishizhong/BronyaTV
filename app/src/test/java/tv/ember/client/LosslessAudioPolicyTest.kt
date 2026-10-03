package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.player.LosslessAudioPolicy

class LosslessAudioPolicyTest {
    @Test fun losslessFamilyUsesPcmAndRuntimeFallbackOccursOnlyOnce() {
        for(mime in listOf("audio/true-hd","audio/mlp","audio/vnd.dts","audio/vnd.dts.hd")) {
            assertTrue(LosslessAudioPolicy.decodeToPcm(mime))
            assertTrue(LosslessAudioPolicy.shouldRetryWithFfmpeg(true,mime,false))
            assertFalse(LosslessAudioPolicy.shouldRetryWithFfmpeg(true,mime,true))
            assertFalse(LosslessAudioPolicy.shouldRetryWithFfmpeg(false,mime,false))
        }
        for(mime in listOf(null,"audio/aac","audio/ac3","audio/vnd.dts.uhd","video/hevc")) {
            assertFalse(LosslessAudioPolicy.decodeToPcm(mime))
            assertFalse(LosslessAudioPolicy.shouldRetryWithFfmpeg(true,mime,false))
        }
    }
}
