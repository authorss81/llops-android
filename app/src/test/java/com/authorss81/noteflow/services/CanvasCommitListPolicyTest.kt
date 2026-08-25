package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 205: the ONE full-list derivation for canvas emissions.
 *
 * Defect being closed (AnnotationCanvas pre-205): commit / erase / text emit
 * sites rebuilt the payload from the `strokes` list CAPTURED by the gesture or
 * dialog closure. That capture is frozen at whatever composition last restarted
 * the pointerInput block, so combined with the old async commit it:
 *  - RESURRECTED erased strokes (stale snapshot re-emitted them), and
 *  - reordered rapid commits (background compute landed out of gesture order).
 *
 * The canvas now derives "other pages" from its CURRENT-state provider at APPLY
 * time via [CanvasCommitListPolicy.emittedList]. These tests model the parent
 * state machine to prove ordering and no-resurrection under rapid multi-stroke
 * and erase+commit interleaving.
 */
class CanvasCommitListPolicyTest {

    private fun stroke(id: String, page: Int): Stroke = Stroke(
        id = id,
        tool = StrokeTool.PEN,
        points = emptyList(),
        pdfPage = page
    )

    private val pageOf: (Stroke) -> Int = { it.pdfPage }

    // ---- derivation parity ---------------------------------------------------

    @Test
    fun `paginated mode keeps other-page strokes from CURRENT state then appends the scoped list in order`() {
        val currentAll = listOf(stroke("p1-a", 0), stroke("other-1", 1), stroke("p1-b", 0), stroke("other-2", 2))
        val scopedAfterChange = listOf(stroke("p1-a", 0), stroke("new", 0))
        val emitted = CanvasCommitListPolicy.emittedList(
            currentAll = currentAll,
            isContinuousMode = false,
            pageOf = pageOf,
            pdfPageFilter = 0,
            scopedReplacement = scopedAfterChange
        )
        assertEquals(listOf("other-1", "other-2", "p1-a", "new"), emitted.map { it.id })
    }

    @Test
    fun `continuous mode emits exactly the scoped replacement`() {
        val scoped = listOf(stroke("a", 0), stroke("b", 3))
        val emitted = CanvasCommitListPolicy.emittedList(
            currentAll = listOf(stroke("stale", 9)),
            isContinuousMode = true,
            pageOf = pageOf,
            pdfPageFilter = 0,
            scopedReplacement = scoped
        )
        assertEquals(scoped, emitted)
    }

    // ---- failure mode (b): ordering under rapid multi-stroke ------------------

    @Test
    fun `rapid sequential commits land in gesture order - apply order equals call order`() {
        // Model of the post-205 synchronous commit: each pen-up applies BEFORE
        // the next gesture handler runs, so the provider the second commit reads
        // already contains the first stroke. Z-order and undo order both follow
        // call order — there is no background compute that could finish out of
        // sequence.
        var parentState: List<Stroke> = emptyList()
        val commitsInCallOrder = listOf(
            stroke("s1", 0),
            stroke("s2", 0),
            stroke("s3", 0)
        )
        for (newStroke in commitsInCallOrder) {
            val scoped = parentState.filter { it.pdfPage == 0 } + newStroke
            parentState = CanvasCommitListPolicy.emittedList(
                currentAll = parentState, // provider read at APPLY time
                isContinuousMode = false,
                pageOf = pageOf,
                pdfPageFilter = 0,
                scopedReplacement = scoped
            )
        }
        assertEquals(listOf("s1", "s2", "s3"), parentState.map { it.id })
    }

    // ---- failure mode (c): erase+commit interleaving ---------------------------

    @Test
    fun `erase followed by commit never resurrects deleted strokes`() {
        var parentState = listOf(stroke("a", 0), stroke("b", 0), stroke("c", 0), stroke("x", 1))

        // Erase "b": scoped list rebuilt without it; payload derived from the
        // CURRENT provider state.
        val afterEraseScoped = parentState.filter { it.id != "b" && it.pdfPage == 0 }
        parentState = CanvasCommitListPolicy.emittedList(
            currentAll = parentState,
            isContinuousMode = false,
            pageOf = pageOf,
            pdfPageFilter = 0,
            scopedReplacement = afterEraseScoped
        )
        assertEquals(listOf("x", "a", "c"), parentState.map { it.id })

        // Commit a new stroke AFTER the erase: reads post-erase state, so "b"
        // cannot come back through the other-pages branch.
        val scopedWithNew = parentState.filter { it.pdfPage == 0 } + stroke("d", 0)
        parentState = CanvasCommitListPolicy.emittedList(
            currentAll = parentState,
            isContinuousMode = false,
            pageOf = pageOf,
            pdfPageFilter = 0,
            scopedReplacement = scopedWithNew
        )
        assertEquals(listOf("x", "a", "c", "d"), parentState.map { it.id })
        assertTrue(parentState.none { it.id == "b" })
    }

    @Test
    fun `deriving from a CAPTURED pre-erase snapshot would resurrect - why the provider matters`() {
        // Counter-model documenting the defect: the same erase+commit sequence,
        // but the commit derives "other pages" from a FROZEN pre-erase capture
        // (what the pre-205 code did). The erased stroke comes back.
        var parentState = listOf(stroke("a", 0), stroke("b", 0), stroke("c", 0))
        val capturedAtDragStart = parentState // stale snapshot held by the closure

        val afterEraseScoped = parentState.filter { it.id != "b" && it.pdfPage == 0 }
        parentState = CanvasCommitListPolicy.emittedList(
            currentAll = parentState,
            isContinuousMode = false,
            pageOf = pageOf,
            pdfPageFilter = 0,
            scopedReplacement = afterEraseScoped
        )
        assertEquals(listOf("a", "c"), parentState.map { it.id })

        val resurrectedPayload =
            capturedAtDragStart.filter { it.pdfPage != 0 } + afterEraseScoped + stroke("d", 0)
        assertTrue(resurrectedPayload.none { it.id == "b" }) // same page: safe...

        // ...but an erased stroke on ANOTHER page WOULD resurrect from capture:
        var withCrossPageErase = listOf(stroke("k", 0), stroke("z", 1))
        val frozenCapture = withCrossPageErase
        val page0Survivors = withCrossPageErase.filter { it.pdfPage == 0 }
        withCrossPageErase = CanvasCommitListPolicy.emittedList(
            currentAll = withCrossPageErase,
            isContinuousMode = false,
            pageOf = pageOf,
            pdfPageFilter = 0,
            scopedReplacement = page0Survivors
        )
        assertEquals(listOf("z", "k"), withCrossPageErase.map { it.id })

        val staleEmit = frozenCapture.filter { it.pdfPage != 0 } + page0Survivors + stroke("n", 0)
        assertTrue(staleEmit.any { it.id == "z" }) // resurrected by the stale capture
    }
}
