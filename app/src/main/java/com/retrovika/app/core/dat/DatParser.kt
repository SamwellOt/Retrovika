package com.retrovika.app.core.dat

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.StringReader

/**
 * Lê arquivos DAT nos dois formatos usados por No-Intro / libretro-database:
 * o XML Logiqx (`<datafile><game><rom .../></game></datafile>`) e o texto
 * clrmamepro (`game ( name "…" rom ( name "…" size … crc … ) )`).
 */
object DatParser {
    data class Rom(val gameName: String, val size: Long, val crc: String?, val md5: String?)

    fun parse(text: String): List<Rom> =
        if (text.trimStart().startsWith("<")) parseXml(text) else parseClrMamePro(text)

    private fun parseXml(text: String): List<Rom> {
        val out = ArrayList<Rom>()
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(StringReader(text))
        }
        var gameName: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name.lowercase()) {
                    "game", "machine" -> gameName = parser.getAttributeValue(null, "name")
                    "rom" -> {
                        val name = gameName ?: parser.getAttributeValue(null, "name")?.substringBeforeLast('.')
                        if (name != null) {
                            out += Rom(
                                gameName = name,
                                size = parser.getAttributeValue(null, "size")?.toLongOrNull() ?: 0L,
                                crc = normalizeCrc(parser.getAttributeValue(null, "crc")),
                                md5 = parser.getAttributeValue(null, "md5")?.lowercase(),
                            )
                        }
                    }
                }
            }
            event = parser.next()
        }
        return out
    }

    private val blockRegex = Regex("""game \(([\s\S]*?)\n\)""")
    private val nameRegex = Regex("""\bname "([^"]*)"""")
    // Ignora parênteses dentro de aspas: o nome da ROM traz tags como "(World)".
    private val romRegex = Regex("""rom \(((?:[^()"]|"[^"]*")*)\)""")
    private val sizeRegex = Regex("""\bsize (\d+)""")
    private val crcRegex = Regex("""\bcrc (\w+)""")
    private val md5Regex = Regex("""\bmd5 (\w+)""")

    private fun parseClrMamePro(text: String): List<Rom> {
        val out = ArrayList<Rom>()
        for (block in blockRegex.findAll(text)) {
            val body = block.groupValues[1]
            val gameName = nameRegex.find(body)?.groupValues?.get(1) ?: continue
            val rom = romRegex.find(body)?.groupValues?.get(1) ?: continue
            out += Rom(
                gameName = gameName,
                size = sizeRegex.find(rom)?.groupValues?.get(1)?.toLongOrNull() ?: 0L,
                crc = normalizeCrc(crcRegex.find(rom)?.groupValues?.get(1)),
                md5 = md5Regex.find(rom)?.groupValues?.get(1)?.lowercase(),
            )
        }
        return out
    }

    private fun normalizeCrc(raw: String?): String? =
        raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.padStart(8, '0')
}
