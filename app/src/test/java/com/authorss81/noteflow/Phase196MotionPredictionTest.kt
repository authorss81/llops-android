package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.services.MotionPredictionPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 196: stylus motion prediction regression guard.
 *
 * Behavioral half: exercises the pure-JVM decision table
 * ([MotionPredictionPolicy]) — the API-29 capability gate, the preview-extension
 * guards, the window->world coordinate mapping (identical page policy to the
 * real drag path: out-of-page dropped, in-page boundary-inclusive), the
 * fail-safe rejection of non-finite/degenerate inputs,
 * and the predicted-tail tracker whose strip-before-append/commit contract
 * guarantees stored stroke geometry never contains a predicted point.
 *
 * Source-pin half: pins the AnnotationCanvas wiring (record() in the passive
 * pointerInteropFilter, predict() once per frame, reconcile at every real-event
 * hop, stabilizer untouched) and the dependency pins in the Gradle catalogs.
 */
class Phase196MotionPredictionTest {

    // ---- 1. Capability gate --------------------------------------------------

    @Test
    fun `prediction is attempted only on API 29+`() {
        assertFalse(MotionPredictionPolicy.isSupported(26))
        assertFalse(MotionPredictionPolicy.isSupported(27))
        assertFalse(MotionPredictionPolicy.isSupported(28))
        assertTrue(MotionPredictionPolicy.isSupported(29))
        assertTrue(MotionPredictionPolicy.isSupported(30))
        assertTrue(MotionPredictionPolicy.isSupported(36))
    }

    @Test
    fun `MIN_SDK is 29`() {
        assertEquals(29, MotionPredictionPolicy.MIN_SDK)
    }

    // ---- 2. Preview-extension guards -----------------------------------------

    @Test
    fun `all guards passing allows the preview tail`() {
        assertTrue(
            MotionPredictionPolicy.shouldExtendPreview(
                predictorAvailable = true,
                freehandTool = true,
                strokeInProgress = true,
                singlePointerStream = true,
                panningWhiteSpace = false
            )
        )
    }

    @Test
    fun `each failed guard blocks the preview tail`() {
        assertFalse(
            MotionPredictionPolicy.shouldExtendPreview(
                predictorAvailable = false,
                freehandTool = true,
                strokeInProgress = true,
                singlePointerStream = true,
                panningWhiteSpace = false
            )
        )
        assertFalse(
            MotionPredictionPolicy.shouldExtendPreview(
                predictorAvailable = true,
                freehandTool = false,
                strokeInProgress = true,
                singlePointerStream = true,
                panningWhiteSpace = false
            )
        )
        assertFalse(
            MotionPredictionPolicy.shouldExtendPreview(
                predictorAvailable = true,
                freehandTool = true,
                strokeInProgress = false,
                singlePointerStream = true,
                panningWhiteSpace = false
            )
        )
        assertFalse(
            MotionPredictionPolicy.shouldExtendPreview(
                predictorAvailable = true,
                freehandTool = true,
                strokeInProgress = true,
                singlePointerStream = false,
                panningWhiteSpace = false
            )
        )
        assertFalse(
            MotionPredictionPolicy.shouldExtendPreview(
                predictorAvailable = true,
                freehandTool = true,
                strokeInProgress = true,
                singlePointerStream = true,
                panningWhiteSpace = true
            )
        )
    }

    // ---- 3. Window -> world mapping ------------------------------------------

    @Test
    fun `identity mapping when canvas sits at window origin with no transform`() {
        val p = MotionPredictionPolicy.predictedWorldPoint(
            predictedViewX = 120f, predictedViewY = 340f,
            canvasWindowX = 0f, canvasWindowY = 0f,
            zoomScale = 1f, panX = 0f, panY = 0f,
            pageWidthPx = 1080f, pageTopY = 0f, pageBottomY = 1528f,
            pressure = 0.5f, tilt = 15f, timestampMs = 1234L
        )!!
        assertEquals(120f, p.x, 1e-4f)
        assertEquals(340f, p.y, 1e-4f)
        assertEquals(0.5f, p.pressure!!, 1e-6f)
        assertEquals(15f, p.tilt!!, 1e-6f)
        assertEquals(1234L, p.timestampMs!!)
    }

    @Test
    fun `window offset, pan and zoom are inverted exactly like the drag path`() {
        // Canvas box starts 100px right / 200px down inside the window (Scaffold
        // padding); user has panned by (50, -30) and zoomed to 2x.
        // view(350,170) -> local(250,-30) -> world((250-50)/2, (-30+30)/2) = (100, 0)
        val p = MotionPredictionPolicy.predictedWorldPoint(
            predictedViewX = 350f, predictedViewY = 170f,
            canvasWindowX = 100f, canvasWindowY = 200f,
            zoomScale = 2f, panX = 50f, panY = -30f,
            pageWidthPx = 1080f, pageTopY = -500f, pageBottomY = 1528f,
            pressure = 1f, tilt = 0f, timestampMs = 0L
        )!!
        assertEquals(100f, p.x, 1e-4f)
        assertEquals(0f, p.y, 1e-4f)
    }

    @Test
    fun `predictions outside the active page are dropped like the real drag path`() {
        fun predict(x: Float, y: Float, top: Float = 100f, bottom: Float = 1628f) =
            MotionPredictionPolicy.predictedWorldPoint(
                predictedViewX = x, predictedViewY = y,
                canvasWindowX = 0f, canvasWindowY = 0f,
                zoomScale = 1f, panX = 0f, panY = 0f,
                pageWidthPx = 1080f, pageTopY = top, pageBottomY = bottom,
                pressure = 1f, tilt = 0f, timestampMs = 0L
            )
        // Outside any bound -> null (the real path early-returns; it never
        // clamps out-of-page samples onto the edge).
        assertNull(predict(-0.5f, 500f))
        assertNull(predict(1080.5f, 500f))
        assertNull(predict(500f, 99.9f))
        assertNull(predict(500f, 1628.1f))
        // Boundary-inclusive parity with `rawX < 0f || rawX > width || ...`:
        // values ON a bound are NOT outside and are kept.
        val onLeftBound = predict(0f, 500f)!!
        assertEquals(0f, onLeftBound.x, 1e-4f)
        val onBottomBound = predict(500f, 1628f)!!
        assertEquals(1628f, onBottomBound.y, 1e-4f)
    }

    @Test
    fun `degenerate or non-finite predictions fail safe to null`() {
        fun predict(x: Float = 10f, y: Float = 10f, zoom: Float = 1f, pageW: Float = 1080f, top: Float = 0f, bottom: Float = 1528f) =
            MotionPredictionPolicy.predictedWorldPoint(
                predictedViewX = x, predictedViewY = y,
                canvasWindowX = 0f, canvasWindowY = 0f,
                zoomScale = zoom, panX = 0f, panY = 0f,
                pageWidthPx = pageW, pageTopY = top, pageBottomY = bottom,
                pressure = 1f, tilt = 0f, timestampMs = 0L
            )
        assertNull(predict(x = Float.NaN))
        assertNull(predict(y = Float.POSITIVE_INFINITY))
        assertNull(predict(zoom = 0f))
        assertNull(predict(zoom = -1.5f))
        assertNull(predict(pageW = 0f))
        assertNull(predict(bottom = -1f)) // pageBottomY < pageTopY
    }

    @Test
    fun `non-finite or non-positive pressure is dropped, valid pressure kept`() {
        val dropped = MotionPredictionPolicy.predictedWorldPoint(
            predictedViewX = 1f, predictedViewY = 1f,
            canvasWindowX = 0f, canvasWindowY = 0f,
            zoomScale = 1f, panX = 0f, panY = 0f,
            pageWidthPx = 100f, pageTopY = 0f, pageBottomY = 100f,
            pressure = Float.NaN, tilt = null, timestampMs = null
        )!!
        assertNull(dropped.pressure)
        val kept = MotionPredictionPolicy.predictedWorldPoint(
            predictedViewX = 1f, predictedViewY = 1f,
            canvasWindowX = 0f, canvasWindowY = 0f,
            zoomScale = 1f, panX = 0f, panY = 0f,
            pageWidthPx = 100f, pageTopY = 0f, pageBottomY = 100f,
            pressure = 0.42f, tilt = 0f, timestampMs = null
        )!!
        assertEquals(0.42f, kept.pressure!!, 1e-6f)
    }

    // ---- 4. Predicted-tail tracker (reconcile contract) -----------------------

    @Test
    fun `strip removes exactly the flagged trailing predicted point`() {
        val tracker = MotionPredictionPolicy.PredictedTailTracker()
        val points = mutableListOf(PointF(0f, 0f), PointF(5f, 5f), PointF(9f, 9f))
        points.add(PointF(11f, 12f)) // the predicted sample
        tracker.mark()
        assertTrue(tracker.isPresent)
        tracker.stripFrom(points)
        assertFalse(tracker.isPresent)
        assertEquals(listOf(PointF(0f, 0f), PointF(5f, 5f), PointF(9f, 9f)), points)
    }

    @Test
    fun `strip without a marked tail never mutates the list`() {
        val tracker = MotionPredictionPolicy.PredictedTailTracker()
        val points = mutableListOf(PointF(1f, 2f), PointF(3f, 4f))
        tracker.stripFrom(points)
        assertEquals(listOf(PointF(1f, 2f), PointF(3f, 4f)), points)
    }

    @Test
    fun `strip is idempotent`() {
        val tracker = MotionPredictionPolicy.PredictedTailTracker()
        val points = mutableListOf(PointF(1f, 1f), PointF(2f, 2f))
        tracker.mark()
        tracker.stripFrom(points)
        assertEquals(1, points.size)
        tracker.stripFrom(points)
        assertEquals(1, points.size)
        assertFalse(tracker.isPresent)
    }

    @Test
    fun `strip on a flagged empty list clears the flag safely`() {
        val tracker = MotionPredictionPolicy.PredictedTailTracker()
        val points = mutableListOf<PointF>()
        tracker.mark()
        tracker.clear()
        assertFalse(tracker.isPresent)
        tracker.stripFrom(points) // no-op
        assertTrue(points.isEmpty())
    }

    @Test
    fun `reconcile cycle predicted tail is replaced by the next real sample`() {
        val tracker = MotionPredictionPolicy.PredictedTailTracker()
        val points = mutableListOf(PointF(0f, 0f), PointF(4f, 4f))
        // Frame N: the predictor extends the preview.
        points.add(PointF(8f, 9f))
        tracker.mark()
        // Frame N+1: a real event lands — strip first, then append the real point.
        tracker.stripFrom(points)
        val real = PointF(8f, 8f)
        points.add(real)
        // Committed geometry contains ONLY real samples.
        assertEquals(listOf(PointF(0f, 0f), PointF(4f, 4f), real), points)
        assertFalse(tracker.isPresent)
    }

    // ---- 5. Source pins: AnnotationCanvas wiring ------------------------------

    private fun source(rel: String): String =
        File(repoRoot(), rel).readText()

    private fun canvasSource(): String =
        source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")

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

    @Test
    fun `canvas instantiates the OS predictor behind the API gate`() {
        val src = canvasSource()
        assertTrue(src.contains("com.authorss81.noteflow.services.MotionPredictionPolicy.isSupported"))
        assertTrue(src.contains("androidx.input.motionprediction.MotionEventPredictor.newInstance(hostView)"))
        // newInstance failure falls back to null (stabilizer-only), never crashes.
        assertTrue(src.contains("} catch (t: Throwable) {"))
    }

    @Test
    fun `pointerInteropFilter records every real event and keeps its passive bridge`() {
        val src = canvasSource()
        // Pressure/tilt/timestamp bridging is intact (must not break phase-124).
        assertTrue(src.contains("lastPressure = motionEvent.pressure"))
        assertTrue(src.contains("motionEvent.getAxisValue(android.view.MotionEvent.AXIS_TILT)"))
        assertTrue(src.contains("predictionPointerCount.set(motionEvent.pointerCount)"))
        assertTrue(src.contains("motionPredictor?.record(motionEvent)"))
    }

    @Test
    fun `prediction is drawn through the activePoints preview and reconciled at every real hop`() {
        val src = canvasSource()
        // record/predict split: predict happens in the per-frame loop...
        assertTrue(src.contains("predictor.predict()"))
        // Review-fix: the loop is re-keyed on page geometry too, so a
        // mid-session orientation/continuous-mode change can never leave stale
        // bounds captured in its closure.
        assertTrue(
            src.contains(
                "LaunchedEffect(motionPredictor, currentTool, pressureCurve, " +
                    "pageWidthPx, pageHeightPx, isContinuousMode)"
            )
        )
        // ...and the tail is reconciled: frame loop (!extend + replace),
        // top of onDrag and top of onDragEnd — both BEFORE any early-return.
        // Phase 249 added a FIFTH hop: the card-hit onDragStart early-return
        // drops the tail before it claims the gesture (a tail left over from a
        // prior freehand stroke would otherwise render as a ghost segment ahead
        // of the next stroke's first real sample).
        val expectedDropCallSites = 5
        assertEquals(
            "dropPredictedTail() must be called exactly at the $expectedDropCallSites reconcile hops",
            expectedDropCallSites + 1, // +1 = the definition itself
            src.countOccurrences("dropPredictedTail()")
        )
        // Ordering pin (phase-249): in onDragStart, the card-hit branch strips
        // the predicted tail BEFORE isDraggingCard = true and its early-return.
        val cardHitDragStart = src.indexOf("if (isHittingCard(canvasOffset)) {")
        assertTrue(cardHitDragStart >= 0)
        val tailBeforeCardClaim = src.indexOf("dropPredictedTail()", cardHitDragStart)
        val cardClaim = src.indexOf("isDraggingCard = true", cardHitDragStart)
        assertTrue(
            "onDragStart must strip the predicted tail BEFORE the card claim",
            tailBeforeCardClaim in 0 until cardClaim
        )
        // Ordering pin (review-fix): in onDrag, reconcile precedes the FIRST
        // early-return (isDraggingCard). The first `onDrag = {` in the file is
        // the freehand detectDragGestures handler; every other one comes later.
        val dragStart = src.indexOf("onDrag = { change, dragAmount ->")
        assertTrue(dragStart >= 0)
        val dragStrip = src.indexOf("dropPredictedTail()", dragStart)
        val dragEarlyReturn = src.indexOf("return@detectDragGestures", dragStart)
        assertTrue(
            "onDrag must strip the predicted tail BEFORE its early-returns",
            dragStrip in 0 until dragEarlyReturn
        )
        // Same for onDragEnd: strip first, then any early-return, then commit.
        val dragEndStart = src.indexOf("onDragEnd = {")
        assertTrue(dragEndStart > dragStart)
        val endStrip = src.indexOf("dropPredictedTail()", dragEndStart)
        val endEarlyReturn = src.indexOf("return@detectDragGestures", dragEndStart)
        assertTrue(
            "onDragEnd must strip the predicted tail BEFORE its early-returns",
            endStrip in 0 until endEarlyReturn
        )
        // Commit-time guarantee: strip runs before the geometry snapshot.
        assertTrue(src.indexOf("val pointsToSimplify") > endStrip)
        // Every wholesale clear of the preview resets the flag so a future
        // stroke's first REAL point can never be wrongly stripped.
        assertEquals(4, src.countOccurrences("predictedTailTracker.clear()"))
    }

    @Test
    fun `existing stabilizer path is untouched`() {
        val src = canvasSource()
        assertTrue(src.contains("val stabilizerFilter = remember { StrokeStabilizer.create() }"))
        assertTrue(src.contains("stabilizerFilter.reset()"))
        // Phase 214 moved the capture call to the full-channel overload
        // (pressure/tilt/velocity/timestamp); the prediction tail pipeline
        // around it is unchanged.
        assertTrue(src.contains("val s = stabilizerFilter.next("))
    }

    @Test
    fun `dependency is pinned in both gradle catalogs`() {
        val toml = source("gradle/libs.versions.toml")
        assertTrue(toml.contains("motionPrediction = \"1.0.0\""))
        assertTrue(toml.contains("androidx-input-motionprediction = { group = \"androidx.input\", name = \"input-motionprediction\", version.ref = \"motionPrediction\" }"))
        val build = source("app/build.gradle.kts")
        assertTrue(build.contains("implementation(libs.androidx.input.motionprediction)"))
    }
}

private fun String.countOccurrences(needle: String): Int {
    var count = 0
    var idx = indexOf(needle)
    while (idx != -1) {
        count++
        idx = indexOf(needle, idx + needle.length)
    }
    return count
}
