package tv.ember.client.emby

import org.json.JSONObject
import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText

/** Parameters documented by Emby ItemsService, rather than rules inferred from a loaded page. */
enum class BrowseSort(val value: String,val title: UiText) {
    NAME("SortName",UiText.NAME_327),ADDED("DateCreated",UiText.RECENTLY_ADDED_298),
    RELEASE("PremiereDate",UiText.RELEASE_DATE_328),RATING("CommunityRating",UiText.SCORE_329);
    val label get()=Tr.text(title)
}
data class BrowseQuery(val parent: String="",val search: String="",val types: String="",val favorite: Boolean=false,
                       val genre: String="",val year: String="",val played: Boolean?=null,
                       val sort: BrowseSort=BrowseSort.NAME,val descending: Boolean=false,val start: Int=0,val explicitSort: Boolean=false) {
    fun parameters(): Map<String,String> = buildMap {
        put("Limit","40");put("StartIndex",start.toString());put("Fields","MediaSources,Genres")
        // Let Emby choose its search ordering until the user explicitly selects a sort.
        if(search.isBlank() || explicitSort || sort!=BrowseSort.NAME || descending) {
            put("SortBy",sort.value);put("SortOrder",if(descending) "Descending" else "Ascending")
        }
        put("EnableTotalRecordCount","true");put("EnableUserData","true")
        if(parent.isNotBlank()) put("ParentId",parent)
        if(search.isNotBlank()) put("SearchTerm",normalizeSearch(search))
        if(types.isNotBlank()) put("IncludeItemTypes",types)
        else if(search.isNotBlank()) put("IncludeItemTypes","Movie,Series,Episode")
        if(favorite) put("Filters","IsFavorite")
        if(genre.isNotBlank()) put("Genres",genre)
        if(year.isNotBlank()) put("Years",year)
        played?.let { put("IsPlayed",it.toString()) }
        if(search.isNotBlank() || types.isNotBlank() || favorite || genre.isNotBlank() || year.isNotBlank() || played!=null) put("Recursive","true")
    }
    fun json()=JSONObject().put("parent",parent).put("search",search).put("types",types).put("favorite",favorite)
        .put("genre",genre).put("year",year).put("played",played ?: JSONObject.NULL).put("sort",sort.name).put("descending",descending).put("start",start).put("explicitSort",explicitSort).toString()
    companion object {
        fun parse(raw: String): BrowseQuery {
            val j=JSONObject(raw)
            return BrowseQuery(j.optString("parent"),j.optString("search"),j.optString("types"),j.optBoolean("favorite"),j.optString("genre"),j.optString("year"),
                if(j.isNull("played")) null else j.getBoolean("played"),runCatching { BrowseSort.valueOf(j.optString("sort")) }.getOrDefault(BrowseSort.NAME),j.optBoolean("descending"),j.optInt("start"),j.optBoolean("explicitSort"))
        }
    }
}
data class BrowseFacets(val genres: List<String> = emptyList(),val years: List<String> = emptyList(),val genresSupported: Boolean=true,val yearsSupported: Boolean=true)
