package com.authorss81.noteflow

import com.authorss81.noteflow.services.InlineImagePathPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-AUTH-04 (phase-68): markdown inline-image destinations must resolve only
 * inside an allowlisted app-private subtree.
 *
 * Finding: `MarkdownInlineImage` (ImageViewer.kt:123-132) resolved
 * `![alt](dest)` as `File(dest)` and accepted it whenever it was an existing
 * absolute path, else `File(baseDir, dest)` with no canonicalization — so a
 * crafted note arriving via vault-import zip, WebDAV, the share sheet or
 * LocalSend could read-and-display ANY file the process can read, and the
 * "File not found: <path>" fallback doubled as an existence oracle.
 *
 * These pure-JVM tests pin [InlineImagePathPolicy], the single resolver now
 * used by the composable:
 *
 *  - absolute destinations are rejected outright (even ones that exist and are
 *    readable by the process);
 *  - `..` traversal — in either separator (`/` or `\`) — is rejected before any
 *    file I/O;
 *  - in-subtree relative paths resolve to their canonical file;
 *  - a symlink planted under baseDir cannot escape the subtree
 *    (canonicalization + strict-prefix check);
 *  - null / non-directory baseDir yields no resolution;
 *  - wiring pins: the composable routes through the policy, the old
 *    `file.isAbsolute && file.exists()` branch is gone, and the policy is the
 *    only `..`-handling resolver.
 */
class B1Auth04InlineImagePathTest {

    private fun tempRoot(): File =
        java.io.File(
            System.getProperty("java.io.tmpdir")!!,
            "b1auth04-" + java.util.UUID.randomUUID()
        ).apply { mkdirs() }

    // ---------- behavioral: absolute paths --------------------------------

    @Test
    fun `absolute destination is rejected even when it exists and is readable`() {
        val root = tempRoot()
        try {
            val outside = java.io.File(root, "secret.txt").also { it.writeText("classified") }
            val absolute = outside.absolutePath
            // a file at an absolute path, no traversal involved:
            assertTrue(outside.exists())
            assertNull(
                "an absolute destination must never resolve, even to a file inside baseDir",
                InlineImagePathPolicy.resolve(absolute, root)
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `absolute destination naming the vault voice-notes dir is rejected`() {
        val root = tempRoot()
        try {
            val voiceNote = java.io.File(root, "voice_note.enc").also { it.writeText("blob") }
            assertNull(
                "a crafted /data/user/0/<appId>/files/... path must never resolve",
                InlineImagePathPolicy.resolve("/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/voice_notes/v.enc", root)
            )
            assertNull(InlineImagePathPolicy.resolve(voiceNote.absolutePath, root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- behavioral: `..` traversal --------------------------------

    @Test
    fun `parent segment traversal is rejected`() {
        val root = tempRoot()
        try {
            java.io.File(root, "img.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            for (dest in listOf(
                "../img.png",
                "../../../img.png",
                "a/../../img.png",
                "./../img.png",
                "../img.png/../img.png"
            )) {
                assertNull("$dest must be rejected as traversal", InlineImagePathPolicy.resolve(dest, root))
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `backslash smuggled parent segments are rejected`() {
        val root = tempRoot()
        try {
            java.io.File(root, "img.png").also { it.writeBytes(byteArrayOf(1)) }
            // `..\..\x` — backslash is a legal filename character on Linux, but
            // an importer may see Windows separators; treat them as traversal.
            assertNull(InlineImagePathPolicy.resolve("..\\..\\img.png", root))
            assertNull(InlineImagePathPolicy.resolve("..\\img.png", root))
            assertNull(InlineImagePathPolicy.resolve("..\\/img.png", root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `dotdot itself and dotdot-only paths are rejected`() {
        val root = tempRoot()
        try {
            assertNull(InlineImagePathPolicy.resolve("..", root))
            assertNull(InlineImagePathPolicy.resolve(".", root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- behavioral: in-subtree relative paths ---------------------

    @Test
    fun `a relative path under baseDir resolves to its canonical file`() {
        val root = tempRoot()
        try {
            java.io.File(root, "img.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            java.io.File(root, "sub").mkdirs()
            java.io.File(root, "sub/other.png").also { it.writeBytes(byteArrayOf(9)) }

            val resolved = InlineImagePathPolicy.resolve("img.png", root)
            assertNotNull("an in-subtree file must resolve", resolved)
            assertEquals(java.io.File(root, "img.png").canonicalPath, resolved!!.canonicalPath)

            val subResolved = InlineImagePathPolicy.resolve("sub/other.png", root)
            assertNotNull(subResolved)
            assertEquals(java.io.File(root, "sub/other.png").canonicalPath, subResolved!!.canonicalPath)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `nonexistent relative destination does not resolve`() {
        val root = tempRoot()
        try {
            assertNull(InlineImagePathPolicy.resolve("missing.png", root))
            assertNull(InlineImagePathPolicy.resolve("sub/missing.png", root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `blank and null destinations never resolve`() {
        val root = tempRoot()
        try {
            assertNull(InlineImagePathPolicy.resolve(null, root))
            assertNull(InlineImagePathPolicy.resolve("", root))
            assertNull(InlineImagePathPolicy.resolve("   ", root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- behavioral: canonicalization escape -----------------------

    @Test
    fun `a symlink under baseDir pointing outside is refused`() {
        val root = tempRoot()
        try {
            val siblingDir = java.io.File(System.getProperty("java.io.tmpdir"), "b1auth04-out-" + java.util.UUID.randomUUID())
            siblingDir.mkdirs()
            val trulyOutside = java.io.File(siblingDir, "secret.png").also { it.writeBytes(byteArrayOf(1, 2, 3)) }
            try {
                val link = java.io.File(root, "link.png")
                java.nio.file.Files.deleteIfExists(link.toPath())
                try {
                    java.nio.file.Files.createSymbolicLink(link.toPath(), trulyOutside.toPath())
                    // On platforms lacking symlink support the loop just skips the assertion.
                    if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                        assertNull(
                            "a symlink whose canonical target is outside baseDir must be refused",
                            InlineImagePathPolicy.resolve("link.png", root)
                        )
                    }
                } catch (e: Exception) {
                    // symlinks unsupported (e.g. Windows CI) — nothing to assert for this vector.
                }
                // The plain relative file next to it still resolves.
                val realTarget = java.io.File(root, "real.png").also { it.writeBytes(byteArrayOf(4, 5, 6)) }
                assertNotNull(InlineImagePathPolicy.resolve("real.png", root))
                assertTrue(realTarget.exists())
            } finally {
                siblingDir.deleteRecursively()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a destination that lands on the root directory itself never resolves`() {
        val root = tempRoot()
        try {
            // "." has no `..` segment, so it reaches the canonical prefix gate —
            // the final defense that a candidate must be STRICTLY inside root.
            assertNull(InlineImagePathPolicy.resolve(".", root))
            // A child directory is not a valid image file source; decode of a
            // dir is meaningless and the resolver must not hand it back either.
            val childDir = java.io.File(root, "child").also { it.mkdirs() }
            assertNull(InlineImagePathPolicy.resolve("child", root))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- behavioral: baseDir gating ---------------------------------

    @Test
    fun `null or non-directory baseDir never resolves`() {
        val root = tempRoot()
        try {
            val file = java.io.File(root, "note.md").also { it.writeText("x") }
            assertNull(InlineImagePathPolicy.resolve("note.md", null))
            assertNull(InlineImagePathPolicy.resolve("note.md", file))
            assertNull(InlineImagePathPolicy.resolve(null, null))
        } finally {
            root.deleteRecursively()
        }
    }

    // ---------- behavioral: ineligibility classifier -----------------------

    @Test
    fun `blocked destination classifier flags absolute and traversal references`() {
        assertTrue(
            "an absolute destination is inherently blocked regardless of existence",
            InlineImagePathPolicy.isBlockedDestination("/data/user/0/com.aistudio.inkflow.app/files/voice_notes/v.enc")
        )
        for (trav in listOf(
            "../img.png",
            "../../secret.png",
            "a/../img.png",
            "./../img.png",
            "..\\img.png",
            "a\\..\\..\\img.png",
            "../img.png/../img.png"
        )) {
            assertTrue("$trav must be flagged as blocked traversal", InlineImagePathPolicy.isBlockedDestination(trav))
        }
        assertFalse(InlineImagePathPolicy.isBlockedDestination("img.png"))
        assertFalse(InlineImagePathPolicy.isBlockedDestination("images/photo.png"))
        assertFalse("blank/null are not policy-violations, just unresolvable", InlineImagePathPolicy.isBlockedDestination(null))
        assertFalse(InlineImagePathPolicy.isBlockedDestination("   "))
    }

    // ---------- wiring pins ------------------------------------------------

    @Test
    fun `MarkdownInlineImage routes through the policy and the pre-fix branch is gone`() {
        val viewerSource = readImageViewerSource()
        assertTrue(
            "the composable must route destination resolution through InlineImagePathPolicy",
            viewerSource.contains("InlineImagePathPolicy.resolve(destination, baseDir)")
        )
        assertTrue(
            "the fallback must distinguish a policy-blocked location from a missing file",
            viewerSource.contains("InlineImagePathPolicy.isBlockedDestination(destination)")
        )
        assertTrue(
            "the decode path must re-canonicalize to refuse a symlink swapped in after resolution",
            viewerSource.contains("canonicalPath")
        )
        // The old `/../`-accepting resolver branches are deleted from the file.
        assertFalse(
            "the absolute-path accept branch (`file.isAbsolute && file.exists()`) must be deleted",
            viewerSource.contains("isAbsolute")
        )
        assertFalse(
            "the uncanonicalized File(baseDir, dest) accept branch must be deleted",
            viewerSource.contains("File(baseDir, dest).exists()")
        )
        assertFalse(
            "the `else -> dest` absolute acceptance must be deleted",
            Regex("""\.isAbsolute && .*\.exists\(\)\s*->\s*dest""").containsMatchIn(viewerSource)
        )
    }

    @Test
    fun `InlineImagePathPolicy is the only destination resolver handling traversal`() {
        val viewerSource = readImageViewerSource()
        assertTrue("ImageViewer.kt must import the policy", viewerSource.contains("InlineImagePathPolicy"))
        // No other path resolution may remain in the composable file: all uses
        // of File(dest)/File(baseDir, dest) belong to the policy.
        assertEquals("no raw File(baseDir, dest) resolution may survive in ImageViewer.kt", 0,
            Regex("""File\(baseDir,\s*dest""").findAll(viewerSource).count())
        assertEquals("no isAbsolute check may survive in ImageViewer.kt", 0,
            Regex("""isAbsolute""").findAll(viewerSource).count())
    }

    // ---------- helpers ------------------------------------------------------

    private fun readImageViewerSource(): String {
        val file = java.io.File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/components/ImageViewer.kt"
        )
        assertTrue("ImageViewer.kt must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
