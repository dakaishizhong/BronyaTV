package tv.ember.client.ui

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
private data class BrowserPage(val parent: String, val name: String, val search: String = "", val sort: String = "SortName")

class MainActivity : TvActivity() {
    private lateinit var rows: RowsSupportFragment
    private lateinit var adapter: ArrayObjectAdapter
    private lateinit var title: TextView
    private lateinit var description: TextView
    private lateinit var navLabel: TextView
    private lateinit var homeButton: Button
    private var loadedSession: Session? = null
    private var work: Job? = null
    private val history = ArrayDeque<BrowserPage>()
    private var page: BrowserPage? = null
    private var loadingLogin = false
    private var lastHomeLoaded=0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = paddedColumn()
        root.setPadding(TvUi.dp(root, 36), TvUi.dp(root, 16), TvUi.dp(root, 36), 0)
        val nav = TvUi.row(this)
        navLabel = TvUi.text(this, "BronyaTV", 22f, TvUi.accent).apply { letterSpacing = .1f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        nav.addView(navLabel, LinearLayout.LayoutParams(0, -2, 1f))
        homeButton = TvUi.button(this, "首页") { history.clear(); page = null; loadHome() }
        listOf(homeButton, TvUi.button(this, "搜索") { searchDialog() },TvUi.button(this,"排序") { sortDialog() }, TvUi.button(this, "设置") { startActivity(Intent(this, SettingsActivity::class.java)) },
            TvUi.button(this, "刷新") { if(page == null) loadHome() else loadPage(page!!) }).forEach {
            nav.addView(it, LinearLayout.LayoutParams(-2, TvUi.dp(nav, 46)).apply { marginStart = TvUi.dp(nav, 8) })
        }
        TvUi.add(root, nav)
        title = TvUi.text(this, "你的下一场精彩", 30f).apply { maxLines = 1 }
        description = TvUi.text(this, "选择服务器内容，直接开始观看。", 16f, TvUi.muted).apply { maxLines = 2 }
        TvUi.add(root, title, bottom = 3); TvUi.add(root, description, height = TvUi.dp(root, 48), bottom = 2)
        val frame = FrameLayout(this).apply { id = View.generateViewId() }
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f)); setContentView(root)
        rows = RowsSupportFragment()
        supportFragmentManager.beginTransaction().replace(frame.id, rows).commitNow()
        adapter = ArrayObjectAdapter(ListRowPresenter().apply { shadowEnabled = false; selectEffectEnabled = false })
        rows.adapter = adapter
        rows.setOnItemViewClickedListener { _, item, _, _ -> when(item) {
            is VideoItem -> if(item.isFolder) openPage(BrowserPage(item.id, item.name)) else startActivity(Intent(this, DetailActivity::class.java).putExtra("item_id", item.id))
            is BrowserCommand -> item.action()
        } }
        rows.setOnItemViewSelectedListener { _, item, _, _ -> when(item) {
            is VideoItem -> { title.text = item.name; description.text = (item.subtitle + "\n" + item.overview).trim() }
            is BrowserCommand -> { title.text = item.name; description.text = "使用遥控器确定键打开" }
        } }
        homeButton.requestFocus()
        onBackPressedDispatcher.addCallback(this) {
            if(page != null) {
                val prev = if(history.isEmpty()) BrowserPage("", "首页") else history.removeLast()
                if(prev.name == "首页" && prev.parent.isBlank()) loadHome() else loadPage(prev)
            } else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
        }
    }
    override fun onResume() {
        super.onResume()
        val s = app.sessions.load()
        if(s == null) {
            loadedSession=null;adapter.clear()
            title.text="连接你的媒体库";description.text="登录 Emby，继续观看电影与剧集。";navLabel.text="BronyaTV"
            row("开始观看",listOf(BrowserCommand("连接服务器") { startActivity(Intent(this,LoginActivity::class.java)) }))
            if(!loadingLogin) { loadingLogin = true; startActivity(Intent(this, LoginActivity::class.java)) }
            return
        }
        loadingLogin = false
        if(s != loadedSession) { loadedSession = s; history.clear(); page = null; loadHome() }
        else if(page==null && android.os.SystemClock.elapsedRealtime()-lastHomeLoaded>15_000 && work?.isActive!=true) loadHome(true)
    }
    private fun row(name: String, items: List<Any>) {
        if(items.isEmpty()) return
        val a = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, items) }
        adapter.add(ListRow(HeaderItem(name), a))
    }
    private fun loadHome(preserveFocus: Boolean=false) {
        val s = app.sessions.load() ?: return
        val selected=if(preserveFocus) rows.selectedPosition else 0
        page = null; navLabel.text = "BronyaTV  /  ${s.userName}"; title.text = "正在连接服务器…"; description.text = ""
        work?.cancel()
        work = lifecycleScope.launch {
            try {
                val views = async { app.api.views(s) }
                val resume = async { optional { app.api.resume(s) } }
                val latest = async { optional { app.api.latest(s) } }
                val v = views.await(); val r = resume.await(); val l = latest.await()
                adapter.clear(); row("继续观看", r); row("最近添加", l); row("服务器分类", v)
                if(adapter.size() == 0) row("暂无内容", listOf(BrowserCommand("刷新服务器") { loadHome() }))
                lastHomeLoaded=android.os.SystemClock.elapsedRealtime()
                title.text = "欢迎回来，${s.userName}"; description.text = "接着看喜欢的电影与剧集"
                rows.setSelectedPosition(selected.coerceIn(0,(adapter.size()-1).coerceAtLeast(0)))
                rows.view?.requestFocus()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { showError(e) }
        }
    }
    private suspend fun optional(block: suspend () -> List<VideoItem>): List<VideoItem> = try { block() } catch(e: CancellationException) { throw e } catch(e: Exception) { android.util.Log.w("BronyaTVBrowse", "Optional server row unavailable", e); emptyList() }
    private fun openPage(next: BrowserPage) { history.addLast(page ?: BrowserPage("", "首页")); page = next; loadPage(next) }
    private fun loadPage(next: BrowserPage) {
        val s = app.sessions.load() ?: return
        page = next; work?.cancel(); title.text = "正在加载 ${next.name}…"; description.text = ""; navLabel.text = "BronyaTV  /  ${next.name}"
        work = lifecycleScope.launch {
            try {
                val data = app.api.items(s, next.parent, search = next.search,sort=next.sort)
                adapter.clear(); appendPage(next, data, 0)
                title.text = next.name; description.text = "共 ${data.total} 项 · 选择影片查看详情或打开分类"
                rows.view?.requestFocus()
            } catch(e: CancellationException) { throw e } catch(e: Exception) { showError(e) }
        }
    }
    private fun appendPage(next: BrowserPage, data: ItemPage, start: Int) {
        data.items.chunked(10).forEachIndexed { index, list -> row(if(index == 0) next.name else "继续浏览", list) }
        val end = start + data.items.size
        if(end < data.total && data.items.isNotEmpty()) row("更多内容", listOf(BrowserCommand("加载更多（$end / ${data.total}）") {
            work?.cancel(); work = lifecycleScope.launch {
                try {
                    val more = app.api.items(app.sessions.load() ?: return@launch, next.parent, end, next.search,next.sort)
                    adapter.removeItems(adapter.size() - 1, 1); appendPage(next, more, end)
                } catch(e: CancellationException) { throw e } catch(e: Exception) { message(e.message ?: "加载失败") }
            }
        }))
        if(data.items.isEmpty() && start == 0) row("没有结果", listOf(BrowserCommand("返回首页") { history.clear(); loadHome() }))
    }
    private fun showError(e: Exception) {
        title.text = "暂时无法加载"; description.text = e.message ?: "请检查网络连接"
        adapter.clear()
        row("连接选项", listOf(BrowserCommand("重试") { if(page == null) loadHome() else loadPage(page!!) }, BrowserCommand("重新登录") {
            app.sessions.clear(); loadedSession = null; startActivity(Intent(this, LoginActivity::class.java))
        }))
        if(e is ApiException && e.status == 401) description.text = "登录已失效，请选择重新登录"
        rows.view?.requestFocus()
    }
    private fun searchDialog() {
        val field = TvUi.input(this,"影片或剧集名称").apply { setText(page?.search.orEmpty()) }
        AlertDialog.Builder(this).setTitle("搜索服务器").setView(field).setPositiveButton("搜索") { _, _ ->
            val q = field.text.toString().trim(); if(q.isNotBlank()) openPage(BrowserPage("", "搜索：$q", q))
        }.setNegativeButton("取消", null).show()
    }
    private fun sortDialog() {
        val current=page ?: run { message("打开分类或搜索结果后可选择排序");return }
        val values=listOf("SortName","DateCreated","PremiereDate","CommunityRating")
        AlertDialog.Builder(this).setTitle("内容排序").setSingleChoiceItems(
            arrayOf("名称","最近添加","上映时间","评分"),values.indexOf(current.sort)) { dialog,index ->
            dialog.dismiss();loadPage(current.copy(sort=values[index]))
        }.setNegativeButton("取消",null).show()
    }
    inner class CardPresenter : Presenter() {
        inner class Holder(val card: ImageCardView) : ViewHolder(card) { var job: Job? = null }
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder = Holder(ImageCardView(parent.context).apply {
            isFocusable = true; isFocusableInTouchMode = true
            setMainImageDimensions(TvUi.dp(this, 132), TvUi.dp(this, 188))
            setMainImageScaleType(ImageView.ScaleType.CENTER_CROP)
            background=TvUi.box(TvUi.panel,18f);clipToOutline=true;setInfoAreaBackgroundColor(TvUi.panel)
            setOnFocusChangeListener { _, hasFocus ->
                background=TvUi.box(TvUi.panel,18f,if(hasFocus) TvUi.accent else 0)
                setInfoAreaBackgroundColor(if(hasFocus) TvUi.raised else TvUi.panel)
                animate().scaleX(if(hasFocus) 1.055f else 1f).scaleY(if(hasFocus) 1.055f else 1f).setDuration(130).start()
            }
        })
        override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
            val h = viewHolder as Holder; h.job?.cancel()
            when(item) {
                is VideoItem -> {
                    h.card.titleText = item.name; h.card.contentText = if(item.resumeTicks>0) "续看 · ${tv.ember.client.player.SeekPolicy.time(item.resumeTicks/10000)}" else item.subtitle.ifBlank { if(item.isFolder) "打开分类" else "查看详情" }
                    h.card.mainImageView!!.setBackgroundColor(TvUi.panel)
                    val s = app.sessions.load()
                    if(s != null) h.job = PosterLoader.load(lifecycleScope, h.card.mainImageView!!, app.api.imageUrl(s, item), s)
                }
                is BrowserCommand -> { h.card.titleText = item.name; h.card.contentText = "按确定键"; h.card.mainImageView!!.setImageResource(tv.ember.client.R.drawable.ic_launcher) }
            }
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) { (viewHolder as Holder).job?.cancel(); viewHolder.card.mainImage = null }
    }
}
