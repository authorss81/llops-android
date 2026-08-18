package com.authorss81.noteflow.ui.components

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.authorss81.noteflow.services.ExportDestinationPolicy
import com.authorss81.noteflow.services.ExportStagingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Outcome of an SAF export for the caller, distinguishing a genuine user cancel
 * from a failed write to a confirmed destination (R2-B1P-02 phase-141 review
 * fix): the previous `(Boolean) -> Unit` collapsed both into `false`, so a
 * copy-failure on a confirmed destination was shown to the user as "Export
 * cancelled", which is false and discards the "retry exists" signal (the file is
 * KEPT in that case).
 */
enum class SaFExportResult { SAVED, CANCELLED, FAILED }

/**
 * B1-PLAT-3 (phase-59): the single route for every user-facing export.
 *
 * An export request is delivered ONLY through the system Storage Access
 * Framework destination picker (`ACTION_CREATE_DOCUMENT`) — the app never writes
 * an export into public shared storage on its own. The whole-vault PLAINTEXT
 * kinds (Obsidian zip / HTML site / notebook & section vault zips) additionally
 * require the bold unencrypted-warning consent dialog before the picker opens;
 * the encrypted backup and single-page renders simply go to the picker (that IS
 * the consent).
 *
 * On a successful write the generated cacheDir copy is deleted (transfer-then-delete
 * guidance from the finding); a cancelled/dismissed picker deletes it too (R2-B1P-02,
 * phase-141) so a decrypted export never lingers in cacheDir — but a failed write to a
 * confirmed destination KEEPS the file so the user can retry. [SaFExportResult] tells
 * the caller which of the three happened. API floor: `ACTION_CREATE_DOCUMENT` exists
 * since API 19, well below the app's minSdk 26 — no fallback needed.
 */
class SaFExporter internal constructor(
    private val doExport: (ExportDestinationPolicy.ExportKind, File, (SaFExportResult) -> Unit) -> Unit
) {
    /**
     * @param kind   which export kind (drives MIME, suggested name, consent gate).
     * @param file   the generated export currently in app-private cacheDir.
     * @param onDone called with the outcome (SAVED / CANCELLED / FAILED).
     */
    fun export(kind: ExportDestinationPolicy.ExportKind, file: File, onDone: (SaFExportResult) -> Unit) {
        doExport(kind, file, onDone)
    }
}

@Composable
fun rememberSaFExporter(scope: CoroutineScope = rememberCoroutineScope()): SaFExporter {
    val context = LocalContext.current

    // R2-B1P-02 phase-141 review fix: the in-flight request and the warning
    // gate are rememberSaveable so a rotation / recreation while the SAF picker
    // (or the consent dialog) is open keeps the staging-cleanup contract alive —
    // the request reference survives and the pending file is still deleted (or
    // KEPT on copy-failure). Only the done callback is intentionally non-saved
    // (a lambda from the caller; post-rotation the caller's composition is new
    // anyway, so a post-rotation completion simply skips the original callback).
    var pendingRequest by rememberSaveable { mutableStateOf<Pair<ExportDestinationPolicy.ExportKind, File>?>(null) }
    var pendingWarningKind by rememberSaveable { mutableStateOf<ExportDestinationPolicy.ExportKind?>(null) }
    var pendingDone by remember { mutableStateOf<((SaFExportResult) -> Unit)?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val request = pendingRequest
        val done = pendingDone
        pendingRequest = null
        pendingDone = null
        if (request == null) {
            done?.invoke(SaFExportResult.CANCELLED)
            return@rememberLauncherForActivityResult
        }
        val file = request.second
        scope.launch {
            // The picker may be RESULT_OK (user confirmed a destination) or
            // cancelled/dismissed — the staging copy is cleaned up on EVERY
            // outcome via ExportStagingPolicy (R2-B1P-02, phase-141).
            var ok = false
            if (result.resultCode == Activity.RESULT_OK) {
                val uri = result.data?.data
                if (uri != null) {
                    ok = withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                file.inputStream().use { it.copyTo(out) }
                            } != null
                        }.getOrDefault(false)
                    }
                }
            }
            if (ExportStagingPolicy.cleanupAfterSaF(
                    resultCode = result.resultCode,
                    destinationUriPresent = result.data?.data != null,
                    copySucceeded = if (result.resultCode == Activity.RESULT_OK && result.data?.data != null) ok else null
                ) == ExportStagingPolicy.Cleanup.DELETE
            ) {
                // Transfer-then-delete: drop the app-private staging copy once the
                // bytes are at the user-picked destination — and on cancel/no-data
                // so a decrypted export can never linger in the cache dirs.
                runCatching { file.delete() }
            }
            val outcome = when {
                ok -> SaFExportResult.SAVED
                result.resultCode == Activity.RESULT_OK && result.data?.data != null -> SaFExportResult.FAILED
                else -> SaFExportResult.CANCELLED
            }
            done?.invoke(outcome)
        }
    }

    if (pendingWarningKind != null && pendingRequest != null) {
        val kind = pendingWarningKind!!
        AlertDialog(
            onDismissRequest = {
                pendingWarningKind = null
                // R2-B1P-02 (phase-141): dismissing the plaintext-warning dialog is a
                // cancel outcome — drop the staged (decrypted) export too.
                pendingRequest?.second?.let { runCatching { it.delete() } }
                pendingRequest = null
                pendingDone?.invoke(SaFExportResult.CANCELLED)
                pendingDone = null
            },
            title = {
                Text(
                    ExportDestinationPolicy.PLAINTEXT_WARNING_TITLE,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )
            },
            text = { Text(ExportDestinationPolicy.PLAINTEXT_WARNING_BODY) },
            confirmButton = {
                TextButton(onClick = {
                    pendingWarningKind = null
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = ExportDestinationPolicy.mimeType(kind)
                        putExtra(
                            Intent.EXTRA_TITLE,
                            ExportDestinationPolicy.suggestedFileName(kind, pendingRequest!!.second.name)
                        )
                    }
                    picker.launch(intent)
                }) {
                    Text("Choose destination")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pendingWarningKind = null
                    // R2-B1P-02 (phase-141): explicit Cancel of the consent dialog is a
                    // cancel outcome — drop the staged (decrypted) export too.
                    pendingRequest?.second?.let { runCatching { it.delete() } }
                    pendingRequest = null
                    pendingDone?.invoke(SaFExportResult.CANCELLED)
                    pendingDone = null
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    return remember(picker, scope) {
        SaFExporter { kind, file, onDone ->
            pendingRequest = kind to file
            pendingDone = onDone
            if (ExportDestinationPolicy.requiresPlaintextWarning(kind)) {
                pendingWarningKind = kind
            } else {
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = ExportDestinationPolicy.mimeType(kind)
                    putExtra(
                        Intent.EXTRA_TITLE,
                        ExportDestinationPolicy.suggestedFileName(kind, file.name)
                    )
                }
                picker.launch(intent)
            }
        }
    }
}
