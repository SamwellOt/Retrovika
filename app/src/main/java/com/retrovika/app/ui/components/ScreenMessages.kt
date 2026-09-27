package com.retrovika.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Mensagens de operações que rodam no escopo do app (importar ROMs, BIOS…). Ficam num ViewModel, e não
 * num `remember`, porque girar a tela recria a Activity: o resultado chegaria a uma tela que não existe mais.
 */
class ScreenMessages : ViewModel() {
    var message by mutableStateOf<String?>(null)
    var errors by mutableStateOf<String?>(null)
    /** Sobe a cada operação concluída, para a tela recarregar o que depende dela. */
    var version by mutableIntStateOf(0)
}
