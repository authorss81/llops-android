# Phase 112 — B2-DEPS-02 (INFO): `androidx.security:security-crypto` 1.1.0-alpha06 — unmaintained alpha pulled into the base APK but never used

Finding fixed: `B2-DEPS-02` — the unmaintained 3-year-old alpha
`androidx.security:security-crypto:1.1.0-alpha06` was declared in the Gradle
version catalog and pulled into the base APK, yet grep of `app/src` for
`androidx.security.crypto` / `EncryptedSharedPreferences` / `MasterKeys` found
ZERO usages. Not exploitable today; the forward-looking risk was that any future
wiring of `EncryptedSharedPreferences` would reintroduce Tink keyset-manager
failure classes (`AEADBadTagException` on backup-restore/key-loss) with zero
future security maintenance, since Google deprecated the whole API.

NOTE: CVE-2024-37150 is a Deno npm-registry bug, NOT an androidx.security CVE —
it is deliberately not cited anywhere in this change.

## What changed (file:line)

Pure dependency REMOVAL — no new dependency, no code behavior change, no DB
schema change.

| File | Before | After |
|------|--------|-------|
| `gradle/libs.versions.toml:12` | `securityCrypto = "1.1.0-alpha06"` | removed |
| `gradle/libs.versions.toml:56` (now 55) | `security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }` | removed |
| `app/build.gradle.kts:164` (now 163) | `implementation(libs.security.crypto)` | removed |

- `app/build.gradle.kts:164` — deleted `implementation(libs.security.crypto)` from
  the "Coil & Utilities" dependency block. The compile tree (`gradle assembleDebug`
  below) proves the accessor was unused: the APK no longer contains a single
  `androidx/security` class.
- `gradle/libs.versions.toml:12` — deleted the `securityCrypto` version.
- `gradle/libs.versions.toml:55` — deleted the `security-crypto` library entry.
- No `settings.gradle.kts`, `.github/workflows/`, manifest or source change.

## Grep verification (before)

```
$ grep -rn "security.crypto\|EncryptedSharedPreferences\|MasterKeys" app/src plugins plugin-sdk
(no matches)   # → the dependency was dead weight
```

## Grep verification (after)

```
$ grep -rn "security.crypto\|security-crypto\|securityCrypto\|androidx.security.crypto" \
     --include="*.kts" --include="*.toml" --include="*.kt" --include="*.java" --include="*.xml" .
app/src/test/.../SecurityCryptoAbsenceTest.kt   # the regression test itself (asserting absence)
```

The only remaining matches repo-wide are inside the new regression test that
pins the absence of the library.

## OS/API floor (AGENTS.md hardware reality)

This is a dependency removal, not a feature. No new Android API is introduced and
none is dropped. Google's supported replacement (`SharedPreferences`/`KeyGenerator`
+ AndroidKeyStore) is API-level independent and the app's own encrypted-prefs lane
(`WebDavCredentialStore`) already uses the AndroidKeyStore directly — valid on the
API 26+ floor with no fallback or notice required.

## Unit tests

`app/src/test/java/com/authorss81/noteflow/SecurityCryptoAbsenceTest.kt`
(new, 4 tests, pure-JVM, no network):

1. `app build file no longer pulls security-crypto` — `app/build.gradle.kts` has no
   `security.crypto` / `security-crypto` reference.
2. `version catalog carries no securityCrypto version or library` —
   `gradle/libs.versions.toml` has no `securityCrypto` version, no `security-crypto`
   library and no `androidx.security` group pin.
3. `source tree has zero references to the deprecated crypto API` — walks all
   production source sets (`app/src/main`, `plugin-sdk/src/main`,
   `plugins/llm/src/main`) asserting zero `androidx.security.crypto`,
   `androidx.security:security`, `EncryptedSharedPreferences` or `MasterKeys`
   references. Excludes `*/test/*` so the test's own KDoc/assert strings do not
   trip the scan.
4. `encrypted prefs lane stays exclusively AndroidKeyStore based` — pins that
   `WebDavCredentialStore` (the recommended AndroidKeyStore replacement lane)
   still exists, uses `AndroidKeyStore`, and does not adopt the deprecated library.

Total suite: **701 tests, 0 skipped, 0 failures, 0 errors** (697 pre-existing
+ 4 new).

## Verification output

1. `gradle :app:testDebugUnitTest --tests SecurityCryptoAbsenceTest` → BUILD
   SUCCESSFUL, 4 tests, 0 failures.
2. `gradle testDebugUnitTest` (full multi-module: plugin-sdk, plugins/llm, app)
   → BUILD SUCCESSFUL in 41s. Aggregated app results: **701 tests, 0 failures,
   0 errors**.
3. `gradle assembleDebug` → BUILD SUCCESSFUL in 2m 2s (90 tasks), producing
   `app/build/outputs/apk/debug/app-debug.apk`.
4. APK content check (compile-tree proof the removal is effective):
   `unzip -l app-debug.apk | grep -c "androidx/security"` → **0** — the
   security-crypto (and its transitive Tink) classes no longer ship at all.

## Checksum / secrets handling

None affected. The change removes an unused dependency; no key, password, salt,
wrapped DEK or decrypted-note data is touched, logged or newly persisted.
`allowBackup="false"`, `ClipboardGuard` and `FLAG_SECURE` are untouched. No DB
schema change / migration (no schema migration note required).

## Out-of-scope / notes

- Only the unused `security-crypto` dependency is removed. The bloaty right-place
  concerns of other Batch-2 findings (Gradle dependency verification,
  unpinned Gradle distribution — B2-DEPS-01/03, jsoup 1.17.2 CVE-fix lag) are
  separate phases with their own prompts; not touched here.
- If encrypted prefs are ever needed, the finding's guidance is to use
  AndroidKeyStore + Tink directly (or `datastore-tink`) — deliberately NOT adopting
  the deprecated library. `WebDavCredentialStore` is the in-tree precedent.
- `.github/workflows/` untouched; no dependency added.