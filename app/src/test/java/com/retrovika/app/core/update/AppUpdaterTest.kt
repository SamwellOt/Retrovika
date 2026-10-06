package com.retrovika.app.core.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun `compara versoes por numero, nao por texto`() {
        assertTrue(Versions.newer("0.3.4", "0.3.3"))
        assertTrue(Versions.newer("v0.3.10", "0.3.9"))
        assertTrue(Versions.newer("1.0", "0.9.9"))
        assertTrue(Versions.newer("0.4", "0.3.12"))
        assertFalse(Versions.newer("0.3.3", "0.3.3"))
        assertFalse(Versions.newer("0.3.3", "0.3.3.0"))
        assertFalse(Versions.newer("0.3.2", "0.3.3"))
        assertFalse(Versions.newer("0.3.3", "0.3.3-debug"))
    }

    @Test
    fun `le a release com o apk e limpa as notas`() {
        val json = """
            {"tag_name":"v0.3.4","draft":false,"prerelease":false,
             "html_url":"https://github.com/SamwellOt/Retrovika/releases/tag/v0.3.4",
             "body":"**Instalação:** baixe o `Retrovika-0.3.4.apk` abaixo.\r\n\r\n> **Atenção:** só desta vez.\r\n\r\n## Novidades\r\n\r\n**Atualização pelo app**\r\n- O app se atualiza sozinho.",
             "assets":[{"name":"notes.txt","browser_download_url":"https://x/notes.txt","size":10},
                       {"name":"Retrovika-0.3.4.apk","browser_download_url":"https://x/Retrovika-0.3.4.apk","size":25000000}]}
        """.trimIndent()
        val r = AppUpdater.parseRelease(json)!!
        assertEquals("0.3.4", r.version)
        assertEquals("https://x/Retrovika-0.3.4.apk", r.apkUrl)
        assertEquals(25_000_000L, r.apkSize)
        assertEquals("Atenção: só desta vez.\n\nAtualização pelo app\n- O app se atualiza sozinho.", r.notes)
    }

    @Test
    fun `escolhe o apk da arquitetura do aparelho e cai no universal`() {
        val json = """
            {"tag_name":"v0.6.7","assets":[
              {"name":"Retrovika-0.6.7.arm64-v8a.apk","browser_download_url":"https://x/arm64.apk","size":14},
              {"name":"Retrovika-0.6.7.apk","browser_download_url":"https://x/universal.apk","size":25},
              {"name":"Retrovika-0.6.7.armeabi-v7a.apk","browser_download_url":"https://x/v7a.apk","size":13},
              {"name":"Retrovika-0.6.7.x86_64.apk","browser_download_url":"https://x/x86_64.apk","size":15}]}
        """.trimIndent()
        assertEquals("https://x/arm64.apk", AppUpdater.parseRelease(json, listOf("arm64-v8a", "armeabi-v7a"))!!.apkUrl)
        assertEquals("https://x/v7a.apk", AppUpdater.parseRelease(json, listOf("armeabi-v7a", "armeabi"))!!.apkUrl)
        // Arquitetura sem APK próprio, ou sem saber a do aparelho: o universal, nunca o de outra arquitetura.
        assertEquals("https://x/universal.apk", AppUpdater.parseRelease(json, listOf("riscv64"))!!.apkUrl)
        assertEquals("https://x/universal.apk", AppUpdater.parseRelease(json)!!.apkUrl)
        // Release só com APKs por arquitetura, nenhum deste aparelho: nada a instalar.
        val onlySplits = """{"tag_name":"v0.6.7","assets":[{"name":"Retrovika-0.6.7.x86_64.apk","browser_download_url":"u"}]}"""
        assertNull(AppUpdater.parseRelease(onlySplits, listOf("arm64-v8a")))
    }

    @Test
    fun `ignora release sem apk e pre-release`() {
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.0","assets":[]}"""))
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.0","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
    }

    @Test
    fun `tag vira nome de arquivo seguro`() {
        assertEquals("0.5.6", AppUpdater.safeVersion("0.5.6"))
        assertFalse(AppUpdater.safeVersion("../../x").contains('/'))
        assertFalse(AppUpdater.safeVersion("../../x").contains(".."))
        assertEquals("1.0_beta", AppUpdater.safeVersion("1.0 beta"))
    }
}
