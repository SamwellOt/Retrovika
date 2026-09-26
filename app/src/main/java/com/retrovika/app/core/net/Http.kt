package com.retrovika.app.core.net

import com.retrovika.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Resposta HTTP fora da faixa 2xx; [code] permite tratar casos como 404 sem depender da mensagem. */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code: $url")

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .addInterceptor { chain ->
            // Mantém o User-Agent quando ele foi definido na requisição (ex.: o do WebView, ao qual os cookies do site estão presos).
            val request = chain.request()
            if (request.header("User-Agent") != null) chain.proceed(request)
            else chain.proceed(request.newBuilder().header("User-Agent", "Retrovika/0.1 (Android)").build())
        }
        .build()

    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code, url)
            res.body!!.string()
        }
    }

    /** POST de formulário (application/x-www-form-urlencoded), usado por fontes que expõem os links via AJAX. */
    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: $url")
            res.body!!.string()
        }
    }

    /**
     * Baixa [url] para [target] reportando progresso de 0 a 1 (ou -1 quando o tamanho é desconhecido).
     * Escreve primeiro em um arquivo .part para nunca deixar arquivos corrompidos.
     */
    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val part = File(target.parentFile, target.name + ".part")
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        // Falha ou cancelamento não deixam o .part ocupando espaço.
        try {
            client.newCall(request).execute().use { res ->
                if (res.code == 403 && headers.isNotEmpty()) throw LocalizedException(R.string.download_forbidden)
                if (!res.isSuccessful) throw LocalizedException(R.string.download_http_error, res.code, url)
                val body = res.body!!
                val total = body.contentLength()
                var read = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (read - lastReport > 256 * 1024) {
                                lastReport = read
                                onProgress(if (total > 0) read.toFloat() / total else -1f)
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        onProgress(1f)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw LocalizedException(R.string.download_move_failed, part.name)
        target
    }
}
