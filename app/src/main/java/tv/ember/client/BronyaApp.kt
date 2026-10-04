package tv.ember.client

import android.app.Application
import tv.ember.client.data.SessionStore
import tv.ember.client.data.PlaybackLaunchStore
import tv.ember.client.emby.EmbyApi
import tv.ember.client.network.HttpClient
import tv.ember.client.settings.PlaybackSettings

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class BronyaApp : Application() {
    lateinit var sessions: SessionStore
    lateinit var settings: PlaybackSettings
    lateinit var api: EmbyApi
    val launches=PlaybackLaunchStore()
    val progress=tv.ember.client.data.TransientProgress()
    val playbackCache by lazy { tv.ember.client.cache.PlaybackDiskCache(this) }
    val metadataCache by lazy { tv.ember.client.cache.MetadataStore(java.io.File(filesDir,"metadata-v1")) }
    val imageCache by lazy { tv.ember.client.cache.ImageCache(this) { settings.imageCacheMb*1048576L } }
    override fun onCreate() {
        super.onCreate()
        tv.ember.client.i18n.AppLanguage.wrap(this)
        sessions = SessionStore(this)
        settings = PlaybackSettings(this)
        api = EmbyApi(HttpClient.api, sessions.deviceId)
        api.metadataStore=metadataCache
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(level>=TRIM_MEMORY_RUNNING_LOW) tv.ember.client.ui.PosterLoader.trim()
        if(level>=TRIM_MEMORY_RUNNING_LOW) imageCache.trim(level>=TRIM_MEMORY_RUNNING_CRITICAL)
    }
}
