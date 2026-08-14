# Phase 46: B1-AUTH-01 - Downloadable plugin bytecode executes with the app... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B1-AUTH-01, HIGH) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B1-AUTH-01` (HIGH)
- **Area:** Batch 1 - App logic & auth
- **Evidence:** `AppClassLoaderFactory.kt:22-28` (`DexClassLoader(artifactPath, optimizedDir, null, parent)` - parent is the app's own classloader), `RuntimePluginLoader.kt:94-121` (loads the declared class with no resolution restriction), `PluginContext.kt:27-29` ('never receives DB/keystore/EncryptionService' is only documentation), `PluginManager.kt:117-126` (invokes plugin code with full app privileges)
- **Exploit scenario:** Compromised/leaked-signed plugin bytecode (see B1-CRYPTO-01/B1-NET-03) simply executes `val dek = VaultKeyHolder.dek ?: SecurityService(c).getOrCreateDek()` and exfiltrates it - the `FacadeWhitelist` is an optional UX layer a plugin is under no obligation to use. There is no security boundary between a plugin and the vault.

## The fix (where & how)

`AppClassLoaderFactory.kt:22-28`, `RuntimePluginLoader.kt:94-121`. Load plugin DEX under an isolated classloader whose PARENT is a scoped, interface-only loader so `com.authorss81.noteflow.*` beyond the `plugins.*`/plugin-SDK framework types cannot resolve; or run downloadable plugins in a separate `:remote` process behind an IPC capability boundary. At install/verify time statically scan the artifact for references to `services.*`, `data.*`, `VaultKeyHolder`, `EncryptionService` and reject. Enforce network egress only through the host.


## Verification

- Unit test: a test plugin touching `VaultKeyHolder`/`SecurityService`/`NoteflowDatabase` fails to load (or fails static scan); a whitelisted-capability plugin loads and runs. Existing `PluginFrameworkTest`/`PluginExecutionSimulationTest` stay green. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B1-AUTH-01 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-46/REPORT.md` committed: what changed (file:line), the
  checksum/secrets handling, verification output, and any input you judged
  out-of-scope.

## Constraints

- NO DB schema change unless this fix requires one - then a migration-safe note
  in REPORT.md is MANDATORY, and the migration must never delete user data.
- Do NOT edit `.github/workflows/`. Do not add new dependencies unless required
  by the fix (then justify in the commit).
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`,
  `ClipboardGuard`, and FLAG_SECURE intact.
- Do not fix OTHER security findings in this phase - that is a different phase.
  If you find a new related bug, document it in REPORT.md, do not fix it here.
