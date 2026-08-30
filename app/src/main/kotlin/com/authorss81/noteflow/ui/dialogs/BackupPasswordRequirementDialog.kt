package com.authorss81.noteflow.ui.dialogs

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.authorss81.noteflow.R

/**
 * Phase 252 (HIGH 4/5): the NON-BYPASSABLE warning shown when the user taps
 * "Backup" on a vault with NO master password.
 *
 * A backup from a passwordless vault is encrypted with the device-wrapped DEK
 * (B1-CRYPTO-05): the AndroidKeyStore-bound blob can only be unwrapped on the
 * originating device, so the archive is effectively wedded to one piece of
 * hardware — lose the device / factory-reset / a keystore re-key and the
 * backup is unreadable forever. Until phase-252 the export path shipped this
 * archive silently.
 *
 * There is exactly ONE road to a portable backup: set a master password first.
 * The dialog therefore has only two actions — "Set Master Password"
 * (dismisses this dialog and opens the Security settings where the master
 * password is enabled) and "Cancel Export". There is deliberately NO "export
 * anyway" affordance; the only way out of this dialog is setting a master
 * password or abandoning the export.
 *
 * Strings live in `strings.xml` (no hardcoded English literals in the
 * composable) so the copy is translatable and source-pinnable.
 */
@Composable
fun BackupPasswordRequirementDialog(
    onSetMasterPassword: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.backup_password_requirement_title)) },
        text = { Text(stringResource(R.string.backup_password_requirement_body)) },
        confirmButton = {
            Button(onClick = onSetMasterPassword) {
                Text(stringResource(R.string.backup_password_requirement_set_password))
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.backup_password_requirement_cancel_export))
            }
        }
    )
}