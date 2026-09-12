package com.authorss81.noteflow

import com.authorss81.noteflow.services.RawInputSample
import com.authorss81.noteflow.services.StrokeBatchPolicy
import com.authorss81.noteflow.services.StrokeInputBatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 255 (Canvas ingest): stale-timestamp dots + batcher tail loss.
 *
 * Fixes three ingest-gate defects found by the AI Studio strict canvas audit:
 *  1. HIGH — the drag-handler batch drain froze `prevAcceptedTime` BEFORE the
 *     `for (sample in batchDrainScratch)` loop, so the monotonic gate was never
 *     advanced to the just-accepted sample. A cross-event duplicate `eventTime`
 *     burst (the same physical sample re-served as the NEXT event's oldest
 *     historical) passed the frozen gate and injected a zero-distance sample
 *     that polluted the wet-throttle distance gate
 *     (`WetThrottlePolicy.shouldProcess`, MIN_PX 1.5f) — a `points.size==1`
 *     dot where a real mark should be.
 *  2. MEDIUM — the drag fallback stamped samples with `change.uptimeMillis`
 *     (a different dispatch layer than the batcher's `eventTime`), so on
 *     120 Hz panels the first live sample after a batch could be off-by-1 and
 *     wrongly stale/duplicate. Now the passive bridge's `motionEvent.eventTime`
 *     (threaded through `lastTimestampMs`) is preferred; `uptimeMillis` remains
 *     ONLY as the `?:` fallback when no MotionEvent has been seen yet.
 *  3. MEDIUM — mid-gesture navigation tore the canvas down via
 *     `DisposableEffect(Unit).onDispose` and committed `activePoints.toList()`
 *     WITHOUT draining `strokeInputBatcher` first, silently dropping the last
 *     10-20 ms tail (2-3 queued ACTION_MOVE samples). The dispose flush now
 *     drains through the SAME ingestion gate (`ingestPointerSample`) before the
 *     stroke is built.
 */
class Phase255CanvasIngestTest {

    private fun sample(i: Int, ts: Long) = RawInputSample(
        x = i * 1.0f,
        y = i * 2.0f,
        pressure = 0.4f,
        tiltRad = 0.0f,
        timestampMs = ts
    )

    // ---- 1. Pure-JVM: frozen-prev vs live gate on a duplicate burst ----------
    //
    // Mechanically demonstrates fix 1 using the PRODUCTION gate
    // (`StrokeBatchPolicy.isStale`) and the PRODUCTION batcher. Batching
    // digitizers deliver `history` + current per ACTION_MOVE; a cross-event
    // duplicate surfaces the LAST sample of event N again as the FIRST
    // historical of event N+1. A frozen gate (prev accepted snapshotted before
    // the burst) admits it; the live gate rejects it.

    @Test
    fun `live gate rejects the cross-event duplicate that a frozen gate admits`() {
        val batcher = StrokeInputBatcher()
        // Event 1 (ACTION_MOVE, historySize=2): historical [1000, 1001], current 1002.
        batcher.offer(sample(0, 1000))
        batcher.offer(sample(1, 1001))
        batcher.offer(sample(2, 1002))
        // Event 2 (ACTION_MOVE, historySize=1): historical 1002 == event-1's
        // current (the duplicate the audit found), then current 1003.
        batcher.offer(sample(2, 1002))
        batcher.offer(sample(3, 1003))
        val drained = mutableListOf<RawInputSample>()
        assertEquals(5, batcher.drainInto(drained))

        // Strategy A (BUG — frozen): stamp captured once before the loop, never
        // advanced. The duplicate 1002 passes because the gate still compares
        // against the pre-burst state.
        var frozenGate = 999L
        val frozenAccepted = mutableListOf<Long>()
        for (sample in drained) {
            if (StrokeBatchPolicy.isStale(sample.timestampMs, frozenGate)) continue
            frozenAccepted.add(sample.timestampMs)
        }
        assertEquals("frozen gate admits the duplicate", 5, frozenAccepted.size)
        assertEquals(listOf(1000L, 1001L, 1002L, 1002L, 1003L), frozenAccepted)

        // Strategy B (FIX — live): gate advances to each just-accepted sample.
        var liveGate: Long? = null
        val liveAccepted = mutableListOf<Long>()
        for (sample in drained) {
            if (StrokeBatchPolicy.isStale(sample.timestampMs, liveGate)) continue
            liveAccepted.add(sample.timestampMs)
            liveGate = sample.timestampMs
        }
        assertEquals("live gate drops the duplicate", 4, liveAccepted.size)
        assertEquals(listOf(1000L, 1001L, 1002L, 1003L), liveAccepted)
        // The two strategies must DIVERGE exactly on the duplicate: the live
        // gate consumes one FEWER sample than the frozen gate.
        assertEquals(frozenAccepted.size - 1, liveAccepted.size)
        assertEquals(
            "the duplicate must be the only difference",
            frozenAccepted.filter { it != 1002L },
            liveAccepted.filter { it != 1002L }
        )
    }

    @Test
    fun `within a single event historical times stay monotone and are all accepted`() {
        val batcher = StrokeInputBatcher()
        batcher.offer(sample(0, 2000))
        batcher.offer(sample(1, 2001))
        batcher.offer(sample(2, 2002))
        val drained = mutableListOf<RawInputSample>()
        batcher.drainInto(drained)
        assertEquals(listOf(2000L, 2001L, 2002L), drained.map { it.timestampMs })
        var gate: Long? = null
        for (s in drained) assertTrue(StrokeBatchPolicy.isStale(s.timestampMs, gate).not())
    }

    // ---- 2. Source pins -------------------------------------------------------

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

    private fun batcherSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/StrokeInputBatcher.kt").readText()

    @Test
    fun `batch drain tests the gate against the LIVE stamp and never freezes a prev`() {
        val src = canvasSource()
        // The gate is read fresh for every sample in the drag-handler batch loop.
        assertTrue(
            "gate must compare against the live lastIngestedInputTimestampMs",
            src.contains("if (StrokeBatchPolicy.isStale(sample.timestampMs, lastIngestedInputTimestampMs)) continue")
        )
        // The accepted sample MUST advance that live stamp (both the dispose
        // flush and the drag batch loop).
        assertTrue(
            src.contains("if (accepted) lastIngestedInputTimestampMs = sample.timestampMs")
        )
        // The frozen pre-loop capture is gone from code (the only remaining
        // occurrence is the historiographical comment at :2315).
        val code = src.lineSequence()
            .filter { it.trimStart().startsWith("val prevAcceptedTime") }
            .toList()
        assertTrue("no live `val prevAcceptedTime =` capture may exist", code.isEmpty())
        assertTrue(src.contains("prevAcceptedTime"))
    }

    @Test
    fun `fallback clock prefers the passive bridge eventTime over uptimeMillis`() {
        val src = canvasSource()
        // The passive pointerInteropFilter bridge captures THIS event's
        // MotionEvent.eventTime before the drag fallback runs.
        assertTrue(src.contains("lastTimestampMs = motionEvent.eventTime"))
        // The fallback reads that eventTime first; uptimeMillis is only the
        // `?:` fallback (no MotionEvent has been seen yet).
        assertTrue(src.contains("val changeTime = lastTimestampMs ?: change.uptimeMillis"))
        assertTrue("uptimeMillis alone is never the primary stamp", !src.contains("val changeTime = change.uptimeMillis"))
        assertTrue(src.contains("sampleTimestampMs = changeTime"))
    }

    @Test
    fun `dispose flush drains the batcher through the shared ingest gate before building the stroke`() {
        val src = canvasSource()
        // The dispose flush (mid-gesture navigation) must drain the batcher
        // BEFORE committing activePoints. The FIRST drain occurrence in the
        // file is the dispose flush (it appears earlier in source order than
        // the drag-handler drain); it must precede the dispose Stroke build.
        val firstDrainIdx = src.indexOf("strokeInputBatcher.drainInto(batchDrainScratch)")
        assertTrue("a drain call must exist", firstDrainIdx >= 0)
        val disposeGuardIdx = src.indexOf("if (tool.isFreehandTool && tool != StrokeTool.LASER && strokeInputBatcher.isNotEmpty)")
        assertTrue("dispose flush must guard on freehand + not-laser + non-empty", disposeGuardIdx >= 0)
        val inkIdx = src.indexOf("val ink = activePoints.toList()")
        assertTrue("dispose stroke build must exist", inkIdx >= 0)
        assertTrue("dispose drain must come before the collected ink", disposeGuardIdx < inkIdx)
        val disposeRegion = src.substring(firstDrainIdx, inkIdx)
        assertTrue(
            "the dispose drain must ingest through the SAME gate as live ink",
            disposeRegion.contains("fun ingestPointerSample") || disposeRegion.contains("ingestPointerSample(")
        )
        assertTrue(
            "the dispose drain must apply the monotonic gate and advance the live stamp",
            disposeRegion.contains("isStale(sample.timestampMs, lastIngestedInputTimestampMs)") &&
                disposeRegion.contains("if (accepted) lastIngestedInputTimestampMs = sample.timestampMs")
        )
        // A SECOND drain (the drag handler) exists further down.
        val secondDrainIdx = src.indexOf("strokeInputBatcher.drainInto(batchDrainScratch)", firstDrainIdx + 1)
        assertTrue("the drag handler must still drain its own batch", secondDrainIdx > firstDrainIdx)
    }

    @Test
    fun `batcher KDoc pins the single-threaded two-consumer contract`() {
        val src = batcherSource()
        assertTrue(src.contains("PHASE 255 CONTRACT PIN"))
        assertTrue(src.contains("`DisposableEffect(Unit).onDispose` flush"))
        assertTrue(
            "the second consumer must be named and routed through the shared gate",
            src.contains("ring through `ingestPointerSample` before the stroke is committed")
        )
        assertTrue(
            "the concurrency rule for future consumers must be stated",
            src.contains("can execute concurrently with the drag handler")
        )
    }
}