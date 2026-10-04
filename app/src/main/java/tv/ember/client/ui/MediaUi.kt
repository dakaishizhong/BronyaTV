package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.graphics.Typeface
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
    fun remaining(item: VideoItem): String {
        val minutes=((item.runtimeTicks-item.resumeTicks).coerceAtLeast(0)/600_000_000)
        return if(item.runtimeTicks<=0) Tr.text(UiText.CONTINUE_WATCHING_297) else if(minutes>=60) Tr.text(UiText.H_M_REMAINING_330 ,(minutes/60),(minutes%60)) else Tr.text(UiText.MIN_REMAINING_331 ,(minutes))
    }
    fun percent(item: VideoItem)=if(item.runtimeTicks<=0) 0 else ((item.resumeTicks.toDouble()/item.runtimeTicks)*100).toInt().coerceIn(0,100)
}
