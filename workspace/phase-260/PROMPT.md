# Phase 260 — Storage: Room FK/migration/WAL/quarantine/transactions + crypto leftovers

## Goal
Close vault-loss paths; keep SQLCipher/HMAC guarantees.

## Evidence
- CRITICAL `NoteflowDatabase.kt:485` `.fallbackToDestructiveMigration()` unconditional + SCHEMA_VERSION=9 → restored backup with user_version 10+ or missing edge silently wipes vault. Fix: remove or `...OnDowngrade()` only.
- HIGH `Entities.kt:15-135` no `ForeignKey` (orphans survive torn deletes); missing composite index `(sectionId,pinned,updatedAt)` for `Daos.kt:84`; `exportSchema=true` but no committed `/schemas` JSON.
- HIGH `NoteRepository.kt:1170 deletePagePermanently` + `1264 emptyTrash` no `withTransaction` (unlike deleteNotebook:833/deleteSection:857); file deletes before DB deletes (dangling rows or leaked plaintext `.m4a` on crash). HIGH `Daos.kt:164` hard deletes no VACUUM; `1173` substring `contains("imports/")` instead of `SourceFilePathPolicy.confine` (path escape).
- MEDIUM `dispose()` swallows wal_checkpoint BUSY → closes with uncheckpointed WAL → HMAC baseline stale → false Mismatch next start; no `wal_autocheckpoint`; `quarantineCorruptDatabase` ignores `renameTo` bool + millisecond timestamp collision + flag set after rename (kill window); `isDatabaseCorruptException` overbroad `malformed` substring.
- MEDIUM retention TOCTOU (`count` vs `prune` concurrent creators); `getAll*ForReencrypt` unbounded (50k strokes OOM; versions path paged, pages/strokes/embeds not).
- Crypto leftovers (MEDIUM→LOW): `PBEKeySpec` char[] never `clearPassword` (`EncryptionService.kt:184,201`); `NoteRepository.kt:698 Log.w e.message` leaks path/marker (route via `FailureLogPolicy`).

## Files to change
- `data/db/NoteflowDatabase.kt`, `data/model/Entities.kt`, `data/repository/NoteRepository.kt`, `data/db/Daos.kt`, `services/EncryptionService.kt`

## Tests
- `Phase260StorageTest`: no destructive fallback (source pin); deletePagePermanently atomic (source pin); confine on delete (JVM policy test).

## Constraints
- No Room schema change / migration
- No new dependencies (pure Kotlin/Compose only)
- No `.github/workflows/` edits
- Follow AGENTS.md hard rules (allowBackup=false stays, no plaintext rows, fail closed)
- `verification-metadata.xml` untouched

## DoD
- `gradle :app:assembleDebug` green
- `gradle :app:testDebugUnitTest` green (all new + existing, 0 failures)
- `gradle :app:lintDebug` 0 errors
- `workspace/phase-NNN/REPORT.md` with file:line evidence table (claim / reality / status / evidence)
