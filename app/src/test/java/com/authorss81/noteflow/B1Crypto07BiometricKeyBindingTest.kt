package com.authorss81.noteflow

import com.authorss81.noteflow.services.BiometricKeyBindingPolicy
import com.authorss81.noteflow.services.DekAtRestMode
import com.authorss81.noteflow.services.DekAtRestPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-CRYPTO-07 (phase-65) — the vault DEK biometric key is only
 * BIOMETRIC-STRONG-bound on API 30+.
 *
 * Finding: `SecurityService.getOrCreateKey` applied
 * `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)` ONLY on API 30+;
 * on API 26-29 it used bare `.setUserAuthenticationRequired(true)`, whose default
 * validity (0) the pre-30 keystore maps to `HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC`
 * — a screen PIN/pattern/password can authorize the unwrap. `BiometricAuthHelper`
 * only checked strong-biometric *availability*, never what the key *requires*.
 *
 * Fix (this phase):
 * 1. [BiometricKeyBindingPolicy] — pure-JVM decision table: API 26-29 cannot bind
 *    a key to AUTH_BIOMETRIC_STRONG, so the feature is REFUSED there with a
 *    non-alarming message.
 * 2. `NoteflowViewModel.setBiometricEnabled` refuses the enable below API 30;
 *    `enforceDekAtRestPolicy` DOWNGRADES any legacy enabled state to password-only
 *    below API 30 (clears the weak-bound device copy).
 * 3. `SecurityService.getOrCreateKey` binds any pre-30 auth key defensively with
 *    `setUserAuthenticationValidityDurationSeconds(-1)` (biometric-only per use —
 *    never the device-credential path); `getDecryptionCipher` refuses below API 30;
 *    `storeDek` stamps the API-level marker (the finding's "explicit marker").
 */
class B1Crypto07BiometricKeyBindingTest {

    // ---------- BiometricKeyBindingPolicy: the decision table ----------

    @Test
    fun `API 26 through 29 cannot create a strong-biometric-bound key`() {
        for (api in listOf(26, 27, 28, 29)) {
            assertFalse("API $api must be refused for strong binding", BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(api))
        }
    }

    @Test
    fun `API 30 and newer can create a strong-biometric-bound key`() {
        for (api in listOf(30, 31, 32, 33, 34, 35, 36)) {
            assertTrue("API $api must support strong binding", BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported(api))
        }
    }

    @Test
    fun `pre-30 biometric-only binding constant is minus one`() {
        // -1 is the ONLY pre-30 validity the keystore maps to HW_AUTH_BIOMETRIC
        // (biometric-only); every other value (incl. the default 0) accepts a
        // device credential as well.
        assertEquals(-1, BiometricKeyBindingPolicy.PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS)
    }

    @Test
    fun `refusal message is present below API 30 and clear not alarming`() {
        val message = BiometricKeyBindingPolicy.refuseEnableMessage(29)
        assertNotNull("API 29 must yield a refusal message", message)
        message!!.also {
            assertTrue("must mention the strong-biometric key binding", it.contains("strong biometric"))
            assertTrue("must point back to the Master Password protection", it.contains("Master Password"))
            assertFalse("must not sound like an attack alarm", it.contains("danger"))
        }
    }

    @Test
    fun `no refusal message on API 30 and newer`() {
        assertNull(BiometricKeyBindingPolicy.refuseEnableMessage(30))
        assertNull(BiometricKeyBindingPolicy.refuseEnableMessage(36))
    }

    @Test
    fun `pre-30 with biometrics enabled downgrades to password only`() {
        // The B1-CRYPTO-02 at-rest policy, fed the NEW binding-support input: a
        // master-password vault on API 26-29 with biometrics "on" is PASSWORD_ONLY —
        // the weak-bound device copy must be cleared, never re-written.
        assertEquals(
            DekAtRestMode.PASSWORD_ONLY,
            DekAtRestPolicy.modeFor(
                hasMasterPassword = true,
                biometricAuthEnabled = true,
                strongBiometricBindingSupported = false,
            )
        )
    }

    @Test
    fun `API 30 plus biometrics enabled keeps the auth-gated copy`() {
        assertEquals(
            DekAtRestMode.BIOMETRIC_GATED_AUTH_COPY,
            DekAtRestPolicy.modeFor(
                hasMasterPassword = true,
                biometricAuthEnabled = true,
                strongBiometricBindingSupported = true,
            )
        )
    }

    @Test
    fun `biometrics off is password only regardless of binding support`() {
        assertEquals(
            DekAtRestMode.PASSWORD_ONLY,
            DekAtRestPolicy.modeFor(hasMasterPassword = true, biometricAuthEnabled = false, strongBiometricBindingSupported = true)
        )
        assertEquals(
            DekAtRestMode.PASSWORD_ONLY,
            DekAtRestPolicy.modeFor(hasMasterPassword = true, biometricAuthEnabled = false, strongBiometricBindingSupported = false)
        )
    }

    @Test
    fun `passwordless boot is unaffected by binding support`() {
        // No master password: the non-gated device copy is the boot credential and
        // stays untouched on every API level.
        assertEquals(
            DekAtRestMode.DEVICE_WRAPPED_NOT_AUTHGATED,
            DekAtRestPolicy.modeFor(hasMasterPassword = false, biometricAuthEnabled = true, strongBiometricBindingSupported = false)
        )
    }

    @Test
    fun `two-arg modeFor keeps its pre-fix default (binding supported)`() {
        // Backward compatibility: existing call sites (and the phase-45 test mirror
        // in B1Crypto02DekAtRestTest) pass two args and expect the old table.
        assertEquals(
            DekAtRestMode.BIOMETRIC_GATED_AUTH_COPY,
            DekAtRestPolicy.modeFor(hasMasterPassword = true, biometricAuthEnabled = true)
        )
    }

    // ---------- source pins: SecurityService ----------
    // Key creation is AndroidKeyStore-bound and cannot run on the pure JVM; pin the
    // exact binding branches and the marker stamp at source level (same technique as
    // B1Crypto02DekAtRestTest / B1Crypto05SilentRekeyTest).

    @Test
    fun `SecurityService binds API 30 plus keys to AUTH_BIOMETRIC_STRONG only`() {
        val source = read("services/SecurityService.kt")
        assertTrue(
            "API-30+ branch must call setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)",
            source.contains("setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)")
        )
    }

    @Test
    fun `SecurityService never leaves a pre-30 auth key on the bare-required path`() {
        val source = read("services/SecurityService.kt")
        // The pre-30 branch must bind biometric-only per use (-1), never fall through
        // to a bare setUserAuthenticationRequired(true) whose default validity (0)
        // lands in HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC (a PIN would satisfy it).
        assertTrue(
            "pre-30 auth branch must call setUserAuthenticationValidityDurationSeconds",
            source.contains("setUserAuthenticationValidityDurationSeconds(")
        )
        assertTrue(
            "pre-30 branch must use the policy's -1 constant",
            source.contains("BiometricKeyBindingPolicy.PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS")
        )
        assertTrue(
            "the API-30+ setUserAuthenticationParameters must be gated on authRequired && API >= R",
            source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.R")
        )
    }

    @Test
    fun `SecurityService stores an explicit API-level marker at wrap time`() {
        val source = read("services/SecurityService.kt")
        assertTrue(
            "storeDek must stamp the platform API level onto the device blob",
            source.contains("wrapperApiLevel = Build.VERSION.SDK_INT")
        )
        assertTrue(
            "the blob data class must carry wrapperApiLevel",
            source.contains("val wrapperApiLevel: Int = 0")
        )
        assertTrue(
            "the shared-prefs store must persist the marker",
            source.contains("KEY_WRAPPER_API_LEVEL")
        )
    }

    @Test
    fun `SecurityService getDecryptionCipher refuses below a strong-binding platform`() {
        val source = read("services/SecurityService.kt")
        val cipherBlock = source.substringAfter("fun getDecryptionCipher", "END")
            .substringBefore("fun decryptWithCipher", "END")
        assertTrue(
            "getDecryptionCipher must refuse when strong binding is unsupported",
            cipherBlock.contains("strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)")
        )
    }

    @Test
    fun `SecurityService KDoc documents the pre-30 device-credential trap`() {
        val source = read("services/SecurityService.kt")
        assertTrue(
            "the pre-30 branch must be documented as a PIN/device-credential hazard",
            source.contains("PIN/pattern/password")
        )
    }

    // ---------- source pins: NoteflowViewModel (authoritative gates) ----------

    @Test
    fun `setBiometricEnabled refuses below a strong-binding platform`() {
        val source = read("ui/viewmodel/NoteflowViewModel.kt")
        val block = source.substringAfter("suspend fun setBiometricEnabled", "END")
            .substringBefore("fun getBiometricCipher", "END")
        assertTrue(
            "enabling must be gated on the strong-binding policy",
            block.contains("strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)")
        )
        assertTrue(
            "a refused enable must surface the non-alarming message",
            block.contains("_biometricRefusalMessage")
        )
        assertTrue(
            "the enable gate must refuse BEFORE flipping the setting / writing a blob",
            block.indexOf("strongBiometricKeyBindingSupported") < block.indexOf("settings.biometricAuthEnabled = enabled")
        )
    }

    @Test
    fun `enforceDekAtRestPolicy downgrades legacy biometrics below API 30`() {
        val source = read("ui/viewmodel/NoteflowViewModel.kt")
        val block = source.substringAfter("private fun enforceDekAtRestPolicy", "END")
        assertTrue(
            "enforce must feed the strong-binding support into the policy",
            block.contains("strongBiometricBindingSupported")
        )
        assertTrue(
            "a legacy enabled state below API 30 must be turned off (downgrade)",
            block.contains("settings.biometricAuthEnabled = false")
        )
        assertTrue(
            "the downgrade must clear the weak-bound device copy via PASSWORD_ONLY",
            block.contains("security.clearDek()")
        )
    }

    @Test
    fun `getBiometricCipher refuses below a strong-binding platform`() {
        val source = read("ui/viewmodel/NoteflowViewModel.kt")
        val block = source.substringAfter("fun getBiometricCipher", "END")
        assertTrue(
            "getBiometricCipher must never hand a cipher to the prompt below API 30",
            block.contains("strongBiometricKeyBindingSupported(Build.VERSION.SDK_INT)")
        )
    }

    // ---------- source pins: BiometricAuthHelper + settings dialog ----------

    @Test
    fun `BiometricAuthHelper exposes the key-binding capability separately`() {
        val source = read("services/BiometricAuthHelper.kt")
        assertTrue(
            "BiometricAuthHelper must answer 'can we create a strong-bound key?'",
            source.contains("fun canCreateStrongBiometricBoundKey")
        )
        assertTrue(
            "it must delegate to the pure-JVM policy",
            source.contains("BiometricKeyBindingPolicy.strongBiometricKeyBindingSupported")
        )
    }

    @Test
    fun `settings dialog gates the biometric switch and surfaces the refusal message`() {
        val source = read("ui/components/Dialogs.kt")
        val securityBlock = source.substringAfter("fun SecuritySettingsDialog", "END")
        assertTrue(
            "the switch must refuse enabling up-front on a non-strong platform",
            securityBlock.contains("canCreateStrongBiometricBoundKey()")
        )
        assertTrue(
            "the dialog must surface the ViewModel refusal message instead of a generic error",
            securityBlock.contains("biometricRefusalMessage")
        )
    }

    // ---------- helpers ----------

    private fun read(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
