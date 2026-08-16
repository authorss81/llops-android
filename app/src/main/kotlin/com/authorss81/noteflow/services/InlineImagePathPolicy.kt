package com.authorss81.noteflow.services

import java.io.File

/**
 * B1-AUTH-04 (phase-68): markdown inline image destinations may only resolve
 * inside an allowlisted app-private subtree.
 *
 * Before phase-68, `MarkdownInlineImage` resolved `![alt](dest)` as `File(dest)`
 * and accepted it whenever it was an existing absolute path, else `File(baseDir, dest)`
 * with no canonicalization — so a crafted note arriving via the Obsidian/HTML
 * vault-import zip, WebDAV sync, the share sheet or LocalSend could name
 * `/data/user/0/<appId>/files/…` (voice-note blobs, imports, exports) or a
 * `../../../…` traversal and get the app to decode-and-display ANY file the
 * process can read; the "File not found: <path>" fallback doubled as an
 * existence oracle.
 *
 * This policy is the SINGLE resolver for those destinations, pure JVM so the
 * contract is unit-testable on the host:
 *
 *  - a blank/null destination never resolves;
 *  - an ABSOLUTE destination (leading `/`) is refused outright — even when the
 *    process could read the file, a note never names files by absolute path;
 *  - a destination containing a `..` path segment is refused before any file
 *    I/O (traversal can never escape the base directory);
 *  - the candidate must EXIST and, after canonicalization (symlink resolution),
 *    must live strictly INSIDE the canonical base directory — a symlink planted
 *    under baseDir cannot reach outside the subtree;
 *  - with a null or non-directory baseDir nothing relative can resolve.
 */
object InlineImagePathPolicy {

    /**
     * Resolves an inline-image [destination] relative to [baseDir], or returns
     * null when the destination must not be read (see class docs). The returned
     * [File] is canonical and is guaranteed to be an existing file strictly
     * inside the canonical [baseDir].
     */
    fun resolve(destination: String?, baseDir: File?): File? {
        val dest = destination
        if (dest.isNullOrBlank()) return null

        // Absolute paths are refused outright, before any existence probe, so a
        // note can never read files by their absolute location.
        if (File(dest).isAbsolute) return null

        // Any `..` segment is refused before reading — even a "Windows-style"
        // `..\..` sequence (backslash is a legal filename character on the OS
        // floor, so it is treated as a segment separator here lest an imported
        // Obsidian note smuggle traversal inside a single name).
        for (segment in dest.split('/').flatMap { it.split('\\') }) {
            if (segment == "..") return null
        }

        val root = baseDir ?: return null
        if (!root.isDirectory) return null

        val candidate = File(root, dest)
        if (!candidate.exists() || candidate.isDirectory) return null

        return try {
            val rootCanonical = root.canonicalFile
            val candidateCanonical = candidate.canonicalFile
            if (isStrictlyInside(candidateCanonical, rootCanonical)) candidateCanonical else null
        } catch (e: Exception) {
            null
        }
    }

    /** Is [candidate] a strict descendant of [root] (root itself excluded)? */
    private fun isStrictlyInside(candidate: File, root: File): Boolean {
        val rootPath = root.path
        val candidatePath = candidate.path
        if (candidatePath.length <= rootPath.length) return false
        if (!candidatePath.startsWith(rootPath)) return false
        return candidatePath.startsWith(rootPath + File.separator)
    }
}