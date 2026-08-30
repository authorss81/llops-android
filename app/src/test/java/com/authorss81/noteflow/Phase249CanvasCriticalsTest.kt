package com.authorss81.noteflow

import com.authorss81.noteflow.services.WetThrottlePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 249 — canvas criticals (AUDIT_2026-08-30):
 *
 *  1. Wet throttle used fabricated wall-clock stamps (`now()-16L`) + the
 *     STABILIZER-CURBED distance, dropping real ink on fast strokes
 *     ("dots far from touch").
 *  2. `flushPendingSaves` was fire-and-forget on a cancellable scope, so a
 *     process kill in the write window lost the dispose-flushed stroke.
 *  3. `onDragStart`'s card-hit early-return skipped `dropPredictedTail()`,
 *     leaving a ghost tail at the start of the next freehand stroke.
 *  4. `applyEraser` was O(strokes × points × samples) per drag sample —
 *     quadratic on long eraser drags over dense notes.
 *
 * Behaviour provable on the pure JVM (the new WetThrottlePolicy decision
 * table + the EraseHitBucketPolicy in its own test class) is tested directly
 * here; the Compose wiring is pinned at source level. Phase-240's node-local
 * drain path is pinned too, so the new gates can't regress it (the PROMPT's
 * `Phase240RotationGateTest` does not exist in this repo — the phase-240
 * coordinate-fix regression surface is the batch drain, pinned below).
 */
class Phase249CanvasCriticalsTest {

    // ------------------------------------------------------------------
    // 1. Wet throttle: real sample timeline + raw digitizer distance
    // ------------------------------------------------------------------

    @Test
    fun `wet throttle keeps every sample that moved the raw floor regardless of time`() {
        assertTrue(
            "1.5px of RAW movement must be accepted even within the same uptime ms",
            WetThrottlePolicy.shouldProcess(
                lastRawX = 0f, lastRawY = 0f, lastSampleTimeMs = 0L,
                rawX = 1.5f, rawY = 0f, sampleTimeMs = 0L
            )
        )
        assertTrue(
            "movement well past the floor is accepted",
            WetThrottlePolicy.shouldProcess(
                lastRawX = 0f, lastRawY = 0f, lastSampleTimeMs = 0L,
                rawX = 60f, rawY = 0f, sampleTimeMs = 0L
            )
        )
    }

    @Test
    fun `wet throttle swallows stationary jitter inside the uptime floor`() {
        assertFalse(
            "sub-floor jitter inside the 16ms window must be dropped",
            WetThrottlePolicy.shouldProcess(
                lastRawX = 0f, lastRawY = 0f, lastSampleTimeMs = 1000L,
                rawX = 0.5f, rawY = 0f, sampleTimeMs = 1005L
            )
        )
    }

    @Test
    fun `wet throttle accepts stationary samples once the uptime floor elapses`() {
        assertTrue(
            "near-stationary sample must still deposit a stamp after 16ms of uptime",
            WetThrottlePolicy.shouldProcess(
                lastRawX = 0f, lastRawY = 0f, lastSampleTimeMs = 1000L,
                rawX = 1.0f, rawY = 0f, sampleTimeMs = 1016L
            )
        )
        assertTrue(
            "exact 16ms uptime boundary accepts",
            WetThrottlePolicy.shouldProcess(
                lastRawX = 0f, lastRawY = 0f, lastSampleTimeMs = 1000L,
                rawX = 0f, rawY = 0f, sampleTimeMs = 1016L
            )
        )
    }

    @Test
    fun `wet throttle is fail open when a reference is missing (first sample)`() {
        assertTrue(
            "first sample of a stroke must never be throttled by an absent reference",
            WetThrottlePolicy.shouldProcess(null, null, null, 0f, 0f, 100L)
        )
        assertTrue(
            "sample with raw reference but no stored uptime is processed",
            WetThrottlePolicy.shouldProcess(1f, 1f, null, 2f, 2f, 100L)
        )
        assertTrue(
            "sample with stored uptime but no raw reference is processed",
            WetThrottlePolicy.shouldProcess(null, null, 50L, 2f, 2f, 100L)
        )
    }

    @Test
    fun `wet gate source pin - stamped sample timestamps instead of fabricated wall clock`() {
        val source = file("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        assertTrue(
            "wet gate must route through WetThrottlePolicy.shouldProcess",
            source.contains("WetThrottlePolicy.shouldProcess(")
        )
        assertTrue(
            "wet gate must track the previous ACCEPTED raw sample's real uptime",
            source.contains("lastRawWetTimeMs")
        )
        assertTrue(
            "wet gate must measure the RAW digitizer distance (pre-smoothing)",
            source.contains("rawX = rawCanvasX") && source.contains("rawY = rawCanvasY")
        )
        assertTrue(
            "the fabricated `now()-16L` wall-clock stamp must be gone",
            !source.contains("System.currentTimeMillis() - 16L") &&
                !source.contains("System.currentTimeMillis() - 100L")
        )
        assertTrue(
            "the NEWEST real MotionEvent uptime must be threaded in as current time",
            source.contains("val curTime = sampleTimestampMs")
        )
    }

    // ------------------------------------------------------------------
    // 3. card-hit onDragStart must clear the predicted tail first
    // ------------------------------------------------------------------

    @Test
    fun `card hit early return source pin - predicted tail is dropped before the return`() {
        val source = file("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        val cardBlock = source.substringAfter("if (isHittingCard(canvasOffset)) {")
            .substringBefore("isDraggingCard = false")
        assertTrue(
            "card-hit block must drop the predicted tail before the early return",
            cardBlock.contains("dropPredictedTail()")
        )
        val tailIdx = cardBlock.indexOf("dropPredictedTail()")
        val returnIdx = cardBlock.indexOf("return@detectDragGestures")
        assertTrue(
            "dropPredictedTail() must run BEFORE the card drag claims the gesture",
            tailIdx in 0 until returnIdx
        )
        assertTrue("the card drag is still claimed afterwards", cardBlock.contains("isDraggingCard = true"))
    }

    // ------------------------------------------------------------------
    // 2. flushPendingSaves must complete under cancellation
    // ------------------------------------------------------------------

    @Test
    fun `flushPendingSaves source pin - body wrapped in withContext NonCancellable`() {
        val vmSrc = file("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        val flush = vmSrc.substringAfter("fun flushPendingSaves(", "END")
            .substringBefore("\n    fun ", "END")
        assertTrue(
            "flushPendingSaves body must run inside withContext(NonCancellable)",
            flush.contains("withContext(NonCancellable) {")
        )
        assertTrue(
            "the phase-242 debounce settle (cancel) must be preserved",
            flush.contains("pendingDebounce?.cancel()")
        )
        assertTrue(
            "the phase-242 debounce settle (join) must be preserved",
            flush.contains("pendingDebounce?.join()")
        )
        assertTrue(
            "the newest snapshot must still be flushed last",
            flush.contains("flushEditorPageSave(pageId, strokes, stickyNotes, embeds, layers)")
        )
    }

    // ------------------------------------------------------------------
    // 4. applyEraser: bounded by the spatial bucket + sample cap
    // ------------------------------------------------------------------

    @Test
    fun `applyEraser source pin - bucket + sample window + hard cap`() {
        val source = file("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        assertTrue(
            "applyEraser must build the spatial bucket",
            source.contains("EraseHitBucketPolicy.build(")
        )
        assertTrue(
            "applyEraser must query only candidate strokes near the cursor",
            source.contains("candidatesWithinCircle(")
        )
        assertTrue(
            "applyEraser must cap the samples processed per call",
            source.contains("EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY")
        )
        assertTrue(
            "applyEraser must process only samples accumulated since the last pass",
            source.contains("subList(startIdx, eraseSamples.size)")
        )
    }

    // ------------------------------------------------------------------
    // Phase-240 regression surface: node-local drain path
    // ------------------------------------------------------------------

    @Test
    fun `batch drain source pin - node local sample coordinates flow through unchanged`() {
        val source = file("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        assertTrue(
            "drained historical samples must be handed to ingest with NO offset arithmetic",
            source.contains("boxLocalX = sample.x,") && source.contains("boxLocalY = sample.y,")
        )
        assertTrue(
            "the live fallback must use the change position directly",
            source.contains("change.position.x") && source.contains("change.position.y")
        )
    }

    private fun file(path: String): String {
        return java.io.File(repoRoot(), path).readText()
    }

    private fun repoRoot(): String {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        while (dir.parentFile != null && !isRepoRoot(dir)) dir = dir.parentFile ?: break
        return dir.absolutePath
    }

    private fun isRepoRoot(dir: java.io.File): Boolean =
        java.io.File(dir, "gradle/libs.versions.toml").isFile && java.io.File(dir, "app").isDirectory
}
