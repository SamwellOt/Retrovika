package com.retrovika.app.core.gameinfo

import java.text.Normalizer

/**
 * Nomes de jogo como os sites de download escrevem ("Chrono Trigger (USA) (Rev 1)", "Digimon World
 * 2003 PSX ISO", "super_mario_64") viram o título limpo que as bases de dados usam.
 */
object GameTitles {
    /** Sufixos que os sites de ROM põem depois do nome e que não fazem parte do título. */
    private val trailing = Regex(
        """(\s+[-–]\s+)?\b(psx|ps1|ps2|psp|gba|gbc|nds|n64|snes|nes|gb|gc|ngc|wii|dc|md|iso|isos|rom|roms|eboot|cso|chd)\b\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /** Remove região/revisão entre parênteses e colchetes, sufixos de formato e sublinhados. */
    fun clean(raw: String): String {
        var t = raw.replace('_', ' ')
        t = t.replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
        // "Jogo PSX ISO", "Jogo GBA": o console e o formato repetem o que a página já diz.
        repeat(3) { t = t.replace(trailing, " ").trim() }
        // "Legend of Zelda, The" (ordem de catálogo) volta a "The Legend of Zelda".
        Regex("""^(.*),\s*(The|A|An)\b(.*)$""", RegexOption.IGNORE_CASE).find(t)?.let { m ->
            t = "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
        }
        return t.replace(Regex("""\s+"""), " ").trim().trim('-', '–', ':', ' ')
    }

    /**
     * Forma de comparação: minúsculas, sem acentos nem pontuação, "&" como "and". "The Legend of
     * Zelda - A Link to the Past" e "The Legend of Zelda: A Link to the Past" ficam iguais.
     */
    fun key(title: String): String {
        val folded = Normalizer.normalize(title.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        return folded.replace("&", " and ").replace(Regex("""[^a-z0-9]+"""), " ").trim()
    }

    fun same(a: String, b: String): Boolean = key(a) == key(b)

    /**
     * [key] com as vogais longas do japonês romanizado juntas: "Taiyou", "Taiyō", "Taiyoo" e "Taiyo"
     * ficam iguais. Os sites de ROM escrevem os títulos japoneses de um jeito e a Wikipedia de outro.
     */
    fun romajiKey(title: String): String =
        key(title).replace("ou", "o").replace("oo", "o").replace("uu", "u").replace("aa", "a")
            // Hepburn tradicional escreve "m" antes de b/m/p ("Shimpan"); o romaji de teclado, "n".
            .replace(Regex("""m(?=[bmp])"""), "n")
            // A partícula を aparece como "wo" ou "o".
            .replace(Regex("""\bwo\b"""), "o")

    /**
     * Outros nomes que a abertura de um artigo da Wikipedia dá ao jogo: as partes dos parênteses
     * ("(Japanese: 花と太陽と雨と, Hepburn: Hana to Taiyō to Ame to)") sem o rótulo, e o que vem depois
     * de "known in Japan as".
     */
    fun knownAs(intro: String): List<String> {
        val parts = Regex("""\(([^()]*)\)""").findAll(intro).flatMap { it.groupValues[1].split(',', ';') } +
            Regex("""in Japan as\s+([^,;.(]+)""", RegexOption.IGNORE_CASE).findAll(intro).map { it.groupValues[1] }
        return parts.map { it.replace(label, "").trim() }.filter { it.isNotBlank() }.toList()
    }

    private val label = Regex(
        """^\s*(?:(?:Japanese|Revised Hepburn|Hepburn|romanized|lit\.?|also known as|known as|or)(?![a-z])\s*:?\s*)+""",
        RegexOption.IGNORE_CASE,
    )
}

/**
 * Plataformas de cada console como o Backloggd (IGDB) as nomeia: o slug do link
 * `release_platform:<slug>` e o nome exibido. Serve para escolher, entre jogos de mesmo nome, o do
 * console certo.
 */
object Platforms {
    private class Match(val slugs: Set<String>, val names: Set<String>)

    private val bySystem: Map<String, Match> = mapOf(
        "nes" to Match(setOf("nes", "famicom", "fds"), setOf("nintendo entertainment system", "family computer", "family computer disk system")),
        "snes" to Match(setOf("snes", "sfam", "satellaview"), setOf("super nintendo entertainment system", "super famicom")),
        "n64" to Match(setOf("n64", "64dd"), setOf("nintendo 64")),
        "gb" to Match(setOf("gb"), setOf("game boy")),
        "gbc" to Match(setOf("gbc"), setOf("game boy color")),
        "gba" to Match(setOf("gba"), setOf("game boy advance")),
        "nds" to Match(setOf("nds"), setOf("nintendo ds")),
        "3ds" to Match(setOf("3ds", "new-nintendo-3ds"), setOf("nintendo 3ds", "new nintendo 3ds")),
        "gc" to Match(setOf("ngc"), setOf("nintendo gamecube")),
        "wii" to Match(setOf("wii"), setOf("wii")),
        "psx" to Match(setOf("ps"), setOf("playstation")),
        "ps2" to Match(setOf("ps2"), setOf("playstation 2")),
        "psp" to Match(setOf("psp"), setOf("playstation portable")),
        "genesis" to Match(setOf("genesis-slash-megadrive"), setOf("sega mega drive genesis")),
        "segacd" to Match(setOf("sega-cd"), setOf("sega cd")),
        "32x" to Match(setOf("sega32"), setOf("sega 32x")),
        "sms" to Match(setOf("sms"), setOf("sega master system mark iii")),
        "gg" to Match(setOf("gamegear"), setOf("sega game gear")),
        "saturn" to Match(setOf("saturn"), setOf("sega saturn")),
        "dreamcast" to Match(setOf("dc"), setOf("dreamcast")),
        "arcade" to Match(setOf("arcade", "neogeomvs", "neogeoaes"), setOf("arcade", "neo geo mvs", "neo geo aes")),
        "pce" to Match(setOf("turbografx16--1", "turbografx-16-slash-pc-engine-cd", "supergrafx"), setOf("turbografx 16 pc engine", "turbografx 16 pc engine cd", "pc engine supergrafx")),
        "atari2600" to Match(setOf("atari2600"), setOf("atari 2600")),
        "atari7800" to Match(setOf("atari7800"), setOf("atari 7800")),
        "lynx" to Match(setOf("lynx"), setOf("atari lynx")),
        "ngp" to Match(setOf("neo-geo-pocket", "neo-geo-pocket-color"), setOf("neo geo pocket", "neo geo pocket color")),
        "wswan" to Match(setOf("wonderswan", "wonderswan-color"), setOf("wonderswan", "wonderswan color")),
        "vb" to Match(setOf("virtualboy"), setOf("virtual boy")),
        "coleco" to Match(setOf("colecovision"), setOf("colecovision")),
        "pokemini" to Match(setOf("pokemon-mini"), setOf("pokemon mini")),
        "gw" to Match(setOf("g-and-w"), setOf("game and watch")),
        "sg1000" to Match(setOf("sg1000"), setOf("sg 1000")),
        "a5200" to Match(setOf("atari5200"), setOf("atari 5200")),
        "a800" to Match(setOf("atari8bit"), setOf("atari 8 bit")),
        "jaguar" to Match(setOf("jaguar"), setOf("atari jaguar")),
        "neocd" to Match(setOf("neo-geo-cd"), setOf("neo geo cd")),
        "pcfx" to Match(setOf("pc-fx"), setOf("pc fx")),
        "3do" to Match(setOf("3do"), setOf("3do interactive multiplayer")),
        "vectrex" to Match(setOf("vectrex"), setOf("vectrex")),
        "intv" to Match(setOf("intellivision"), setOf("intellivision")),
        "odyssey2" to Match(setOf("odyssey-2-slash-videopac-g7000"), setOf("odyssey 2 videopac g7000")),
        "channelf" to Match(setOf("fairchild-channel-f"), setOf("fairchild channel f")),
        "supervision" to Match(setOf("watara-slash-quickshot-supervision"), setOf("watara quickshot supervision")),
        "msx" to Match(setOf("msx", "msx2"), setOf("msx", "msx2")),
        "c64" to Match(setOf("c64"), setOf("commodore c64 128 max")),
        "amiga" to Match(setOf("amiga", "amiga-cd32"), setOf("amiga", "amiga cd32")),
        "zxspectrum" to Match(setOf("zxs"), setOf("zx spectrum")),
        "cpc" to Match(setOf("acpc"), setOf("amstrad cpc")),
        "dos" to Match(setOf("dos"), setOf("dos")),
        "pico8" to Match(setOf("pico-8"), setOf("pico 8")),
        "arduboy" to Match(setOf("arduboy"), setOf("arduboy")),
    )

    /** Verdadeiro se alguma plataforma do jogo ([slugs] dos links ou [names] exibidos) é do console [systemId]. */
    fun matches(systemId: String, slugs: Collection<String>, names: Collection<String>): Boolean {
        val m = bySystem[systemId] ?: return false
        return slugs.any { it.lowercase() in m.slugs } || names.any { GameTitles.key(it) in m.names }
    }

    fun knows(systemId: String): Boolean = systemId in bySystem
}
