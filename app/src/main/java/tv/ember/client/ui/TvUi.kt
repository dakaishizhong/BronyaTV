package tv.ember.client.ui

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import tv.ember.client.BronyaApp
import tv.ember.client.i18n.*

/** Shared palette, viewport proportions and Activity/Surface interoperability. All controls use Compose. */
object TvUi {
    val bg=Color.rgb(7,8,11)
    val panel=Color.rgb(15,17,24)
    val raised=Color.rgb(21,24,34)
    val accent=Color.rgb(56,211,159)
    val text=Color.rgb(241,243,249)
    val muted=Color.rgb(142,149,165)
    val error=Color.rgb(255,151,156)
    fun scale(context: Context)=context.resources.displayMetrics.let { it.heightPixels/it.density/540f }
    fun unit(context: Context,n: Int)=(n*scale(context)).toInt().coerceAtLeast(1)
    fun railWidth(context: Context)=unit(context,56)
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
