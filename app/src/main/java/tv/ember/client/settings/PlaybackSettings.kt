package tv.ember.client.settings

import android.content.Context

enum class BufferMode(val label: String) { AUTO("自动"), LOW_LATENCY("低延迟"), BALANCED("平衡"), LARGE("大缓存") }
enum class PlayerChoice(val label: String, val packages: List<String>) {
    INTERNAL("内置 Media3", emptyList()), VLC("VLC", listOf("org.videolan.vlc")),
    MX("MX Player", listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad")),
    JUST("Just Player", listOf("com.brouken.player"))
}
data class BufferPreferences(val mode: BufferMode = BufferMode.AUTO, val requestedMb: Int = 512, val prebufferSeconds: Int = 5)
class PlaybackSettings(context: Context) {
    private val p = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    // SO_RCVBUF locks the socket's receive size and disables Linux TCP auto-tuning.
    // Undo manual choices made before this consequence was explained in 1.0.3.
    val restoredAutomaticWindow: Boolean
    init {
        restoredAutomaticWindow = !p.getBoolean("tcp_autotune_migrated", false) && p.getInt("receive_buffer_kb", 0) > 0
        if(!p.getBoolean("tcp_autotune_migrated", false)) {
            p.edit().putInt("receive_buffer_kb", 0).putBoolean("tcp_autotune_migrated", true).apply()
        }
    }
    var mode: BufferMode
        get() = runCatching { BufferMode.valueOf(p.getString("mode", "AUTO")!!) }.getOrDefault(BufferMode.AUTO)
        set(v) { p.edit().putString("mode", v.name).apply() }
    var bufferMb: Int
        get() = p.getInt("buffer_mb", 512).takeIf { it in listOf(512, 1024, 2048) } ?: 512
        set(v) { require(v in listOf(512, 1024, 2048)); p.edit().putInt("buffer_mb", v).apply() }
    var prebuffer: Int
        get() = p.getInt("prebuffer", 5).takeIf { it in listOf(5, 15, 30, 60) } ?: 5
        set(v) { require(v in listOf(5, 15, 30, 60)); p.edit().putInt("prebuffer", v).apply() }
    var receiveBufferKb: Int
        get() = p.getInt("receive_buffer_kb", 0).takeIf { it in listOf(0, 256, 512, 1024, 2048, 4096) } ?: 0
        set(v) { require(v in listOf(0, 256, 512, 1024, 2048, 4096)); p.edit().putInt("receive_buffer_kb", v).apply() }
    var streamConnections:Int
        get()=p.getInt("stream_connections",1).takeIf { it in listOf(1,2,4,8) } ?: 1
        set(v) { require(v in listOf(1,2,4,8));p.edit().putInt("stream_connections",v).apply() }
    var player: PlayerChoice
        get() = runCatching { PlayerChoice.valueOf(p.getString("player", "INTERNAL")!!) }.getOrDefault(PlayerChoice.INTERNAL)
        set(v) { p.edit().putString("player", v.name).apply() }
    var osd: Boolean
        get() = p.getBoolean("osd", false)
        set(v) { p.edit().putBoolean("osd", v).apply() }
    // Debug mode is deliberately ephemeral and does not survive process restart.
    var debugEnabled = false
    fun snapshot() = BufferPreferences(mode, bufferMb, prebuffer)
}
