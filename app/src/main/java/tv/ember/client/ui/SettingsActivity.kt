package tv.ember.client.ui

import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import tv.ember.client.player.BufferPolicy
import tv.ember.client.player.ExternalPlayers
import tv.ember.client.settings.*

class SettingsActivity : TvActivity() {
    private var taps = 0
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }
    private fun render() {
        val scroll = ScrollView(this); val root = paddedColumn(); scroll.addView(root); setContentView(scroll)
        TvUi.add(root, TvUi.text(this, "播放设置", 30f))
        val p = app.settings
        fun setting(label: String, action: () -> Unit) { TvUi.add(root, TvUi.button(this, label, action)) }
        setting("缓冲模式：${p.mode.label}") {
            select("缓冲模式", BufferMode.entries.map { it.label }, BufferMode.entries.indexOf(p.mode)) { p.mode = BufferMode.entries[it]; render() }
        }
        setting("缓存大小上限：${when(p.bufferMb) { 1024 -> "1GB"; 2048 -> "2GB"; else -> "512MB" }}") {
            select("缓存大小", listOf("512MB", "1GB", "2GB"), listOf(512, 1024, 2048).indexOf(p.bufferMb)) { p.bufferMb = listOf(512, 1024, 2048)[it]; render() }
        }
        setting("预缓冲时间：${p.prebuffer} 秒") {
            select("预缓冲时间", listOf("5 秒", "15 秒", "30 秒", "60 秒"), listOf(5, 15, 30, 60).indexOf(p.prebuffer)) { p.prebuffer = listOf(5, 15, 30, 60)[it]; render() }
        }
        val rt = Runtime.getRuntime()
        val safe = BufferPolicy.create(p.snapshot(), rt.maxMemory(), rt.totalMemory() - rt.freeMemory())
        TvUi.add(root, TvUi.text(this, "缓冲仅保存在内存，播放结束即释放。电视内存不足时自动缩小；当前安全上限约 ${safe.targetBytes / 1_048_576}MB。设置在下次播放生效。", 15f, TvUi.muted))
        setting("网络接收缓冲：${if(p.receiveBufferKb == 0) "系统自动" else "${p.receiveBufferKb}KB"}") {
            val sizes = listOf(0, 256, 512, 1024, 2048, 4096)
            select("网络接收缓冲", sizes.map { if(it == 0) "系统自动（推荐）" else "${it}KB" }, sizes.indexOf(p.receiveBufferKb)) {
                p.receiveBufferKb = sizes[it]; render()
            }
        }
        TvUi.add(root, TvUi.text(this, "推荐系统自动：保留 TCP 窗口自动调节。手动设置会关闭该连接的自动调节，较大的请求也可能被系统夹小，导致高延迟链路限速。无需 root，更改后重新打开视频生效。性能信息显示接收缓冲报告值，它不等同于服务端观察到的实际接收窗口。", 15f, TvUi.muted))
        setting("分段接收：${if(p.streamConnections==1) "单连接" else "${p.streamConnections} 路独立连接"}") {
            val counts=listOf(1,2,4,8)
            select("分段接收",listOf("单连接（默认）","2 路","4 路","8 路"),counts.indexOf(p.streamConnections)) { p.streamConnections=counts[it];render() }
        }
        TvUi.add(root,TvUi.text(this,"高延迟、小接收窗口时可尝试 4 路，再比较 8 路。仅对支持 Range 的原文件生效；不支持时自动使用单连接。分段预取只在播放期间保存在有界内存中，不写磁盘。更改后重新打开视频。",15f,TvUi.muted))
        setting("默认播放器：${p.player.label}") {
            select("默认播放器", PlayerChoice.entries.map { it.label + if(ExternalPlayers.available(this, it)) "" else "（未安装）" }, PlayerChoice.entries.indexOf(p.player)) {
                val selected = PlayerChoice.entries[it]
                if(ExternalPlayers.available(this, selected)) { p.player = selected; render() } else message("请先安装 ${selected.label}")
            }
        }
        setting("显示性能信息：${if(p.osd) "开启" else "关闭"}") { p.osd = !p.osd; render() }
        if(p.debugEnabled) setting("高级调试：开启 · 点击关闭") { p.debugEnabled = false; taps = 0; render() }
        setting("关于 Ember TV · ${tv.ember.client.BuildConfig.VERSION_NAME}") {
            taps++
            if(taps >= 7) { p.debugEnabled = true; message("本次会话已启用高级调试，可在播放菜单切换显示"); render() }
            else if(taps >= 4) message("再按 ${7 - taps} 次启用调试")
        }
        setting("退出登录") {
            AlertDialog.Builder(this).setTitle("退出当前 Emby 账号？").setPositiveButton("退出") { _, _ ->
                app.sessions.clear(); PosterLoader.clear(); finish()
            }.setNegativeButton("取消", null).show()
        }
        root.getChildAt(1)?.requestFocus()
    }
    private fun select(title: String, values: List<String>, checked: Int, action: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(), checked) { dialog, which -> dialog.dismiss(); action(which) }.setNegativeButton("取消", null).show()
    }
}
