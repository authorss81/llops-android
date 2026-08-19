# Release Engineering Guide — InkFlow / NoteFlow

How to build a **signable, publishable** release APK and (eventually) upload it
to the Play Store / a store front. Read this before releasing.

## Build gates

All three must pass locally or in CI before shipping:

```bash
gradle testDebugUnitTest     # JVM unit tests (no device needed)
gradle assembleDebug         # debug APK (installable, not for release)
gradle assembleRelease       # shrink + obfuscate + sign
```

- The project has NO gradle wrapper jar — use the system `gradle` (CI uses
  Gradle 8.13). `compileSdk = 36`, `minSdk = 26`, `targetSdk = 36`.
- Release build: `isMinifyEnabled = true` (R8), ProGuard rules in
  `app/proguard-rules.pro` (Room/Gson/Ink keep-rules).
- Baseline profiles are intentionally NOT wired (deferred; see ROADMAP 21.3/32.9).

## Release signing — fail-closed (B1-PLAT-1)

The `release` build type is bound **only** to the `releaseConfig` signing
config, which reads its identity **exclusively** from build-environment
variables:

| Variable | Meaning |
|---|---|
| `KEYSTORE_FILE` | path to an existing release keystore (`.jks`/`.keystore`) |
| `KEYSTORE_PASSWORD` | keystore (store) password |
| `KEY_ALIAS` | alias of the signing key inside the keystore |
| `KEY_PASSWORD` | password of the signing key |

**There is no fallback.** If `KEYSTORE_FILE` is unset (or points at a missing
file), the `releaseConfig` has no `storeFile` and `gradle assembleRelease`
**FAILS** at AGP's `:app:validateSigningRelease` task with `Keystore file not
set for signing config 'releaseConfig'` (or a similar `packageRelease` signing
error). A plain `gradle assembleRelease` with no keystore **cannot produce an
APK at all** — the old `debug.keystore` / `debug.keystore.base64` fallbacks and
the "auto-generated debug keystore" release path were **removed** in phase-57.

> **Hard rule: never distribute a debug-signed build.** Debug-signing a release
> uses the publicly known Android debug key (password `android`) — anyone who
> obtains that keystore can sign a same-signature malicious update that Android
> installs with no signature warning. Release builds that fail the
> `validateSigningRelease` gate are **not** a workaround: they are the build
> loudly refusing to ship an unverifiable artifact. `gradle assembleDebug`
> keeps using AGP's auto-generated debug keystore and is unaffected.

### Wire a real release keystore (one-time, per maintainer)

```bash
# 1. Generate a real keystore. 10,000+ iterations and a STRONG password.
keytool -genkey -v \
  -keystore ./release.keystore \
  -alias inkflow \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storepass '<FORT>_PASSWORD' \
  -dname "CN=InkFlow, OU=Mobile, O=YourOrg, L=City, ST=State, C=US"

# 2. Never commit the keystore. Delete it locally after upload if it is only
#    used from CI, or store it encrypted in your company vault.
```

### Build the release with the keystore

```bash
KEYSTORE_FILE=./release.keystore \
KEYSTORE_PASSWORD='<keystore password>' \
KEY_ALIAS=inkflow \
KEY_PASSWORD='<key password>' \
gradle assembleRelease
```

Any of the four variables missing, or `KEYSTORE_FILE` pointing at a missing
file, is an immediate loud `assembleRelease` failure (B1-PLAT-1).

### Wire it into GitHub Actions

Add repo secrets in Settings → Secrets and variables → Actions:

| Secret | Value |
|---|---|
| `KEYSTORE_FILE` | path/name of the keystore as it will exist on the runner (e.g. `/tmp/inkflow-release.keystore`) |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `inkflow` (or your alias) |
| `KEY_PASSWORD` | key password |

Upload the keystore into the workflow as a base64 secret and decode it in the
job **before** the `assembleRelease` step, for example:

```yaml
- name: Decode release keystore
  run: |
    echo "${{ secrets.RELEASE_KEYSTORE_B64 }}" | base64 -d > /tmp/inkflow-release.keystore
  env:
    RELEASE_KEYSTORE_B64: ${{ secrets.RELEASE_KEYSTORE_B64_B64 }}
```

(The exact workflow lives in `.github/workflows/`; this phase does not edit it.)

### Verify the signature

```bash
# Signing key of a produced APK (check the universal + every ABI split)
keytool -printcert -jarfile app/build/outputs/apk/release/app-universal-release.apk | grep -E "owner|CN"
keytool -printcert -jarfile app/build/outputs/apk/release/app-arm64-v8a-release.apk | grep -E "owner|CN"
apksigner verify --verbose app/build/outputs/apk/release/*.apk   # all must print "Verifies"
```

Expected for a real build: your `CN=InkFlow...` cert. If it says
`CN=Android Debug`, you are still on the debug fallback — fix the keystore
before publishing.

## Artifacts

`assembleRelease` now emits **ABI-split APKs** (phase-170, Phase-32-NEW-02) so a
device only downloads/native-loads its own ABI. The output directory is
`app/build/outputs/apk/release/`:

| Build | Output(s) |
|---|---|
| `assembleRelease` | `app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk`, `app-x86-release.apk`, `app-x86_64-release.apk` (one per ABI) **and** `app-universal-release.apk` (all 4 ABIs, for sideloading/emulators) — signed with the same `releaseConfig` (fail-closed, B1-PLAT-1) |
| `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` (single monolithic APK, unchanged) |

- The ABI split runs ONLY on release builds (`splits.abi.isEnable` is gated on the
  requested task list containing a release task); `assembleDebug` output is unchanged.
- Every produced split APK passes `apksigner verify` (v2), signed `CN=InkFlow Release`.
- The universal APK is the full-fat fallback for sideloading / emulators / x86
  test-beds; it keeps ALL four ABIs + the trimmed lingua corpus and is the honest
  "one build downloads everything" channel (Phase-32-NEW-02 notes it as the only
  remaining non-split distribution path — prefer an ABI-split APK per device).

A ".aab" (Android App Bundle) is not produced yet — generating one needs
`bundleRelease`. Do that when actually onboarding to Play.

## Publish checklist

1. `gradle testDebugUnitTest` green.
2. `gradle assembleRelease` green with a REAL keystore (`keytool -printcert`).
3. Bump `versionCode`/`versionName` via CI env `VERSION_CODE`/`VERSION_NAME`
   (defaults: 2 / 1.0.0).
4. Update `CHANGELOG.md` honestly (no fake feature claims).
5. Store metadata: Play Data safety is in `docs/DATA_SAFETY.md`, privacy policy
   in `docs/PRIVACY_POLICY.md`.
6. Signing key is kept forever — losing it means you cannot update the app.

## Gotchas

- **Master password strength & offline brute force (B1-PLAT-8, B1-CRYPTO-04)**:
  new master/backup passwords are strength-gated at set/rotate time by the
  pure-JVM `PasswordStrengthPolicy` (`services/PasswordStrengthPolicy.kt`):
  ≥ 10 NFKC-normalized graphemes, no sequential/keyboard-row/repeated patterns,
  no widely-leaked password words (bare, or with only digit/symbol prefix/suffix
  decoration), and class diversity for short passwords. **Offline brute force is
  only mitigated by password entropy — NOT by the on-device lockout.** The
  5-attempt UI lockout throttles only typing on the device; an attacker with a
  copy of the prefs + SQLCipher vault (or a restore onto a rooted emulator)
  cracks the wrapped DEK with a GPU rig at the speed of the password itself.
  Recommend long passphrases (≥ 16 chars) in all user-facing guidance.
  Evaluation order (phase-90 review fix): a common word is detected BEFORE the
  length floor and the pattern checks, so a bare `password`/`sunshine` and the
  `password123`/`123password` keyspace report "too common" — never a misleading
  "too short"/"predictable pattern"; non-common inputs still report the length/
  pattern reason. The order only changes the reason string, never accept/reject.
- **App icon / label**: `applicationId = com.aistudio.inkflow.app.bkxjrz`,
  namespace `com.authorss81.noteflow` (known mismatch — ROADMAP 21.10). Keep
  `applicationId` stable; renaming it orphans existing installs.
- **`allowBackup=false` + `data_extraction_rules.xml`**: never re-enable
  backup; the vault is encrypted but the device-transfer policy excludes
  everything.
- **No secrets in the APK**: no API keys are compiled in (web search is keyless;
  OCR is on-device; WebDAV credentials are typed by the user at runtime).