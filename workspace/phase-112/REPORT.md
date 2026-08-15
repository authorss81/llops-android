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

## Review follow-up — fixes for review findings (committed after `cdb2b58`)

Three review findings were fixed in a follow-up change to
`SecurityCryptoAbsenceTest.kt` + this report:

- **Dependency-graph pin added** (review finding 4): new 5th test
  `test runtime classpath carries no security-crypto or tink artifacts` — the
  unit-test runtime classpath carries every `:app` implementation dependency,
  so the test asserts `androidx.security.crypto.EncryptedSharedPreferences` and
  `com.google.crypto.tink.KeysetHandle` are NOT resolvable and that no
  security-crypto/Tink jar entry is on `java.class.path`. This catches a
  reintroduction the source scan would miss (e.g. pulled in by a module that
  postdates the scan). Unit tests cannot read the final APK, so full APK-level
  absence stays a build-time/CI check (`unzip -l app-debug.apk | grep -c
  androidx/security` = 0 in the verification run below).
- **Dynamic module discovery** (review finding 5): `source tree has zero
  references to the deprecated crypto API` now discovers every module's
  `src/main` from the repo tree (no hardcoded module list) and additionally
  scans every `*.gradle.kts`/`*.toml` under the repo root (pruning `.git`,
  `.gradle`, `.kotlin`, `build/`, `logs/`, `docs/`, `workspace/`, `gradle/`).
  Future modules and their build files are covered without editing the test.
- **Narrowed catalog pin** (review finding 6): `version catalog carries no
  securityCrypto version or library` now bans only the REMOVED artifact
  (`securityCrypto`, `security-crypto`) instead of blanket-banning the
  `androidx.security` group string, so a future maintained androidx.security
  artifact remains adoptable (per the finding's AndroidKeyStore+Tink guidance).

Suite total with the follow-up: **702 tests** (697 pre-existing + 5 new).

### Pre-existing flake observed (documented, unrelated, NOT fixed here)

While re-running `gradle testDebugUnitTest`, the pre-existing
`PluginUpdateEngineTest` intermittently failed 1 of its 10 testcases — the
affected testcase varied between runs (`a hash mismatch on the downloaded
artifact is never applied` one run, `rollback restores the recorded previous
verified version` another). It is unrelated to phase-112:

- **Proven pre-existing and intermittent:** the class also failed when run
  against the UNMODIFIED baseline (phase-112's own test changes stashed); every
  ISOLATED re-run of the class passes (`BUILD SUCCESSFUL`); and full-suite runs
  flip between green and 1-of-702 failing with AND without these changes (both
  states observed repeatedly). A fully green run of the complete suite
  (702 tests, 0 failures) with these fixes is recorded immediately below.
- **Root-cause (for phase-27):** `TestArtifactBuilder` delegates signing to
  external `keytool`/`jarsigner` subprocesses (`TestDownloadablePlugin.kt:92-113,
  156-173`) and writes jars with wall-clock `ZipEntry` timestamps
  (`TestDownloadablePlugin.kt:175-192`), so v1/v2 artifacts are not guaranteed
  byte-distinct across runs — a digest-mismatch/rollback expectation can then
  see an identical artifact and flip the assertion. Concurrent-suite wall time
  shifts that window.
- Phase scope (B2-DEPS-02) forbids fixing other findings here; this is tracked
  for the Phase 27 bug-fix queue. No production code is affected.

## Out-of-scope / notes

- Only the unused `security-crypto` dependency is removed. The bloaty right-place
  concerns of other Batch-2 findings (Gradle dependency verification,
  unpinned Gradle distribution — B2-DEPS-01/03, jsoup 1.17.2 CVE-fix lag) are
  separate phases with their own prompts; not touched here.
- If encrypted prefs are ever needed, the finding's guidance is to use
  AndroidKeyStore + Tink directly (or `datastore-tink`) — deliberately NOT adopting
  the deprecated library. `WebDavCredentialStore` is the in-tree precedent.
- `.github/workflows/` untouched; no dependency added.