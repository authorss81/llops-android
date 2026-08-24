package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.StrokeGeometryGateResult
import com.authorss81.noteflow.services.StrokeGeometryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 50 (B2-DOS-01): there is now a size gate everywhere stroke geometry
 * flows between DB bytes and the composable. This test pins the pure-JVM
 * policy that owns the budgets plus the source-level wiring of the fixes into
 * the Android-bound paths (repository save/load, import restore, serializer,
 * canvas renderer).
 *
 * Budgets under test:
 *  - a single stroke never holds more than [StrokeGeometryPolicy.MAX_POINTS_PER_STROKE]
 *    points — write + load truncate to the stroke's head;
 *  - a page never totals more than [StrokeGeometryPolicy.MAX_POINTS_PER_PAGE]
 *    points or [StrokeGeometryPolicy.MAX_STROKES_PER_PAGE] strokes — the page
 *    budget drops the overflowing strokes at save, and load stops early;
 *  - a stored (encrypted) pointsJson whose base64 length exceeds
 *    [StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS] is refused by the DAO
 *    filter / restore strip BEFORE any decrypt;
 *  - a decrypted pointsJson longer than
 *    [StrokeGeometryPolicy.MAX_STROKE_JSON_PLAINTEXT_CHARS] is refused by
 *    [EncryptionService.deserializeStrokes] BEFORE any Gson object graph.
 */
class B2Dos01StrokeGeometryTest {

    private fun stroke(id: String, pointCount: Int, tool: StrokeTool = StrokeTool.PEN): Stroke =
        Stroke(
            id = id,
            tool = tool,
            colorInt = 0xFF1B365D.toInt(),
            width = 3f,
            points = (0 until pointCount).map { i -> PointF(i.toFloat(), i * 2f) }
        )

    // ---------- write gate (StrokeGeometryPolicy.applySaveGate) ----------

    @Test
    fun `a stroke above the per-stroke cap is truncated at save, head preserved`() {
        val over = stroke("s1", StrokeGeometryPolicy.MAX_POINTS_PER_STROKE + 500)
        val result = StrokeGeometryPolicy.applySaveGate(listOf(over))

        assertTrue(result.geometryWasCapped)
        assertEquals(1, result.truncatedStrokes)
        assertEquals(StrokeGeometryPolicy.MAX_POINTS_PER_STROKE, result.kept.single().points.size)
        assertEquals(0f, result.kept.single().points.first().x, 0f)
        assertEquals(
            (StrokeGeometryPolicy.MAX_POINTS_PER_STROKE - 1) * 2f,
            result.kept.single().points.last().y,
            0f
        )
    }

    @Test
    fun `in-budget strokes pass through the save gate unchanged`() {
        val ok = listOf(
            stroke("a", 100),
            stroke("b", 2000, StrokeTool.TEXT),
            stroke("c", 0)
        )
        val result = StrokeGeometryPolicy.applySaveGate(ok)

        assertFalse(result.geometryWasCapped)
        assertEquals(ok.map { it.id }, result.kept.map { it.id })
        assertEquals(listOf(100, 2000, 0), result.kept.map { it.points.size })
        assertEquals(StrokeGeometryPolicy.totalPoints(ok), result.pointsAfter)
    }

    @Test
    fun `strokes beyond the total page points budget are dropped`() {
        // Eleven strokes that each fit the per-stroke cap but whose SUM exceeds
        // MAX_POINTS_PER_PAGE: only the first ten can be represented.
        val page = (0 until 11).map { i ->
            stroke("s$i", StrokeGeometryPolicy.MAX_POINTS_PER_STROKE)
        }
        val result = StrokeGeometryPolicy.applySaveGate(page)

        assertTrue(result.geometryWasCapped)
        assertEquals(1, result.droppedStrokes)
        assertEquals(10, result.kept.size)
        assertEquals(StrokeGeometryPolicy.MAX_POINTS_PER_PAGE, result.pointsAfter)
        assertEquals(0, result.truncatedStrokes)
    }

    @Test
    fun `strokes beyond the per-page stroke count budget are dropped`() {
        val many = (0 until StrokeGeometryPolicy.MAX_STROKES_PER_PAGE + 25).map { stroke("s$it", 1) }
        val result = StrokeGeometryPolicy.applySaveGate(many)

        assertTrue(result.geometryWasCapped)
        assertEquals(25, result.droppedStrokes)
        assertEquals(StrokeGeometryPolicy.MAX_STROKES_PER_PAGE, result.kept.size)
    }

    @Test
    fun `gate results meter before and after counts for the notice`() {
        val big = stroke("big", StrokeGeometryPolicy.MAX_POINTS_PER_STROKE + 1000)
        val tail = stroke("tail", 50)
        val result: StrokeGeometryGateResult =
            StrokeGeometryPolicy.applySaveGate(listOf(big, tail))

        assertTrue(result.pointsBefore > result.pointsAfter)
        assertTrue(result.strokesBefore >= result.strokesAfter)
        assertTrue(result.noticeText.contains("capped"))
    }

    @Test
    fun `gateStroke keeps at most MAX_POINTS_PER_STROKE points and reuses the input when small`() {
        val small = stroke("s", 7)
        assertEquals(small, StrokeGeometryPolicy.gateStroke(small))
        val big = stroke("b", StrokeGeometryPolicy.MAX_POINTS_PER_STROKE + 3)
        assertEquals(StrokeGeometryPolicy.MAX_POINTS_PER_STROKE, StrokeGeometryPolicy.gateStroke(big).points.size)
    }

    // ---------- read gate ----------

    @Test
    fun `capLoadedPoints truncates a legacy over-specified stroke at load`() {
        val over = (0 until StrokeGeometryPolicy.MAX_POINTS_PER_STROKE + 10)
            .map { PointF(it.toFloat(), it.toFloat()) }
        val capped = StrokeGeometryPolicy.capLoadedPoints(over)
        assertEquals(StrokeGeometryPolicy.MAX_POINTS_PER_STROKE, capped.size)
        val under = listOf(PointF(1f, 2f), PointF(3f, 4f))
        assertEquals(under, StrokeGeometryPolicy.capLoadedPoints(under))
    }

    @Test
    fun `stored ciphertext length is the pre-decrypt gate for a single stroke row`() {
        assertTrue(StrokeGeometryPolicy.storedPointsJsonOverBudget(StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS + 1))
        assertFalse(
            StrokeGeometryPolicy.storedPointsJsonOverBudget(StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS - 1)
        )
        assertTrue(StrokeGeometryPolicy.plaintextPointsJsonOverBudget(StrokeGeometryPolicy.MAX_STROKE_JSON_PLAINTEXT_CHARS + 1))
        assertFalse(
            StrokeGeometryPolicy.plaintextPointsJsonOverBudget(StrokeGeometryPolicy.MAX_STROKE_JSON_PLAINTEXT_CHARS - 1)
        )
    }

    // ---------- serializer belt-and-braces ----------

    @Test
    fun `deserializeStrokes refuses an oversized plaintext payload before parsing`() {
        val oversized = "x".repeat(StrokeGeometryPolicy.MAX_STROKE_JSON_PLAINTEXT_CHARS + 1)
        assertTrue(EncryptionService.deserializeStrokes(oversized).isEmpty())
    }

    @Test
    fun `deserializeStrokes still round-trips a legal max-size stroke`() {
        val max = stroke("max", StrokeGeometryPolicy.MAX_POINTS_PER_STROKE)
        val json = EncryptionService.serializeStrokes(listOf(max))
        assertFalse(StrokeGeometryPolicy.plaintextPointsJsonOverBudget(json.length))
        val restored = EncryptionService.deserializeStrokes(json)
        assertEquals(1, restored.size)
        assertEquals(StrokeGeometryPolicy.MAX_POINTS_PER_STROKE, restored[0].points.size)
    }

    @Test
    fun `serialized max-budget page stays under the stored ciphertext envelope`() {
        val page = (0 until 10).map { i ->
            stroke("p$i", StrokeGeometryPolicy.MAX_POINTS_PER_STROKE)
        }
        val gate = StrokeGeometryPolicy.applySaveGate(page)
        var totalStored = 0
        for (s in gate.kept) {
            val json = EncryptionService.serializeStrokes(listOf(s.copy(text = "")))
            val ciphertext = EncryptionService.encryptField(json.toByteArray(), "k".toByteArray(Charsets.UTF_8).copyOf(32), "strokes", s.id, "pointsJson")
            totalStored += ciphertext.length
            assertFalse(StrokeGeometryPolicy.storedPointsJsonOverBudget(ciphertext.length))
        }
        // The whole page's stored geometry stays comfortably bounded.
        assertTrue(totalStored < 10L * StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS)
    }

    // ---------- wiring pins (source-level, Android-bound code) ----------

    @Test
    fun `repository save path applies the gate and never writes raw geometry`() {
        val source = readNoteRepositorySource()
        val saveBlock = source.substringAfter("fun saveStrokesForPage", "END")
        assertTrue(
            "saveStrokesForPage must route the incoming strokes through the geometry policy",
            saveBlock.contains("StrokeGeometryPolicy.applySaveGate")
        )
        assertTrue(
            "saveStrokesForPage must report the gate result (notice wiring)",
            saveBlock.contains("StrokeGeometryGateResult")
        )
        assertTrue(
            "the per-stroke serialization must use the GATED stroke, not the raw incoming one",
            saveBlock.contains("dummyStroke = stroke.copy")
        )
    }

    @Test
    fun `repository load path is bounded end to end`() {
        val source = readNoteRepositorySource()
        val loadBlock = source.substringAfter("fun getStrokesForPage", "END")
        assertTrue(
            "getStrokesForPage must page through the bounded DAO reader",
            loadBlock.contains("getStrokesForPageBounded")
        )
        assertTrue(
            "getStrokesForPage must stop once the page budget is consumed",
            loadBlock.contains("MAX_POINTS_PER_PAGE") && loadBlock.contains("budgetExhausted")
        )
        assertTrue(
            "getStrokesForPage must cap legacy over-specified strokes",
            loadBlock.contains("capLoadedPoints")
        )
        assertTrue(
            "no unbounded all-row load may remain in getStrokesForPage",
            !loadBlock.contains("db.strokeDao().getStrokesForPage(pageId)")
        )
    }

    @Test
    fun `stroke dao exposes the paginated length-gated reader`() {
        val source = readDaosSource()
        assertTrue(
            "StrokeDao must have a LIMIT/OFFSET plus length-gated reader",
            source.contains("getStrokesForPageBounded") &&
                source.contains("LIMIT :limit OFFSET :offset") &&
                source.contains("length(pointsJson) <= :maxStoredChars")
        )
    }

    @Test
    fun `restore strips oversized stroke rows before they reach the live vault`() {
        val source = readImportExportSource()
        assertTrue(
            "restore must run the sanitizer on the opened backup DB",
            source.contains("sanitizeRestoredStrokeGeometry(db)")
        )
        assertTrue(
            "the sanitizer must delete strokes whose stored pointsJson exceeds the budget",
            source.contains("DELETE FROM strokes WHERE length(pointsJson) > ?") &&
                source.contains("MAX_STORED_POINTS_JSON_CHARS")
        )
    }

    @Test
    fun `serializer guards the Gson parse with the plaintext budget`() {
        val source = readEncryptionServiceSource()
        assertTrue(
            "deserializeStrokes must refuse oversized plaintext before Gson",
            source.contains("StrokeGeometryPolicy.plaintextPointsJsonOverBudget(json.length)")
        )
    }

    @Test
    fun `viewmodel surfaces a one-time non-alarming notice when geometry was capped`() {
        val source = readNoteflowViewModelSource()
        assertTrue(
            "save paths must surface the gate notice through the capped latch",
            source.contains("maybeNotifyGeometryCapped") &&
                source.contains(".geometryWasCapped") &&
                source.contains("gate.noticeText")
        )
        assertTrue(
            "the notice must be one-time per page per session (no snackbar spam)",
            source.contains("geometryCappedNotifiedPages")
        )
    }

    @Test
    fun `canvas renderer skips off-viewport pages instead of walking every point`() {
        val source = readAnnotationCanvasSource()
        assertTrue(
            "the paginated renderer must derive the visible world rect from pan/zoom",
            source.contains("internalPanOffset") && source.contains("internalZoomScale")
        )
        // Phase 198 (PERF 2.5): the visible window is resolved in closed form by
        // ViewportPageWindowPolicy BEFORE the loop — O(visiblePages) iterations,
        // not iterate-every-page-and-`continue` — with identical skip semantics
        // (parity pinned exhaustively in ViewportPageWindowPolicyTest).
        assertTrue(
            "the paginated renderer must resolve the visible page window in closed form",
            source.contains("com.authorss81.noteflow.services.ViewportPageWindowPolicy.visiblePageRange(")
        )
        assertTrue(
            "the paginated loop must touch only the pages in that window",
            source.contains("for (pageIdx in visiblePageWindow) {")
        )
        assertTrue(
            "the horizontal band guard must skip the whole world when panned past the edges",
            source.contains("(size.width - internalPanOffset.x) / internalZoomScale) < 0f")
        )
    }

    // ---------- source readers ----------

    private fun readNoteRepositorySource(): String =
        readSource("data/repository/NoteRepository.kt")

    private fun readDaosSource(): String =
        readSource("data/db/Daos.kt")

    private fun readImportExportSource(): String =
        readSource("services/ImportExportService.kt")

    private fun readEncryptionServiceSource(): String =
        readSource("services/EncryptionService.kt")

    private fun readNoteflowViewModelSource(): String =
        readSource("ui/viewmodel/NoteflowViewModel.kt")

    private fun readAnnotationCanvasSource(): String =
        readSource("ui/components/AnnotationCanvas.kt")

    private fun readSource(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}