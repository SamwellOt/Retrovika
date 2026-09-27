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

    /**
     * Arquivo real por trás de um documento do armazenamento local ("primary:Pasta/jogo.iso" ou
     * "1234-ABCD:…" num cartão SD), se o app consegue lê-lo. Null para outros provedores ou sem permissão.
     */
    fun realFile(context: Context, uri: Uri): File? {
        if (uri.authority != EXTERNAL_STORAGE_AUTHORITY || !granted(context)) return null
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        val root = when (volume) {
            "" -> return null
            "primary" -> Environment.getExternalStorageDirectory()
            "home" -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            else -> File("/storage", volume)
        }
        return File(root, relative).takeIf { it.isFile && it.canRead() }
    }
}
