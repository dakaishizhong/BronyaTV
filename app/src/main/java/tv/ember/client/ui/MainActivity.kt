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
    private var selectedItemId: String?=null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = paddedColumn()
        root.setPadding(TvUi.dp(root, 36), TvUi.dp(root, 16), TvUi.dp(root, 36), 0)
        val nav = TvUi.row(this)
        navLabel = TvUi.text(this, "BronyaTV", 22f, TvUi.text).apply { letterSpacing = .02f;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END }
        nav.addView(navLabel, LinearLayout.LayoutParams(0, -2, 1f))
        homeButton = TvUi.button(this, "首页") { history.clear(); page = null; loadHome() }
        listOf(homeButton, TvUi.button(this, "搜索") { searchDialog() },TvUi.button(this,"排序") { sortDialog() }, TvUi.button(this, "设置") { startActivity(Intent(this, SettingsActivity::class.java)) },
            TvUi.button(this, "刷新") { if(page == null) loadHome(true) else loadPage(page!!) }).forEach {
            nav.addView(it, LinearLayout.LayoutParams(-2, TvUi.dp(nav, 42)).apply { marginStart = TvUi.dp(nav, 8) })
        }
        TvUi.add(root, nav)
        title = TvUi.text(this, "你的媒体库", 28f).apply { maxLines = 1 }
        description = TvUi.text(this, "电影与剧集", 14f, TvUi.muted).apply { maxLines = 1;ellipsize=android.text.TextUtils.TruncateAt.END }
        TvUi.add(root, title, bottom = 3); TvUi.add(root, description, height = TvUi.dp(root, 28), bottom = 2)
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
            is VideoItem -> { selectedItemId=item.id;title.text = item.name; description.text = item.subtitle.ifBlank { item.overview } }
            is BrowserCommand -> { title.text = item.name; description.text = "使用遥控器确定键打开" }
        } }
        homeButton.requestFocus()
        onBackPressedDispatcher.addCallback(this) {
            if(page != null) {
                val prev = if(history.isEmpty()) BrowserPage("", "首页") else history.removeLast()
                if(prev.name == "首页" && prev.parent.isBlank()) loadHome() else loadPage(prev)
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
    }
    private fun row(name: String, items: List<Any>) {
        if(items.isEmpty()) return
        val a = ArrayObjectAdapter(CardPresenter()).apply { addAll(0, items) }
        adapter.add(ListRow(HeaderItem(name), a))
    }
    private fun loadHome(preserveFocus: Boolean=false) {
        val s = app.sessions.load() ?: return
        val selected=if(preserveFocus) rows.selectedPosition else 0
        val selectedId=if(preserveFocus) selectedItemId else null
        val restoreRows=!preserveFocus || rows.view?.hasFocus()==true
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
                title.text = "欢迎回来，${s.userName}"; description.text = "接着看喜欢的电影与剧集"
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
        TvUi.dialog(this).setTitle("搜索服务器").setView(field).setPositiveButton("搜索") { _, _ ->
            val q = field.text.toString().trim(); if(q.isNotBlank()) openPage(BrowserPage("", "搜索：$q", q))
        }.setNegativeButton("取消", null).show()
    }
    private fun sortDialog() {
        val current=page ?: run { message("打开分类或搜索结果后可选择排序");return }
        val values=listOf("SortName","DateCreated","PremiereDate","CommunityRating")
        TvUi.dialog(this).setTitle("内容排序").setSingleChoiceItems(
            arrayOf("名称","最近添加","上映时间","评分"),values.indexOf(current.sort)) { dialog,index ->
            dialog.dismiss();loadPage(current.copy(sort=values[index]))
        }.setNegativeButton("取消",null).show()
    }
    inner class CardPresenter : Presenter() {
        inner class Holder(val card: LinearLayout,val image: ImageView,val label: TextView,val subtitle: TextView) : ViewHolder(card) { var job: Job?=null }
        override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
            val card=TvUi.column(parent.context).apply {
                isFocusable=true;isFocusableInTouchMode=true
                setPadding(TvUi.dp(this,5),TvUi.dp(this,5),TvUi.dp(this,5),TvUi.dp(this,7))
                background=TvUi.box(TvUi.panel,20f)
                layoutParams=ViewGroup.LayoutParams(TvUi.dp(this,134),ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val image=ImageView(parent.context).apply { scaleType=ImageView.ScaleType.CENTER_CROP;background=TvUi.box(TvUi.raised,14f);clipToOutline=true }
            card.addView(image,LinearLayout.LayoutParams(-1,TvUi.dp(card,172)))
            val label=TvUi.text(parent.context,"",14f).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(4,TvUi.dp(this,8),4,0) }
            val sub=TvUi.text(parent.context,"",11f,TvUi.muted).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(4,2,4,0) }
            card.addView(label);card.addView(sub)
            TvUi.focusOnTouch(card)
            card.setOnFocusChangeListener { _,focused ->
                card.background=TvUi.box(if(focused) TvUi.text else TvUi.panel,20f)
                label.setTextColor(if(focused) TvUi.bg else TvUi.text);sub.setTextColor(if(focused) TvUi.raised else TvUi.muted)
                card.animate().scaleX(if(focused) 1.04f else 1f).scaleY(if(focused) 1.04f else 1f).setDuration(130).start()
            }
            return Holder(card,image,label,sub)
        }
        override fun onBindViewHolder(viewHolder: ViewHolder,item: Any?) {
            val h=viewHolder as Holder;h.job?.cancel();h.image.setImageDrawable(null)
            when(item) {
                is VideoItem -> {
                    h.label.text=item.name
                    h.subtitle.text=if(item.resumeTicks>0) "续看 · ${tv.ember.client.player.SeekPolicy.time(item.resumeTicks/10000)}" else item.subtitle.ifBlank { if(item.isFolder) "打开分类" else "查看详情" }
                    val s=app.sessions.load()
                    if(s!=null) h.job=PosterLoader.load(lifecycleScope,h.image,app.api.imageUrl(s,item),s)
                }
                is BrowserCommand -> { h.label.text=item.name;h.subtitle.text="按确定键";h.image.setImageResource(tv.ember.client.R.drawable.ic_launcher) }
            }
        }
        override fun onUnbindViewHolder(viewHolder: ViewHolder) { (viewHolder as Holder).job?.cancel();viewHolder.image.setImageDrawable(null) }
    }
}
