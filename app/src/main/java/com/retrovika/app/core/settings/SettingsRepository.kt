package com.retrovika.app.core.settings

import androidx.annotation.StringRes
import com.retrovika.app.R
import android.content.Context
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
import com.retrovika.app.core.systems.Preset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.IOException

private val Context.dataStore by preferencesDataStore("retrovika_settings")

enum class ShaderOption(@StringRes val label: Int) {
    DEFAULT(R.string.shader_default), SHARP(R.string.shader_sharp), CRT(R.string.shader_crt), LCD(R.string.shader_lcd)
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
        fun core(systemId: String) = stringPreferencesKey("core_$systemId")
        fun preset(systemId: String) = stringPreferencesKey("preset_$systemId")
        fun coreOptions(coreId: String) = stringPreferencesKey("core_options_$coreId")
    }

    private val optionsSerializer = MapSerializer(String.serializer(), String.serializer())

    // Um erro de leitura do arquivo de preferências não pode derrubar o app na abertura: usa os padrões.
    private val data: Flow<Preferences> = context.dataStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val settings: Flow<AppSettings> = data.map { p ->
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
        p[Keys.hidden] = p[Keys.hidden].orEmpty().filterNot { it.startsWith(uri) }.toSet()
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

    /** Volta controle, vídeo e emulação aos padrões; pastas, jogos ocultos e núcleos escolhidos ficam como estão. */
    suspend fun resetPreferences() = context.dataStore.edit { p ->
        listOf(Keys.shader, Keys.padOpacity, Keys.padScale, Keys.haptics, Keys.autoSave, Keys.autoLoad, Keys.ffSpeed, Keys.lowLatency, Keys.hidePad)
            .forEach { p.remove(it) }
    }

    fun coreFor(systemId: String): Flow<String?> = data.map { it[Keys.core(systemId)] }
    suspend fun setCore(systemId: String, coreId: String) = set(Keys.core(systemId), coreId)

    fun presetFor(systemId: String): Flow<Preset> = data.map { p ->
        p[Keys.preset(systemId)]?.let { runCatching { Preset.valueOf(it) }.getOrNull() } ?: Preset.BALANCED
    }
    suspend fun setPreset(systemId: String, preset: Preset) = set(Keys.preset(systemId), preset.name)

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
}
