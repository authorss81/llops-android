package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.ClipKind
import com.authorss81.noteflow.plugins.ClipParseOutcome
import com.authorss81.noteflow.plugins.SharedInput
import com.authorss81.noteflow.plugins.SharedStream
import com.authorss81.noteflow.plugins.clipshare.SharedClipParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 15 "Clip to InkFlow" pure-JVM tests: classification (text / images /
 * files / multipart) and the size guard that rejects oversized shares BEFORE
 * any content is copied into the encrypted vault.
 */
class SharedClipParserTest {

    @Test
    fun `plain text share classifies as TEXT`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND,
                text = "Hello from another app"
            )
        )
        assertTrue(outcome is ClipParseOutcome.Success)
        val clip = (outcome as ClipParseOutcome.Success).clip
        assertEquals(ClipKind.TEXT, clip.kind)
        assertEquals("Hello from another app", clip.text)
        assertTrue(clip.streams.isEmpty())
    }

    @Test
    fun `image-only share classifies as IMAGES`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND_MULTIPLE,
                streams = listOf(
                    SharedStream("content://a/1.png", "image/png", 1024),
                    SharedStream("content://a/2.jpg", "image/jpeg", 2048)
                )
            )
        )
        assertTrue(outcome is ClipParseOutcome.Success)
        assertEquals(ClipKind.IMAGES, (outcome as ClipParseOutcome.Success).clip.kind)
    }

    @Test
    fun `text plus images classifies as MULTIPART`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND_MULTIPLE,
                text = "caption",
                streams = listOf(SharedStream("content://a/1.png", "image/png", 1024))
            )
        )
        assertTrue(outcome is ClipParseOutcome.Success)
        assertEquals(ClipKind.MULTIPART, (outcome as ClipParseOutcome.Success).clip.kind)
    }

    @Test
    fun `file share without images classifies as FILES`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND,
                streams = listOf(SharedStream("content://a/plan.pdf", "application/pdf", 5000))
            )
        )
        assertTrue(outcome is ClipParseOutcome.Success)
        assertEquals(ClipKind.FILES, (outcome as ClipParseOutcome.Success).clip.kind)
    }

    @Test
    fun `blank share is rejected loudly`() {
        val outcome = SharedClipParser.parse(SharedInput(action = null, text = "   "))
        assertTrue(outcome is ClipParseOutcome.Rejected)
        assertTrue((outcome as ClipParseOutcome.Rejected).reason.contains("empty"))
    }

    @Test
    fun `oversized single stream is rejected`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND,
                text = "file",
                streams = listOf(
                    SharedStream("content://a/big.mp4", "video/mp4", SharedClipParser.MAX_SINGLE_STREAM_BYTES + 1)
                )
            )
        )
        assertTrue(outcome is ClipParseOutcome.Rejected)
        assertTrue((outcome as ClipParseOutcome.Rejected).reason.contains("50 MB"))
    }

    @Test
    fun `oversized total is rejected`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND_MULTIPLE,
                streams = listOf(
                    SharedStream("content://a/1.mp4", "video/mp4", SharedClipParser.MAX_SINGLE_STREAM_BYTES),
                    SharedStream("content://a/2.mp4", "video/mp4", SharedClipParser.MAX_SINGLE_STREAM_BYTES),
                    SharedStream("content://a/3.mp4", "video/mp4", SharedClipParser.MAX_SINGLE_STREAM_BYTES),
                    SharedStream("content://a/4.mp4", "video/mp4", SharedClipParser.MAX_SINGLE_STREAM_BYTES),
                    SharedStream("content://a/5.mp4", "video/mp4", 1)
                )
            )
        )
        assertTrue(outcome is ClipParseOutcome.Rejected)
        assertTrue((outcome as ClipParseOutcome.Rejected).reason.contains("200 MB"))
    }

    @Test
    fun `oversized text is rejected`() {
        val hugeText = "x".repeat(SharedClipParser.MAX_TEXT_BYTES + 1)
        val outcome = SharedClipParser.parse(
            SharedInput(action = SharedClipParser.ACTION_SEND, text = hugeText)
        )
        assertTrue(outcome is ClipParseOutcome.Rejected)
        assertTrue((outcome as ClipParseOutcome.Rejected).reason.contains("5 MB"))
    }

    @Test
    fun `sizes just at the limits are accepted`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND,
                text = "ok",
                streams = listOf(SharedStream("content://a/1.mp4", "video/mp4", SharedClipParser.MAX_SINGLE_STREAM_BYTES))
            )
        )
        assertTrue(outcome is ClipParseOutcome.Success)
    }

    @Test
    fun `blank uri streams are filtered out before classification`() {
        val outcome = SharedClipParser.parse(
            SharedInput(
                action = SharedClipParser.ACTION_SEND,
                streams = listOf(SharedStream("   ", "image/png", 100))
            )
        )
        // After filtering, the share is empty → rejected, not misclassified.
        assertTrue(outcome is ClipParseOutcome.Rejected)
    }
}