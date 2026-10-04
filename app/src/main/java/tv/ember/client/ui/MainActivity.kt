package tv.ember.client.ui

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.addCallback
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.Text
import kotlinx.coroutines.*
import tv.ember.client.data.*
import tv.ember.client.emby.*
import tv.ember.client.i18n.*

private data class HomeRow(val key: String,val title: String,val items: List<VideoItem>,val library: VideoItem?=null)
private class BrowserFrame(private val originalName: String,var query: BrowseQuery?=null,val id: String=java.util.UUID.randomUUID().toString(),kind: String?=null) {
    val destination=kind ?: when {
        query==null -> "home"
        query!!.parent.isNotBlank() -> "library"
        originalName==Tr.text(UiText.SEARCH_295) -> "search"
        query!!.favorite -> "favorites"
        query!!.types=="Movie" -> "movies"
        query!!.types=="Series" -> "series"
        else -> "library"
    }
    val name get()=when(destination) {
        "home" -> Tr.text(UiText.HOME_267);"movies" -> Tr.text(UiText.MOVIES_316);"series" -> Tr.text(UiText.SERIES_317)
        "favorites" -> Tr.text(UiText.FAVORITES_318);"search" -> Tr.text(UiText.SEARCH_295);else -> originalName
    }
    var items by mutableStateOf<List<VideoItem>>(emptyList())
    var rows by mutableStateOf<List<HomeRow>>(emptyList())
    var total by mutableIntStateOf(0)
    var facets by mutableStateOf(BrowseFacets())
    var loading by mutableStateOf(false)
    var error by mutableStateOf("")
    var first=0;var offset=0;var focus="";var requestRevision=0
    var restoreFirst=0;var restoreOffset=0;var restoreFocus=""
    var resultRevision by mutableIntStateOf(0)
    var filtersOpen by mutableStateOf(false)
    val rowPositions=mutableMapOf<String,Pair<Int,Int>>()
}
class MainActivity: TvActivity() {
    private var frame by mutableStateOf(BrowserFrame(Tr.text(UiText.HOME_267)))
    private var hero by mutableStateOf<VideoItem?>(null)
    private var libraries by mutableStateOf<List<VideoItem>>(emptyList())
    private var restoreEpoch by mutableIntStateOf(0)
    private val history=ArrayDeque<BrowserFrame>()
    private var session: Session?=null
    private var work: Job?=null
    private var facetWork: Job?=null
    private val homeFocus=FocusRequester()
    private var railFocused=""
    private var restored=false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedInstanceState?.getString("query")?.let { raw ->
            frame=BrowserFrame(savedInstanceState.getString("name").orEmpty(),if(raw=="home") null else BrowseQuery.parse(raw),kind=savedInstanceState.getString("kind"))
            frame.filtersOpen=savedInstanceState.getBoolean("filters_open");frame.first=savedInstanceState.getInt("first");frame.offset=savedInstanceState.getInt("offset");frame.focus=savedInstanceState.getString("focus").orEmpty();readRows(frame,savedInstanceState.getString("rows"));restored=true
        }
        savedInstanceState?.getStringArrayList("history")?.forEach { raw ->
            val j=org.json.JSONObject(raw);history.addLast(BrowserFrame(j.getString("name"),if(j.getString("query")=="home") null else BrowseQuery.parse(j.getString("query")),kind=j.optString("kind").takeIf(String::isNotBlank)).apply {
                filtersOpen=j.optBoolean("filters_open");first=j.optInt("first");offset=j.optInt("offset");focus=j.optString("focus");readRows(this,j.optString("rows"))
            })
        }
        tvContent { Screen() }
        onBackPressedDispatcher.addCallback(this) {
            if(history.isNotEmpty()) { work?.cancel();facetWork?.cancel();frame=history.removeLast();restoreEpoch++;load(frame) }
            else if(frame.query!=null) { frame=BrowserFrame(Tr.text(UiText.HOME_267));restoreEpoch++;load(frame) }
            else if(railFocused!=Tr.text(UiText.HOME_267)) homeFocus.requestFocus() else finish()
        }
    }
    override fun onResume() {
        super.onResume()
        val current=app.sessions.load() ?: run { startActivity(Intent(this,LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();return }
        if(session!=current) {
            session=current
            if(!restored) { history.clear();frame=BrowserFrame(Tr.text(UiText.HOME_267)) }
            restored=false;load(frame)
        } else { restoreEpoch++;load(frame) }
        intent.getStringExtra("navigate")?.let { intent.removeExtra("navigate");navigate(it) }
    }
    private fun push(next: BrowserFrame) {
        history.addLast(frame)
        // Retain the complete navigation path, but only eight pages' response payloads.
        history.toList().dropLast(8).forEach { it.items=emptyList();it.rows=emptyList() }
        frame=next;restoreEpoch++;load(next)
    }
    private fun navigate(name: String) {
        if(name==Tr.text(UiText.SETTINGS_268)) { startActivity(Intent(this,SettingsActivity::class.java));return }
        if(name==frame.name && (frame.query?.parent ?: "").isBlank()) return
        when(name) {
            Tr.text(UiText.HOME_267) -> { history.clear();frame=BrowserFrame(name);restoreEpoch++;load(frame) }
            Tr.text(UiText.MOVIES_316) -> push(BrowserFrame(name,BrowseQuery(types="Movie")))
            Tr.text(UiText.SERIES_317) -> push(BrowserFrame(name,BrowseQuery(types="Series")))
            Tr.text(UiText.FAVORITES_318) -> push(BrowserFrame(name,BrowseQuery(favorite=true)))
            Tr.text(UiText.SEARCH_295) -> push(BrowserFrame(name,BrowseQuery()))
        }
    }
    private fun openLibrary(item: VideoItem) { push(BrowserFrame(item.name,BrowseQuery(parent=item.id))) }
    private fun open(item: VideoItem) {
        if(item.isFolder) push(BrowserFrame(item.name,BrowseQuery(parent=item.id)))
        else startActivity(Intent(this,DetailActivity::class.java).putExtra("item_id",item.id))
    }
    private fun filter(query: BrowseQuery) {
        work?.cancel();frame.query=query.copy(start=0);frame.first=0;frame.offset=0;frame.focus="";restoreEpoch++;load(frame,facets=false)
    }
    private fun load(target: BrowserFrame,facets: Boolean=true) {
        val s=session ?: return;val request=++target.requestRevision;work?.cancel();target.loading=true;target.error=""
        val query=target.query
        target.restoreFirst=target.first;target.restoreOffset=target.offset;target.restoreFocus=target.focus
        work=lifecycleScope.launch {
            try {
                if(query==null) {
                    fun display(v: List<VideoItem>,r: List<VideoItem>,sections: List<HomeRow>) {
                        libraries=v
                        val continuing=r.filterNot { app.progress.finished(s,it.id) }.map { app.progress.apply(s,it) }
                        target.rows=(listOf(HomeRow("resume",Tr.text(UiText.CONTINUE_WATCHING_297),continuing))+sections+
                            if(v.size>6) listOf(HomeRow("libraries",Tr.text(UiText.SERVER_LIBRARIES_299),v.drop(6))) else emptyList())
                            .filter { it.items.isNotEmpty() || it.library!=null }
                        val candidate=continuing.firstOrNull { !it.isFolder } ?: sections.flatMap { it.items }.firstOrNull { !it.isFolder }
                        hero=(continuing.firstOrNull { it.id==hero?.id } ?: candidate)?.let { app.progress.apply(s,it) }
                    }
                    suspend fun cachedSections(v: List<VideoItem>)=if(v.isEmpty())
                        listOf(HomeRow("latest",Tr.text(UiText.RECENTLY_ADDED_298),app.api.cachedLatest(s).orEmpty()))
                    else v.take(6).map { HomeRow("library:${it.id}",it.name,app.api.cachedLatest(s,it.id).orEmpty(),it) }
                    if(target.rows.isEmpty()) {
                        val v=app.api.cachedViews(s).orEmpty();display(v,app.api.cachedResume(s).orEmpty(),cachedSections(v))
                    }
                    coroutineScope {
                        val views=async { app.api.views(s) };val resume=async { optional { app.api.resume(s) } }
                        val v=views.await();val r=resume.await();display(v,r,cachedSections(v))
                        val sections=if(v.isEmpty()) listOf(HomeRow("latest",Tr.text(UiText.RECENTLY_ADDED_298),optional { app.api.latest(s) }))
                        else v.take(6).map { library -> async { HomeRow("library:${library.id}",library.name,optional { app.api.latest(s,library.id) }.map { app.progress.apply(s,it) },library) } }.awaitAll()
                        display(v,r,sections);target.resultRevision++
                    }
                } else if(!(target.destination=="search" && query.search.isBlank())) {
                    if(target.items.isEmpty()) app.api.cachedItems(s,query)?.let { target.items=it.items;target.total=it.total }
                    val result=app.api.browse(s,query);target.items=result.items.map { app.progress.apply(s,it) };target.total=result.total;target.resultRevision++
                    if(libraries.isEmpty()) libraries=app.api.views(s)
                }
            } catch(e: CancellationException) { throw e } catch(e: Exception) { target.error=e.message ?: Tr.text(UiText.FAILED_TO_LOAD_247) }
            finally { if(target.requestRevision==request) target.loading=false }
        }
        if(query!=null && facets) {
            facetWork?.cancel();facetWork=lifecycleScope.launch {
                try { target.facets=app.api.facets(s,query) }
                catch(e: CancellationException) { throw e } catch(_: Exception) { target.facets=BrowseFacets(genresSupported=false,yearsSupported=false) }
            }
        }
    }
    private suspend fun optional(block: suspend ()->List<VideoItem>): List<VideoItem> = try { block() } catch(e: CancellationException) { throw e } catch(_: Exception) { emptyList() }
    @Composable private fun Screen() {
        val s=session ?: app.sessions.load();val current=frame;val scale=LocalTvScale.current
        fun section(target: BrowserFrame): UiText? {
            val q=target.query ?: return UiText.HOME_267
            if(target.destination=="search") return UiText.SEARCH_295
            if(q.favorite) return UiText.FAVORITES_318
            if(q.types=="Series") return UiText.SERIES_317
            if(q.types=="Movie") return UiText.MOVIES_316
            return when(libraries.firstOrNull { it.id==q.parent }?.collectionType) { "tvshows" -> UiText.SERIES_317;"movies" -> UiText.MOVIES_316;else -> null }
        }
        val selected=Tr.text(section(current) ?: history.toList().asReversed().firstNotNullOfOrNull { section(it)?.takeUnless { value -> value==UiText.HOME_267 } } ?: UiText.MOVIES_316)
        TvShell(selected,::navigate,if(current.query==null) hero else MediaUi.backdropItem,homeFocus,{ railFocused=it }) {
            Column(Modifier.fillMaxSize().onFocusChanged { if(it.hasFocus) railFocused="" }.focusGroup().padding(horizontal=(14*scale).dp,vertical=(12*scale).dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) { TvAction("⋯","browse_menu") { showMenu() };Spacer(Modifier.width(12.dp));TvClock() }
                if(s!=null) {
                    key(current.id) { if(current.query==null) Home(current,s) else Browse(current,s) }
                }
                if(current.loading && current.rows.isEmpty() && current.items.isEmpty()) Text(Tr.text(UiText.CONNECTING_TO_SERVER_296),color=Cyan)
                if(current.error.isNotBlank()) {
                    Text(Tr.text(UiText.UNABLE_TO_LOAD_311),color=Paper);Text(current.error,color=Muted)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        TvAction(Tr.text(UiText.RETRY_248),"browse_retry") { load(current) }
                        TvAction(Tr.text(UiText.SIGN_IN_AGAIN_314)) { app.sessions.clear();startActivity(Intent(this@MainActivity,LoginActivity::class.java));finish() }
                    }
                }
            }
        }
        LaunchedEffect(Unit) { if(current.focus.isBlank()) homeFocus.requestFocus() }
    }
    @Composable private fun ColumnScope.Home(current: BrowserFrame,s: Session) {
        val scale=LocalTvScale.current;val rail=LocalRailFocus.current
        val focusMap=remember(current.id) { mutableMapOf<String,FocusRequester>() }
        val scroll=rememberLazyListState(current.first,current.offset)
        hero?.let { featured ->
            Column(Modifier.fillMaxWidth().height((205*scale).dp).padding(top=(10*scale).dp)) {
                Text(listOf(if(featured.resumeTicks>0) Tr.text(UiText.RESUME_STATUS,MediaUi.remaining(featured)) else "",MediaUi.badges(featured).joinToString("  ")).filter(String::isNotBlank).joinToString("  ·  "),color=Cyan,fontSize=(12*scale).sp)
                Text(featured.name,color=Paper,fontWeight=FontWeight.Bold,fontSize=(43*scale).sp,maxLines=1,overflow=TextOverflow.Ellipsis)
                Text(MediaUi.metadata(featured),color=Muted,fontSize=(14*scale).sp)
                Spacer(Modifier.height((6*scale).dp));Text(featured.overview,Modifier.widthIn(max=(580*scale).dp),color=Paper,fontSize=(14*scale).sp,maxLines=2,overflow=TextOverflow.Ellipsis)
                Spacer(Modifier.height((8*scale).dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    TvAction(Tr.text(if(featured.resumeTicks>0) UiText.RESUME_251 else UiText.PLAY_252),"hero_play",Modifier.width((145*scale).dp).height((37*scale).dp),primary=true) { startActivity(Intent(this@MainActivity,DetailActivity::class.java).putExtra("item_id",featured.id).putExtra("auto_play",true)) }
                    TvAction(Tr.text(UiText.DETAILS_324),"hero_details",Modifier.width((100*scale).dp).height((37*scale).dp)) { open(featured) }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("home_rows"),state=scroll,verticalArrangement=Arrangement.spacedBy((7*scale).dp)) {
            items(current.rows,key={ it.key }) { row ->
                if(row.library==null) Section(row.title) else {
                    val header="header:${row.library.id}";val request=remember(header) { FocusRequester() }
                    var focused by remember { mutableStateOf(false) }
                    DisposableEffect(header) { focusMap[header]=request;onDispose { if(focusMap[header]===request) focusMap.remove(header) } }
                    Row(Modifier.fillMaxWidth().testTag("home_library_${row.library.id}").focusRequester(request)
                        .onFocusChanged { focused=it.isFocused;if(it.isFocused) current.focus=header }
                        .focusProperties { down=focusMap["${row.key}:${row.items.firstOrNull()?.id}"] ?: FocusRequester.Default }
                        .background(if(focused) Cyan.copy(alpha=.1f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { openLibrary(row.library) },horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
                        Section(row.title);Text("›",color=if(focused) Cyan else Muted,fontSize=(22*scale).sp)
                    }
                }
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val width=(maxWidth-(8*scale*4).dp-6.dp)/5
                    val position=current.rowPositions[row.key] ?: (0 to 0)
                    val rowScroll=rememberLazyListState(position.first.coerceAtMost((row.items.size-1).coerceAtLeast(0)),position.second)
                    LaunchedEffect(row.key) { snapshotFlow { rowScroll.firstVisibleItemIndex to rowScroll.firstVisibleItemScrollOffset }.collect { current.rowPositions[row.key]=it } }
                    LazyRow(state=rowScroll,horizontalArrangement=Arrangement.spacedBy((8*scale).dp),contentPadding=PaddingValues(3.dp)) {
                        itemsIndexed(row.items,key={ _,item->item.id }) { i,item ->
                            val key="${row.key}:${item.id}";val request=remember(key) { FocusRequester() }
                            DisposableEffect(key) { focusMap[key]=request;onDispose { if(focusMap[key]===request) focusMap.remove(key) } }
                            MediaCard(app,s,item,Modifier.width(width),request,{ current.focus=key;railFocused="";if(!item.isFolder) MediaUi.backdropItem=item },if(i==0) rail else null) { if(row.key=="libraries") openLibrary(item) else open(item) }
                        }
                    }
                }
            }
        }
        LaunchedEffect(current.id) { snapshotFlow { scroll.firstVisibleItemIndex to scroll.firstVisibleItemScrollOffset }.collect { current.first=it.first;current.offset=it.second } }
        LaunchedEffect(current.id,restoreEpoch,current.resultRevision,current.rows.isNotEmpty()) {
            if(current.restoreFocus.isNotBlank() && current.rows.isNotEmpty()) { scroll.scrollToItem(current.restoreFirst.coerceAtMost(current.rows.lastIndex),current.restoreOffset);withFrameNanos {};withFrameNanos {};focusMap[current.restoreFocus]?.requestFocus() }
        }
    }
    @Composable private fun ColumnScope.Browse(current: BrowserFrame,s: Session) {
        val scale=LocalTvScale.current;val query=current.query ?: return;val rail=LocalRailFocus.current
        val grid=rememberLazyGridState(current.first,current.offset)
        val focusMap=remember(current.id) { mutableMapOf<String,FocusRequester>() }
        Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Text(current.name,color=Paper,fontWeight=FontWeight.Bold,fontSize=(29*scale).sp)
            TvAction(Tr.text(UiText.FILTER_SORT),"browse_filters",selected=current.filtersOpen) { current.filtersOpen=!current.filtersOpen }
        }
        if(current.destination=="search") {
            var term by rememberSaveable(current.id) { mutableStateOf(query.search) }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TvField(term,{ term=it },Tr.text(UiText.SEARCH_MOVIES_OR_SERIES_320),"search_query",Modifier.weight(1f),onSubmit={ search(current,term) })
                TvAction(Tr.text(UiText.SEARCH_295),"search_submit",primary=true) { search(current,term) }
            }
            Options("",listOf(Tr.text(UiText.ALL_322) to "",Tr.text(UiText.MOVIES_316) to "Movie",Tr.text(UiText.SERIES_317) to "Series,Episode"),query.types) { filter(query.copy(types=it)) }
            val recent=getSharedPreferences("search_history",0).getString("queries","").orEmpty().split("\n").filter(String::isNotBlank).take(6)
            if(recent.isNotEmpty()) Options("",recent.map { it to it },"") { term=it;search(current,it) }
        } else if(query.parent.isBlank() && current.destination in listOf("movies","series")) {
            val visible=libraries.filter { it.collectionType==if(current.destination=="series") "tvshows" else "movies" }
            if(visible.isNotEmpty()) Options("",listOf(Tr.text(UiText.ALL_322) to "")+visible.map { it.name to it.id },query.parent) { id ->
                if(id.isNotBlank()) push(BrowserFrame(visible.first { it.id==id }.name,BrowseQuery(parent=id)))
            }
        }
        if(current.filtersOpen) {
            Row(Modifier.fillMaxWidth().padding(vertical=5.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                if(current.facets.genres.isNotEmpty()) FilterPicker(Tr.text(UiText.GENRE),"genre",listOf(Tr.text(UiText.ALL_322) to "")+current.facets.genres.map { it to it },query.genre,Modifier.weight(1f)) { filter(query.copy(genre=it)) }
                if(current.facets.years.isNotEmpty()) FilterPicker(Tr.text(UiText.YEAR),"year",listOf(Tr.text(UiText.ALL_322) to "")+current.facets.years.map { it to it },query.year,Modifier.weight(1f)) { filter(query.copy(year=it)) }
                FilterPicker(Tr.text(UiText.WATCH_STATUS),"watch",listOf(Tr.text(UiText.ALL_322) to "",Tr.text(UiText.WATCHED) to "true",Tr.text(UiText.UNWATCHED) to "false"),query.played?.toString().orEmpty(),Modifier.weight(1f)) { filter(query.copy(played=it.takeIf(String::isNotBlank)?.toBoolean())) }
                FilterPicker(Tr.text(UiText.SORT_292),"sort",BrowseSort.entries.map { it.label to it.name },query.sort.name,Modifier.weight(1f)) { filter(query.copy(sort=BrowseSort.valueOf(it),descending=it!=BrowseSort.NAME.name)) }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TvAction(Tr.text(if(query.descending) UiText.DESCENDING else UiText.ASCENDING),"sort_order") { filter(query.copy(descending=!query.descending)) }
                TvAction(Tr.text(UiText.RESET_FILTERS),"filter_reset") { filter(query.copy(genre="",year="",played=null,sort=BrowseSort.NAME,descending=false)) }
            }
            if(!current.facets.genresSupported || !current.facets.yearsSupported) Text(Tr.text(UiText.SERVER_FILTER_UNAVAILABLE),color=Muted,fontSize=(10*scale).sp)
        } else if(query.genre.isNotBlank() || query.year.isNotBlank() || query.played!=null) {
            Text(listOf(query.genre,query.year,query.played?.let { Tr.text(if(it) UiText.WATCHED else UiText.UNWATCHED) }.orEmpty()).filter(String::isNotBlank).joinToString(" · "),color=Cyan,fontSize=(12*scale).sp)
        }
        LazyVerticalGrid(GridCells.Fixed(5),Modifier.weight(1f).fillMaxWidth().testTag("browse_grid"),state=grid,contentPadding=PaddingValues(3.dp),
            horizontalArrangement=Arrangement.spacedBy((8*scale).dp),verticalArrangement=Arrangement.spacedBy((10*scale).dp)) {
            itemsIndexed(current.items,key={ _,item->item.id }) { index,item ->
                val request=remember(item.id) { FocusRequester() }
                DisposableEffect(item.id) { focusMap[item.id]=request;onDispose { if(focusMap[item.id]===request) focusMap.remove(item.id) } }
                MediaCard(app,s,item,requester=request,onFocused={ current.focus=item.id;railFocused="" },onLeft=if(index%5==0) rail else null) { open(item) }
            }
        }
        if(current.items.isEmpty() && !current.loading && current.error.isBlank()) Text(Tr.text(UiText.NO_RESULTS_309),color=Muted)
        if(current.total>40 || query.start>0) Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            TvAction(Tr.text(UiText.PREVIOUS_PAGE),"page_previous",enabled=query.start>0 && !current.loading) { page(current,query.copy(start=(query.start-40).coerceAtLeast(0))) }
            Text(Tr.text(UiText.PAGE_POSITION,query.start/40+1,current.total),color=Muted)
            TvAction(Tr.text(UiText.NEXT_PAGE),"page_next",enabled=query.start+current.items.size<current.total && !current.loading) { page(current,query.copy(start=query.start+40)) }
        }
        LaunchedEffect(current.id) { snapshotFlow { grid.firstVisibleItemIndex to grid.firstVisibleItemScrollOffset }.collect { current.first=it.first;current.offset=it.second } }
        LaunchedEffect(current.id,restoreEpoch,current.resultRevision,current.items.isNotEmpty()) {
            if(current.items.isNotEmpty()) {
                grid.scrollToItem(current.restoreFirst.coerceAtMost(current.items.lastIndex),current.restoreOffset);withFrameNanos {};withFrameNanos {}
                if(current.restoreFocus.isNotBlank()) focusMap[current.restoreFocus]?.requestFocus()
            }
        }
    }
    @Composable private fun FilterPicker(label: String,tag: String,values: List<Pair<String,String>>,selected: String,modifier: Modifier,onChange: (String)->Unit) {
        TvAction(label+" · "+(values.firstOrNull { it.second==selected }?.first ?: Tr.text(UiText.ALL_322))+" ▾","picker_$tag",modifier) {
            TvUi.dialog(this).setTitle(label).setSingleChoiceItems(values.map { it.first }.toTypedArray(),values.indexOfFirst { it.second==selected }) { dialog,index ->
                dialog.dismiss();onChange(values[index].second)
            }.show()
        }
    }
    @Composable private fun Options(label: String,values: List<Pair<String,String>>,selected: String,onChange: (String)->Unit) {
        val scale=LocalTvScale.current
        Row(verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
            if(label.isNotBlank()) Text(label,Modifier.width((75*scale).dp),color=Muted,fontSize=(12*scale).sp)
            LazyRow(horizontalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(vertical=3.dp,horizontal=2.dp)) {
                items(values,key={ it.second }) { (name,value) -> TvAction(name,"filter_${label}_$value",selected=selected==value) { onChange(value) } }
            }
        }
    }
    private fun page(current: BrowserFrame,query: BrowseQuery) { current.query=query;current.first=0;current.offset=0;current.focus="";restoreEpoch++;load(current,facets=false) }
    private fun search(current: BrowserFrame,raw: String) {
        val term=raw.trim();if(term.isBlank()) return
        val prefs=getSharedPreferences("search_history",0);val old=prefs.getString("queries","").orEmpty().split("\n").filter { it.isNotBlank() && it!=term }
        prefs.edit().putString("queries",(listOf(term)+old).take(6).joinToString("\n")).apply()
        filter(current.query!!.copy(search=term))
    }
    private fun showMenu() {
        TvUi.dialog(this).setTitle(Tr.text(UiText.BROWSE_MENU)).setItems(arrayOf(Tr.text(UiText.REFRESH_212),Tr.text(UiText.SORT_292))) { _,which ->
            if(which==0) load(frame) else if(frame.query!=null) TvUi.dialog(this).setTitle(Tr.text(UiText.SORT_CONTENT_326)).setItems(BrowseSort.entries.map { it.label }.toTypedArray()) { _,i -> filter(frame.query!!.copy(sort=BrowseSort.entries[i],descending=i!=0)) }.show()
            else message(Tr.text(UiText.OPEN_A_LIBRARY_OR_SEARCH_RESULTS_325))
        }.show()
    }
    override fun onKeyUp(keyCode: Int,event: KeyEvent): Boolean { if(keyCode==KeyEvent.KEYCODE_MENU) { showMenu();return true };return super.onKeyUp(keyCode,event) }
    private fun rowJson(target: BrowserFrame)=org.json.JSONObject().apply { target.rowPositions.forEach { (key,value)-> put(key,org.json.JSONArray().put(value.first).put(value.second)) } }.toString()
    private fun readRows(target: BrowserFrame,raw: String?) { if(!raw.isNullOrBlank()) runCatching { val j=org.json.JSONObject(raw);j.keys().forEach { key -> j.optJSONArray(key)?.let { target.rowPositions[key]=it.optInt(0) to it.optInt(1) } } } }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("filters_open",frame.filtersOpen);outState.putString("rows",rowJson(frame))
        outState.putString("kind",frame.destination);outState.putString("name",frame.name);outState.putString("query",frame.query?.json() ?: "home");outState.putInt("first",frame.first);outState.putInt("offset",frame.offset);outState.putString("focus",frame.focus)
        outState.putStringArrayList("history",ArrayList(history.map { org.json.JSONObject().put("name",it.name).put("kind",it.destination).put("query",it.query?.json() ?: "home").put("filters_open",it.filtersOpen).put("first",it.first).put("offset",it.offset).put("focus",it.focus).put("rows",rowJson(it)).toString() }))
        super.onSaveInstanceState(outState)
    }
}
