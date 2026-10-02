package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import tv.ember.client.player.ExternalPlayers
import tv.ember.client.settings.*

class SettingsActivity: TvActivity() {
    private var taps=0
    private var category=0
    private var scroll: ScrollView?=null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState);category=savedInstanceState?.getInt("category") ?: 0;render()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("category",category);super.onSaveInstanceState(outState) }
    private fun render(focus: String?=currentFocus?.tag as? String) {
        val oldScroll=if(focus?.startsWith("tab")!=true) scroll?.scrollY ?: 0 else 0
        val root=paddedColumn()
        val heading=TvUi.row(this)
        heading.addView(TvUi.back(this) { onBackPressedDispatcher.onBackPressed() })
        heading.addView(TvUi.text(this,"设置",28f),LinearLayout.LayoutParams(-2,-2).apply { marginStart=TvUi.dp(root,16) })
        TvUi.add(root,heading,bottom=24)
        val body=TvUi.row(this).apply { gravity=Gravity.TOP }
        val side=TvUi.column(this)
        listOf("播放","遥控器","网络与缓存","音轨与字幕","信息与账号").forEachIndexed { index,name ->
            TvUi.add(side,TvUi.button(this,name) { category=index;scroll=null;render("tab$index") }.apply {
                tag="tab$index";isSelected=index==category;gravity=Gravity.START or Gravity.CENTER_VERTICAL
            },bottom=8)
        }
        body.addView(side,LinearLayout.LayoutParams(TvUi.dp(root,180),-1).apply { marginEnd=TvUi.dp(root,32) })
        val scroller=ScrollView(this).apply { clipToPadding=false };scroll=scroller
        val content=TvUi.column(this).apply { setPadding(TvUi.dp(this,4),TvUi.dp(this,4),TvUi.dp(this,8),TvUi.dp(this,18)) }
        scroller.addView(content);body.addView(scroller,LinearLayout.LayoutParams(0,-1,1f))
        root.addView(body,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
        val p=app.settings
        fun setting(key: String,label: String,value: String,action: ()->Unit) {
            val row=TvUi.row(this).apply {
                tag=key;isFocusable=true;isFocusableInTouchMode=true;background=TvUi.focusBackground()
                setPadding(TvUi.dp(this,18),TvUi.dp(this,14),TvUi.dp(this,18),TvUi.dp(this,14))
                minimumHeight=TvUi.dp(this,54)
            }
            val title=TvUi.text(this,label,16f)
            val detail=TvUi.text(this,"$value  ›",14f,TvUi.muted)
            row.addView(title,LinearLayout.LayoutParams(0,-2,1f));row.addView(detail)
            TvUi.focusOnTouch(row)
            row.setOnClickListener { action() }
            row.setOnFocusChangeListener { _,focused -> title.setTextColor(if(focused) TvUi.bg else TvUi.text);detail.setTextColor(if(focused) TvUi.bg else TvUi.muted) }
            TvUi.add(content,row,bottom=9)
        }
        fun hint(value: String) { TvUi.add(content,TvUi.text(this,value,13f,TvUi.muted).apply { setPadding(TvUi.dp(this,4),0,0,0) },bottom=14) }
        fun seconds(key: String,title: String,value: Int,values: List<Int>,save: (Int)->Unit) {
            setting(key,title,"$value 秒") { select(title,values.map { "$it 秒" },values.indexOf(value)) { save(values[it]);render(key) } }
        }
        when(category) {
            0 -> {
                setting("player","默认播放器",p.player.label) {
                    select("默认播放器",PlayerChoice.entries.map { it.label+if(ExternalPlayers.available(this,it)) "" else "（未安装）" },PlayerChoice.entries.indexOf(p.player)) {
                        val selected=PlayerChoice.entries[it]
                        if(ExternalPlayers.available(this,selected)) { p.player=selected;render("player") } else message("请先安装 ${selected.label}")
                    }
                }
                val resizeValues=listOf(0,4,3);val labels=listOf("适应屏幕","裁切填满","拉伸")
                setting("resize","画面比例",labels[resizeValues.indexOf(p.resizeMode)]) { select("画面比例",labels,resizeValues.indexOf(p.resizeMode)) { p.resizeMode=resizeValues[it];render("resize") } }
                setting("autonext","自动播放下一集",if(p.autoNextEpisode) "开启" else "关闭") { p.autoNextEpisode=!p.autoNextEpisode;render("autonext") }
                setting("intro","跳过片头",if(p.introSeconds==0) "关闭" else "${p.introSeconds} 秒") {
                    duration("跳过片头",p.introSeconds) { p.introSeconds=it;render("intro") }
                }
                setting("outro","跳过片尾",if(p.outroSeconds==0) "关闭" else "${p.outroSeconds} 秒") {
                    duration("跳过片尾",p.outroSeconds) { p.outroSeconds=it;render("outro") }
                }
                hint("片头片尾设置用于剧集，0 秒关闭。开启自动连播时，片尾倒计时后进入下一集；可按返回取消。")
            }
            1 -> {
                seconds("seek","短按快进 / 快退",p.seekSeconds,listOf(5,10,20,30,60)) { p.seekSeconds=it }
                seconds("long_seek","长按每次跳转",p.longSeekSeconds,listOf(10,15,20,30,45,60)) { p.longSeekSeconds=it }
                hint("画面播放时按左右键预览位置，松开后跳转；长按持续移动，按返回取消。控制栏显示时，左右键用于选择按钮。")
            }
            2 -> {
                val counts=listOf(0,1,2,4,8)
                setting("connections","分段接收",when(p.streamConnections) { 0 -> "自动";1 -> "单连接";else -> "${p.streamConnections} 路" }) {
                    select("分段接收",listOf("自动","单连接","2 路","4 路","8 路"),counts.indexOf(p.streamConnections)) { p.streamConnections=counts[it];render("connections") }
                }
                hint("多路接收适合高码率片源。更改网络与缓存设置后，重新打开视频生效。")
                val windows=listOf(0,256,512,1024,2048,4096)
                setting("receive","网络接收缓冲",if(p.receiveBufferKb==0) "系统自动" else "${p.receiveBufferKb}KB") {
                    select("网络接收缓冲",windows.map { if(it==0) "系统自动（推荐）" else "${it}KB" },windows.indexOf(p.receiveBufferKb)) { p.receiveBufferKb=windows[it];render("receive") }
                }
                hint("系统自动允许网络窗口自动调节；手动大小受设备限制。")
                setting("mode","缓冲模式",p.mode.label) { select("缓冲模式",BufferMode.entries.map { it.label },BufferMode.entries.indexOf(p.mode)) { p.mode=BufferMode.entries[it];render("mode") } }
                val sizes=listOf(0,16,32,64,128,256,512,1024,2048)
                fun size(n: Int)=when(n) { 0 -> "自动";1024 -> "1GB";2048 -> "2GB";else -> "${n}MB" }
                setting("memory","缓存大小上限",size(p.bufferMb)) { select("缓存大小上限",sizes.map(::size),sizes.indexOf(p.bufferMb)) { p.bufferMb=sizes[it];render("memory") } }
                seconds("prebuffer","预缓冲时间",p.prebuffer,listOf(2,5,10,15,30,60)) { p.prebuffer=it }
                seconds("backbuffer","回退缓存",p.backBufferSeconds,listOf(0,5,15,30)) { p.backBufferSeconds=it }
                hint("缓存按需占用内存，播放结束释放；系统会根据可用内存限制实际用量。")
            }
            3 -> {
                val av=listOf("","zh","en","ja");val al=listOf("自动","中文","英语","日语")
                setting("audio","优先音轨",al[av.indexOf(p.audioLanguage).coerceAtLeast(0)]) { select("优先音轨",al,av.indexOf(p.audioLanguage).coerceAtLeast(0)) { p.audioLanguage=av[it];render("audio") } }
                val tv=listOf("","zh","en","ja","off");val tl=listOf("自动","中文","英语","日语","默认关闭")
                setting("subtitle","字幕偏好",tl[tv.indexOf(p.subtitleLanguage).coerceAtLeast(0)]) { select("字幕偏好",tl,tv.indexOf(p.subtitleLanguage).coerceAtLeast(0)) { p.subtitleLanguage=tv[it];render("subtitle") } }
                val sizes=listOf(80,100,120,140)
                setting("subtitle_size","字幕大小","${p.subtitleScale}%") { select("字幕大小",sizes.map { "$it%" },sizes.indexOf(p.subtitleScale)) { p.subtitleScale=sizes[it];render("subtitle_size") } }
                hint("播放时可单独选择音轨、字幕、播放速度及画面比例。")
            }
            4 -> {
                setting("osd","显示性能信息",if(p.osd) "开启" else "关闭") { p.osd=!p.osd;render("osd") }
                hint("播放诊断包含片源、实际解码画面、HDR 路径和系统音频输出。")
                if(p.debugEnabled) setting("debug","高级调试","开启") { p.debugEnabled=false;taps=0;render("debug") }
                setting("about","BronyaTV",tv.ember.client.BuildConfig.VERSION_NAME) {
                    taps++;if(taps>=7) { p.debugEnabled=true;message("本次会话已启用高级调试");render("about") }
                    else if(taps>=4) message("再按 ${7-taps} 次启用调试")
                }
                setting("logout","退出登录",app.sessions.load()?.userName.orEmpty()) {
                    TvUi.dialog(this).setTitle("退出当前账号？").setPositiveButton("退出") { _,_->
                        app.sessions.clear();app.launches.clear();PosterLoader.clear()
                        startActivity(Intent(this,LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish()
                    }.setNegativeButton("取消",null).show()
                }
            }
        }
        root.post {
            val target=root.findViewWithTag<View>(focus ?: "tab$category")
            (target ?: side.getChildAt(category)).requestFocus()
            if(focus!=null && !focus.startsWith("tab")) scroller.scrollTo(0,oldScroll)
        }
    }
    private fun duration(title: String,value: Int,save: (Int)->Unit) {
        val input=TvUi.input(this,"0–600 秒").apply { inputType=InputType.TYPE_CLASS_NUMBER;setText("$value");selectAll() }
        val dialog=TvUi.dialog(this).setTitle(title).setMessage("输入秒数，0 秒关闭，最多 600 秒。")
            .setView(input).setPositiveButton("保存",null).setNegativeButton("取消",null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val seconds=input.text.toString().toIntOrNull()
                if(seconds==null || seconds !in 0..600) input.error="请输入 0–600 之间的秒数"
                else { dialog.dismiss();save(seconds) }
            }
        };dialog.show()
    }
    private fun select(title: String,values: List<String>,checked: Int,action: (Int)->Unit) {
        TvUi.dialog(this).setTitle(title).setSingleChoiceItems(values.toTypedArray(),checked.coerceAtLeast(0)) { dialog,which -> dialog.dismiss();action(which) }.setNegativeButton("取消",null).show()
    }
}
