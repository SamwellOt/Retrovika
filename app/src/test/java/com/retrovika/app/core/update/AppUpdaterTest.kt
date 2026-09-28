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
    fun `ignora release sem apk e pre-release`() {
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.0","assets":[]}"""))
        assertNull(AppUpdater.parseRelease("""{"tag_name":"v1.0","prerelease":true,"assets":[{"name":"a.apk","browser_download_url":"u"}]}"""))
    }
}
