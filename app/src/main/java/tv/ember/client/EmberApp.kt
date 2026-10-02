package tv.ember.client

import android.app.Application
import tv.ember.client.data.SessionStore
import tv.ember.client.emby.EmbyApi
import tv.ember.client.network.HttpClient
import tv.ember.client.settings.PlaybackSettings

class EmberApp : Application() {
    lateinit var sessions: SessionStore
    lateinit var settings: PlaybackSettings
    lateinit var api: EmbyApi
    override fun onCreate() {
        super.onCreate()
        sessions = SessionStore(this)
        settings = PlaybackSettings(this)
        api = EmbyApi(HttpClient.api, sessions.deviceId)
    }
}
