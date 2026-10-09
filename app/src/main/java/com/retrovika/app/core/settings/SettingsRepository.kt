package com.retrovika.app.core.settings

import androidx.annotation.StringRes
import com.retrovika.app.R
import android.content.Context
import android.os.Build
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.retrovika.app.core.net.Http
import com.retrovika.app.core.textmem.TextHook
import com.retrovika.app.core.systems.Preset
import com.retrovika.app.core.tuning.TuneResult
import com.retrovika.app.core.cores.SystemBenchmark
import com.retrovika.app.core.translate.GeminiText
import com.retrovika.app.emulation.input.PadProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

// Arquivo corrompido (gravação cortada, cartão com defeito): recomeça dos padrões em vez de falhar toda leitura.
private val Context.dataStore by preferencesDataStore(
    "retrovika_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

enum class ShaderOption(@StringRes val label: Int) {
    DEFAULT(R.string.shader_default), SHARP(R.string.shader_sharp), CRT(R.string.shader_crt), LCD(R.string.shader_lcd)
}

/** Tamanho das capas nas grades de jogos: [minWidth] é a largura mínima de cada coluna, em dp. */
enum class CoverSize(@StringRes val label: Int, val minWidth: Int) {
    COMPACT(R.string.settings_cover_compact, 96), NORMAL(R.string.settings_cover_normal, 118), LARGE(R.string.settings_cover_large, 150)
}

/** Ordem dos jogos na tela de cada console. */
enum class GameSort(@StringRes val label: Int) {
    TITLE(R.string.sort_title), RECENT(R.string.sort_recent), ADDED(R.string.sort_added), PLAYTIME(R.string.sort_playtime)
}

data class AppSettings(
    val linkedFolders: Set<String> = emptySet(),
    val shader: ShaderOption = ShaderOption.DEFAULT,
    val padOpacity: Float = 0.7f,
    val padScale: Float = 1f,
    val haptics: Boolean = true,
    val autoSave: Boolean = true,
    val autoLoad: Boolean = true,
    val fastForwardSpeed: Int = 2,
    val lowLatencyAudio: Boolean = true,
    val hidePadWithController: Boolean = false,
    val onboardingDone: Boolean = false,
    /** URIs de jogos removidos de pastas vinculadas: o rescan não os adiciona de novo. */
    val hiddenGames: Set<String> = emptySet(),
    /** Quantos downloads rodam ao mesmo tempo; os demais esperam na fila. */
    val maxDownloads: Int = 2,
    /** Troca de telas sem animação: mais leve em aparelhos modestos. */
    val reduceMotion: Boolean = false,
    val coverSize: CoverSize = CoverSize.NORMAL,
    val gameSort: GameSort = GameSort.TITLE,
    /** Botão de tradução ao vivo em todos os jogos; sem isso, só nos japoneses. */
    val translateEverywhere: Boolean = false,
    /** Chave do Gemini (Google AI Studio) do usuário: com ela, a tradução da tela usa IA. */
    val geminiKey: String? = null,
    val geminiModel: String = GeminiText.DEFAULT_MODEL,
    /** Na primeira vez que um console roda, testa os núcleos dele e escolhe o ideal para o aparelho. */
    val autoBenchmark: Boolean = true,
    /** Contador de velocidade e quadros por segundo no canto da tela do jogo. */
    val showPerformance: Boolean = false,
    /** Procura uma versão nova do app ao abrir. */
    val checkUpdates: Boolean = true,
    /** Versão cujo aviso o usuário fechou na tela inicial: só volta a aparecer para uma mais nova. */
    val dismissedUpdate: String? = null,
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {

    private object Keys {
        val folders = stringSetPreferencesKey("linked_folders")
        val shader = stringPreferencesKey("shader")
        val padOpacity = floatPreferencesKey("pad_opacity")
        val padScale = floatPreferencesKey("pad_scale")
        val haptics = booleanPreferencesKey("haptics")
        val autoSave = booleanPreferencesKey("auto_save")
        val autoLoad = booleanPreferencesKey("auto_load")
        val ffSpeed = intPreferencesKey("ff_speed")
        val lowLatency = booleanPreferencesKey("low_latency_audio")
        val hidePad = booleanPreferencesKey("hide_pad_controller")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val hidden = stringSetPreferencesKey("hidden_games")
        val maxDownloads = intPreferencesKey("max_downloads")
        val reduceMotion = booleanPreferencesKey("reduce_motion")
        val coverSize = stringPreferencesKey("cover_size")
        val gameSort = stringPreferencesKey("game_sort")
        val translateEverywhere = booleanPreferencesKey("translate_everywhere")
        val geminiKey = stringPreferencesKey("gemini_key")
        val geminiModel = stringPreferencesKey("gemini_model")
        val autoBenchmark = booleanPreferencesKey("auto_benchmark")
        val showPerformance = booleanPreferencesKey("show_performance")
        fun benchmark(systemId: String) = stringPreferencesKey("$BENCH_PREFIX$systemId")
        val checkUpdates = booleanPreferencesKey("check_updates")
        val dismissedUpdate = stringPreferencesKey("dismissed_update")
        fun core(systemId: String) = stringPreferencesKey("core_$systemId")
        fun preset(systemId: String) = stringPreferencesKey("preset_$systemId")
        fun noVulkan(coreId: String) = booleanPreferencesKey("no_vulkan_$coreId")
        fun tuning(systemId: String, coreId: String) = stringPreferencesKey("$TUNE_PREFIX${systemId}_$coreId")
        fun gameTuning(gameId: Long) = stringPreferencesKey("$GAME_TUNE_PREFIX$gameId")
        fun coreOptions(coreId: String) = stringPreferencesKey("core_options_$coreId")
        fun gameCoreOptions(gameId: Long, coreId: String) = stringPreferencesKey("$GAME_OPTIONS_PREFIX${gameId}_$coreId")
        fun gameIni(gameId: Long) = stringPreferencesKey("$GAME_INI_PREFIX$gameId")
        fun gameTextHooks(gameId: Long) = stringPreferencesKey("$GAME_HOOKS_PREFIX$gameId")
        fun gameAutoTranslate(gameId: Long) = booleanPreferencesKey("$GAME_AUTO_PREFIX$gameId")
        fun padProfile(systemId: String) = stringPreferencesKey("pad_console_$systemId")
        fun gamePadProfile(gameId: Long) = stringPreferencesKey("$GAME_PAD_PREFIX$gameId")
    }

    private val optionsSerializer = MapSerializer(String.serializer(), String.serializer())

    // Um erro de leitura do arquivo de preferências não pode derrubar o app na abertura: usa os padrões.
    private val data: Flow<Preferences> = context.dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    /**
     * Segredos (a chave do Gemini) ficam num arquivo próprio, fora do backup do Android (ver
     * res/xml/backup_rules.xml): a chave é do usuário e não deve ir parar na nuvem junto com as preferências.
     */
    private val secrets = context.getSharedPreferences(SECRETS_FILE, Context.MODE_PRIVATE)
    private val geminiKey = MutableStateFlow(secrets.getString(SECRET_GEMINI_KEY, null))

    init {
        // Versões anteriores guardavam a chave no DataStore (que vai para o backup): passa para o arquivo de segredos.
        scope.launch(Dispatchers.IO) {
            runCatching {
                context.dataStore.edit { p ->
                    val old = p[Keys.geminiKey] ?: return@edit
                    if (secrets.getString(SECRET_GEMINI_KEY, null) == null && old.isNotBlank()) {
                        secrets.edit().putString(SECRET_GEMINI_KEY, old.trim()).commit()
                        geminiKey.value = old.trim()
                    }
                    p.remove(Keys.geminiKey)
                }
            }
        }
    }

    val settings: Flow<AppSettings> = combine(data, geminiKey) { p, secretKey ->
        AppSettings(
            linkedFolders = p[Keys.folders].orEmpty(),
            shader = p[Keys.shader]?.let { runCatching { ShaderOption.valueOf(it) }.getOrNull() } ?: ShaderOption.DEFAULT,
            padOpacity = p[Keys.padOpacity] ?: 0.7f,
            padScale = p[Keys.padScale] ?: 1f,
            haptics = p[Keys.haptics] ?: true,
            autoSave = p[Keys.autoSave] ?: true,
            autoLoad = p[Keys.autoLoad] ?: true,
            fastForwardSpeed = p[Keys.ffSpeed] ?: 2,
            lowLatencyAudio = p[Keys.lowLatency] ?: true,
            hidePadWithController = p[Keys.hidePad] ?: false,
            onboardingDone = p[Keys.onboarding] ?: false,
            hiddenGames = p[Keys.hidden].orEmpty(),
            maxDownloads = (p[Keys.maxDownloads] ?: 2).coerceIn(1, MAX_PARALLEL_DOWNLOADS),
            reduceMotion = p[Keys.reduceMotion] ?: false,
            coverSize = p[Keys.coverSize]?.let { runCatching { CoverSize.valueOf(it) }.getOrNull() } ?: CoverSize.NORMAL,
            gameSort = p[Keys.gameSort]?.let { runCatching { GameSort.valueOf(it) }.getOrNull() } ?: GameSort.TITLE,
            translateEverywhere = p[Keys.translateEverywhere] ?: false,
            // A do DataStore só até a migração do init terminar.
            geminiKey = (secretKey ?: p[Keys.geminiKey])?.takeIf { it.isNotBlank() },
            geminiModel = p[Keys.geminiModel]?.takeIf { it.isNotBlank() } ?: GeminiText.DEFAULT_MODEL,
            autoBenchmark = p[Keys.autoBenchmark] ?: true,
            showPerformance = p[Keys.showPerformance] ?: false,
            checkUpdates = p[Keys.checkUpdates] ?: true,
            dismissedUpdate = p[Keys.dismissedUpdate],
        )
    }

    /** Última leitura em memória, para telas que precisam desenhar sem esperar o DataStore. */
    val cached: StateFlow<AppSettings> = settings.stateIn(scope, SharingStarted.Eagerly, AppSettings())

    suspend fun current(): AppSettings = settings.first()

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) { context.dataStore.edit { it[key] = value } }

    suspend fun addFolder(uri: String) = context.dataStore.edit { it[Keys.folders] = it[Keys.folders].orEmpty() + uri }
    suspend fun removeFolder(uri: String) = context.dataStore.edit { p ->
        p[Keys.folders] = p[Keys.folders].orEmpty() - uri
        // Jogos ocultos daquela pasta deixam de importar.
        p[Keys.hidden] = p[Keys.hidden].orEmpty().filterNot { it.startsWith("$uri/") }.toSet()
    }
    suspend fun hideGame(uri: String) = context.dataStore.edit { it[Keys.hidden] = it[Keys.hidden].orEmpty() + uri }
    suspend fun clearHiddenGames() = context.dataStore.edit { it.remove(Keys.hidden) }
    suspend fun setShader(v: ShaderOption) = set(Keys.shader, v.name)
    suspend fun setPadOpacity(v: Float) = set(Keys.padOpacity, v)
    suspend fun setPadScale(v: Float) = set(Keys.padScale, v)
    suspend fun setHaptics(v: Boolean) = set(Keys.haptics, v)
    suspend fun setAutoSave(v: Boolean) = set(Keys.autoSave, v)
    suspend fun setAutoLoad(v: Boolean) = set(Keys.autoLoad, v)
    suspend fun setFastForwardSpeed(v: Int) = set(Keys.ffSpeed, v)
    suspend fun setLowLatencyAudio(v: Boolean) = set(Keys.lowLatency, v)
    suspend fun setHidePadWithController(v: Boolean) = set(Keys.hidePad, v)
    suspend fun setOnboardingDone() = set(Keys.onboarding, true)
    suspend fun setMaxDownloads(v: Int) = set(Keys.maxDownloads, v.coerceIn(1, MAX_PARALLEL_DOWNLOADS))
    suspend fun setReduceMotion(v: Boolean) = set(Keys.reduceMotion, v)
    suspend fun setCoverSize(v: CoverSize) = set(Keys.coverSize, v.name)
    suspend fun setGameSort(v: GameSort) = set(Keys.gameSort, v.name)
    suspend fun setTranslateEverywhere(v: Boolean) = set(Keys.translateEverywhere, v)
    suspend fun setGeminiKey(v: String) {
        val value = v.trim().ifEmpty { null }
        withContext(Dispatchers.IO) {
            val editor = secrets.edit()
            if (value == null) editor.remove(SECRET_GEMINI_KEY) else editor.putString(SECRET_GEMINI_KEY, value)
            editor.commit()
        }
        geminiKey.value = value
        // Uma cópia antiga que a migração ainda não tirou não pode voltar a valer.
        context.dataStore.edit { it.remove(Keys.geminiKey) }
    }
    suspend fun setGeminiModel(v: String) = context.dataStore.edit { p ->
        // "models/gemini-…" é como a própria API lista os modelos; o endereço só quer o id.
        val value = v.trim().removePrefix("models/").trim()
        if (value.isEmpty()) p.remove(Keys.geminiModel) else p[Keys.geminiModel] = value
    }
    suspend fun setAutoBenchmark(v: Boolean) = set(Keys.autoBenchmark, v)
    suspend fun setShowPerformance(v: Boolean) = set(Keys.showPerformance, v)
    suspend fun setCheckUpdates(v: Boolean) = set(Keys.checkUpdates, v)
    suspend fun setDismissedUpdate(version: String) = set(Keys.dismissedUpdate, version)

    /** Volta controle, vídeo, emulação, interface e downloads aos padrões; pastas, jogos ocultos e núcleos escolhidos ficam como estão. */
    suspend fun resetPreferences() = context.dataStore.edit { p ->
        listOf(
            Keys.shader, Keys.padOpacity, Keys.padScale, Keys.haptics, Keys.autoSave, Keys.autoLoad, Keys.ffSpeed, Keys.lowLatency, Keys.hidePad,
            Keys.maxDownloads, Keys.reduceMotion, Keys.coverSize, Keys.gameSort, Keys.translateEverywhere, Keys.autoBenchmark,
            Keys.showPerformance, Keys.checkUpdates,
        ).forEach { p.remove(it) }
    }

    fun coreFor(systemId: String): Flow<String?> = data.map { it[Keys.core(systemId)] }

    /** O núcleo que vai rodar o console: o escolhido pelo usuário; senão, o do teste automático; senão, nulo (o padrão). */
    fun effectiveCoreFor(systemId: String): Flow<String?> = data.map { p ->
        p[Keys.core(systemId)] ?: p[Keys.benchmark(systemId)]?.let(::decodeBenchmark)?.takeIf { !it.skipped }?.chosen
    }

    /**
     * Resultado do teste de núcleos do console neste aparelho. O de outro aparelho (backup restaurado num
     * celular novo) conta como ausente: a escolha não vale aqui e o teste roda de novo.
     */
    fun benchmark(systemId: String): Flow<SystemBenchmark?> = data.map { p -> p[Keys.benchmark(systemId)]?.let(::decodeBenchmark) }

    suspend fun setBenchmark(systemId: String, result: SystemBenchmark?) = context.dataStore.edit { p ->
        if (result == null) p.remove(Keys.benchmark(systemId))
        else p[Keys.benchmark(systemId)] = Http.json.encodeToString(SystemBenchmark.serializer(), result)
    }

    private fun decodeBenchmark(raw: String): SystemBenchmark? =
        runCatching { Http.json.decodeFromString(SystemBenchmark.serializer(), raw) }.getOrNull()?.takeIf { it.device == BENCH_DEVICE }
    suspend fun setCore(systemId: String, coreId: String) = set(Keys.core(systemId), coreId)

    /** O usuário desligou o Vulkan deste núcleo (ele tem a opção, mas o resultado não agradou). */
    fun vulkanDisabled(coreId: String): Flow<Boolean> = data.map { it[Keys.noVulkan(coreId)] ?: false }
    suspend fun setVulkanDisabled(coreId: String, disabled: Boolean) = context.dataStore.edit { p ->
        if (disabled) p[Keys.noVulkan(coreId)] = true else p.remove(Keys.noVulkan(coreId))
    }

    /** Predefinição escolhida pelo usuário para o console; nulo = automática (a do aparelho). */
    fun presetChoice(systemId: String): Flow<Preset?> = data.map { p ->
        p[Keys.preset(systemId)]?.let { runCatching { Preset.valueOf(it) }.getOrNull() }
    }
    suspend fun setPresetChoice(systemId: String, preset: Preset?) = context.dataStore.edit { p ->
        if (preset == null) p.remove(Keys.preset(systemId)) else p[Keys.preset(systemId)] = preset.name
    }

    /** O que o teste de velocidade (ou o chute pela classe do aparelho) decidiu para [coreId] no console. */
    fun tuning(systemId: String, coreId: String): Flow<TuneResult?> = data.map { p -> decodeTuning(p[Keys.tuning(systemId, coreId)]) }

    suspend fun setTuning(systemId: String, coreId: String, result: TuneResult) = context.dataStore.edit { p ->
        p[Keys.tuning(systemId, coreId)] = Http.json.encodeToString(TuneResult.serializer(), result)
    }

    /** Ajuste próprio de um jogo, que vence o do console. */
    fun gameTuning(gameId: Long): Flow<TuneResult?> = data.map { p -> decodeTuning(p[Keys.gameTuning(gameId)]) }

    /** Nulo volta o jogo a seguir o console. */
    suspend fun setGameTuning(gameId: Long, result: TuneResult?) = context.dataStore.edit { p ->
        if (result == null) p.remove(Keys.gameTuning(gameId))
        else p[Keys.gameTuning(gameId)] = Http.json.encodeToString(TuneResult.serializer(), result)
    }

    /** Apaga o que foi medido do console (e dos jogos dele, via [gameIds]): o próximo jogo mede de novo. */
    suspend fun clearTuning(systemId: String, gameIds: Collection<Long>) = context.dataStore.edit { p ->
        p.asMap().keys.filter { it.name.startsWith("$TUNE_PREFIX${systemId}_") }.forEach { p.remove(it) }
        gameIds.forEach { p.remove(Keys.gameTuning(it)) }
    }

    /** Apaga tudo o que foi medido neste aparelho: o teste dos núcleos e os níveis de qualidade, de consoles e de jogos. */
    suspend fun clearAllTuning() = context.dataStore.edit { p ->
        p.asMap().keys.filter { it.name.startsWith(TUNE_PREFIX) || it.name.startsWith(BENCH_PREFIX) }.toList().forEach { p.remove(it) }
    }

    /** Esquece o que era só de um jogo removido da biblioteca: controle próprio e nível de qualidade medido. */
    suspend fun forgetGame(gameId: Long) = context.dataStore.edit { p ->
        p.remove(Keys.gamePadProfile(gameId))
        p.remove(Keys.gameTuning(gameId))
        p.remove(Keys.gameIni(gameId))
        p.remove(Keys.gameTextHooks(gameId))
        p.remove(Keys.gameAutoTranslate(gameId))
        p.asMap().keys.filter { it.name.startsWith("$GAME_OPTIONS_PREFIX${gameId}_") }.toList().forEach { p.remove(it) }
    }

    private fun decodeTuning(raw: String?): TuneResult? =
        raw?.let { runCatching { Http.json.decodeFromString(TuneResult.serializer(), it) }.getOrNull() }

    /** Opções de núcleo alteradas manualmente pelo usuário (sobrepõem os presets). */
    suspend fun coreOptions(coreId: String): Map<String, String> =
        decodeOptions(data.first()[Keys.coreOptions(coreId)])

    private fun decodeOptions(raw: String?): Map<String, String> =
        raw?.let { runCatching { Http.json.decodeFromString(optionsSerializer, it) }.getOrNull() }.orEmpty()

    /** Lê e grava na mesma transação: duas opções trocadas em seguida não se sobrescrevem. */
    suspend fun setCoreOption(coreId: String, key: String, value: String) {
        context.dataStore.edit { p ->
            val updated = decodeOptions(p[Keys.coreOptions(coreId)]) + (key to value)
            p[Keys.coreOptions(coreId)] = Http.json.encodeToString(optionsSerializer, updated)
        }
    }

    suspend fun resetCoreOptions(coreId: String) = context.dataStore.edit { it.remove(Keys.coreOptions(coreId)) }

    /** Opções de núcleo só deste jogo: vencem as do usuário para o núcleo todo e só perdem para as fixas do app. */
    suspend fun gameCoreOptions(gameId: Long, coreId: String): Map<String, String> =
        decodeOptions(data.first()[Keys.gameCoreOptions(gameId, coreId)])

    suspend fun setGameCoreOption(gameId: Long, coreId: String, key: String, value: String) {
        context.dataStore.edit { p ->
            val updated = decodeOptions(p[Keys.gameCoreOptions(gameId, coreId)]) + (key to value)
            p[Keys.gameCoreOptions(gameId, coreId)] = Http.json.encodeToString(optionsSerializer, updated)
        }
    }

    suspend fun removeGameCoreOption(gameId: Long, coreId: String, key: String) {
        context.dataStore.edit { p ->
            val left = decodeOptions(p[Keys.gameCoreOptions(gameId, coreId)]) - key
            if (left.isEmpty()) p.remove(Keys.gameCoreOptions(gameId, coreId))
            else p[Keys.gameCoreOptions(gameId, coreId)] = Http.json.encodeToString(optionsSerializer, left)
        }
    }

    suspend fun resetGameCoreOptions(gameId: Long, coreId: String) = context.dataStore.edit { it.remove(Keys.gameCoreOptions(gameId, coreId)) }

    /** Texto do arquivo de configuração próprio do jogo (Dolphin: `GameSettings/<ID>.ini`); vazio se não há. */
    suspend fun gameIni(gameId: Long): String = data.first()[Keys.gameIni(gameId)].orEmpty()

    /** Onde o texto do jogo está na memória (ver [TextHook]): criados pelo jogador na busca de texto, um conjunto por jogo. */
    suspend fun gameTextHooks(gameId: Long): List<TextHook> =
        data.first()[Keys.gameTextHooks(gameId)]?.let {
            runCatching { Http.json.decodeFromString(ListSerializer(TextHook.serializer()), it) }.getOrNull()
        }.orEmpty()

    /** A tradução dentro do jogo roda sozinha (a cada diálogo novo), em vez de só ao apertar o botão. */
    suspend fun gameAutoTranslate(gameId: Long): Boolean = data.first()[Keys.gameAutoTranslate(gameId)] == true

    suspend fun setGameAutoTranslate(gameId: Long, on: Boolean) = context.dataStore.edit { p ->
        if (on) p[Keys.gameAutoTranslate(gameId)] = true else p.remove(Keys.gameAutoTranslate(gameId))
    }

    suspend fun setGameTextHooks(gameId: Long, hooks: List<TextHook>) = context.dataStore.edit { p ->
        if (hooks.isEmpty()) p.remove(Keys.gameTextHooks(gameId))
        else p[Keys.gameTextHooks(gameId)] = Http.json.encodeToString(ListSerializer(TextHook.serializer()), hooks)
    }

    suspend fun setGameIni(gameId: Long, text: String) = context.dataStore.edit { p ->
        if (text.isBlank()) p.remove(Keys.gameIni(gameId)) else p[Keys.gameIni(gameId)] = text
    }

    /**
     * Controle virtual do console [systemId] (visível, tamanho, posições…); o padrão quando nunca foi ajustado.
     * Por console, não por núcleo: os botões vêm do console (um núcleo como o Genesis Plus GX roda Master
     * System e Mega Drive, com controles diferentes) e trocar de núcleo não perde o controle ajustado.
     */
    fun padProfile(systemId: String): Flow<PadProfile> = data.map { p -> decodeProfile(p[Keys.padProfile(systemId)]) }

    suspend fun setPadProfile(systemId: String, profile: PadProfile) = context.dataStore.edit { p ->
        if (profile.isDefault) p.remove(Keys.padProfile(systemId))
        else p[Keys.padProfile(systemId)] = Http.json.encodeToString(PadProfile.serializer(), profile)
    }

    /**
     * Controle próprio de um jogo, que vence o do console (um jogo de luta com seis botões, outro só de
     * toque…). Nulo quando o jogo segue o do console.
     */
    fun gamePadProfile(gameId: Long): Flow<PadProfile?> = data.map { p -> p[Keys.gamePadProfile(gameId)]?.let(::decodeProfile) }

    /** Nulo volta o jogo ao controle do console. */
    suspend fun setGamePadProfile(gameId: Long, profile: PadProfile?) = context.dataStore.edit { p ->
        if (profile == null) p.remove(Keys.gamePadProfile(gameId))
        else p[Keys.gamePadProfile(gameId)] = Http.json.encodeToString(PadProfile.serializer(), profile)
    }

    /** Consoles com o controle ajustado, para a lista de Ajustes. */
    val customizedPads: Flow<Set<String>> = data.map { p ->
        p.asMap().keys.map { it.name }.filter { it.startsWith(PAD_PREFIX) }.map { it.removePrefix(PAD_PREFIX) }.toSet()
    }

    suspend fun resetAllPadProfiles() = context.dataStore.edit { p ->
        // Também os perfis por núcleo da versão de desenvolvimento anterior, que não são mais lidos.
        p.asMap().keys.filter { k -> listOf(PAD_PREFIX, OLD_PAD_PREFIX, GAME_PAD_PREFIX).any { k.name.startsWith(it) } }.toList().forEach { p.remove(it) }
    }

    // Um perfil ilegível (versão antiga, campo renomeado) volta ao padrão em vez de impedir o jogo de abrir.
    private fun decodeProfile(raw: String?): PadProfile =
        raw?.let { runCatching { Http.json.decodeFromString(PadProfile.serializer(), it) }.getOrNull() } ?: PadProfile()

    companion object {
        const val MAX_PARALLEL_DOWNLOADS = 4
        private const val PAD_PREFIX = "pad_console_"
        private const val OLD_PAD_PREFIX = "pad_profile_"
        private const val GAME_PAD_PREFIX = "pad_game_"
        private const val GAME_OPTIONS_PREFIX = "game_opts_"
        private const val GAME_INI_PREFIX = "game_ini_"
        private const val GAME_HOOKS_PREFIX = "game_hooks_"
        private const val GAME_AUTO_PREFIX = "game_auto_translate_"
        private const val BENCH_PREFIX = "bench_"
        private const val TUNE_PREFIX = "tune_"
        private const val GAME_TUNE_PREFIX = "tune_game_"
        /** Arquivo de SharedPreferences fora do backup (res/xml/backup_rules.xml e data_extraction_rules.xml). */
        private const val SECRETS_FILE = "secrets"
        private const val SECRET_GEMINI_KEY = "gemini_key"

        /** Como o GameActivity identifica o aparelho em [SystemBenchmark.device]: os dois têm de bater. */
        val BENCH_DEVICE: String get() = "${Build.MANUFACTURER} ${Build.MODEL}"
    }
}
