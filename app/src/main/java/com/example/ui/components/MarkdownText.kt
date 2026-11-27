package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.AccentCyan
import com.example.ui.theme.AccentEmerald
import com.example.ui.theme.GlassRowBorder
import com.example.ui.theme.TextBody
import com.example.ui.theme.TextHero
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSubtitle

internal sealed interface MarkdownBlock {
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class BulletItem(val text: String, val indentLevel: Int = 0) : MarkdownBlock
    data class TaskItem(val text: String, val checked: Boolean, val indentLevel: Int = 0) : MarkdownBlock
    data class OrderedItem(val number: String, val text: String, val indentLevel: Int = 0) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeBlock(val code: String) : MarkdownBlock
    data object Divider : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

private val HtmlCommentRegex = Regex("<!--[\\s\\S]*?-->")
private val HeadingRegex = Regex("^\\s{0,3}(#{1,6})\\s+(.+?)(?:\\s+#+\\s*)?$")
private val DividerRegex = Regex("^\\s{0,3}(?:[-*_]\\s*){3,}$")
private val TaskRegex = Regex("^(\\s*)[-*+]\\s+\\[([ xX])]\\s+(.+)$")
private val BulletRegex = Regex("^(\\s*)(?:[-*+•])\\s+(.+)$")
private val OrderedRegex = Regex("^(\\s*)(\\d+)[.)]\\s+(.+)$")
private val QuoteRegex = Regex("^\\s{0,3}>\\s?(.*)$")

/**
 * Parses GitHub-flavored Markdown release notes into renderable blocks.
 * Designed to be tolerant of real-world changelogs without third-party dependencies.
 */
internal fun parseMarkdownBlocks(rawMarkdown: String): List<MarkdownBlock> {
    val cleaned = rawMarkdown
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(HtmlCommentRegex, "")
        .trim()
    if (cleaned.isEmpty()) return emptyList()

    val lines = cleaned.split('\n')
    val blocks = mutableListOf<MarkdownBlock>()
    var i = 0

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()

        if (trimmed.isEmpty()) {
            i++
            continue
        }

        // Fenced code block ```
        if (trimmed.startsWith("```")) {
            val codeLines = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            if (i < lines.size) i++ // skip closing ```
            val content = codeLines.joinToString("\n").trimEnd()
            if (content.isNotEmpty()) {
                blocks.add(MarkdownBlock.CodeBlock(content))
            }
            continue
        }

        // Horizontal rule
        if (DividerRegex.matches(line)) {
            blocks.add(MarkdownBlock.Divider)
            i++
            continue
        }

        // Heading (# .. ######)
        HeadingRegex.matchEntire(line)?.let { match ->
            val level = match.groupValues[1].length.coerceIn(1, 6)
            val title = match.groupValues[2].trim()
            if (title.isNotEmpty()) {
                blocks.add(MarkdownBlock.Heading(level = level, text = title))
            }
            i++
            continue
        }

        // Blockquote (> ...)
        if (QuoteRegex.matches(line)) {
            val quoteLines = mutableListOf<String>()
            while (i < lines.size) {
                val qm = QuoteRegex.matchEntire(lines[i]) ?: break
                quoteLines.add(qm.groupValues[1].trim())
                i++
            }
            val quoteText = quoteLines.joinToString("\n").trim()
            if (quoteText.isNotEmpty()) {
                blocks.add(MarkdownBlock.Quote(quoteText))
            }
            continue
        }

        // Task list item (- [x] / - [ ])
        TaskRegex.matchEntire(line)?.let { match ->
            val indent = (match.groupValues[1].length / 2).coerceAtMost(2)
            val checked = match.groupValues[2].equals("x", ignoreCase = true)
            val body = match.groupValues[3].trim()
            blocks.add(MarkdownBlock.TaskItem(text = body, checked = checked, indentLevel = indent))
            i++
            continue
        }

        // Bullet list item (- / * / + / •)
        BulletRegex.matchEntire(line)?.let { match ->
            val indent = (match.groupValues[1].length / 2).coerceAtMost(2)
            val body = match.groupValues[2].trim()
            blocks.add(MarkdownBlock.BulletItem(text = body, indentLevel = indent))
            i++
            continue
        }

        // Ordered list item (1. / 1))
        OrderedRegex.matchEntire(line)?.let { match ->
            val indent = (match.groupValues[1].length / 2).coerceAtMost(2)
            val number = match.groupValues[2]
            val body = match.groupValues[3].trim()
            blocks.add(MarkdownBlock.OrderedItem(number = number, text = body, indentLevel = indent))
            i++
            continue
        }

        // Paragraph (group consecutive plain lines, preserving intentional line breaks)
        val paragraphLines = mutableListOf<String>()
        while (i < lines.size) {
            val cur = lines[i]
            val curTrim = cur.trim()
            if (curTrim.isEmpty() ||
                curTrim.startsWith("```") ||
                DividerRegex.matches(cur) ||
                HeadingRegex.matches(cur) ||
                QuoteRegex.matches(cur) ||
                TaskRegex.matches(cur) ||
                BulletRegex.matches(cur) ||
                OrderedRegex.matches(cur)
            ) {
                break
            }
            paragraphLines.add(curTrim)
            i++
        }
        if (paragraphLines.isNotEmpty()) {
            blocks.add(MarkdownBlock.Paragraph(paragraphLines.joinToString("\n")))
        }
    }

    return blocks
}

/**
 * Parses inline Markdown spans (bold, italic, bold+italic, inline code, strikethrough,
 * escaped characters, and safe http/https links) into a styled [AnnotatedString].
 */
internal fun parseInlineMarkdown(
    text: String,
    boldColor: Color = TextPrimary,
    accentColor: Color = AccentCyan,
    codeBgColor: Color = Color(0x28FFFFFF),
    strikeColor: Color = TextSubtitle,
): AnnotatedString = buildAnnotatedString {
    appendInlineMarkdown(
        text = text,
        boldColor = boldColor,
        accentColor = accentColor,
        codeBgColor = codeBgColor,
        strikeColor = strikeColor,
    )
}

private fun AnnotatedString.Builder.appendInlineMarkdown(
    text: String,
    boldColor: Color,
    accentColor: Color,
    codeBgColor: Color,
    strikeColor: Color,
) {
    val n = text.length
    var i = 0

    while (i < n) {
        val ch = text[i]

        // Backslash escape for Markdown punctuation
        if (ch == '\\' && i + 1 < n && text[i + 1] in "\\`*_{}[]()#+-.!~") {
            append(text[i + 1])
            i += 2
            continue
        }

        // Inline code: `code`
        if (ch == '`') {
            val end = text.indexOf('`', startIndex = i + 1)
            if (end > i + 1) {
                val codeText = text.substring(i + 1, end)
                pushStyle(
                    SpanStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        color = accentColor,
                        background = codeBgColor,
                    ),
                )
                append(" $codeText ")
                pop()
                i = end + 1
                continue
            }
        }

        // Markdown link: [label](url)
        if (ch == '[') {
            val closeBracket = text.indexOf(']', startIndex = i + 1)
            if (closeBracket > i + 1 && closeBracket + 1 < n && text[closeBracket + 1] == '(') {
                val closeParen = findClosingLinkParen(text, startIndex = closeBracket + 2)
                if (closeParen > closeBracket + 2) {
                    val label = text.substring(i + 1, closeBracket)
                    val url = text.substring(closeBracket + 2, closeParen).trim()
                    val isSafeHttpUrl = url.startsWith("https://", ignoreCase = true) ||
                        url.startsWith("http://", ignoreCase = true)
                    if (isSafeHttpUrl) {
                        val linkStyle = SpanStyle(
                            color = accentColor,
                            textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.Medium,
                        )
                        pushLink(
                            LinkAnnotation.Url(
                                url = url,
                                styles = TextLinkStyles(style = linkStyle),
                            ),
                        )
                        appendInlineMarkdown(label, boldColor, accentColor, codeBgColor, strikeColor)
                        pop()
                    } else {
                        appendInlineMarkdown(label, boldColor, accentColor, codeBgColor, strikeColor)
                    }
                    i = closeParen + 1
                    continue
                }
            }
        }

        // Bold + Italic: ***text*** or ___text___
        if ((text.startsWith("***", i) || text.startsWith("___", i))) {
            val delim = text.substring(i, i + 3)
            val end = text.indexOf(delim, startIndex = i + 3)
            if (end > i + 3) {
                val inner = text.substring(i + 3, end)
                pushStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        fontStyle = FontStyle.Italic,
                        color = boldColor,
                    ),
                )
                appendInlineMarkdown(inner, boldColor, accentColor, codeBgColor, strikeColor)
                pop()
                i = end + 3
                continue
            }
        }

        // Bold: **text** or __text__
        if (text.startsWith("**", i) || text.startsWith("__", i)) {
            val delim = text.substring(i, i + 2)
            val end = text.indexOf(delim, startIndex = i + 2)
            if (end > i + 2) {
                val inner = text.substring(i + 2, end)
                pushStyle(
                    SpanStyle(
                        fontWeight = FontWeight.Bold,
                        color = boldColor,
                    ),
                )
                appendInlineMarkdown(inner, boldColor, accentColor, codeBgColor, strikeColor)
                pop()
                i = end + 2
                continue
            }
        }

        // Strikethrough: ~~text~~
        if (text.startsWith("~~", i)) {
            val end = text.indexOf("~~", startIndex = i + 2)
            if (end > i + 2) {
                val inner = text.substring(i + 2, end)
                pushStyle(
                    SpanStyle(
                        textDecoration = TextDecoration.LineThrough,
                        color = strikeColor,
                    ),
                )
                appendInlineMarkdown(inner, boldColor, accentColor, codeBgColor, strikeColor)
                pop()
                i = end + 2
                continue
            }
        }

        // Italic: *text* or _text_ (avoid breaking snake_case words)
        if (ch == '*' || ch == '_') {
            val prevIsWord = i > 0 && text[i - 1].isLetterOrDigit()
            val nextIsSpace = i + 1 >= n || text[i + 1].isWhitespace()
            if (!(ch == '_' && prevIsWord) && !nextIsSpace) {
                val end = findClosingSingleDelimiter(text, ch, i + 1)
                if (end > i + 1) {
                    val inner = text.substring(i + 1, end)
                    pushStyle(SpanStyle(fontStyle = FontStyle.Italic))
                    appendInlineMarkdown(inner, boldColor, accentColor, codeBgColor, strikeColor)
                    pop()
                    i = end + 1
                    continue
                }
            }
        }

        append(ch)
        i++
    }
}

private fun findClosingLinkParen(text: String, startIndex: Int): Int {
    var depth = 0
    var idx = startIndex
    while (idx < text.length) {
        when (text[idx]) {
            '\\' -> idx += 2
            '(' -> {
                depth++
                idx++
            }
            ')' -> {
                if (depth == 0) return idx
                depth--
                idx++
            }
            else -> idx++
        }
    }
    return -1
}

private fun findClosingSingleDelimiter(text: String, delim: Char, startIndex: Int): Int {
    var idx = startIndex
    while (idx < text.length) {
        if (text[idx] == '\\') {
            idx += 2
            continue
        }
        if (text[idx] == delim) {
            val prevIsSpace = idx > 0 && text[idx - 1].isWhitespace()
            val nextIsWord = idx + 1 < text.length && text[idx + 1].isLetterOrDigit()
            val isDouble = idx + 1 < text.length && text[idx + 1] == delim
            if (!prevIsSpace && !isDouble && !(delim == '_' && nextIsWord)) {
                return idx
            }
        }
        idx++
    }
    return -1
}

/**
 * Renders GitHub-flavored Markdown release notes cleanly inside Liquid Glass cards.
 */
@Composable
fun MarkdownChangelogContent(
    markdown: String,
    accentColor: Color = AccentCyan,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        blocks.forEachIndexed { index, block ->
            when (block) {
                is MarkdownBlock.Heading -> {
                    val topPad = if (index > 0) 4.dp else 0.dp
                    val (style, color) = when (block.level) {
                        1 -> MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold) to TextHero
                        2 -> MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold) to accentColor
                        else -> MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold) to TextPrimary
                    }
                    val annotated = remember(block.text, color, accentColor) {
                        parseInlineMarkdown(
                            text = block.text,
                            boldColor = color,
                            accentColor = accentColor,
                        )
                    }
                    Text(
                        text = annotated,
                        style = style,
                        color = color,
                        modifier = Modifier.padding(top = topPad),
                    )
                }

                is MarkdownBlock.BulletItem -> {
                    val annotated = remember(block.text, accentColor) {
                        parseInlineMarkdown(text = block.text, accentColor = accentColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indentLevel * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (block.indentLevel == 0) "•" else "◦",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = accentColor,
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.TaskItem -> {
                    val annotated = remember(block.text, accentColor) {
                        parseInlineMarkdown(text = block.text, accentColor = accentColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indentLevel * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = if (block.checked) "✓" else "○",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = if (block.checked) AccentEmerald else TextSubtitle,
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.OrderedItem -> {
                    val annotated = remember(block.text, accentColor) {
                        parseInlineMarkdown(text = block.text, accentColor = accentColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = (block.indentLevel * 12).dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "${block.number}.",
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = accentColor,
                            modifier = Modifier.widthIn(min = 16.dp),
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextBody,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.Quote -> {
                    val annotated = remember(block.text, accentColor) {
                        parseInlineMarkdown(text = block.text, accentColor = accentColor)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(IntrinsicSize.Min)
                            .background(Color(0x14FFFFFF), RoundedCornerShape(8.dp))
                            .padding(vertical = 6.dp, horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(2.5.dp)
                                .fillMaxHeight()
                                .background(accentColor.copy(alpha = 0.65f), RoundedCornerShape(999.dp)),
                        )
                        Text(
                            text = annotated,
                            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                            color = TextSubtitle,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                is MarkdownBlock.CodeBlock -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0x30000000), RoundedCornerShape(10.dp))
                            .border(1.dp, GlassRowBorder, RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                            ),
                            color = TextPrimary,
                        )
                    }
                }

                MarkdownBlock.Divider -> {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 2.dp),
                        thickness = 1.dp,
                        color = GlassRowBorder,
                    )
                }

                is MarkdownBlock.Paragraph -> {
                    val annotated = remember(block.text, accentColor) {
                        parseInlineMarkdown(text = block.text, accentColor = accentColor)
                    }
                    Text(
                        text = annotated,
                        style = MaterialTheme.typography.bodySmall,
                        color = TextBody,
                    )
                }
            }
        }
    }
}
