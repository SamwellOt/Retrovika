package com.retrovika.app.core.net

import com.retrovika.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
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
        client.newCall(request).executeCancellable { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code, url)
            res.body!!.string()
        }
    }

    /** POST de formulário (application/x-www-form-urlencoded), usado por fontes que expõem os links via AJAX. */
    suspend fun postForm(url: String, form: Map<String, String>, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val request = Request.Builder().url(url).post(body).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).executeCancellable { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}: $url")
            res.body!!.string()
        }
    }

    /**
     * Baixa [url] para [target] reportando progresso de 0 a 1 (ou -1 quando o tamanho é desconhecido).
     * [onBytes] recebe os bytes lidos e o total (-1 se desconhecido), no mesmo ritmo do progresso.
     * Escreve primeiro em um arquivo .part para nunca deixar arquivos corrompidos.
     */
    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        onBytes: (read: Long, total: Long) -> Unit = { _, _ -> },
        onProgress: (Float) -> Unit = {},
        onSaved: (File) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        // Temporário com nome único: dois downloads que caem no mesmo arquivo final não escrevem
        // no mesmo .part ao mesmo tempo.
        target.parentFile?.mkdirs()
        val part = File.createTempFile("dl-" + target.name.take(60) + ".", ".part", target.parentFile)
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val ctx = coroutineContext
        // Falha ou cancelamento não deixam o .part ocupando espaço.
        try {
            client.newCall(request).executeCancellable { res ->
                // Só downloads do navegador interno levam cookies: aí o 403 costuma ser a sessão do site.
                if (res.code == 403 && "Cookie" in headers) throw LocalizedException(R.string.download_forbidden)
                if (!res.isSuccessful) throw LocalizedException(R.string.download_http_error, res.code, url)
                val body = res.body!!
                val total = body.contentLength()
                var read = 0L
                var lastReport = 0L
                body.byteStream().use { input ->
                    part.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ctx.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            // Por tempo, não por bytes: numa conexão rápida cada aviso recompõe as telas de
                            // download dezenas de vezes por segundo.
                            val now = System.nanoTime()
                            if (now - lastReport > PROGRESS_INTERVAL_NS) {
                                lastReport = now
                                onBytes(read, total)
                                onProgress(if (total > 0) read.toFloat() / total else -1f)
                            }
                        }
                    }
                }
                onBytes(read, if (total > 0) total else read)
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
        onProgress(1f)
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.delete()
            throw LocalizedException(R.string.download_move_failed, part.name)
        }
        // Avisado antes do retorno: um cancelamento que chegue agora ainda deixa quem chamou apagar o arquivo.
        onSaved(target)
        target
    }

    private const val PROGRESS_INTERVAL_NS = 150_000_000L

    /**
     * Executa a chamada de forma que cancelar a corrotina corte a conexão: numa rede parada, a leitura
     * bloqueada só voltaria no timeout, e o download cancelado seguia ocupando a vaga da fila.
     */
    private suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
        val call = this@executeCancellable
        val watcher = launch { try { awaitCancellation() } finally { call.cancel() } }
        try {
            call.execute().use(block)
        } catch (t: Throwable) {
            // Conexão cortada pelo cancelamento: sobe como cancelamento, não como erro de rede.
            coroutineContext.ensureActive()
            throw t
        } finally {
            watcher.cancel()
        }
    }
}
