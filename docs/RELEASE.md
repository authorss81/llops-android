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

## Release signing — the honest state

The `release` build type tries, in order:

1. **A real release keystore** when the `KEYSTORE_FILE` env var points at an
   existing file. Credentials come from `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
   `KEY_PASSWORD` (defaults `android`/`key0`/`android` — set them explicitly!).
2. **`debug.keystore`** generated from an optional `debug.keystore.base64`
   blob at the repo root (password `android`). This is a CI/dev fallback only.
3. **AGP's auto-generated debug keystore** (`~/.android/debug.keystore`).

This means a plain `gradle assembleRelease` today produces an APK **signed with
a debug key** — fine for internal/beta distribution and CI verification, but
**NOT publishable**. Do not upload it to a store as-is.

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
# Signing key of a produced APK
keytool -printcert -jarfile app/build/outputs/apk/release/app-release.apk | grep -E "owner|CN"
```

Expected for a real build: your `CN=InkFlow...` cert. If it says
`CN=Android Debug`, you are still on the debug fallback — fix the keystore
before publishing.

## Artifacts

| Build | Output |
|---|---|
| `assembleRelease` | `app/build/outputs/apk/release/app-release.apk` |
| `assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |

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

- **App icon / label**: `applicationId = com.aistudio.inkflow.app.bkxjrz`,
  namespace `com.authorss81.noteflow` (known mismatch — ROADMAP 21.10). Keep
  `applicationId` stable; renaming it orphans existing installs.
- **`allowBackup=false` + `data_extraction_rules.xml`**: never re-enable
  backup; the vault is encrypted but the device-transfer policy excludes
  everything.
- **No secrets in the APK**: no API keys are compiled in (web search is keyless;
  OCR is on-device; WebDAV credentials are typed by the user at runtime).