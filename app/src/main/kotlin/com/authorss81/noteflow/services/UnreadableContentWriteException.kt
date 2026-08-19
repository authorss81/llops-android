package com.authorss81.noteflow.services

/**
 * Phase-169: thrown by the single repository write paths
 * ([com.authorss81.noteflow.data.repository.NoteRepository.updatePageBody] /
 * `updatePageTitleAndTags`) when a caller tries to persist the fail-closed
 * render marker ([DecryptFailurePolicy.UNREADABLE_MARKER]) as a page's real
 * title or body.
 *
 * If that literal marker were written, the (still encrypted) original content
 * would be permanently replaced with the marker text — the "pages become
 * unreadable and the contents don't show" data-loss path. The fail-closed
 * read path renders the marker only when GCM authentication failed, so a
 * successful write of the marker would destroy the very bytes a re-import or
 * re-key could have recovered. The write is instead REFUSED (the original
 * ciphertext stays intact) and the caller surfaces [DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE].
 *
 * Deliberately NOT a subclass of [VaultLockedWriteException] / [LockedPoolGuard]
 * — a locked vault throws those; this is thrown only while the vault is
 * UNLOCKED and the content itself failed authentication, so the ViewModel can
 * tell the two apart and show the right guidance.
 */
class UnreadableContentWriteException(message: String = "Refusing to overwrite a page whose contents could not be decrypted") :
    Exception(message)
