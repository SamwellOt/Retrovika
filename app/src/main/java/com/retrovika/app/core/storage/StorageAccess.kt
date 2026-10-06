package com.retrovika.app.core.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import java.io.File

/**
 * Acesso direto aos arquivos das pastas vinculadas. Pelo SAF o app só recebe descritores, e núcleos que
 * abrem o jogo com o próprio I/O (sem a VFS do libretro) precisam de um caminho real. Com a permissão
 * "acesso a todos os arquivos" (ou a de leitura, abaixo do Android 11), o documento do provedor de
 * armazenamento local vira o arquivo em /storage.
 */
object StorageAccess {
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    fun granted(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Tela do sistema onde o usuário concede a permissão. */
    fun settingsIntent(context: Context): Intent {
        val pkg = Uri.parse("package:${context.packageName}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg)
        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
    }

    /** Volume do armazenamento local ("primary", "1234-ABCD") e a pasta raiz dele no disco. */
    data class VolumeDir(val volume: String, val volumeRoot: File)

    /**
     * O volume por trás de uma pasta vinculada do armazenamento local, quando o app pode listá-la direto do disco
     * com o mesmo resultado do provedor: Android 11+ com acesso a todos os arquivos, ou até o 9 com a permissão de
     * leitura. No Android 10 o armazenamento isolado esconde de java.io os arquivos que não são mídia: fica no SAF.
     */
    fun realDir(context: Context, treeUri: Uri): VolumeDir? {
        if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
        val fullAccess = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P -> granted(context)
            else -> false
        }
        if (!fullAccess) return null
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        val volume = treeId.substringBefore(':', "")
        val root = volumeRoot(volume) ?: return null
        return VolumeDir(volume, root).takeIf { root.isDirectory }
    }

    /**
     * Pastas que o provedor esconde das árvores a partir do Android 11 (Android/data, Android/obb, Android/sandbox):
     * lidas direto do disco elas trariam, por exemplo, a própria pasta `roms/` do app como jogos vinculados. Até o 9
     * o provedor as mostra, e a leitura direta também precisa mostrar, senão os jogos de lá sairiam da biblioteca.
     */
    fun isRestricted(relative: String): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            relative.lowercase().let { p -> RESTRICTED.any { p == it || p.startsWith("$it/") } }

    private val RESTRICTED = listOf("android/data", "android/obb", "android/sandbox")

    private fun volumeRoot(volume: String): File? = when (volume) {
        "" -> null
        "primary" -> Environment.getExternalStorageDirectory()
        "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        else -> File("/storage", volume)
    }

    /**
     * Arquivo real por trás de um documento do armazenamento local ("primary:Pasta/jogo.iso" ou
     * "1234-ABCD:…" num cartão SD), se o app consegue lê-lo. Null para outros provedores ou sem permissão.
     */
    fun realFile(context: Context, uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY || !granted(context)) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        val root = volumeRoot(volume) ?: return null
        return File(root, relative).takeIf { it.isFile && it.canRead() }
    }
}
