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
    val playbackCache by lazy { tv.ember.client.cache.PlaybackDiskCache(this) }
    override fun onCreate() {
        super.onCreate()
        sessions = SessionStore(this)
        settings = PlaybackSettings(this)
        api = EmbyApi(HttpClient.api, sessions.deviceId)
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(level>=TRIM_MEMORY_RUNNING_LOW) tv.ember.client.ui.PosterLoader.trim()
    }
}
