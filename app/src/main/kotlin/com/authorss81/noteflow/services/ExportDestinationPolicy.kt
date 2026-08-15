package com.authorss81.noteflow.services

/**
 * B1-PLAT-3 (phase-59): the export-destination decision table for everything the
 * app writes out of its sandbox. Pure JVM so the consent/mime/warning rules are
 * unit-testable without Android.
 *
 * The vulnerability (see docs/security-report.md B1-PLAT-3): the whole-vault
 * export functions auto-copied plaintext .md/.html zips into the world-readable
 * public `/storage/emulated/0/Download` with a single tap — no password, no
 * confirm dialog, no "unencrypted" warning. The proxy fix (chosen over the SAF
 * rewrite) is:
 *
 *  1. NO export ever writes to public storage by itself — the export service
 *     functions keep their outputs in app-private `cacheDir` only (the automatic
 *     `Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)` copy
 *     is removed repo-wide).
 *  2. EVERY user-facing export goes through the system Storage Access Framework
 *     destination picker (`ACTION_CREATE_DOCUMENT`) so the user consciously
 *     picks where the bytes land; ExportDestinationPolicy supplies the MIME type
 *     + suggested file name for that picker.
 *  3. The whole-vault PLAINTEXT exports (Obsidian zip / HTML site / notebook &
 *     section vault zips) additionally require a bold pre-export warning that
 *     the content is unencrypted and suggest transfer-then-delete.
 *
 * Encrypted backups still ride through the same SAF picker (the bytes are
 * encrypted, but no file may silently appear in shared storage), so the picker
 * is the consent and no special warning is shown for them.
 */
object ExportDestinationPolicy {

    /** Every user-facing export kind the app can produce. */
    enum class ExportKind {
        /** Encrypted vault backup archive (.noteflow — device-keyed or password-v2). */
        ENCRYPTED_BACKUP,
        /** Whole-vault plaintext .md vault zip (Obsidian). */
        OBSIDIAN_VAULT,
        /** Whole-vault plaintext HTML site zip. */
        HTML_SITE,
        /** Notebook/section plaintext vault zip (markdown + ink renders + PDFs). */
        VAULT_ZIP,
        /** Single annotated page rendered to PNG. */
        PAGE_PNG,
        /** Single annotated page rendered to WebP. */
        PAGE_WEBP,
        /** Single annotated page rendered to PDF. */
        PAGE_PDF,
        /** Whole multi-page document rendered to PDF. */
        DOCUMENT_PDF,
        /** Single note exported to HTML. */
        NOTE_HTML,
        /** Layered canvas exported to PSD. */
        LAYERED_PSD
    }

    /** MIME type handed to the SAF `ACTION_CREATE_DOCUMENT` picker. */
    fun mimeType(kind: ExportKind): String = when (kind) {
        ExportKind.ENCRYPTED_BACKUP -> "application/octet-stream"
        ExportKind.OBSIDIAN_VAULT, ExportKind.HTML_SITE, ExportKind.VAULT_ZIP -> "application/zip"
        ExportKind.PAGE_PNG -> "image/png"
        ExportKind.PAGE_WEBP -> "image/webp"
        ExportKind.PAGE_PDF, ExportKind.DOCUMENT_PDF -> "application/pdf"
        ExportKind.NOTE_HTML -> "text/html"
        ExportKind.LAYERED_PSD -> "image/vnd.adobe.photoshop"
    }

    /** True when the export content is readable plaintext (not the encrypted backup). */
    fun isUnencrypted(kind: ExportKind): Boolean = kind != ExportKind.ENCRYPTED_BACKUP

    /**
     * Whole-vault plaintext exports carry EVERY note in readable form — they get
     * the bold pre-export warning before the destination picker. Single-page
     * renders and the encrypted backup do not (the picker is their consent).
     */
    fun requiresPlaintextWarning(kind: ExportKind): Boolean = when (kind) {
        ExportKind.OBSIDIAN_VAULT, ExportKind.HTML_SITE, ExportKind.VAULT_ZIP -> true
        else -> false
    }

    /** Never write outside the user-picked destination without asking. */
    fun requiresUserPickedDestination(kind: ExportKind): Boolean = true

    /**
     * The suggested file name pre-filled into the SAF picker. [generatedName] is
     * the name the export service produced (kept verbatim so the open end of the
     * flow stays predictable); guarded against blank.
     */
    fun suggestedFileName(kind: ExportKind, generatedName: String): String =
        generatedName.takeIf { it.isNotBlank() } ?: defaultExportFileName(kind)

    /** Sanitized fallback name when an export produces no friendly name. */
    fun defaultExportFileName(kind: ExportKind): String = when (kind) {
        ExportKind.ENCRYPTED_BACKUP -> "noteflow_backup.noteflow"
        ExportKind.OBSIDIAN_VAULT -> "SmoothNotes_Vault_Obsidian.zip"
        ExportKind.HTML_SITE -> "SmoothNotes_Site_HTML.zip"
        ExportKind.VAULT_ZIP -> "SmoothNotes_Vault.zip"
        ExportKind.PAGE_PNG -> "page_export.png"
        ExportKind.PAGE_WEBP -> "page_export.webp"
        ExportKind.PAGE_PDF -> "page_export.pdf"
        ExportKind.DOCUMENT_PDF -> "document_export.pdf"
        ExportKind.NOTE_HTML -> "note_export.html"
        ExportKind.LAYERED_PSD -> "canvas_export.psd"
    }

    // --- bold pre-export warning (whole-vault plaintext kinds) ------------------

    const val PLAINTEXT_WARNING_TITLE = "Export is NOT encrypted"

    const val PLAINTEXT_WARNING_BODY =
        "This export contains your notes as READABLE PLAINTEXT and will be written to the " +
            "destination you pick next (such as the shared Downloads folder). Anyone who can read " +
            "that location — other apps with storage access, a USB-connected computer, or anyone " +
            "holding the device — can read every note. Transfer the file off this device, then " +
            "DELETE the copy from the device."

    /** Suggested "how to handle it" guidance shown after a successful export. */
    fun postExportGuidance(kind: ExportKind): String = when (kind) {
        ExportKind.ENCRYPTED_BACKUP ->
            "Encrypted backup saved to your chosen location."
        ExportKind.OBSIDIAN_VAULT, ExportKind.HTML_SITE, ExportKind.VAULT_ZIP ->
            "Vault export saved to your chosen location. It is NOT encrypted — transfer the file off this device, then delete the copy here."
        else ->
            "Export saved to your chosen location. This copy is NOT encrypted — transfer it, then delete the on-device copy."
    }
}