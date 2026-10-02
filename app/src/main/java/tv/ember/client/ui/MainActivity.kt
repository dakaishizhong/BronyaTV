package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.leanback.widget.*
import androidx.leanback.app.RowsSupportFragment
import androidx.lifecycle.lifecycleScope
import androidx.activity.addCallback
import kotlinx.coroutines.*
import tv.ember.client.data.*
import tv.ember.client.network.ApiException

data class BrowserCommand(val name: String, val action: () -> Unit)
private data class BrowserPage(val parent: String, val name: String, val search: String = "", val sort: String = "SortName", val types: String = "", val favorite: Boolean = false)

class MainActivity : TvActivity() {
    private lateinit var rows: RowsSupportFragment
    private lateinit var adapter: ArrayObjectAdapter
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var navLabel: TextView
    private lateinit var homeButton: Button
    private lateinit var hero: LinearLayout
    private lateinit var heroOverview: TextView
    private lateinit var heroBadges: LinearLayout
    private lateinit var heroActions: LinearLayout
    private lateinit var searchArea: LinearLayout
    private lateinit var backdrop: ImageView
    private var backdropJob: Job?=null
    private var heroItem: VideoItem?=null
    private var searchField: EditText?=null
    private var searchType=""
    private var loadedSession: Session? = null
    private var work: Job? = null
    private val history = ArrayDeque<BrowserPage>()
    private var page: BrowserPage? = null
    private var loadingLogin = false
    private var selectedItemId: String?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val surface=TvUi.backdrop(this)
        backdrop=surface.findViewWithTag("backdrop")
        val root=TvUi.column(this).apply { setPadding(TvUi.dp(this,20),TvUi.dp(this,12),TvUi.dp(this,18),0) }
        val nav=TvUi.row(this)
        navLabel=TvUi.text(this,Tr.text(UiText.YOUR_LIBRARY_291),12f,TvUi.muted)
        nav.addView(navLabel,LinearLayout.LayoutParams(0,-2,1f))
        nav.addView(TvUi.button(this,Tr.text(UiText.SORT_292)) { sortDialog() }.apply { textSize=12f;setPadding(TvUi.dp(this,8),0,TvUi.dp(this,8),0) },LinearLayout.LayoutParams(TvUi.dp(nav,58),TvUi.dp(nav,32)))
        nav.addView(TvUi.button(this,Tr.text(UiText.REFRESH_212)) { if(page==null) loadHome(true) else loadPage(page!!) }.apply { textSize=12f;setPadding(TvUi.dp(this,8),0,TvUi.dp(this,8),0) },LinearLayout.LayoutParams(TvUi.dp(nav,58),TvUi.dp(nav,32)).apply { marginStart=TvUi.dp(nav,6) })
        nav.addView(TextClock(this).apply { format24Hour="HH:mm";format12Hour="HH:mm";textSize=12f;setTextColor(TvUi.text);setPadding(TvUi.dp(this,12),0,0,0) })
        TvUi.add(root,nav,bottom=6)
        hero=TvUi.column(this)
        title=TvUi.text(this,Tr.text(UiText.YOUR_LIBRARY_291),36f).apply { typeface=android.graphics.Typeface.DEFAULT_BOLD;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        description=TvUi.text(this,Tr.text(UiText.MOVIES_AND_SERIES_293),13f,TvUi.muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        heroOverview=TvUi.text(this,"",14f).apply { maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END;maxWidth=TvUi.dp(this,420) }
        heroActions=TvUi.row(this);heroBadges=TvUi.row(this)
        TvUi.add(hero,title,bottom=3);TvUi.add(hero,description,bottom=5);TvUi.add(hero,heroBadges,bottom=5);TvUi.add(hero,heroOverview,width=TvUi.dp(hero,440),bottom=8);TvUi.add(hero,heroActions,bottom=0)
        TvUi.add(root,hero,height=TvUi.dp(root,180),bottom=6)
        searchArea=TvUi.column(this).apply { visibility=View.GONE }
        TvUi.add(root,searchArea,bottom=4)
        val frame=FrameLayout(this).apply { id=View.generateViewId() }
        root.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
        surface.addView(root,FrameLayout.LayoutParams(-1,-1))
        val shell=TvUi.shell(this,Tr.text(UiText.HOME_267),::navigate,surface)
        homeButton=shell.findViewWithTag(Tr.text(UiText.NAV_HOME_294))
        setContentView(shell)
        rows = RowsSupportFragment()
        supportFragmentManager.beginTransaction().replace(frame.id, rows).commitNow()
        adapter = ArrayObjectAdapter(ListRowPresenter(FocusHighlight.ZOOM_FACTOR_NONE).apply {
            shadowEnabled=false;selectEffectEnabled=false;headerPresenter=TvRowHeaderPresenter()
            rowHeight=TvUi.dp(root,112);expandedRowHeight=rowHeight
        })
        rows.adapter = adapter
        rows.enableRowScaling(false)
        frame.post {
            rows.setAlignment(TvUi.dp(root,32))
            rows.verticalGridView?.setVerticalSpacing(TvUi.dp(root,4))
        }
        rows.setOnItemViewClickedListener { _, item, _, _ -> when(item) {
            is VideoItem -> if(item.isFolder) openPage(BrowserPage(item.id, item.name)) else startActivity(Intent(this, DetailActivity::class.java).putExtra("item_id", item.id))
            is BrowserCommand -> item.action()
        } }
        rows.setOnItemViewSelectedListener { _, item, _, _ -> when(item) {
            is VideoItem -> { selectedItemId=item.id;if(page==null && !item.isFolder) updateHero(item) }
            is BrowserCommand -> Unit
        } }
        homeButton.requestFocus()
        onBackPressedDispatcher.addCallback(this) {
            if(page != null) {
                val prev = if(history.isEmpty()) BrowserPage("", Tr.text(UiText.HOME_267)) else history.removeLast()
                if(prev.name == Tr.text(UiText.HOME_267) && prev.parent.isBlank()) loadHome() else loadPage(prev)
            } else if(!homeButton.hasFocus()) homeButton.requestFocus() else finish()
        }
    }
    override fun onResume() {
        super.onResume()
        val s = app.sessions.load()
        if(s == null) {
            loadedSession=null;adapter.clear()
            if(!loadingLogin) {
                loadingLogin=true
                startActivity(Intent(this,LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                finish()
            }
            return
        }
        loadingLogin = false
        if(s != loadedSession) { loadedSession = s; history.clear(); page = null; loadHome() }
        intent.getStringExtra("navigate")?.let { intent.removeExtra("navigate");navigate(it) }
    }
    private fun row(name: String, items: List<Any>) {
        if(items.isEmpty()) return
        val a = ArrayObjectAdapter(LandscapeCardPresenter(app,lifecycleScope,if(page?.name?.startsWith(Tr.text(UiText.SEARCH_295))==true) 196 else 166)).apply { addAll(0, items) }
        adapter.add(ListRow(HeaderItem(name), a))
    }
    private fun loadHome(preserveFocus: Boolean=false) {
        val s = app.sessions.load() ?: return
        val selected=if(preserveFocus) rows.selectedPosition else 0
        val selectedId=if(preserveFocus) selectedItemId else null
        val restoreRows=!preserveFocus || rows.view?.hasFocus()==true
        page = null; configureHeader(); navLabel.text = "BronyaTV  /  ${s.userName}"; title.text = Tr.text(UiText.CONNECTING_TO_SERVER_296); description.text = ""
        work?.cancel()
        work = lifecycleScope.launch {
            try {
                val (v,r,l)=coroutineScope {
                    val views = async { app.api.views(s) }
                    val resume = async { optional { app.api.resume(s) } }
                    val latest = async { optional { app.api.latest(s) } }
                    Triple(views.await(),resume.await(),latest.await())
                }
                adapter.clear(); row(Tr.text(UiText.CONTINUE_WATCHING_297), r); row(Tr.text(UiText.RECENTLY_ADDED_298), l); row(Tr.text(UiText.SERVER_LIBRARIES_299), v)
                if(adapter.size() == 0) row(Tr.text(UiText.NO_CONTENT_YET_300), listOf(BrowserCommand(Tr.text(UiText.REFRESH_SERVER_301)) { loadHome() }))
                val featured=r.firstOrNull { !it.isFolder } ?: l.firstOrNull { !it.isFolder }
                if(featured!=null) updateHero(featured) else { title.text=Tr.text(UiText.WELCOME_BACK_302 ,(s.userName));description.text=Tr.text(UiText.CONTINUE_YOUR_FAVORITE_MOVIES_AND_SERIES_303) }
                var restored=false
                if(selectedId!=null) for(rowIndex in 0 until adapter.size()) {
                    val row=adapter.get(rowIndex) as? ListRow ?: continue
                    for(column in 0 until row.adapter.size()) if((row.adapter.get(column) as? VideoItem)?.id==selectedId) {
                        rows.setSelectedPosition(rowIndex,false,ListRowPresenter.SelectItemViewHolderTask(column));restored=true;break
                    }
                    if(restored) break
                }
                if(!restored) rows.setSelectedPosition(selected.coerceIn(0,(adapter.size()-1).coerceAtLeast(0)))
                if(restoreRows) rows.view?.requestFocus()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { showError(e) }
        }
    }
    private suspend fun optional(block: suspend () -> List<VideoItem>): List<VideoItem> = try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { android.util.Log.w("BronyaTVBrowse", "Optional server row unavailable", e); emptyList() }
    private fun openPage(next: BrowserPage) { history.addLast(page ?: BrowserPage("", Tr.text(UiText.HOME_267))); page = next; loadPage(next) }
    private fun loadPage(next: BrowserPage) {
        val s = app.sessions.load() ?: return
        page = next; configureHeader(); work?.cancel(); title.text = Tr.text(UiText.LOADING_304 ,(next.name)); description.text = ""; navLabel.text = "BronyaTV  /  ${next.name}"
        work = lifecycleScope.launch {
            try {
                val data = app.api.items(s, next.parent, search = next.search,sort=next.sort,types=next.types,favorite=next.favorite)
                adapter.clear(); appendPage(next, data, 0)
                title.text = next.name; description.text = Tr.text(UiText.ITEMS_SELECT_A_TITLE_FOR_DETAILS_305 ,(data.total))
                rows.view?.requestFocus()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { showError(e) }
        }
    }
    private fun appendPage(next: BrowserPage, data: ItemPage, start: Int) {
        data.items.chunked(if(next.name.startsWith(Tr.text(UiText.SEARCH_295))) 4 else 10).forEachIndexed { index, list -> row(if(index == 0) next.name else Tr.text(UiText.KEEP_BROWSING_306), list) }
        val end = start + data.items.size
        if(end < data.total && data.items.isNotEmpty()) row(Tr.text(UiText.MORE_CONTENT_307), listOf(BrowserCommand(Tr.text(UiText.LOAD_MORE_308 ,(end),(data.total))) {
            work?.cancel(); work = lifecycleScope.launch {
                try {
                    val more = app.api.items(app.sessions.load() ?: return@launch, next.parent, end, next.search,next.sort,next.types,next.favorite)
                    adapter.removeItems(adapter.size() - 1, 1); appendPage(next, more, end)
                } catch(e: CancellationException) { throw e } catch(e: Exception) { message(e.message ?: Tr.text(UiText.FAILED_TO_LOAD_247)) }
            }
        }))
        if(data.items.isEmpty() && start == 0) row(Tr.text(UiText.NO_RESULTS_309), listOf(BrowserCommand(Tr.text(UiText.BACK_TO_HOME_310)) { history.clear(); loadHome() }))
    }
    private fun showError(e: Exception) {
        title.text = Tr.text(UiText.UNABLE_TO_LOAD_311); description.text = e.message ?: Tr.text(UiText.CHECK_YOUR_NETWORK_CONNECTION_312)
        adapter.clear()
        row(Tr.text(UiText.CONNECTION_OPTIONS_313), listOf(BrowserCommand(Tr.text(UiText.RETRY_248)) { if(page == null) loadHome() else loadPage(page!!) }, BrowserCommand(Tr.text(UiText.SIGN_IN_AGAIN_314)) {
            app.sessions.clear(); loadedSession = null; startActivity(Intent(this, LoginActivity::class.java))
        }))
        if(e is ApiException && e.status == 401) description.text = Tr.text(UiText.SESSION_EXPIRED_PLEASE_SIGN_IN_AGAIN_315)
        rows.view?.requestFocus()
    }
    private fun navigate(name: String) {
        if(name==Tr.text(UiText.SETTINGS_268)) { startActivity(Intent(this,SettingsActivity::class.java));return }
        listOf(Tr.text(UiText.HOME_267),Tr.text(UiText.MOVIES_316),Tr.text(UiText.SERIES_317),Tr.text(UiText.FAVORITES_318),Tr.text(UiText.SEARCH_295)).forEach { window.decorView.findViewWithTag<View>("nav_${it}")?.isSelected=it==name }
        when(name) {
            Tr.text(UiText.HOME_267) -> { history.clear();loadHome() }
            Tr.text(UiText.MOVIES_316) -> openPage(BrowserPage("",Tr.text(UiText.MOVIES_316),types="Movie"))
            Tr.text(UiText.SERIES_317) -> openPage(BrowserPage("",Tr.text(UiText.SERIES_317),types="Series"))
            Tr.text(UiText.FAVORITES_318) -> openPage(BrowserPage("",Tr.text(UiText.FAVORITES_318),favorite=true))
            Tr.text(UiText.SEARCH_295) -> { work?.cancel();page=BrowserPage("",Tr.text(UiText.SEARCH_295));adapter.clear();configureHeader();title.text=Tr.text(UiText.SEARCH_295);description.text=Tr.text(UiText.FIND_YOUR_MOVIES_AND_SERIES_BY_319);searchField?.requestFocus() }
        }
    }
    private fun configureHeader() {
        val searching=page?.name?.startsWith(Tr.text(UiText.SEARCH_295))==true
        hero.layoutParams=hero.layoutParams.apply { height=TvUi.dp(hero,if(page==null) 180 else 64) }
        title.textSize=if(page==null) 36f else 28f
        heroOverview.visibility=if(page==null) View.VISIBLE else View.GONE
        heroBadges.visibility=if(page==null) View.VISIBLE else View.GONE
        heroActions.visibility=if(page==null) View.VISIBLE else View.GONE
        searchArea.visibility=if(searching) View.VISIBLE else View.GONE
        if(searching) renderSearch()
        if(page==null) listOf(Tr.text(UiText.HOME_267),Tr.text(UiText.MOVIES_316),Tr.text(UiText.SERIES_317),Tr.text(UiText.FAVORITES_318),Tr.text(UiText.SEARCH_295)).forEach { window.decorView.findViewWithTag<View>("nav_${it}")?.isSelected=it==Tr.text(UiText.HOME_267) }
    }
    private fun renderSearch() {
        searchArea.removeAllViews()
        val query=page?.search.orEmpty()
        val row=TvUi.row(this)
        val field=TvUi.input(this,Tr.text(UiText.SEARCH_MOVIES_OR_SERIES_320)).apply { setText(query);imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
        searchField=field
        fun submit() {
            val q=field.text.toString().trim()
            if(q.isBlank()) return
            (getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(field.windowToken,0)
            val prefs=getSharedPreferences("search_history",0)
            val old=prefs.getString("queries","").orEmpty().split("\n").filter { it.isNotBlank() && it!=q }
            prefs.edit().putString("queries",(listOf(q)+old).take(6).joinToString("\n")).apply()
            loadPage(BrowserPage("",Tr.text(UiText.SEARCH_RESULTS_321),q,types=searchType))
        }
        field.setOnEditorActionListener { _,action,event ->
            if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH || event?.keyCode==android.view.KeyEvent.KEYCODE_ENTER) { submit();true } else false
        }
        row.addView(field,LinearLayout.LayoutParams(0,TvUi.dp(row,44),1f))
        row.addView(TvUi.button(this,Tr.text(UiText.SEARCH_295),true) { submit() },LinearLayout.LayoutParams(TvUi.dp(row,82),TvUi.dp(row,44)).apply { marginStart=TvUi.dp(row,8) })
        TvUi.add(searchArea,row,bottom=6)
        val filters=TvUi.row(this)
        listOf(Tr.text(UiText.ALL_322) to "",Tr.text(UiText.MOVIES_316) to "Movie",Tr.text(UiText.SERIES_317) to "Series,Episode").forEach { (label,type) ->
            filters.addView(TvUi.button(this,label) { searchType=type;if(field.text.isNotBlank()) submit() else renderSearch() }.apply { isSelected=searchType==type;textSize=13f;setPadding(TvUi.dp(this,8),0,TvUi.dp(this,8),0) },LinearLayout.LayoutParams(TvUi.dp(filters,86),TvUi.dp(filters,34)).apply { marginEnd=TvUi.dp(filters,7) })
        }
        TvUi.add(searchArea,filters,bottom=6)
        val recent=TvUi.row(this)
        getSharedPreferences("search_history",0).getString("queries","").orEmpty().split("\n").filter(String::isNotBlank).take(4).forEach { q ->
            recent.addView(TvUi.button(this,q) { field.setText(q);submit() },LinearLayout.LayoutParams(-2,TvUi.dp(recent,32)).apply { marginEnd=TvUi.dp(recent,8) })
        }
        if(recent.childCount>0) TvUi.add(searchArea,recent,bottom=4)
    }
    private fun updateHero(video: VideoItem) {
        title.text=video.name;description.text=MediaUi.metadata(video);heroOverview.text=video.overview
        heroBadges.removeAllViews();heroBadges.addView(MediaUi.badgeRow(this,video))
        if(heroItem?.id==video.id && heroActions.childCount>0) return
        heroItem=video;MediaUi.backdropItem=video
        heroActions.removeAllViews()
        heroActions.addView(TvUi.button(this,if(video.resumeTicks>0) Tr.text(UiText.RESUME_323 ,(MediaUi.remaining(video))) else Tr.text(UiText.PLAY_252),true) {
            startActivity(Intent(this,DetailActivity::class.java).putExtra("item_id",video.id).putExtra("auto_play",true))
        },LinearLayout.LayoutParams(-2,TvUi.dp(hero,42)))
        heroActions.addView(TvUi.button(this,Tr.text(UiText.DETAILS_324)) { startActivity(Intent(this,DetailActivity::class.java).putExtra("item_id",video.id)) },LinearLayout.LayoutParams(-2,TvUi.dp(hero,42)).apply { marginStart=TvUi.dp(hero,10) })
        val s=app.sessions.load() ?: return
        backdropJob?.cancel();backdropJob=PosterLoader.load(lifecycleScope,backdrop,app.api.landscapeUrl(s,video,true),s,true)
    }
    private fun sortDialog() {
        val current=page ?: run { message(Tr.text(UiText.OPEN_A_LIBRARY_OR_SEARCH_RESULTS_325));return }
        val values=listOf("SortName","DateCreated","PremiereDate","CommunityRating")
        TvUi.dialog(this).setTitle(Tr.text(UiText.SORT_CONTENT_326)).setSingleChoiceItems(
            arrayOf(Tr.text(UiText.NAME_327),Tr.text(UiText.RECENTLY_ADDED_298),Tr.text(UiText.RELEASE_DATE_328),Tr.text(UiText.SCORE_329)),values.indexOf(current.sort)) { dialog,index ->
            dialog.dismiss();loadPage(current.copy(sort=values[index]))
        }.setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
    }
}
