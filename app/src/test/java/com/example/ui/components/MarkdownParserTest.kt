package com.example.ui.components

import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun parsesHeadingsBulletsOrderedQuotesAndDividers() {
        val md = """
            <!-- hidden comment -->
            # ToBus 2.0
            ## Novità principali
            - **Widget avvisi** ridisegnato
              - Sotto-elemento con *corsivo*
            - [x] Fix linea `METRO`
            1. Migliorata stabilità MQTT
            > Nota importante sui dati GTT
            ---
            Testo finale con [link](https://github.com/example/tobus).
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md)
        assertEquals(9, blocks.size)
        assertEquals(MarkdownBlock.Heading(1, "ToBus 2.0"), blocks[0])
        assertEquals(MarkdownBlock.Heading(2, "Novità principali"), blocks[1])
        assertEquals(MarkdownBlock.BulletItem("**Widget avvisi** ridisegnato", indentLevel = 0), blocks[2])
        assertEquals(MarkdownBlock.BulletItem("Sotto-elemento con *corsivo*", indentLevel = 1), blocks[3])
        assertEquals(MarkdownBlock.TaskItem("Fix linea `METRO`", checked = true, indentLevel = 0), blocks[4])
        assertEquals(MarkdownBlock.OrderedItem("1", "Migliorata stabilità MQTT", indentLevel = 0), blocks[5])
        assertEquals(MarkdownBlock.Quote("Nota importante sui dati GTT"), blocks[6])
        assertEquals(MarkdownBlock.Divider, blocks[7])
        assertEquals(MarkdownBlock.Paragraph("Testo finale con [link](https://github.com/example/tobus)."), blocks[8])
    }

    @Test
    fun parsesInlineBoldItalicCodeStrikethroughAndLinksWithoutRawDelimiters() {
        val annotated = parseInlineMarkdown(
            "Aggiunto **supporto Markdown**, *corsivo*, ~~vecchio~~, `snake_case_id` e [GitHub](https://github.com/test/repo)",
        )

        assertFalse(annotated.text.contains("**"))
        assertFalse(annotated.text.contains("~~"))
        assertFalse(annotated.text.contains("[GitHub]"))
        assertTrue(annotated.text.contains("supporto Markdown"))
        assertTrue(annotated.text.contains("snake_case_id"))
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.Bold })
        assertTrue(annotated.spanStyles.any { it.item.fontStyle == FontStyle.Italic })
        assertTrue(annotated.spanStyles.any { it.item.textDecoration == TextDecoration.LineThrough })
        assertEquals(1, annotated.getLinkAnnotations(0, annotated.length).size)
    }

    @Test
    fun preservesSnakeCaseAndRejectsUnsafeLinkSchemes() {
        val snake = parseInlineMarkdown("Variabile my_snake_case_name invariata")
        assertEquals("Variabile my_snake_case_name invariata", snake.text)
        assertTrue(snake.spanStyles.none { it.item.fontStyle == FontStyle.Italic })

        val unsafe = parseInlineMarkdown("Apri [qui](javascript:alert(1))")
        assertEquals("Apri qui", unsafe.text)
        assertTrue(unsafe.getLinkAnnotations(0, unsafe.length).isEmpty())
    }
}
