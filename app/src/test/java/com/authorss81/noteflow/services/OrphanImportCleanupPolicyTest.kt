package com.authorss81.noteflow.services

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 212: [OrphanImportCleanupPolicy] — the tracked-vs-committed matrix that
 * decides whether a persisted import artifact file is DELETED. A regression here
 * silently destroys committed user files (or, the other way, leaks orphaned
 * at-rest copies). All tests run against real temp files.
 */
class OrphanImportCleanupPolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newFile(name: String): File = tmp.newFile(name)

    @Test
    fun `tracked path starts as an uncommitted orphan`() {
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted("/x/import.pdf")

        assertTrue(run.isOrphan("/x/import.pdf"))
        assertEquals(listOf("/x/import.pdf"), run.pendingOrphans())
    }

    @Test
    fun `blank paths are never tracked`() {
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted("")
        run.trackPersisted("   ")

        assertFalse(run.isOrphan(""))
        assertTrue(run.pendingOrphans().isEmpty())
    }

    @Test
    fun `committed import is never an orphan`() {
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted("/x/import.pdf")
        run.markCommitted("/x/import.pdf")

        assertFalse(run.isOrphan("/x/import.pdf"))
        assertTrue(run.pendingOrphans().isEmpty())
    }

    @Test
    fun `markCommitted for an unknown path is a no-op`() {
        val run = OrphanImportCleanupPolicy.Run()
        run.markCommitted("/never/tracked.bin")

        assertFalse(run.isOrphan("/never/tracked.bin"))
        assertTrue(run.pendingOrphans().isEmpty())
    }

    @Test
    fun `sweep deletes only the uncommitted orphan file`() {
        val orphan = newFile("orphan.png")
        val committed = newFile("committed.pdf")
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted(orphan.absolutePath)
        run.trackPersisted(committed.absolutePath)
        run.markCommitted(committed.absolutePath)

        assertTrue(run.sweep(orphan.absolutePath))
        assertFalse("the orphan must be gone", orphan.exists())
        assertTrue("a COMMITTED user file must never be swept", committed.exists())
        assertFalse(run.sweep(committed.absolutePath))
    }

    @Test
    fun `sweep never touches unknown or untracked files`() {
        val untracked = newFile("unrelated.txt")
        val run = OrphanImportCleanupPolicy.Run()

        assertFalse(run.sweep(untracked.absolutePath))
        assertTrue(untracked.exists())
    }

    @Test
    fun `sweep of a missing file is tolerated and reports false`() {
        val missing = File(tmp.root, "gone.bin")
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted(missing.absolutePath)

        // Best-effort deletion: a delete failure must never break the flow.
        assertFalse(run.sweep(missing.absolutePath))
    }

    @Test
    fun `sweepOrphans removes every uncommitted file and keeps committed ones`() {
        val a = newFile("a.pdf")
        val b = newFile("b.png")
        val c = newFile("c.md")
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted(a.absolutePath)
        run.trackPersisted(b.absolutePath)
        run.trackPersisted(c.absolutePath)
        run.markCommitted(b.absolutePath)

        // Both a and c lack a committed page row — both must be swept; only
        // the committed b survives.
        assertEquals(
            listOf(a.absolutePath, c.absolutePath).sorted(),
            run.sweepOrphans().sorted()
        )
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertFalse(c.exists())
    }

    @Test
    fun `sweepOrphans is idempotent`() {
        val a = newFile("a.pdf")
        val b = newFile("b.pdf")
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted(a.absolutePath)
        run.trackPersisted(b.absolutePath)

        assertEquals(2, run.sweepOrphans().size)
        assertTrue("second sweep must find nothing left", run.sweepOrphans().isEmpty())
    }

    @Test
    fun `clear starts a fresh import run`() {
        val run = OrphanImportCleanupPolicy.Run()
        run.trackPersisted("/x/a.pdf")
        run.markCommitted("/x/a.pdf")
        run.clear()

        assertTrue(run.pendingOrphans().isEmpty())
        assertFalse(run.isOrphan("/x/a.pdf"))
        // After clear, the path is untracked again — markCommitted is a no-op.
        run.trackPersisted("/x/a.pdf")
        assertTrue(run.isOrphan("/x/a.pdf"))
    }

    @Test
    fun `cancelled-run simulation sweeps only artifacts without page rows`() {
        // Mirrors HomeScreen.processImportedUris: persist → track → (page row |
        // cancel) — the cancellation sweep must keep exactly the committed set.
        val pdf1 = newFile("scan1.pdf")
        val img2 = newFile("photo2.png")
        val pdf3 = newFile("scan3.pdf")
        val run = OrphanImportCleanupPolicy.Run()

        run.trackPersisted(pdf1.absolutePath)
        run.markCommitted(pdf1.absolutePath)
        run.trackPersisted(img2.absolutePath)
        // Import cancelled before page 3's row was created:
        run.trackPersisted(pdf3.absolutePath)

        val swept = run.sweepOrphans()
        assertEquals(listOf(img2.absolutePath, pdf3.absolutePath), swept)
        assertTrue(pdf1.exists())
        assertFalse(img2.exists())
        assertFalse(pdf3.exists())
    }

    @Test
    fun `cancelled notice is non-alarming and honest`() {
        val notice = OrphanImportCleanupPolicy.CANCELLED_NOTICE
        assertTrue(notice.contains("cancelled", ignoreCase = true))
        assertTrue(notice.contains("kept"))
        assertFalse(notice.contains("error", ignoreCase = true))
        assertFalse(notice.contains("failed", ignoreCase = true))
    }
}
