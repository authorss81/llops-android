package com.authorss81.noteflow.ui.components.markdown

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.authorss81.noteflow.services.CodeHighlightPolicy
import com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy

/**
 * Phase 179 + Phase 217 — shared fenced/indented code-block surface used by BOTH
 * markdown renderers (MarkdownRenderer + MarkdownPreviewScreen) so highlighting
 * is wired once. The fence literal is rendered VERBATIM (AnnotatedString built on
 * the raw [codeText], never rewritten) — copy/long-press selection sees the exact
 * source; color spans are purely additive. An unknown/absent language tag or a
 * tokenizer edge case degrades to the existing plain-text look, never to a crash.
 *
 * Phase 217: a bottom-edge drag handle lets the user resize the code block's
 * rendered height (transient, per-composition — resets on recomposition from the
 * note's saved height). The handle is always dimly visible at rest (alpha 0.45).
 */
@Composable
fun CodeBlockTextView(
    codeText: String,
    languageTag: String?,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val onSurfaceVariant = scheme.onSurfaceVariant
    val darkTheme = remember(scheme.surface) { scheme.surface.luminance() < 0.5f }
    val language = remember(languageTag) { CodeHighlightPolicy.languageForFenceTag(languageTag) }
    val spans = remember(codeText, language, darkTheme) {
        CodeHighlightPolicy.highlightSpans(codeText, language, darkTheme)
    }
    val annotated = remember(codeText, spans) { buildHighlightedCode(codeText, spans) }

    // Phase 217: transient height state for the resize handle.
    var extraHeightDp by remember { mutableFloatStateOf(0f) }

    Surface(
        color = scheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = (100 + extraHeightDp).coerceAtLeast(100f).dp, max = 600.dp)
    ) {
        Box {
            Text(
                text = annotated,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                color = onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
            )

            // Phase 217: bottom-edge resize handle — always dimly visible at rest.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(min = 16.dp)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            // No-op: just reveal on touch
                            waitForUpOrCancellation()
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, dragAmount ->
                                change.consume()
                                extraHeightDp = (extraHeightDp + dragAmount.y).coerceIn(0f, 500f)
                            }
                        )
                    }
                    .graphicsLayer {
                        alpha = ResizeHandleVisibilityPolicy.markdownHandleAlpha()
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.DragHandle,
                    contentDescription = "Resize code block",
                    tint = onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

/**
 * Pure-JVM span -> [AnnotatedString] builder (tested directly by
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
