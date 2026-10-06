package com.retrovika.app.emulation

import com.swordfish.libretrodroid.Variable

/** Opção de núcleo no formato libretro v0: "Descrição; valor1|valor2|valor3". */
data class CoreOption(val key: String, val title: String, val value: String, val values: List<String>) {
    fun next(): String = values.getOrNull((values.indexOf(value) + 1) % values.size.coerceAtLeast(1)) ?: value

    /** O valor antes do atual, dando a volta no início (setas do controle na lista de opções). */
    fun previous(): String {
        if (values.isEmpty()) return value
        val i = values.indexOf(value)
        return if (i <= 0) values.last() else values[i - 1]
    }

    /** No formato v0 o primeiro valor da lista é o padrão do núcleo. */
    val default: String get() = values.first()

    companion object {
        fun parse(v: Variable): CoreOption? {
            val key = v.key ?: return null
            val raw = v.description ?: return null
            val title = raw.substringBefore(';').trim()
            val values = raw.substringAfter(';', "").trim().split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (values.size < 2) return null
            return CoreOption(key, title, v.value ?: values.first(), values)
        }
    }
}
