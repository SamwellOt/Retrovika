package com.retrovika.app.core.gameinfo

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor

/** Texto legível de um trecho HTML: `<br>` e parágrafos viram quebras de linha, o resto é texto puro. */
internal object HtmlText {
    /**
     * [keepNewlines]: quebras de linha do próprio texto contam (resumos do IGDB separam os itens assim).
     * Sem ele, elas são só a formatação do código-fonte da página e viram espaços.
     */
    fun of(el: Element, keepNewlines: Boolean = false): String {
        val copy = el.clone()
        copy.select("script, style").remove()
        if (!keepNewlines) {
            NodeTraversor.traverse(NodeVisitor { node, _ ->
                if (node is TextNode) node.text(node.wholeText.replace(Regex("""\s*\n\s*"""), " "))
            }, copy)
        }
        copy.select("br").forEach { it.replaceWith(TextNode("\n")) }
        copy.select("li").forEach { it.prependChild(TextNode("• ")) }
        copy.select("p, li, div, h1, h2, h3, h4").forEach { it.prependChild(TextNode("\n\n")) }
        return tidy(copy.wholeText())
    }

    fun of(html: String, keepNewlines: Boolean = false): String = of(Jsoup.parseBodyFragment(html).body(), keepNewlines)

    /** Linhas aparadas, sem espaços duplicados e no máximo uma linha em branco seguida. */
    fun tidy(text: String): String = text
        .replace(' ', ' ')
        .lines()
        .joinToString("\n") { it.replace(Regex("""[ \t]+"""), " ").trim() }
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
