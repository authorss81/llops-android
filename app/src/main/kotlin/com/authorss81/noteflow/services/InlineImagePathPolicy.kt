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
 *
 * [isBlockedDestination] is the shared ineligibility classifier used by both
 * [resolve] and the UI, so a policy-blocked reference (absolute or `..`
 * traversal) is never probed for existence nor echoed as "not found".
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
        // Blank destinations are refused outright; absolute and `..`-traversing
        // destinations are refused by the policy classifier — all before any
        // existence probe, so a note can never read files by their absolute
        // location or escape the base directory.
        if (dest.isNullOrBlank() || isBlockedDestination(dest)) return null

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

    /**
     * True when [destination] is inherently ineligible REGARDLESS of what
     * exists on disk: an ABSOLUTE path or a destination containing a `..` path
     * segment (in either separator). The UI uses this to distinguish a
     * policy-blocked reference — which is never readable and must not be
     * echoed as "file not found" — from a genuinely missing in-subtree file.
     * Blank/null destinations return false (they cannot resolve either, but
     * they are not a policy violation).
     */
    fun isBlockedDestination(destination: String?): Boolean {
        val dest = destination
        if (dest.isNullOrBlank()) return false
        if (File(dest).isAbsolute) return true
        for (segment in dest.split('/').flatMap { it.split('\\') }) {
            if (segment == "..") return true
        }
        return false
    }
}
