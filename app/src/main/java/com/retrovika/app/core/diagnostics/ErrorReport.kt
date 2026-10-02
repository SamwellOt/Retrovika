package com.retrovika.app.core.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.os.Build
import com.retrovika.app.core.tuning.DeviceProfile
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Relatório para diagnosticar um jogo que fecha sozinho num aparelho que não está à mão: modelo,
 * versões, os últimos encerramentos que o Android registrou e o log recente do próprio app. O log de
 * um processo que caiu continua legível pelo mesmo app na abertura seguinte, com o backtrace do crash
 * e as mensagens dos núcleos ("Libretro Core"). Só texto: o usuário decide se e para quem envia.
 */
object ErrorReport {
    private const val LOG_LINES = 1500
    private const val EXITS = 5

    fun build(context: Context): String = buildString {
        val am = context.getSystemService(ActivityManager::class.java)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        appendLine("Retrovika $version")
        appendLine("Aparelho: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}), SoC ${socName()}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        val gles = am.deviceConfigurationInfo.reqGlEsVersion
        appendLine("OpenGL ES ${gles shr 16}.${gles and 0xFFFF}")
        val mem = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        appendLine("RAM ${mem.totalMem / (1024 * 1024)} MB, heap Java ${Runtime.getRuntime().maxMemory() / (1024 * 1024)} MB")
        // Base do ajuste automático de qualidade: a classe que o app deduziu para este aparelho.
        val device = DeviceProfile.detect(context)
        // O estado do Vulkan: resultado do autoteste da ponte e tentativas/desligamentos por núcleo.
        appendLine("Vulkan (estado): ${context.getSharedPreferences("vulkan_health", Context.MODE_PRIVATE).all.entries.joinToString { "${it.key.take(48)}=${it.value}" }.ifEmpty { "sem registro" }}")
        appendLine("GPU ${device.gpu ?: "?"}, CPU até ${device.maxMhz} MHz (${device.perfCores}/${device.cpuCores} rápidos), classe ${device.tier}, Vulkan 1.1+: ${if (device.vulkan) "sim" else "não"}")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            appendLine()
            appendLine("== Últimos encerramentos ==")
            val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
            runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, EXITS) }.getOrDefault(emptyList()).forEach { exit ->
                appendLine("${format.format(Date(exit.timestamp))} ${reason(exit.reason)} status=${exit.status} ${exit.description.orEmpty()}")
            }
        }

        appendLine()
        appendLine("== Log recente ==")
        // Um app só lê as próprias linhas do log; "*:I" deixa de fora o ruído de depuração.
        val log = runCatching {
            val process = ProcessBuilder("logcat", "-d", "-b", "main,system,crash", "-v", "time", "-t", "$LOG_LINES", "*:I")
                .redirectErrorStream(true).start()
            process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
        }.getOrElse { "(log indisponível: ${it.message})" }
        append(log)
    }

    /**
     * Grava [report] como arquivo de texto no cache (pasta coberta pelo FileProvider) e devolve o Intent de
     * compartilhar. O relatório inteiro no EXTRA_TEXT passava do limite do Binder (TransactionTooLargeException);
     * no texto vai só o cabeçalho, curto. Faz E/S: chamar fora da thread principal.
     */
    fun shareIntent(context: Context, report: String): Intent {
        // Subpasta própria: o compartilhamento de estados limpa os arquivos soltos de "shared/"
        val dir = context.cacheDir.resolve("shared/report").apply { mkdirs() }
        val file = dir.resolve("retrovika-report.txt")
        file.writeText(report)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val summary = report.substringBefore("\n\n").take(SUMMARY_CHARS)
        return Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Retrovika")
            .putExtra(Intent.EXTRA_TEXT, summary)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private const val SUMMARY_CHARS = 2_000

    private fun socName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE

    private fun reason(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "CRASH(Java)"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH(nativo)"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "POUCA_MEMÓRIA"
        ApplicationExitInfo.REASON_SIGNALED -> "SINAL"
        ApplicationExitInfo.REASON_EXIT_SELF -> "SAIU"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USUÁRIO"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "RECURSOS"
        ApplicationExitInfo.REASON_OTHER -> "OUTRO"
        else -> "motivo $reason"
    }
}
