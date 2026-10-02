package com.retrovika.app.core.tuning

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import java.io.File

/** Classe de desempenho do aparelho para emulação, da mais fraca à mais forte. */
enum class DeviceTier { ENTRY, MID, HIGH, TOP }

/**
 * O aparelho como o emulador o enxerga: processador, GPU, memória e tela. Serve para chutar o ajuste
 * inicial de cada núcleo; o teste de velocidade ([Tuning.search]) corrige o chute com o que o aparelho
 * realmente entrega.
 */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val soc: String,
    val ramMb: Int,
    val cpuCores: Int,
    /** Maior frequência entre os núcleos da CPU, em MHz; 0 quando o sistema não informa. */
    val maxMhz: Int,
    /** GL_RENDERER, ex.: "Adreno (TM) 740"; nulo se não deu para consultar. */
    val gpu: String?,
    /**
     * Núcleos de CPU na faixa de cima: os com pelo menos 80% da frequência do mais rápido (o "prime" e os
     * "performance"; ficam de fora os eficientes). Zero quando o sistema não informa as frequências.
     */
    val perfCores: Int = 0,
    /** O aparelho declara Vulkan 1.1 ou mais (o mínimo da ponte do LibretroDroid para os núcleos Vulkan). */
    val vulkan: Boolean = false,
) {
    val tier: DeviceTier = tierOf(this)

    /** Muda quando o aparelho é outro (backup restaurado em outro celular): o ajuste medido deixa de valer. */
    val signature: String get() = "$manufacturer|$model|$soc|${ramMb / 1024}"

    val displayName: String get() = "$manufacturer $model".trim()

    /** Núcleos que sobram para trabalho extra de emulação: os rápidos quando conhecidos, senão metade dos que há. */
    val fastCores: Int get() = if (perfCores > 0) perfCores else cpuCores / 2

    companion object {
        /** Nota de 0 a 3 da GPU pelo nome do GL_RENDERER; nulo se não reconhece a família. */
        fun gpuScore(renderer: String?): Int? {
            val r = renderer?.trim().orEmpty()
            if (r.isEmpty()) return null
            if (r.contains("swiftshader", true) || r.contains("llvmpipe", true) || r.contains("software", true)) return 0
            number(r, ADRENO)?.let { n ->
                return when {
                    n >= 740 -> 3
                    n >= 725 -> 2
                    n >= 700 -> if (n <= 702) 0 else 1
                    n >= 640 -> 2
                    n >= 610 -> 1
                    else -> 0
                }
            }
            if (r.contains("immortalis", true)) return 3
            number(r, MALI)?.let { n ->
                return when {
                    n >= 720 -> 3
                    n == 77 || n == 78 || n == 615 || n == 710 || n == 715 -> 2
                    n == 57 || n == 68 || n == 72 || n == 76 || n == 610 -> 1
                    else -> 0
                }
            }
            number(r, XCLIPSE)?.let { n -> return if (n >= 940) 3 else 2 }
            if (r.contains("powervr", true)) return 0
            return null
        }

        /** Nota de 0 a 3 pela frequência do núcleo mais rápido; nulo quando não se sabe. */
        fun cpuScore(maxMhz: Int): Int? = when {
            maxMhz <= 0 -> null
            maxMhz >= 3_000 -> 3
            maxMhz >= 2_600 -> 2
            maxMhz >= 2_200 -> 1
            else -> 0
        }

        /**
         * A classe pela pior nota entre GPU e CPU (as duas seguram o emulador), limitada pela memória:
         * com pouca RAM, as texturas ampliadas e o recompilador dinâmico derrubam o app. Sem nenhuma
         * informação, assume a do meio, que é o padrão de antes do ajuste automático. O total informado
         * desconta o que o sistema reserva: um aparelho de 6 GB mostra uns 5,2 a 5,7 GB, um de 4 GB uns 3,6.
         */
        fun tierOf(p: DeviceProfile): DeviceTier {
            val score = listOfNotNull(gpuScore(p.gpu), cpuScore(p.maxMhz)).minOrNull() ?: 1
            val cap = when {
                p.ramMb < 3_000 -> 0
                p.ramMb < 5_000 -> 1
                else -> 3
            }
            return DeviceTier.entries[minOf(score, cap)]
        }

        private val ADRENO = Regex("""adreno\D*(\d{3})""", RegexOption.IGNORE_CASE)
        private val MALI = Regex("""mali-g(\d{2,3})""", RegexOption.IGNORE_CASE)
        private val XCLIPSE = Regex("""xclipse\s*(\d{3})""", RegexOption.IGNORE_CASE)

        private fun number(renderer: String, regex: Regex): Int? = regex.find(renderer)?.groupValues?.get(1)?.toIntOrNull()

        /** Como [detect], mas sem exceção: com o aparelho ilegível vale um perfil sem informação (classe do meio). */
        fun detectOrDefault(context: Context): DeviceProfile = runCatching { detect(context) }.getOrElse {
            DeviceProfile(Build.MANUFACTURER.orEmpty(), Build.MODEL.orEmpty(), Build.HARDWARE.orEmpty(), 4_096, Runtime.getRuntime().availableProcessors(), 0, null)
        }

        /** Lê o aparelho. Faz E/S (sysfs, EGL): chamar fora da thread principal. */
        fun detect(context: Context): DeviceProfile {
            val freqs = cpuMhz()
            val am = context.getSystemService(ActivityManager::class.java)
            val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            // Emuladores e alguns aparelhos devolvem "unknown": melhor o nome do hardware do que isso.
            val soc = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else "")
                .takeIf { it.isNotBlank() && !it.contains("unknown", true) } ?: Build.HARDWARE
            return DeviceProfile(
                manufacturer = Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() },
                model = Build.MODEL.orEmpty(),
                soc = soc,
                ramMb = (mem.totalMem / (1024 * 1024)).toInt(),
                cpuCores = Runtime.getRuntime().availableProcessors(),
                maxMhz = freqs.maxOrNull() ?: 0,
                gpu = gpuRenderer(context),
                perfCores = freqs.count { it >= (freqs.maxOrNull() ?: 0) * PERF_CORE_SHARE },
                vulkan = runCatching { context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION, VULKAN_1_1) }.getOrDefault(false),
            )
        }

        private const val PERF_CORE_SHARE = 0.8

        /** VK_MAKE_VERSION(1, 1, 0) */
        private const val VULKAN_1_1 = 0x401000

        /** cpuinfo_max_freq (MHz) de cada núcleo da CPU; alguns fabricantes bloqueiam a leitura (lista vazia). */
        private fun cpuMhz(): List<Int> = runCatching {
            File("/sys/devices/system/cpu").listFiles { f -> f.name.matches(Regex("cpu\\d+")) }.orEmpty()
                .mapNotNull { File(it, "cpufreq/cpuinfo_max_freq").takeIf(File::canRead)?.readText()?.trim()?.toLongOrNull() }
                .map { (it / 1000).toInt() }
        }.getOrDefault(emptyList())

        /** O GL_RENDERER vem de um contexto GLES descartável, uma vez só: a GPU não muda e fica guardada. */
        private fun gpuRenderer(context: Context): String? {
            val prefs = context.getSharedPreferences("device_profile", Context.MODE_PRIVATE)
            val key = "gpu_${Build.FINGERPRINT.hashCode()}"
            prefs.getString(key, null)?.let { return it }
            val queried = queryGpu() ?: return null
            prefs.edit().putString(key, queried).apply()
            return queried
        }

        private fun queryGpu(): String? = runCatching {
            val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null
            val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                display,
                intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE),
                0, configs, 0, 1, count, 0,
            )
            val config = configs[0] ?: return null
            val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            val surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            try {
                if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
                GLES20.glGetString(GLES20.GL_RENDERER)
            } finally {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
            }
        }.getOrNull()
    }
}
