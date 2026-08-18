package com.authorss81.noteflow

import com.authorss81.noteflow.services.ExportStagingPolicy
import com.authorss81.noteflow.services.ExportStagingPolicy.Cleanup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R2-B1P-02 (phase-141) — SAF export staging cleanup on EVERY picker outcome.
 *
 * The finding (docs/security-report-round2.md R2-B1P-02): phase-59's SaFExporter
 * deleted the app-private staging copy ONLY when `resultCode == RESULT_OK`, so a
 * CANCELLED whole-vault PLAINTEXT export left the entire decrypted vault in the
 * per-kind `_exports` dirs of the app cache, and result-without-data left it too —
 * while the success path deleted the freshly generated export even when the SAF
 * `copyTo` FAILED.
 *
 * This test drives the pure-JVM decision table ([ExportStagingPolicy]) through the
 * exact fake-ActivityResult seam `SaFExporter` wires (resultCode + URI-present +
 * copy outcome), asserting the staging lifecycle for every outcome: ok → DELETE,
 * ok-but-copy-failed → KEEP (never destroy the user's fresh export on a transient
 * I/O error), cancel → DELETE, no-data → DELETE.
 */
class ExportStagingPolicyTest {

    private fun cleanup(resultCode: Int, uriPresent: Boolean, copy: Boolean?): Cleanup =
        ExportStagingPolicy.cleanupAfterSaF(
            resultCode = resultCode,
            destinationUriPresent = uriPresent,
            copySucceeded = copy
        )

    // ---- delivered outcomes --------------------------------------------------

    @Test
    fun `delivered ok deletes the staging copy`() {
        assertEquals(
            "bytes moved to the user-picked destination -> drop the staging copy",
            Cleanup.DELETE,
            cleanup(resultCode = ExportStagingPolicy.RESULT_OK, uriPresent = true, copy = true)
        )
    }

    @Test
    fun `delivered but copy failed keeps the staging copy`() {
        assertEquals(
            "a failed write must NOT destroy the freshly generated export",
            Cleanup.KEEP,
            cleanup(resultCode = ExportStagingPolicy.RESULT_OK, uriPresent = true, copy = false)
        )
    }

    @Test
    fun `RESULT_OK with uri but no copy ran keeps the staging copy`() {
        // Unreachable in the wired flow (uri present => the copy always runs);
        // keep conservatively so an impossible state never gambles away the file.
        assertEquals(
            Cleanup.KEEP,
            cleanup(resultCode = ExportStagingPolicy.RESULT_OK, uriPresent = true, copy = null)
        )
    }

    // ---- cancelled / no-data outcomes ----------------------------------------

    @Test
    fun `user cancel deletes the staging copy`() {
        assertEquals(
            "a cancelled picker wrote nothing -> never leave the decrypted archive in cacheDir",
            Cleanup.DELETE,
            cleanup(resultCode = 0 /* RESULT_CANCELED */, uriPresent = false, copy = null)
        )
    }

    @Test
    fun `cancel with a data uri but bad code is still delete`() {
        assertEquals(
            "any non-RESULT_OK code is a cancel, regardless of stray intent data",
            Cleanup.DELETE,
            cleanup(resultCode = 0, uriPresent = true, copy = null)
        )
    }

    @Test
    fun `RESULT_OK with no destination uri deletes the staging copy`() {
        assertEquals(
            "result-without-data wrote nothing -> drop the staging copy",
            Cleanup.DELETE,
            cleanup(resultCode = ExportStagingPolicy.RESULT_OK, uriPresent = false, copy = null)
        )
    }

    @Test
    fun `every cancel variant resolves to delete regardless of copy hint`() {
        for (copyHint in listOf(null, true, false)) {
            assertEquals(
                "non-OK code must be DELETE even with a copy hint of $copyHint",
                Cleanup.DELETE,
                cleanup(resultCode = 4, uriPresent = false, copy = copyHint)
            )
        }
    }

    // ---- the full wiring decision (as SaFExporter feeds the policy) ----------

    @Test
    fun `full cartesian sweep matches the documented every-outcome contract`() {
        val expected = mapOf(
            Triple(true, true, true) to Cleanup.DELETE,   // ok+uri+copy-ok
            Triple(true, true, false) to Cleanup.KEEP,    // ok+uri+copy-failed
            Triple(true, true, null) to Cleanup.KEEP,     // ok+uri+no-copy (unreachable)
            Triple(true, false, null) to Cleanup.DELETE,  // ok+no-uri (no-data)
            Triple(false, false, null) to Cleanup.DELETE, // cancel
            Triple(false, true, null) to Cleanup.DELETE   // cancel+stray-uri
        )
        for ((key, want) in expected) {
            val (ok, uri, copy) = key
            assertEquals(
                "ok=$ok uri=$uri copy=$copy",
                want,
                cleanup(if (ok) ExportStagingPolicy.RESULT_OK else 0, uri, copy)
            )
        }
    }
}