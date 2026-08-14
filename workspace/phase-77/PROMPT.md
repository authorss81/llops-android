# Phase 77: B2-DEPS-05 - Downloaded LLM model (GGUF) is neither hash-pinned nor... [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report.md`** first
(finding B2-DEPS-05, MEDIUM) and `docs/phase-status.md`. This phase FIXES one
security finding from the Phase-30/32 audit. Scope is deliberately tight - one
finding per phase - so keep the diff small and targeted.

## Source finding

- **Finding:** `B2-DEPS-05` (MEDIUM)
- **Area:** Batch 2 - Dependencies / CVE / supply chain
- **Evidence:** `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/engine/AssistantModelDownloader.kt:69-74` (plain HttpURLConnection, `instanceFollowRedirects=true`, no cert pin, no host allow-list), `:75-98` (accepts any 2xx body; only `AssistantStoragePolicy.isPlausibleModelFile(total)` at :95 - `expectedSizeBytes` is never compared), `LocalLlmPlugin.kt:150-154` (URL from the user-editable `SETTING_MODEL_URL` or the hardcoded default), `AssistantStoragePolicy.kt:20-21`
- **Exploit scenario:** The model is the one non-code artifact in the trust chain but gets none of the SHA-256/cert-pin treatment claims. A MITM/DNS-hijack/mirror-compromise/user-supplied URL delivers an arbitrary GGUF that the MediaPipe tasks-genai runtime parses in-process (full app privileges), and served weights become a permanent model-poisoning vector.

## The fix (where & how)

`AssistantModelDownloader.kt:69-98`, `LocalLlmPlugin.kt:150-154`, `AssistantStoragePolicy.kt` - publish and verify the expected SHA-256 (ideally a detached signature) of the default GGUF; pin the download host (huggingface.co); set `instanceFollowRedirects=false` or re-validate redirect targets; drop the user-editable arbitrary URL or gate it behind strong confirmation while showing the hash; verify `expectedSizeBytes` equals actual bytes after download.


## Verification

- Pure-JVM unit test: a download whose bytes do not match the published SHA-256 is rejected and deleted; size mismatch is rejected. `gradle testDebugUnitTest` + `gradle assembleDebug`.
- New/updated pure-JVM unit tests for the exact behavior above (follow the repo's
  existing test layout in `app/src/test`).
- Always run `gradle testDebugUnitTest` then `gradle assembleDebug` and report the
  outcomes in REPORT.md. If a command is skipped, say why.

## Definition of done

- The vulnerability path described in B2-DEPS-05 is closed with `file:line`
  evidence in the commit (before/after).
- Fix protects the OS/API floor (API 26+) - include a fallback or explicit
  non-alarming notice where a newer API is required (AGENTS.md hardware reality).
- New unit tests prove the fix and no existing test regressed.
- `gradle testDebugUnitTest` + `gradle assembleDebug` both pass (or a documented,
  pre-existing-only failure is proven unrelated).
- `workspace/phase-77/REPORT.md` committed: what changed (file:line), the
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
