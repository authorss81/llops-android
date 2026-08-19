# Kali Dynamic Pentest — Round 2 (Pass 2)

> Target prepared by **phase-159** (release-APK build & verification, 2026-08-19).
> Security findings from the Kali dynamic/instrumented pass (phase-160) go here.
> The pentest plan lives in `docs/pentest-plan.md`; the summary report is `docs/pentest-report.md`.

## APK target (verified by phase-159)

| Field | Value |
|-------|-------|
| Exact filename | `app/build/outputs/apk/release/app-release.apk` |
| Build commit sha | `a9d8918c933ce498ed4ad7c2780218ad2b606392` |
| versionCode | `2` |
| versionName | `1.0.0` |
| ApplicationId | `com.aistudio.inkflow.app.bkxjrz` |
| Signing scheme | APK Signature Scheme v2 (verified, `Verifies`) |
| Signer DN | `CN=InkFlow Release, OU=Dev, O=Authorss81, L=Unknown, ST=Unknown, C=US` |
| Signer certificate SHA-256 | `69636edb9ee2487762e98f855f250ea1ec66233de13b61a4c014026b82c50196` |
| Signer public-key SHA-256 | `0328af289a4b325229ffee68d8ac41aa4b863180174bd901e620bd75c04e7030` |
| APK SHA-256 | `54feb16c3533c6966f071414095c2256966c69161d845d9a67f7224d82bb455a` |
| Size | 142,339,635 bytes (~142.3 MB, > 1 MB sanity pass) |
| R8 minify | ON (release `isMinifyEnabled = true`; `:app:minifyReleaseWithR8` executed) |
| Signing keys source | env `KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD` (fail-closed B1-PLAT-1; no debug-keystore fallback) |

Verify identity against the keystore before attacking:
`keytool -list -keystore "$KEYSTORE_FILE" -storepass "$KEYSTORE_PASSWORD"` must list the same
certificate SHA-256 fingerprint.
