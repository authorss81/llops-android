package com.authorss81.noteflow

import com.authorss81.noteflow.services.RestoredDbPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1D-02 (phase-135) behavioral tests for the pre-swap restore gate.
 *
 * The exploit premise: a freshly-initialized EMPTY SQLCipher database opens
 * under the backup/current DEK, passes `PRAGMA integrity_check` (= "ok"), and
 * reports `user_version` 0 — which `checkRestoredSchemaNotNewer` accepted
 * (0 < 9). `PRAGMA rekey` even materializes a header so the file is non-empty
 * by re-arm time, and the app then HMAC-rearms + swaps it over the live vault.
 * The decision table must distinguish that blank DB from a real (possibly empty)
 * vault backup using schema-presence + user_version + rowcount, all of which the
 * Android-bound caller collects under the SAME candidate open (see
 * RestoreHardeningWiringTest for those pins).
 */
class RestoredDbPolicyTest {

    private val allTables = RestoredDbPolicy.REQUIRED_TABLES.size

    @Test
    fun `a real populated vault passes`() {
        val decision = RestoredDbPolicy.decide(
            userVersion = 9,
            presentTableCount = allTables,
            pageCount = 42L,
            allowEmptyVault = false
        )
        assertEquals(RestoredDbPolicy.Decision.Pass, decision)
    }

    @Test
    fun `an older yet real schema still passes - migrations may run forward`() {
        // user_version 1 is a valid Room-created vault (migrations run forward).
        val decision = RestoredDbPolicy.decide(
            userVersion = RestoredDbPolicy.MIN_USER_VERSION,
            presentTableCount = allTables,
            pageCount = 3L,
            allowEmptyVault = false
        )
        assertEquals(RestoredDbPolicy.Decision.Pass, decision)
    }

    @Test
    fun `a missing required table is rejected - an empty SQLCipher DB has no Room schema`() {
        // The core exploit: the blank DB's sqlite_master is EMPTY. Even with a
        // believable user_version, a schema missing any required table is not a vault.
        for (missing in 0 until allTables) {
            val decision = RestoredDbPolicy.decide(
                userVersion = 9,
                presentTableCount = missing,
                pageCount = 0L,
                allowEmptyVault = false
            )
            assertTrue(
                "missing $missing/${allTables} tables must reject, not pass",
                decision is RestoredDbPolicy.Decision.Reject
            )
        }
    }

    @Test
    fun `a missing table is rejected even when allowEmptyVault is set`() {
        // allowEmptyVault is ONLY the zero-row "start fresh" escape hatch — it can
        // never whitelist a structurally-invalid database.
        val decision = RestoredDbPolicy.decide(
            userVersion = 9,
            presentTableCount = allTables - 1,
            pageCount = 0L,
            allowEmptyVault = true
        )
        assertTrue(decision is RestoredDbPolicy.Decision.Reject)
    }

    @Test
    fun `user_version below the accepted range is rejected - the never-initialized marker`() {
        // The blank-DB marker: user_version 0 means Room never stamped a schema.
        for (v in listOf(0L, -1L, Long.MIN_VALUE)) {
            val decision = RestoredDbPolicy.decide(
                userVersion = v,
                presentTableCount = allTables,
                pageCount = 5L,
                allowEmptyVault = false
            )
            assertTrue("user_version $v must be rejected", decision is RestoredDbPolicy.Decision.Reject)
        }
    }

    @Test
    fun `a real schema with zero rows is refused until the user confirms start fresh`() {
        // A legit-but-empty vault (e.g. exported from a fresh install) has the full
        // Room schema and user_version >= 1 but no pages — restoring it silently
        // wipes a populated vault. It must be refused unless explicitly confirmed.
        val refused = RestoredDbPolicy.decide(
            userVersion = 9,
            presentTableCount = allTables,
            pageCount = 0L,
            allowEmptyVault = false
        )
        assertEquals(RestoredDbPolicy.Decision.EmptyVault, refused)

        val confirmed = RestoredDbPolicy.decide(
            userVersion = 9,
            presentTableCount = allTables,
            pageCount = 0L,
            allowEmptyVault = true
        )
        assertEquals(RestoredDbPolicy.Decision.Pass, confirmed)
    }

    @Test
    fun `the required table set matches the field-encrypted Room tables`() {
        // A structural splice between this gate and the re-key/migrate pass would
        // break the promised invariants — the four required tables are exactly the
        // core Room entities.
        assertEquals(listOf("pages", "strokes", "note_versions", "media_embeds"), RestoredDbPolicy.REQUIRED_TABLES)
        assertEquals(4, allTables)
    }
}