package tv.ember.client.ui

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import tv.ember.client.player.BufferPolicy
import tv.ember.client.player.ExternalPlayers
import tv.ember.client.settings.*

class SettingsActivity: TvActivity() {
    private var taps=0
    private var scroll: ScrollView?=null
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState);render() }
    private fun render() {
        val focused=currentFocus?.tag as? String
        val previousScroll=scroll?.scrollY ?: 0
        val scroller=ScrollView(this).apply { clipToPadding=false };scroll=scroller
        val root=paddedColumn();scroller.addView(root);setContentView(scroller)
        TvUi.add(root,TvUi.text(this,"设置",31f),bottom=3)
        TvUi.add(root,TvUi.text(this,"让播放更适合你的电视",16f,TvUi.muted),bottom=5)
        val p=app.settings
        fun setting(key: String,label: String,action: ()->Unit) {
            TvUi.add(root,TvUi.button(this,label,action).apply { tag=key;gravity=Gravity.START or Gravity.CENTER_VERTICAL })
        }
        fun hint(value: String) { TvUi.add(root,TvUi.text(this,value,13f,TvUi.muted),bottom=4) }
        TvUi.section(root,"网络与缓存")
        setting("connections","分段接收：${when(p.streamConnections) { 0 -> "自动";1 -> "单连接";else -> "${p.streamConnections} 路独立连接" }}") {
            val counts=listOf(0,1,2,4,8)
            select("分段接收",listOf("自动 · 按片源码率和内存选择","单连接","2 路","4 路","8 路"),counts.indexOf(p.streamConnections)) {
                p.streamConnections=counts[it];render()
            }
        }
        hint("自动模式为高码率影片选择多路接收；服务器不支持分段时使用单连接。更改后重新打开视频。")
        setting("receive","网络接收缓冲：${if(p.receiveBufferKb==0) "系统自动" else "${p.receiveBufferKb}KB"}") {
            val sizes=listOf(0,256,512,1024,2048,4096)
            select("网络接收缓冲",sizes.map { if(it==0) "系统自动（推荐）" else "${it}KB" },sizes.indexOf(p.receiveBufferKb)) {
                p.receiveBufferKb=sizes[it];render()
            }
        }
        hint("推荐系统自动，保留 TCP 自动调节。手动值受电视内核限制，不代表实际接收窗口。")
        setting("mode","缓冲模式：${p.mode.label}") {
            select("缓冲模式",BufferMode.entries.map { it.label },BufferMode.entries.indexOf(p.mode)) { p.mode=BufferMode.entries[it];render() }
        }
        setting("memory","缓存大小上限：${when(p.bufferMb) { 0 -> "自动";1024 -> "1GB";2048 -> "2GB";else -> "${p.bufferMb}MB" }}") {
            val sizes=listOf(0,16,32,64,128,256,512,1024,2048)
            select("播放内存上限",sizes.map { when(it) { 0 -> "自动（推荐）";1024 -> "1GB";2048 -> "2GB";else -> "${it}MB" } },sizes.indexOf(p.bufferMb)) { p.bufferMb=sizes[it];render() }
        }
        val rt=Runtime.getRuntime()
        val safe=BufferPolicy.create(p.snapshot(),rt.maxMemory(),rt.totalMemory()-rt.freeMemory())
        hint("当前播放缓冲安全上限约 ${safe.targetBytes/1_048_576}MB；按需使用，播放结束释放，不占用电视磁盘。")
        setting("prebuffer","预缓冲时间：${p.prebuffer} 秒") {
            val seconds=listOf(2,5,10,15,30,60)
            select("启动前缓冲",seconds.map { "${it} 秒" },seconds.indexOf(p.prebuffer)) { p.prebuffer=seconds[it];render() }
        }
        setting("backbuffer","回退缓存：${if(p.backBufferSeconds==0) "关闭" else "最多 ${p.backBufferSeconds} 秒"}") {
            val seconds=listOf(0,5,15,30)
            select("回退缓存",seconds.map { if(it==0) "关闭" else "${it} 秒" },seconds.indexOf(p.backBufferSeconds)) { p.backBufferSeconds=seconds[it];render() }
        }
        hint("回退缓存用于快速回看，共享播放内存上限；高码率或低内存时自动缩短。")
        TvUi.section(root,"播放与遥控器")
        setting("seek","快进 / 快退步长：${p.seekSeconds} 秒") {
            val seconds=listOf(5,10,20,30,60)
            select("遥控器跳转步长",seconds.map { "${it} 秒" },seconds.indexOf(p.seekSeconds)) { p.seekSeconds=seconds[it];render() }
        }
        hint("控制栏隐藏时，左右键预览位置，松开后跳转；长按加速，返回键取消。")
        setting("player","默认播放器：${p.player.label}") {
            select("默认播放器",PlayerChoice.entries.map { it.label+if(ExternalPlayers.available(this,it)) "" else "（未安装）" },PlayerChoice.entries.indexOf(p.player)) {
                val selected=PlayerChoice.entries[it]
                if(ExternalPlayers.available(this,selected)) { p.player=selected;render() } else message("请先安装 ${selected.label}")
            }
        }
        val resizeValues=listOf(0,4,3);val resizeLabels=listOf("适应屏幕","裁切填满","拉伸")
        setting("resize","画面比例：${resizeLabels[resizeValues.indexOf(p.resizeMode)]}") {
            select("画面比例",resizeLabels,resizeValues.indexOf(p.resizeMode)) { p.resizeMode=resizeValues[it];render() }
        }
        TvUi.section(root,"音轨与字幕偏好")
        val audioValues=listOf("","zh","en","ja");val audioLabels=listOf("自动","中文","英语","日语")
        setting("audio","优先音轨：${audioLabels[audioValues.indexOf(p.audioLanguage).coerceAtLeast(0)]}") {
            select("优先音轨",audioLabels,audioValues.indexOf(p.audioLanguage).coerceAtLeast(0)) { p.audioLanguage=audioValues[it];render() }
        }
        val textValues=listOf("","zh","en","ja","off");val textLabels=listOf("自动","中文","英语","日语","默认关闭")
        setting("subtitle","字幕偏好：${textLabels[textValues.indexOf(p.subtitleLanguage).coerceAtLeast(0)]}") {
            select("字幕偏好",textLabels,textValues.indexOf(p.subtitleLanguage).coerceAtLeast(0)) { p.subtitleLanguage=textValues[it];render() }
        }
        setting("subtitle_size","字幕大小：${p.subtitleScale}%") {
            val sizes=listOf(80,100,120,140)
            select("字幕大小",sizes.map { "${it}%" },sizes.indexOf(p.subtitleScale)) { p.subtitleScale=sizes[it];render() }
        }
        hint("播放期间还可在“播放选项”里切换轨道、倍速和画面比例。")
        TvUi.section(root,"信息与账号")
        setting("osd","显示性能信息：${if(p.osd) "开启" else "关闭"}") { p.osd=!p.osd;render() }
        if(p.debugEnabled) setting("debug","高级调试：开启 · 点击关闭") { p.debugEnabled=false;taps=0;render() }
        setting("about","关于 BronyaTV · ${tv.ember.client.BuildConfig.VERSION_NAME}") {
            taps++
            if(taps>=7) { p.debugEnabled=true;message("本次会话已启用高级调试");render() }
            else if(taps>=4) message("再按 ${7-taps} 次启用调试")
        }
        setting("logout","退出登录") {
            AlertDialog.Builder(this).setTitle("退出当前 Emby 账号？").setPositiveButton("退出") { _,_->
                app.sessions.clear();app.launches.clear();PosterLoader.clear();finish()
            }.setNegativeButton("取消",null).show()
        }
        root.post {
            val target=root.findViewWithTag<View>(focused ?: "connections")
            target?.requestFocus()
            if(focused!=null) scroller.scrollTo(0,previousScroll)
        }
    }
    private fun select(title: String,values: List<String>,checked: Int,action: (Int)->Unit) {
        AlertDialog.Builder(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(),checked.coerceAtLeast(0)) { dialog,which ->
            dialog.dismiss();action(which)
        }.setNegativeButton("取消",null).show()
    }
}
