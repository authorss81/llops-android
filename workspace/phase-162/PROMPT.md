# Phase 162: Build & code failure fixes + verify decryption-safety edge cases [NOT STARTED]

You are working on **InkFlow/Noteflow**. This phase fixes real build/code failures
that were diagnosed in the field, then VERIFIES (does not re-write) the
decryption-failure and data-integrity safeguards already in the codebase.

Read `docs/ARCHITECTURE.md`, `docs/phase-status.md`, and `AGENTS.md` first.

## Part A - Fix the build & code failures (REAL, confirmed)

### A1. Duplicate method declarations in NoteflowViewModel (compilation breaker)
`app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt`
currently declares DUPLICATE methods (merge-conflict leftover):
- `renameTag(oldTag, newTag)` at ~L2021 AND ~L2133
- `deleteTag(tag)` at ~L2054 AND ~L2160

These produce "Conflicting overloads" / duplicate function errors. Consolidate
into ONE implementation each, dispatched via `viewModelScope` (e.g.
`viewModelScope.launch(Dispatchers.IO) { repository.renameTag(...) }`). Keep any
additional state updates (tag list refresh, etc.) from the duplicate copies.
Then grep for OTHER duplicates the same merge introduced (same function name
declared more than once in the file) and fix those too. Verify no unbalanced
braces remain.

### A2. Deprecated `project.exec` in plugins/llm/build.gradle.kts
`plugins/llm/build.gradle.kts` uses `project.exec { ... }` (L250 `signPlugin`,
L273 `verifyPluginSignature`). It is deprecated and breaks Gradle 9 config-cache
isolation. Replace each with a `ProcessBuilder` inside the task's `doLast`:
`ProcessBuilder("sh", "-c", "...")` (or Windows-safe equivalent), waitFor(),
throw `GradleException("... failed with code $exitCode")` on non-zero. Preserve
exact command semantics (keystore passwords must NOT be printed).

### A3. Add `<trusted-artifacts>` to dependency verification
`gradle/verification-metadata.xml` has `verify-metadata=true` + `verify-signatures=false`
but no trusted-artifacts allowlist. When a NEW toolchain artifact (AGP/Kotlin
plugin) is resolved without a checksum entry, Gradle fails closed with
`Dependency verification failed for configuration ':classpath'`. Add:
```xml
<trusted-artifacts>
   <trust group="com.android.tools.build" />
   <trust group="org.jetbrains.kotlin" />
   <trust group="androidx.databinding" />
</trusted-artifacts>
```
inside `<configuration>` (AFTER the existing verify flags). This allows verified
components from official vendor groups without disabling verification.

## Part B - VERIFY the decryption-failure & integrity safeguards (already implemented)

Do NOT rewrite these — audit them against the claims and only fix if a claim is
FALSE. Cite `file:line` for each check in your REPORT.

### B1. Fail-closed "Unreadable" marker
`app/src/main/kotlin/com/authorss81/noteflow/services/DecryptFailurePolicy.kt`:
ciphertext failing AES-GCM auth must render `UNREADABLE_MARKER = "Unreadable (decryption failed)"`,
never raw Base64/garbage. Verify `NoteRepository.kt` (`data/repository/`) uses it
in `decryptFieldForDisplay`, and that a persistent failure threshold
(`PERSISTENT_FAILURE_THRESHOLD`, ~3 distinct records) trips
`DatabaseSecurityHelper.setCorruptionDetected` → CorruptionRecoveryScreen.

### B2. AndroidKeyStore invalidation → DEK loss
`SecurityService.kt` must return sealed `DekReadResult` (`Unlocked / NoBlob /
AuthRequired / KeyLost`) — on `KeyPermanentlyInvalidatedException` it must NOT
silently mint a fresh DEK (which orphans all encrypted notes). Verify
`NoteflowViewModel` maps `KeyLost` → `KeystoreKeyLostRecoveryScreen` (Master
Password / Backup Archive recovery).

### B3. AAD context mismatch on copy/move
`EncryptionService.computeAad(tableName, entityId, columnName)`. Verify all
copy/clone/move/reparent paths RE-ENCRYPT with the target entity's ID (decrypt
in memory first, fresh ciphertext) — no ciphertext survives a pageId mutation
with stale AAD.

### B4. SQLite WAL vs checksum-baseline race
`NoteflowViewModel` buffers initial DB integrity check until SQLite init +
WAL checkpoints settle (firstDataInitDone gating). Verify no TAMPERED
false-positive is possible from a mid-read WAL checkpoint on cold start.

### B5. OOM guards
`StrokeGeometryPolicy.kt` (`MAX_POINTS_PER_STROKE = 2000`, `MAX_POINTS_PER_PAGE = 50000`)
and `AttachmentIngestPolicy.kt` (`MAX_ATTACHMENT_BYTES`, head-only text reads) —
verify they are actually applied at ingestion AND at render.

### B6. Concurrent save collisions
`NoteRepository.kt` per-page `pageSaveLocks` (`ConcurrentHashMap<String, Mutex>`)
serializing stroke/embed/body updates FIFO. Verify it covers autosave +
on-pause + background writes for a given pageId.

## Definition of done
- Part A: duplicates removed (compile passes), `project.exec` replaced by
  ProcessBuilder tasks, trusted-artifacts added. Run `gradle build` /
  `./gradlew :app:assembleDebug` + `:plugins:llm` tasks to prove compilation.
- Part B: each B1-B6 item VERIFIED with `file:line` evidence; any FALSE claim
  FIXED and noted.
- `workspace/phase-162/REPORT.md`: table of every A/B item → status (FIXED /
  VERIFIED-OK / VERIFIED + FIXED) → `file:line` evidence.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT change the security model (fail-closed,
  DEK handling). Do NOT weaken dependency verification — only ADD the
  trusted-artifacts allowlist.
- Never log real secrets (keystore passwords, DEK, decrypted note content).
- Keep the base-APK-size rule; no heavy new dependencies.