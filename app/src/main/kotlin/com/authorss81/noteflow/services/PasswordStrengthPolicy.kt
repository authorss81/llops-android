package com.authorss81.noteflow.services

import java.text.BreakIterator

/**
 * B1-CRYPTO-04 (phase-63) + B1-PLAT-8 (phase-90): the single source of truth
 * for how strong a NEW master password must be at
 * `setMasterPassword` / `changeMasterPassword`.
 *
 * Threat model (the findings): the salt + wrapped DEK (now the single `MPB1|...`
 * blob, B1-CRYPTO-03) and the SQLCipher `noteflow.sqlite` sit on the normal data
 * partition. An attacker who obtains a data copy (cloud/manual backup, forensic
 * extraction, shared device image, rooted emulator restore) cracks the wrapped
 * DEK OFFLINE with a GPU/FPGA PBKDF2-SHA-256 rig — the only on-device throttle
 * is the UI lockout, which by definition never fires for an offline attacker.
 * A 6-7 character lowercase/numeric password falls in hours-to-days; even 8-9
 * chars of a common word (B1-PLAT-8's `password1` / `12345678` class) is
 * wordlist-exhausted in minutes. The on-device 5-fail lockout
 * (`NoteflowViewModel` failed-attempt counters) is UI-only: it cannot protect a
 * copied vault, and restoring the prefs + DB to a rooted emulator defeats it
 * entirely — offline resistance is provided ONLY by password entropy.
 *
 * This policy therefore enforces a *stronger minimum* at password SET and
 * ROTATE time (never at verify/unlock — an existing weaker vault must keep
 * unlocking and rotating):
 *  1. length ≥ [MIN_STRENGTH_GRAPHEMES] (10 perceived characters), ≤
 *     [EncryptionService.MAX_PASSWORD_GRAPHEMES] (128) — measured on the
 *     NFKC-normalized password in grapheme clusters, exactly the byte string
 *     `EncryptionService.deriveKey` hashes (B2-CRYPTO-07), so normalization can
 *     never silently shrink/reject a stored form;
 *  2. not a sequential / keyboard-row / repeated-char pattern (rejects
 *     `1234567890`, `abcdefghij`, `qwerty`, `aaaaaaaa`, `1212121212` — patterns
 *     a GPU wordlist exhausts first);
 *  3. not a widely-leaked password word (B1-PLAT-8), whether bare (`password`,
 *     `sunshine`) or thinly decorated with a digit/symbol prefix/suffix
 *     (`monkey1234`, `2026sunshine`, `letmein!`) — the exact keyspace an
 *     offline cracker feeds to a wordlist first. Decoration detection is
 *     STRUCTURAL (only non-letter padding around a contiguous base word is
 *     rejected) so genuine passphrases that merely contain common words keep
 *     passing;
 *  4. character-class diversity — a short password must mix at least 3 of the 4
 *     classes (upper / lower / digit / symbol); long passphrases (≥ 12) are
 *     accepted on length alone so `correct horse battery staple`-style inputs
 *     pass without the 3-class burden.
 *
 * EVALUATION ORDER (phase-90 review fix): the common-word check (3) runs
 * immediately after the 128-grapheme cap and BEFORE the length floor (1) and the
 * sequential/repeated checks (2). A bare short word (`password`) and the classic
 * `password123`/`123password` keyspace therefore report `COMMON_PASSWORD` — the
 * reviewer-flagged phase-90 gap where those inputs got a misleading
 * `TOO_SHORT`/`SEQUENTIAL` instead. The order never flips accept/reject (the
 * check only adds rejection); it only makes the reason string accurate.
 *
 * VERIFY-TIME LOCKOUT IS DOCUMENTED UI-ONLY, BY DESIGN: this policy and the
 * lockout counters in `NoteflowViewModel.verifyMasterPassword` only throttle
 * human attempts through the UI. offline brute force on a copied vault is only
 * mitigated by password entropy, NOT by the on-device lockout (B1-PLAT-8). The
 * vault's real protection against an offline data copy is the password itself
 * (plus the per-device DEK placement from B1-CRYPTO-02). A TEE-bound attempt
 * gate or an Argon2id KDF would raise the offline cost; both are tracked as
 * follow-ups (see `workspace/phase-63/REPORT.md`) and are deliberately NOT
 * introduced here (no new dependencies, no new platform requirements — this
 * policy runs on the API 26+ floor unchanged).
 *
 * Pure JVM (no Android imports) so the decision table is unit-testable in
 * `app/src/test`. Wired as the authoritative gate in
 * `NoteflowViewModel.setMasterPassword` / `changeMasterPassword` and surfaced
 * with its human-readable [PasswordStrengthVerdict.message] by the set/change
 * dialogs in `Dialogs.kt`. The backup-password gate
 * ([com.authorss81.noteflow.services.BackupPasswordPolicy], B2-CRYPTO-04)
 * delegates here so a backup can never be protected by a weaker password than
 * its vault.
 */
internal enum class PasswordStrengthVerdict(val accepted: Boolean, val message: String) {
    ACCEPTED(true, ""),
    TOO_SHORT(
        false,
        "Password must be at least ${PasswordStrengthPolicy.MIN_STRENGTH_GRAPHEMES} characters"
    ),
    TOO_LONG(
        false,
        "Password must be at most ${EncryptionService.MAX_PASSWORD_GRAPHEMES} characters"
    ),
    SEQUENTIAL(
        false,
        "Password must not be a predictable pattern (e.g. 1234567890, qwerty, abcdefgh)"
    ),
    COMMON_PASSWORD(
        false,
        "Password is too common — don't use a widely used word (with or without digits/symbols around it)"
    ),
    WEAK(
        false,
        "Password must use at least 3 distinct characters"
    ),
    LOW_DIVERSITY(
        false,
        "Password is short — use at least 3 of: uppercase, lowercase, digit, symbol"
    ),
}

internal object PasswordStrengthPolicy {

    /**
     * Minimum perceived length for a NEW master password (graphemes, NFKC-normalized).
     * B1-CRYPTO-04 raised it from the pre-fix 6 to 8; B1-PLAT-8 (phase-90) raises
     * it to 10 — a 6-9 char lowercase/numeric/common-word password is still
     * wordlist-exhaustible by an offline GPU rig in minutes-hours. The floor is
     * deliberately only at SET/ROTATE time (never at unlock), so pre-existing
     * weaker vaults keep opening and can be rotated up.
     */
    const val MIN_STRENGTH_GRAPHEMES = 10

    /** Passwords of at least this many graphemes are accepted on length alone. */
    private const val PASSPHRASE_GRAPHEMES = 12

    /** Character classes a short password must diversify across. */
    private const val REQUIRED_CLASSES_SHORT = 3

    /** At least this many distinct graphemes are required — kills `aaaa…`/`abab…` keyspaces. */
    private const val MIN_DISTINCT_GRAPHEMES = 3

    private val keyboardPatterns = listOf(
        "qwertyuiop", "asdfghjkl", "zxcvbnm",
        "qwerty", "asdf", "qaz", "wsx", "edc", "qazwsx", "qazwsxedc",
        "1234567890", "0987654321", "0123456789", "9876543210",
    )

    /**
     * B1-PLAT-8 (phase-90): widely-leaked password words an offline GPU cracker
     * feeds to a wordlist first. Each entry is rejected whole (`password`,
     * `sunshine`) or in the classic prefix/suffix keyspace (`password123`,
     * `123password`, `password!`). Only HIGH-PRECISION bases are listed (top
     * breach rolls), so genuine passphrases that merely contain an ordinary word
     * are never blocked. Phase-90 review fix: this check runs BEFORE the length
     * floor and the sequential check (see [evaluate]), so whole words — even
     * sub-10-grapheme ones — report `COMMON_PASSWORD`, and so do decorated
     * variants whose pad happens to be a predictable run (`password123`).
     */
    private val commonPasswordBases = listOf(
        "password", "passw0rd",
        "letmein", "welcome", "admin", "monkey", "dragon",
        "football", "baseball", "iloveyou", "sunshine", "princess",
        "master", "login", "secret", "shadow", "trustno1", "superman",
        "hunter", "michael",
    )

    fun evaluate(raw: String): PasswordStrengthVerdict {
        val normalized = EncryptionService.normalizePassword(raw)
        val graphemes = EncryptionService.normalizedGraphemeCount(normalized)
        if (graphemes > EncryptionService.MAX_PASSWORD_GRAPHEMES) return PasswordStrengthVerdict.TOO_LONG
        // B1-PLAT-8 review fix (phase-90): common-word detection runs BEFORE the
        // length floor and the pattern checks. A bare word (`password`) or its
        // classic digit/symbol-decorated keyspace (`password123`, `123password`)
        // is the exact wordlist-first input an offline GPU rig feeds, so it must
        // report "too common" — never a misleading "too short"/"predictable
        // pattern". This ordering only ever changes the reason string, never the
        // accept/reject outcome (the check only adds rejection).
        if (isCommonPasswordVariant(normalized)) return PasswordStrengthVerdict.COMMON_PASSWORD
        if (graphemes < MIN_STRENGTH_GRAPHEMES) return PasswordStrengthVerdict.TOO_SHORT
        if (containsSequentialPattern(normalized)) return PasswordStrengthVerdict.SEQUENTIAL
        if (distinctGraphemeCount(normalized) < MIN_DISTINCT_GRAPHEMES) return PasswordStrengthVerdict.WEAK
        if (graphemes < PASSPHRASE_GRAPHEMES && classCount(normalized) < REQUIRED_CLASSES_SHORT) {
            return PasswordStrengthVerdict.LOW_DIVERSITY
        }
        return PasswordStrengthVerdict.ACCEPTED
    }

    /**
     * B1-PLAT-8 (phase-90): true when the normalized password is a widely-leaked
     * password word or a thinly decorated variant of one. The base must appear
     * CONTIGUOUSLY (lowercase compared), and everything AROUND it must be
     * non-letter padding (digits/symbols/whitespace — the classic `word123`,
     * `2024word`, `word!` keyspace). A base merely embedded among real letters
     * (a genuine passphrase like `my monkey friend` or `sunshine on parade`)
     * is NOT rejected, so the length-alone passphrase exception keeps working.
     * Called first in [evaluate] (phase-90 review fix) so bare words do not fall
     * through to the misleading `TOO_SHORT`/`SEQUENTIAL` verdicts.
     */
    private fun isCommonPasswordVariant(normalized: String): Boolean {
        val lower = normalized.lowercase(java.util.Locale.ROOT)
        for (base in commonPasswordBases) {
            val idx = lower.indexOf(base)
            if (idx < 0) continue
            val before = lower.substring(0, idx)
            val after = lower.substring(idx + base.length)
            if (before.all { !it.isLetter() } && after.all { !it.isLetter() }) return true
        }
        return false
    }

    /**
     * True if the NFKC-normalized password contains a predictable typing pattern:
     * a known keyboard row substring, or a monotonic run of 4+ consecutive ASCII
     * letters/digits (in either direction — `abcd`, `1234`, `9876`, `zyxw`).
     * Non-ASCII characters reset the run (their code-point neighbors are not
     * predictable). The 4-char threshold keeps real words (`fed`, `cab`) from
     * being falsely flagged while catching every practical sequential password.
     */
    private fun containsSequentialPattern(normalized: String): Boolean {
        val lower = normalized.lowercase(java.util.Locale.ROOT)
        if (keyboardPatterns.any { lower.contains(it) }) return true
        var run = 1
        var lastDelta = 0
        for (i in 1 until lower.length) {
            val prev = lower[i - 1]
            val cur = lower[i]
            if (isAsciiAlphaNumeric(prev) && isAsciiAlphaNumeric(cur)) {
                val delta = cur.code - prev.code
                if (delta == lastDelta && delta != 0 && delta in -1..1) {
                    run++
                    if (run >= 3) return true
                } else {
                    run = 1
                    lastDelta = delta
                }
            } else {
                run = 1
                lastDelta = 0
            }
        }
        return false
    }

    private fun isAsciiAlphaNumeric(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9')

    private fun distinctGraphemeCount(normalized: String): Int =
        graphemesOf(normalized).toSet().size

    private fun classCount(normalized: String): Int {
        var upper = false
        var lower = false
        var digit = false
        var symbol = false
        for (g in graphemesOf(normalized)) {
            val cp = g.codePointAt(0)
            when {
                Character.isUpperCase(cp) -> upper = true
                Character.isLowerCase(cp) -> lower = true
                Character.isDigit(cp) || Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER.toInt() -> digit = true
                else -> symbol = true
            }
        }
        return (if (upper) 1 else 0) + (if (lower) 1 else 0) +
            (if (digit) 1 else 0) + (if (symbol) 1 else 0)
    }

    private fun graphemesOf(normalized: String): List<String> {
        val result = mutableListOf<String>()
        val it = BreakIterator.getCharacterInstance()
        it.setText(normalized)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            result.add(normalized.substring(start, end))
            start = end
            end = it.next()
        }
        return result
    }
}
