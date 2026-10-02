package tv.ember.client.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

object HttpClient {
    val api: OkHttpClient = OkHttpClient.Builder().connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(35, TimeUnit.SECONDS).retryOnConnectionFailure(true)
        .addNetworkInterceptor { chain ->
            val original = chain.call().request().url
            val redirected = chain.request().url
            val request = if(original.host != redirected.host || original.port != redirected.port || original.scheme != redirected.scheme)
                chain.request().newBuilder().removeHeader("X-Emby-Token").removeHeader("X-Emby-Authorization").build()
            else chain.request()
            chain.proceed(request)
        }.build()
    // No disk cache; data belongs only to the current playback and is released with it.
    val playback: OkHttpClient = api.newBuilder().callTimeout(0, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).build()
}
suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }
    })
}
class ApiException(val status: Int, message: String) : IOException(message)
