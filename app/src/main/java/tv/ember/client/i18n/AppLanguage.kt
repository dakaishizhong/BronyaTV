package tv.ember.client.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object AppLanguage {
    fun read(context: Context): String=normalize(context.getSharedPreferences("interface",0).getString("language","en"))
    fun normalize(value: String?)=if(value=="zh") "zh" else "en"
    fun save(context: Context,value: String) {
        val code=normalize(value)
        context.getSharedPreferences("interface",0).edit().putString("language",code).apply()
        Tr.language=code
    }
    fun wrap(context: Context): Context {
        val code=read(context)
        Tr.language=code
        val locale=if(code=="zh") Locale.SIMPLIFIED_CHINESE else Locale.ENGLISH
        Locale.setDefault(locale)
        val config=Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(config)
    }
}
