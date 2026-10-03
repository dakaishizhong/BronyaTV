package tv.ember.client.player

/** These codecs use a decoder and PCM output, rather than relying on HDMI passthrough. */
object LosslessAudioPolicy {
    private val pcmCodecs = setOf("audio/true-hd", "audio/mlp", "audio/vnd.dts", "audio/vnd.dts.hd")
    fun decodeToPcm(mime: String?) = mime?.lowercase() in pcmCodecs
    fun shouldRetryWithFfmpeg(audioRendererFailure: Boolean, mime: String?, alreadySoftware: Boolean) =
        audioRendererFailure && decodeToPcm(mime) && !alreadySoftware
}
