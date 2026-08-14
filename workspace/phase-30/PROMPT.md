# Phase 30: Full security audit (hacker mindset) — 2 batches × 5 parallel subagents [NOT STARTED]
You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. The owner has EXPLICITLY CONSENTED to an adversarial security audit. This
phase finds and documents security loopholes in the source code (a later phase,
32, will also attack the built APK with hacking tools).

Your deliverable is **`docs/security-report.md`** (create it fresh if absent;
APPEND if present). Follow the AGENTS.md rule: write findings to the file
INCREMENTALLY as you go, commit/push after each batch, never keep findings only
in your reply.

## Method (2 batches, 5 parallel subagents each)
- **Batch 1**: launch **5 parallel subagents** (Task tool). Give each a distinct
  attack area and tell them to think like a hacker and find real loopholes with
  `file:line` evidence:
  1. Cryptography & key management (EncryptionService, VaultKeyHolder,
     SecurityService, PBKDF2/AES-GCM/AndroidKeyStore usage, DEK wrapping,
     zeroization, password validation).
  2. Data-at-rest & DB (NoteflowDatabase, NoteRepository, field encryption,
     WAL checkpoint, quarantine/restore paths, backup disable, export/import).
  3. Data-in-transit & network (WebDAV, all HTTP clients, URL construction,
     TLS, SSRF, redirects, the new cloud-AI endpoint, LocalSend).
  4. Android platform surface (manifest: exported components, intent filters,
     exported providers/activities, file_paths.xml, WebView usage, clipboard,
     debug/release flags, deep links, permissions).
  5. App logic & auth (authentication flow, biometrics, session/lock,
     authorization, IDOR-style access between notes, injection in queries,
     path traversal in imports/exports, plugin error isolation bypasses).
- **Batch 2**: launch **5 parallel subagents** on DIFFERENT angles, explicitly
  instructed to find NEW loopholes NOT already in the report:
  1. Compose/UI-layer issues (state leakage, previews, lazy fields), race
     conditions, concurrency, TOCTOU.
  2. Dependency/vuln review (check known CVEs of the libraries in
     `gradle/libs.versions.toml`; supply-chain notes).
  3. Logging, crash reporting, telemetry, and information disclosure
     (PrivacyCrashReporter, AppStartupLogger, error messages, stack traces).
  4. Resource-exhaustion/DoS vectors (huge notes, zip bombs in import, memory
     pressure, bitmap pool, recursion).
  5. Crypto side-channels & edge cases (padding oracles, IV reuse, key reuse,
     timestamp leaks, RNG usage, timing).
- Every subagent MUST append its findings to `docs/security-report.md` itself
  (incremental writes + commit/push after each batch), with: severity
  (CRITICAL/HIGH/MEDIUM/LOW/INFO), evidence `file:line`, exploit scenario, and a
  suggested fix.
- You reconcile the batches: dedupe, merge severities, and ensure the report is
  complete and consistent.

## Definition of done
- `docs/security-report.md` written by both batches with findings from all 10
  subagents, deduped, severity-ranked, `file:line` evidence, and fixes.
- No finding exists only in a subagent reply — everything is in the file.
- Evidence that 10 subagents ran (list which agent covered which area).
- `gradle assembleDebug`/`testDebugUnitTest` still pass (no code changes unless a
  subagent fixed a trivial finding — prefer documenting over fixing here).
- The report has a findings-count summary + top-risks section.

## Constraints
- You are AUTHORIZED to probe the code (consent given). Do NOT attack any
  external service/device — this is source-code review only.
- Do NOT fix findings in this phase (that's later phases from the report) except
  trivial ones — document everything.
- Do NOT change the DB schema or `.github/workflows/`. No new deps.
- The report is the single source of truth for later fix phases — be precise
  (file:line, reproducer, fix suggestion).
