package com.authorss81.noteflow.services

import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object BiometricAuthHelper {

    /**
     * Strong-biometric PRESENCE at prompt time (class-3 enrolled). This answers
     * "can the BiometricPrompt show a strong biometric?" — it does NOT answer
     * "can the DEK-wrapping keystore key be bound to BIOMETRIC_STRONG only?"
     * (that is [canCreateStrongBiometricBoundKey]; the finding B1-CRYPTO-07's
     * evidence point was that the two are conflated).
     */
    fun isBiometricAvailable(context: Context): Boolean {
        val biometricManager = BiometricManager.from(context)
        // Class-3 (BIOMETRIC_STRONG) only: weak/device-credential authenticators
        // are not allowed to unlock the DEK-bound key.
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
                BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * B1-CRYPTO-07 (phase-65): whether the CURRENT platform can create an
     * AndroidKeyStore key bound to `AUTH_BIOMETRIC_STRONG` only
     * ([BiometricKeyBindingPolicy] — API 30+, the `setUserAuthenticationParameters`
     * API). False on API 26-29, where the strongest binding is "any biometric per
     * use" (and bare `setUserAuthenticationRequired(true)` even accepts a device
     * credential), so enabling the biometric-lock setting is refused there.
     */
    fun canCreateStrongBiometricBoundKey(): Boolean =
        BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)

    fun promptBiometricAuth(
        activity: FragmentActivity,
        title: String = "Unlock Noteflow",
        subtitle: String = "Use fingerprint or face unlock to decrypt your encrypted notebook",
        cryptoObject: BiometricPrompt.CryptoObject? = null,
        onSuccess: (BiometricPrompt.AuthenticationResult) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        try {
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        super.onAuthenticationSucceeded(result)
                        onSuccess(result)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        super.onAuthenticationError(errorCode, errString)
                        onError(errString.toString())
                    }

                    override fun onAuthenticationFailed() {
                        super.onAuthenticationFailed()
                        onError("Authentication failed")
                    }
                }
            )

            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                )
                .build()

            if (cryptoObject != null) {
                prompt.authenticate(promptInfo, cryptoObject)
            } else {
                prompt.authenticate(promptInfo)
            }
        } catch (_: Exception) {
            // R2-b2b3-LOG-01 review (phase-148): never surface `e.message` — a
            // biometric-init failure can carry ROM/package-specific text. Fixed
            // text only; the user already has a password fallback.
            onError("Failed to initialize the biometric prompt.")
        }
    }
}
