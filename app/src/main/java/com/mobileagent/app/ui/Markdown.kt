package com.mobileagent.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface Segment {
    data class Text(val text: String) : Segment
    data class Code(val text: String) : Segment
}

private fun split(text: String): List<Segment> {
    if (!text.contains("```")) return listOf(Segment.Text(text))
    val parts = text.split("```")
    return parts.mapIndexedNotNull { index, part ->
        when {
            part.isBlank() && index != 0 -> null
            index % 2 == 1 -> Segment.Code(part.trim('\n'))
            else -> Segment.Text(part)
        }
    }
}

/** Minimal Markdown renderer: fenced code, headings, bullets, bold and inline code. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val segments = remember(text) { split(text) }
    Column(modifier = modifier.fillMaxWidth()) {
        segments.forEach { segment ->
            when (segment) {
                is Segment.Code -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = segment.text,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }

                is Segment.Text -> {
                    segment.text.split("\n").forEach { line ->
                        when {
                            line.isBlank() -> Spacer(Modifier.height(6.dp))
                            line.startsWith("### ") -> Text(
                                inline(line.removePrefix("### ")),
                                style = MaterialTheme.typography.titleSmall,
                            )

                            line.startsWith("## ") -> Text(
                                inline(line.removePrefix("## ")),
                                style = MaterialTheme.typography.titleMedium,
                            )

                            line.startsWith("# ") -> Text(
                                inline(line.removePrefix("# ")),
                                style = MaterialTheme.typography.titleLarge,
                            )

                            line.startsWith("- ") || line.startsWith("* ") -> Text(
                                inline("• " + line.drop(2)),
                                style = MaterialTheme.typography.bodyMedium,
                            )

                            else -> Text(inline(line), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

private fun inline(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    val bold = Regex("\\*\\*(.+?)\\*\\*")
    val code = Regex("`([^`]+)`")
    while (index < text.length) {
        val boldMatch = bold.find(text, index)
        val codeMatch = code.find(text, index)
        val next = listOfNotNull(boldMatch, codeMatch).minByOrNull { it.range.first }
        if (next == null) {
            append(text.substring(index))
            break
        }
        append(text.substring(index, next.range.first))
        if (next == boldMatch) {
            withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(next.groupValues[1]) }
        } else {
            withStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)) { append(next.groupValues[1]) }
        }
        index = next.range.last + 1
    }
}
