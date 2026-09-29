package com.retrovika.app.core.net

import com.retrovika.app.R
import com.retrovika.app.core.storage.FileNames
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Resposta HTTP fora da faixa 2xx; [code] permite tratar casos como 404 sem depender da mensagem. */
class HttpStatusException(val code: Int, url: String) : IOException("HTTP $code: $url")

/**
 * Marca de um pedido com cookies postos à mão (downloads do navegador interno). O cabeçalho Cookie
 * vale só para [host]: num redirecionamento para outro host ele sai, e entram os cookies que
 * [cookiesFor] tiver para a URL do novo salto (os do próprio navegador para aquele site).
 */
private class CookieScope(val host: String, val cookiesFor: ((String) -> String?)?)

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
        // Interceptor de rede: roda a cada salto de redirecionamento, que o OkHttp refaz copiando os
        // cabeçalhos do pedido original. Sem isso o Cookie de um site vazaria para o host seguinte.
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val scope = request.tag(CookieScope::class.java)
            if (scope == null || request.url.host.equals(scope.host, ignoreCase = true)) chain.proceed(request)
            else {
                val cookies = scope.cookiesFor?.invoke(request.url.toString())?.takeIf { it.isNotBlank() }
                chain.proceed(
                    request.newBuilder().removeHeader("Cookie")
                        .apply { if (cookies != null) header("Cookie", cookies) }
                        .build(),
                )
            }
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
            if (!res.isSuccessful) throw HttpStatusException(res.code, url)
            res.body!!.string()
        }
    }

    /**
     * Baixa [url] para [target] reportando progresso de 0 a 1 (ou -1 quando o tamanho é desconhecido).
     * [onBytes] recebe os bytes lidos e o total (-1 se desconhecido), no mesmo ritmo do progresso.
     * Escreve primeiro em um arquivo .part para nunca deixar arquivos corrompidos.
     *
     * [cookiesFor] dá os cookies de cada host quando um redirecionamento sai do host de [url] (o Cookie
     * de [headers] só vale para ele). Com [keepExisting], um arquivo diferente com o mesmo nome não é
     * apagado: o novo ganha "nome (2).ext". Com [serverName], o nome vem do Content-Disposition ou do
     * fim da URL final (depois dos redirecionamentos), quando existem, no lugar do nome de [target].
     */
    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        cookiesFor: ((String) -> String?)? = null,
        keepExisting: Boolean = false,
        serverName: Boolean = false,
        onBytes: (read: Long, total: Long) -> Unit = { _, _ -> },
        onSaved: (File) -> Unit = {},
        // Por último: quem chama passa o progresso como lambda final.
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        // Temporário com nome único: dois downloads que caem no mesmo arquivo final não escrevem
        // no mesmo .part ao mesmo tempo.
        target.parentFile?.mkdirs()
        val part = File.createTempFile(PART_PREFIX + target.name.take(60) + ".", PART_SUFFIX, target.parentFile)
        val request = Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            if (headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
                tag(CookieScope::class.java, CookieScope(url.toHttpUrl().host, cookiesFor))
            }
        }.build()
        val ctx = coroutineContext
        var remoteName: String? = null
        // Falha ou cancelamento não deixam o .part ocupando espaço.
        try {
            client.newCall(request).executeCancellable { res ->
                // Só downloads do navegador interno levam cookies: aí o 403 costuma ser a sessão do site.
                if (res.code == 403 && "Cookie" in headers) throw LocalizedException(R.string.download_forbidden)
                if (!res.isSuccessful) throw LocalizedException(R.string.download_http_error, res.code, url)
                if (serverName) remoteName = remoteFileName(res)
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
        val named = remoteName?.let { File(target.parentFile, FileNames.safe(it)) } ?: target
        // Escolher o nome e mover numa trava só: dois downloads terminando juntos não pegam o mesmo "(2)".
        val dest = synchronized(moveLock) {
            val dest = if (keepExisting) freeName(named, part) else named
            if (dest.exists()) dest.delete()
            if (!part.renameTo(dest)) {
                part.delete()
                throw LocalizedException(R.string.download_move_failed, part.name)
            }
            dest
        }
        // Avisado antes do retorno: um cancelamento que chegue agora ainda deixa quem chamou apagar o arquivo.
        onSaved(dest)
        dest
    }

    private const val PROGRESS_INTERVAL_NS = 150_000_000L
    private const val PART_PREFIX = "dl-"
    private const val PART_SUFFIX = ".part"
    private val moveLock = Any()
    /** Fim de URL que é script ou página, não nome de arquivo ("download.php?id=…"). */
    private val PAGE_EXTENSIONS = setOf("php", "html", "htm", "asp", "aspx", "jsp", "cgi")

    /**
     * Apaga os temporários de download ("dl-*.part") que sobraram nas subpastas de [root]: um app
     * encerrado à força (ou o aparelho reiniciado) no meio de um download não passa pelo finally que
     * os apagaria. Só pode rodar antes de qualquer download começar.
     */
    fun sweepStaleParts(root: File) {
        root.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.name.startsWith(PART_PREFIX) && f.name.endsWith(PART_SUFFIX)) f.delete()
            }
        }
    }

    /**
     * [wanted] se ele ainda não existe ou já tem exatamente o conteúdo baixado (o mesmo arquivo baixado
     * de novo: substitui sem duplicar). Senão, o primeiro "nome (N).ext" livre, para não apagar outro
     * jogo que só tem o mesmo nome (dois "rom.gb" de jogos diferentes, por exemplo).
     */
    private fun freeName(wanted: File, downloaded: File): File {
        if (!wanted.exists() || sameContent(wanted, downloaded)) return wanted
        val dir = wanted.parentFile
        val base = wanted.name.substringBeforeLast('.')
        val ext = wanted.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (true) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists() || sameContent(candidate, downloaded)) return candidate
            n++
        }
    }

    private fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || a.length() != b.length()) return false
        val bx = ByteArray(64 * 1024)
        val by = ByteArray(64 * 1024)
        var same = true
        a.inputStream().use { x ->
            b.inputStream().use { y ->
                while (same) {
                    val nx = x.fill(bx)
                    val ny = y.fill(by)
                    if (nx != ny) same = false
                    else if (nx <= 0) break
                    else for (i in 0 until nx) if (bx[i] != by[i]) { same = false; break }
                }
            }
        }
        return same
    }

    /** Lê até encher [buf] (ou chegar ao fim); devolve quantos bytes leu. */
    private fun java.io.InputStream.fill(buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val n = read(buf, total, buf.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /**
     * Nome dado pelo servidor: o Content-Disposition (filename* ou filename) ou, sem ele, o fim da URL
     * final quando tem extensão (o "uc" do Google Drive ou um "download.php" não servem de nome).
     */
    private fun remoteFileName(res: Response): String? {
        val cd = res.header("Content-Disposition")
        val fromHeader = cd?.let {
            Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
                ?.let { v -> runCatching { java.net.URLDecoder.decode(v.trim().replace("+", "%2B"), "UTF-8") }.getOrNull() }
                ?: Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }?.trim()?.takeIf { it.isNotBlank() }
        val fromUrl = res.request.url.pathSegments.lastOrNull()
            ?.takeIf { '.' in it && !it.startsWith('.') && it.substringAfterLast('.').lowercase() !in PAGE_EXTENSIONS }
        return fromHeader ?: fromUrl
    }

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
