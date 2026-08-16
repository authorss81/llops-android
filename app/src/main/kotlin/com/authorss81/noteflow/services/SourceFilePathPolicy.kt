package com.authorss81.noteflow.services

import java.io.File

/**
 * B1-AUTH-05 (phase-69): a note's `pages.sourceFilePath` column must only ever
 * point at a file inside the app-private imports root
 * (`File(filesDir, "noteflow/imports")`).
 *
 * Before phase-69 the value was stored as given and never validated: a crafted
 * vault-backup restore (_ImportExportService.validateAndPrepareRestoredDb_)
 * could transplant DB rows whose `sourceFilePath` pointed anywhere the process
 * can read/write (voice-note blobs, the crash log, shared/exported artifacts),
 * and the note body readers (_NoteBodyVaultPolicy.resolveBodyForDisplay_,
 * _WikiLinkParser.readFullText_, the legacy text-body migration) would read that
 * file's whole contents into the editor/preview/backlinks, while saving wrote
 * attacker-chosen bytes to an attacker-chosen path — an arbitrary read/write
 * primitive inside the app sandbox.
 *
 * This policy is the SINGLE confinement decision for stored absolute
 * `sourceFilePath` values, pure JVM so the contract is unit-testable on the
 * host:
 *
 *  - a blank/null value never resolves (nothing to confine);
 *  - a RELATIVE value is refused outright — every legitimately-stored value is
 *    `File.absolutePath`, so a relative value is never a real file reference;
 *  - a value containing a `..` path segment (in either separator) is refused
 *    before any file I/O — backslash separators count, so Windows-style
 *    `..\..` sequences in imported archives cannot hide a traversal in a
 *    filename;
 *  - the value (and the imports root) are canonicalized and the value must be a
 *    STRICT descendant of the canonical root — a symlink placed under the root
 *    cannot escape the subtree, and the root itself (a directory) is never a
 *    file source;
 *  - a null/non-directory imports root confines nothing (fail closed).
 *
 * [isBlocked] is the shared ineligibility classifier (relative + `..`), used by
 * both [confine]/[isConfined] and the restored-DB sanitizer, so a
 * policy-blocked value is dropped without any existence probe.
 */
object SourceFilePathPolicy {

    /**
     * Confines a stored [value] under [importsRoot]: returns the canonical
     * absolute path when the value is a legitimate confined file reference, or
     * null when it must not be used (blank, relative, `..`-traversing, outside
     * the root, or no usable root). Callers persist/read ONLY the returned
     * value. Pure JVM — no file existence requirement (reads fail naturally on
     * a missing file; writes are gated by their own callers).
     */
    fun confine(value: String?, importsRoot: File?): String? {
        val v = value
        val root = importsRoot ?: return null
        if (v.isNullOrBlank()) return null
        if (!root.isDirectory) return null
        if (isBlocked(v)) return null
        return try {
            val rootCanonical = root.canonicalPath
            val candidateCanonical = File(v).canonicalPath
            if (isStrictlyInside(candidateCanonical, rootCanonical)) candidateCanonical else null
        } catch (e: Exception) {
            null
        }
    }

    /** Is [value] a strictly-inside-the-root (and therefore readable) path? */
    fun isConfined(value: String?, importsRoot: File): Boolean = confine(value, importsRoot) != null

    /**
     * True when [value] is inherently unusable as a source-file reference
     * REGARDLESS of what exists on disk: blank, RELATIVE, or containing a `..`
     * path segment (in either separator). A blank value returns true here but
     * [confine] treats it as "no file reference" (null) — both fail closed.
     */
    fun isBlocked(value: String?): Boolean {
        val v = value
        if (v.isNullOrBlank()) return true
        val f = File(v)
        if (!f.isAbsolute) return true
        for (segment in v.split('/').flatMap { it.split('\\') }) {
            if (segment == "..") return true
        }
        return false
    }

    /** Is [candidate] a strict descendant of [root] (root itself excluded)? */
    private fun isStrictlyInside(candidate: String, root: String): Boolean {
        if (candidate.length <= root.length) return false
        if (!candidate.startsWith(root)) return false
        return candidate.startsWith(root + File.separator)
    }
}
