package com.authorss81.noteflow.services

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the vault DEK under an AndroidKeyStore key and persists it as the
 * "device copy" in `noteflow_keystore` SharedPreferences.
 *
 * B1-CRYPTO-02 (phase-45): before the fix, [getOrCreateDek] persisted the vault's
 * DEK under a NON-user-authenticated AndroidKeyStore key (`authRequired = false`,
 * key alias `noteflow_dek_key`), and the master-password flows never removed that
 * copy — so a root/forensic attacker or an in-process plugin could invoke the
 * keystore key under the app UID with no credential and recover the DEK. The
 * invariant now enforced by [DekAtRestPolicy] (see `NoteflowViewModel.enforceDekAtRestPolicy`)
 * is: when a master password exists, the only at-rest wrapping of the DEK is the
 * password-derived KEK, UNLESS the user explicitly enabled biometrics — and then
 * the device copy exists ONLY as the `authRequired = true` (biometric-gated) blob.
 *
 * The persistence layer is isolated behind [DekDeviceStore] so the fail-closed
 * invariants (absent/gated blob ⇒ [readDek] returns null without a credential) are
 * unit-testable on the JVM without AndroidKeyStore.
 */
class SecurityService internal constructor(
    private val dekStore: DekDeviceStore,
) {
    companion object {
        private const val KEY_ALIAS = "noteflow_dek_key"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /**
         * Android entry point: binds the real SharedPreferences-backed device store.
         * (Private primary constructor + factory so pure-JVM tests can inject an
         * in-memory [DekDeviceStore] without a Context.)
         */
        fun forDevice(context: Context): SecurityService = SecurityService(SharedPrefsDekDeviceStore(context))
    }

    private fun getOrCreateKey(authRequired: Boolean = false): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val alias = if (authRequired) "${KEY_ALIAS}_auth" else KEY_ALIAS

        // Phase 31 Part C3: NEVER blindly cast `keyStore.getEntry(alias, null)`
        // to SecretKeyEntry. The stored entry on a given device/emulator may be a
        // PrivateKeyEntry, a TrustedCertificateEntry, a migrated/legacy entry, or
        // unreadable — an unchecked `as SecretKeyEntry` then throws
        // ClassCastException during DEK init and kills cold start. Inspect the
        // entry type instead; a wrong/invalid entry is cleared and re-created.
        resolveSecretKeyEntry(keyStore, alias)?.let { return it.secretKey }
        // Wrong-typed / unreadable entry under the alias: delete it so the
        // generator below mints a fresh SecretKey entry instead of colliding.
        if (keyStore.containsAlias(alias)) {
            runCatching { keyStore.deleteEntry(alias) }
        }
 
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(authRequired)
            .let {
                if (authRequired && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    it.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                } else {
                    it
                }
            }
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
 
    fun getDecryptionCipher(): Cipher? {
        val blob = dekStore.read() ?: return null
        return try {
            val combined = Base64.decode(blob.encoded, Base64.NO_WRAP)
            if (combined.size < 12) return null
            val iv = ByteArray(12)
            System.arraycopy(combined, 0, iv, 0, 12)
            
            val secretKey = getOrCreateKey(authRequired = true)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher
        } catch (e: Exception) {
            null
        }
    }

    fun decryptWithCipher(cipher: Cipher): ByteArray? {
        val blob = dekStore.read() ?: return null
        return try {
            val combined = Base64.decode(blob.encoded, Base64.NO_WRAP)
            val cipherText = ByteArray(combined.size - 12)
            System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Wraps [dek] under the AndroidKeyStore key and persists it as the device
     * copy. Returns true only when the wrap + persistence both succeeded.
     *
     * B1-CRYPTO-02 (phase-45 review fix): the previous implementation swallowed
     * every keystore/AEAD exception and returned Unit, so the master-password
     * flows could report "biometrics enabled / device copy written" while the
     * at-rest state was actually absent or stale. Failures now surface to the
     * caller ([NoteflowViewModel.enforceDekAtRestPolicy]) so the biometrics
     * setting is only committed when the auth-gated blob demonstrably exists.
     */
    fun storeDek(dek: ByteArray, authRequired: Boolean = false): Boolean {
        return try {
            val secretKey = getOrCreateKey(authRequired)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey)
            val iv = cipher.iv
            val encryptedDek = cipher.doFinal(dek)

            val combined = ByteArray(iv.size + encryptedDek.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedDek, 0, combined, iv.size, encryptedDek.size)

            val encoded = Base64.encodeToString(combined, Base64.NO_WRAP)
            dekStore.write(DekDeviceBlob(encoded, authRequired))
            true
        } catch (e: Exception) {
            // Return the failure to the caller instead of crashing app startup.
            false
        }
    }
 
    /**
     * Reads the passwordless device copy of the DEK — B1-CRYPTO-02 fails closed:
     * an absent blob OR an auth-gated (`authRequired=true`, biometric-only) blob
     * returns null, never a passwordless unwrap. When the vault has a master
     * password and biometrics are OFF there is deliberately no device copy, so
     * this is the "no password ⇒ null" guard the finding requires.
     */
    fun readDek(): ByteArray? {
        val blob = dekStore.read() ?: return null
        if (blob.authRequired) return null // Must use biometric unlock flow

        return try {
            val combined = Base64.decode(blob.encoded, Base64.NO_WRAP)
            if (combined.size < 12) return null
            val iv = ByteArray(12)
            System.arraycopy(combined, 0, iv, 0, 12)
            val cipherText = ByteArray(combined.size - 12)
            System.arraycopy(combined, 12, cipherText, 0, cipherText.size)
 
            val secretKey = getOrCreateKey(authRequired = false)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            cipher.doFinal(cipherText)
        } catch (e: Exception) {
            null
        }
    }

    fun getOrCreateDek(allowPasswordlessMint: Boolean = true): ByteArray? {
        val existing = readDek()
        if (existing != null) return existing
        val blob = dekStore.read()
        // A biometric-gated device copy exists but was not unlockable without the
        // biometric flow: never silently mint/re-persist a fresh fallback key.
        if (blob != null && blob.authRequired) return null
        // B1-CRYPTO-02 (phase-45 review fix): when a master password exists the
        // ONLY at-rest wrapping of the DEK is the password-derived KEK. A locked
        // open (VaultKeyHolder.dek == null) must NOT mint a fresh non-auth DEK —
        // that would both recreate the bypass blob AND open the vault with the
        // wrong SQLCipher passphrase (SQLiteNotADatabaseException → phase-43
        // quarantine). The DB factory gates this with
        // `allowPasswordlessMint = !settings.hasMasterPassword`; belt-and-braces
        // here so no future caller can re-open the mint path on a protected vault.
        if (!allowPasswordlessMint) return null

        val newDek = EncryptionService.generateDek()
        storeDek(newDek, authRequired = false)
        return newDek
    }

    /**
     * Removes the device-wrapped DEK copy completely (B1-CRYPTO-02). After this,
     * [readDek] returns null with no credential and the ONLY at-rest wrapping of
     * the vault DEK is the password-derived KEK in settings. Returns the durable
     * clear result so the caller only reports success once the blob is
     * disk-acknowledged-gone.
     */
    fun clearDek(): Boolean {
        return dekStore.clear()
    }
}

/**
 * B1-CRYPTO-02 (phase-45) persistence seam for the device-wrapped DEK copy.
 * `internal` so pure-JVM tests in this module can inject an in-memory fake and
 * prove the fail-closed invariants without AndroidKeyStore/Context.
 */
internal interface DekDeviceStore {
    fun read(): DekDeviceBlob?
    fun write(blob: DekDeviceBlob)
    /** True when the device copy was durably removed. */
    fun clear(): Boolean
}

/** The persisted wrapper: Base64(iv + AES-GCM(DEK)) plus the auth-gating flag. */
internal data class DekDeviceBlob(val encoded: String, val authRequired: Boolean)

/**
 * Real SharedPreferences-backed [DekDeviceStore] (`noteflow_keystore` file,
 * keys `noteflow_sec_dek` + `dek_auth_required`).
 *
 * B1-CRYPTO-02: [clear] uses `commit()` — not the async `.apply()` — so the
 * non-auth blob is disk-acknowledged-gone BEFORE the master-password flows
 * report success; a process kill right after "password set" cannot resurrect the
 * bypass byte copy. It also clears the stale `dek_auth_required` flag so a
 * removed non-auth wrapper can never masquerade as a biometric-gated key.
 */
internal class SharedPrefsDekDeviceStore(context: Context) : DekDeviceStore {
    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    override fun read(): DekDeviceBlob? {
        val encoded = prefs.getString(KEY_DEK, null) ?: return null
        return DekDeviceBlob(encoded, prefs.getBoolean(KEY_AUTH_REQUIRED, false))
    }

    override fun write(blob: DekDeviceBlob) {
        prefs.edit()
            .putString(KEY_DEK, blob.encoded)
            .putBoolean(KEY_AUTH_REQUIRED, blob.authRequired)
            .apply()
    }

    override fun clear(): Boolean {
        return prefs.edit()
            .remove(KEY_DEK)
            .remove(KEY_AUTH_REQUIRED)
            .commit()
    }

    private companion object {
        const val PREF_NAME = "noteflow_keystore"
        const val KEY_DEK = "noteflow_sec_dek"
        const val KEY_AUTH_REQUIRED = "dek_auth_required"
    }
}

/**
 * Phase 31 Part C3 seam: resolve the DEK-wrapping key stored at [alias] WITHOUT
 * an unchecked cast. Returns the key only when the alias holds a genuine
 * [KeyStore.SecretKeyEntry]; returns null for an absent alias, a wrong-typed
 * entry (PrivateKeyEntry / TrustedCertificateEntry / migrated-legacy entry),
 * or an entry that cannot be read (getEntry threw). A null result means the
 * caller must clear the alias and mint a fresh key — never a crash.
 *
 * Pure JVM (only java.security.KeyStore) so the wrong-entry-type path is
 * unit-testable with a fake/in-memory keystore on the JVM.
 */
internal fun resolveSecretKeyEntry(keyStore: KeyStore, alias: String): KeyStore.SecretKeyEntry? {
    if (!keyStore.containsAlias(alias)) return null
    val entry = try {
        keyStore.getEntry(alias, null)
    } catch (e: Exception) {
        null
    }
    return entry as? KeyStore.SecretKeyEntry
}
