package tv.ember.client.ui

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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.fragment.app.FragmentActivity
import tv.ember.client.BronyaApp

object TvUi {
    val bg=Color.rgb(13,15,20)
    val panel=Color.rgb(29,32,40)
    val raised=Color.rgb(43,47,58)
    val accent=Color.rgb(124,177,255)
    val text=Color.rgb(245,246,250)
    val muted=Color.rgb(162,169,183)
    val error=Color.rgb(255,151,156)
    fun dp(v: View,n: Int)=(n*v.resources.displayMetrics.density).toInt()
    fun canvas()=GradientDrawable(GradientDrawable.Orientation.TL_BR,intArrayOf(Color.rgb(25,29,40),bg,Color.rgb(18,20,28)))
    fun box(color: Int,radius: Float=16f,stroke: Int=0)=GradientDrawable().apply {
        setColor(color);cornerRadius=radius
        if(stroke!=0) setStroke(2,stroke)
    }
    fun focusBackground(primary: Boolean=false)=StateListDrawable().apply {
        addState(intArrayOf(-android.R.attr.state_enabled),box(0xFF242730.toInt(),32f))
        addState(intArrayOf(android.R.attr.state_focused),box(Color.WHITE,32f))
        addState(intArrayOf(android.R.attr.state_pressed),box(0xFFDCE7F8.toInt(),32f))
        addState(intArrayOf(android.R.attr.state_selected),box(raised,32f,accent))
        addState(intArrayOf(),box(if(primary) 0xFFE9EDF5.toInt() else panel,32f))
    }
    fun dialog(context: Context)=AlertDialog.Builder(context)
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
        textSize=26f;contentDescription="返回";setPadding(0,0,0,0)
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
    fun section(parent: LinearLayout,title: String) {
        add(parent,text(parent.context,title,14f,accent).apply {
            typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
            setPadding(0,dp(this,18),0,dp(this,4))
        },bottom=8)
    }
}
open class TvActivity: FragmentActivity() {
    val app get()=application as BronyaApp
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
