package com.retrovika.app.core.net

import android.content.Context
import androidx.annotation.StringRes
import com.retrovika.app.R
import com.retrovika.app.core.settings.localized
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.serialization.SerializationException

/**
 * Erro que chega ao usuário (ex.: na lista de downloads). Guarda o recurso de texto em vez da
 * mensagem pronta, para ser traduzido no idioma atual quando for exibido.
 */
class LocalizedException(@StringRes val messageRes: Int, vararg val args: Any) : IOException() {
    fun localizedMessage(context: Context): String = context.localized().getString(messageRes, *args)
}

/**
 * Mensagem legível de qualquer falha: traduzida quando for [LocalizedException], e as falhas comuns
 * de rede e de disco viram um texto claro em vez da mensagem técnica (ou de nenhuma).
 */
fun Throwable.userMessage(context: Context): String {
    if (this is LocalizedException) return localizedMessage(context)
    val res = context.localized()
    val raw = message.orEmpty()
    return when {
        this is UnknownHostException || this is ConnectException -> res.getString(R.string.common_error_no_internet)
        this is SocketTimeoutException -> res.getString(R.string.common_error_timeout)
        this is SSLException -> res.getString(R.string.common_error_secure_connection)
        // Resposta HTTP de erro: o código vira uma frase, em vez de "HTTP 404: <url>".
        this is HttpStatusException -> when (code) {
            404, 410 -> res.getString(R.string.common_error_http_not_found)
            401, 403 -> res.getString(R.string.common_error_http_forbidden)
            in 500..599 -> res.getString(R.string.common_error_http_server, code)
            else -> res.getString(R.string.common_error_http_other, code)
        }
        // JSON fora do formato esperado (a API mudou ou devolveu outra coisa).
        this is SerializationException -> res.getString(R.string.common_error_bad_data)
        "ENOSPC" in raw || "No space left" in raw -> res.getString(R.string.common_error_no_space)
        this is OutOfMemoryError -> res.getString(R.string.common_error_memory)
        raw.isNotBlank() -> raw
        else -> res.getString(R.string.common_error_unknown, javaClass.simpleName)
    }
}
