package com.retrovika.app.core.systems

import com.retrovika.app.R
import com.retrovika.app.core.tuning.DeviceProfile
import com.retrovika.app.core.tuning.DeviceTier
import com.retrovika.app.emulation.input.PadLayouts

/**
 * Catálogo de todos os sistemas suportados pelo Retrovika.
 * A ordem aqui define a ordem de exibição na UI.
 */
object Systems {

    private const val ASSETS = "https://buildbot.libretro.com/assets/system"
    private val PPSSPP_ASSETS = SystemAsset("PPSSPP", "$ASSETS/PPSSPP.zip")
    /** dolphin-emu/Sys: sem ele o Dolphin não inicia GameCube nem Wii. */
    private val DOLPHIN_ASSETS = SystemAsset("Dolphin", "$ASSETS/Dolphin.zip")
    /** pcsx2/resources exigido pelo LRPS2. */
    private val LRPS2_ASSETS = SystemAsset("LRPS2", "$ASSETS/LRPS2.zip")
    /** Machines/ e Databases/ do blueMSX (inclui o C-BIOS livre). */
    private val BLUEMSX_ASSETS = SystemAsset("blueMSX", "$ASSETS/blueMSX.zip")

    /**
     * Frameskip "auto" dos núcleos leves: ele pula quadros só quando o frontend avisa que o buffer de áudio está para
     * esvaziar (SET_AUDIO_BUFFER_STATUS_CALLBACK), então não custa nada onde o aparelho dá conta. O núcleo pede mais latência
     * de áudio enquanto está ligado, por isso só nas classes básica e média, as que podem engasgar; fora delas o valor
     * do núcleo (sem pular) fica. O usuário muda nas opções do núcleo (as escolhas dele valem por cima).
     */
    private fun autoFrameskip(key: String, value: String = "auto"): (DeviceProfile) -> Map<String, String> =
        { d -> if (d.tier <= DeviceTier.MID) mapOf(key to value) else emptyMap() }

    private val GPGX_FRAMESKIP = autoFrameskip("genesis_plus_gx_frameskip")
    private val PICODRIVE_FRAMESKIP = autoFrameskip("picodrive_frameskip")

    /**
     * O mGBA toca a animação da BIOS oficial se houver gba_bios.bin (ou gb_bios.bin etc.) em system/: pular a
     * abertura vale para quem a colocou; sem BIOS a opção é ignorada pelo núcleo. Os valores são "ON"/"OFF".
     */
    private val MGBA_DEFAULTS = mapOf("mgba_skip_bios" to "ON")

    /**
     * Dolphin: a resolução interna (`dolphin_efb_scale`, múltiplo de 640x528) e a compilação de shaders. No
     * LibretroDroid o Dolphin não cria o contexto compartilhado das threads de compilação ("Failed to initialize shader
     * compiler worker thread" no logcat), então todo shader compila na hora, em qualquer modo: os assíncronos não
     * evitam o engasgo, e os ubershaders ("1" e "2") só somam o custo deles na GPU. Com ubershaders e 2x/3x a 0.6.5
     * deixou o Dolphin lento; ficam de fora. O equilibrado é o que rodava até a 0.6.4 (o padrão do núcleo) e o teto
     * do Auto (ver CoreInfo.autoMax). O leve liga o VI Skip, que pula quadros só quando o jogo atrasa.
     */
    private val DOLPHIN_PRESETS = mapOf(
        Preset.PERFORMANCE to mapOf(
            "dolphin_efb_scale" to "1", "dolphin_shader_compilation_mode" to "3", "dolphin_vi_skip" to "enabled",
        ),
        Preset.BALANCED to mapOf("dolphin_efb_scale" to "1", "dolphin_shader_compilation_mode" to "0"),
        Preset.QUALITY to mapOf("dolphin_efb_scale" to "2", "dolphin_shader_compilation_mode" to "0"),
    )

    val all: List<GameSystem> = listOf(
        GameSystem(
            id = "nes", name = "Nintendo Entertainment System", shortName = "NES",
            manufacturer = "Nintendo", year = 1983,
            extensions = setOf("nes", "fds", "unf", "unif"),
            cores = listOf(
                CoreInfo("fceumm", "FCEUmm", R.string.core_fceumm),
                CoreInfo("nestopia", "Nestopia UE", R.string.core_nestopia),
                CoreInfo("mesen", "Mesen", R.string.core_mesen, needsRealPath = true),
            ),
            layout = PadLayouts.NES, accent = 0xFFE53935,
            libretroDbName = "Nintendo - Nintendo Entertainment System", lightweight = true,
            bios = listOf(BiosFile("disksys.rom", R.string.bios_disksys_rom, "ca30b50f880eb660a320674ed365ef7a", required = false)),
            homebrewPlatform = "NES",
        ),
        GameSystem(
            id = "snes", name = "Super Nintendo", shortName = "SNES",
            manufacturer = "Nintendo", year = 1990,
            extensions = setOf("smc", "sfc", "swc", "fig", "bs"),
            cores = listOf(
                CoreInfo("snes9x", "Snes9x", R.string.core_snes9x),
                CoreInfo("snes9x2010", "Snes9x 2010", R.string.core_snes9x2010, deviceOptions = autoFrameskip("snes9x_2010_frameskip")),
                CoreInfo("bsnes", "bsnes", R.string.core_bsnes, needsRealPath = true),
            ),
            layout = PadLayouts.SNES, accent = 0xFF7E57C2,
            libretroDbName = "Nintendo - Super Nintendo Entertainment System", lightweight = true,
        ),
        GameSystem(
            id = "n64", name = "Nintendo 64", shortName = "N64",
            manufacturer = "Nintendo", year = 1996,
            extensions = setOf("n64", "z64", "v64"),
            cores = listOf(
                CoreInfo(
                    "mupen64plus_next_gles3", "Mupen64Plus-Next", R.string.core_mupen64plus_next_gles3,
                    defaults = mapOf(
                        "mupen64plus-rdp-plugin" to "gliden64",
                        "mupen64plus-rsp-plugin" to "hle",
                        "mupen64plus-cpucore" to "dynamic_recompiler",
                        "mupen64plus-EnableFBEmulation" to "True",
                        "mupen64plus-43screensize" to "640x480",
                    ),
                    presets = mapOf(
                        // Sem o buffer de quadro (EnableFBEmulation) o GLideN64 deixa de desenhar telas de pausa e efeitos em
                        // muitos jogos: fica ligado em todos os níveis. A economia daqui vem da resolução, do cálculo de LOD
                        // por pixel (ligado no núcleo) e do filtro "híbrido" de escala, que o próprio núcleo avisa ser lento em
                        // GPU fraca. A cópia da cor para a RDRAM fica no "Async" do núcleo (desligar quebra efeitos).
                        Preset.PERFORMANCE to mapOf(
                            "mupen64plus-43screensize" to "320x240", "mupen64plus-EnableLODEmulation" to "False", "mupen64plus-HybridFilter" to "False",
                        ),
                        Preset.BALANCED to mapOf("mupen64plus-43screensize" to "640x480"),
                        Preset.QUALITY to mapOf("mupen64plus-43screensize" to "1280x960", "mupen64plus-MultiSampling" to "4"),
                    ),
                ),
                CoreInfo("parallel_n64", "ParaLLEl N64", R.string.core_parallel_n64, needsRealPath = true),
            ),
            layout = PadLayouts.N64, accent = 0xFF43A047,
            libretroDbName = "Nintendo - Nintendo 64",
        ),
        GameSystem(
            id = "gb", name = "Game Boy", shortName = "GB",
            manufacturer = "Nintendo", year = 1989,
            extensions = setOf("gb"),
            cores = listOf(
                CoreInfo("gambatte", "Gambatte", R.string.core_gambatte, defaults = mapOf("gambatte_gb_colorization" to "auto")),
                CoreInfo("sameboy", "SameBoy", R.string.core_sameboy),
                CoreInfo("mgba", "mGBA", R.string.core_mgba_gb, defaults = MGBA_DEFAULTS),
            ),
            layout = PadLayouts.GAMEBOY, accent = 0xFF8BC34A, orientation = Orientation.PORTRAIT,
            libretroDbName = "Nintendo - Game Boy", lightweight = true, homebrewPlatform = "GB",
        ),
        GameSystem(
            id = "gbc", name = "Game Boy Color", shortName = "GBC",
            manufacturer = "Nintendo", year = 1998,
            extensions = setOf("gbc"),
            cores = listOf(
                CoreInfo("gambatte", "Gambatte", R.string.core_gambatte),
                CoreInfo("sameboy", "SameBoy", R.string.core_sameboy),
            ),
            layout = PadLayouts.GAMEBOY, accent = 0xFFFFB300, orientation = Orientation.PORTRAIT,
            libretroDbName = "Nintendo - Game Boy Color", lightweight = true, homebrewPlatform = "GBC",
        ),
        GameSystem(
            id = "gba", name = "Game Boy Advance", shortName = "GBA",
            manufacturer = "Nintendo", year = 2001,
            extensions = setOf("gba"),
            cores = listOf(
                CoreInfo("mgba", "mGBA", R.string.core_mgba_gba, defaults = MGBA_DEFAULTS, deviceOptions = autoFrameskip("mgba_frameskip")),
                CoreInfo("gpsp", "gpSP", R.string.core_gpsp, deviceOptions = autoFrameskip("gpsp_frameskip")),
                CoreInfo("vba_next", "VBA Next", R.string.core_vba_next),
            ),
            layout = PadLayouts.GBA, accent = 0xFF5C6BC0,
            libretroDbName = "Nintendo - Game Boy Advance", lightweight = true,
            bios = listOf(BiosFile("gba_bios.bin", R.string.bios_gba_bios_bin, "a860e8c0b6d573d191e4ec7db1b1e4f6", required = false)),
            homebrewPlatform = "GBA",
        ),
        GameSystem(
            id = "nds", name = "Nintendo DS", shortName = "NDS",
            manufacturer = "Nintendo", year = 2004,
            extensions = setOf("nds"),
            cores = listOf(
                CoreInfo(
                    "melondsds", "melonDS DS", R.string.core_melondsds,
                    defaults = mapOf("melonds_render_mode" to "software", "melonds_console_mode" to "ds"),
                ),
                CoreInfo("desmume", "DeSmuME", R.string.core_desmume),
            ),
            layout = PadLayouts.NDS, accent = 0xFF90A4AE, orientation = Orientation.PORTRAIT,
            libretroDbName = "Nintendo - Nintendo DS",
            bios = listOf(
                BiosFile("bios7.bin", R.string.bios_bios7_bin, "df692a80a5b1bc90728bc3dfc76cd948", required = false),
                BiosFile("bios9.bin", R.string.bios_bios9_bin, "a392174eb3e572fed6447e956bde4b25", required = false),
                BiosFile("firmware.bin", R.string.bios_firmware_bin, required = false),
            ),
        ),
        GameSystem(
            id = "3ds", name = "Nintendo 3DS", shortName = "3DS",
            manufacturer = "Nintendo", year = 2011,
            extensions = setOf("3ds", "3dsx", "cci", "cxi", "app"),
            cores = listOf(
                CoreInfo(
                    "citra", "Citra", R.string.core_citra, experimental = true,
                    // O toque na tela só aperta com citra_touch_touchscreen, que vem desligado. O modo "mouse" (ligado
                    // por padrão) move o cursor com o dedo mas só aperta com o botão do mouse, que o Android não manda.
                    defaults = mapOf("citra_touch_touchscreen" to "enabled", "citra_mouse_touchscreen" to "disabled"),
                    // Resolução interna (1x = 400x240 por tela). Valores da lista do próprio núcleo ("1x (Native)|2x|3x…").
                    presets = mapOf(
                        Preset.PERFORMANCE to mapOf("citra_resolution_factor" to "1x (Native)"),
                        Preset.BALANCED to mapOf("citra_resolution_factor" to "2x"),
                        Preset.QUALITY to mapOf("citra_resolution_factor" to "4x"),
                    ),
                    // O Auto fica em 1x, como até a 0.6.4: 2x e 4x só por escolha do usuário.
                    autoMax = Preset.PERFORMANCE,
                ),
                CoreInfo("panda3ds", "Panda3DS", R.string.core_panda3ds, experimental = true, needsRealPath = true),
            ),
            layout = PadLayouts.NDS, accent = 0xFFD32F2F, orientation = Orientation.PORTRAIT,
            libretroDbName = "Nintendo - Nintendo 3DS", experimental = true,
            // .app é nome de programa/pacote comum demais: só dentro de uma pasta "3ds".
            folderOnlyExtensions = setOf("app"),
        ),
        GameSystem(
            id = "gc", name = "GameCube", shortName = "GC",
            manufacturer = "Nintendo", year = 2001,
            extensions = setOf("iso", "gcm", "rvz", "ciso", "gcz"),
            cores = listOf(CoreInfo("dolphin", "Dolphin", R.string.core_dolphin_gc, experimental = true, systemAssets = listOf(DOLPHIN_ASSETS), portDevice = RETRO_DEVICE_JOYPAD, presets = DOLPHIN_PRESETS, autoMax = Preset.BALANCED)),
            layout = PadLayouts.GAMECUBE, accent = 0xFF6A1B9A,
            libretroDbName = "Nintendo - GameCube", experimental = true,
        ),
        GameSystem(
            id = "wii", name = "Nintendo Wii", shortName = "WII",
            manufacturer = "Nintendo", year = 2006,
            extensions = setOf("wbfs", "rvz", "wia", "iso", "gcz", "ciso", "wad"),
            cores = listOf(CoreInfo("dolphin", "Dolphin", R.string.core_dolphin_wii, experimental = true, systemAssets = listOf(DOLPHIN_ASSETS), portDevice = RETRO_DEVICE_JOYPAD, presets = DOLPHIN_PRESETS, autoMax = Preset.BALANCED)),
            layout = PadLayouts.WII, accent = 0xFF90CAF9,
            libretroDbName = "Nintendo - Wii", experimental = true,
            // .wad também é o pacote de dados do Doom (e de outros jogos de PC): só dentro de uma pasta "wii".
            folderOnlyExtensions = setOf("wad"),
        ),
        GameSystem(
            id = "psx", name = "PlayStation", shortName = "PS1",
            manufacturer = "Sony", year = 1994,
            extensions = setOf("cue", "chd", "pbp", "m3u", "iso", "img", "bin", "exe"),
            cores = listOf(
                CoreInfo(
                    "pcsx_rearmed", "PCSX ReARMed", R.string.core_pcsx_rearmed,
                    defaults = mapOf(
                        "pcsx_rearmed_drc" to "enabled",
                        "pcsx_rearmed_neon_enhancement_enable" to "disabled",
                        "pcsx_rearmed_frameskip_type" to "disabled",
                    ),
                    presets = mapOf(
                        // Frameskip pelo buffer de áudio (só pula quando vai falhar), sem a reverberação da SPU nem o dithering
                        // da GPU, os dois itens que o próprio núcleo marca como custo de desempenho. O equilibrado fica sem
                        // mexer em nada: os "speed hacks" do núcleo (GTE, stalls) avisam que causam erros de imagem.
                        Preset.PERFORMANCE to mapOf(
                            "pcsx_rearmed_frameskip_type" to "auto", "pcsx_rearmed_spu_reverb" to "disabled", "pcsx_rearmed_dithering" to "disabled",
                        ),
                        Preset.BALANCED to emptyMap(),
                        Preset.QUALITY to mapOf("pcsx_rearmed_neon_enhancement_enable" to "enabled"),
                    ),
                    // Cartão 1 é a SRAM (.srm); o 2 é saves/psx/pcsx-card2.mcd, criado e formatado pelo núcleo.
                    fixed = mapOf("pcsx_rearmed_memcard1" to "libretro", "pcsx_rearmed_memcard2" to "shared"),
                ),
                CoreInfo(
                    "swanstation", "SwanStation", R.string.core_swanstation,
                    needsBios = true,
                    defaults = mapOf(
                        "swanstation_GPU_ResolutionScale" to "2", "swanstation_GPU_Renderer" to "OpenGL",
                        // Pula a animação da BIOS ao abrir o jogo.
                        "swanstation_BIOS_PatchFastBoot" to "true",
                    ),
                    presets = mapOf(
                        Preset.PERFORMANCE to mapOf("swanstation_GPU_ResolutionScale" to "1"),
                        Preset.BALANCED to mapOf("swanstation_GPU_ResolutionScale" to "2"),
                        Preset.QUALITY to mapOf("swanstation_GPU_ResolutionScale" to "4", "swanstation_GPU_PGXPEnable" to "true"),
                    ),
                    // O cartão 2 vem vazio no núcleo: com "Shared" é saves/psx/duckstation_shared_card_2.mcd.
                    fixed = mapOf("swanstation_MemoryCards_Card1Type" to "Libretro", "swanstation_MemoryCards_Card2Type" to "Shared"),
                ),
            ),
            layout = PadLayouts.PSX, accent = 0xFF9E9E9E,
            libretroDbName = "Sony - PlayStation",
            bios = listOf(
                BiosFile("scph5501.bin", R.string.bios_scph5501_bin, "490f666e1afb15b7362b406ed1cea246", required = false),
                BiosFile("scph5500.bin", R.string.bios_scph5500_bin, "8dd7d5296a650fac7319bce665a6a53c", required = false),
                BiosFile("scph5502.bin", R.string.bios_scph5502_bin, "32736f17079d0b2b7024407c39bd3050", required = false),
            ),
            multiDisc = true,
            // .img também é imagem de disquete/cartão SD/firmware: só dentro de uma pasta "psx".
            folderOnlyExtensions = setOf("img"),
        ),
        GameSystem(
            id = "ps2", name = "PlayStation 2", shortName = "PS2",
            manufacturer = "Sony", year = 2000,
            extensions = setOf("iso", "chd", "cso", "bin", "cue"),
            cores = listOf(
                CoreInfo(
                    "play", "Play!", R.string.core_play, experimental = true, needsRealPath = true, relaxedGlesVersion = true,
                    saveStates = false,
                    // O Play! desenha com GLES 3 (é o único PS2 daqui que usa a GPU): o custo é o preenchimento, e a
                    // resolução é o que o nível escolhe. Valores da lista do próprio núcleo ("1x|2x|4x|8x").
                    defaults = mapOf("play_res_multi" to "1x"),
                    presets = mapOf(
                        Preset.PERFORMANCE to mapOf("play_res_multi" to "1x"),
                        Preset.BALANCED to mapOf("play_res_multi" to "2x"),
                        Preset.QUALITY to mapOf("play_res_multi" to "4x"),
                    ),
                ),
                CoreInfo(
                    "pcsx2", "LRPS2 (PCSX2)", R.string.core_pcsx2, experimental = true, systemAssets = listOf(LRPS2_ASSETS),
                    needsBios = true,
                    // Lê o disco primeiro com o próprio I/O: o caminho virtual do SAF não abriu no 0.6.3. O binário tem uma VFS
                    // híbrida (pede GET_VFS_INTERFACE v5..v1 e usa a do frontend como reserva, visto no Ghidra em 2026-10-09);
                    // se um teste com jogo de pasta SAF e BIOS real mostrar que ela abre o disco, dá para tirar o needsRealPath.
                    needsRealPath = true,
                    // Vulkan pela ponte do LibretroDroid (o quadro vai por um AHardwareBuffer para o GLES): é o renderizador
                    // de GPU do núcleo que o app consegue servir (o OpenGL dele é o de desktop, e exige GL 4.2).
                    vulkan = true,
                    // Os níveis mexem no que pesa na GPU (resolução, mistura de cores, leitura de volta ao CPU) e, no
                    // leve, poupam a CPU (salto de ciclos do EE, acesso rápido ao disco). O resto (EE e VU1 recompilados,
                    // MTVU, fastmem) já vem ligado no núcleo. Os valores são os da lista do próprio núcleo.
                    // A leitura de volta sem sincronizar vale até o equilibrado: a "Accurate" para a CPU esperando a GPU a
                    // cada leitura, e no celular isso derruba a velocidade em menus e telas de salvar (o núcleo avisa que o
                    // erro, sem ela, aparece só em poucos jogos). Fica a exata no nível de qualidade.
                    presets = mapOf(
                        Preset.PERFORMANCE to mapOf(
                            "pcsx2_upscale_multiplier" to "1x (Native)", "pcsx2_blending_accuracy" to "Minimum",
                            "pcsx2_hw_download_mode" to "Unsynchronized", "pcsx2_ee_cycle_skip" to "Mild Underclock", "pcsx2_fastcdvd" to "enabled",
                        ),
                        Preset.BALANCED to mapOf("pcsx2_upscale_multiplier" to "2x", "pcsx2_hw_download_mode" to "Unsynchronized"),
                        Preset.QUALITY to mapOf("pcsx2_upscale_multiplier" to "4x", "pcsx2_blending_accuracy" to "Medium"),
                    ),
                    // O que depende do aparelho: o renderizador (Vulkan quando o aparelho o tem; senão o por software, o
                    // único que não pede GPU) e, no software, o teto de 1x e as threads do rasterizador, que somam às do
                    // EE, do VU1 (MTVU) e do GS: sobram os núcleos rápidos além desses três (no máximo uma em CPU fraca).
                    deviceOptions = { d ->
                        if (d.vulkan) {
                            mapOf("pcsx2_renderer" to "Vulkan")
                        } else {
                            val spare = (d.fastCores - 3).coerceIn(0, 4)
                            mapOf(
                                "pcsx2_renderer" to "Software (SW)",
                                "pcsx2_upscale_multiplier" to "1x (Native)",
                                "pcsx2_sw_renderer_threads" to (if (d.tier == DeviceTier.ENTRY) minOf(spare, 1) else spare).toString(),
                            )
                        }
                    },
                    // Compartilhados, os dois cartões ficam em system/pcsx2/memcards; por jogo, só existe o 1.
                    fixed = mapOf("pcsx2_shared_memory_cards" to "enabled"),
                ),
                CoreInfo(
                    "armsx2", "ARMSX2", R.string.core_armsx2, experimental = true,
                    // Pede GLES 3.2 mas aceita 3.0: sem isto o LibretroDroid recusa o contexto em aparelhos GLES 3.1 (justo os sem Vulkan 1.1).
                    relaxedGlesVersion = true,
                    // Sem BIOS real o PCSX2 não abre jogo nenhum (ele pega a primeira válida de system/pcsx2/bios).
                    needsBios = true,
                    // Sem needsRealPath: o núcleo lê o disco pela VFS do frontend (versão 3), feita para o SAF do Android.
                    // Sem systemAssets: shaders e GameIndex.yaml vêm dentro do .so (lê os de pcsx2/resources só se existirem).
                    // Os cartões de memória ficam em system/pcsx2/memcards, compartilhados por todos os jogos (o padrão do PCSX2).
                    // Só existe para arm64-v8a no buildbot: nas outras arquiteturas o download diz que não há versão.
                    vulkan = true,
                    // Mesmos níveis do LRPS2, com os valores da lista do ARMSX2 (o salto de ciclos é "mild", não "Mild Underclock").
                    presets = mapOf(
                        Preset.PERFORMANCE to mapOf(
                            "armsx2_upscale" to "1x", "armsx2_blending_accuracy" to "Minimum",
                            "armsx2_hw_download_mode" to "Unsynchronized", "armsx2_ee_cycle_skip" to "mild",
                        ),
                        Preset.BALANCED to mapOf("armsx2_upscale" to "2x", "armsx2_hw_download_mode" to "Unsynchronized"),
                        Preset.QUALITY to mapOf("armsx2_upscale" to "4x", "armsx2_blending_accuracy" to "Medium"),
                    ),
                    // Sem Vulkan, o OpenGL dele roda no GLES do Android (pede 3.2, aceita até 3.0): ao contrário do LRPS2,
                    // continua na GPU. Se o contexto pedido for recusado, o próprio núcleo tenta o outro.
                    deviceOptions = { d -> mapOf("armsx2_renderer" to if (d.vulkan) "Vulkan" else "OpenGL") },
                ),
            ),
            layout = PadLayouts.PS2, accent = 0xFF1E88E5,
            libretroDbName = "Sony - PlayStation 2",
            bios = listOf(BiosFile("pcsx2/bios/scph39001.bin", R.string.bios_pcsx2_bios_scph39001_bin, required = false, format = BiosFormat.PS2)),
            experimental = true,
        ),
        GameSystem(
            id = "psp", name = "PlayStation Portable", shortName = "PSP",
            manufacturer = "Sony", year = 2004,
            extensions = setOf("iso", "cso", "pbp", "chd"),
            cores = listOf(
                CoreInfo(
                    "ppsspp", "PPSSPP", R.string.core_ppsspp,
                    // Vulkan pela ponte do LibretroDroid quando o aparelho a sustenta (o backend "auto" do núcleo seguiria o
                    // renderizador preferido, que é o GLES; só o valor explícito pede Vulkan).
                    vulkan = true,
                    deviceOptions = { d -> mapOf("ppsspp_backend" to if (d.vulkan) "vulkan" else "opengl") },
                    defaults = mapOf("ppsspp_internal_resolution" to "960x544", "ppsspp_frameskip" to "disabled"),
                    presets = mapOf(
                        // O auto frameskip do PPSSPP só vale com ppsspp_frameskip > 0 (com 0 ele nem entra no cálculo) e
                        // pula quando o jogo atrasa em relação ao relógio, sem depender do áudio. As três opções de "hacks"
                        // são as que o núcleo dá como aceleração: leitura da GPU de volta e cache preguiçoso de texturas
                        // quebram texto/efeitos em poucos jogos, por isso ficam só no nível mais leve.
                        Preset.PERFORMANCE to mapOf(
                            "ppsspp_internal_resolution" to "480x272", "ppsspp_frameskip" to "1", "ppsspp_auto_frameskip" to "enabled",
                            "ppsspp_skip_gpu_readbacks" to "enabled", "ppsspp_lazy_texture_caching" to "enabled",
                        ),
                        Preset.BALANCED to mapOf("ppsspp_internal_resolution" to "960x544"),
                        Preset.QUALITY to mapOf("ppsspp_internal_resolution" to "1920x1088", "ppsspp_texture_anisotropic_filtering" to "16x"),
                    ),
                    systemAssets = listOf(PPSSPP_ASSETS),
                ),
            ),
            layout = PadLayouts.PSP, accent = 0xFF263238,
            libretroDbName = "Sony - PlayStation Portable",
        ),
        GameSystem(
            id = "genesis", name = "Mega Drive / Genesis", shortName = "MD",
            manufacturer = "Sega", year = 1988,
            extensions = setOf("md", "gen", "smd", "bin", "68k", "sgd"),
            cores = listOf(
                CoreInfo("genesis_plus_gx", "Genesis Plus GX", R.string.core_genesis_plus_gx_genesis, deviceOptions = GPGX_FRAMESKIP),
                CoreInfo("picodrive", "PicoDrive", R.string.core_picodrive_genesis, deviceOptions = PICODRIVE_FRAMESKIP),
            ),
            layout = PadLayouts.GENESIS, accent = 0xFF212121,
            libretroDbName = "Sega - Mega Drive - Genesis", lightweight = true,
            // .md é o Markdown de qualquer README: só dentro de uma pasta "megadrive"/"genesis".
            folderOnlyExtensions = setOf("md"),
        ),
        GameSystem(
            id = "segacd", name = "Mega CD / Sega CD", shortName = "SCD",
            manufacturer = "Sega", year = 1991,
            extensions = setOf("cue", "chd", "iso", "m3u"),
            cores = listOf(CoreInfo("genesis_plus_gx", "Genesis Plus GX", R.string.core_genesis_plus_gx_segacd, deviceOptions = GPGX_FRAMESKIP)),
            layout = PadLayouts.GENESIS, accent = 0xFF37474F,
            libretroDbName = "Sega - Mega-CD - Sega CD",
            bios = listOf(
                BiosFile("bios_CD_U.bin", R.string.bios_bios_cd_u_bin, "2efd74e3232ff260e371b99f84024f7f"),
                BiosFile("bios_CD_E.bin", R.string.bios_bios_cd_e_bin, "e66fa1dc5820d254611fdcdba0662372", required = false),
                BiosFile("bios_CD_J.bin", R.string.bios_bios_cd_j_bin, "278a9397d192149e84e820ac621a8edd", required = false),
            ),
            multiDisc = true,
        ),
        GameSystem(
            id = "32x", name = "Sega 32X", shortName = "32X",
            manufacturer = "Sega", year = 1994,
            extensions = setOf("32x"),
            cores = listOf(CoreInfo("picodrive", "PicoDrive", R.string.core_picodrive_32x, deviceOptions = PICODRIVE_FRAMESKIP)),
            layout = PadLayouts.GENESIS, accent = 0xFFC62828,
            libretroDbName = "Sega - 32X",
        ),
        GameSystem(
            id = "sms", name = "Master System", shortName = "SMS",
            manufacturer = "Sega", year = 1985,
            extensions = setOf("sms"),
            cores = listOf(CoreInfo("genesis_plus_gx", "Genesis Plus GX", R.string.core_genesis_plus_gx_sms), CoreInfo("gearsystem", "Gearsystem", R.string.core_gearsystem)),
            layout = PadLayouts.MASTER_SYSTEM, accent = 0xFF0D47A1,
            libretroDbName = "Sega - Master System - Mark III", lightweight = true,
        ),
        GameSystem(
            id = "gg", name = "Game Gear", shortName = "GG",
            manufacturer = "Sega", year = 1990,
            extensions = setOf("gg"),
            cores = listOf(CoreInfo("genesis_plus_gx", "Genesis Plus GX", R.string.core_genesis_plus_gx_gg), CoreInfo("gearsystem", "Gearsystem", R.string.core_gearsystem)),
            layout = PadLayouts.MASTER_SYSTEM, accent = 0xFF00838F,
            libretroDbName = "Sega - Game Gear", lightweight = true,
        ),
        GameSystem(
            id = "saturn", name = "Sega Saturn", shortName = "SAT",
            manufacturer = "Sega", year = 1994,
            extensions = setOf("cue", "chd", "ccd", "m3u", "iso", "mds"),
            cores = listOf(
                CoreInfo(
                    "yabasanshiro", "YabaSanshiro", R.string.core_yabasanshiro,
                    defaults = mapOf("yabasanshiro_resolution_mode" to "2x"),
                    // Resolução (vale no próximo início: a opção do núcleo diz "restart"). Valores da lista do núcleo.
                    presets = mapOf(
                        Preset.PERFORMANCE to mapOf("yabasanshiro_resolution_mode" to "original"),
                        Preset.BALANCED to mapOf("yabasanshiro_resolution_mode" to "2x"),
                        Preset.QUALITY to mapOf("yabasanshiro_resolution_mode" to "4x"),
                    ),
                    // O Auto vai no máximo até 2x, o padrão de antes dos níveis.
                    autoMax = Preset.BALANCED,
                ),
                CoreInfo("mednafen_saturn", "Beetle Saturn", R.string.core_mednafen_saturn, experimental = true),
            ),
            layout = PadLayouts.SATURN, accent = 0xFF455A64,
            libretroDbName = "Sega - Saturn",
            bios = listOf(
                BiosFile("saturn_bios.bin", R.string.bios_saturn_bios_bin, "af5828fdff51384f99b3c4926be27762", group = "saturn"),
                BiosFile("sega_101.bin", R.string.bios_sega_101_bin, "85ec9ca47d8f6807718151cbcca8b964", group = "saturn"),
                BiosFile("mpr-17933.bin", R.string.bios_mpr_17933_bin, "3240872c70984b6cbfda1586cab68dbe", group = "saturn"),
            ),
            multiDisc = true,
        ),
        GameSystem(
            id = "dreamcast", name = "Dreamcast", shortName = "DC",
            manufacturer = "Sega", year = 1998,
            extensions = setOf("cdi", "gdi", "chd", "cue", "m3u"),
            cores = listOf(
                CoreInfo(
                    "flycast", "Flycast", R.string.core_flycast,
                    // Renderização em thread separada deixa a tela preta em frontends sem contexto GL compartilhado
                    // (caso do LibretroDroid); desligada, o Flycast desenha no mesmo thread do retro_run.
                    defaults = mapOf(
                        "reicast_internal_resolution" to "1280x960", "reicast_threaded_rendering" to "disabled",
                        // Os valores "normais" do núcleo, fixados para o que os níveis mudam valer igual em qualquer compilação.
                        "reicast_alpha_sorting" to "per-triangle (normal)", "reicast_enable_dsp" to "enabled", "reicast_gdrom_fast_loading" to "disabled",
                    ),
                    presets = mapOf(
                        // reicast_auto_skip_frame só age com a renderização em thread (desligada acima), então o salto de
                        // quadros segue fixo no nível leve. A classificação de transparências por faixa, o DSP desligado e o
                        // carregamento rápido do GD-ROM são os padrões da compilação "LOW_END" do próprio núcleo.
                        Preset.PERFORMANCE to mapOf(
                            "reicast_internal_resolution" to "640x480", "reicast_frame_skipping" to "1",
                            "reicast_alpha_sorting" to "per-strip (fast, least accurate)", "reicast_enable_dsp" to "disabled",
                            "reicast_gdrom_fast_loading" to "enabled",
                        ),
                        Preset.BALANCED to mapOf("reicast_internal_resolution" to "1280x960", "reicast_gdrom_fast_loading" to "enabled"),
                        Preset.QUALITY to mapOf("reicast_internal_resolution" to "1920x1440", "reicast_anisotropic_filtering" to "4"),
                    ),
                    portDevice = RETRO_DEVICE_JOYPAD,
                ),
            ),
            layout = PadLayouts.DREAMCAST, accent = 0xFFFF6F00,
            libretroDbName = "Sega - Dreamcast",
            bios = listOf(
                BiosFile("dc/dc_boot.bin", R.string.bios_dc_dc_boot_bin, "e10c53c2f8b90bab96ead2d368858623", required = false),
                BiosFile("dc/dc_flash.bin", R.string.bios_dc_dc_flash_bin, "0a93f7940c455905bea6e392dfde92a4", required = false),
            ),
            multiDisc = true,
        ),
        GameSystem(
            id = "arcade", name = "Arcade (FinalBurn Neo / MAME)", shortName = "ARC",
            manufacturer = "Vários", year = 1978,
            extensions = setOf("zip", "7z"),
            cores = listOf(
                CoreInfo("fbneo", "FinalBurn Neo", R.string.core_fbneo),
                CoreInfo("mame2003_plus", "MAME 2003-Plus", R.string.core_mame2003_plus),
            ),
            layout = PadLayouts.ARCADE, accent = 0xFFFFD600,
            libretroDbName = "FBNeo - Arcade Games", keepArchives = true,
            bios = listOf(BiosFile("neogeo.zip", R.string.bios_neogeo_zip, required = false)),
        ),
        GameSystem(
            id = "pce", name = "PC Engine / TurboGrafx-16", shortName = "PCE",
            manufacturer = "NEC", year = 1987,
            extensions = setOf("pce", "sgx", "cue", "ccd", "chd", "toc"),
            cores = listOf(CoreInfo("mednafen_pce_fast", "Beetle PCE Fast", R.string.core_mednafen_pce_fast), CoreInfo("geargrafx", "Geargrafx", R.string.core_geargrafx)),
            layout = PadLayouts.PC_ENGINE, accent = 0xFFEF6C00,
            libretroDbName = "NEC - PC Engine - TurboGrafx 16", lightweight = true,
            bios = listOf(BiosFile("syscard3.pce", R.string.bios_syscard3_pce, "38179df8f4ac870017db21ebcbf53114", required = false)),
        ),
        GameSystem(
            id = "atari2600", name = "Atari 2600", shortName = "2600",
            manufacturer = "Atari", year = 1977,
            extensions = setOf("a26", "bin"),
            cores = listOf(CoreInfo("stella2014", "Stella 2014", R.string.core_stella2014), CoreInfo("stella", "Stella", R.string.core_stella)),
            layout = PadLayouts.ATARI, accent = 0xFF6D4C41,
            libretroDbName = "Atari - 2600", lightweight = true,
        ),
        GameSystem(
            id = "atari7800", name = "Atari 7800", shortName = "7800",
            manufacturer = "Atari", year = 1986,
            extensions = setOf("a78", "bin"),
            cores = listOf(CoreInfo("prosystem", "ProSystem", R.string.core_prosystem)),
            layout = PadLayouts.ATARI, accent = 0xFF8D6E63,
            libretroDbName = "Atari - 7800",
        ),
        GameSystem(
            id = "lynx", name = "Atari Lynx", shortName = "LYNX",
            manufacturer = "Atari", year = 1989,
            extensions = setOf("lnx", "o"),
            cores = listOf(CoreInfo("handy", "Handy", R.string.core_handy)),
            layout = PadLayouts.HANDHELD_2, accent = 0xFFF9A825,
            libretroDbName = "Atari - Lynx",
            bios = listOf(BiosFile("lynxboot.img", R.string.bios_lynxboot_img, "fcd403db69f54290b51035d82f835e7b", required = false)),
            // .o é arquivo objeto de compilador: só dentro de uma pasta "lynx".
            folderOnlyExtensions = setOf("o"),
        ),
        GameSystem(
            id = "ngp", name = "Neo Geo Pocket / Color", shortName = "NGP",
            manufacturer = "SNK", year = 1998,
            extensions = setOf("ngp", "ngc"),
            cores = listOf(CoreInfo("mednafen_ngp", "Beetle NeoPop", R.string.core_mednafen_ngp)),
            layout = PadLayouts.HANDHELD_2, accent = 0xFF00897B, orientation = Orientation.PORTRAIT,
            libretroDbName = "SNK - Neo Geo Pocket Color",
            // Os jogos do modelo monocromático (.ngp, padrão No-Intro) têm capas num acervo à parte.
            libretroDbByExtension = mapOf("ngp" to "SNK - Neo Geo Pocket"),
        ),
        GameSystem(
            id = "wswan", name = "WonderSwan / Color", shortName = "WS",
            manufacturer = "Bandai", year = 1999,
            extensions = setOf("ws", "wsc"),
            cores = listOf(CoreInfo("mednafen_wswan", "Beetle Cygne", R.string.core_mednafen_wswan)),
            layout = PadLayouts.HANDHELD_2, accent = 0xFF3949AB,
            libretroDbName = "Bandai - WonderSwan Color",
            // Os jogos do modelo monocromático (.ws) têm capas num acervo à parte.
            libretroDbByExtension = mapOf("ws" to "Bandai - WonderSwan"),
        ),
        GameSystem(
            id = "vb", name = "Virtual Boy", shortName = "VB",
            manufacturer = "Nintendo", year = 1995,
            extensions = setOf("vb", "vboy"),
            cores = listOf(CoreInfo("mednafen_vb", "Beetle VB", R.string.core_mednafen_vb)),
            layout = PadLayouts.GBA, accent = 0xFFB71C1C,
            libretroDbName = "Nintendo - Virtual Boy",
            // .vb é código-fonte de Visual Basic: só dentro de uma pasta "virtualboy"/"vb".
            folderOnlyExtensions = setOf("vb"),
        ),
        GameSystem(
            id = "coleco", name = "ColecoVision", shortName = "CV",
            manufacturer = "Coleco", year = 1982,
            extensions = setOf("col", "cv"),
            cores = listOf(CoreInfo("gearcoleco", "Gearcoleco", R.string.core_gearcoleco)),
            layout = PadLayouts.HANDHELD_2, accent = 0xFF5D4037,
            libretroDbName = "Coleco - ColecoVision",
            bios = listOf(BiosFile("colecovision.rom", R.string.bios_colecovision_rom, "2c66f5911e5b42b8ebe113403548eee7")),
            // .col também é paleta de cores de programas de desenho: só dentro de uma pasta "coleco".
            folderOnlyExtensions = setOf("col"),
        ),
        GameSystem(
            id = "pokemini", name = "Pokémon Mini", shortName = "PM",
            manufacturer = "Nintendo", year = 2001,
            extensions = setOf("min"),
            cores = listOf(CoreInfo("pokemini", "PokeMini", R.string.core_pokemini)),
            layout = PadLayouts.HANDHELD_2, accent = 0xFFFDD835, orientation = Orientation.PORTRAIT,
            libretroDbName = "Nintendo - Pokemon Mini",
        ),
        // ---- Nintendo (portáteis extras) ----
        GameSystem(
            id = "gw", name = "Game & Watch", shortName = "G&W",
            manufacturer = "Nintendo", year = 1980,
            extensions = setOf("mgw"),
            cores = listOf(CoreInfo("gw", "Game & Watch", R.string.core_gw)),
            layout = PadLayouts.NES, accent = 0xFFB0BEC5,
            libretroDbName = "Handheld Electronic Game",
        ),

        // ---- Sega ----
        GameSystem(
            id = "sg1000", name = "Sega SG-1000", shortName = "SG",
            manufacturer = "Sega", year = 1983,
            extensions = setOf("sg"),
            cores = listOf(CoreInfo("genesis_plus_gx", "Genesis Plus GX", R.string.core_genesis_plus_gx_sg1000), CoreInfo("gearsystem", "Gearsystem", R.string.core_gearsystem)),
            layout = PadLayouts.MASTER_SYSTEM, accent = 0xFF1565C0,
            libretroDbName = "Sega - SG-1000", lightweight = true,
        ),

        // ---- Atari ----
        GameSystem(
            id = "a5200", name = "Atari 5200", shortName = "5200",
            manufacturer = "Atari", year = 1982,
            extensions = setOf("a52"),
            cores = listOf(CoreInfo("a5200", "a5200", R.string.core_a5200), CoreInfo("atari800", "Atari800", R.string.core_atari800_a5200)),
            layout = PadLayouts.ATARI_5200, accent = 0xFF795548,
            libretroDbName = "Atari - 5200", lightweight = true,
            bios = listOf(BiosFile("5200.rom", R.string.bios_5200_rom, "281f20ea4320404ec820fb7ec0693b38")),
        ),
        GameSystem(
            id = "a800", name = "Atari 400/800/XL/XE", shortName = "A800",
            manufacturer = "Atari", year = 1979,
            extensions = setOf("xex", "atr", "atx", "cas", "car", "xfd", "dcm"),
            cores = listOf(CoreInfo("atari800", "Atari800", R.string.core_atari800_a800)),
            layout = PadLayouts.ATARI_8BIT, accent = 0xFFA1887F,
            libretroDbName = "Atari - 8-bit",
            bios = listOf(
                BiosFile("ATARIXL.ROM", R.string.bios_atarixl_rom, required = false),
                BiosFile("ATARIBAS.ROM", R.string.bios_ataribas_rom, required = false),
                BiosFile("ATARIOSB.ROM", R.string.bios_atariosb_rom, required = false),
            ),
        ),
        GameSystem(
            id = "jaguar", name = "Atari Jaguar", shortName = "JAG",
            manufacturer = "Atari", year = 1993,
            extensions = setOf("j64", "jag", "abs", "cof"),
            cores = listOf(CoreInfo("virtualjaguar", "Virtual Jaguar", R.string.core_virtualjaguar)),
            layout = PadLayouts.JAGUAR, accent = 0xFFB71C1C,
            libretroDbName = "Atari - Jaguar",
            // .abs e .cof são formatos genéricos de binário/objeto de compilador: só dentro de uma pasta "jaguar".
            folderOnlyExtensions = setOf("abs", "cof"),
        ),

        // ---- SNK / NEC / 3DO ----
        GameSystem(
            id = "neocd", name = "Neo Geo CD", shortName = "NGCD",
            manufacturer = "SNK", year = 1994,
            extensions = setOf("cue", "chd"),
            cores = listOf(CoreInfo("neocd", "NeoCD", R.string.core_neocd)),
            layout = PadLayouts.NEO_GEO, accent = 0xFFFFA000,
            libretroDbName = "SNK - Neo Geo CD",
            bios = listOf(
                BiosFile("neocd/000-lo.lo", R.string.bios_neocd_000_lo_lo, "fc7599f3f871578fe9a0453662d1c966"),
                BiosFile("neocd/neocd_f.rom", R.string.bios_neocd_neocd_f_rom, group = "neocd"),
                BiosFile("neocd/neocd_t.rom", R.string.bios_neocd_neocd_t_rom, group = "neocd"),
                BiosFile("neocd/neocd_z.rom", R.string.bios_neocd_neocd_z_rom, group = "neocd"),
                BiosFile("neocd/neocd_sf.rom", R.string.bios_neocd_neocd_sf_rom, group = "neocd"),
                BiosFile("neocd/neocd_st.rom", R.string.bios_neocd_neocd_st_rom, group = "neocd"),
                BiosFile("neocd/neocd_sz.rom", R.string.bios_neocd_neocd_sz_rom, group = "neocd"),
                BiosFile("neocd/uni-bioscd.rom", R.string.bios_neocd_uni_bioscd_rom, group = "neocd"),
            ),
        ),
        GameSystem(
            id = "pcfx", name = "NEC PC-FX", shortName = "PCFX",
            manufacturer = "NEC", year = 1994,
            extensions = setOf("cue", "ccd", "toc", "chd"),
            cores = listOf(CoreInfo("mednafen_pcfx", "Beetle PC-FX", R.string.core_mednafen_pcfx, portDevice = RETRO_DEVICE_JOYPAD)),
            layout = PadLayouts.PC_FX, accent = 0xFF5E35B1,
            libretroDbName = "NEC - PC-FX",
            bios = listOf(BiosFile("pcfx.rom", R.string.bios_pcfx_rom, "08e36edbea28a017f79f8d4f7ff9b6d7")),
        ),
        GameSystem(
            id = "3do", name = "3DO Interactive Multiplayer", shortName = "3DO",
            manufacturer = "Panasonic", year = 1993,
            extensions = setOf("iso", "cue", "chd"),
            cores = listOf(CoreInfo("opera", "Opera", R.string.core_opera, portDevice = RETRO_DEVICE_JOYPAD)),
            layout = PadLayouts.THREE_DO, accent = 0xFFC62828,
            libretroDbName = "The 3DO Company - 3DO",
            bios = listOf(
                BiosFile("panafz10.bin", R.string.bios_panafz10_bin, "51f2f43ae2f3508a14d9f56597e2d3ce", group = "3do"),
                BiosFile("panafz1.bin", R.string.bios_panafz1_bin, group = "3do"),
                BiosFile("goldstar.bin", R.string.bios_goldstar_bin, group = "3do"),
            ),
            multiDisc = true,
        ),

        // ---- Consoles clássicos ----
        GameSystem(
            id = "vectrex", name = "Vectrex", shortName = "VEC",
            manufacturer = "GCE", year = 1982,
            extensions = setOf("vec", "gam"),
            cores = listOf(CoreInfo("vecx", "vecx", R.string.core_vecx)),
            layout = PadLayouts.VECTREX, accent = 0xFF00E5FF, orientation = Orientation.PORTRAIT,
            libretroDbName = "GCE - Vectrex",
        ),
        GameSystem(
            id = "intv", name = "Intellivision", shortName = "INTV",
            manufacturer = "Mattel", year = 1979,
            extensions = setOf("int", "itv"),
            cores = listOf(CoreInfo("freeintv", "FreeIntv", R.string.core_freeintv)),
            layout = PadLayouts.INTELLIVISION, accent = 0xFF8D6E63,
            libretroDbName = "Mattel - Intellivision",
            bios = listOf(
                BiosFile("exec.bin", R.string.bios_exec_bin, "62e761035cb657903761800f4437b8af"),
                BiosFile("grom.bin", R.string.bios_grom_bin, "0cd5946c6473e42e8e4c2137785e427f"),
            ),
            // .int aparece em arquivos intermediários e de dados de vários programas: só dentro de uma pasta "intv".
            folderOnlyExtensions = setOf("int"),
        ),
        GameSystem(
            id = "odyssey2", name = "Odyssey² / Videopac", shortName = "O²",
            manufacturer = "Magnavox", year = 1978,
            extensions = setOf("bin"),
            cores = listOf(CoreInfo("o2em", "O2EM", R.string.core_o2em)),
            layout = PadLayouts.TWO_BUTTONS, accent = 0xFF43A047,
            libretroDbName = "Magnavox - Odyssey2",
            bios = listOf(BiosFile("o2rom.bin", R.string.bios_o2rom_bin, "562d5ebf9e030a40d6fabfc2f33139fd")),
        ),
        GameSystem(
            id = "channelf", name = "Fairchild Channel F", shortName = "CHF",
            manufacturer = "Fairchild", year = 1976,
            extensions = setOf("chf", "bin"),
            cores = listOf(CoreInfo("freechaf", "FreeChaF", R.string.core_freechaf)),
            layout = PadLayouts.FOUR_BUTTONS, accent = 0xFFFF7043,
            libretroDbName = "Fairchild - Channel F",
            bios = listOf(
                BiosFile("sl31253.bin", R.string.bios_sl31253_bin, "ac9804d4c0e9d07e33472e3726ed15c3"),
                BiosFile("sl31254.bin", R.string.bios_sl31254_bin, "da98f4bb3242ab80d76629021bb27585"),
                BiosFile("sl90025.bin", R.string.bios_sl90025_bin, "95d339631d867c8f1d15a5f2ec26069d", required = false),
            ),
        ),

        // ---- Portáteis de outras marcas ----
        GameSystem(
            id = "supervision", name = "Watara Supervision", shortName = "SV",
            manufacturer = "Watara", year = 1992,
            extensions = setOf("sv"),
            cores = listOf(CoreInfo("potator", "Potator", R.string.core_potator)),
            layout = PadLayouts.HANDHELD_2, accent = 0xFF78909C, orientation = Orientation.PORTRAIT,
            libretroDbName = "Watara - Supervision",
            // .sv é código-fonte de SystemVerilog: só dentro de uma pasta "supervision".
            folderOnlyExtensions = setOf("sv"),
        ),
        GameSystem(
            id = "megaduck", name = "Mega Duck / Cougar Boy", shortName = "DUCK",
            manufacturer = "Welback", year = 1993,
            extensions = setOf("bin"),
            cores = listOf(CoreInfo("sameduck", "SameDuck", R.string.core_sameduck, needsRealPath = true)),
            layout = PadLayouts.GAMEBOY, accent = 0xFFFFCA28, orientation = Orientation.PORTRAIT,
        ),
        GameSystem(
            id = "arduboy", name = "Arduboy", shortName = "ARDU",
            manufacturer = "Arduboy", year = 2016,
            extensions = setOf("hex", "arduboy"),
            cores = listOf(CoreInfo("arduous", "Arduous", R.string.core_arduous), CoreInfo("ardens", "Ardens", R.string.core_ardens)),
            layout = PadLayouts.TWO_BUTTONS, accent = 0xFF26C6DA,
            libretroDbName = "Arduboy Inc - Arduboy",
            // .hex é o formato Intel HEX de qualquer microcontrolador: só dentro de uma pasta "arduboy".
            folderOnlyExtensions = setOf("hex"),
        ),

        // ---- Computadores ----
        GameSystem(
            id = "msx", name = "MSX / MSX2", shortName = "MSX",
            manufacturer = "Microsoft/ASCII", year = 1983,
            extensions = setOf("rom", "ri", "mx1", "mx2", "dsk", "cas"),
            cores = listOf(
                CoreInfo("bluemsx", "blueMSX", R.string.core_bluemsx, systemAssets = listOf(BLUEMSX_ASSETS)),
                CoreInfo("fmsx", "fMSX", R.string.core_fmsx, portDevice = FMSX_JOYSTICK_KEYBOARD),
            ),
            layout = PadLayouts.COMPUTER, accent = 0xFF3949AB,
            libretroDbName = "Microsoft - MSX",
            bios = listOf(
                BiosFile("MSX.ROM", R.string.bios_msx_rom, required = false),
                BiosFile("MSX2.ROM", R.string.bios_msx2_rom, required = false),
                BiosFile("MSX2EXT.ROM", R.string.bios_msx2ext_rom, required = false),
            ),
            // .rom é o nome de qualquer dump (BIOS de outros consoles, firmware, a própria MSX.ROM): só dentro de uma pasta "msx".
            folderOnlyExtensions = setOf("rom"),
        ),
        GameSystem(
            id = "c64", name = "Commodore 64", shortName = "C64",
            manufacturer = "Commodore", year = 1982,
            extensions = setOf("d64", "d71", "d81", "g64", "x64", "t64", "tap", "prg", "p00", "crt"),
            cores = listOf(
                CoreInfo("vice_x64", "VICE x64", R.string.core_vice_x64, portDevice = RETRO_DEVICE_JOYPAD),
                CoreInfo("vice_x64sc", "VICE x64sc", R.string.core_vice_x64sc, portDevice = RETRO_DEVICE_JOYPAD),
            ),
            layout = PadLayouts.COMPUTER, accent = 0xFF7986CB,
            libretroDbName = "Commodore - 64",
            // .crt também é certificado (X.509) e .prg, programa de vários sistemas: só dentro de uma pasta "c64".
            folderOnlyExtensions = setOf("crt", "prg"),
        ),
        GameSystem(
            id = "amiga", name = "Commodore Amiga", shortName = "AMIGA",
            manufacturer = "Commodore", year = 1985,
            extensions = setOf("adf", "adz", "dms", "ipf", "hdf", "hdz", "lha", "uae"),
            cores = listOf(CoreInfo("puae", "PUAE", R.string.core_puae, portDevice = RETRO_DEVICE_JOYPAD)),
            layout = PadLayouts.COMPUTER, accent = 0xFFE53935,
            libretroDbName = "Commodore - Amiga",
            bios = listOf(
                BiosFile("kick34005.A500", R.string.bios_kick34005_a500, "82a21c1890cae844b3df741f2762d48d", required = false),
                BiosFile("kick40068.A1200", R.string.bios_kick40068_a1200, "646773759326fbac3b2311fd8c8793ee", required = false),
            ),
        ),
        GameSystem(
            id = "zxspectrum", name = "ZX Spectrum", shortName = "ZX",
            manufacturer = "Sinclair", year = 1982,
            extensions = setOf("tzx", "tap", "z80", "sna", "szx", "trd", "scl", "rzx"),
            cores = listOf(CoreInfo("fuse", "Fuse", R.string.core_fuse)),
            layout = PadLayouts.COMPUTER, accent = 0xFFD50000,
            libretroDbName = "Sinclair - ZX Spectrum",
        ),
        GameSystem(
            id = "cpc", name = "Amstrad CPC", shortName = "CPC",
            manufacturer = "Amstrad", year = 1984,
            extensions = setOf("dsk", "cdt", "cpr", "sna"),
            cores = listOf(CoreInfo("cap32", "Caprice32", R.string.core_cap32, portDevice = RETRO_DEVICE_JOYPAD), CoreInfo("crocods", "CrocoDS", R.string.core_crocods)),
            layout = PadLayouts.COMPUTER, accent = 0xFF00897B,
            libretroDbName = "Amstrad - CPC",
        ),
        GameSystem(
            id = "dos", name = "MS-DOS", shortName = "DOS",
            manufacturer = "PC", year = 1981,
            extensions = setOf("zip", "dosz", "exe", "com", "bat", "conf"),
            cores = listOf(CoreInfo("dosbox_pure", "DOSBox Pure", R.string.core_dosbox_pure)),
            layout = PadLayouts.COMPUTER, accent = 0xFF607D8B,
            libretroDbName = "DOS", keepArchives = true,
            // .com/.bat/.conf aparecem em qualquer pasta de programas: só dentro de uma pasta "dos".
            folderOnlyExtensions = setOf("com", "bat", "conf"),
        ),

        // ---- Consoles de fantasia ----
        GameSystem(
            id = "tic80", name = "TIC-80", shortName = "TIC",
            manufacturer = "Nesbox", year = 2017,
            extensions = setOf("tic"),
            cores = listOf(CoreInfo("tic80", "TIC-80", R.string.core_tic80)),
            layout = PadLayouts.FOUR_BUTTONS, accent = 0xFF1DE9B6,
            libretroDbName = "TIC-80",
        ),
        GameSystem(
            id = "pico8", name = "PICO-8", shortName = "P8",
            manufacturer = "Lexaloffle", year = 2015,
            extensions = setOf("p8", "png"),
            cores = listOf(CoreInfo("retro8", "Retro8", R.string.core_retro8)),
            layout = PadLayouts.TWO_BUTTONS, accent = 0xFFFF77A8,
            // Qualquer .png seria tomado por cartucho: só dentro de uma pasta "pico8".
            folderOnlyExtensions = setOf("png"),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): GameSystem? = byId[id]

    /** Sistemas que aceitam uma determinada extensão. */
    fun forExtension(ext: String): List<GameSystem> =
        all.filter { ext.lowercase() in it.extensions }

    /**
     * O único console que aceita a extensão fora de uma pasta de console, ou nulo se ela for
     * ambígua (.bin, .iso…) ou só valer dentro da pasta ([GameSystem.folderOnlyExtensions]).
     */
    fun byUniqueExtension(ext: String): GameSystem? {
        val matches = forExtension(ext)
        return matches.singleOrNull()?.takeIf { ext.lowercase() !in it.folderOnlyExtensions }
    }
}
