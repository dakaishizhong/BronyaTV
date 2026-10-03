package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import android.view.View
import android.graphics.Typeface
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
        status = TvUi.text(this, Tr.text(UiText.FETCHING_MOVIE_DETAILS_246), 22f)
        setContentView(paddedColumn().apply { TvUi.add(this, status) })
        load()
    }
    private fun load() {
        val s = app.sessions.load() ?: run { finish(); return }
        val id = intent.getStringExtra("item_id") ?: run { finish(); return }
        lifecycleScope.launch {
            try {
                item = app.api.detail(s, id); render(item!!, s)
                if(intent.getBooleanExtra("auto_play",false)) { intent.removeExtra("auto_play");chooseVersion(item!!.resumeTicks/10_000) }
            }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) {
                val root = paddedColumn(); TvUi.add(root, TvUi.text(this@DetailActivity, e.message ?: Tr.text(UiText.FAILED_TO_LOAD_247)))
                TvUi.add(root, TvUi.button(this@DetailActivity, Tr.text(UiText.RETRY_248)) { load() }); setContentView(root)
            }
        }
    }
    private fun render(video: VideoItem, session: Session) {
        MediaUi.backdropItem=video
        val surface=TvUi.backdrop(this)
        PosterLoader.load(lifecycleScope,surface.findViewWithTag("backdrop"),app.api.landscapeUrl(session,video,true),session,true)
        val scroll=ScrollView(this).apply { isFillViewport=true;clipToPadding=false }
        val root=TvUi.column(this).apply { setPadding(TvUi.dp(this,TvUi.gutter(context)),TvUi.dp(this,18),TvUi.dp(this,TvUi.gutter(context)),TvUi.dp(this,20)) }
        val top=TvUi.row(this)
        top.addView(TvUi.back(this) { onBackPressedDispatcher.onBackPressed() },LinearLayout.LayoutParams(TvUi.dp(root,32),TvUi.dp(root,32)))
        top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        top.addView(TextClock(this).apply { format24Hour="HH:mm";format12Hour="HH:mm";textSize=12f;setTextColor(TvUi.text) })
        TvUi.add(root,top,bottom=8)
        TvUi.add(root,TvUi.text(this,video.name,44f*TvUi.scale(this)).apply { typeface=Typeface.DEFAULT_BOLD;maxLines=2;includeFontPadding=false },bottom=6)
        TvUi.add(root,TvUi.text(this,"",14f).apply { text=MediaUi.styledMetadata(video) },bottom=8)
        val badges=MediaUi.badgeRow(this,video)
        if(badges.childCount>0) TvUi.add(root,badges,bottom=8)
        TvUi.add(root,TvUi.text(this,video.overview.ifBlank { Tr.text(UiText.NO_OVERVIEW_PROVIDED_BY_THE_SERVER_250) },14f).apply {
            maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=TvUi.dp(this,460)
        },width=TvUi.dp(root,460),bottom=12)
        val actions=TvUi.row(this)
        val play=TvUi.button(this,if(video.resumeTicks>0) Tr.text(UiText.RESUME_251) else Tr.text(UiText.PLAY_252),true) { chooseVersion(video.resumeTicks/10_000) }
        actions.addView(play,LinearLayout.LayoutParams(-2,TvUi.dp(root,44)))
        fun action(label: String,block: ()->Unit) { actions.addView(TvUi.button(this,label,block),LinearLayout.LayoutParams(-2,TvUi.dp(root,44)).apply { marginStart=TvUi.dp(root,8) }) }
        if(video.resumeTicks>0) action(Tr.text(UiText.PLAY_FROM_START_253)) { chooseVersion(0) }
        action(Tr.text(UiText.SOURCES_254)) { chooseVersion(video.resumeTicks/10_000,true) }
        action(Tr.text(UiText.MORE_INFO_255)) {
            val details=listOf(video.overview,MediaUi.metadata(video),Tr.text(UiText.RATING_256 ,(video.rating.ifBlank { Tr.text(UiText.NOT_PROVIDED_026) })),video.people.joinToString("\n") { "${it.name} · ${it.role.ifBlank { it.type }}" }).filter(String::isNotBlank).joinToString("\n\n")
            TvUi.dialog(this).setTitle(video.name).setMessage(details).setPositiveButton(Tr.text(UiText.OFF_187),null).show()
        }
        action(Tr.text(UiText.PLAYER_257 ,(choice.label))) {
            val choices=PlayerChoice.entries
            TvUi.dialog(this).setTitle(Tr.text(UiText.CHOOSE_PLAYER_258)).setItems(choices.map { it.label+if(ExternalPlayers.available(this,it)) "" else Tr.text(UiText.NOT_INSTALLED_259) }.toTypedArray()) { _,which ->
                val c=choices[which]
                if(ExternalPlayers.available(this,c)) { choice=c;render(video,session) } else message(Tr.text(UiText.INSTALL_ON_YOUR_TV_FIRST_260 ,(c.label)))
            }.show()
        }
        TvUi.add(root,actions,bottom=6)
        if(video.resumeTicks>0) {
            TvUi.add(root,TvUi.progress(this,MediaUi.percent(video)),width=TvUi.dp(root,220),height=TvUi.dp(root,4),bottom=4)
            TvUi.add(root,TvUi.text(this,Tr.text(UiText.WATCHED_261 ,(MediaUi.percent(video)),(MediaUi.remaining(video))),11f,TvUi.accent),bottom=4)
        }
        status=TvUi.text(this,"",12f,TvUi.muted).apply { visibility=View.GONE };TvUi.add(root,status,bottom=8)
        if(video.people.isNotEmpty()) {
            val cast=TvUi.column(this).apply { background=TvUi.box(0xDD0C1C26.toInt(),12f,0xFF1C3744.toInt());setPadding(TvUi.dp(this,12),TvUi.dp(this,6),TvUi.dp(this,12),TvUi.dp(this,8)) }
            TvUi.add(cast,TvUi.text(this,"",15f).apply { text=TvUi.sectionTitle(Tr.text(UiText.CAST_CREW_262)) },bottom=4)
            val people=TvUi.row(this)
            video.people.take(8).forEach { person ->
                val personRow=TvUi.row(this)
                val portrait=FrameLayout(this).apply { background=TvUi.box(TvUi.raised,100f);clipToOutline=true }
                portrait.addView(TvUi.text(this,person.name.take(1),18f,TvUi.accent).apply { gravity=android.view.Gravity.CENTER },FrameLayout.LayoutParams(-1,-1))
                if(person.id.isNotBlank() && person.imageTag.isNotBlank()) {
                    val photo=ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
                    portrait.addView(photo,FrameLayout.LayoutParams(-1,-1))
                    PosterLoader.load(lifecycleScope,photo,app.api.imageUrl(session,VideoItem(person.id,person.name,"Person",imageTag=person.imageTag)),session)
                }
                personRow.addView(portrait,LinearLayout.LayoutParams(TvUi.dp(root,40),TvUi.dp(root,40)))
                val label=TvUi.column(this)
                TvUi.add(label,TvUi.text(this,person.name,12f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END },bottom=3)
                TvUi.add(label,TvUi.text(this,person.role.ifBlank { when(person.type) { "Director" -> Tr.text(UiText.DIRECTOR_263);"Actor" -> Tr.text(UiText.ACTOR_264);"Writer" -> Tr.text(UiText.WRITER_265);else -> person.type } },10f,TvUi.muted).apply { maxLines=1 },bottom=0)
                personRow.addView(label,LinearLayout.LayoutParams(TvUi.dp(root,118),-2).apply { marginStart=TvUi.dp(root,8) })
                people.addView(personRow,LinearLayout.LayoutParams(-2,-2).apply { marginEnd=TvUi.dp(root,14) })
            }
            cast.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false;addView(people) })
            TvUi.add(root,cast,bottom=8)
        }
        val related=TvUi.column(this);TvUi.add(root,related,bottom=0)
        lifecycleScope.launch {
            try {
                val items=app.api.similar(session,video.id).filter { it.id!=video.id }
                if(items.isNotEmpty()) {
                    TvUi.section(related,Tr.text(UiText.RELATED_TITLES_266))
                    val strip=TvUi.row(this@DetailActivity)
                    val presenter=LandscapeCardPresenter(app,lifecycleScope,TvUi.cardWidth(this@DetailActivity))
                    items.forEach { v ->
                        val holder=presenter.onCreateViewHolder(strip);presenter.onBindViewHolder(holder,v)
                        holder.view.setOnClickListener { startActivity(Intent(this@DetailActivity,DetailActivity::class.java).putExtra("item_id",v.id)) }
                        strip.addView(holder.view,LinearLayout.LayoutParams(TvUi.dp(strip,TvUi.cardWidth(this@DetailActivity)),-2).apply { marginEnd=TvUi.dp(strip,TvUi.cardGap(this@DetailActivity)) })
                    }
                    related.addView(HorizontalScrollView(this@DetailActivity).apply { isHorizontalScrollBarEnabled=false;addView(strip) })
                }
            } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Recommendations are optional. */ }
        }
        scroll.addView(root);surface.addView(scroll)
        setContentView(TvUi.shell(this,Tr.text(UiText.HOME_267),{ name ->
            if(name==Tr.text(UiText.SETTINGS_268)) startActivity(Intent(this,SettingsActivity::class.java))
            else { startActivity(Intent(this,MainActivity::class.java).putExtra("navigate",name).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));finish() }
        },surface));play.requestFocus()
    }
    private fun chooseVersion(positionMs: Long,forceChoice: Boolean=false) {
        if(busy) return
        val v = item ?: return; val s = app.sessions.load() ?: return
        busy = true; status.visibility=View.VISIBLE;status.text = Tr.text(UiText.FETCHING_PLAYABLE_VERSIONS_206)
        lifecycleScope.launch {
            try {
                val now=android.os.SystemClock.elapsedRealtime()
                val info = playbackInfo?.takeIf { now-playbackInfoAt<15_000 } ?: app.api.playbackInfo(s,v.id).also { playbackInfo=it;playbackInfoAt=now }
                check(info.versions.isNotEmpty()) { Tr.text(UiText.SERVER_HAS_NO_VIDEO_VERSIONS_269) }
                status.text = Tr.text(UiText.VERSIONS_AVAILABLE_270 ,(info.versions.size))
                fun play(index: Int) {
                        val source = info.versions[index]
                        if(choice == PlayerChoice.INTERNAL) {
                            val spec=app.api.playbackSpec(s,v.id,source,info.playSessionId)
                            app.launches.put(s,v,spec)
                            startActivity(Intent(this@DetailActivity, PlaybackActivity::class.java).putExtra("item_id", v.id)
                                .putExtra("source_id", source.id).putExtra("position_ms", positionMs))
                        } else {
                            try { ExternalPlayers.launch(this@DetailActivity, choice, app.api.playbackSpec(s, v.id, source, info.playSessionId), v.name, positionMs) }
                            catch(e: Exception) { message(e.message ?: Tr.text(UiText.CANNOT_OPEN_EXTERNAL_PLAYER_271)) }
                        }
                }
                if(info.versions.size==1 && !forceChoice) play(0) else {
                    val dialog=TvUi.dialog(this@DetailActivity).setTitle(Tr.text(UiText.CHOOSE_VIDEO_VERSION_272))
                    .setItems(info.versions.map { it.label }.toTypedArray()) { _,index ->
                        try { play(index) } catch(e: Exception) { message(e.message ?: Tr.text(UiText.CANNOT_OPEN_THE_SELECTED_SOURCE_273)) }
                    }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).create()
                    versionDialog=dialog;dialog.setOnDismissListener { busy=false;versionDialog=null };dialog.show()
                }
            } catch(e: CancellationException) { throw e }
            catch(e: Exception) { status.text = e.message ?: Tr.text(UiText.SOURCE_REQUEST_FAILED_210) }
            finally { if(versionDialog==null) busy=false }
        }
    }
}
