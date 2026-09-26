package com.retrovika.app.core.library

import android.net.Uri
import com.retrovika.app.core.systems.GameSystem
import com.retrovika.app.core.systems.Systems

object RomNaming {
    private val tagRegex = Regex("""\s*[\(\[][^)\]]*[\)\]]""")
    private val regionRegex = Regex("""\((USA|Europe|Japan|World|Brazil|Korea|China|Asia|Australia|France|Germany|Spain|Italy|En|Ja|Pt)[^)]*\)""", RegexOption.IGNORE_CASE)

    /** "Final Fantasy VII (USA) (Disc 1) [!]" -> "Final Fantasy VII" */
    fun cleanTitle(rawName: String): String {
        val cleaned = rawName.replace(tagRegex, "").replace('_', ' ').trim()
        // "Legend of Zelda, The" -> "The Legend of Zelda"
        val articleSuffix = Regex("""^(.*), (The|A|An|O|Os|As)$""").find(cleaned)
        return (articleSuffix?.let { "${it.groupValues[2]} ${it.groupValues[1]}" } ?: cleaned).ifBlank { rawName }
    }

    fun region(rawName: String): String? = regionRegex.find(rawName)?.groupValues?.get(1)?.let {
        when (it.lowercase()) {
            "usa" -> "EUA"; "europe" -> "Europa"; "japan", "ja" -> "Japão"; "world" -> "Mundo"
            "brazil", "pt" -> "Brasil"; "korea" -> "Coreia"; "china" -> "China"; else -> it
        }
    }

    /** Capa automática do repositório libretro-thumbnails (nomes No-Intro/Redump). */
    fun coverUrl(system: GameSystem, rawName: String): String? {
        val db = system.libretroDbName ?: return null
        val sanitized = rawName.replace(Regex("""[&*/:`<>?\\|"]"""), "_")
        return "https://thumbnails.libretro.com/${Uri.encode(db)}/Named_Boxarts/${Uri.encode(sanitized)}.png"
    }

    /** Apelidos comuns de pastas, para vincular pastas já organizadas do usuário. */
    private val folderAliases: Map<String, String> = buildMap {
        Systems.all.forEach { put(it.id, it.id); put(normalizeFolder(it.shortName), it.id) }
        putAll(
            mapOf(
                "famicom" to "nes", "fc" to "nes", "sfc" to "snes", "superfamicom" to "snes", "supernintendo" to "snes",
                "nintendo64" to "n64", "gameboy" to "gb", "gameboycolor" to "gbc", "gameboyadvance" to "gba",
                "ds" to "nds", "nintendods" to "nds", "3ds" to "3ds", "gamecube" to "gc", "ngc" to "gc",
                "ps1" to "psx", "playstation" to "psx", "playstation1" to "psx", "psone" to "psx",
                "playstation2" to "ps2", "playstationportable" to "psp",
                "megadrive" to "genesis", "md" to "genesis", "genesis" to "genesis",
                "segacd" to "segacd", "megacd" to "segacd", "sega32x" to "32x", "mastersystem" to "sms",
                "gamegear" to "gg", "saturn" to "saturn", "segasaturn" to "saturn", "dc" to "dreamcast",
                "mame" to "arcade", "fbneo" to "arcade", "fba" to "arcade", "neogeo" to "arcade", "cps1" to "arcade",
                "cps2" to "arcade", "cps3" to "arcade", "pcengine" to "pce", "tg16" to "pce", "turbografx16" to "pce",
                "atari" to "atari2600", "a2600" to "atari2600", "a7800" to "atari7800", "atarilynx" to "lynx",
                "neogeopocket" to "ngp", "ngpc" to "ngp", "wonderswan" to "wswan", "virtualboy" to "vb",
                "colecovision" to "coleco", "pokemonmini" to "pokemini",
                "sg1000" to "sg1000", "sc3000" to "sg1000", "segasg1000" to "sg1000",
                "atari5200" to "a5200", "a5200" to "a5200", "5200" to "a5200",
                "atari800" to "a800", "a800" to "a800", "atari8bit" to "a800", "atarixl" to "a800", "atarixe" to "a800",
                "jaguar" to "jaguar", "atarijaguar" to "jaguar",
                "vectrex" to "vectrex", "intellivision" to "intv", "intv" to "intv",
                "odyssey2" to "odyssey2", "videopac" to "odyssey2", "magnavoxodyssey2" to "odyssey2",
                "channelf" to "channelf", "fairchildchannelf" to "channelf",
                "neogeocd" to "neocd", "ngcd" to "neocd", "neocd" to "neocd",
                "pcfx" to "pcfx", "3do" to "3do", "panasonic3do" to "3do",
                "wii" to "wii", "nintendowii" to "wii",
                "msx" to "msx", "msx1" to "msx", "msx2" to "msx", "msx2plus" to "msx",
                "c64" to "c64", "commodore64" to "c64", "amiga" to "amiga", "commodoreamiga" to "amiga",
                "zxspectrum" to "zxspectrum", "spectrum" to "zxspectrum", "zx" to "zxspectrum",
                "cpc" to "cpc", "amstrad" to "cpc", "amstradcpc" to "cpc",
                "dos" to "dos", "msdos" to "dos", "dosbox" to "dos",
                "supervision" to "supervision", "watarasupervision" to "supervision",
                "megaduck" to "megaduck", "gameandwatch" to "gw", "gw" to "gw",
                "arduboy" to "arduboy", "tic80" to "tic80", "pico8" to "pico8",
            )
        )
    }

    fun systemForFolder(folderName: String): GameSystem? =
        folderAliases[normalizeFolder(folderName)]?.let(Systems::byId)

    /** "Game Boy Advance" -> "gameboyadvance", "G&W" -> "gw". */
    private fun normalizeFolder(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    /**
     * Resolve o sistema de um arquivo: primeiro pela pasta (mais confiável), depois
     * pela extensão quando ela é exclusiva de um único console.
     */
    fun resolveSystem(fileName: String, parentFolders: List<String>): GameSystem? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        parentFolders.asReversed().forEach { folder ->
            systemForFolder(folder)?.let { if (ext in it.extensions) return it }
        }
        return Systems.byUniqueExtension(ext)
    }

    /**
     * Palpite de console para um arquivo baixado no navegador: extensão exclusiva primeiro; senão,
     * procura apelidos de console em [hints] (URL da página, título…), tentando também palavras
     * vizinhas juntas ("game-boy-advance" -> "gameboyadvance"). É só a pré-seleção; o usuário confirma.
     */
    fun guessSystem(fileName: String, hints: List<String>): GameSystem? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        // .zip/.7z não dizem nada: quase todo site compacta as ROMs (e .zip só "pertence" ao Arcade no catálogo).
        if (ext !in setOf("zip", "7z")) Systems.byUniqueExtension(ext)?.let { return it }
        hints.forEach { hint ->
            val words = hint.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotEmpty() }
            for (size in 3 downTo 1) {
                words.windowed(size).forEach { gram -> systemForFolder(gram.joinToString(""))?.let { return it } }
            }
        }
        return null
    }

    /**
     * Arquivos que fazem parte de outro jogo (faixas de um .cue, discos listados em um .m3u).
     *
     * [referenced] diz se algum índice da pasta cita este arquivo. Com [sheetsKnown] (todos os
     * índices da pasta foram lidos), essa é a resposta exata; sem isso, usa-se uma heurística pelo
     * nome, para não esconder à toa uma ROM .bin de outro console que esteja na mesma pasta.
     */
    fun isAuxiliaryFile(fileName: String, siblings: Set<String>, referenced: Boolean = false, sheetsKnown: Boolean = false): Boolean {
        val lower = fileName.lowercase()
        val ext = lower.substringAfterLast('.', "")
        if (referenced && ext != "m3u") return true
        if (ext in setOf("raw", "sub", "sbi")) return true
        if (sheetsKnown) return false
        return when (ext) {
            "bin", "img" -> {
                val sheets = siblings.filter { it.substringAfterLast('.', "") in setOf("cue", "ccd", "gdi") }
                val base = lower.substringBeforeLast('.')
                sheets.isNotEmpty() && ("(track" in lower || sheets.any { base.startsWith(it.substringBeforeLast('.')) })
            }
            "cue", "chd", "ccd", "gdi", "cdi", "pbp", "iso" -> Regex("""\(disc \d+\)""").containsMatchIn(lower) && siblings.any { it.endsWith(".m3u") }
            else -> false
        }
    }
}
