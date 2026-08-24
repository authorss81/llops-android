package com.authorss81.noteflow.services

import java.io.File

/**
 * B1-DB-4 (phase-44): policy for where a markdown/text note's BODY may live.
 *
 * Since phase-44 the single authoritative store for a note body is the
 * field-encrypted `pages.extractedText` column (AES-256-GCM under the DEK). A
 * plaintext `.md`/`.txt` companion file under `filesDir/noteflow/imports` must
 * never be written or left behind: an attacker with run-as/root/a forensic
 * image reads it verbatim, bypassing the whole vault encryption (the B1-DB-4
 * exploit this phase closes).
 *
 * A pre-fix vault may still carry legacy plaintext source files whose body is
 * NEWER than the DB column (the file was the authoritative content before the
 * fix). Such files are read exactly once, transiently, into the caller's
 * in-memory state and deleted on the next save (or by the one-time unlock
 * migration [com.authorss81.noteflow.data.repository.NoteRepository.migrateLegacyPlaintextNoteBodies])
 * — a migration, never a new storage location.
 */
object NoteBodyVaultPolicy {

    /**
     * Is [path] (pointed to by a page with [sourceFileType]) the plaintext body
     * file of a text/markdown note? Only pages flagged "text" (or bearing an
     * `.md`/`.txt` file name) get this treatment — imported PDFs/images and
     * exported artifacts are legitimate non-note-body files and are untouched.
     */
    fun isNoteTextBodySource(path: String?, sourceFileType: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (sourceFileType == "text") return true
        val lower = path.lowercase()
        return lower.endsWith(".md") || lower.endsWith(".txt")
    }

    /**
     * Body to display/scan for a note. Prefers the legacy plaintext source file
     * when one still exists AND is confined under [importsRoot] (it was the
     * authoritative content pre-fix); otherwise returns the (already-decrypted)
     * [extractedText] column. A null [importsRoot] (or a reference that escapes
     * it) refuses the file read entirely and falls back to the column — a stored
     * path must NEVER be read outside the imports subtree (B1-AUTH-05). Blocking
     * file I/O — call on a background dispatcher. The returned body is only ever
     * in-memory; nothing here persists plaintext anywhere.
     */
    fun resolveBodyForDisplay(
        extractedText: String?,
        sourceFilePath: String?,
        sourceFileType: String?,
        importsRoot: File? = null
    ): String {
        if (isNoteTextBodySource(sourceFilePath, sourceFileType)) {
            // B1-AUTH-05: only a path confined under the imports root may be read.
            val path = SourceFilePathPolicy.confine(sourceFilePath, importsRoot)
                ?: return extractedText ?: ""
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    // B2-DOS-05 (phase-81 review fix): a legacy body WITHIN the ingest
                    // cap is read head-bounded (readTextHead yields its FULL content),
                    // so a huge/attacker-supplied file can never be fully readText()-ed
                    // into heap. A file LARGER than the cap is never silently truncated
                    // into the display body — the head would be written back as the
                    // authoritative column body on the next save and the full file
                    // deleted. Instead we fall through to the full encrypted column and
                    // leave the oversized legacy file untouched (the unlock migration
                    // also refuses to read/delete oversized legacy bodies), so no
                    // content is silently lost.
                    if (file.length() <= AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES) {
                        // Phase 204: readTextHead returns null on a FAILED read —
                        // fall through to the encrypted column, never surface a
                        // guessed/partial body.
                        val fileBody = AttachmentIngestPolicy.readTextHead(file)
                        if (!fileBody.isNullOrEmpty()) return fileBody
                    }
                }
            } catch (e: Exception) {
                // Fall through to the encrypted column.
            }
        }
        return extractedText ?: ""
    }

    /**
     * Deletes the legacy plaintext note-body file of a text/markdown page, if it
     * still exists AND is confined under [importsRoot] (a stored path is NEVER
     * deleted outside the imports subtree — B1-AUTH-05). With a null
     * [importsRoot] nothing is ever deleted. Returns the deleted path, or null
     * when there was no note-body file (or the delete failed). Call ONLY after
     * the body has been persisted into the encrypted column so no content is
     * ever lost.
     */
    fun deleteLegacyNoteTextBody(
        sourceFilePath: String?,
        sourceFileType: String?,
        importsRoot: File? = null
    ): String? {
        if (!isNoteTextBodySource(sourceFilePath, sourceFileType)) return null
        val path = SourceFilePathPolicy.confine(sourceFilePath, importsRoot) ?: return null
        return try {
            val file = File(path)
            if (file.exists() && file.delete()) path else null
        } catch (e: Exception) {
            null
        }
    }
}