package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for B2-CRYPTO-07 (phase-113): Unicode normalization of
 * master/backup passwords.
 *
 * The fix: every password derivation now runs through a SINGLE
 * [EncryptionService.deriveKey] path that NFKC-normalizes first, so a password
 * set once with composed characters (NFC `é` U+00E9) and later typed with the
 * decomposed form (NFD `e` + U+0301) on another keyboard/IME derives the same
 * key instead of permanently locking the vault. Password length is enforced on
 * that normalized form in extended grapheme clusters (perceived characters),
 * never UTF-16 code units. For vaults/backups created BEFORE the fix with a
 * non-NFKC byte sequence, [EncryptionService.passwordCandidates] still offers
 * the raw legacy bytes so the fix itself can never lock an existing vault.
 */
class UnicodeNormalizationPasswordTest {

    // NFC composed "é"; NFD "e" + combining acute — canonically equivalent.
    private val nfcAccent = "caf\u00e9"
    private val nfdAccent = "cafe\u0301"

    @Test
    fun `NFC password and equivalent NFD password derive the identical key`() {
        val salt = EncryptionService.generateSalt()
        assertArrayEquals(
            "NFC set must derive the same key as NFD typed at verify",
            EncryptionService.deriveKey(nfcAccent, salt),
            EncryptionService.deriveKey(nfdAccent, salt)
        )
    }

    @Test
    fun `password set as NFC unlocks when the equivalent NFD is typed at verify`() {
        val salt = EncryptionService.generateSalt()
        val dek = EncryptionService.generateDek()
        val setAsNfc = EncryptionService.deriveKey(nfcAccent, salt)
        val wrapped = EncryptionService.encrypt(dek, setAsNfc)
        val verifyAsNfd = EncryptionService.deriveKey(nfdAccent, salt)
        assertArrayEquals(dek, EncryptionService.decrypt(wrapped, verifyAsNfd))
    }

    @Test
    fun `full-width and half-width letters collapse under the single NFKC path`() {
        val salt = EncryptionService.generateSalt()
        // ＡＰ (U+FF21 U+FF30) collapses to "AP" only under NFKC.
        assertArrayEquals(
            EncryptionService.deriveKey("\uff21\uff30-Pass", salt),
            EncryptionService.deriveKey("AP-Pass", salt)
        )
    }

    @Test
    fun `normalizePassword is idempotent and canonicalizes to NFC`() {
        assertEquals(nfcAccent, EncryptionService.normalizePassword(nfdAccent))
        assertEquals(nfcAccent, EncryptionService.normalizePassword(nfcAccent))
        for (raw in listOf("", "plain", nfcAccent, nfdAccent, "\uff21\uff2f")) {
            assertEquals(
                "normalize must be idempotent for '$raw'",
                EncryptionService.normalizePassword(raw),
                EncryptionService.normalizePassword(EncryptionService.normalizePassword(raw))
            )
        }
    }

    @Test
    fun `password length is counted in graphemes not UTF-16 code units`() {
        // 8 base letters each followed by a combining accent = 8 graphemes,
        // 16 code units.
        val composed = "a\u0301".repeat(8)
        assertEquals(16, composed.length)
        assertEquals(8, EncryptionService.normalizedGraphemeCount(composed))

        // 6 single non-BMP emoji = 6 graphemes, 12 code units.
        val emoji = "\ud83d\ude00".repeat(6)
        assertEquals(12, emoji.length)
        assertEquals(6, EncryptionService.normalizedGraphemeCount(emoji))
    }

    @Test
    fun `length gate accepts grapheme-valid and rejects over-length passwords`() {
        // 100 emoji = 100 graphemes / 200 code units. A code-unit cap would
        // wrongly reject this; the grapheme cap must ACCEPT it.
        val hundredEmoji = "\ud83d\ude00".repeat(100)
        assertTrue(EncryptionService.isValidPasswordLength(hundredEmoji))
        // 200 ASCII letters = 200 graphemes — above the 128-grapheme cap.
        assertFalse(EncryptionService.isValidPasswordLength("a".repeat(200)))
        // 5 graphemes — below the 6-char minimum.
        assertFalse(EncryptionService.isValidPasswordLength("abcde"))
        // 6 graphemes of combining sequences — valid even at 12 code units.
        assertTrue(EncryptionService.isValidPasswordLength("a\u0301".repeat(6)))
    }

    @Test
    fun `passwordCandidates keeps a single normalized form when input is already normalized`() {
        assertEquals(listOf(nfcAccent), EncryptionService.passwordCandidates(nfcAccent))
        assertEquals(listOf("ascii-pass"), EncryptionService.passwordCandidates("ascii-pass"))
    }

    @Test
    fun `passwordCandidates appends the raw form as a legacy reader for non-NFKC input`() {
        val candidates = EncryptionService.passwordCandidates(nfdAccent)
        assertEquals(2, candidates.size)
        assertEquals(nfcAccent, candidates[0])
        assertEquals(nfdAccent, candidates[1])
    }

    @Test
    fun `legacy raw derivation still opens a pre-fix non-normalized vault`() {
        val salt = EncryptionService.generateSalt()
        val dek = EncryptionService.generateDek()
        // PRE-fix vault: the wrapped DEK was derived from the RAW NFD bytes.
        val preFixKey = EncryptionService.deriveKeyLegacyRaw(nfdAccent, salt)
        val wrapped = EncryptionService.encrypt(dek, preFixKey)

        // The normalized path alone cannot open it — the regression the fix
        // would otherwise introduce for a pre-fix NFD password.
        assertFalse(
            "normalized key must differ from the legacy raw key",
            EncryptionService.deriveKey(nfdAccent, salt).contentEquals(preFixKey)
        )
        // ...but deriveKeyCandidates reaches the raw form second and unlocks it.
        val candidateKeys = EncryptionService.deriveKeyCandidates("cafe\u0301", salt)
        assertEquals(2, candidateKeys.size)
        assertArrayEquals(dek, EncryptionService.decrypt(wrapped, candidateKeys[1]))
        candidateKeys[0].fill(0.toByte())
        candidateKeys[1].fill(0.toByte())
        preFixKey.fill(0.toByte())
    }
}