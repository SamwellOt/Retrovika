package com.retrovika.app.core.net

import android.content.Context
import androidx.annotation.StringRes
import com.retrovika.app.core.settings.localized
import java.io.IOException

/**
 * Erro que chega ao usuário (ex.: na lista de downloads). Guarda o recurso de texto em vez da
 * mensagem pronta, para ser traduzido no idioma atual quando for exibido.
 */
class LocalizedException(@StringRes val messageRes: Int, vararg val args: Any) : IOException() {
    fun localizedMessage(context: Context): String = context.localized().getString(messageRes, *args)
}

/** Mensagem legível de qualquer falha: traduzida quando for [LocalizedException]. */
fun Throwable.userMessage(context: Context): String? =
    (this as? LocalizedException)?.localizedMessage(context) ?: message
