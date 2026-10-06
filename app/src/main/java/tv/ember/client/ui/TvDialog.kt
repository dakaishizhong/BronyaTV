package tv.ember.client.ui

import android.content.Context
import androidx.activity.addCallback
import android.os.Bundle
import android.view.WindowManager
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Android window interoperability, with the entire dialog body implemented in Compose for TV. */
class TvDialog(context: Context): androidx.activity.ComponentDialog(context) {
    companion object { private val backKeys=BackKeyGate() }
    // Filter TV remote repeats; gestures use the same AndroidX dispatcher callback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if(event.keyCode==android.view.KeyEvent.KEYCODE_BACK) {
            if(event.action==android.view.KeyEvent.ACTION_UP && backKeys.accept(event.downTime,event.isCanceled)) onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.dispatchKeyEvent(event)
    }
    var heading=""
    var message by mutableStateOf("")
    var options=emptyList<String>()
    var selected=-1
    var optionAction: ((TvDialog,Int)->Unit)?=null
    var singleChoice=false
    var sidePanel=false
    var body: (@Composable () -> Unit)?=null
    var inputLabel: String?=null
    var input by mutableStateOf("")
    var inputError by mutableStateOf("")
    val buttons=mutableListOf<Triple<String,Boolean,(TvDialog)->Unit>>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) { cancel() }
        window?.setBackgroundDrawableResource(android.R.color.transparent)
        setContentView(ComposeView(context).apply { setContent {
            TvTheme {
                val focus=remember { FocusRequester() }
                val scale=LocalTvScale.current
                val messageFocus=remember { FocusRequester() };val buttonFocus=remember { FocusRequester() }
                val messageScroll=rememberScrollState();val scope=rememberCoroutineScope()
                androidx.tv.material3.Surface(shape=androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding((24*scale).dp)) {
                        Text(heading,color=Paper,fontSize=(24*scale).sp,lineHeight=(30*scale).sp,fontWeight=androidx.compose.ui.text.font.FontWeight.Medium)
                        Spacer(Modifier.height(12.dp))
                        body?.invoke()
                        if(options.isNotEmpty()) {
                            LazyColumn(Modifier.fillMaxWidth().weight(1f,false).heightIn(max=(260*scale).dp),state=rememberLazyListState(selected.coerceIn(0,options.lastIndex)),verticalArrangement=Arrangement.spacedBy(7.dp)) {
                                itemsIndexed(options,key={ i,_-> i }) { i,label ->
                                    TvAction(label,tag="dialog_option_$i",modifier=Modifier.fillMaxWidth().then(if(i==selected.coerceAtLeast(0)) Modifier.focusRequester(focus) else Modifier),selected=i==selected) {
                                        optionAction?.invoke(this@TvDialog,i);if(!singleChoice) dismiss()
                                    }
                                }
                            }
                        }
                        if(message.isNotBlank()) Text(message,Modifier.weight(1f,false).heightIn(max=(260*scale).dp).testTag("dialog_message")
                            .focusRequester(messageFocus).onPreviewKeyEvent { event ->
                                if(event.key==Key.DirectionDown || event.key==Key.DirectionUp) {
                                    if(event.type==KeyEventType.KeyDown) {
                                        if(event.key==Key.DirectionDown && messageScroll.value>=messageScroll.maxValue && buttons.isNotEmpty()) buttonFocus.requestFocus()
                                        else scope.launch { messageScroll.scrollBy((if(event.key==Key.DirectionDown) 100 else -100)*scale) }
                                    };true
                                } else false
                            }.verticalScroll(messageScroll).focusable(),color=Paper,fontSize=(14*scale).sp,lineHeight=(20*scale).sp)
                        inputLabel?.let { label ->
                            TvField(input,{ input=it },label,"dialog_input",Modifier.fillMaxWidth().focusRequester(focus))
                            if(inputError.isNotBlank()) Text(inputError,color=androidx.compose.ui.graphics.Color(TvUi.error))
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            buttons.forEachIndexed { i,(label,close,action) ->
                                TvAction(label,tag="dialog_button_$i",modifier=Modifier.then(if(i==0) Modifier.focusRequester(buttonFocus) else Modifier).then(if(options.isEmpty() && inputLabel==null && i==0) Modifier.focusRequester(focus) else Modifier)) {
                                    action(this@TvDialog);if(close) dismiss()
                                }
                            }
                        }
                    }
                }
                LaunchedEffect(Unit) { if(body==null) { if(options.isNotEmpty() || inputLabel!=null || buttons.isNotEmpty()) focus.requestFocus() else if(message.isNotBlank()) messageFocus.requestFocus() } }
                LaunchedEffect(messageScroll.maxValue>0) { if(messageScroll.maxValue>0 && options.isEmpty() && inputLabel==null) messageFocus.requestFocus() }
            }
        } })
        window?.setLayout((context.resources.displayMetrics.widthPixels*(if(sidePanel) .42f else .76f)).toInt(),if(sidePanel) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT)
        if(sidePanel) window?.apply { setGravity(android.view.Gravity.END);setDimAmount(.35f) }
    }
}
class TvDialogBuilder(private val context: Context) {
    private val dialog=TvDialog(context)
    fun setTitle(title: String)=apply { dialog.heading=title }
    fun setSidePanel()=apply { dialog.sidePanel=true }
    fun setContent(content: @Composable () -> Unit)=apply { dialog.body=content }
    fun setMessage(message: String)=apply { dialog.message=message }
    fun setItems(items: Array<String>,action: (TvDialog,Int)->Unit)=apply { dialog.options=items.toList();dialog.optionAction=action }
    fun setSingleChoiceItems(items: Array<String>,selected: Int,action: (TvDialog,Int)->Unit)=apply { dialog.options=items.toList();dialog.selected=selected;dialog.optionAction=action;dialog.singleChoice=true }
    fun setInput(label: String,initial: String)=apply { dialog.inputLabel=label;dialog.input=initial }
    fun button(label: String,close: Boolean=true,action: (TvDialog)->Unit={})=apply { dialog.buttons.add(Triple(label,close,action)) }
    fun setPositiveButton(label: String,action: ((TvDialog,Int)->Unit)?=null)=button(label) { action?.invoke(it,-1) }
    fun setNegativeButton(label: String,action: ((TvDialog,Int)->Unit)?=null)=if(action==null && label==tv.ember.client.i18n.Tr.text(tv.ember.client.i18n.UiText.CANCEL_196)) button(label,false) { it.cancel() } else button(label) { action?.invoke(it,-2) }
    fun setNeutralButton(label: String,action: ((TvDialog,Int)->Unit)?=null)=button(label) { action?.invoke(it,-3) }
    fun create()=dialog
    fun show()=dialog.apply { show() }
}
