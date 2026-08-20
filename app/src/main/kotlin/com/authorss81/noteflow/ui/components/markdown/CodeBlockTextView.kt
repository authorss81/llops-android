package com.authorss81.noteflow.ui.components.markdown

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.CodeHighlightPolicy

/**
 * Phase 179 — shared fenced/indented code-block surface used by BOTH markdown
 * renderers (MarkdownRenderer + MarkdownPreviewScreen) so highlighting is wired
 * once. The fence literal is rendered VERBATIM (AnnotatedString built on the
 * raw [codeText], never rewritten) — copy/long-press selection sees the exact
 * source; color spans are purely additive. An unknown/absent language tag or a
 * tokenizer edge case degrades to the existing plain-text look, never to a
 * crash.
 */
@Composable
fun CodeBlockTextView(
    codeText: String,
    languageTag: String?,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val onSurfaceVariant = scheme.onSurfaceVariant
    // The syntax palette follows the scheme the code surface actually sits on
    // (Dark/Amoled/Glass-dark vs Light/Sepia/Glass-light), not a system toggle.
    val darkTheme = remember(scheme.surface) { scheme.surface.luminance() < 0.5f }
    val language = remember(languageTag) { CodeHighlightPolicy.languageForFenceTag(languageTag) }
    val spans = remember(codeText, language, darkTheme) {
        CodeHighlightPolicy.highlightSpans(codeText, language, darkTheme)
    }
    val annotated = remember(codeText, spans) { buildHighlightedCode(codeText, spans) }
    Surface(
        color = scheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            text = annotated,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            color = onSurfaceVariant,
            modifier = Modifier.padding(12.dp)
        )
    }
}

/**
 * Pure-JVM span → [AnnotatedString] builder (tested directly by
 * `Phase179CodeHighlightTest`). The fence literal is appended EXACTLY ONCE and
 * every highlight is applied to its own range via
 * [AnnotatedString.Builder.addStyle] — styles are purely additive, so the
 * underlying text (and therefore what copy/selection returns) is byte-for-byte
 * the raw source. Spans are bounds-clamped defensively so a rogue span can
 * never crash the builder.
 */
internal fun buildHighlightedCode(
    codeText: String,
    spans: List<CodeHighlightPolicy.CodeSpan>
): AnnotatedString {
    return buildAnnotatedString {
        append(codeText)
        spans.forEach { span ->
            val start = span.start.coerceIn(0, codeText.length)
            val end = span.end.coerceIn(start, codeText.length)
            if (start < end) {
                addStyle(spanStyleFor(span), start, end)
            }
        }
    }
}

private fun spanStyleFor(span: CodeHighlightPolicy.CodeSpan): SpanStyle {
    return if (span.bold) {
        SpanStyle(fontWeight = FontWeight.Bold)
    } else {
        SpanStyle(color = Color(0xFF000000L or span.rgb.toLong()))
    }
}