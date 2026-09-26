package com.retrovika.app.core.systems

import androidx.annotation.StringRes
import com.retrovika.app.R
import com.retrovika.app.emulation.input.PadLayout

/**
 * Um núcleo libretro capaz de rodar um sistema. [id] é o nome do arquivo no buildbot
 * (ex.: "snes9x" -> snes9x_libretro_android.so).
 */
data class CoreInfo(
    val id: String,
    val displayName: String,
    @StringRes val description: Int,
    val experimental: Boolean = false,
    /** Variáveis padrão aplicadas ao iniciar (otimizações testadas para mobile). */
    val defaults: Map<String, String> = emptyMap(),
    /** Presets de desempenho selecionáveis pelo usuário. */
    val presets: Map<Preset, Map<String, String>> = emptyMap(),
    /** Pacotes de assets que o núcleo precisa dentro da pasta system/. */
    val systemAssets: List<SystemAsset> = emptyList(),
)

enum class Preset(@StringRes val label: Int) { PERFORMANCE(R.string.preset_performance), BALANCED(R.string.preset_balanced), QUALITY(R.string.preset_quality) }

/**
 * Pacote .zip extraído em system/. [name] identifica o pacote (nome do marcador de instalação e do
 * arquivo temporário); a instalação só conta como feita depois de extraída por completo.
 */
data class SystemAsset(val name: String, val url: String)

data class BiosFile(
    val fileName: String,
    @StringRes val description: Int,
    val md5: String? = null,
    val required: Boolean = true,
    /**
     * BIOS alternativas entre si (ex.: modelos diferentes do mesmo console): basta uma do grupo.
     * Só faz sentido junto com [required].
     */
    val group: String? = null,
)

enum class Orientation { LANDSCAPE, PORTRAIT, ANY }

data class GameSystem(
    val id: String,
    val name: String,
    val shortName: String,
    val manufacturer: String,
    val year: Int,
    val extensions: Set<String>,
    val cores: List<CoreInfo>,
    val layout: PadLayout,
    /** Cor de destaque (ARGB) usada na UI do sistema. */
    val accent: Long,
    /** Nome do sistema no libretro-thumbnails, para capas automáticas. */
    val libretroDbName: String? = null,
    val bios: List<BiosFile> = emptyList(),
    val orientation: Orientation = Orientation.ANY,
    /** Plataforma correspondente no Homebrew Hub. */
    val homebrewPlatform: String? = null,
    val experimental: Boolean = false,
    /** Sistemas baseados em disco usam M3U para trocar CDs. */
    val multiDisc: Boolean = false,
    /** O núcleo abre o .zip/.7z diretamente (arcade, DOS): downloads não são extraídos. */
    val keepArchives: Boolean = false,
    /**
     * Extensões genéricas demais para identificar o console sozinhas (ex.: .png do PICO-8):
     * só contam dentro de uma pasta com o nome do console.
     */
    val folderOnlyExtensions: Set<String> = emptySet(),
) {
    val defaultCore: CoreInfo get() = cores.first()
    fun core(id: String?): CoreInfo = cores.firstOrNull { it.id == id } ?: defaultCore
}
