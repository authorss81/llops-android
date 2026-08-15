package com.authorss81.noteflow

import com.authorss81.noteflow.services.BoundedStreamCopier
import com.authorss81.noteflow.services.ImportArchivePolicy
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-2 (phase-58): the hard byte budget that replaced the unbounded
 * `input.copyTo(out)` in `MainActivity.copySharedUris`.
 *
 * An attacker-fired ACTION_SEND can no longer stage an unbounded amount of
 * `EXTRA_STREAM` bytes into app-private storage: every shared stream now flows
 * through [BoundedStreamCopier.copyBounded], which enforces a per-stream cap
 * and a running total cap against the ACTUAL bytes read and fails closed with
 * the clean [ImportArchivePolicy.ImportSizeLimitException].
 */
class BoundedStreamCopierTest {

    @Test
    fun `a stream within budget copies fully and returns its byte count`() {
        val payload = ByteArray(4000) { (it % 251).toByte() }
        val out = ByteArrayOutputStream()
        val written = BoundedStreamCopier.copyBounded(
            ByteArrayInputStream(payload), out, BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES
        )
        assertEquals(payload.size.toLong(), written)
        assertTrue(out.toByteArray().contentEquals(payload))
    }

    @Test
    fun `an empty stream copies zero bytes`() {
        val out = ByteArrayOutputStream()
        val written = BoundedStreamCopier.copyBounded(
            ByteArrayInputStream(ByteArray(0)), out, BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES
        )
        assertEquals(0L, written)
        assertEquals(0, out.size())
    }

    @Test
    fun `a stream that exceeds the per-stream budget throws the clean cap exception`() {
        val overBudgetSize = (BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES + 123L).toInt()
        val out = ByteArrayOutputStream()
        val ex = assertThrows(ImportArchivePolicy.ImportSizeLimitException::class.java) {
            BoundedStreamCopier.copyBounded(
                ByteArrayInputStream(ByteArray(overBudgetSize) { 0 }), out, BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES
            )
        }
        assertTrue(ex.message.orEmpty().contains("too large"))
        // fail-closed: the target never holds over-budget bytes.
        assertTrue(out.size() <= BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES)
    }

    @Test
    fun `a stream that exceeds the total budget throws against the running total`() {
        // Running total is the same mechanics as a single stream; a caller
        // performing a multi-stream clip deducts from the 200MB total per item.
        val remainingBudget = 64L * 1024
        val out = ByteArrayOutputStream()
        val ex = assertThrows(ImportArchivePolicy.ImportSizeLimitException::class.java) {
            BoundedStreamCopier.copyBounded(
                ByteArrayInputStream(ByteArray((remainingBudget + 1).toInt()) { 1 }),
                out,
                remainingBudget
            )
        }
        assertTrue(ex.message.orEmpty().contains("too large"))
        assertTrue(out.size() <= remainingBudget.toInt())
    }

    @Test
    fun `the shared budgets mirror the ClipShare parser limits`() {
        assertEquals(50L * 1024 * 1024, BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES)
        assertEquals(200L * 1024 * 1024, BoundedStreamCopier.MAX_TOTAL_BYTES)
        assertEquals(
            com.authorss81.noteflow.plugins.clipshare.SharedClipParser.MAX_SINGLE_STREAM_BYTES,
            BoundedStreamCopier.MAX_SINGLE_STREAM_BYTES
        )
        assertEquals(
            com.authorss81.noteflow.plugins.clipshare.SharedClipParser.MAX_TOTAL_BYTES,
            BoundedStreamCopier.MAX_TOTAL_BYTES
        )
    }

    @Test
    fun `zero budget refuses any non-empty stream`() {
        val out = ByteArrayOutputStream()
        assertThrows(ImportArchivePolicy.ImportSizeLimitException::class.java) {
            BoundedStreamCopier.copyBounded(
                ByteArrayInputStream(ByteArray(10) { 1 }), out, 0L
            )
        }
        assertEquals(0, out.size())
    }
}