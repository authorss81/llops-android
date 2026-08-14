package com.authorss81.noteflow.services

import android.util.Base64
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionService {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PAYLOAD_VERSION: Byte = 1
    // Domain separation AAD: binds ciphertext to this app's field-encryption context.
    private val FIELD_AAD = "Noteflow-Vault-Field-Encryption-v1".toByteArray(Charsets.UTF_8)
    private val gson = Gson()

    fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt
    }

    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val keySpec = PBEKeySpec(password.toCharArray(), salt, 600000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(keySpec).encoded
    }

    fun generateDek(): ByteArray {
        val random = SecureRandom()
        val dek = ByteArray(32)
        random.nextBytes(dek)
        return dek
    }

    private fun base64Encode(data: ByteArray): String {
        return try {
            Base64.encodeToString(data, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(data)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            Base64.decode(str, Base64.NO_WRAP)
        } catch (e: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    fun encrypt(data: ByteArray, key: ByteArray): String {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, parameterSpec)
        cipher.updateAAD(FIELD_AAD)
        val cipherText = cipher.doFinal(data)

        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = PAYLOAD_VERSION
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)
        return base64Encode(combined)
    }

    fun decrypt(encryptedBase64: String, key: ByteArray): ByteArray {
        val combined = base64Decode(encryptedBase64)
        if (combined.size < GCM_IV_LENGTH) throw IllegalArgumentException("Invalid encrypted payload")

        // Payload v1: [version byte][12-byte IV][ciphertext] with AAD.
        // Legacy payloads (no version byte) are decrypted without AAD.
        // A version byte collision in a legacy payload is resolved by trying
        // versioned decryption first and falling back on tag mismatch.
        val versioned = combined[0] == PAYLOAD_VERSION && combined.size >= GCM_IV_LENGTH + 1
        if (versioned) {
            try {
                return decryptCore(combined, key, offset = 1, withAad = true)
            } catch (e: javax.crypto.AEADBadTagException) {
                // Fall through to legacy format
            }
        }
        return decryptCore(combined, key, offset = 0, withAad = false)
    }

    /**
     * Domain-separated AAD encryption (B2-CRYPTO-03). Same wire format as
     * [encrypt] ([PAYLOAD_VERSION] byte + 12-byte IV) but binds the given
     * caller-supplied AAD instead of the fixed [FIELD_AAD], so two KEK uses in
     * the same format (e.g. backup v2's DEK wrap vs payload) can never be
     * decrypted into each other's context. Returns the raw combined bytes
     * (version + IV + ciphertext/tag) — callers that need Base64 encode it.
     */
    fun encryptAad(data: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        val cipherText = cipher.doFinal(data)
        val combined = ByteArray(1 + iv.size + cipherText.size)
        combined[0] = PAYLOAD_VERSION
        System.arraycopy(iv, 0, combined, 1, iv.size)
        System.arraycopy(cipherText, 0, combined, 1 + iv.size, cipherText.size)
        return combined
    }

    /**
     * Decrypts a [encryptAad] payload under its AAD. On a tag mismatch the
     * pre-B2-CRYPTO-03 wrapped-DEK format (authenticated under [FIELD_AAD])
     * is retried so backups exported before domain separation still restore.
     */
    fun decryptAad(combined: ByteArray, key: ByteArray, aad: ByteArray): ByteArray {
        if (combined.size < GCM_IV_LENGTH) throw IllegalArgumentException("Invalid encrypted payload")
        val versioned = combined[0] == PAYLOAD_VERSION && combined.size >= GCM_IV_LENGTH + 1
        if (versioned) {
            try {
                return decryptCoreAad(combined, key, aad, offset = 1)
            } catch (e: javax.crypto.AEADBadTagException) {
                return decryptCoreAad(combined, key, FIELD_AAD, offset = 1)
            }
        }
        return decryptCoreAad(combined, key, aad, offset = 0)
    }

    private fun decryptCoreAad(combined: ByteArray, key: ByteArray, aad: ByteArray, offset: Int): ByteArray {
        if (combined.size < offset + GCM_IV_LENGTH) throw IllegalArgumentException("Invalid encrypted payload")

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, offset, iv, 0, GCM_IV_LENGTH)
        val cipherTextSize = combined.size - offset - GCM_IV_LENGTH
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(combined, offset + GCM_IV_LENGTH, cipherText, 0, cipherTextSize)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(cipherText)
    }

    private fun decryptCore(combined: ByteArray, key: ByteArray, offset: Int, withAad: Boolean): ByteArray {
        if (combined.size < offset + GCM_IV_LENGTH) throw IllegalArgumentException("Invalid encrypted payload")

        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, offset, iv, 0, GCM_IV_LENGTH)
        val cipherTextSize = combined.size - offset - GCM_IV_LENGTH
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(combined, offset + GCM_IV_LENGTH, cipherText, 0, cipherTextSize)

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        if (withAad) {
            cipher.updateAAD(FIELD_AAD)
        }
        return cipher.doFinal(cipherText)
    }

    fun decryptOrNull(encryptedBase64: String, key: ByteArray): String? {
        return try {
            String(decrypt(encryptedBase64, key), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    fun serializeStrokes(strokes: List<Stroke>): String {
        return gson.toJson(strokes)
    }

    fun deserializeStrokes(json: String): List<Stroke> {
        if (json.isBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<Stroke>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}
