package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.*
import androidx.leanback.widget.Presenter
import androidx.leanback.widget.RowHeaderPresenter
import androidx.lifecycle.LifecycleCoroutineScope
import kotlinx.coroutines.Job
import tv.ember.client.BronyaApp
import tv.ember.client.data.VideoItem

object MediaUi {
    var backdropItem: VideoItem?=null
    fun metadata(item: VideoItem)=listOf(
        item.communityRating.takeIf(String::isNotBlank)?.let { "★ ${it}" }.orEmpty(),item.year,
        item.genres.take(3).joinToString(" / "),item.episodeLabel,
        if(item.runtimeTicks>0) Tr.text(UiText.MIN_012 ,(item.runtimeTicks/600_000_000)) else ""
    ).filter(String::isNotBlank).joinToString("  |  ")
    fun styledMetadata(item: VideoItem)=android.text.SpannableString(metadata(item)).apply {
        if(item.communityRating.isNotBlank()) setSpan(android.text.style.ForegroundColorSpan(0xFFFFC32B.toInt()),0,"★ ${item.communityRating}".length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    fun badges(item: VideoItem): List<String> {
        val video=item.sources.firstOrNull()?.streams?.firstOrNull { it.type=="Video" }
        val audio=item.sources.firstOrNull()?.streams?.firstOrNull { it.type=="Audio" }
        return listOf(
            video?.let { when { it.width>=3800 || it.height>=2100 -> "4K";it.height>=1000 -> "1080P";it.height>=700 -> "720P";else -> "" } }.orEmpty(),
            video?.videoRange?.takeUnless { it in listOf("","SDR","None") }.orEmpty(),
            audio?.label?.takeIf { it.contains("Atmos",true) }?.let { "Dolby Atmos" }.orEmpty(),item.rating
        ).filter(String::isNotBlank).distinct()
    }
    fun badgeRow(context: android.content.Context,item: VideoItem)=TvUi.row(context).apply {
        badges(item).forEach { value -> addView(TvUi.text(context,value,11f).apply {
            background=TvUi.box(0xC00C1C26.toInt(),7f,0xFF35505C.toInt())
            setPadding(TvUi.dp(this,8),TvUi.dp(this,3),TvUi.dp(this,8),TvUi.dp(this,3))
        },LinearLayout.LayoutParams(-2,-2).apply { marginEnd=(7*context.resources.displayMetrics.density).toInt() }) }
    }
    fun remaining(item: VideoItem): String {
        val minutes=((item.runtimeTicks-item.resumeTicks).coerceAtLeast(0)/600_000_000)
        return if(item.runtimeTicks<=0) Tr.text(UiText.CONTINUE_WATCHING_297) else if(minutes>=60) Tr.text(UiText.H_M_REMAINING_330 ,(minutes/60),(minutes%60)) else Tr.text(UiText.MIN_REMAINING_331 ,(minutes))
    }
    fun percent(item: VideoItem)=if(item.runtimeTicks<=0) 0 else ((item.resumeTicks.toDouble()/item.runtimeTicks)*100).toInt().coerceIn(0,100)
}

class LandscapeCardPresenter(private val app: BronyaApp,private val scope: LifecycleCoroutineScope,private val widthDp: Int=0): Presenter() {
    private class Holder(val card: LinearLayout,val frame: FrameLayout,val image: ImageView,val label: TextView,val subtitle: TextView,val badges: LinearLayout,val progress: ProgressBar): ViewHolder(card) { var job: Job?=null }
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val card=TvUi.column(parent.context).apply {
            isFocusable=true;isFocusableInTouchMode=true
            setPadding(TvUi.dp(this,3),TvUi.dp(this,3),TvUi.dp(this,3),TvUi.dp(this,5))
            clipChildren=false;clipToPadding=false
            layoutParams=ViewGroup.LayoutParams(TvUi.dp(this,if(widthDp>0) widthDp else TvUi.cardWidth(context)),ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val frame=FrameLayout(parent.context).apply { tag="card_image";background=TvUi.box(TvUi.raised,10f);clipToOutline=true }
        val image=ImageView(parent.context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;background=TvUi.box(TvUi.raised,10f);clipToOutline=true }
        frame.addView(image,FrameLayout.LayoutParams(-1,-1))
        val progress=TvUi.progress(parent.context,0)
        frame.addView(progress,FrameLayout.LayoutParams(-1,TvUi.dp(card,4),android.view.Gravity.BOTTOM))
        val imageWidth=(if(widthDp>0) widthDp else TvUi.cardWidth(parent.context))-6
        card.addView(frame,LinearLayout.LayoutParams(-1,TvUi.dp(card,(imageWidth/2.6f).toInt())))
        val label=TvUi.text(parent.context,"",13f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;typeface=Typeface.DEFAULT_BOLD;setPadding(2,TvUi.dp(this,5),2,0) }
        val sub=TvUi.text(parent.context,"",11f,TvUi.muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(2,2,2,0) }
        label.tag="card_title";sub.tag="card_metadata"
        val info=TvUi.row(parent.context)
        val badges=TvUi.row(parent.context)
        info.addView(sub);info.addView(badges,LinearLayout.LayoutParams(-2,-2).apply { marginStart=TvUi.dp(card,6) })
        card.addView(label);card.addView(info)
        TvUi.focusOnTouch(card)
        card.setOnFocusChangeListener { _,focused ->
            frame.foreground=if(focused) FocusRingDrawable(parent.context) else null
            frame.animate().scaleX(if(focused) 1.025f else 1f).scaleY(if(focused) 1.025f else 1f).setDuration(120).start()
        }
        return Holder(card,frame,image,label,sub,badges,progress)
    }
    override fun onBindViewHolder(viewHolder: ViewHolder,item: Any?) {
        val h=viewHolder as Holder;h.job?.cancel();h.image.setImageDrawable(null);h.progress.visibility=android.view.View.GONE;h.badges.removeAllViews()
        when(item) {
            is VideoItem -> {
                h.card.tag="media_${item.id}"
                h.label.text=item.name
                h.subtitle.text=if(item.resumeTicks>0) MediaUi.remaining(item) else listOf(item.year,item.episodeLabel).filter(String::isNotBlank).joinToString("  ")
                if(item.resumeTicks<=0) MediaUi.badges(item).take(2).forEach { badge ->
                    h.badges.addView(TvUi.text(h.card.context,badge,10f).apply {
                        background=TvUi.box(0x900C1C26.toInt(),5f,0xFF35505C.toInt())
                        setPadding(TvUi.dp(this,5),TvUi.dp(this,1),TvUi.dp(this,5),TvUi.dp(this,1))
                    },LinearLayout.LayoutParams(-2,-2).apply { marginEnd=TvUi.dp(h.card,5) })
                }
                if(item.resumeTicks>0) { h.progress.visibility=android.view.View.VISIBLE;h.progress.progress=MediaUi.percent(item) }
                val s=app.sessions.load()
                if(s!=null) h.job=PosterLoader.load(scope,h.image,app.api.landscapeUrl(s,item),s)
            }
            is BrowserCommand -> { h.label.text=item.name;h.subtitle.text=Tr.text(UiText.PRESS_OK_332);h.image.setImageResource(tv.ember.client.R.drawable.ic_launcher) }
        }
    }
    override fun onUnbindViewHolder(viewHolder: ViewHolder) { (viewHolder as Holder).job?.cancel();viewHolder.image.setImageDrawable(null) }
}

class TvRowHeaderPresenter: RowHeaderPresenter() {
    override fun onBindViewHolder(viewHolder: Presenter.ViewHolder,item: Any?) {
        super.onBindViewHolder(viewHolder,item)
        fun style(view: android.view.View) {
            view.alpha=1f
            if(view is TextView) {
                view.textSize=16f;view.setTextColor(TvUi.text);view.typeface=Typeface.DEFAULT_BOLD
                view.minimumHeight=0;view.includeFontPadding=false
                view.text=TvUi.sectionTitle(view.text.toString())
            }
            if(view is ViewGroup) for(i in 0 until view.childCount) style(view.getChildAt(i))
        }
        style(viewHolder.view)
    }
    override fun onSelectLevelChanged(viewHolder: RowHeaderPresenter.ViewHolder) { viewHolder.view.alpha=1f }
}
