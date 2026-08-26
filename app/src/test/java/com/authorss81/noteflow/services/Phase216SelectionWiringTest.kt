package com.authorss81.noteflow.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 216: source-level wiring pins that verify the selection actions
 * (copy/cut/duplicate/delete/translate) are correctly wired and the encryption
 * gate on [NoteRepository.saveStrokesForPage] is still enforced.
 *
 * These tests read source files and grep for expected tokens — they catch
 * accidental unwiring or refactoring that breaks the contract without needing
 * a running Android process.
 */
class Phase216SelectionWiringTest {

    private val srcRoot = File("src/main/kotlin/com/authorss81/noteflow")
    private val testRoot = File("src/test/java/com/authorss81/noteflow")

    private fun read(path: String): String =
        File(srcRoot, path).readText()

    private fun readTest(path: String): String =
        File(testRoot, path).readText()

    // ---- EditorScreen wiring -----------------------------------------------

    @Test
    fun `source pin - EditorScreen passes strokeSelection to AnnotationCanvas`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must pass strokeSelection to AnnotationCanvas",
            src.contains("strokeSelection = strokeSelection")
        )
    }

    @Test
    fun `source pin - EditorScreen passes onSelectionChanged to AnnotationCanvas`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must pass onSelectionChanged to AnnotationCanvas",
            src.contains("onSelectionChanged = {")
        )
    }

    @Test
    fun `source pin - EditorScreen passes onSelectionTranslate to AnnotationCanvas`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must pass onSelectionTranslate to AnnotationCanvas",
            src.contains("onSelectionTranslate = {")
        )
    }

    @Test
    fun `source pin - EditorScreen has copySelectedStrokes function`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must have copySelectedStrokes function",
            src.contains("fun copySelectedStrokes()")
        )
    }

    @Test
    fun `source pin - EditorScreen has cutSelectedStrokes function`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must have cutSelectedStrokes function",
            src.contains("fun cutSelectedStrokes()")
        )
    }

    @Test
    fun `source pin - EditorScreen has duplicateSelectedStrokes function`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must have duplicateSelectedStrokes function",
            src.contains("fun duplicateSelectedStrokes()")
        )
    }

    @Test
    fun `source pin - EditorScreen has deleteSelectedStrokes function`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must have deleteSelectedStrokes function",
            src.contains("fun deleteSelectedStrokes()")
        )
    }

    @Test
    fun `source pin - EditorScreen has translateSelectedStrokes function`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "EditorScreen must have translateSelectedStrokes function",
            src.contains("fun translateSelectedStrokes(")
        )
    }

    @Test
    fun `source pin - copySelectedStrokes uses ClipboardGuard recordCopy`() {
        val src = read("ui/screens/EditorScreen.kt")
        assertTrue(
            "copySelectedStrokes must call ClipboardGuard.recordCopy()",
            src.contains("ClipboardGuard.recordCopy()")
        )
    }

    @Test
    fun `source pin - cutSelectedStrokes calls handleStrokesChange`() {
        val src = read("ui/screens/EditorScreen.kt")
        // Find the cutSelectedStrokes function body
        val cutIdx = src.indexOf("fun cutSelectedStrokes()")
        assertTrue("cutSelectedStrokes function must exist", cutIdx >= 0)
        val cutBody = src.substring(cutIdx, cutIdx + 500)
        assertTrue(
            "cutSelectedStrokes must call handleStrokesChange for single undo entry",
            cutBody.contains("handleStrokesChange")
        )
    }

    @Test
    fun `source pin - duplicateSelectedStrokes calls handleStrokesChange`() {
        val src = read("ui/screens/EditorScreen.kt")
        val dupIdx = src.indexOf("fun duplicateSelectedStrokes()")
        assertTrue("duplicateSelectedStrokes must exist", dupIdx >= 0)
        val dupBody = src.substring(dupIdx, dupIdx + 600)
        assertTrue(
            "duplicateSelectedStrokes must call handleStrokesChange",
            dupBody.contains("handleStrokesChange")
        )
    }

    @Test
    fun `source pin - deleteSelectedStrokes calls handleStrokesChange`() {
        val src = read("ui/screens/EditorScreen.kt")
        val delIdx = src.indexOf("fun deleteSelectedStrokes()")
        assertTrue("deleteSelectedStrokes must exist", delIdx >= 0)
        val delBody = src.substring(delIdx, delIdx + 400)
        assertTrue(
            "deleteSelectedStrokes must call handleStrokesChange",
            delBody.contains("handleStrokesChange")
        )
    }

    @Test
    fun `source pin - translateSelectedStrokes calls handleStrokesChange`() {
        val src = read("ui/screens/EditorScreen.kt")
        val trIdx = src.indexOf("fun translateSelectedStrokes(")
        assertTrue("translateSelectedStrokes must exist", trIdx >= 0)
        val trBody = src.substring(trIdx, trIdx + 500)
        assertTrue(
            "translateSelectedStrokes must call handleStrokesChange",
            trBody.contains("handleStrokesChange")
        )
    }

    // ---- AnnotationCanvas wiring -------------------------------------------

    @Test
    fun `source pin - AnnotationCanvas has onSelectionTranslate parameter`() {
        val src = read("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "AnnotationCanvas must have onSelectionTranslate parameter",
            src.contains("onSelectionTranslate: (dx: Float, dy: Float) -> Unit")
        )
    }

    @Test
    fun `source pin - AnnotationCanvas has isTranslatingSelection state`() {
        val src = read("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "AnnotationCanvas must have isTranslatingSelection state",
            src.contains("isTranslatingSelection")
        )
    }

    @Test
    fun `source pin - AnnotationCanvas translate accumulates delta in drag handler`() {
        val src = read("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "AnnotationCanvas must accumulate selectionTranslateAccX/Y in onDrag",
            src.contains("selectionTranslateAccX += dragAmount.x")
        )
    }

    @Test
    fun `source pin - AnnotationCanvas translate committed on drag end`() {
        val src = read("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "AnnotationCanvas must call currentOnSelectionTranslate on drag end",
            src.contains("currentOnSelectionTranslate(dx, dy)")
        )
    }

    @Test
    fun `source pin - AnnotationCanvas translate cancelled on drag cancel`() {
        val src = read("ui/components/AnnotationCanvas.kt")
        assertTrue(
            "AnnotationCanvas must reset isTranslatingSelection on drag cancel",
            src.contains("isTranslatingSelection = false\n                            selectionTranslateAccX = 0f")
        )
    }

    // ---- StrokeSelectionActionPolicy ---------------------------------------

    @Test
    fun `source pin - StrokeSelectionActionPolicy exists`() {
        val f = File(srcRoot, "services/StrokeSelectionActionPolicy.kt")
        assertTrue("StrokeSelectionActionPolicy.kt must exist", f.exists())
    }

    @Test
    fun `source pin - StrokeSelectionActionPolicy is pure JVM (no Android imports)`() {
        val src = read("services/StrokeSelectionActionPolicy.kt")
        assertTrue(
            "StrokeSelectionActionPolicy must not import Android framework classes",
            !src.contains("import android.")
        )
    }

    @Test
    fun `source pin - StrokeSelectionActionPolicy test exists`() {
        val f = File(testRoot, "services/StrokeSelectionActionPolicyTest.kt")
        assertTrue("StrokeSelectionActionPolicyTest.kt must exist", f.exists())
    }

    // ---- Encryption gate still enforced ------------------------------------

    @Test
    fun `source pin - saveStrokesForPage still uses requireEncryptionKey`() {
        val src = File("src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()
        assertTrue(
            "saveStrokesForPage must still use requireEncryptionKey",
            src.contains("requireEncryptionKey()")
        )
    }

    @Test
    fun `source pin - saveStrokesForPage still encrypts pointsJson`() {
        val src = File("src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()
        assertTrue(
            "saveStrokesForPage must still encrypt pointsJson via encryptField",
            src.contains("encryptField")
        )
    }

    // ---- CanvasCommitListPolicy still single source -----------------------

    @Test
    fun `source pin - CanvasCommitListPolicy emitedList still exists`() {
        val src = read("services/CanvasCommitListPolicy.kt")
        assertTrue(
            "CanvasCommitListPolicy.emittedList must still exist",
            src.contains("fun <T> emittedList(")
        )
    }
}
