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
import androidx.compose.foundation.text.ClickableText
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.llamadroid.R

private const val URL_ANNOTATION_TAG = "url"
private val HyperlinkBlue = Color(0xFF1A73E8)
private val PlainUrlPattern = Regex("""(?i)(https?://|www\.)[^\s<>\[\]{}"']+""")
private val TrailingUrlPunctuation = setOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

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
                        contentDescription = stringResource(R.string.llama_copy_code),
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.llama_copy_code),
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
                    MarkdownInlineText(
                        text = trimmed.removePrefix("### "),
                        textColor = textColor,
                        codeStyle = codeStyle,
                        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    MarkdownInlineText(
                        text = trimmed.removePrefix("## "),
                        textColor = textColor,
                        codeStyle = codeStyle,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    MarkdownInlineText(
                        text = trimmed.removePrefix("# "),
                        textColor = textColor,
                        codeStyle = codeStyle,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                // Task lists
                trimmed.matches(Regex("""^[-*]\s+\[[ xX]]\s+.*""")) -> {
                    val checked = trimmed.contains("[x]", ignoreCase = true)
                    val itemText = trimmed.replace(Regex("""^[-*]\s+\[[ xX]]\s+"""), "")
                    Row(
                        modifier = Modifier.padding(start = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(28.dp)
                        )
                        MarkdownInlineText(
                            text = itemText,
                            textColor = textColor,
                            codeStyle = codeStyle,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Bullet lists
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text("• ", color = textColor, fontSize = 16.sp)
                        MarkdownInlineText(
                            text = trimmed.drop(2),
                            textColor = textColor,
                            codeStyle = codeStyle,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Numbered lists
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val number = trimmed.substringBefore(".")
                    val rest = trimmed.substringAfter(". ")
                    Row(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Text("$number. ", color = textColor, fontSize = 16.sp)
                        MarkdownInlineText(
                            text = rest,
                            textColor = textColor,
                            codeStyle = codeStyle,
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                // Empty lines as spacers
                trimmed.isEmpty() -> {
                    Spacer(modifier = Modifier.height(4.dp))
                }
                // Regular paragraph
                else -> {
                    MarkdownInlineText(
                        text = trimmed,
                        textColor = textColor,
                        codeStyle = codeStyle,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkdownInlineText(
    text: String,
    textColor: Color,
    codeStyle: SpanStyle,
    modifier: Modifier = Modifier,
    style: TextStyle = TextStyle.Default
) {
    val uriHandler = LocalUriHandler.current
    val annotated = remember(text, textColor, codeStyle) {
        buildInlineAnnotated(text, textColor, codeStyle)
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = style.copy(color = textColor),
        onClick = { offset ->
            annotated
                .getStringAnnotations(URL_ANNOTATION_TAG, offset, offset)
                .firstOrNull()
                ?.let { annotation ->
                    runCatching { uriHandler.openUri(annotation.item) }
                }
        }
    )
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
                text[i] == '[' && (i == 0 || text[i - 1] != '!') -> {
                    val markdownLink = parseMarkdownLinkAt(text, i)
                    if (markdownLink != null) {
                        appendHyperlink(
                            label = markdownLink.label.ifBlank { markdownLink.url },
                            url = markdownLink.url
                        )
                        i = markdownLink.endExclusive
                    } else {
                        append(text[i])
                        i++
                    }
                }
                plainUrlAt(text, i) != null -> {
                    val plainUrl = plainUrlAt(text, i)!!
                    appendHyperlink(plainUrl.displayText, plainUrl.url)
                    if (plainUrl.trailingText.isNotEmpty()) {
                        append(plainUrl.trailingText)
                    }
                    i = plainUrl.endExclusive
                }
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
    }
}

private data class ParsedMarkdownLink(
    val label: String,
    val url: String,
    val endExclusive: Int
)

private data class ParsedPlainUrl(
    val displayText: String,
    val trailingText: String,
    val url: String,
    val endExclusive: Int
)

private fun parseMarkdownLinkAt(text: String, start: Int): ParsedMarkdownLink? {
    val labelEnd = text.indexOf("](", start + 1)
    if (labelEnd <= start) return null
    val urlStart = labelEnd + 2
    val urlEnd = text.indexOf(')', urlStart)
    if (urlEnd == -1) return null
    val rawUrl = text.substring(urlStart, urlEnd).trim()
    val normalizedUrl = normalizeBrowserUrl(rawUrl) ?: return null
    return ParsedMarkdownLink(
        label = text.substring(start + 1, labelEnd),
        url = normalizedUrl,
        endExclusive = urlEnd + 1
    )
}

private fun plainUrlAt(text: String, start: Int): ParsedPlainUrl? {
    val match = PlainUrlPattern.find(text, start)?.takeIf { it.range.first == start } ?: return null
    val raw = match.value
    val display = raw.trimEnd { it in TrailingUrlPunctuation }
    if (display.isBlank()) return null
    val normalizedUrl = normalizeBrowserUrl(display) ?: return null
    return ParsedPlainUrl(
        displayText = display,
        trailingText = raw.drop(display.length),
        url = normalizedUrl,
        endExclusive = match.range.last + 1
    )
}

private fun normalizeBrowserUrl(rawUrl: String): String? {
    val trimmed = rawUrl.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> trimmed
        trimmed.startsWith("www.", ignoreCase = true) -> "https://$trimmed"
        else -> null
    }
}

private fun AnnotatedString.Builder.appendHyperlink(label: String, url: String) {
    val start = length
    append(label)
    addStringAnnotation(URL_ANNOTATION_TAG, url, start, length)
    addStyle(
        SpanStyle(
            color = HyperlinkBlue,
            textDecoration = TextDecoration.Underline
        ),
        start,
        length
    )
}
