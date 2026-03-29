package com.example.llamadroid.ui.ai.llama

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

/**
 * Lightweight Markdown renderer for chat messages.
 * Supports: headers, bold, italic, inline code, fenced code blocks with copy, bullet/numbered lists.
 */
@Composable
fun MarkdownText(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier
) {
    val blocks = parseIntoBlocks(text)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.CodeBlock -> CodeBlockView(block)
                is MdBlock.TextBlock -> MarkdownSpannedText(block.content, textColor)
            }
        }
    }
}

// ─── Block Model ─────────────────────────────────────────────────────────────

private sealed class MdBlock {
    data class TextBlock(val content: String) : MdBlock()
    data class CodeBlock(val language: String, val code: String) : MdBlock()
}

// ─── Block Parser ────────────────────────────────────────────────────────────
// Splits raw markdown into alternating text blocks and fenced code blocks.

private fun parseIntoBlocks(raw: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val codeBlockRegex = Regex("```(\\w*)?\\s*\\n([\\s\\S]*?)```")
    var lastEnd = 0

    for (match in codeBlockRegex.findAll(raw)) {
        // Text before this code block
        if (match.range.first > lastEnd) {
            val textBefore = raw.substring(lastEnd, match.range.first).trim()
            if (textBefore.isNotEmpty()) blocks.add(MdBlock.TextBlock(textBefore))
        }
        val lang = match.groupValues[1].ifEmpty { "" }
        val code = match.groupValues[2].trimEnd()
        blocks.add(MdBlock.CodeBlock(lang, code))
        lastEnd = match.range.last + 1
    }

    // Remaining text after last code block
    if (lastEnd < raw.length) {
        val remaining = raw.substring(lastEnd).trim()
        if (remaining.isNotEmpty()) blocks.add(MdBlock.TextBlock(remaining))
    }

    if (blocks.isEmpty() && raw.isNotBlank()) {
        blocks.add(MdBlock.TextBlock(raw.trim()))
    }

    return blocks
}

// ─── Code Block Composable ───────────────────────────────────────────────────

@Composable
private fun CodeBlockView(block: MdBlock.CodeBlock) {
    val context = LocalContext.current
    val codeBg = MaterialTheme.colorScheme.surfaceContainerHighest

    Surface(
        color = codeBg,
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Header: language label + copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = block.language.ifEmpty { "code" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("Code", block.code))
                            Toast.makeText(context, context.getString(R.string.llama_code_copied), Toast.LENGTH_SHORT).show()
                        }
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Copy",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Code content — horizontally scrollable
            SelectionContainer {
                Text(
                    text = block.code,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(12.dp)
                        .fillMaxWidth(),
                    lineHeight = 18.sp
                )
            }
        }
    }
}

// ─── Inline Markdown Renderer ────────────────────────────────────────────────
// Renders a text block with headers, bold, italic, inline code, and lists.

@Composable
private fun MarkdownSpannedText(content: String, textColor: Color) {
    val lines = content.split("\n")
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceContainerHighest
    val inlineCodeColor = MaterialTheme.colorScheme.primary
    val codeStyle = SpanStyle(
        fontFamily = FontFamily.Monospace,
        background = inlineCodeBg,
        color = inlineCodeColor,
        fontSize = 14.sp
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (line in lines) {
            val trimmed = line.trimStart()
            when {
                // Headers
                trimmed.startsWith("### ") -> {
                    Text(
                        text = buildInlineAnnotated(trimmed.removePrefix("### "), textColor, codeStyle),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = buildInlineAnnotated(trimmed.removePrefix("## "), textColor, codeStyle),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = buildInlineAnnotated(trimmed.removePrefix("# "), textColor, codeStyle),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = textColor,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // Bullet lists
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("• ", color = textColor, fontSize = 16.sp)
                        Text(
                            text = buildInlineAnnotated(trimmed.drop(2), textColor, codeStyle),
                            fontSize = 16.sp
                        )
                    }
                }
                // Numbered lists
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val number = trimmed.substringBefore(".")
                    val rest = trimmed.substringAfter(". ")
                    Row(modifier = Modifier.padding(start = 8.dp)) {
                        Text("$number. ", color = textColor, fontSize = 16.sp)
                        Text(
                            text = buildInlineAnnotated(rest, textColor, codeStyle),
                            fontSize = 16.sp
                        )
                    }
                }
                // Empty lines as spacers
                trimmed.isEmpty() -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // Regular paragraph
                else -> {
                    Text(
                        text = buildInlineAnnotated(trimmed, textColor, codeStyle),
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

// ─── Inline Span Builder ─────────────────────────────────────────────────────
// Handles **bold**, *italic*, and `inline code` within a single line.

private fun buildInlineAnnotated(
    text: String,
    defaultColor: Color,
    codeStyle: SpanStyle
): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Inline code: `...`
                text[i] == '`' && i + 1 < text.length -> {
                    val endTick = text.indexOf('`', i + 1)
                    if (endTick != -1) {
                        withStyle(codeStyle) {
                            append(" ")
                            append(text.substring(i + 1, endTick))
                            append(" ")
                        }
                        i = endTick + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Bold: **...**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = defaultColor)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *...*  (single asterisk, not double)
                text[i] == '*' && (i == 0 || text[i - 1] != '*') && i + 1 < text.length && text[i + 1] != '*' -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && (end + 1 >= text.length || text[end + 1] != '*')) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = defaultColor)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Regular character
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
        // Set default color for any spans that don't have explicit color
        addStyle(SpanStyle(color = defaultColor), 0, length)
    }
}
