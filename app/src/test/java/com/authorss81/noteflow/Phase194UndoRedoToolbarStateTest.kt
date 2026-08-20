package com.authorss81.noteflow

import com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 194: Undo/Redo toolbar-state (dim/disable) + Canvas & Paper Options
 * bottom-sheet scrollability regression guard.
 *
 * User report: the canvas Undo/Redo buttons were always the same bright color
 * even with an empty stack, and every option below the fold in the "Canvas &
 * Paper Options" sheet was clipped/unreachable. Fix: one pure-JVM policy owns
 * the enabled+dim decision (`CanvasUndoRedoStatePolicy`) wired into both ink
 * bars, and the sheet content Column is vertically scrollable.
 *
 * This suite is pure JVM: it exercises the decision table directly, drives the
 * state-machine oracle mirrors, and source-pins the composable wiring.
 */
class Phase194UndoRedoToolbarStateTest {

    // ---- 1. Empty undo/redo = dimmed + disabled ------------------------------

    @Test
    fun `empty undo stack disables and dims the undo button`() {
        assertFalse(CanvasUndoRedoStatePolicy.canUndo(0))
        assertEquals(CanvasUndoRedoStatePolicy.DISABLED_ALPHA, CanvasUndoRedoStatePolicy.iconAlpha(false), 0f)
        assertEquals(0.38f, CanvasUndoRedoStatePolicy.iconAlpha(CanvasUndoRedoStatePolicy.canUndo(0)), 0f)
    }

    @Test
    fun `empty redo stack disables and dims the redo button`() {
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(0))
        assertEquals(CanvasUndoRedoStatePolicy.DISABLED_ALPHA, CanvasUndoRedoStatePolicy.iconAlpha(false), 0f)
        assertEquals(0.38f, CanvasUndoRedoStatePolicy.iconAlpha(CanvasUndoRedoStatePolicy.canRedo(0)), 0f)
    }

    @Test
    fun `disabled alpha is the Material 3 standard`() {
        assertEquals(0.38f, CanvasUndoRedoStatePolicy.DISABLED_ALPHA, 0f)
    }

    // ---- 2. Non-empty stack = bright + enabled -------------------------------

    @Test
    fun `non-empty undo stack enables the undo button at full alpha`() {
        assertTrue(CanvasUndoRedoStatePolicy.canUndo(1))
        assertEquals(1f, CanvasUndoRedoStatePolicy.iconAlpha(true), 0f)
        // Any positive size is actionable — the 30-cap drop-oldest never leaves
        // a stale zero-size "disabled" state while actions remain.
        assertTrue(CanvasUndoRedoStatePolicy.canUndo(30))
        assertEquals(1f, CanvasUndoRedoStatePolicy.iconAlpha(CanvasUndoRedoStatePolicy.canUndo(30)), 0f)
    }

    @Test
    fun `negative or zero sizes never enable a button`() {
        assertFalse(CanvasUndoRedoStatePolicy.canUndo(-1))
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(-1))
    }

    // ---- 3. Reactive transitions (oracle mirrors of EditorScreen) ------------

    @Test
    fun `after an undo the redo stack becomes enabled while undo dims`() {
        // One undoable snapshot: undo bright, redo dimmed.
        assertTrue(CanvasUndoRedoStatePolicy.canUndo(1))
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(0))

        // handleUndo pops undo -> pushes the pre-undo state onto redo.
        val (afterUndoSize, afterRedoSize) = CanvasUndoRedoStatePolicy.afterUndo(undoStackSize = 1, redoStackSize = 0)
        assertEquals(0, afterUndoSize)
        assertEquals(1, afterRedoSize)

        // Redo is now actionable (bright) and undo is dimmed.
        assertFalse(CanvasUndoRedoStatePolicy.canUndo(afterUndoSize))
        assertTrue(CanvasUndoRedoStatePolicy.canRedo(afterRedoSize))
        assertEquals(0.38f, CanvasUndoRedoStatePolicy.iconAlpha(CanvasUndoRedoStatePolicy.canUndo(afterUndoSize)), 0f)
        assertEquals(1f, CanvasUndoRedoStatePolicy.iconAlpha(CanvasUndoRedoStatePolicy.canRedo(afterRedoSize)), 0f)
    }

    @Test
    fun `after an undo on a fresh page (no-op) both buttons stay dimmed`() {
        val (u, r) = CanvasUndoRedoStatePolicy.afterUndo(undoStackSize = 0, redoStackSize = 0)
        assertEquals(0 to 0, u to r)
        assertFalse(CanvasUndoRedoStatePolicy.canUndo(u))
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(r))
    }

    @Test
    fun `after a redo the undo stack re-enables while redo dims again`() {
        val (afterRedoUndo, afterRedoRedo) = CanvasUndoRedoStatePolicy.afterRedo(undoStackSize = 0, redoStackSize = 1)
        assertEquals(1, afterRedoUndo)
        assertEquals(0, afterRedoRedo)
        assertTrue(CanvasUndoRedoStatePolicy.canUndo(afterRedoUndo))
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(afterRedoRedo))
    }

    @Test
    fun `a new stroke clears + dims redo and re-enables undo`() {
        // Drawing the very first stroke: undo +1, redo cleared.
        val (afterStrokeUndo, afterStrokeRedo) = CanvasUndoRedoStatePolicy.afterNewStroke(undoStackSize = 0)
        assertEquals(1, afterStrokeUndo)
        assertEquals(0, afterStrokeRedo)
        assertTrue(CanvasUndoRedoStatePolicy.canUndo(afterStrokeUndo))
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(afterStrokeRedo))

        // Drawing after an undo: the undoable history grows again, redo drops back
        // to dimmed exactly as handleStrokesChange does.
        val (u2, r2) = CanvasUndoRedoStatePolicy.afterNewStroke(undoStackSize = 2)
        assertEquals(3, u2)
        assertEquals(0, r2)
        assertFalse(CanvasUndoRedoStatePolicy.canRedo(r2))
    }

    // ---- 4. Source pins: both toolbars wire the policy ------------------------

    @Test
    fun `editor passes policy-computed canUndo-canRedo into the tool dock`() {
        val src = editorSource()
        assertTrue(src.contains("canUndo = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.canUndo(undoStack.size)"))
        assertTrue(src.contains("canRedo = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.canRedo(redoStack.size)"))
        // The flags flow into FloatingToolDock and on into BOTH bars.
        assertEquals(2, src.countOccurrences("canUndo = canUndo"))
        assertEquals(2, src.countOccurrences("canRedo = canRedo"))
    }

    @Test
    fun `both undo-render sites disable + dim through the policy`() {
        val src = editorSource()
        // Exactly two Undo buttons (portrait + landscape) and two Redo buttons.
        assertEquals(2, src.countOccurrences("enabled = canUndo"))
        assertEquals(2, src.countOccurrences("enabled = canRedo"))
        // Both bars tint via the policy alpha over the existing onSurfaceVariant.
        assertEquals(2, src.countOccurrences("alpha = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.iconAlpha(canUndo)"))
        assertEquals(2, src.countOccurrences("alpha = com.authorss81.noteflow.services.CanvasUndoRedoStatePolicy.iconAlpha(canRedo)"))
        // No old fixed-tint inline Undo/Redo IconButton remains.
        assertFalse(src.contains("IconButton(onClick = onUndo, modifier = Modifier.size(36.dp))"))
        assertFalse(src.contains("IconButton(onClick = onRedo, modifier = Modifier.size(36.dp))"))
        // The DATA logic is untouched (appearance-only change).
        assertTrue(src.contains("if (newUndo.size > 30) newUndo.removeAt(0)"))
        assertTrue(src.contains("redoStack = emptyList()"))
    }

    @Test
    fun `policy is the single decision owner`() {
        val policySrc = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/CanvasUndoRedoStatePolicy.kt").readText()
        assertTrue(policySrc.contains("object CanvasUndoRedoStatePolicy"))
        assertTrue(policySrc.contains("fun canUndo(undoStackSize: Int): Boolean = undoStackSize > 0"))
        assertTrue(policySrc.contains("fun canRedo(redoStackSize: Int): Boolean = redoStackSize > 0"))
        assertTrue(policySrc.contains("fun iconAlpha(actionable: Boolean): Float"))
        assertTrue(policySrc.contains("const val DISABLED_ALPHA: Float = 0.38f"))
    }

    // ---- 5. CanvasSettingsBottomSheet is scrollable ---------------------------

    @Test
    fun `canvas and paper options sheet content column is scrollable`() {
        val src = editorSource()
        // The tall sheet's content Column carries the scroll modifier (the fix).
        assertTrue(src.contains("// Phase 194: every option in this very tall sheet"))
        assertTrue(src.contains(".verticalScroll(rememberScrollState())"))
        // The scroll line sits inside CanvasSettingsBottomSheet (between the sheet's
        // title Text and the sections below it).
        val sheetStart = src.indexOf("private fun CanvasSettingsBottomSheet")
        val sheetBlock = src.substring(sheetStart)
        val scrollIdx = sheetBlock.indexOf(".verticalScroll(rememberScrollState())")
        assertTrue("verticalScroll must exist inside CanvasSettingsBottomSheet", scrollIdx > 0)
        // And the template rows (the user's "templates don't open" complaint is the
        // second row Cornell/Meeting/To-Do Grid being unreachable below the fold)
        // live in the same scrollable sheet, BELOW the scroll line.
        assertTrue(sheetBlock.contains("\"cornell\" to \"Cornell\""))
        assertTrue(sheetBlock.contains("\"meeting\" to \"Meeting\""))
        assertTrue(sheetBlock.contains("\"todo\" to \"To-Do Grid\""))
        assertTrue(sheetBlock.indexOf("\"cornell\" to \"Cornell\"") > scrollIdx)
    }

    private fun editorSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").isFile) return d
            dir = d.parentFile
        }
        return start
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
}