package com.retrovika.app.core.net

import com.retrovika.app.R
import com.retrovika.app.core.storage.FileNames
import com.retrovika.app.core.storage.RomExtractor
import com.retrovika.app.core.storage.formatBytes
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.net.Inet6Address
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

/** Resposta HTTP fora da faixa 2xx; [code] permite tratar casos como 404 sem depender da mensagem. */
class HttpStatusException(val code: Int, val url: String) : IOException("HTTP $code: $url")

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
        // O padrão guarda só 5 conexões ociosas, divididas com as capas (Coil usa este cliente): a cada
        // página nova do catálogo ou do jogo, as dos sites já abertos tinham sido descartadas e cada pedido
        // pagava de novo o DNS e o TLS.
        .connectionPool(ConnectionPool(24, 5, TimeUnit.MINUTES))
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

    /**
     * Prende o Cookie posto à mão em [url] ao host dela: num redirecionamento para outro host ele sai (e entram
     * os de [cookiesFor], se houver). Todo pedido com Cookie nos cabeçalhos deve passar por aqui.
     */
    fun Request.Builder.scopeCookies(url: String, cookiesFor: ((String) -> String?)? = null): Request.Builder =
        tag(CookieScope::class.java, CookieScope(url.toHttpUrl().host, cookiesFor))

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

    /**
     * Versão do arquivo em [url] por um HEAD: o ETag ou, sem ele, o Last-Modified (com a data em milissegundos,
     * para comparar com a do arquivo local). Nulo quando o servidor não informa nenhum dos dois.
     */
    suspend fun fileVersion(url: String): Pair<String, Long?>? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().build()
        client.newCall(request).executeCancellable { res ->
            if (!res.isSuccessful) throw HttpStatusException(res.code, url)
            val modified = res.headers.getDate("Last-Modified")?.time
            val tag = res.header("ETag") ?: res.header("Last-Modified") ?: return@executeCancellable null
            tag to modified
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
     * Onde um download que falhou parou, para "tentar de novo" continuar dali (Range) em vez de baixar
     * gigabytes desde o zero. Quem chama guarda um por tarefa e o passa a cada tentativa; [discard] apaga o
     * .part quando a tarefa é cancelada ou sai da lista. Vale só para o download por uma conexão: o .part
     * em pedaços tem buracos e é descartado se falhar.
     */
    class ResumePoint {
        @Volatile internal var part: File? = null
        @Volatile internal var total: Long = -1
        /** ETag forte ou Last-Modified da resposta, mandado no If-Range: arquivo mudado no servidor recomeça. */
        @Volatile internal var validator: String? = null

        fun discard() {
            part?.delete()
            part = null
            total = -1
            validator = null
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
     *
     * Servidor ocupado (503/429) é tentado de novo com espera crescente, ou a que ele pedir no Retry-After,
     * por até [BUSY_BUDGET_MS] no total; [onWait] recebe a hora (epoch ms) da próxima tentativa, e 0 quando
     * ela começa. Conexão que cai no meio continua de onde parou (Range), quando o servidor aceita.
     * Servidores de ROM vivem assim.
     *
     * Com [resume], uma falha (não o cancelamento) deixa o .part guardado nele, e a próxima chamada com o
     * mesmo [resume] continua de onde parou.
     */
    suspend fun download(
        url: String,
        target: File,
        headers: Map<String, String> = emptyMap(),
        cookiesFor: ((String) -> String?)? = null,
        keepExisting: Boolean = false,
        serverName: Boolean = false,
        http: OkHttpClient = client,
        /**
         * Conexões ao mesmo tempo, cada uma baixando um pedaço (Range), quando o servidor aceita: servidores
         * que limitam a velocidade por conexão (Internet Archive e outros) ficam várias vezes mais rápidos.
         * 1 para sites que bloqueiam o IP com muitos pedidos.
         */
        parallel: Int = 1,
        resume: ResumePoint? = null,
        onBytes: (read: Long, total: Long) -> Unit = { _, _ -> },
        onSaved: (File) -> Unit = {},
        onWait: (until: Long) -> Unit = {},
        // Por último: quem chama passa o progresso como lambda final.
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        // Temporário com nome único: dois downloads que caem no mesmo arquivo final não escrevem
        // no mesmo .part ao mesmo tempo. Uma tentativa anterior que falhou deixa o seu em [resume].
        target.parentFile?.mkdirs()
        val kept = resume?.part?.takeIf { it.isFile && it.length() > 0 && it.parentFile == target.parentFile }
        if (kept == null) resume?.discard()
        val part = kept ?: File.createTempFile(PART_PREFIX + target.name.take(60) + ".", PART_SUFFIX, target.parentFile)
        val ctx = coroutineContext
        var remoteName: String? = null
        var read = kept?.length() ?: 0L
        var total = if (kept != null) resume?.total ?: -1L else -1L
        var validator = if (kept != null) resume?.validator else null
        // O .part só serve para continuar se for contínuo do byte 0 até [read] (o das partes tem buracos).
        var contiguous = true
        try {
            var busyTries = 0
            var busyWaited = 0L
            var busyWait = 0L
            var dropTries = 0
            var lastReport = 0L
            var restart = false
            // Em partes quando dá; senão (servidor sem Range, arquivo pequeno, ocupado), o download comum abaixo.
            var inParts = false
            val cookies = headers.keys.any { it.equals("Cookie", ignoreCase = true) }
            if (parallel > 1 && !cookies && read == 0L) {
                contiguous = false
                downloadInParts(url, part, headers, http, parallel, onBytes, onProgress, onWait)?.let { done ->
                    read = done.total
                    total = done.total
                    if (serverName) remoteName = done.remoteName
                    inParts = true
                }
                // null: nada ficou gravado (ou foi zerado), e o download comum começa do byte 0.
                contiguous = true
            }
            while (!inParts) {
                val request = Request.Builder().url(url).apply {
                    headers.forEach { (k, v) -> header(k, v) }
                    if (read > 0) {
                        header("Range", "bytes=$read-")
                        // Arquivo trocado no servidor desde o começo: ele manda o novo inteiro (200), não o resto.
                        validator?.let { header("If-Range", it) }
                    }
                    // Cookie posto à mão vale só para o host de [url] (ver o interceptor de rede do client).
                    if (headers.keys.any { it.equals("Cookie", ignoreCase = true) }) {
                        scopeCookies(url, cookiesFor)
                    }
                }.build()
                val readBefore = read
                val finished = try {
                    http.newCall(request).executeCancellable { res ->
                        // Só downloads do navegador interno levam cookies: aí o 403 costuma ser a sessão do site.
                        if (res.code == 403 && "Cookie" in headers) throw LocalizedException(R.string.download_forbidden)
                        // Servidor de arquivos sobrecarregado ou limitando o IP: vale esperar e tentar de novo.
                        if (res.code == 503 || res.code == 429) {
                            busyWait = retryAfterMs(res.header("Retry-After")) ?: BUSY_WAITS_MS[minOf(busyTries, BUSY_WAITS_MS.size - 1)]
                            if (busyWaited + busyWait > BUSY_BUDGET_MS) throw LocalizedException(R.string.download_server_busy)
                            return@executeCancellable false
                        }
                        // Queda logo depois do último byte de um arquivo sem tamanho anunciado: o pedido de
                        // continuação volta 416 porque não falta nada.
                        if (res.code == 416 && read > 0 && total <= 0) return@executeCancellable true
                        // Servidor que não atende a continuação (script de download com link assinado): o
                        // que já veio é descartado e o arquivo recomeça do zero, sem Range.
                        if (res.code == 416 && read > 0) {
                            FileOutputStream(part).close()
                            read = 0
                            restart = true
                            return@executeCancellable false
                        }
                        if (!res.isSuccessful) throw LocalizedException(R.string.download_http_error, res.code, url)
                        // 206 continua de onde parou; 200 manda o arquivo inteiro de novo.
                        val resumed = read > 0 && res.code == 206
                        if (resumed) {
                            // A continuação tem de começar no byte que falta e ser do mesmo arquivo (mesmo
                            // tamanho): sem validador, é o que dá para conferir. Senão, recomeça do zero.
                            val range = parseContentRange(res.header("Content-Range"))
                            if (range == null || range.first != read || (total > 0 && range.second > 0 && range.second != total)) {
                                FileOutputStream(part).close()
                                read = 0
                                restart = true
                                return@executeCancellable false
                            }
                            if (total <= 0 && range.second > 0) total = range.second
                        }
                        if (!resumed) read = 0
                        val body = res.body!!
                        if (!resumed) {
                            total = body.contentLength()
                            validator = validatorOf(res)
                        }
                        if (serverName && !resumed) remoteName = remoteFileName(res)
                        // Sem espaço para o que falta, nem começa: o disco encheria no meio de um arquivo grande.
                        // Recomeçando do zero, o .part antigo é zerado antes, para o espaço dele contar como livre.
                        if (!resumed && part.length() > 0) FileOutputStream(part).close()
                        if (total > 0) requireSpace(part, total - read)
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
                    // Disco cheio não é queda de rede: tentar de novo só encheria o disco outra vez.
                    if (e !is LocalizedException && RomExtractor.isNoSpace(e)) throw LocalizedException(R.string.common_error_no_space)
                    // Queda no meio do arquivo: tenta continuar. Antes do primeiro byte (sem internet,
                    // endereço errado) o erro sobe como sempre.
                    // Conta só quedas seguidas sem progresso: um arquivo grande numa rede instável pode cair
                    // muitas vezes e ainda assim terminar.
                    if (read > readBefore) dropTries = 0
                    if (e is LocalizedException || read == 0L || dropTries >= MAX_RESUMES) throw e
                    dropTries++
                    delay(RESUME_WAIT_MS)
                    continue
                }
                if (finished) break
                if (restart) {
                    restart = false
                    continue
                }
                busyTries++
                busyWaited += busyWait
                onWait(System.currentTimeMillis() + busyWait)
                delay(busyWait)
                onWait(0)
            }
            onBytes(read, if (total > 0) total else read)
        } catch (t: Throwable) {
            // Falha com parte já baixada fica guardada em [resume] para "tentar de novo" continuar. Cancelamento
            // (o usuário desistiu) e disco cheio apagam: o .part só ocuparia o espaço que falta.
            val keep = resume != null && t !is kotlinx.coroutines.CancellationException && contiguous && read > 0 &&
                !(t is LocalizedException && t.messageRes == R.string.common_error_no_space) && !RomExtractor.isNoSpace(t)
            if (keep) {
                resume!!.part = part
                resume.total = total
                resume.validator = validator
            } else {
                part.delete()
                resume?.discard()
            }
            throw t
        }
        // Concluído: o .part vira o arquivo final abaixo e não serve mais para continuar.
        resume?.let { it.part = null; it.total = -1; it.validator = null }
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

    /** Arquivo baixado em partes: o tamanho e o nome que o servidor deu (Content-Disposition). */
    private class PartsResult(val total: Long, val remoteName: String?)

    /** Um pedaço do arquivo, de [start] até [end] (inclusive). */
    private class Piece(val start: Long, val end: Long)

    /** O servidor parou de atender pedaços (respondeu 200 com o arquivo inteiro): o download comum recomeça. */
    private class NoRangesException : IOException()

    /**
     * Baixa [url] em pedaços com [connections] conexões ao mesmo tempo, gravando cada um na sua posição de
     * [part]. O primeiro pedido (Range desde o byte 0) serve de teste: se o servidor não responde 206, ou o
     * arquivo é pequeno, devolve null sem gravar nada e o download comum assume (um pedido a mais, só).
     *
     * Servidor ocupado (503/429) num pedaço reduz as conexões: aquela desiste e devolve o pedaço à fila;
     * só a última espera o Retry-After. Conexão que cai continua o pedaço de onde parou.
     */
    private suspend fun downloadInParts(
        url: String, part: File, headers: Map<String, String>, http: OkHttpClient, connections: Int,
        onBytes: (Long, Long) -> Unit, onProgress: (Float) -> Unit, onWait: (Long) -> Unit,
    ): PartsResult? = coroutineScope {
        // HTTP/1.1: no HTTP/2 os pedaços dividiriam uma conexão só, e o limite por conexão continuaria valendo.
        val h1 = http.newBuilder().protocols(listOf(Protocol.HTTP_1_1)).build()
        val probeCall = h1.newCall(rangeRequest(url, headers, 0, null))
        // Cancelar o download corta também o pedido de teste, que é lido fora do executeCancellable.
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { probeCall.cancel() } }
        val probe = try { probeCall.execute() } catch (t: Throwable) { watcher.cancel(); throw t }
        val total = probe.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
        if (probe.code != 206 || total == null || total < MIN_PARTS_BYTES) {
            probe.close()
            watcher.cancel()
            return@coroutineScope null
        }
        val remoteName = remoteFileName(probe)
        // Cada pedaço manda o validador do teste no If-Range: se o arquivo mudar no servidor no meio, o pedaço
        // volta 200 (o arquivo novo inteiro), o que cai em NoRangesException e o download comum recomeça do zero
        // em vez de juntar pedaços de dois arquivos.
        val validator = validatorOf(probe)
        try {
            requireSpace(part, total)
        } catch (e: Throwable) {
            probe.close()
            watcher.cancel()
            throw e
        }
        val pieceSize = (total / (connections * 4)).coerceIn(MIN_PIECE_BYTES, MAX_PIECE_BYTES)
        val queue = ArrayDeque<Piece>()
        var start = 0L
        while (start < total) {
            val end = minOf(start + pieceSize, total) - 1
            queue.addLast(Piece(start, end))
            start = end + 1
        }
        val done = AtomicLong(0)
        val active = AtomicInteger(connections)
        val busyWaited = AtomicLong(0)
        val lastReport = AtomicLong(0)
        fun report() {
            val now = System.nanoTime()
            val last = lastReport.get()
            if (now - last > PROGRESS_INTERVAL_NS && lastReport.compareAndSet(last, now)) {
                val d = done.get()
                onBytes(d, total)
                onProgress(d.toFloat() / total)
            }
        }
        RandomAccessFile(part, "rw").use { raf ->
            val channel = raf.channel
            try {
                val first = synchronized(queue) { queue.removeFirst() }
                var noRanges = false
                val workers = (0 until connections).map { i ->
                    launch(Dispatchers.IO) {
                        var piece: Piece? = if (i == 0) first else null
                        var response: Response? = if (i == 0) probe else null
                        try {
                            while (true) {
                                val current = piece ?: synchronized(queue) { queue.removeFirstOrNull() }
                                if (current == null) { active.decrementAndGet(); break }
                                piece = null
                                val rest = fetchPiece(h1, url, headers, validator, current, channel, response, done, active, busyWaited, ::report, onWait)
                                response = null
                                if (rest != null) {
                                    // Ocupado com outras conexões ainda trabalhando: esta sai e o que falta do pedaço volta à fila.
                                    synchronized(queue) { queue.addFirst(rest) }
                                    break
                                }
                            }
                        } catch (e: NoRangesException) {
                            noRanges = true
                        }
                    }
                }
                workers.joinAll()
                if (noRanges) throw NoRangesException()
                // Todas desistiram por ocupado ao mesmo tempo: a última espera e segue sozinha com o que sobrou.
                while (true) {
                    val current = synchronized(queue) { queue.removeFirstOrNull() } ?: break
                    active.set(1)
                    fetchPiece(h1, url, headers, validator, current, channel, null, done, active, busyWaited, ::report, onWait)
                        ?.let { rest -> synchronized(queue) { queue.addFirst(rest) } }
                }
            } catch (e: NoRangesException) {
                raf.setLength(0)
                return@coroutineScope null
            } finally {
                probe.close()
                watcher.cancel()
            }
        }
        if (done.get() != total) throw IOException("fim prematuro: ${done.get()} de $total")
        onBytes(total, total)
        PartsResult(total, remoteName)
    }

    private fun rangeRequest(url: String, headers: Map<String, String>, from: Long, to: Long?, validator: String? = null): Request =
        Request.Builder().url(url).apply {
            headers.forEach { (k, v) -> header(k, v) }
            header("Range", "bytes=$from-${to ?: ""}")
            validator?.let { header("If-Range", it) }
        }.build()

    /**
     * Validador para o If-Range: o ETag forte ou, sem ele, o Last-Modified. ETag fraco (W/) não vale no
     * If-Range (RFC 9110), e o servidor mandaria sempre o arquivo inteiro.
     */
    internal fun validatorOf(res: Response): String? =
        res.header("ETag")?.trim()?.takeIf { it.startsWith('"') }
            ?: res.header("Last-Modified")?.trim()?.takeIf { it.isNotEmpty() }

    /** "bytes 100-199/1000" → (100, 1000); o total é -1 quando vem "*". Null quando ilegível. */
    internal fun parseContentRange(header: String?): Pair<Long, Long>? {
        val m = header?.let { CONTENT_RANGE.find(it.trim()) } ?: return null
        val start = m.groupValues[1].toLongOrNull() ?: return null
        return start to (m.groupValues[2].toLongOrNull() ?: -1L)
    }
    private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-\d+/(\d+|\*)""", RegexOption.IGNORE_CASE)

    /** Falha logo, com o tamanho, quando o disco não tem [needed] bytes livres (e uma folga) ao lado de [file]. */
    private fun requireSpace(file: File, needed: Long) {
        val dir = file.parentFile ?: return
        val free = dir.usableSpace
        // usableSpace 0 é também o "não sei" de alguns sistemas de arquivos: só confia num valor positivo.
        if (needed > 0 && free > 0 && free < needed + SPACE_MARGIN) {
            throw LocalizedException(R.string.download_no_space_download, needed.formatBytes())
        }
    }
    private const val SPACE_MARGIN = 16L * 1024 * 1024

    /**
     * Baixa [piece] e grava na posição dele. [initial] é uma resposta já aberta que começa no início do
     * pedaço (o pedido de teste). Devolve null com o pedaço completo, ou o que falta dele quando o servidor está
     * ocupado e há outras conexões para continuar (os bytes já gravados ficam). Sendo a última conexão, espera
     * o Retry-After (dentro do limite de [BUSY_BUDGET_MS]), avisando [onWait] como o download comum.
     */
    private suspend fun fetchPiece(
        http: OkHttpClient, url: String, headers: Map<String, String>, validator: String?, piece: Piece, channel: FileChannel,
        initial: Response?, done: AtomicLong, active: AtomicInteger, busyWaited: AtomicLong, report: () -> Unit,
        onWait: (Long) -> Unit,
    ): Piece? {
        var pos = piece.start
        var opened = initial
        var drops = 0
        var busyTries = 0
        val ctx = coroutineContext
        // Lê a resposta até o fim do pedaço; devolve a espera pedida quando o servidor está ocupado (0 se leu).
        val read: (Response) -> Long = { res ->
            when {
                res.code == 503 || res.code == 429 ->
                    retryAfterMs(res.header("Retry-After")) ?: BUSY_WAITS_MS[minOf(busyTries++, BUSY_WAITS_MS.size - 1)]
                // 200 (arquivo inteiro) ou 416: o servidor não atende pedaços; o download comum assume.
                res.code == 200 || res.code == 416 -> throw NoRangesException()
                res.code != 206 -> throw LocalizedException(R.string.download_http_error, res.code, url)
                else -> {
                    val input = res.body!!.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    while (pos <= piece.end) {
                        ctx.ensureActive()
                        val n = input.read(buffer, 0, minOf(buffer.size.toLong(), piece.end - pos + 1).toInt())
                        if (n < 0) break
                        val bb = ByteBuffer.wrap(buffer, 0, n)
                        var at = pos
                        while (bb.hasRemaining()) at += channel.write(bb, at)
                        pos += n
                        done.addAndGet(n.toLong())
                        report()
                    }
                    if (pos <= piece.end) throw IOException("pedaço incompleto")
                    0L
                }
            }
        }
        while (pos <= piece.end) {
            val before = pos
            val wait = try {
                val first = opened
                opened = null
                first?.use(read) ?: http.newCall(rangeRequest(url, headers, pos, piece.end, validator)).executeCancellable(read)
            } catch (e: IOException) {
                if (e is LocalizedException || e is NoRangesException) throw e
                // Disco cheio não é queda de rede: tentar de novo só encheria o disco outra vez.
                if (RomExtractor.isNoSpace(e)) throw LocalizedException(R.string.common_error_no_space)
                if (pos > before) drops = 0
                if (drops++ >= MAX_RESUMES) throw e
                delay(RESUME_WAIT_MS)
                continue
            }
            if (wait > 0) {
                if (active.get() > 1) { active.decrementAndGet(); return Piece(pos, piece.end) }
                if (busyWaited.addAndGet(wait) > BUSY_BUDGET_MS) throw LocalizedException(R.string.download_server_busy)
                onWait(System.currentTimeMillis() + wait)
                delay(wait)
                onWait(0)
            }
        }
        return null
    }

    private const val PROGRESS_INTERVAL_NS = 150_000_000L
    private const val PART_PREFIX = "dl-"
    private const val PART_SUFFIX = ".part"
    private val moveLock = Any()
    /** Fim de URL que é script ou página, não nome de arquivo ("download.php?id=…"). */
    private val PAGE_EXTENSIONS = setOf("php", "html", "htm", "asp", "aspx", "jsp", "cgi")

    /**
     * [wanted] se ele ainda não existe ou já tem exatamente o conteúdo baixado (o mesmo arquivo baixado
     * de novo: substitui sem duplicar). Senão, o primeiro "nome (N).ext" livre, para não apagar outro
     * jogo que só tem o mesmo nome (dois "rom.gb" de jogos diferentes, por exemplo).
     */
    private fun freeName(wanted: File, downloaded: File): File {
        if (!wanted.exists() || FileNames.sameContent(wanted, downloaded)) return wanted
        val dir = wanted.parentFile
        val base = wanted.name.substringBeforeLast('.')
        val ext = wanted.name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var n = 2
        while (true) {
            val candidate = File(dir, "$base ($n)$ext")
            if (!candidate.exists() || FileNames.sameContent(candidate, downloaded)) return candidate
            n++
        }
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

    /** Esperas entre as tentativas com o servidor ocupado (503/429) quando ele não diz quanto esperar. */
    private val BUSY_WAITS_MS = longArrayOf(5_000, 10_000, 20_000, 40_000, 60_000)

    /**
     * Espera total com o servidor ocupado antes de desistir. Os servidores do RomsFun respondem 503 com
     * Retry-After de 5 minutos a arquivos grandes: desistir em um minuto (como antes) nunca chegava a
     * tentar de novo depois do prazo que o próprio servidor pediu.
     */
    private const val BUSY_BUDGET_MS = 16 * 60_000L
    private const val MAX_RETRY_AFTER_MS = 5 * 60_000L

    /** Retry-After em segundos ou como data HTTP, entre 1 s e [MAX_RETRY_AFTER_MS]; null quando ausente ou ilegível. */
    internal fun retryAfterMs(header: String?, now: Long = System.currentTimeMillis()): Long? {
        val value = header?.trim()?.ifEmpty { null } ?: return null
        val ms = value.toLongOrNull()?.times(1000)
            ?: runCatching { java.time.ZonedDateTime.parse(value, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - now }.getOrNull()
            ?: return null
        return ms.coerceIn(1_000, MAX_RETRY_AFTER_MS)
    }
    private const val MAX_RESUMES = 5
    /** Abaixo disso o download em partes não compensa os pedidos a mais. */
    private const val MIN_PARTS_BYTES = 8L * 1024 * 1024
    private const val MIN_PIECE_BYTES = 2L * 1024 * 1024
    private const val MAX_PIECE_BYTES = 32L * 1024 * 1024
    private const val RESUME_WAIT_MS = 2_000L

    /**
     * Executa a chamada de forma que cancelar a corrotina corte a conexão: numa rede parada, a leitura
     * bloqueada só voltaria no timeout, e o download cancelado seguia ocupando a vaga da fila.
     */
    internal suspend fun <T> Call.executeCancellable(block: (Response) -> T): T = coroutineScope {
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
