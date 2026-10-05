package com.retrovika.app.core.tuning

import android.content.SharedPreferences

/**
 * O Vulkan de um núcleo depende do driver do aparelho, e um driver ruim derruba o app inteiro (não dá para
 * pegar uma falha nativa). Cada abertura com Vulkan conta uma tentativa que só zera quando o primeiro quadro
 * chega; duas seguidas sem chegar desligam o Vulkan daquele núcleo neste aparelho, e ele passa a abrir no
 * renderizador por software. Sair da tela antes do primeiro quadro não conta. Depois do primeiro quadro, as
 * quedas no meio do jogo contam à parte ([sessionCrashed]).
 *
 * O [device] de cada chamada é o aparelho e a versão do núcleo: o desligamento vale para os dois, e uma versão
 * nova do núcleo (que pode ter corrigido a queda) ganha outra chance.
 */
class VulkanHealth(private val store: Store) {

    /** O que a classe precisa guardar; em produção são as SharedPreferences. */
    interface Store {
        fun int(key: String): Int
        fun string(key: String): String?
        fun put(key: String, value: Int, sync: Boolean = false)
        fun put(key: String, value: String)
        fun remove(key: String)
        fun clear()
    }

    constructor(prefs: SharedPreferences) : this(object : Store {
        override fun int(key: String) = prefs.getInt(key, 0)
        override fun string(key: String) = prefs.getString(key, null)
        // commit() quando o processo pode cair logo em seguida: o apply() pode não chegar ao disco.
        override fun put(key: String, value: Int, sync: Boolean) { prefs.edit().putInt(key, value).let { if (sync) it.commit() else it.apply() } }
        // commit(): o valor "running" precisa estar no disco se o teste derrubar o processo.
        override fun put(key: String, value: String) { prefs.edit().putString(key, value).commit() }
        // apply(): esquecer as falhas (o usuário pediu para tentar de novo) não precisa chegar ao disco antes de nada.
        override fun remove(key: String) { prefs.edit().remove(key).apply() }
        override fun clear() { prefs.edit().clear().apply() }
    })

    /** Vale tentar Vulkan neste núcleo e aparelho. */
    fun shouldTry(coreId: String, device: String): Boolean {
        if (isOff(coreId, device)) return false
        if (store.int(strikes(coreId)) >= MAX_STRIKES) {
            turnOff(coreId, device)
            return false
        }
        return true
    }

    /** O Vulkan deste núcleo foi desligado aqui pelas falhas (não pelo usuário). */
    fun isOff(coreId: String, device: String): Boolean = store.string(off(coreId)) == device

    /** O usuário quer tentar de novo: esquece as falhas deste núcleo. */
    fun forget(coreId: String) {
        store.remove(off(coreId))
        store.remove(strikes(coreId))
        store.remove(crashes(coreId))
    }

    /**
     * Um jogo com Vulkan derrubou o app depois do primeiro quadro (driver, falta de memória de vídeo, defeito do
     * núcleo no renderizador). Uma queda só pode ser do jogo ou da cena; elas pesam [CRASH_WEIGHT] e cada sessão que
     * termina bem ([sessionEnded]) desconta um: duas quedas seguidas, ou quedas em mais da metade das sessões,
     * desligam o Vulkan. Verdadeiro se desligou agora.
     */
    fun sessionCrashed(coreId: String, device: String): Boolean {
        if (isOff(coreId, device)) return false
        val total = store.int(crashes(coreId)) + CRASH_WEIGHT
        if (total >= CRASH_LIMIT) {
            turnOff(coreId, device)
            return true
        }
        store.put(crashes(coreId), total, sync = true)
        return false
    }

    /** Uma sessão com Vulkan terminou sem queda depois de um tempo de jogo de verdade. */
    fun sessionEnded(coreId: String) {
        val current = store.int(crashes(coreId))
        if (current > 0) store.put(crashes(coreId), current - 1)
    }

    private fun turnOff(coreId: String, device: String) {
        store.put(off(coreId), device)
        store.put(strikes(coreId), 0)
        store.put(crashes(coreId), 0)
    }

    /**
     * Antes de abrir com Vulkan; gravado de forma síncrona (commit): o processo pode cair logo em seguida e a próxima
     * abertura precisa achar a tentativa no disco. Por ser síncrona (fsync), quem chama fora de teste deve usar uma
     * thread de fundo e esperar o retorno antes de criar o contexto do jogo.
     */
    fun attemptStarted(coreId: String) {
        store.put(strikes(coreId), store.int(strikes(coreId)) + 1, sync = true)
    }

    /** O primeiro quadro apareceu: o Vulkan funciona neste aparelho. */
    fun attemptSucceeded(coreId: String) {
        store.put(strikes(coreId), 0)
    }

    /** O usuário saiu antes do primeiro quadro: a tentativa não prova nada. */
    fun attemptAborted(coreId: String) {
        val current = store.int(strikes(coreId))
        if (current > 0) store.put(strikes(coreId), current - 1)
    }

    /** O LibretroDroid recusou ou não conseguiu criar o contexto: desliga já. */
    fun attemptFailed(coreId: String, device: String) = turnOff(coreId, device)

    /**
     * A ponte Vulkan funciona neste aparelho? [probe] roda uma vez e o resultado fica guardado por [build] (a versão do
     * sistema: um driver novo pede outro teste). Gravado como "running" antes: se o teste derruba o app, o seguinte já
     * sabe (senão todo jogo cairia na hora, em laço). Um "running" sozinho não prova nada (o usuário pode ter fechado o
     * app no meio), então o teste roda mais uma vez, como "retry"; só a segunda queda desliga de vez.
     */
    fun bridgeWorks(build: String, probe: () -> Boolean): Boolean {
        val key = "bridge_$build"
        val next = when (store.string(key)) {
            "ok" -> return true
            "failed", "retry" -> return false
            "running" -> "retry"
            else -> "running"
        }
        store.put(key, next)
        val works = runCatching(probe).getOrDefault(false)
        store.put(key, if (works) "ok" else "failed")
        return works
    }

    fun reset() = store.clear()

    private fun strikes(coreId: String) = "strikes_$coreId"
    private fun off(coreId: String) = "off_$coreId"
    private fun crashes(coreId: String) = "crashes_$coreId"

    companion object {
        const val MAX_STRIKES = 2
        const val CRASH_WEIGHT = 2
        const val CRASH_LIMIT = 4
    }
}
