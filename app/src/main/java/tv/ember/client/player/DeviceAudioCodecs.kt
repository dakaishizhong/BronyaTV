package tv.ember.client.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

/** Some TV vendors advertise a lossless decoder under an equivalent MIME alias. */
@UnstableApi
object DeviceAudioCodecs {
    fun query(mime: String,secure: Boolean,tunnel: Boolean): List<MediaCodecInfo> {
        val codecs=MediaCodecSelector.DEFAULT.getDecoderInfos(mime,secure,tunnel).toMutableList()
        val aliases=when(mime) {
            "audio/true-hd" -> listOf("audio/mlp","audio/truehd")
            "audio/mlp" -> listOf("audio/true-hd","audio/truehd")
            "audio/vnd.dts" -> listOf("audio/dts")
            "audio/vnd.dts.hd" -> listOf("audio/dts-hd","audio/dtshd")
            else -> emptyList()
        }
        for(alias in aliases) {
            for(codec in MediaCodecSelector.DEFAULT.getDecoderInfos(alias,secure,tunnel)) {
                if(codecs.none { it.name==codec.name }) codecs.add(MediaCodecInfo.newInstance(
                    codec.name,mime,codec.codecMimeType,codec.capabilities,codec.hardwareAccelerated,
                    codec.softwareOnly,codec.vendor,!codec.adaptive,codec.secure))
            }
        }
        return codecs.sortedBy { if(it.hardwareAccelerated) 0 else 1 }
    }
}
