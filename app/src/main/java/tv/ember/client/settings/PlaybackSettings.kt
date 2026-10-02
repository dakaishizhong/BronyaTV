package tv.ember.client.settings

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.content.Context

enum class BufferMode(private val text: UiText) {
    AUTO(UiText.AUTO_220), LOW_LATENCY(UiText.LOW_LATENCY_242), BALANCED(UiText.BALANCED_243), LARGE(UiText.LARGE_BUFFER_244);
    val label get()=Tr.text(text)
}
enum class PlayerChoice(private val nameLabel: String, val packages: List<String>) {
    INTERNAL("", emptyList()), VLC("VLC", listOf("org.videolan.vlc")),
    MX("MX Player", listOf("com.mxtech.videoplayer.pro", "com.mxtech.videoplayer.ad")),
    JUST("Just Player", listOf("com.brouken.player"));
    val label get()=if(this==INTERNAL) Tr.text(UiText.INTERNAL_PLAYER_245) else nameLabel
}
data class BufferPreferences(val mode: BufferMode = BufferMode.AUTO, val requestedMb: Int = 0, val prebufferSeconds: Int = 2, val backBufferSeconds: Int = 5)
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
        get() = p.getInt("buffer_mb", 0).takeIf { it in listOf(0, 16, 32, 64, 128, 256, 512, 1024, 2048) } ?: 0
        set(v) { require(v in listOf(0, 16, 32, 64, 128, 256, 512, 1024, 2048)); p.edit().putInt("buffer_mb", v).apply() }
    var prebuffer: Int
        get() = p.getInt("prebuffer", 2).takeIf { it in listOf(2, 5, 10, 15, 30, 60) } ?: 2
        set(v) { require(v in listOf(2, 5, 10, 15, 30, 60)); p.edit().putInt("prebuffer", v).apply() }
    var receiveBufferKb: Int
        get() = p.getInt("receive_buffer_kb", 0).takeIf { it in listOf(0, 256, 512, 1024, 2048, 4096) } ?: 0
        set(v) { require(v in listOf(0, 256, 512, 1024, 2048, 4096)); p.edit().putInt("receive_buffer_kb", v).apply() }
    var streamConnections:Int
        get()=p.getInt("stream_connections",0).takeIf { it in listOf(0,1,2,4,8) } ?: 0
        set(v) { require(v in listOf(0,1,2,4,8));p.edit().putInt("stream_connections",v).apply() }
    var backBufferSeconds: Int
        get()=p.getInt("back_buffer_seconds",5).takeIf { it in listOf(0,5,15,30) } ?: 5
        set(v) { require(v in listOf(0,5,15,30));p.edit().putInt("back_buffer_seconds",v).apply() }
    var diskCacheMb: Int
        get() = p.getInt("disk_cache_mb", -1).takeIf { it in tv.ember.client.cache.DiskCachePlan.sizesMb } ?: -1
        set(v) { require(v in tv.ember.client.cache.DiskCachePlan.sizesMb); p.edit().putInt("disk_cache_mb", v).apply() }
    var diskAheadSeconds: Int
        get() = p.getInt("disk_ahead_seconds", 60).takeIf { it in tv.ember.client.cache.DiskCachePlan.aheadSeconds } ?: 60
        set(v) { require(v in tv.ember.client.cache.DiskCachePlan.aheadSeconds); p.edit().putInt("disk_ahead_seconds", v).apply() }
    var seekSeconds: Int
        get()=p.getInt("seek_seconds",10).takeIf { it in listOf(5,10,20,30,60) } ?: 10
        set(v) { require(v in listOf(5,10,20,30,60));p.edit().putInt("seek_seconds",v).apply() }
    var longSeekSeconds: Int
        get()=p.getInt("long_seek_seconds",30).takeIf { it in listOf(10,15,20,30,45,60) } ?: 30
        set(v) { require(v in listOf(10,15,20,30,45,60));p.edit().putInt("long_seek_seconds",v).apply() }
    var introSeconds: Int
        get()=p.getInt("intro_seconds",0).coerceIn(0,600)
        set(v) { require(v in 0..600);p.edit().putInt("intro_seconds",v).apply() }
    var outroSeconds: Int
        get()=p.getInt("outro_seconds",0).coerceIn(0,600)
        set(v) { require(v in 0..600);p.edit().putInt("outro_seconds",v).apply() }
    var autoNextEpisode: Boolean
        get()=p.getBoolean("auto_next_episode",true)
        set(v) { p.edit().putBoolean("auto_next_episode",v).apply() }
    var audioLanguage: String
        get()=p.getString("audio_language","").orEmpty()
        set(v) { p.edit().putString("audio_language",v).apply() }
    var subtitleLanguage: String
        get()=p.getString("subtitle_language","zh").orEmpty()
        set(v) { p.edit().putString("subtitle_language",v).apply() }
    var resizeMode: Int
        get()=p.getInt("resize_mode",0).takeIf { it in listOf(0,3,4) } ?: 0
        set(v) { require(v in listOf(0,3,4));p.edit().putInt("resize_mode",v).apply() }
    var subtitleScale: Int
        get()=p.getInt("subtitle_scale",100).takeIf { it in listOf(80,100,120,140) } ?: 100
        set(v) { require(v in listOf(80,100,120,140));p.edit().putInt("subtitle_scale",v).apply() }
    var player: PlayerChoice
        get() = runCatching { PlayerChoice.valueOf(p.getString("player", "INTERNAL")!!) }.getOrDefault(PlayerChoice.INTERNAL)
        set(v) { p.edit().putString("player", v.name).apply() }
    var osd: Boolean
        get() = p.getBoolean("osd", false)
        set(v) { p.edit().putBoolean("osd", v).apply() }
    // Debug mode is deliberately ephemeral and does not survive process restart.
    var debugEnabled = false
    fun snapshot() = BufferPreferences(mode, bufferMb, prebuffer, backBufferSeconds)
}
