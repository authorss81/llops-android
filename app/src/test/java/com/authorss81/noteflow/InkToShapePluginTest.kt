package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.plugins.InMemoryPluginSettingsStore
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginEnableResult
import com.authorss81.noteflow.plugins.PluginFailureReason
import com.authorss81.noteflow.plugins.PluginLifecycleState
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.ShapeFromInkOutcome
import com.authorss81.noteflow.plugins.ShapeKind
import com.authorss81.noteflow.plugins.inktos.InkPoint
import com.authorss81.noteflow.plugins.inktos.InkToShapeGeometry
import com.authorss81.noteflow.plugins.inktos.InkToShapePlugin
import com.authorss81.noteflow.plugins.store.InMemoryPluginInstallStore
import com.authorss81.noteflow.plugins.store.PluginStoreCatalog
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 25 — InkStroke→Shape plugin tests. PURE JVM, no Android deps:
 *
 * 1. Geometry-unit tests against synthetic point sets (straight, slightly-wavy,
 *    closed loops, and wrong-shaped strokes that MUST NOT convert).
 * 2. Plugin conversion tests (Stroke → crisp ShapeFromInkOutcome).
 * 3. Settings-toggle behaviour (namespaced `keepOriginal`: replace vs insert).
 * 4. Capability routing through the registry/manager + store listing.
 */
class InkToShapePluginTest {

    // ---------------------------------------------------------------------
    // synthetic point-set generators (deterministic, pure JVM)
    // ---------------------------------------------------------------------

    /** Uniformly sampled straight segment. */
    private fun straight(from: Pair<Float, Float>, to: Pair<Float, Float>, samples: Int = 24): List<InkPoint> {
        return (0 until samples).map { i ->
            val t = i.toFloat() / (samples - 1)
            InkPoint(from.first + (to.first - from.first) * t, from.second + (to.second - from.second) * t)
        }
    }

    /** Sine-wave wavy stroke along the x axis. */
    private fun wavy(amplitude: Float, waves: Int, length: Float = 200f, samples: Int = 60): List<InkPoint> {
        return (0 until samples).map { i ->
            val t = i.toFloat() / (samples - 1)
            InkPoint(length * t, amplitude * sin(2 * PI * waves * t).toFloat())
        }
    }

    /** Closed ellipse/circle perimeter (includes the closing point). */
    private fun closedEllipse(cx: Float, cy: Float, rx: Float, ry: Float, steps: Int = 36): List<InkPoint> {
        return (0..steps).map { i ->
            val a = 2 * PI * i / steps
            InkPoint(cx + rx * cos(a).toFloat(), cy + ry * sin(a).toFloat())
        }
    }

    /** Traced rectangle perimeter (with a small deterministic wobble). */
    private fun rectTrace(w: Float, h: Float, perEdge: Int = 8, wobble: Float = 1.5f): List<InkPoint> {
        val pts = ArrayList<InkPoint>()
        fun w(i: Int) = ((i % 5) - 2) * wobble * 0.25f
        var k = 0
        for (edge in 0 until 4) {
            for (i in 0 until perEdge) {
                val t = i.toFloat() / (perEdge - 1)
                val p = when (edge) {
                    0 -> InkPoint(w * t + w(k), w(k + 3))
                    1 -> InkPoint(w + w(k + 1), h * t + w(k + 4))
                    2 -> InkPoint(w - w * t + w(k + 2), h + w(k + 5))
                    else -> InkPoint(w(k + 3), h - h * t + w(k + 6))
                }
                pts.add(p)
                k++
            }
        }
        pts.add(InkPoint(0f, 0f)) // close the loop
        return pts
    }

    /** Traced rounded-rectangle perimeter with corner radius [r]. */
    private fun roundedRectTrace(w: Float, h: Float, r: Float, perEdge: Int = 8, arcSteps: Int = 6): List<InkPoint> {
        val pts = ArrayList<InkPoint>()
        fun addEdge(a: InkPoint, b: InkPoint) {
            for (i in 0 until perEdge) {
                val t = i.toFloat() / (perEdge - 1)
                pts.add(InkPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t))
            }
        }
        fun addArc(cx: Float, cy: Float, a0: Double, a1: Double) {
            for (i in 0 .. arcSteps) {
                val a = a0 + (a1 - a0) * i / arcSteps
                pts.add(InkPoint(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()))
            }
        }
        val topLeft = InkPoint(r, 0f) // start == end after the full trace
        pts.add(topLeft)
        addEdge(InkPoint(r, 0f), InkPoint(w - r, 0f))
        addArc(w - r, r, -PI / 2, 0.0)
        addEdge(InkPoint(w, r), InkPoint(w, h - r))
        addArc(w - r, h - r, 0.0, PI / 2)
        addEdge(InkPoint(w - r, h), InkPoint(r, h))
        addArc(r, h - r, PI / 2, PI)
        addEdge(InkPoint(0f, h - r), InkPoint(0f, r))
        addArc(r, r, PI, 3 * PI / 2)
        return pts
    }

    /** Hand-drawn arrow: straight shaft + a closing vee head. [angleDegrees] = shaft bearing. */
    private fun arrowStroke(length: Float = 200f, angleDegrees: Float = 0f, shaftSamples: Int = 24): List<InkPoint> {
        val ang = angleDegrees * PI / 180.0
        val tip = InkPoint((length * cos(ang)).toFloat(), (length * sin(ang)).toFloat())
        val pts = ArrayList<InkPoint>()
        for (i in 0 until shaftSamples) {
            val t = i.toFloat() / (shaftSamples - 1)
            pts.add(InkPoint((length * t * cos(ang)).toFloat(), (length * t * sin(ang)).toFloat()))
        }
        val headLength = min(35f, max(15f, length * 0.2f))
        val headAngle = PI / 6.0 // 30°
        val p1 = InkPoint(
            (tip.x - headLength * cos(ang - headAngle).toFloat()),
            (tip.y - headLength * sin(ang - headAngle).toFloat())
        )
        val p2 = InkPoint(
            (tip.x - headLength * cos(ang + headAngle).toFloat()),
            (tip.y - headLength * sin(ang + headAngle).toFloat())
        )
        pts.add(tip); pts.add(p1); pts.add(tip); pts.add(p2)
        return pts
    }

    /** Zigzag (lightning bolt) open stroke — must never convert. */
    private fun zigzag(): List<InkPoint> {
        val bends = listOf(0f to 0f, 30f to 40f, 60f to -40f, 90f to 40f, 120f to -40f, 150f to 0f)
        val pts = ArrayList<InkPoint>()
        for (s in 0 until bends.size - 1) {
            val a = bends[s]; val b = bends[s + 1]
            for (i in 0 until 6) {
                val t = i / 5f
                pts.add(InkPoint(a.first + (b.first - a.first) * t, a.second + (b.second - a.second) * t))
            }
        }
        return pts
    }

    /** Closed triangle scribble — closed loop but NOT a rectangle/ellipse. */
    private fun triangleClosed(): List<InkPoint> {
        val a = 0f to 0f; val b = 200f to 0f; val c = 100f to 140f
        val pts = ArrayList<InkPoint>()
        fun edge(p: Pair<Float, Float>, q: Pair<Float, Float>) {
            for (i in 0 until 12) {
                val t = i / 11f
                pts.add(InkPoint(p.first + (q.first - p.first) * t, p.second + (q.second - p.second) * t))
            }
        }
        edge(a, b); edge(b, c); edge(c, a)
        return pts
    }

    /** Random radial blob — closed but noisy; must never convert. */
    private fun blob(): List<InkPoint> {
        return (0 until 24).map { i ->
            val a = 2 * PI * i / 24
            val r = 80f + ((i * 37 % 11) - 5) * 9f
            InkPoint(100f + r * cos(a).toFloat(), 100f + r * sin(a).toFloat())
        }
    }

    private fun strokeOf(points: List<InkPoint>): Stroke =
        Stroke(
            id = UUID.randomUUID().toString(),
            tool = StrokeTool.PEN,
            points = points.map { PointF(it.x, it.y) },
            start = points.firstOrNull()?.let { PointF(it.x, it.y) },
            end = points.lastOrNull()?.let { PointF(it.x, it.y) }
        )

    // ---------------------------------------------------------------------
    // 1. geometry core — detection accuracy
    // ---------------------------------------------------------------------

    @Test
    fun `straight line converts to LINE`() {
        val d = InkToShapeGeometry.detect(straight(0f to 0f, 200f to 0f))
        assertEquals(InkToShapeGeometry.ShapeType.LINE, d!!.type)
        assertEquals(2, d.points.size)
        assertEquals(InkPoint(0f, 0f), d.points.first())
        assertEquals(InkPoint(200f, 0f), d.points.last())
    }

    @Test
    fun `RDP-simplified straight line with just two points still converts`() {
        val d = InkToShapeGeometry.detect(listOf(InkPoint(0f, 0f), InkPoint(200f, 0f)))
        assertEquals(InkToShapeGeometry.ShapeType.LINE, d!!.type)
    }

    @Test
    fun `diagonal straight line converts to LINE`() {
        val d = InkToShapeGeometry.detect(straight(50f to 80f, 250f to 40f))
        assertEquals(InkToShapeGeometry.ShapeType.LINE, d!!.type)
        assertEquals(2, d.points.size)
    }

    @Test
    fun `slightly-wavy stroke straightens to LINE`() {
        val d = InkToShapeGeometry.detect(wavy(amplitude = 3f, waves = 2))
        assertEquals(InkToShapeGeometry.ShapeType.LINE, d!!.type)
        assertEquals(2, d.points.size)
    }

    @Test
    fun `closed circle converts to ELLIPSE with high circularity`() {
        val d = InkToShapeGeometry.detect(closedEllipse(100f, 100f, 80f, 80f))
        assertEquals(InkToShapeGeometry.ShapeType.ELLIPSE, d!!.type)
        assertTrue("circularity ~= 1.0 for a circle", (d!!.circularity ?: 0f) > 0.8f)
        assertTrue("snapped shape is crisper than the raw", d.points.size == 37)
    }

    @Test
    fun `closed ellipse converts to ELLIPSE`() {
        val d = InkToShapeGeometry.detect(closedEllipse(120f, 90f, 60f, 40f))
        assertEquals(InkToShapeGeometry.ShapeType.ELLIPSE, d!!.type)
        assertTrue((d!!.circularity ?: 0f) > 0.5f)
    }

    @Test
    fun `traced rectangle converts to RECTANGLE`() {
        val trace = rectTrace(200f, 100f)
        val d = InkToShapeGeometry.detect(trace)
        assertEquals(InkToShapeGeometry.ShapeType.RECTANGLE, d!!.type)
        assertEquals(5, d.points.size)
        assertTrue(d!!.cornerCoverage >= 2)
        val traceXs = trace.map { it.x }
        val traceYs = trace.map { it.y }
        val xs = d.points.map { it.x }
        val ys = d.points.map { it.y }
        assertEquals(traceXs.min() as Float, xs.min(), 0.001f)
        assertEquals(traceXs.max() as Float, xs.max(), 0.001f)
        assertEquals(traceYs.min() as Float, ys.min(), 0.001f)
        assertEquals(traceYs.max() as Float, ys.max(), 0.001f)
    }

    @Test
    fun `rounded-rectangle converts to RECTANGLE`() {
        val d = InkToShapeGeometry.detect(roundedRectTrace(200f, 100f, r = 8f))
        assertEquals(InkToShapeGeometry.ShapeType.RECTANGLE, d!!.type)
        assertTrue(d!!.cornerCoverage >= 2)
    }

    @Test
    fun `horizontal arrow converts to ARROW with a direction change`() {
        val d = InkToShapeGeometry.detect(arrowStroke(200f, angleDegrees = 0f))
        assertEquals(InkToShapeGeometry.ShapeType.ARROW, d!!.type)
        assertTrue(d!!.endDirectionChangeDegrees > 10f)
        assertEquals(5, d.points.size)
    }

    @Test
    fun `diagonal arrow converts to ARROW`() {
        val d = InkToShapeGeometry.detect(arrowStroke(200f, angleDegrees = 45f))
        assertEquals(InkToShapeGeometry.ShapeType.ARROW, d!!.type)
    }

    @Test
    fun `long arrow converts to ARROW not LINE`() {
        val d = InkToShapeGeometry.detect(arrowStroke(600f, angleDegrees = 0f))
        assertEquals(InkToShapeGeometry.ShapeType.ARROW, d!!.type)
        assertTrue(d!!.endDirectionChangeDegrees > 10f)
        assertEquals(5, d.points.size)
    }

    // ---- 1b. wrong-shaped strokes must NOT convert ------------------------

    @Test
    fun `very-wavy open stroke is rejected`() {
        assertNull(InkToShapeGeometry.detect(wavy(amplitude = 40f, waves = 3)))
    }

    @Test
    fun `zigzag open stroke is rejected`() {
        assertNull(InkToShapeGeometry.detect(zigzag()))
    }

    @Test
    fun `closed triangle scribble is rejected`() {
        assertNull(InkToShapeGeometry.detect(triangleClosed()))
    }

    @Test
    fun `random closed blob is rejected`() {
        assertNull(InkToShapeGeometry.detect(blob()))
    }

    @Test
    fun `tiny speck is rejected`() {
        assertNull(InkToShapeGeometry.detect(straight(0f to 0f, 10f to 10f)))
    }

    @Test
    fun `too few points is rejected`() {
        assertNull(InkToShapeGeometry.detect(listOf(InkPoint(0f, 0f))))
    }

    // ---------------------------------------------------------------------
    // 2. plugin conversion end-to-end
    // ---------------------------------------------------------------------

    @Test
    fun `plugin converts a line stroke to a crisp LINE stroke`() {
        val plugin = InkToShapePlugin()
        val outcome = plugin.convertToShape(strokeOf(straight(0f to 0f, 200f to 0f)))
        assertTrue(outcome is ShapeFromInkOutcome.Success)
        val s = outcome as ShapeFromInkOutcome.Success
        assertEquals(ShapeKind.LINE, s.kind)
        assertEquals(StrokeTool.LINE, s.snappedStroke.tool)
        assertEquals(2, s.snappedStroke.points.size)
        assertEquals(3f, s.snappedStroke.width) // style carried over
    }

    @Test
    fun `plugin rejects a zigzag stroke honestly`() {
        val plugin = InkToShapePlugin()
        val outcome = plugin.convertToShape(strokeOf(zigzag()))
        assertTrue(outcome is ShapeFromInkOutcome.NotAShape)
    }

    // ---------------------------------------------------------------------
    // 3. setting-toggle behaviour (namespaced keepOriginal)
    // ---------------------------------------------------------------------

    @Test
    fun `keepOriginal setting toggles replace-vs-insert`() {
        val reg = newRegistry()
        reg.setEnabled(InkToShapePlugin.ID, true)
        val manager = PluginManager(reg)
        val lineStroke = strokeOf(straight(0f to 0f, 200f to 0f))

        fun convert(): ShapeFromInkOutcome.Success {
            val res = manager.withPlugin(PluginCapability.ShapeFromInk, null) { plugin ->
                (plugin as InkToShapePlugin).convertToShape(lineStroke)
            }
            return (res as PluginResult.Success).value as ShapeFromInkOutcome.Success
        }

        // Default: keepOriginal = false → the shape REPLACES the raw stroke.
        assertFalse(reg.settingsFor(InkToShapePlugin.ID).containsKey(InkToShapePlugin.SETTING_KEEP_ORIGINAL))
        assertTrue(convert().replaceOriginal)

        // keepOriginal on → the raw stroke is KEPT and the shape inserted alongside.
        reg.settingsFor(InkToShapePlugin.ID).setBoolean(InkToShapePlugin.SETTING_KEEP_ORIGINAL, true)
        reg.notifyConfigChanged(InkToShapePlugin.ID)
        val kept = convert()
        assertFalse(kept.replaceOriginal)
        // The snapped shape must carry a NEW id — reusing the raw stroke's id
        // would collide on the strokes table primary key and drop one of them
        // on save (NoteRepository.saveStrokesForPage upserts by id).
        assertNotEquals(lineStroke.id, kept.snappedStroke.id)

        // Back off → replace again.
        reg.settingsFor(InkToShapePlugin.ID).setBoolean(InkToShapePlugin.SETTING_KEEP_ORIGINAL, false)
        reg.notifyConfigChanged(InkToShapePlugin.ID)
        assertTrue(convert().replaceOriginal)
    }

    // ---------------------------------------------------------------------
    // 4. capability routing + store listing
    // ---------------------------------------------------------------------

    private fun newRegistry(): PluginRegistry = PluginRegistry(
        enableStore = InMemoryEnableStore(),
        settingsStore = InMemoryPluginSettingsStore(),
        currentApiLevel = 26
    )

    @Test
    fun `shape_from_ink capability resolves by key`() {
        assertEquals(PluginCapability.ShapeFromInk, PluginCapability.byKey("shape_from_ink"))
    }

    @Test
    fun `plugin is bundled, installed by default and off until enabled`() {
        val reg = newRegistry()
        assertTrue(reg.allPlugins.any { it.id == InkToShapePlugin.ID })
        assertTrue(reg.isBuiltIn(InkToShapePlugin.ID))
        assertFalse(reg.isEnabled(InkToShapePlugin.ID))
        assertEquals(PluginLifecycleState.REGISTERED, reg.stateOf(InkToShapePlugin.ID)?.state)

        // Routing refuses while it is off — NONE_ENABLED, never a silent no-op.
        val manager = PluginManager(reg)
        val res = manager.withPlugin(PluginCapability.ShapeFromInk, null) { plugin ->
            (plugin as InkToShapePlugin).convertToShape(strokeOf(straight(0f to 0f, 200f to 0f)))
        }
        assertEquals(PluginFailureReason.NONE_ENABLED, (res as PluginResult.Failure).reason)
    }

    @Test
    fun `enabled plugin routes and converts`() {
        val reg = newRegistry()
        assertEquals(
            PluginEnableResult.Changed(InkToShapePlugin.ID, nowEnabled = true),
            reg.setEnabled(InkToShapePlugin.ID, true)
        )
        assertEquals(PluginLifecycleState.AVAILABLE, reg.stateOf(InkToShapePlugin.ID)?.state)

        val manager = PluginManager(reg)
        val res = manager.withPlugin(PluginCapability.ShapeFromInk, null) { plugin ->
            (plugin as InkToShapePlugin).convertToShape(strokeOf(closedEllipse(100f, 100f, 70f, 70f)))
        }
        assertTrue(res is PluginResult.Success)
        val outcome = (res as PluginResult.Success).value
        assertTrue(outcome is ShapeFromInkOutcome.Success)
        assertEquals(ShapeKind.ELLIPSE, (outcome as ShapeFromInkOutcome.Success).kind)
    }

    @Test
    fun `disabled plugin is skipped by capability routing`() {
        val reg = newRegistry()
        reg.setEnabled(InkToShapePlugin.ID, true)
        reg.setEnabled(InkToShapePlugin.ID, false)
        val manager = PluginManager(reg)
        val res = manager.withPlugin(PluginCapability.ShapeFromInk, null) { plugin ->
            (plugin as InkToShapePlugin).convertToShape(strokeOf(straight(0f to 0f, 200f to 0f)))
        }
        assertEquals(PluginFailureReason.NONE_ENABLED, (res as PluginResult.Failure).reason)
    }

    @Test
    fun `plugin is listed in the store as a bundled built-in under Canvas`() {
        val installStore = InMemoryPluginInstallStore(PluginRegistry.defaultPlugins().map { it.id })
        val reg = PluginRegistry(
            enableStore = InMemoryEnableStore(),
            settingsStore = InMemoryPluginSettingsStore(),
            installStore = installStore,
            currentApiLevel = 26
        )
        val catalog = PluginStoreCatalog(reg)
        val entry = catalog.entryFor(InkToShapePlugin.ID)
        assertNotNull(entry)
        assertTrue(entry!!.bundled)
        assertFalse(entry.optional)
        assertEquals("Canvas", entry.category)
        assertTrue(reg.isInstalled(InkToShapePlugin.ID))
    }
}