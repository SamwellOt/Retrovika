package com.retrovika.app.core.gameconfig

import java.io.File

/**
 * O `GameSettings/<ID>.ini` que o Dolphin lê para um jogo, na pasta de usuário dele (`saves/<console>/User`, achada
 * rastreando as aberturas de arquivo do núcleo). O Retrovika só mexe nos arquivos que ele mesmo gravou: a primeira
 * linha é a marca [MARK]. Um arquivo que o usuário colocou à mão fica como está.
 */
object GameIniFiles {

    const val MARK = "; Retrovika game profile"

    fun file(savesDir: File, discId: String): File = File(savesDir, "User/GameSettings/$discId.ini")

    /** Grava [text] como o arquivo do jogo; vazio apaga o que o Retrovika gravou. Devolve se o arquivo ficou presente. */
    fun sync(savesDir: File, discId: String, text: String): Boolean {
        val target = file(savesDir, discId)
        val ours = target.isFile && runCatching { target.useLines { it.firstOrNull() } }.getOrNull() == MARK
        if (text.isBlank()) {
            if (ours) target.delete()
            return false
        }
        // Arquivo do usuário, sem a nossa marca: não sobrescreve.
        if (target.exists() && !ours) return false
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        tmp.writeText(MARK + "\n" + text.trimEnd() + "\n")
        if (!tmp.renameTo(target)) { tmp.delete(); return false }
        return true
    }

    /** Se o arquivo de [discId] é de alguém que não o Retrovika (o usuário o colocou), a tela avisa em vez de gravar. */
    fun blockedByUserFile(savesDir: File, discId: String): Boolean {
        val target = file(savesDir, discId)
        return target.isFile && runCatching { target.useLines { it.firstOrNull() } }.getOrNull() != MARK
    }
}
