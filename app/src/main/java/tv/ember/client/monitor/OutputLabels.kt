package tv.ember.client.monitor

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
/** Labels describe observed pipeline data; display capabilities are never treated as active output. */
object OutputLabels {
    // Media3 maps these vendor MIME aliases to Dolby Vision codecs.
    private val dolbyVisionMimes=setOf("video/dolby-vision","video/hevcdv","video/dv_hevc")
    fun video(transfer: Int, dolbyInput: Boolean, codecMime: String, rendered: Boolean): String {
        if(!rendered) return Tr.text(UiText.WAITING_FOR_DECODED_VIDEO_OUTPUT_041)
        val colour=when(transfer) { 6 -> "PQ / HDR";7 -> "HLG / HDR";3 -> "SDR";else -> Tr.text(UiText.COLOR_TRANSFER_NOT_PROVIDED_042) }
        return when {
            dolbyInput && codecMime in dolbyVisionMimes -> Tr.text(UiText.DOLBY_VISION_DECODER_PATH_043 ,(colour))
            dolbyInput -> Tr.text(UiText.DOLBY_VISION_INPUT_COMPATIBLE_DECODER_PATH_044 ,(colour))
            else -> colour
        }
    }
    fun audio(encoding: Int): String=when(encoding) {
        2 -> "PCM 16bit";3 -> "PCM 8bit";4 -> "PCM Float"
        0x20000000 -> "PCM 24bit";0x30000000 -> "PCM 32bit"
        5 -> Tr.text(UiText.AC_DOLBY_DIGITAL_BITSTREAM_045);6 -> Tr.text(UiText.E_AC_DOLBY_DIGITAL_PLUS_BITSTREAM_046)
        14 -> Tr.text(UiText.DOLBY_TRUEHD_BITSTREAM_047);18 -> Tr.text(UiText.E_AC_JOC_DOLBY_ATMOS_BITSTREAM_048)
        7 -> Tr.text(UiText.DTS_BITSTREAM_049);8 -> Tr.text(UiText.DTS_HD_BITSTREAM_050);17 -> Tr.text(UiText.AC_BITSTREAM_051)
        0 -> Tr.text(UiText.WAITING_FOR_SYSTEM_AUDIO_OUTPUT_052);else -> Tr.text(UiText.SYSTEM_ENCODING_053 ,(encoding))
    }
    fun atmos(encoding: Int,inputMime: String?): String=when {
        encoding==18 -> Tr.text(UiText.DOLBY_ATMOS_JOC_BITSTREAM_SUBMITTED_RECEIVER_054)
        inputMime=="audio/eac3-joc" -> Tr.text(UiText.DOLBY_ATMOS_JOC_INPUT_DECODED_ATMOS_055)
        inputMime=="audio/true-hd" -> Tr.text(UiText.DOLBY_ATMOS_OUTPUT_UNCONFIRMED_TRUEHD_ALONE_056)
        else -> Tr.text(UiText.DOLBY_ATMOS_CURRENT_OUTPUT_UNCONFIRMED_057)
    }
}
