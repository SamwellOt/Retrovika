package com.retrovika.app.core.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** Idiomas oferecidos em Ajustes. [tag] nulo = seguir o idioma do sistema. */
enum class AppLanguage(val tag: String?) { SYSTEM(null), PORTUGUESE("pt-BR"), ENGLISH("en") }

/**
 * Idioma do app escolhido pelo usuário.
 *
 * No Android 13+ usa o idioma por app do sistema (LocaleManager): ele persiste sozinho, aparece em
 * Configurações › Apps › Idioma e recria as Activities. Nas versões anteriores a escolha fica em
 * SharedPreferences (leitura síncrona, necessária em attachBaseContext) e cada Activity embrulha o contexto.
 */
object Languages {
    private const val PREFS = "retrovika_locale"
    private const val KEY = "language"

    private val native get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    fun current(context: Context): AppLanguage {
        val tag = if (native) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales?.takeIf { !it.isEmpty }?.get(0)?.toLanguageTag()
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        }
        return when {
            tag == null -> AppLanguage.SYSTEM
            tag.startsWith("pt") -> AppLanguage.PORTUGUESE
            tag.startsWith("en") -> AppLanguage.ENGLISH
            else -> AppLanguage.SYSTEM
        }
    }

    fun set(activity: Activity, language: AppLanguage) {
        if (current(activity) == language) return
        if (native) {
            // O sistema recria as Activities com o novo idioma.
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                language.tag?.let { LocaleList.forLanguageTags(it) } ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
            activity.recreate()
        }
    }

    /** Para attachBaseContext: aplica o idioma escolhido nas versões sem idioma por app nativo. */
    fun wrap(base: Context): Context {
        if (native) return base
        // Sem escolha salva, segue o idioma do sistema: o contexto da Application pode ter sido
        // embrulhado na abertura com um idioma que o usuário depois trocou por "Sistema".
        val tag = base.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        val locale = tag?.let(Locale::forLanguageTag) ?: Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply { setLocale(locale) }
        return base.createConfigurationContext(config)
    }
}

/**
 * Contexto com o idioma atual, para textos gerados fora da UI (mensagens de erro de downloads,
 * nomes de região...). O contexto da Application não é recriado quando o idioma muda no Android < 13.
 */
fun Context.localized(): Context = Languages.wrap(applicationContext)
