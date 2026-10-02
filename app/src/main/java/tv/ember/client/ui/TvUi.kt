package tv.ember.client.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.fragment.app.FragmentActivity
import tv.ember.client.EmberApp

object TvUi {
    val bg = Color.rgb(11, 16, 27)
    val panel = Color.rgb(27, 36, 52)
    val accent = Color.rgb(255, 180, 92)
    val text = Color.rgb(245, 244, 240)
    val muted = Color.rgb(167, 179, 197)
    fun dp(v: View, n: Int) = (n * v.resources.displayMetrics.density).toInt()
    fun box(color: Int, radius: Float = 12f, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius
        if (stroke != 0) setStroke(3, stroke)
    }
    fun focusBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), box(Color.rgb(59, 47, 34), 12f, accent))
        addState(intArrayOf(android.R.attr.state_pressed), box(Color.rgb(59, 47, 34), 12f, accent))
        addState(intArrayOf(), box(panel))
    }
    fun text(context: android.content.Context, value: String, size: Float = 18f, color: Int = text) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
    }
    fun button(context: android.content.Context, title: String, action: () -> Unit) = Button(context).apply {
        text = title; textSize = 16f; isAllCaps = false; setTextColor(TvUi.text)
        background = focusBackground(); isFocusable = true
        setPadding(dp(this, 18), dp(this, 8), dp(this, 18), dp(this, 8))
        minimumHeight = dp(this, 48)
        setOnClickListener { action() }
        setOnFocusChangeListener { _, focused ->
            animate().scaleX(if(focused) 1.03f else 1f).scaleY(if(focused) 1.03f else 1f).setDuration(130).start()
        }
    }
    fun column(context: android.content.Context) = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row(context: android.content.Context) = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL }
    fun add(parent: LinearLayout, child: View, width: Int = ViewGroup.LayoutParams.MATCH_PARENT, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT, bottom: Int = 10) {
        parent.addView(child, LinearLayout.LayoutParams(width, height).apply { bottomMargin = dp(parent, bottom) })
    }
}
open class TvActivity : FragmentActivity() {
    val app get() = application as EmberApp
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
    }
    fun paddedColumn(): LinearLayout = TvUi.column(this).apply {
        setBackgroundColor(TvUi.bg)
        setPadding(TvUi.dp(this, 40), TvUi.dp(this, 24), TvUi.dp(this, 40), TvUi.dp(this, 24))
    }
    fun message(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}
