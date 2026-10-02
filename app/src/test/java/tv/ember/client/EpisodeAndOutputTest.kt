package tv.ember.client

import org.junit.Assert.*
import org.junit.Test
import tv.ember.client.data.*
import tv.ember.client.monitor.OutputLabels
import tv.ember.client.player.SkipPolicy

class EpisodeAndOutputTest {
    private fun episode(id:String,series:String="s")=VideoItem(id,id,"Episode",seriesId=series)
    @Test fun neighborsRespectServerOrderAndDoNotCrossSeriesOrMissingItems() {
        val current=episode("b")
        val order=listOf(episode("z"),episode("other","another"),current,episode("a"))
        assertEquals("z",EpisodeNeighbors.from(current,order).previous?.id)
        assertEquals("a",EpisodeNeighbors.from(current,order).next?.id)
        assertNull(EpisodeNeighbors.from(episode("missing"),order).next)
        assertNull(EpisodeNeighbors.from(episode("z"),order).previous)
        assertNull(EpisodeNeighbors.from(episode("a"),order).next)
        assertNull(EpisodeNeighbors.from(current.copy(type="Movie"),order).next)
    }
    @Test fun introDoesNotRewindResumedEpisodesOrSkipBeyondDuration() {
        assertEquals(90_000L,SkipPolicy.intro(0,2_000_000,90))
        assertNull(SkipPolicy.intro(100_000,2_000_000,90))
        assertNull(SkipPolicy.intro(0,50_000,90))
        assertNull(SkipPolicy.intro(0,-1,90));assertNull(SkipPolicy.intro(0,2_000_000,0))
        assertFalse(SkipPolicy.outro(0,50_000,90))
        assertFalse(SkipPolicy.outro(100_000,-1,90))
        assertTrue(SkipPolicy.outro(1_920_000,2_000_000,90))
        assertFalse(SkipPolicy.outro(2_000_000,2_000_000,90))
    }
    @Test fun dolbyFallbackAndUnrenderedInputAreNeverReportedAsActiveDolbyVision() {
        assertEquals("等待实际画面输出",OutputLabels.video(6,true,"video/dolby-vision",false))
        assertTrue(OutputLabels.video(6,true,"video/hevc",true).contains("兼容解码路径"))
        assertTrue(OutputLabels.video(6,true,"video/dolby-vision",true).contains("Dolby Vision 解码路径"))
        assertTrue(OutputLabels.video(6,true,"video/hevcdv",true).contains("Dolby Vision 解码路径"))
        assertTrue(OutputLabels.video(6,true,"video/dv_hevc",true).contains("Dolby Vision 解码路径"))
        assertEquals("SDR",OutputLabels.video(3,false,"video/avc",true))
        assertTrue(OutputLabels.video(-1,false,"video/avc",true).contains("未提供"))
    }
    @Test fun pcmOutputDoesNotInheritAtmosLabelFromInputOrTrueHdContainer() {
        assertEquals("PCM 16bit",OutputLabels.audio(2))
        assertFalse(OutputLabels.audio(2).contains("Atmos"))
        assertTrue(OutputLabels.atmos(2,"audio/eac3-joc").contains("未确认"))
        assertTrue(OutputLabels.atmos(14,"audio/true-hd").contains("不能证明"))
        assertTrue(OutputLabels.atmos(18,"audio/eac3-joc").contains("已提交 JOC 码流"))
        assertTrue(OutputLabels.atmos(18,"audio/eac3-joc").contains("接收设备模式未确认"))
    }
}
