package com.authorss81.noteflow

import com.authorss81.noteflow.services.HeadingScrollIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 174 (Feature 2) — precomputed heading index for the reader-mode rail.
 */
class Phase174HeadingScrollIndexTest {

    @Test
    fun `index builds heads in document order and skips blank texts`() {
        val index = HeadingScrollIndex().build(listOf("Intro" to 1, "" to 2, "Notes" to 2, "Details" to 3))
        assertEquals(3, index.size)
        assertFalse(index.isEmpty)
        assertEquals(listOf("Intro", "Notes", "Details"), index.labels())
        assertEquals(1, index.levelAt(0))
        assertEquals(2, index.levelAt(1))
        assertEquals(3, index.levelAt(2))
    }

    @Test
    fun `duplicate heading texts get stable occurrence suffixes`() {
        val index = HeadingScrollIndex().build(listOf("Notes" to 1, "Notes" to 2, "Notes" to 3))
        assertEquals(listOf("Notes", "Notes (2)", "Notes (3)"), index.labels())
        // Offset lookup must resolve each occurrence to its own position.
        index.register(0, 100)
        index.register(1, 400)
        index.register(2, 900)
        assertEquals(100, index.offsetForLabel("Notes")!!)
        assertEquals(400, index.offsetForLabel("Notes (2)")!!)
        assertEquals(900, index.offsetForLabel("Notes (3)")!!)
    }

    @Test
    fun `registered offsets are coerced non-negative and unknown labels are null`() {
        val index = HeadingScrollIndex().build(listOf("A" to 1, "B" to 2))
        index.register(0, -50)
        assertEquals(0, index.offsetForPosition(0)!!)
        assertNull(index.offsetForLabel("Missing"))
    }

    @Test
    fun `out-of-range registers are ignored`() {
        val index = HeadingScrollIndex().build(listOf("A" to 1))
        index.register(5, 100)
        assertNull(index.offsetForPosition(5))
    }

    @Test
    fun `clearOffsets keeps headings but drops measured positions`() {
        val index = HeadingScrollIndex().build(listOf("A" to 1))
        index.register(0, 120)
        assertEquals(120, index.offsetForPosition(0)!!)
        index.clearOffsets()
        assertNull(index.offsetForPosition(0))
        assertEquals(listOf("A"), index.labels())
    }

    @Test
    fun `rebuild replaces the previous heading set`() {
        val index = HeadingScrollIndex().build(listOf("Old" to 1))
        index.register(0, 50)
        assertTrue(index.isEmpty.not())
        index.build(listOf("New" to 2))
        assertNull(index.offsetForPosition(0))
        assertNull(index.offsetForPosition(1))
        assertEquals(listOf("New"), index.labels())
        assertEquals(2, index.levelAt(0))
    }

    @Test
    fun `empty document produces an empty index`() {
        val index = HeadingScrollIndex().build(emptyList())
        assertTrue(index.isEmpty)
        assertEquals(0, index.size)
        assertTrue(index.labels().isEmpty())
        assertNull(index.offsetForLabel("anything"))
    }
}