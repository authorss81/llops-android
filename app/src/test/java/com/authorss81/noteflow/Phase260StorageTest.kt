package com.authorss81.noteflow

import com.authorss81.noteflow.data.db.quarantineSingleFile
import com.authorss81.noteflow.services.PageDeleteFilePolicy
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 260 — storage hardening: Room fallback / atomic deletes / confine-on-delete.
 *
 * 1. No destructive fallback (source pin on NoteflowDatabase.kt): neither
 *    `fallbackToDestructiveMigration` nor `...OnDowngrade` may appear — an
 *    unknown user_version must throw fail-closed, never wipe the vault — while
 *    the additive migration chain and the WAL autocheckpoint stay wired.
 * 2. deletePagePermanently atomic (source pin on NoteRepository.kt): rows go in
 *    ONE transaction incl. the previously-orphaned note_versions, files resolve
 *    through the confinement policy, the substring gate is gone, and emptyTrash
 *    finishes with a best-effort VACUUM.
 * 3. Confine on delete (JVM behavior on the real PageDeleteFilePolicy).
 */
class Phase260StorageTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    private fun read(path: String): String =
        File(repoRoot(), path).readText()

    private fun database(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt")

    private fun repository(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt")

    // ---------------- 1. no destructive fallback ----------------

    @Test
    fun `no destructive migration fallback anywhere in the database builder`() {
        val db = database()
        assertFalse(
            "any fallbackToDestructiveMigration* silently wipes the vault on version drift",
            db.contains("fallbackToDestructiveMigration")
        )
    }

    @Test
    fun `additive migrations and wal autocheckpoint stay wired`() {
        val db = database()
        assertTrue(db.contains("addMigrations(MIGRATION_1_2"))
        assertTrue(db.contains("MIGRATION_8_9"))
        assertTrue(db.contains("PRAGMA wal_autocheckpoint=1000"))
        assertTrue(db.contains("runWalCheckpointFull(db)"))
        assertTrue(db.contains("cursor.getInt(0)"))
    }

    // ---------------- 2. atomic deletes (source pins) ----------------

    @Test
    fun `page rows delete in one transaction including note versions`() {
        val repo = repository()
        assertTrue(repo.contains("private suspend fun deletePageRowsPermanently"))
        assertTrue(repo.contains("db.withTransaction {"))
        assertTrue(repo.contains("db.noteVersionDao().deleteVersionsForPage(pageId)"))
        assertTrue(repo.contains("db.strokeDao().deleteStrokesForPage(pageId)"))
        assertTrue(repo.contains("db.pageDao().deletePagePermanently(pageId)"))
    }

    @Test
    fun `deletes resolve files through confinement and vacuum after purge`() {
        val repo = repository()
        assertTrue(repo.contains("PageDeleteFilePolicy.sourceFileForDelete"))
        assertTrue(repo.contains("PageDeleteFilePolicy.voiceBlobForDelete"))
        assertFalse(
            "the substring gate deleted /tmp/x/imports/y and unconfined .enc paths",
            repo.contains("contains(\"imports/\")")
        )
        assertTrue(repo.contains("\"VACUUM\""))
    }

    @Test
    fun `re-encrypt sweeps page through bounded windows`() {
        val repo = repository()
        assertTrue(repo.contains("getPagesForReencryptPaged("))
        assertTrue(repo.contains("getStrokesForReencryptPaged("))
        assertTrue(repo.contains("getEmbedsForReencryptPaged("))
        assertTrue(repo.contains("getVersionsForReencryptPaged("))
    }

    // ---------------- 3. confine on delete (behavior) ----------------

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun `confined source file resolves, everything else refuses`() {
        val root = tempDir("imports")
        try {
            val inside = File(root, "doc.pdf").apply { writeText("x") }
            assertNotNull(PageDeleteFilePolicy.sourceFileForDelete(inside.absolutePath, root))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete(null, root))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete("", root))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete("relative/path.pdf", root))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete(File(root, "../evil.pdf").path, root))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete("/tmp/somewhere-else.pdf", root))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete(inside.absolutePath, null))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `substring-lookalike source path is refused`() {
        val root = tempDir("imports")
        val decoy = tempDir("decoy")
        try {
            // The old gate `contains("imports/")` deleted this; confinement must not.
            val lookalike = File(decoy, "notimports").apply { mkdirs() }
            val victim = File(lookalike, "x.pdf").apply { writeText("x") }
            assertFalse(victim.absolutePath.contains(root.canonicalPath))
            assertNull(PageDeleteFilePolicy.sourceFileForDelete(victim.absolutePath, root))
        } finally {
            root.deleteRecursively()
            decoy.deleteRecursively()
        }
    }

    @Test
    fun `voice blob resolves only inside the voice dir with an audio name`() {
        val voiceDir = tempDir("voice_notes")
        val outside = tempDir("outside")
        try {
            val blob = File(voiceDir, "voice_p1_123.enc").apply { writeText("x") }
            assertNotNull(PageDeleteFilePolicy.voiceBlobForDelete(blob.absolutePath, voiceDir))
            val legacy = File(voiceDir, "voice_p1_123.m4a").apply { writeText("x") }
            assertNotNull(PageDeleteFilePolicy.voiceBlobForDelete(legacy.absolutePath, voiceDir))
            // Same names outside the voice dir: never deleted.
            assertNull(PageDeleteFilePolicy.voiceBlobForDelete(File(outside, "voice_p1_123.enc").absolutePath, voiceDir))
            // Non-audio name inside the voice dir: never deleted.
            assertNull(PageDeleteFilePolicy.voiceBlobForDelete(File(voiceDir, "notes.txt").absolutePath, voiceDir))
            assertNull(PageDeleteFilePolicy.voiceBlobForDelete(null, voiceDir))
            assertNull(PageDeleteFilePolicy.voiceBlobForDelete(blob.absolutePath, null))
        } finally {
            voiceDir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `quarantine preserves bytes and never collides`() {
        val dir = tempDir("quarantine")
        try {
            val source = File(dir, "noteflow.sqlite").apply { writeText("vault-bytes") }
            quarantineSingleFile(dir, "noteflow.sqlite", ".corrupt-123")
            assertFalse(source.exists())
            val first = File(dir, "noteflow.sqlite.corrupt-123")
            assertTrue(first.isFile)
            assertEquals("vault-bytes", first.readText())
            // A second quarantine with the same stamp must not overwrite the first.
            File(dir, "noteflow.sqlite").apply { writeText("newer-bytes") }
            quarantineSingleFile(dir, "noteflow.sqlite", ".corrupt-123")
            assertEquals("vault-bytes", first.readText())
            val second = File(dir, "noteflow.sqlite.corrupt-123-1")
            assertTrue(second.isFile)
            assertEquals("newer-bytes", second.readText())
            // Missing source is a no-op.
            quarantineSingleFile(dir, "nope.sqlite", ".corrupt-123")
        } finally {
            dir.deleteRecursively()
        }
    }
}
