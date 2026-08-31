package com.authorss81.noteflow.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.authorss81.noteflow.services.BiometricAuthHelper
import com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel
import kotlinx.coroutines.launch

@Composable
fun LockScreen(
    viewModel: NoteflowViewModel
) {
    var password by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isUnlocking by remember { mutableStateOf(false) }
    val biometricEnabled by viewModel.biometricEnabled.collectAsState()
    val lockoutRemainingMs by viewModel.lockoutRemainingMs.collectAsState()
    val context = LocalContext.current
    val isLockedOut = lockoutRemainingMs > 0L
    val scope = rememberCoroutineScope()

    val triggerBiometric = {
        val activity = context as? FragmentActivity
        if (activity != null && BiometricAuthHelper.isBiometricAvailable(context)) {
            val cryptoObject = viewModel.getBiometricCipher()
            if (cryptoObject == null) {
                // No crypto-bound cipher available (key invalidated, or biometric
                // key not provisioned). Never fall back to presence-only unlock.
                errorMessage = "Biometric key unavailable or invalidated. Use your Master Password instead."
                viewModel.disableBiometricFallback()
            } else {
                BiometricAuthHelper.promptBiometricAuth(
                    activity = activity,
                    cryptoObject = cryptoObject,
                    onSuccess = { result ->
                        viewModel.verifyBiometricsAndUnlock(result)
                    },
                    onError = { err ->
                        errorMessage = err
                    }
                )
            }
        } else {
            // Only allow password fallback if biometrics really unavailable
            errorMessage = "Biometrics unavailable. Please use Master Password."
        }
    }

    LaunchedEffect(biometricEnabled) {
        if (biometricEnabled) {
            triggerBiometric()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .imePadding()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(72.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = "Noteflow Locked",
                    style = MaterialTheme.typography.headlineMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Enter your Master Password to decrypt and access your notes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        // Phase 255 (lock bug): trim the input so a stray leading/
                        // trailing space from a copy-paste never silently makes
                        // the password "wrong" (the lock screen accepts
                        // whitespace-stripped input; the security dialog does
                        // the same). Existing users with passwords that
                        // intentionally contain internal spaces keep unlocking
                        // because only outer whitespace is removed.
                        password = it.trim()
                        errorMessage = null
                    },
                    label = { Text("Master Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }

                if (isLockedOut) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.errorContainer
                    ) {
                        Text(
                            text = "Too many failed attempts. Try again in ${(lockoutRemainingMs / 1000).let { "${it / 60}m ${it % 60}s" }}.",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (isUnlocking || password.isBlank()) return@Button
                        isUnlocking = true
                        scope.launch {
                            if (viewModel.verifyMasterPassword(password)) {
                                password = ""
                            } else {
                                errorMessage = if (viewModel.lockoutActive()) {
                                    "Too many failed attempts. Lockout in effect."
                                } else {
                                    "Incorrect Master Password"
                                }
                            }
                            isUnlocking = false
                        }
                    },
                    enabled = !isLockedOut && !isUnlocking,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isUnlocking) "Unlocking…" else "Unlock")
                }

                if (biometricEnabled) {
                    Spacer(modifier = Modifier.height(12.dp))
                    IconButton(
                        onClick = { triggerBiometric() }
                    ) {
                        Icon(
                            Icons.Outlined.Fingerprint,
                            contentDescription = "Biometric Unlock",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
            }
        }
    }
}
