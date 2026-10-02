package com.retrovika.app.core.tuning

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** O jogo que estava na frente do usuário: o que a próxima abertura precisa saber se o processo morrer com ele. */
@Serializable
data class PlaySession(
    val systemId: String,
    val coreId: String,
    val gameId: Long,
    /** Rodava com Vulkan; [vulkanKey] é o aparelho e a versão do núcleo, como o [VulkanHealth] os guarda. */
    val vulkan: Boolean,
    val vulkanKey: String? = null,
    /** O nível de qualidade em vigor, e se foi o usuário que o escolheu (aí ele não é mexido). */
    val preset: String? = null,
    val userPreset: Boolean = false,
    /** O processo dono: o registro de um processo que ainda vive não é queda. */
    val pid: Int,
)

/** Como o processo de uma sessão que ficou aberta terminou. */
enum class ExitKind {
    /** Falha nativa (núcleo, driver), ANR, ou um sistema que não informa o motivo. */
    CRASH,
    /** O sistema matou o app por memória, com o jogo na frente: costuma ser a resolução alta. */
    MEMORY,
    /** Outro motivo (atualização do app, o usuário forçou a parada, uma exceção do próprio app): não diz nada do núcleo. */
    OTHER,
}

/**
 * Registro da sessão de jogo na frente do usuário, gravado depois do primeiro quadro e apagado quando ela sai da
 * frente normalmente (segundo plano ou saída com o núcleo já descarregado). Se o processo morre com ele gravado, a
 * abertura seguinte sabe qual jogo, núcleo, nível e renderizador estavam em uso, e reage: o [VulkanHealth] só via as
 * quedas antes do primeiro quadro, e um Vulkan que derrubava o jogo depois de alguns minutos nunca era desligado.
 */
class SessionGuard(private val store: Store) {

    /** Onde fica o registro; gravado de forma síncrona, porque o processo pode cair logo em seguida. */
    interface Store {
        fun read(): String?
        fun write(value: String?)
    }

    /** Em [file]; fica em filesDir/benchmark/, fora do backup (num aparelho restaurado apontaria uma queda que não houve). */
    constructor(file: File) : this(object : Store {
        override fun read(): String? = runCatching { file.readText() }.getOrNull()
        override fun write(value: String?) {
            if (value == null) { file.delete(); return }
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.path + ".tmp")
                tmp.writeText(value)
                tmp.renameTo(file)
            }
        }
    })

    fun open(session: PlaySession) = store.write(json.encodeToString(PlaySession.serializer(), session))

    fun close() = store.write(null)

    /**
     * A sessão que um processo já morto deixou aberta, e como ele terminou ([exitReason] dá o motivo que o sistema
     * guardou para aquele pid, nulo quando não sabe); nula se não há. O registro é consumido.
     */
    fun takeDead(currentPid: Int, exitReason: (Int) -> Int?): Pair<PlaySession, ExitKind>? {
        val raw = store.read() ?: return null
        val session = runCatching { json.decodeFromString(PlaySession.serializer(), raw) }.getOrNull()
        // Deste mesmo processo: a Activity foi recriada sem passar pelo fechamento; não houve queda.
        if (session == null || session.pid == currentPid) {
            store.write(null)
            return null
        }
        store.write(null)
        return session to classify(exitReason(session.pid))
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        // Os valores de ApplicationExitInfo.REASON_*.
        private const val REASON_UNKNOWN = 0
        private const val REASON_LOW_MEMORY = 3
        private const val REASON_CRASH_NATIVE = 5
        private const val REASON_ANR = 6
        private const val REASON_EXCESSIVE_RESOURCE_USAGE = 9

        /** O motivo do sistema ([reason], nulo abaixo do Android 11 ou quando ele não guardou) virando o que interessa. */
        fun classify(reason: Int?): ExitKind = when (reason) {
            // Sem motivo, o registro aberto já diz que o processo morreu com o jogo na frente, sem passar pela pausa.
            null, REASON_UNKNOWN, REASON_CRASH_NATIVE, REASON_ANR -> ExitKind.CRASH
            REASON_LOW_MEMORY, REASON_EXCESSIVE_RESOURCE_USAGE -> ExitKind.MEMORY
            else -> ExitKind.OTHER
        }
    }
}
