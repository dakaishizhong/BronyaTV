package tv.ember.client.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

/** Stores only an AES-GCM encrypted session. The password never reaches persistence. */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private var initialized=false
    private var cached: Session?=null
    val lastServer get()=prefs.getString("last_server","").orEmpty()
    val lastUserName get()=prefs.getString("last_name","").orEmpty()
    val deviceId: String = prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("device_id", it).apply()
    }
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("ember_session_v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("ember_session_v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun save(session: Session) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val json = JSONObject().put("server", session.server).put("token", session.token)
            .put("userId", session.userId).put("name", session.userName).toString()
        val data = c.iv + c.doFinal(json.toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted", Base64.encodeToString(data, Base64.NO_WRAP))
            .putString("last_server",session.server).putString("last_name",session.userName).commit())
        cached=session;initialized=true
    }
    @Synchronized fun load(): Session? {
        if(initialized) return cached
        cached=runCatching {
        val raw = prefs.getString("encrypted", null) ?: return@runCatching null
        val data = Base64.decode(raw, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        }
        val j = JSONObject(String(c.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
        Session(j.getString("server"), j.getString("token"), j.getString("userId"), j.getString("name"))
        }.getOrNull()
        // Fill login hints when upgrading an encrypted session saved before 1.1.0.
        cached?.let { session ->
            if(!prefs.contains("last_server")) prefs.edit().putString("last_server",session.server)
                .putString("last_name",session.userName).apply()
        }
        initialized=true
        return cached
    }
    @Synchronized fun clear() { cached=null;initialized=true;prefs.edit().remove("encrypted").apply() }
}
