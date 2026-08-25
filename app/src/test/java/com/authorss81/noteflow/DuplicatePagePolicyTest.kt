package com.authorss81.noteflow

import com.authorss81.noteflow.services.DuplicatePagePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 208 fixes #3 + #4: the page-management verb policy — duplicate-title
 * derivation and the multi-select bulk-bar verb table per tab context.
 */
class DuplicatePagePolicyTest {

    // ---------- duplicateTitle ----------

    @Test
    fun `duplicate appends the copy suffix`() {
        assertEquals("Meeting notes (Copy)", DuplicatePagePolicy.duplicateTitle("Meeting notes"))
    }

    @Test
    fun `duplicating a copy does not stack suffixes`() {
        assertEquals(
            "Meeting notes (Copy)",
            DuplicatePagePolicy.duplicateTitle("Meeting notes (Copy)")
        )
    }

    @Test
    fun `blank titles become Untitled copy`() {
        assertEquals("Untitled (Copy)", DuplicatePagePolicy.duplicateTitle(""))
        assertEquals("Untitled (Copy)", DuplicatePagePolicy.duplicateTitle("   "))
    }

    @Test
    fun `title is trimmed before the suffix is appended`() {
        assertEquals("Trip plan (Copy)", DuplicatePagePolicy.duplicateTitle("  Trip plan  "))
    }

    // ---------- bulkVerbs ----------

    @Test
    fun `live context offers recoverable verbs only`() {
        val verbs = DuplicatePagePolicy.bulkVerbs(trashContext = false)
        assertEquals(
            listOf(
                DuplicatePagePolicy.BulkVerb.MOVE_TO_TRASH,
                DuplicatePagePolicy.BulkVerb.MOVE_TO_SECTION,
                DuplicatePagePolicy.BulkVerb.EDIT_TAGS
            ),
            verbs
        )
        assertFalse(DuplicatePagePolicy.BulkVerb.DELETE_PERMANENTLY in verbs)
        assertFalse(DuplicatePagePolicy.BulkVerb.RESTORE in verbs)
    }

    @Test
    fun `trash context offers restore and permanent delete only`() {
        val verbs = DuplicatePagePolicy.bulkVerbs(trashContext = true)
        assertTrue(DuplicatePagePolicy.BulkVerb.RESTORE in verbs)
        assertTrue(DuplicatePagePolicy.BulkVerb.DELETE_PERMANENTLY in verbs)
        assertFalse(DuplicatePagePolicy.BulkVerb.MOVE_TO_TRASH in verbs)
        assertFalse(DuplicatePagePolicy.BulkVerb.EDIT_TAGS in verbs)
    }
}
