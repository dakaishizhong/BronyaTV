package tv.ember.client.monitor

/** Labels describe observed pipeline data; display capabilities are never treated as active output. */
object OutputLabels {
    // Media3 maps these vendor MIME aliases to Dolby Vision codecs.
    private val dolbyVisionMimes=setOf("video/dolby-vision","video/hevcdv","video/dv_hevc")
    fun video(transfer: Int, dolbyInput: Boolean, codecMime: String, rendered: Boolean): String {
        if(!rendered) return "等待实际画面输出"
        val colour=when(transfer) { 6 -> "PQ / HDR";7 -> "HLG / HDR";3 -> "SDR";else -> "色彩传递未提供" }
        return when {
            dolbyInput && codecMime in dolbyVisionMimes -> "Dolby Vision 解码路径 · $colour"
            dolbyInput -> "Dolby Vision 输入 → 兼容解码路径 · $colour"
            else -> colour
        }
    }
    fun audio(encoding: Int): String=when(encoding) {
        2 -> "PCM 16bit";3 -> "PCM 8bit";4 -> "PCM Float"
        0x20000000 -> "PCM 24bit";0x30000000 -> "PCM 32bit"
        5 -> "AC-3 / Dolby Digital 码流";6 -> "E-AC-3 / Dolby Digital Plus 码流"
        14 -> "Dolby TrueHD 码流";18 -> "E-AC-3 JOC / Dolby Atmos 码流"
        7 -> "DTS 码流";8 -> "DTS-HD 码流";17 -> "AC-4 码流"
        0 -> "等待系统音频输出";else -> "系统编码 $encoding"
    }
    fun atmos(encoding: Int,inputMime: String?): String=when {
        encoding==18 -> "Dolby Atmos：系统已提交 JOC 码流，接收设备模式未确认"
        inputMime=="audio/eac3-joc" -> "Dolby Atmos：JOC 输入已解码，Atmos 输出未确认"
        inputMime=="audio/true-hd" -> "Dolby Atmos：输出未确认；TrueHD 本身不能证明包含 Atmos"
        else -> "Dolby Atmos：当前输出未确认"
    }
}
