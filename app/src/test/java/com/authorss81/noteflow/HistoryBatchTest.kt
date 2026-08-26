package com.authorss81.noteflow

import com.authorss81.noteflow.services.RawInputSample
import com.authorss81.noteflow.services.StrokeBatchPolicy
import com.authorss81.noteflow.services.StrokeInputBatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 214 (Stroke Smoothing v2) — Task 1: coalesced MotionEvent history.
 *
 * Batching digitizers deliver several physical pen/touch samples per dispatched
 * event (`historySize`). Pre-214 only the newest was read, so 120–240 Hz
 * hardware was downsampled to the dispatch rate BEFORE any smoothing ran. These
 * tests pin the ingestion contract:
 *  - a coalesced event carrying N historical samples yields N+1 consumed
 *    samples (the DoD "3 vs 1" case: three historical + one current vs the old
 *    single sample);
 *  - `historySize == 0` still yields exactly ONE (latest) sample;
 *  - FIFO order, overflow shedding, and the monotonic staleness gate behave;
 *  - the AnnotationCanvas wiring reads the historical axes and drains BEFORE
 *    the stabilizer runs (source pins).
 */
class HistoryBatchTest {

    private fun sample(i: Int) = RawInputSample(
        x = i * 1.0f,
        y = i * 2.0f,
        pressure = 0.3f + i * 0.01f,
        tiltRad = 0.05f * i,
        timestampMs = 1_000L + i
    )

    // ---- 1. The DoD case: 3 vs 1 per coalesced event ---------------------------

    @Test
    fun `coalesced event with three historical samples yields four samples vs the legacy one`() {
        val batcher = StrokeInputBatcher()
        // What the passive bridge pushes for ONE ACTION_MOVE whose
        // historySize == 3: three getHistorical* samples, then the current one.
        repeat(3) { batcher.offer(sample(it)) }
        batcher.offer(sample(3))

        val drained = mutableListOf<RawInputSample>()
        val count = batcher.drainInto(drained)

        assertEquals("coalesced event must contribute ALL its samples", 4, count)
        // Legacy counterfactual: pre-214 read exactly one sample per event.
        val legacy = StrokeInputBatcher()
        legacy.offer(sample(3))
        val legacyDrained = mutableListOf<RawInputSample>()
        assertEquals(1, legacy.drainInto(legacyDrained))
        assertTrue("temporal resolution must strictly improve", count > legacyDrained.size)
    }

    @Test
    fun `historySize zero still yields the single latest sample`() {
        assertEquals(
            "policy identity: a non-batching event contributes no extra reads",
            0,
            StrokeBatchPolicy.historicalCount(0)
        )
        val batcher = StrokeInputBatcher()
        // The bridge pushes ONLY the current sample when historySize == 0.
        val latest = RawInputSample(12f, 34f, 0.75f, 0.2f, 5_000L)
        batcher.offer(latest)

        val drained = mutableListOf<RawInputSample>()
        assertEquals(1, batcher.drainInto(drained))
        assertEquals(latest.x, drained[0].x, 0f)
        assertEquals(latest.y, drained[0].y, 0f)
        assertEquals(latest.pressure, drained[0].pressure, 0f)
        assertEquals(latest.tiltRad, drained[0].tiltRad, 0f)
        assertEquals(latest.timestampMs, drained[0].timestampMs)
        // And the queue is empty afterwards.
        assertEquals(0, batcher.drainInto(mutableListOf()))
    }

    // ---- 2. Queue mechanics ------------------------------------------------------

    @Test
    fun `drain preserves FIFO capture order`() {
        val batcher = StrokeInputBatcher()
        for (i in 0 until 10) batcher.offer(sample(i))
        val drained = mutableListOf<RawInputSample>()
        batcher.drainInto(drained)
        assertEquals(
            (0 until 10).map { it.toFloat() },
            drained.map { it.x }
        )
    }

    @Test
    fun `overflow sheds the oldest samples and keeps the freshest geometry`() {
        val batcher = StrokeInputBatcher(capacity = 8)
        for (i in 0 until 24) batcher.offer(sample(i))
        val drained = mutableListOf<RawInputSample>()
        val count = batcher.drainInto(drained)
        assertEquals(8, count)
        assertEquals("oldest must have been shed", 16f, drained.first().x, 0f)
        assertEquals("newest must survive", 23f, drained.last().x, 0f)
    }

    @Test
    fun `clear discards everything at gesture boundaries`() {
        val batcher = StrokeInputBatcher()
        batcher.offer(sample(1))
        batcher.offer(sample(2))
        batcher.clear()
        assertEquals(0, batcher.drainInto(mutableListOf()))
    }

    // ---- 3. Monotonic gate --------------------------------------------------------

    @Test
    fun `monotonic gate rejects replays and accepts strictly newer samples`() {
        assertTrue(StrokeBatchPolicy.isStale(1_000L, 1_000L))
        assertTrue(StrokeBatchPolicy.isStale(999L, 1_000L))
        assertTrue(!StrokeBatchPolicy.isStale(1_001L, 1_000L))
        assertTrue("first sample of a stroke is never stale", !StrokeBatchPolicy.isStale(42L, null))
    }

    // ---- 4. Wiring pins -----------------------------------------------------------

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    private fun canvasSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

    @Test
    fun `canvas pushes historical axes in the passive bridge`() {
        val src = canvasSource()
        assertTrue(src.contains("motionEvent.historySize"))
        assertTrue(src.contains("motionEvent.getHistoricalX(0, h)"))
        assertTrue(src.contains("motionEvent.getHistoricalY(0, h)"))
        assertTrue(src.contains("motionEvent.getHistoricalPressure(0, h)"))
        assertTrue(src.contains("AXIS_TILT, 0, h"))
        assertTrue(src.contains("motionEvent.getHistoricalEventTime(h)"))
        // Non-batching devices must keep the single-latest-sample behaviour.
        assertTrue(src.contains("StrokeBatchPolicy.historicalCount(motionEvent.historySize)"))
        // Only MOVE feeds the queue; UP/CANCEL flush it so nothing leaks
        // across gestures.
        assertTrue(src.contains("strokeInputBatcher.clear()"))
    }

    @Test
    fun `drag handler drains the queue into the SAME pipeline used by live samples`() {
        val src = canvasSource()
        val drainIdx = src.indexOf("val drainedCount = strokeInputBatcher.drainInto(batchDrainScratch)")
        assertTrue("queue must be drained at the top of onDrag", drainIdx >= 0)
        // Drained samples flow through ONE shared ingestion path that runs the
        // EWMA: the local helper must invoke the full-channel stabilizer, and
        // the drained loop below the drain must call that helper per sample.
        val helperStart = src.indexOf("fun ingestPointerSample(")
        assertTrue("shared ingestion helper must exist", helperStart in 0 until drainIdx)
        val helperBody = src.substring(helperStart, drainIdx)
        assertTrue(
            "the ingestion helper must run the stabilizer",
            helperBody.contains("val s = stabilizerFilter.next(")
        )
        assertTrue(
            "drained samples must be ingested AFTER the drain",
            src.indexOf("ingestPointerSample(", drainIdx) > drainIdx
        )
        // Window -> box-local mapping happens at consumption time (same space
        // transform the phase-196 predicted tail uses).
        assertTrue(src.contains("sample.x - canvasBoxWindowOffset.x"))
        assertTrue(src.contains("sample.y - canvasBoxWindowOffset.y"))
        // Monotonic gate guards every ingested sample.
        assertTrue(src.contains("StrokeBatchPolicy.isStale(sample.timestampMs, lastIngestedInputTimestampMs)"))
        // Review-fix: the gate stamp advances ONLY when the sample was actually
        // accepted — a page-bounds rejection must not consume its timestamp.
        assertTrue(src.contains("val accepted = ingestPointerSample("))
        assertTrue(src.contains("if (accepted) lastIngestedInputTimestampMs = sample.timestampMs"))
        assertTrue(src.contains("if (accepted && lastTimestampMs != null) lastIngestedInputTimestampMs = lastTimestampMs"))
        // Freehand ingests the WHOLE batch; other tools stay newest-only.
        assertTrue(src.contains("drainedCount > 1 && currentTool.isFreehandTool"))
        assertTrue(src.contains("batchDrainScratch.last()"))
    }
}
