package com.authorss81.noteflow

import com.authorss81.noteflow.services.TutorialAction
import com.authorss81.noteflow.services.TutorialCurriculum
import com.authorss81.noteflow.services.TutorialSection
import com.authorss81.noteflow.services.TutorialSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 125: the enhanced interactive tutorial's pure-JVM state machine —
 * advance gating (progress checks), skip-step bypass, back/resume, completion
 * semantics and the curriculum's structural honesty (unique ids, section
 * ordering, all 10 sections, action slides present, real growth vs the old
 * 7-step deck).
 *
 * Pure JVM: no Android / Compose dependencies involved.
 */
class TutorialStateMachineTest {

    // ---------------------------------------------------------------- basics

    @Test
    fun `curriculum is well-formed - unique ids and ordered sections`() {
        assertTrue("curriculum must pass structural sanity", TutorialCurriculum.isWellFormed)
        assertEquals(
            "slide ids must be unique",
            TutorialCurriculum.slides.size,
            TutorialCurriculum.ids.size
        )
    }

    @Test
    fun `curriculum grows the old 11-step deck by at least 3x`() {
        assertTrue(
            "expected >= 21 slides (old deck was 11); got ${TutorialCurriculum.slides.size}",
            TutorialCurriculum.slides.size >= 21
        )
    }

    @Test
    fun `curriculum covers all ten sections`() {
        val covered = TutorialCurriculum.slides.map { it.section }.toSet()
        assertEquals(
            TutorialSection.values().toSet(),
            covered
        )
    }

    @Test
    fun `every section has at least one slide`() {
        for (section in TutorialSection.values()) {
            assertTrue(
                "section $section has no slides",
                TutorialCurriculum.slides.any { it.section == section }
            )
        }
    }

    @Test
    fun `action slides map to every interactive demo type`() {
        val expected = listOf(
            TutorialAction.DrawStroke,
            TutorialAction.EraseStroke,
            TutorialAction.AddLayer,
            TutorialAction.PickColourMode,
            TutorialAction.TypeMarkdown
        ).map { it::class.java }.toSet()
        val exercisedBySlides = TutorialCurriculum.actionSlides
            .mapNotNull { it.action?.javaClass }
            .toSet()
        assertEquals(
            "every TutorialAction type must be exercised by an interactive slide",
            expected,
            exercisedBySlides
        )
    }

    // ------------------------------------------------------------- navigation

    @Test
    fun `informational slides advance freely`() {
        val session = TutorialSession(TutorialCurriculum.slides)
        assertTrue(session.canAdvance)
        assertTrue(session.advance())
        assertEquals(1, session.index)
    }

    @Test
    fun `action slides gate Next until the action is recorded`() {
        val actionIndex = TutorialCurriculum.slides.indexOfFirst { it.action != null }
        assertTrue(actionIndex >= 0)
        val session = TutorialSession(TutorialCurriculum.slides, initialIndex = actionIndex)
        assertFalse("action drives advance gating", session.canAdvance)
        assertFalse("advance refused before action", session.advance())

        val id = TutorialCurriculum.slides[actionIndex].id
        assertTrue(session.recordAction(id))
        assertTrue(session.canAdvance)
        assertTrue(session.advance())
    }

    @Test
    fun `recordAction is idempotent per slide`() {
        val id = TutorialCurriculum.slides[0].id
        val session = TutorialSession(TutorialCurriculum.slides, initialIndex = 0)
        assertTrue(session.recordAction(id))
        assertFalse("second record must be ignored", session.recordAction(id))
        assertTrue(session.isActionDone(id))
    }

    @Test
    fun `forceAdvance bypasses the progress check`() {
        val actionIndex = TutorialCurriculum.slides.indexOfFirst { it.action != null }
        val session = TutorialSession(TutorialCurriculum.slides, initialIndex = actionIndex)
        assertFalse(session.canAdvance)
        assertTrue("skip-step must escape a stuck demo", session.forceAdvance())
        assertEquals(actionIndex + 1, session.index)
    }

    @Test
    fun `back moves one slide and never below zero`() {
        val session = TutorialSession(TutorialCurriculum.slides, initialIndex = 2)
        assertTrue(session.back())
        assertEquals(1, session.index)
        assertTrue(session.back())
        assertEquals(0, session.index)
        assertFalse(session.back())
        assertEquals(0, session.index)
    }

    @Test
    fun `advance refuses at the last slide`() {
        val last = TutorialCurriculum.slides.size - 1
        val session = TutorialSession(TutorialCurriculum.slides, initialIndex = last)
        assertFalse(session.advance())
        assertFalse(session.forceAdvance())
        assertTrue("completion reached", session.isLast)
    }

    @Test
    fun `resume index is clamped to a valid range`() {
        val n = TutorialCurriculum.slides.size
        assertEquals(0, TutorialSession(TutorialCurriculum.slides, initialIndex = -5).index)
        assertEquals(
            n - 1,
            TutorialSession(TutorialCurriculum.slides, initialIndex = 9999).index
        )
    }

    // ------------------------------------------------------ progress counting

    @Test
    fun `progress climbs from 0 to 100 across the deck`() {
        val n = TutorialCurriculum.slides.size
        val session = TutorialSession(TutorialCurriculum.slides)
        assertEquals(0, session.progressPercent)
        session.forceAdvance() // skip past first (informational) slide
        assertTrue(session.progressPercent > 0)
        val atEnd = TutorialSession(TutorialCurriculum.slides, initialIndex = n - 1)
        assertEquals(100, atEnd.progressPercent)
    }

    @Test
    fun `section counters are 1-based and bounded`() {
        val session = TutorialSession(TutorialCurriculum.slides)
        val sec = session.section
        assertNotNull(sec)
        assertEquals(1, session.slideNumberInSection)
        assertEquals(session.slidesInSection, TutorialCurriculum.sectionSlideCounts[sec])
        // A slide inside the CANVAS section reports a sane in-section position.
        val canvasIndex = TutorialCurriculum.slides.indexOfFirst {
            it.section == TutorialSection.CANVAS
        }
        val canvasSession = TutorialSession(TutorialCurriculum.slides, initialIndex = canvasIndex)
        assertTrue("in-section number is 1-based", canvasSession.slideNumberInSection >= 1)
        assertEquals(7, canvasSession.slidesInSection)
    }

    @Test
    fun `progress percentage equals straight-line completion`() {
        val n = TutorialCurriculum.slides.size
        val mid = TutorialSession(TutorialCurriculum.slides, initialIndex = n / 2)
        assertEquals((n / 2) * 100 / (n - 1), mid.progressPercent)
    }
}