package tv.ember.client.ui

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import tv.ember.client.data.*
import tv.ember.client.player.ExternalPlayers
import tv.ember.client.player.PlaybackActivity
import tv.ember.client.settings.PlayerChoice

class DetailActivity : TvActivity() {
    private lateinit var status: TextView
    private var item: VideoItem? = null
    private var busy = false
    private var versionDialog: AlertDialog?=null
    private var choice = PlayerChoice.INTERNAL
    private var playbackInfo: PlaybackInfo?=null
    private var playbackInfoAt=0L
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        choice = app.settings.player
        status = TvUi.text(this, "正在获取影片信息…", 22f)
        setContentView(paddedColumn().apply { TvUi.add(this, status) })
        load()
    }
    private fun load() {
        val s = app.sessions.load() ?: run { finish(); return }
        val id = intent.getStringExtra("item_id") ?: run { finish(); return }
        lifecycleScope.launch {
            try { item = app.api.detail(s, id); render(item!!, s) }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                val root = paddedColumn(); TvUi.add(root, TvUi.text(this@DetailActivity, e.message ?: "加载失败"))
                TvUi.add(root, TvUi.button(this@DetailActivity, "重试") { load() }); setContentView(root)
            }
        }
    }
    private fun render(video: VideoItem, session: Session) {
        val root = paddedColumn()
        TvUi.add(root,TvUi.row(this).apply {
            addView(TvUi.back(this@DetailActivity) { onBackPressedDispatcher.onBackPressed() })
            addView(TvUi.text(this@DetailActivity,"影片详情",15f,TvUi.muted),LinearLayout.LayoutParams(-2,-2).apply { marginStart=TvUi.dp(root,14) })
        },bottom=22)
        val body = TvUi.row(this)
        val poster = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = TvUi.box(TvUi.panel,22f);clipToOutline=true }
        body.addView(poster, LinearLayout.LayoutParams(TvUi.dp(body, 170), TvUi.dp(body, 255)).apply { marginEnd = TvUi.dp(body, 28) })
        PosterLoader.load(lifecycleScope, poster, app.api.imageUrl(session, video), session)
        val info = TvUi.column(this)
        TvUi.add(info, TvUi.text(this, video.name, 30f).apply { maxLines = 2 })
        TvUi.add(info, TvUi.text(this, video.subtitle, 14f, TvUi.muted))
        val overview = ScrollView(this).apply { addView(TvUi.text(this@DetailActivity, video.overview.ifBlank { "服务器未提供简介。" }, 16f).apply { setLineSpacing(3f, 1.1f) }) }
        TvUi.add(info, overview, height = TvUi.dp(body, 136))
        status = TvUi.text(this, "", 13f, TvUi.muted)
        TvUi.add(info, status)
        body.addView(info, LinearLayout.LayoutParams(0, -2, 1f)); TvUi.add(root, body)
        val actions = TvUi.row(this)
        val play = TvUi.button(this, if(video.resumeTicks > 0) "继续播放 · ${tv.ember.client.player.SeekPolicy.time(video.resumeTicks/10000)}" else "播放",true) { chooseVersion(video.resumeTicks / 10_000) }
        actions.addView(play)
        if(video.resumeTicks > 0) actions.addView(TvUi.button(this, "从头播放") { chooseVersion(0) }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = TvUi.dp(root,12) })
        val playerButton = TvUi.button(this, "播放器：${choice.label}") {
            val choices = PlayerChoice.entries
            TvUi.dialog(this).setTitle("选择播放器").setItems(choices.map { it.label + if(ExternalPlayers.available(this, it)) "" else "（未安装）" }.toTypedArray()) { _, which ->
                val c = choices[which]
                if(ExternalPlayers.available(this, c)) { choice = c; render(video, session) } else message("请先在电视安装 ${c.label}")
            }.show()
        }
        actions.addView(playerButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = TvUi.dp(root,12) })
        TvUi.add(root, actions)
        setContentView(root); play.requestFocus()
    }
    private fun chooseVersion(positionMs: Long) {
        if(busy) return
        val v = item ?: return; val s = app.sessions.load() ?: return
        busy = true; status.text = "正在获取可播放版本…"
        lifecycleScope.launch {
            try {
                val now=android.os.SystemClock.elapsedRealtime()
                val info = playbackInfo?.takeIf { now-playbackInfoAt<15_000 } ?: app.api.playbackInfo(s,v.id).also { playbackInfo=it;playbackInfoAt=now }
                check(info.versions.isNotEmpty()) { "服务器没有提供视频版本" }
                status.text = "${info.versions.size} 个版本可供选择"
                fun play(index: Int) {
                        val source = info.versions[index]
                        if(choice == PlayerChoice.INTERNAL) {
                            val spec=app.api.playbackSpec(s,v.id,source,info.playSessionId)
                            app.launches.put(s,v,spec)
                            startActivity(Intent(this@DetailActivity, PlaybackActivity::class.java).putExtra("item_id", v.id)
                                .putExtra("source_id", source.id).putExtra("position_ms", positionMs))
                        } else {
                            try { ExternalPlayers.launch(this@DetailActivity, choice, app.api.playbackSpec(s, v.id, source, info.playSessionId), v.name, positionMs) }
                            catch(e: Exception) { message(e.message ?: "无法打开外部播放器") }
                        }
                }
                if(info.versions.size==1) play(0) else {
                    val dialog=TvUi.dialog(this@DetailActivity).setTitle("选择视频版本")
                    .setItems(info.versions.map { it.label }.toTypedArray()) { _,index ->
                        try { play(index) } catch(e: Exception) { message(e.message ?: "无法打开所选片源") }
                    }.setNegativeButton("取消",null).create()
                    versionDialog=dialog;dialog.setOnDismissListener { busy=false;versionDialog=null };dialog.show()
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.text = e.message ?: "片源请求失败" }
            finally { if(versionDialog==null) busy=false }
        }
    }
}
