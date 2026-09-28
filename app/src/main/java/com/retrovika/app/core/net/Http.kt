package com.retrovika.app.core.net

import com.retrovika.app.R
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.Inet6Address
import java.net.UnknownHostException
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

    /**
     * Cliente que só conecta por IPv4 ([ipv6] = false) ou só por IPv6 (true). Sites que assinam o link de
     * download com o IP de quem abriu a página (RomsFun) recusam o arquivo se o pedido sair pelo outro
     * protocolo: o WebView e o OkHttp nem sempre escolhem o mesmo num aparelho com IPv4 e IPv6.
     */
    fun clientFor(ipv6: Boolean?): OkHttpClient = when (ipv6) {
        null -> client
        else -> client.newBuilder()
            .dns(object : Dns {
                override fun lookup(hostname: String) =
                    Dns.SYSTEM.lookup(hostname).filter { (it is Inet6Address) == ipv6 }.ifEmpty { throw UnknownHostException(hostname) }
            })
            .build()
    }

    suspend fun getString(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).executeCancellable { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code, url)
            res.body!!.string()
        }
    }

    /** Tamanho do arquivo em [url] pelo cabeçalho de um HEAD, sem baixá-lo; nulo quando o servidor não informa. */
    suspend fun contentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).executeCancellable { res ->
            if (!res.isSuccessful) null else res.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
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
     *
     * Servidor ocupado (503/429) é tentado de novo algumas vezes, com espera crescente; conexão que cai
     * no meio continua de onde parou (Range), quando o servidor aceita. Servidores de ROM vivem assim.
     */
    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        http: OkHttpClient = client,
        onBytes: (read: Long, total: Long) -> Unit = { _, _ -> },
        onSaved: (File) -> Unit = {},
        // Por último: quem chama passa o progresso como lambda final.
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        // Temporário com nome único: dois downloads que caem no mesmo arquivo final não escrevem
        // no mesmo .part ao mesmo tempo.
        target.parentFile?.mkdirs()
        val part = File.createTempFile("dl-" + target.name.take(60) + ".", ".part", target.parentFile)
        val ctx = coroutineContext
        // Falha ou cancelamento não deixam o .part ocupando espaço.
        try {
            var read = 0L
            var total = -1L
            var busyTries = 0
            var dropTries = 0
            var lastReport = 0L
            while (true) {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    if (read > 0) header("Range", "bytes=$read-")
                }.build()
                val finished = try {
                    http.newCall(request).executeCancellable { res ->
                        // Só downloads do navegador interno levam cookies: aí o 403 costuma ser a sessão do site.
                        if (res.code == 403 && "Cookie" in headers) throw LocalizedException(R.string.download_forbidden)
                        // Servidor de arquivos sobrecarregado ou limitando o IP: vale esperar e tentar de novo.
                        if (res.code == 503 || res.code == 429) {
                            if (busyTries >= BUSY_WAITS_MS.size) throw LocalizedException(R.string.download_server_busy)
                            return@executeCancellable false
                        }
                        if (!res.isSuccessful) throw LocalizedException(R.string.download_http_error, res.code, url)
                        // 206 continua de onde parou; 200 manda o arquivo inteiro de novo.
                        val resumed = read > 0 && res.code == 206
                        if (!resumed) read = 0
                        val body = res.body!!
                        if (!resumed) total = body.contentLength()
                        body.byteStream().use { input ->
                            FileOutputStream(part, resumed).use { output ->
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
                        // Conexão encerrada antes do tamanho anunciado: trata como queda.
                        if (total > 0 && read < total) throw IOException("fim prematuro: $read de $total")
                        true
                    }
                } catch (e: IOException) {
                    // Queda no meio do arquivo: tenta continuar. Antes do primeiro byte (sem internet,
                    // endereço errado) o erro sobe como sempre.
                    if (e is LocalizedException || read == 0L || dropTries >= MAX_RESUMES) throw e
                    dropTries++
                    delay(RESUME_WAIT_MS)
                    continue
                }
                if (finished) break
                delay(BUSY_WAITS_MS[busyTries++])
            }
            onBytes(read, if (total > 0) total else read)
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

    /** Esperas entre as tentativas com o servidor ocupado (503/429): pouco mais de um minuto no total. */
    private val BUSY_WAITS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000)
    private const val MAX_RESUMES = 5
    private const val RESUME_WAIT_MS = 2_000L

    /**
     * Executa a chamada de forma que cancelar a corrotina corte a conexão: numa rede parada, a leitura
     * bloqueada só voltaria no timeout, e o download cancelado seguia ocupando a vaga da fila.
     */
    private suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
        val call = this@executeCancellable
        // UNDISPATCHED: o vigia já está esperando antes do execute(); despachado, um cancelamento que chegasse
        // antes de ele rodar nunca cortaria a conexão.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
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
