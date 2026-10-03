package tv.ember.client.ui

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.fragment.app.FragmentActivity
import tv.ember.client.BronyaApp

object TvUi {
    val bg=Color.rgb(3,14,20)
    val panel=Color.rgb(12,28,38)
    val raised=Color.rgb(24,44,56)
    val accent=Color.rgb(0,225,229)
    val text=Color.rgb(245,246,250)
    val muted=Color.rgb(162,169,183)
    val error=Color.rgb(255,151,156)
    fun dp(v: View,n: Int)=(n*v.resources.displayMetrics.density).toInt()
    // Use the TV's logical viewport, so 720p and 4K retain the same composition.
    fun scale(context: Context)=context.resources.displayMetrics.let { it.heightPixels/it.density/540f }
    fun unit(context: Context,n: Int)=(n*scale(context)).toInt().coerceAtLeast(1)
    fun railWidth(context: Context)=context.resources.displayMetrics.let { metrics ->
        val paint=android.graphics.Paint().apply { textSize=10.5f*scale(context)*metrics.scaledDensity;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        val names=listOf(UiText.HOME_267,UiText.MOVIES_316,UiText.SERIES_317,UiText.FAVORITES_318,UiText.SEARCH_295,UiText.SETTINGS_268)
        maxOf((metrics.widthPixels/metrics.density*.077f).toInt(),kotlin.math.ceil(names.maxOf { paint.measureText(Tr.text(it)) }/metrics.density+35f).toInt())
    }
    fun gutter(context: Context)=unit(context,14)
    fun cardGap(context: Context)=unit(context,8)
    fun cardWidth(context: Context)=context.resources.displayMetrics.let {
        ((it.widthPixels/it.density-railWidth(context)-gutter(context)*2-cardGap(context)*4)/5).toInt()
    }
    // Leanback 1.2.0 otherwise aligns every focused item to its keyline, even
    // when all five cards (or both rows) already fit in the viewport.
    @android.annotation.SuppressLint("RestrictedApi")
    fun keepVisibleItemsStill(grid: androidx.leanback.widget.BaseGridView?) {
        grid?.focusScrollStrategy=androidx.leanback.widget.BaseGridView.FOCUS_SCROLL_ITEM
    }
    fun canvas()=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(8,30,43),bg,Color.rgb(2,12,18)))
    fun box(color: Int,radius: Float=16f,stroke: Int=0)=GradientDrawable().apply {
        setColor(color);cornerRadius=radius
        if(stroke!=0) setStroke(2,stroke)
    }
    fun focusBackground(primary: Boolean=false)=StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled),box(0xFF242730.toInt(),32f))
        addState(intArrayOf(android.R.attr.state_focused),box(accent,14f,0xFF8FFFFF.toInt()))
        addState(intArrayOf(android.R.attr.state_pressed),box(0xFF70FFFF.toInt(),14f))
        addState(intArrayOf(android.R.attr.state_selected),box(raised,14f,accent))
        addState(intArrayOf(),box(if(primary) accent else panel,14f,if(primary) 0 else 0xFF25404D.toInt()))
    }
    fun dialog(context: Context)=AlertDialog.Builder(context)
    fun chooseLanguage(activity: TvActivity) {
        if(activity.supportFragmentManager.findFragmentByTag("language_picker")==null)
            LanguagePickerDialog().show(activity.supportFragmentManager,"language_picker")
    }
    fun text(context: Context,value: String,size: Float=18f,color: Int=text)=TextView(context).apply {
        text=value;textSize=size;setTextColor(color);fontFeatureSettings="kern"
        setLineSpacing(0f,1.12f)
    }
    fun button(context: Context,title: String,action: ()->Unit)=button(context,title,false,action)
    fun button(context: Context,title: String,primary: Boolean,action: ()->Unit)=Button(context).apply {
        text=title;textSize=15f;isAllCaps=false;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_pressed),intArrayOf()),
            intArrayOf(bg,bg,if(primary) bg else TvUi.text)))
        background=focusBackground(primary);isFocusable=true;isFocusableInTouchMode=true;stateListAnimator=null
        setPadding(dp(this,18),dp(this,8),dp(this,18),dp(this,8))
        minimumHeight=dp(this,44);minimumWidth=0;minWidth=0;includeFontPadding=false
        focusOnTouch(this)
        setOnClickListener { action() }
        setOnFocusChangeListener { _,focused ->
            animate().scaleX(if(focused) 1.04f else 1f).scaleY(if(focused) 1.04f else 1f).setDuration(120).start()
        }
    }
    fun back(context: Context, action: ()->Unit)=button(context,"‹",action).apply {
        textSize=26f;contentDescription=Tr.text(UiText.BACK_410);setPadding(0,0,0,0)
        layoutParams=LinearLayout.LayoutParams(dp(this,44),dp(this,44))
    }
    // Keep focus restoration available in touch mode while letting the platform dispatch the click.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    fun focusOnTouch(view:View) {
        view.setOnTouchListener { target,event ->
            if(event.actionMasked==MotionEvent.ACTION_DOWN) target.requestFocus()
            false
        }
    }
    fun input(context: Context,hint: String,secret: Boolean=false,uri: Boolean=false)=EditText(context).apply {
        this.hint=hint;textSize=16f;setTextColor(TvUi.text);setHintTextColor(muted)
        inputType=InputType.TYPE_CLASS_TEXT or when {
            secret -> InputType.TYPE_TEXT_VARIATION_PASSWORD
            uri -> InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        isSingleLine=true;isSaveEnabled=!secret
        setPadding(dp(this,16),dp(this,10),dp(this,16),dp(this,10));minimumHeight=dp(this,48)
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),box(raised,16f,accent))
            addState(intArrayOf(),box(panel,16f,Color.rgb(55,61,73)))
        }
    }
    fun column(context: Context)=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL }
    fun row(context: Context)=LinearLayout(context).apply { orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
    fun add(parent: LinearLayout,child: View,width: Int=ViewGroup.LayoutParams.MATCH_PARENT,height: Int=ViewGroup.LayoutParams.WRAP_CONTENT,bottom: Int=10) {
        parent.addView(child,LinearLayout.LayoutParams(width,height).apply { bottomMargin=dp(parent,bottom) })
    }
    fun sectionTitle(title: String)=android.text.SpannableString("▎ ${title.removePrefix("▎ ")}").apply {
        setSpan(android.text.style.ForegroundColorSpan(accent),0,1,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    fun section(parent: LinearLayout,title: String) {
        add(parent,text(parent.context,"",16f,text).apply {
            text=sectionTitle(title)
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(0,dp(this,8),0,dp(this,4))
        },bottom=8)
    }
    fun navigationButton(context: Context,title: String,selected: Boolean,action: ()->Unit)=button(context,title,action).apply {
        isSelected=selected;textSize=10.5f*scale(context);gravity=Gravity.START or Gravity.CENTER_VERTICAL
        setPadding(dp(this,6),0,0,0)
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_selected),intArrayOf()),intArrayOf(accent,accent,TvUi.text)))
        background=StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused),FocusRingDrawable(context,0x4021E2E5))
            addState(intArrayOf(android.R.attr.state_selected),FocusRingDrawable(context,0x2021E2E5))
            addState(intArrayOf(),box(Color.TRANSPARENT))
        }
        setOnFocusChangeListener(null) // The rail stays still while focus moves between destinations.
    }
    fun sidebar(context: Context, selected: String, navigate: (String)->Unit)=column(context).apply {
        background=box(0xF203121B.toInt(),0f)
        setPadding(dp(this,5),dp(this,16),dp(this,5),dp(this,18))
        add(this,ImageView(context).apply { setImageResource(tv.ember.client.R.drawable.ic_launcher);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="BronyaTV" },height=dp(this,40),bottom=0)
        add(this,text(context,"BronyaTV",11f).apply { gravity=Gravity.CENTER;typeface=Typeface.DEFAULT_BOLD;includeFontPadding=false },bottom=22)
        val symbols=mapOf(Tr.text(UiText.HOME_267) to "⌂", Tr.text(UiText.MOVIES_316) to "◉", Tr.text(UiText.SERIES_317) to "▣", Tr.text(UiText.FAVORITES_318) to "☆", Tr.text(UiText.SEARCH_295) to "⌕", Tr.text(UiText.SETTINGS_268) to "⚙")
        for(name in listOf(Tr.text(UiText.HOME_267),Tr.text(UiText.MOVIES_316),Tr.text(UiText.SERIES_317),Tr.text(UiText.FAVORITES_318),Tr.text(UiText.SEARCH_295))) {
            add(this,navigationButton(context,name,selected==name) { navigate(name) }.apply {
                tag="nav_${name}"
                setCompoundDrawablesWithIntrinsicBounds(NavIcon(symbols.getValue(name),TvUi.text,dp(this,16)),null,null,null)
                compoundDrawablePadding=dp(this,3)
            },height=dp(this,unit(context,34)),bottom=unit(context,10))
        }
        addView(View(context),LinearLayout.LayoutParams(1,0,1f))
        val app=context.applicationContext as BronyaApp
        add(this,View(context).apply { background=box(0xFF15303C.toInt()) },height=dp(this,1),bottom=12)
        add(this,navigationButton(context,"Emby",false) {
            val session=app.sessions.load()
            dialog(context).setTitle("Emby").setMessage(listOf(session?.userName,session?.server).filterNotNull().joinToString("\n"))
                .setPositiveButton(Tr.text(UiText.SETTINGS_268)) { _,_ -> navigate(Tr.text(UiText.SETTINGS_268)) }
                .setNegativeButton(Tr.text(UiText.CANCEL_196),null).show()
        }.apply {
            tag="nav_server";contentDescription=app.sessions.load()?.userName ?: "Emby"
            setCompoundDrawablesWithIntrinsicBounds(NavIcon("server",accent,dp(this,16)),null,null,null);compoundDrawablePadding=dp(this,3)
        },height=dp(this,unit(context,34)),bottom=8)
        add(this,navigationButton(context,Tr.text(UiText.SETTINGS_268),selected==Tr.text(UiText.SETTINGS_268)) { navigate(Tr.text(UiText.SETTINGS_268)) }.apply {
            tag=Tr.text(UiText.NAV_SETTINGS_411)
            setCompoundDrawablesWithIntrinsicBounds(NavIcon("settings",TvUi.text,dp(this,16)),null,null,null);compoundDrawablePadding=dp(this,3)
        },height=dp(this,unit(context,34)),bottom=0)
    }
    fun shell(context: Context, selected: String, navigate: (String)->Unit, body: View)=row(context).apply {
        background=canvas();gravity=Gravity.TOP
        addView(sidebar(context,selected,navigate),LinearLayout.LayoutParams(dp(this,railWidth(context)),-1))
        addView(body,LinearLayout.LayoutParams(0,-1,1f))
    }
    fun backdrop(context: Context): FrameLayout=FrameLayout(context).apply {
        background=canvas()
        addView(ImageView(context).apply { tag="backdrop";scaleType=ImageView.ScaleType.CENTER_CROP;alpha=.8f },FrameLayout.LayoutParams(-1,-1))
        addView(View(context).apply { background=GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,intArrayOf(0xF0030E14.toInt(),0x8C030E14.toInt(),0x10030E14)) },FrameLayout.LayoutParams(-1,-1))
        addView(View(context).apply { background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x10030E14,0xD0030E14.toInt(),bg)) },FrameLayout.LayoutParams(-1,-1))
    }
    fun progress(context: Context, progress: Int)=ProgressBar(context,null,android.R.attr.progressBarStyleHorizontal).apply {
        max=100;this.progress=progress.coerceIn(0,100);progressTintList=ColorStateList.valueOf(accent)
        progressBackgroundTintList=ColorStateList.valueOf(raised)
    }
}
private class NavIcon(private val symbol: String,private val color: Int,private val size: Int): android.graphics.drawable.Drawable() {
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color=this@NavIcon.color;textSize=size.toFloat();textAlign=android.graphics.Paint.Align.CENTER }
    override fun draw(canvas: android.graphics.Canvas) {
        paint.color=if(state.contains(android.R.attr.state_focused) || state.contains(android.R.attr.state_selected)) TvUi.accent else color
        paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=1.8f;paint.strokeCap=android.graphics.Paint.Cap.ROUND
        canvas.save();canvas.translate(bounds.left.toFloat(),bounds.top.toFloat());canvas.scale(bounds.width()/24f,bounds.height()/24f)
        when(symbol) {
            "⌂" -> { val p=android.graphics.Path();p.moveTo(3f,11f);p.lineTo(12f,3f);p.lineTo(21f,11f);p.moveTo(6f,9f);p.lineTo(6f,21f);p.lineTo(18f,21f);p.lineTo(18f,9f);p.moveTo(10f,21f);p.lineTo(10f,14f);p.lineTo(14f,14f);p.lineTo(14f,21f);canvas.drawPath(p,paint) }
            "◉" -> { canvas.drawCircle(12f,12f,9f,paint);for((x,y) in listOf(8f to 8f,16f to 8f,8f to 16f,16f to 16f)) canvas.drawCircle(x,y,2f,paint) }
            "▣" -> { canvas.drawRoundRect(3f,7f,21f,21f,2f,2f,paint);canvas.drawLine(8f,2f,12f,7f,paint);canvas.drawLine(16f,2f,12f,7f,paint) }
            "☆" -> { val p=android.graphics.Path();for(i in 0..10) { val angle=-Math.PI/2+i*Math.PI/5;val r=if(i%2==0) 10.0 else 4.5;val x=(12+Math.cos(angle)*r).toFloat();val y=(12+Math.sin(angle)*r).toFloat();if(i==0) p.moveTo(x,y) else p.lineTo(x,y) };p.close();canvas.drawPath(p,paint) }
            "settings" -> { canvas.drawCircle(12f,12f,7f,paint);canvas.drawCircle(12f,12f,3f,paint);for(i in 0..7) { val a=i*Math.PI/4;canvas.drawLine((12+7*Math.cos(a)).toFloat(),(12+7*Math.sin(a)).toFloat(),(12+10*Math.cos(a)).toFloat(),(12+10*Math.sin(a)).toFloat(),paint) } }
            "server" -> { canvas.drawRoundRect(3f,3f,21f,21f,5f,5f,paint);canvas.drawLine(8f,8f,16f,12f,paint);canvas.drawLine(16f,12f,8f,16f,paint);canvas.drawLine(8f,16f,8f,8f,paint) }
            else -> { canvas.drawCircle(10f,10f,7f,paint);canvas.drawLine(15f,15f,22f,22f,paint) }
        };canvas.restore()
    }
    override fun isStateful()=true
    override fun onStateChange(state: IntArray): Boolean { invalidateSelf();return true }
    override fun setAlpha(alpha: Int) { paint.alpha=alpha }
    override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter=filter }
    @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth()=size
    override fun getIntrinsicHeight()=size
}
/** A faint halo and a thin border; the interior never obscures artwork or text. */
class FocusRingDrawable(context: Context,private val fill: Int=Color.TRANSPARENT): android.graphics.drawable.Drawable() {
    private val density=context.resources.displayMetrics.density
    private val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: android.graphics.Canvas) {
        val rect=android.graphics.RectF(bounds)
        paint.style=android.graphics.Paint.Style.FILL;paint.color=fill
        canvas.drawRoundRect(rect,6*density,6*density,paint)
        paint.style=android.graphics.Paint.Style.STROKE
        for(i in 3 downTo 0) {
            paint.color=TvUi.accent;paint.alpha=if(i==0) 255 else 22;paint.strokeWidth=(1+i*1.5f)*density
            val r=android.graphics.RectF(rect).apply { inset(2*density,2*density) }
            canvas.drawRoundRect(r,6*density,6*density,paint)
        }
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(filter: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Java") override fun getOpacity()=android.graphics.PixelFormat.TRANSLUCENT
}
open class TvActivity: FragmentActivity() {
    private var attachedLanguage="en"
    override fun attachBaseContext(newBase: Context) {
        attachedLanguage=tv.ember.client.i18n.AppLanguage.read(newBase)
        super.attachBaseContext(tv.ember.client.i18n.AppLanguage.wrap(newBase))
    }
    override fun onResume() {
        super.onResume()
        if(tv.ember.client.i18n.AppLanguage.read(this)!=attachedLanguage) recreate()
    }
    companion object { private val backKeys = BackKeyGate() }
    val app get()=application as BronyaApp
    // TV remote keys need repeat filtering. Accepted keys use the same dispatcher callbacks
    // as gesture navigation; gesture callbacks themselves are not intercepted here.
    @android.annotation.SuppressLint("RestrictedApi", "GestureBackNavigation")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && backKeys.accept(event.downTime, event.isCanceled)) {
                onBackPressedDispatcher.onBackPressed()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }
    fun paddedColumn()=TvUi.column(this).apply {
        background=TvUi.canvas()
        setPadding(TvUi.dp(this,40),TvUi.dp(this,26),TvUi.dp(this,40),TvUi.dp(this,26))
    }
    fun message(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}
