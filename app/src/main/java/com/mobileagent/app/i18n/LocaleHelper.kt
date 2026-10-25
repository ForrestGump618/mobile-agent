package com.mobileagent.app.i18n

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App-level language selection backed by standard Android string resources
 * (values/strings.xml and values-zh-rCN/strings.xml).
 */
object LocaleHelper {

    private const val PREFS = "mobile_agent_locale"
    private const val KEY = "language_tag"

    fun save(context: Context, tag: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, tag)
            .apply()
    }

    fun stored(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()

    fun wrap(base: Context): Context {
        val tag = stored(base)
        if (tag.isBlank()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }
}
