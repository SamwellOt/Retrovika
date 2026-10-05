package com.retrovika.app.core.systems

import androidx.annotation.StringRes
import com.retrovika.app.R
import com.retrovika.app.core.tuning.DeviceProfile
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
    /**
     * Tipo de controle (RETRO_DEVICE_*) informado ao núcleo em cada porta logo depois de carregar o jogo.
     * O RetroArch sempre faz isso; o LibretroDroid não, e alguns núcleos só conectam o controle quando
     * recebem a chamada (sem ela, Flycast, Dolphin, Opera, PC-FX, PUAE, VICE, Caprice32 e fMSX ignoram
     * os botões). Não vale para todos: no Atari800 o JOYPAD puro troca o controle do 5200 por um genérico.
     */
    val portDevice: Int? = null,
    /**
     * O núcleo abre o jogo com o próprio I/O, sem a VFS do libretro: o caminho virtual dos jogos de pasta
     * vinculada não existe para ele. Sem o acesso a todos os arquivos, o app pede a permissão em vez de
     * deixar o núcleo seguir sem o jogo (o Play! fechava segundos depois).
     */
    val needsRealPath: Boolean = false,
    /**
     * O núcleo salva e carrega estados com segurança. O Play! lê e grava o estado sem pausar a própria
     * thread de emulação: o salvamento automático carregado ao abrir o jogo corrompia a máquina, que
     * travava logo depois. Sem isso, nada de estados, carregamento automático, compartilhar nem jogar em rede.
     */
    val saveStates: Boolean = true,
    /** Aceita o contexto GLES abaixo da versão que o núcleo pede (o Play! pede 3.2 e roda em 3.1). */
    val relaxedGlesVersion: Boolean = false,
    /**
     * O núcleo pode pedir um contexto Vulkan: o LibretroDroid o atende (ponte por AHardwareBuffer) e, se o aparelho
     * ou o núcleo não derem conta, o app volta para o renderizador que não usa Vulkan (`deviceOptions` escolhe).
     */
    val vulkan: Boolean = false,
    /**
     * Variáveis que valem por cima das escolhas do usuário e nem aparecem nas opções do núcleo: o que
     * quebraria o jogo se fosse mudado (sem memory card, jogo de PlayStation não salva nem continua).
     */
    val fixed: Map<String, String> = emptyMap(),
    /**
     * Opções que dependem do aparelho (não do nível de qualidade): ex.: quantas threads o renderizador por
     * software usa conforme os núcleos rápidos da CPU. Valem por cima do preset e abaixo das escolhas do usuário.
     */
    val deviceOptions: ((DeviceProfile) -> Map<String, String>)? = null,
    /**
     * O núcleo não abre jogo nenhum sem uma BIOS real: precisa de pelo menos uma das [GameSystem.bios] do console
     * presente (o SwanStation não tem HLE; o PCSX ReARMed tem). O teste de núcleos pula quem não teria como rodar,
     * em vez de baixar e medir um núcleo que falha.
     */
    val needsBios: Boolean = false,
    /**
     * Nível mais alto que o modo Auto escolhe, e sem o teste de velocidade: nos núcleos 3D pesados os primeiros
     * segundos do jogo (logos, carregamento, o que o teste mede) não dizem nada das cenas de jogo, e o teste subia
     * para resoluções que o aparelho não aguenta. O Auto fica no chute pela classe do aparelho até este nível; o vigia
     * de velocidade e o SessionGuard seguem baixando por jogo, e níveis acima só por escolha do usuário.
     */
    val autoMax: Preset? = null,
)

/** RETRO_DEVICE_JOYPAD do libretro.h (o RetroPad). */
const val RETRO_DEVICE_JOYPAD = 1

/** RETRO_DEVICE_SUBCLASS(JOYPAD, 0) do fMSX: joystick e, nos botões que sobram, teclas do MSX (F1-F5, espaço…). */
const val FMSX_JOYSTICK_KEYBOARD = (1 shl 8) or RETRO_DEVICE_JOYPAD

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
    /** Formato conferido pelo conteúdo, para BIOS sem um MD5 único (a do PS2 tem dezenas de versões). */
    val format: BiosFormat? = null,
)

enum class BiosFormat { PS2 }

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
    /**
     * Acervo do libretro-thumbnails de um modelo que divide o console com outro (Neo Geo Pocket e
     * WonderSwan monocromáticos ao lado dos Color), pela extensão do arquivo; as demais usam [libretroDbName].
     */
    val libretroDbByExtension: Map<String, String> = emptyMap(),
    /**
     * Console em que todo núcleo roda muitas vezes acima do tempo real em qualquer aparelho que não seja da classe
     * básica (8/16 bits e portáteis simples): o teste de núcleos custa segundos e não muda a escolha, então fica o
     * núcleo padrão. Só classe básica (ENTRY) testa. Ver [com.retrovika.app.core.cores.CoreBenchmark.skipForLightweight].
     */
    val lightweight: Boolean = false,
) {
    val defaultCore: CoreInfo get() = cores.first()

    /** Nome do acervo de capas para um arquivo com a extensão [ext]. */
    fun libretroDbFor(ext: String?): String? = ext?.lowercase()?.let(libretroDbByExtension::get) ?: libretroDbName
    fun core(id: String?): CoreInfo = cores.firstOrNull { it.id == id } ?: defaultCore
}
