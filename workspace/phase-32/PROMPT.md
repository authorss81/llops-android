# Phase 32: APK attack — download the built APK and bombard it with hacking tools [NOT STARTED]
You are working on **InkFlow/Noteflow**. The owner has EXPLICITLY CONSENTED to a
full offensive audit of the built APK. Phase 30 did source review; this phase
attacks the actual APK artifact with real security tooling on the Linux CI
runner, and appends every new finding to **`docs/security-report.md`** (create
fresh if absent; APPEND findings; commit/push as you go).

## Steps
1. **Locate the APK.** Check workflow artifacts from recent runs
   (`gh run list` + `gh run download` — look for `noteflow-apk` /
   `noteflow-release-apk` / `release.yml` artifacts) OR build one:
   `gradle assembleDebug` / `assembleRelease`. Download the newest
   `release`/`debug` APK to a local dir.
2. **Read the current report** `docs/security-report.md` first so you do not
   re-report known findings — only NEW ones.
3. **Install and run a battery of meaningful Linux/Android security tools.** Use
   the tools that are meaningful for this APK (install via apt/pip where needed):
   - **Static/structural**: `apktool` (decode; inspect manifest, smali,
     resources), `jadx` (decompile to Java for review), `dex2jar` + `jd-cli`,
     `androguard` (`androguard axml` / APKiD) — detect misconfig, exported
     components, debug flags, weak API usage.
   - **Fingerprinting**: `APKiD` — detect packers, weak crypto, suspicious
     strings.
   - **Dependency/vuln**: scan `libs.versions.toml` and bundled natives for
     known CVEs (`osv-scanner` if installable, else manual CVE notes).
   - **Strings/secrets**: `strings` on the dex/native libs — hunt hardcoded
     keys, URLs, tokens, passwords, debug leftovers.
   - **Cryptography**: search for weak primitives (MD5/SHA1/ECB/static IV),
     custom crypto, insecure random.
   - **Network surface**: extract URLs/endpoints; check TLS usage, cleartext
     config (`network_security_config`), exported network services (LocalSend).
   - **Native code**: `readelf`/`objdump` or `apktool` on `lib/` — check JNI
     symbols, missing hardening (PIE, RELRO, canaries if applicable).
   - **Manifest audit**: exported activities/services/receivers, intent
     injection, backup settings, permissions, debuggable flag, minify/R8,
     `allowBackup`, FLAG_SECURE.
   Bombard it: run every tool that is meaningful; each must produce evidence.
4. **Document new findings** in `docs/security-report.md` (append section
   "Phase 28 — APK dynamic/static analysis"): severity, tool used, evidence
   (command + output excerpt), exploit scenario, suggested fix. Note which
   findings were confirmed-by-tool vs code-review.

## Definition of done
- APK downloaded (or built) and artifact path documented.
- Tools run: at least apktool, jadx, androguard/APKiD, strings (and any others
  that install cleanly); each meaningful one captured output.
- New findings appended to `docs/security-report.md` with tool evidence.
- A summary of which prior findings were CONFIRMED on the APK and which NEW ones
  were found.
- Everything committed/pushed. `gradle` state of the repo unchanged (no code
  edits this phase).

## Constraints
- You are authorized to attack THIS app's APK only — do not touch any other
  system/device/network. No network probing beyond the app's own endpoints if
  present.
- Do NOT fix findings here (later phases fix). Do NOT change code, DB schema, or
  `.github/workflows/`.
- Tool installs limited to the CI environment; if a tool cannot be installed,
  note it and use the closest available equivalent — never fake tool output.
- No new deps added to the project.
