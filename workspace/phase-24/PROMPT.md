# Phase 24: Dynamic plugin updates — latest versions with user approval [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hybrid plugin architecture (Phase 22 skeleton: `PluginEntry` with
semver `PluginVersion` + update-channel, `PluginRuntime` update/rollback seams)
and a **downloadable-plugin runtime** (Phase 23: HTTPS download → pinned-cert-hash
+ sha256 verify → `DexClassLoader` load → capability facade → consent). **Read
`docs/plugin-architecture.md` first** — this phase implements the update model the
skeleton defined.

**THE CORE GOAL:** users get the LATEST verified plugin versions, but nothing is
ever auto-installed — every update requires **explicit user approval**, is
signature + sha256 re-verified, and rolls back cleanly if anything fails.

## What to build

1. **Hosted version manifest** — define a small version manifest format (JSON):
   per plugin `id`: latest `version`, `downloadUrl`, `sha256`, `pinnedCertHash`
   (or reference), `updateNotes`. A default manifest URL constant (HTTPS only);
   manifest fetch is keyless and user-initiated via the store's "Check for
   updates" action (or a manual refresh). Manifest parse is pure-JVM testable.
2. **Update check** — compare installed `PluginEntry.version` (semver,
   `PluginVersion.compareTo`) against the manifest. Report `UPDATE_AVAILABLE`
   only when manifest version > installed (never downgrade; never equal-no-op).
3. **User approval (MANDATORY)** — an update NEVER applies silently. On
   "Update" the store shows a dialog: current version → new version, `updateNotes`
   (what changed), size, and an explicit **"Approve & install"** action with
   consent wording. Refuse / downgrade to older versions is blocked. Approval is
   per-update, never a blanket "auto-update all" in this phase (a global
   auto-update toggle is NOT added).
4. **Verified update install** — download the new artifact, re-verify pinned cert
   hash + sha256 (Phase-23 verification), keep the CURRENT version's files intact
   until the new one is fully verified, then atomically swap (new version dir
   becomes active). On ANY failure (download, hash, signature, load smoke-test):
   rollback to the previous version automatically, keep the old entry, and surface
   a clear error. `file:line`-documented.
5. **Rollback path** — persist the previously-active version; `PluginRuntime.rollback`
   restores it (pure-JVM tests: rollback after failed hash, after failed signature,
   after failed load smoke-test).
6. **Store UI wiring** — in the Phase-21/23 store, downloaded plugins show an
   "Update available (vX→vY)" row state + Update button → approval dialog →
   progress → "Up to date"/"Rolled back" states. Built-in compile-time plugins are
   updated via the normal app release, not this mechanism — mark them
   "managed by app update".
7. **Tests (pure-JVM)** — manifest parse (valid/invalid/missing fields), semver
   compare (newer/older/equal/malformed), update-approval gating (never without
   consent), download→verify→swap happy path (fake transport), rollback on hash
   mismatch / signature mismatch / load failure, no-downgrade rule.

## Definition of done
- `gradle testDebugUnitTest` passes (all above).
- `gradle assembleDebug` succeeds.
- End-to-end: a test downloadable plugin shows UPDATE_AVAILABLE, user approval
  dialog appears, approved install verifies + swaps, and a tampered new artifact
  rolls back to the previous version with a clear message (`file:line` evidence).
- No silent/auto updates exist anywhere. Every update is user-approved, verified,
  and reversible.
- REPORT.md records: manifest format, approval flow, verification steps, rollback
  evidence, and the "managed by app update" handling for built-ins.

## Constraints
- NO auto-update toggle in this phase. Updates are always manual + approved.
- HTTPS only for manifest and artifact downloads; TLS enforced.
- Never downgrade; never replace a verified installed version with a tampered one.
- Never log keys, passwords, decrypted content, or downloaded artifact contents.
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- Do NOT bypass `ClipboardGuard`.
- Keep the update logic in `plugins/runtime/`; no new permissions.