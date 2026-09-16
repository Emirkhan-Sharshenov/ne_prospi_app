package com.nesprosi.app

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.telephony.TelephonyManager
import androidx.appcompat.app.AppCompatDelegate
import java.util.Locale

object Lang {
    /** Языки интерфейса. Английский — основной, для всех остальных стран. */
    val SUPPORTED = listOf("en", "ru", "ky")

    /** Язык, выбранный в приложении, или пусто — «как в телефоне». */
    fun current(): String = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore(',')

    /** Язык, на котором сейчас показывается приложение ("en", "ru", "ky"…). */
    fun language(context: Context): String =
        current().ifEmpty { context.resources.configuration.locales[0].toLanguageTag() }.substringBefore('-')

    /** Язык названий при поиске: сначала язык приложения, потом местные названия, потом английский. */
    fun searchLanguage(context: Context): String = language(context).let { if (it == "en") "en" else "$it,en" }

    fun locale(context: Context): Locale = Locale.forLanguageTag(language(context))

    /**
     * Контекст с языком приложения. На Android 13+ система делает это сама,
     * на старых версиях службе и виджету нужно подставить язык вручную.
     */
    fun wrap(context: Context): Context {
        val tag = current()
        if (Build.VERSION.SDK_INT >= 33 || tag.isEmpty()) return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(tag))
        return context.createConfigurationContext(config)
    }
}

object Region {
    /** Страна пользователя: по сети оператора, по SIM-карте, иначе по настройкам телефона. */
    fun countryCode(context: Context): String {
        val tm = context.getSystemService(TelephonyManager::class.java)
        val fromNetwork = runCatching { tm?.networkCountryIso }.getOrNull()
        val fromSim = runCatching { tm?.simCountryIso }.getOrNull()
        return listOf(fromNetwork, fromSim, Locale.getDefault().country)
            .firstOrNull { !it.isNullOrBlank() }.orEmpty().uppercase(Locale.ROOT)
    }

    /** Страны, где расстояния считают в милях. */
    private val IMPERIAL = setOf("US", "GB", "LR", "MM")

    fun usesMiles(context: Context): Boolean = when (Prefs(context).units) {
        Prefs.UNITS_METRIC -> false
        Prefs.UNITS_IMPERIAL -> true
        else -> countryCode(context) in IMPERIAL
    }
}
