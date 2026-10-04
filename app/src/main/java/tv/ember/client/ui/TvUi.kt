package tv.ember.client.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import tv.ember.client.BronyaApp
import tv.ember.client.i18n.*

/** Shared palette, viewport proportions and Activity/Surface interoperability. All controls use Compose. */
object TvUi {
    val bg=Color.rgb(3,14,20)
    val panel=Color.rgb(12,28,38)
    val raised=Color.rgb(24,44,56)
    val accent=Color.rgb(0,225,229)
    val text=Color.rgb(245,246,250)
    val muted=Color.rgb(162,169,183)
    val error=Color.rgb(255,151,156)
    fun scale(context: Context)=context.resources.displayMetrics.let { it.heightPixels/it.density/540f }
    fun unit(context: Context,n: Int)=(n*scale(context)).toInt().coerceAtLeast(1)
    fun railWidth(context: Context)=context.resources.displayMetrics.let { metrics ->
        val paint=android.graphics.Paint().apply { textSize=10.5f*scale(context)*metrics.scaledDensity;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
        val names=listOf(UiText.HOME_267,UiText.MOVIES_316,UiText.SERIES_317,UiText.FAVORITES_318,UiText.SEARCH_295,UiText.SETTINGS_268)
        maxOf((metrics.widthPixels/metrics.density*.077f).toInt(),kotlin.math.ceil(names.maxOf { paint.measureText(Tr.text(it)) }/metrics.density+45f).toInt())
    }
    fun gutter(context: Context)=unit(context,14)
    fun cardGap(context: Context)=unit(context,8)
    fun cardWidth(context: Context)=context.resources.displayMetrics.let {
        ((it.widthPixels/it.density-railWidth(context)-gutter(context)*2-cardGap(context)*4)/5).toInt()
    }
    fun dialog(context: Context)=TvDialogBuilder(context)
    fun chooseLanguage(activity: TvActivity) {
        if(activity.supportFragmentManager.findFragmentByTag("language_picker")==null)
            LanguagePickerDialog().show(activity.supportFragmentManager,"language_picker")
    }
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
    fun message(message: String) { Toast.makeText(this,message,Toast.LENGTH_LONG).show() }
}
