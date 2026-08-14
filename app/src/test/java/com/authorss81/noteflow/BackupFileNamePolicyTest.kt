package com.authorss81.noteflow

import com.authorss81.noteflow.utils.BackupFileNamePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.HashSet

/**
 * JVM unit tests for the B2-CRYPTO-06 (phase-106) filename policy.
 *
 * The fix: exported backup/sync filenames that land in public `/Download` or on
 * the user's WebDAV server must NEVER embed epoch-millis (or epoch-seconds) —
 * that leaked the exact second of the last backup/sync to any party who can list
 * those directories, i.e. a proxy for "when the user last used the vault".
 * Names are now day-granular (`yyyy-MM-dd`) + a random token.
 */
class BackupFileNamePolicyTest {

    private val epochMillisPattern = Regex("\\b\\d{13}\\b")
    private val epochSecondsPattern = Regex("\\b\\d{10}\\b")

    @Test
    fun localBackupFileNameContainsNoEpochMillis() {
        val name = BackupFileNamePolicy.localBackupFileName()
        assertFalse("local backup name must not contain epoch-millis: $name", epochMillisPattern.containsMatchIn(name))
        assertFalse("local backup name must not contain epoch-seconds: $name", epochSecondsPattern.containsMatchIn(name))
    }

    @Test
    fun localBackupFileNameKeepsPrefixSuffixAndDayGranularity() {
        val fixedDate = LocalDate.of(2026, 8, 14)
        val name = BackupFileNamePolicy.localBackupFileName(date = fixedDate)
        assertTrue("expected noteflow_backup_ prefix", name.startsWith("noteflow_backup_"))
        assertTrue("expected .noteflow suffix", name.endsWith(".noteflow"))
        assertTrue("expected day-granular date, got: $name", name.contains("2026-08-14"))
        assertTrue("expected the day-granular stamp, got: $name", name.matches(Regex("noteflow_backup_\\d{4}-\\d{2}-\\d{2}_[A-Za-z0-9]{10}\\.noteflow")))
    }

    @Test
    fun remoteVaultBackupFileNameContainsNoEpochMillis() {
        val name = BackupFileNamePolicy.remoteVaultBackupFileName()
        assertFalse("remote backup name must not contain epoch-millis: $name", epochMillisPattern.containsMatchIn(name))
        assertFalse("remote backup name must not contain epoch-seconds: $name", epochSecondsPattern.containsMatchIn(name))
    }

    @Test
    fun remoteVaultBackupFileNameKeepsPrefixSuffixAndDayGranularity() {
        val fixedDate = LocalDate.of(2026, 8, 14)
        val name = BackupFileNamePolicy.remoteVaultBackupFileName(date = fixedDate)
        assertTrue("expected noteflow_vault_backup_ prefix", name.startsWith("noteflow_vault_backup_"))
        assertTrue("expected .nfb suffix", name.endsWith(".nfb"))
        assertTrue("expected day-granular date, got: $name", name.contains("2026-08-14"))
        assertTrue(
            "expected the day-granular stamp, got: $name",
            name.matches(Regex("noteflow_vault_backup_\\d{4}-\\d{2}-\\d{2}_[A-Za-z0-9]{10}\\.nfb"))
        )
    }

    @Test
    fun sameDayBackupNamesAreCollisionFree() {
        val fixedDate = LocalDate.of(2026, 8, 14)
        val seen = HashSet<String>()
        repeat(500) {
            val name = BackupFileNamePolicy.localBackupFileName(date = fixedDate)
            if (!seen.add(name)) fail("duplicate backup name on the same day: $name")
        }
    }

    @Test
    fun webDavDownloadRegexStillMatchesNewRemoteNames() {
        // The download listing regex in WebDavSyncService (kept unchanged) must
        // still match the new day-granular + token naming so old code and old
        // remote files keep working side by side with new uploads.
        val regex = Regex("<d:href>([^<]+noteflow_vault_backup_[^<]+\\.nfb)</d:href>", RegexOption.IGNORE_CASE)
        val name = BackupFileNamePolicy.remoteVaultBackupFileName(date = LocalDate.of(2026, 8, 14))
        val xml = "<d:href>https://cloud.example.com/Noteflow_Vault/$name</d:href>"
        val matches = regex.findAll(xml).map { it.groupValues[1] }.toList()
        assertEquals(1, matches.size)
        assertTrue(matches[0].endsWith(name))
    }

    @Test
    fun fixedDayStampAndFixedTokenProduceDeterministicName() {
        assertEquals(
            "noteflow_backup_2026-08-14_AbC123xYz9.noteflow",
            BackupFileNamePolicy.localBackupFileName(date = LocalDate.of(2026, 8, 14), token = "AbC123xYz9")
        )
        assertEquals(
            "noteflow_vault_backup_2026-08-14_AbC123xYz9.nfb",
            BackupFileNamePolicy.remoteVaultBackupFileName(date = LocalDate.of(2026, 8, 14), token = "AbC123xYz9")
        )
    }
}