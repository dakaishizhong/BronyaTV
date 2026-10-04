package tv.ember.client.ui

import android.widget.ImageView
import kotlinx.coroutines.*
import tv.ember.client.BronyaApp
import tv.ember.client.data.Session

/** Compatibility adapter for the remaining media Surface integration. */
object PosterLoader {
    fun load(scope: CoroutineScope,image: ImageView,url: String,session: Session,large: Boolean=false): Job = scope.launch {
        val app=image.context.applicationContext as BronyaApp
        val width=if(image.width>0) image.width else if(large) 1280 else 480
        val height=if(image.height>0) image.height else if(large) 720 else 185
        val bitmap=app.imageCache.load(session,url,width,height)
        if(bitmap!=null) image.setImageBitmap(bitmap)
    }
    fun clear() { /* Account isolation is part of every image key. */ }
    fun trim() { /* BronyaApp trims the shared image memory cache. */ }
}
