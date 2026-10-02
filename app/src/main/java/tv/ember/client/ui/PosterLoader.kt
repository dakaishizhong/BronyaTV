package tv.ember.client.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import kotlinx.coroutines.*
import okhttp3.Request
import tv.ember.client.data.Session
import tv.ember.client.network.HttpClient
import tv.ember.client.network.awaitResponse

/** Modest memory cache only; recycled TV cards cancel their own requests. */
object PosterLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    fun load(scope: CoroutineScope, image: ImageView, url: String, session: Session): Job {
        image.setImageDrawable(null)
        return scope.launch {
            val key = "${session.userId}:$url"
            val cached = cache.get(key)
            if (cached != null) { image.setImageBitmap(cached); return@launch }
            try {
                val req = Request.Builder().url(url).header("X-Emby-Token", session.token).build()
                val data = withContext(Dispatchers.IO) {
                    HttpClient.api.newCall(req).awaitResponse().use { response ->
                        if(!response.isSuccessful) null else response.body?.source()?.let { source ->
                            // Reject oversized posters before a malicious image can consume TV memory.
                            if(source.request(4L * 1024 * 1024 + 1)) null else source.readByteArray()
                        }
                    }
                }
                if (data != null) {
                    val bitmap = withContext(Dispatchers.Default) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                        val options = BitmapFactory.Options().apply {
                            var sample = 1
                            while(bounds.outWidth / sample > 750 || bounds.outHeight / sample > 1125) sample *= 2
                            inSampleSize = sample
                        }
                        BitmapFactory.decodeByteArray(data, 0, data.size, options)
                    }
                    if(bitmap != null) { cache.put(key, bitmap); image.setImageBitmap(bitmap) }
                }
            } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Poster failure never blocks browsing. */ }
        }
    }
    fun clear() { cache.evictAll() }
}
