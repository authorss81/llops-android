package com.authorss81.noteflow

import com.authorss81.noteflow.services.TagAppendPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 208 review-fix (finding 3): the bulk tag APPEND merge rule. Existing
 * tags are preserved verbatim; additions are deduped case-insensitively against
 * existing AND already-appended entries. `null` = nothing to change (caller
 * skips the DB write entirely).
 */
class TagAppendPolicyTest {

    @Test
    fun `appends new tags after the existing ones`() {
        assertEquals("work,trip", TagAppendPolicy.merge("work", listOf("trip")))
        assertEquals("a,b,c", TagAppendPolicy.merge("a,b", listOf("c")))
    }

    @Test
    fun `existing tags are preserved verbatim`() {
        // Odd spacing/case in the note's own tags is never rewritten beyond the
        // outer join-trim.
        assertEquals("Work, travel,b", TagAppendPolicy.merge("Work, travel ", listOf("b")))
    }

    @Test
    fun `additions are deduped case-insensitively against existing tags`() {
        assertNull(TagAppendPolicy.merge("work,trip", listOf("WORK", " Trip ")))
    }

    @Test
    fun `additions are deduped against each other`() {
        assertEquals("a,BIG", TagAppendPolicy.merge("a", listOf("BIG", "big", "Big")))
    }

    @Test
    fun `blank and whitespace additions are dropped`() {
        assertNull(TagAppendPolicy.merge("a", listOf("", "   ", ",")))
    }

    @Test
    fun `no-op applies return null so no write happens`() {
        assertNull(TagAppendPolicy.merge("same,tags", listOf("same")))
        assertNull(TagAppendPolicy.merge(null, emptyList()))
        assertNull(TagAppendPolicy.merge(null, listOf("   ")))
    }

    @Test
    fun `empty existing tags yield just the additions`() {
        assertEquals("x,y", TagAppendPolicy.merge("", listOf("x", "y")))
        assertEquals("x", TagAppendPolicy.merge(null, listOf("x")))
    }
}
