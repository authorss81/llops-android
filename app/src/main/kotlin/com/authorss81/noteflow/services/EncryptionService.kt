package com.authorss81.noteflow.services

import android.util.Base64
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.security.SecureRandom
import java.text.BreakIterator
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object EncryptionService {
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val PAYLOAD_VERSION: Byte = 1

    // B2-CRYPTO-07 (phase-113): password length bounds, measured in extended
    // grapheme clusters (perceived characters), not UTF-16 code units. We store
    // and derive the NFKC-normalized password, so both the 6-char minimum and
    // the 128-char cap are enforced against that normalized form. The cap keeps
    // a pathological input from inflating PBKDF2 work (HMAC-SHA256 processes the
    // password in 64-byte blocks) and is far above any real user password.
    const val MIN_PASSWORD_GRAPHEMES = 6
    const val MAX_PASSWORD_GRAPHEMES = 128
    // Domain separation AAD: binds ciphertext to this app's field-encryption context.
    private val FIELD_AAD = "Noteflow-Vault-Field-Encryption-v1".toByteArray(Charsets.UTF_8)
    // B2-CRYPTO-09 (phase-107): per-record AAD prefix. Every field ciphertext now
    // authenticates under `v2|<table>|<recordId>|<fieldName>` instead of the single
    // global FIELD_AAD, so a ciphertext can never be relocated (transplanted) into a
    // different record or column. The v1 constant above is retained ONLY as the
    // migration fallback reader for rows written before this phase.
    private const val FIELD_AAD_V2_PREFIX = "Noteflow-Vault-Field-Encryption-v2|"
    private val gson = Gson()

    fun generateSalt(): ByteArray {
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)
        return salt
    }

    // ---------- B2-CRYPTO-07 (phase-113): Unicode normalization for passwords ----------

    /**
     * NFKC-normalizes a raw password before any length accounting or key
     * derivation (B2-CRYPTO-07).
     *
     * NFKC is the strongest of the standard forms: it both canonicalizes
     * (composed vs decomposed accents — NFC `é` U+00E9 vs NFD `e` + U+0301
     * collapse to the SAME bytes, so a password typed on one keyboard/IME
     * always matches the same password typed another way) and folds
     * compatibility characters (full-width `Ａ`→`A`, ligatures `ﬁ`→`fi`).
     * Without it, two "visually identical" passwords were silently different,
     * permanently locking the vault with no diagnostic. With exactly ONE
     * normalized derivation path, two byte sequences can never silently
     * collide either.
     */
    fun normalizePassword(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFKC)

    /**
     * Length of a password in extended grapheme clusters (perceived characters)
     * AFTER NFKC normalization — see [normalizePassword]. `String.length` counts
     * UTF-16 code units, so a full-width or accent-composed password would be
     * over/under-counted; graphemes are what the user actually perceives.
     */
    fun normalizedGraphemeCount(raw: String): Int {
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(normalizePassword(raw))
        var count = 0
        while (iterator.next() != BreakIterator.DONE) count++
        return count
    }

    /** True iff the NFKC-normalized password is within [MIN_PASSWORD_GRAPHEMES]..[MAX_PASSWORD_GRAPHEMES]. */
    fun isValidPasswordLength(raw: String): Boolean {
        val graphemes = normalizedGraphemeCount(raw)
        return graphemes in MIN_PASSWORD_GRAPHEMES..MAX_PASSWORD_GRAPHEMES
    }

    /**
     * The candidate passwords for [deriveKey], newest-form first (B2-CRYPTO-07).
     *
     * A stored key is either (a) a post-fix key, derived from the NFKC
     * normalized form, which matches the single normalized candidate, or
     * (b) a PRE-fix key, derived from the raw UTF-16 bytes the user typed —
     * which only matches when that raw string was already NFKC-compatible. For
     * pre-fix vaults/backups whose password was set with a non-NFKC byte
     * sequence (e.g. an IME that emits NFD, or a compatibility character), the
     * normalized candidate differs and the RAW string is returned second as a
     * legacy-compat reader. When raw is already normalized the list has a
     * single element, so the common path never runs a second PBKDF2.
     */
    internal fun passwordCandidates(raw: String): List<String> {
        val normalized = normalizePassword(raw)
        return if (normalized == raw) listOf(normalized) else listOf(normalized, raw)
    }

    /**
     * The candidate KEKs for a typed password, newest-form first, derived WITH
     * the correct path for each form (B2-CRYPTO-07):
     *
     *  1. the NFKC-normalized key — `deriveKey`, the single path used to WRITE
     *     every key since phase-113 (the input is normalized inside, so the
     *     composed and decomposed spellings of the same password both land here);
     *  2. ONLY when the raw input is not already normalized, the legacy
     *     pre-fix key derived from the raw UTF-16 bytes via [deriveKeyLegacyRaw],
     *     so a vault or backup created before normalization with a non-NFKC
     *     byte sequence still opens.
     *
     * Callers must zeroize EVERY returned key: the elected one once they are
     * done with it, and every rejected candidate.
     */
    internal fun deriveKeyCandidates(password: String, salt: ByteArray): List<ByteArray> {
        val normalized = normalizePassword(password)
        return passwordCandidates(password).map { candidate ->
            if (candidate == normalized) deriveKey(candidate, salt) else deriveKeyLegacyRaw(candidate, salt)
        }
    }

    /**
     * Derives the 32-byte AES key from a password (B2-CRYPTO-07).
     *
     * THE single derivation path for vault and backup passwords: it ALWAYS
     * NFKC-normalizes ([normalizePassword]) the input first, so a password set
     * once as NFC and retyped as NFD (or vice versa) derives the identical key.
     * PBKDF2WithHmacSHA256, 600k iterations, 256-bit output, 16-byte per-vault
     * salt. Callers must zeroize the returned key after use.
     */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val keySpec = PBEKeySpec(normalizePassword(password).toCharArray(), salt, 600000, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        return factory.generateSecret(keySpec).encoded
    }

    /**
     * Legacy PRE-B2-CRYPTO-07 key derivation — the raw UTF-16 bytes with NO
     * normalization, exactly as `deriveKey` behaved before phase-113.
     *
     * Used ONLY by the [passwordCandidates] legacy read path so a vault or
     * password-protected backup created BEFORE normalization with a non-NFKC
     * password byte sequence still unlocks/restores. Never used to WRITE new
     * keys. Wrong-password attempts reach it only through human input at a
     * device prompt; both it and the normalized path run the same 600k-iteration
     * PBKDF2 behind the same GCM tag check, so it adds no oracle.
     */
    internal fun deriveKeyLegacyRaw(password: String, salt: ByteArray): ByteArray {
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
            val encoded = Base64.encodeToString(data, Base64.NO_WRAP)
            if (encoded != null) encoded else java.util.Base64.getEncoder().encodeToString(data)
        } catch (e: Throwable) {
            java.util.Base64.getEncoder().encodeToString(data)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            val decoded = Base64.decode(str, Base64.NO_WRAP)
            if (decoded != null) decoded else java.util.Base64.getDecoder().decode(str)
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

    /**
     * Decrypts a field payload produced by [encrypt].
     *
     * B2-CRYPTO-05 (deterministic format selection): this app has only ever
     * written versioned payloads — `[PAYLOAD_VERSION][12-byte IV][ciphertext+tag]`
     * authenticated under [FIELD_AAD] — so a payload whose first byte is not the
     * version marker is rejected outright. A GCM tag mismatch is final and is
     * NEVER retried against a re-guessed legacy layout; the old code ran a second
     * full decrypt on any AEADBadTagException (a measurable timing/tag oracle
     * that classified rows as versioned vs legacy and gave every bad tag one
     * malleable retry). There is no legacy no-AAD format to fall back to.
     */
    fun decrypt(encryptedBase64: String, key: ByteArray): ByteArray {
        val combined = base64Decode(encryptedBase64)
        if (combined.size < GCM_IV_LENGTH + 1) throw IllegalArgumentException("Invalid encrypted payload")
        if (combined[0] != PAYLOAD_VERSION) {
            throw IllegalArgumentException("Unsupported payload format: missing version marker")
        }
        return decryptCore(combined, key)
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
     *
     * The fallback retries only an [javax.crypto.AEADBadTagException] (a
     * well-formed ciphertext whose AAD differs); format/size errors propagate
     * because re-AADing cannot fix them — the same policy [decrypt] follows
     * for malformed/unversioned payloads.
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

    private fun decryptCore(combined: ByteArray, key: ByteArray): ByteArray {
        val iv = ByteArray(GCM_IV_LENGTH)
        System.arraycopy(combined, 1, iv, 0, GCM_IV_LENGTH)
        val cipherTextSize = combined.size - 1 - GCM_IV_LENGTH
        val cipherText = ByteArray(cipherTextSize)
        System.arraycopy(combined, 1 + GCM_IV_LENGTH, cipherText, 0, cipherTextSize)

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val parameterSpec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, parameterSpec)
        cipher.updateAAD(FIELD_AAD)
        return cipher.doFinal(cipherText)
    }

    fun decryptOrNull(encryptedBase64: String, key: ByteArray): String? {
        return try {
            String(decrypt(encryptedBase64, key), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    // ---------- B2-CRYPTO-09 (phase-107): per-record field AAD binding ----------

    /**
     * Binds the ciphertext to its exact storage context: table + record id +
     * field name (e.g. `pages|41f6…|title`). Two ciphertexts from different
     * records (or different columns) can never authenticate in each other's
     * slot, so a transplant attack fails the GCM tag instead of rendering.
     */
    fun fieldAad(table: String, recordId: String, fieldName: String): ByteArray =
        (FIELD_AAD_V2_PREFIX + table + "|" + recordId + "|" + fieldName).toByteArray(Charsets.UTF_8)

    /**
     * Encrypts a field payload under its per-record AAD. Same wire format as
     * [encrypt] ([PAYLOAD_VERSION] + 12-byte IV + GCM ciphertext+tag), so the
     * deterministic format checks in [decryptField] apply unchanged.
     */
    fun encryptField(data: ByteArray, key: ByteArray, table: String, recordId: String, fieldName: String): String {
        return base64Encode(encryptAad(data, key, fieldAad(table, recordId, fieldName)))
    }

    /**
     * Decrypts a field payload produced by [encryptField].
     *
     * The per-record AAD is tried first. On an [javax.crypto.AEADBadTagException]
     * ONLY, the pre-phase-107 global [FIELD_AAD] is retried so rows encrypted
     * before this phase (and their ciphertext is legally in its own record) still
     * decrypt — the migration reader for `migrateFieldRecordAad`. A transplanted
     * NEW-format ciphertext fails both attempts (its tag covers the per-record
     * AAD), so the fallback never rescues a relocation; and after the migration
     * pass completes no legacy rows remain for it to read. Malformed/unversioned
     * payloads are rejected before any decrypt (same policy as [decrypt]).
     */
    fun decryptField(encryptedBase64: String, key: ByteArray, table: String, recordId: String, fieldName: String): ByteArray {
        val combined = base64Decode(encryptedBase64)
        if (combined.size < GCM_IV_LENGTH + 1) throw IllegalArgumentException("Invalid encrypted payload")
        if (combined[0] != PAYLOAD_VERSION) {
            throw IllegalArgumentException("Unsupported payload format: missing version marker")
        }
        return try {
            decryptCoreAad(combined, key, fieldAad(table, recordId, fieldName), offset = 1)
        } catch (e: javax.crypto.AEADBadTagException) {
            decryptCoreAad(combined, key, FIELD_AAD, offset = 1)
        }
    }

    /**
     * Like [decryptField] but returns null on any decrypt failure instead of
     * throwing — the per-record analogue of [decryptOrNull].
     */
    fun decryptFieldOrNull(encryptedBase64: String, key: ByteArray, table: String, recordId: String, fieldName: String): String? {
        return try {
            String(decryptField(encryptedBase64, key, table, recordId, fieldName), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * True iff the payload decrypts under its OWN per-record AAD (i.e. it is not
     * a legacy global-[FIELD_AAD] row). Used by [com.authorss81.noteflow.data.repository.NoteRepository.migrateFieldRecordAad]
     * to decide whether a row still needs re-encryption; does NOT consult the
     * legacy fallback, so a legacy row returns false even though [decryptField]
     * can read it.
     */
    fun isFieldBoundToRecord(encryptedBase64: String, key: ByteArray, table: String, recordId: String, fieldName: String): Boolean {
        return try {
            val combined = base64Decode(encryptedBase64)
            if (combined.size < GCM_IV_LENGTH + 1) return false
            if (combined[0] != PAYLOAD_VERSION) return false
            decryptCoreAad(combined, key, fieldAad(table, recordId, fieldName), offset = 1)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- B2-CRYPTO-10 (phase-108): blank fields are real AEAD payloads ----------

    /**
     * Structural "is this ciphertext?" check.
     *
     * `internal` (phase-108 review): it decodes Base64 leniently and answerable
     * ONLY from payload shape, so garbage input can classify as a "payload" (any
     * ≥13-byte Base64 blob whose first byte is the version marker). It must never
     * be used as a standalone public gate — pair it with an authenticated decrypt
     * ([isFieldEncrypted]) which is what `shouldReencryptField` does.
     *
     * Decided ONLY by payload structure — the Base64 decodes to at least
     * [GCM_IV_LENGTH] + 1 bytes (version byte + 12-byte IV is the minimum a
     * versioned payload can span) and carries the [PAYLOAD_VERSION] marker — and
     * NEVER by content blank-ness. A blank plaintext stored correctly by this
     * app is a valid 29-byte AEAD payload (`[1][12-byte IV][16-byte GCM tag]`,
     * AES-GCM of empty plaintext), so it must classify as encrypted. A raw `""`
     * (a pre-B2-CRYPTO-10 write, or a column whose ciphertext was zeroed) is not
     * a payload at all and must classify as NOT encrypted.
     */
    internal fun isEncryptedPayload(encryptedBase64: String): Boolean {
        if (encryptedBase64.isBlank()) return false
        val combined = try {
            base64Decode(encryptedBase64)
        } catch (e: Exception) {
            return false
        }
        return combined.size >= GCM_IV_LENGTH + 1 && combined[0] == PAYLOAD_VERSION
    }

    /**
     * Field-layer classifier (B2-CRYPTO-10 / phase-108).
     *
     * `internal` (phase-108 review): answers a real question (is this value a
     * genuine authenticated field ciphertext, decryptable under its own record
     * AAD?) and must not be consulted in isolation for the opposite question
     * ("is this plaintext?") — a decrypt failure is NOT proof of plaintext.
     *
     * Replaces the old probe that returned `true` for any blank value ("nothing
     * to encrypt"), which let blank columns masquerade as already-encrypted and
     * made [com.authorss81.noteflow.data.repository.NoteRepository.reencryptPlaintextFields]
     * permanently skip them. "Is this value an encrypted field column?" is answered
     * by payload structure first (a raw blank is NOT a payload), then an
     * authenticated decrypt under the record field AAD:
     *  - a raw blank `""` → false, so the sweep re-stamps it as a tagged payload;
     *  - a correctly-stored blank → true, it round-trips to "" and fails
     *    authentically if the ciphertext column is zeroed afterwards;
     *  - a malformed/transplanted/garbage value → false, so it is never treated
     *    as "encrypted and fine".
     */
    internal fun isFieldEncrypted(value: String, key: ByteArray, table: String, recordId: String, fieldName: String): Boolean {
        if (!isEncryptedPayload(value)) return false
        return try {
            decryptField(value, key, table, recordId, fieldName)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Re-encryption sweep gate (B2-CRYPTO-10 phase-108 review fix, finding #2).
     *
     * True ONLY for genuine plaintext the sweep should stamp as a tagged AEAD
     * payload (including a raw blank `""`). False in both directions that would
     * falsify data at rest:
     *  - an already-encrypted value (decryptable under its own AAD) is skipped —
     *    re-running the sweep must not double-encrypt (idempotence);
     *  - a value that is STRUCTURALLY a payload but fails authentication —
     *    corrupt or transplanted ciphertext — is skipped and left byte-for-byte
     *    intact, so the original plaintext-bytes-are-gone state is never buried
     *    under a second "encryption" that pretends the garbage is real content.
     *
     * Callers must pass the record context from the row being swept (never a
     * value under a wrong record/field AAD) — the whole point of the AAD binding.
     */
    internal fun shouldReencryptField(value: String, key: ByteArray, table: String, recordId: String, fieldName: String): Boolean {
        // Structurally-encrypted value: never treated as plaintext. Whether it
        // decrypts under its own AAD (already stamped — skip for idempotence) or
        // fails auth (corrupt/transplanted — never bury the original bytes by
        // re-encrypting garbage as if it were content), it is NOT a value for the
        // sweep to re-stamp. Anything that is not structurally a payload is
        // genuine plaintext, including a raw blank "".
        return !isEncryptedPayload(value)
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
