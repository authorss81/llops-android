# Phase-77 REPORT — B2-DEPS-05: Downloaded LLM model (GGUF) pinned

**Status: DONE — 2026-08-17 · Verified `gradle testDebugUnitTest` 1719 green (0 failures) + `gradle assembleDebug` green**

This phase replaces the phase-77 **FALSE COMPLETION** (commit `c67707b`, which only added `junit-bom` hashes to `gradle/verification-metadata.xml`) with a real fix: the downloadable-assistant GGUF now carries a PINNED identity end to end.

## Finding restated

`plugins/llm` downloaded the model via a plain `HttpURLConnection` with
`instanceFollowRedirects = true`, no cert pin, no host allow-list; any HTTP 2xx
body was accepted after only `isPlausibleModelFile(total)` (>1 MB) — the
`expectedSizeBytes` constant was never compared — and the URL came from the
user-editable `SETTING_MODEL_URL` setting. A MITM/DNS hijack/mirror compromise
or a user-supplied custom URL could therefore deliver an arbitrary GGUF that the
MediaPipe `tasks-genai` runtime parses in-process (model poisoning / content
injection into every future assistant answer).

## The pin

Re-verified 2026-08-17 against the Hugging Face repo-tree API for
`Qwen/Qwen2-0.5B-Instruct-GGUF` → `qwen2-0_5b-instruct-q4_k_m.gguf`:

| identity | value |
|---|---|
| git-LFS SHA-256 (`oid`) | `f0a42bb979ca62b5e61f3bf924ab4b6a40aa091825ee7dcb4039949980ab81a8` |
| exact size | `397_805_248` B (379.4 MiB — the old "398 MB" approximation was wrong and was never compared) |
| repo commit | `198f08841147e5196a6a69bd0053690fb1fd3857` |
| entry host | `huggingface.co`; `resolve/main` 302s to `us.aws.cdn.hf.co` (CDN family allow-listed as `*.hf.co` + `*.huggingface.co`) |

Detached signature was not published (HF provides `oid`+`size`, which the finding's
fix accepts: "publish and verify the expected SHA-256 (and ideally a detached
signature)"). The hash + size pin satisfies the required part; a signature would
require hosting infra this repo cannot establish.

## Changes

### `plugins/llm` (the module where the finding lives)

- **`policy/AssistantStoragePolicy.kt`** — publishes `DEFAULT_MODEL_SHA256`,
  corrects `DEFAULT_MODEL_SIZE_BYTES` to `397_805_248L`, KDoc documents the
  pin; the URL is fixed and the `model_url` override is documented as gone.
- **`policy/ModelDownloadPolicy.kt`** *(new, pure JVM)* — the single decision
  table:
  - `validateEntry(url)` — https, host **exactly** `huggingface.co`, no embedded
    credentials, valid URI.
  - `isAllowedDownloadHost` / `validateHop` — huggingface.co, `*.huggingface.co`,
    `*.hf.co` (the real CDN family); https-only; no credentials.
  - `resolveNextHop(cur, location)` — RFC 3986 resolution, redirect-loop and
    malformed-target refusal (`HopRefusedException`).
  - `MAX_REDIRECTS = 5`, `CONNECT_TIMEOUT_MS = 20_000`, `READ_TIMEOUT_MS = 40_000`.
  - `verifyDownload(actualBytes, actualSha256Hex, expectedBytes, expectedSha256)`
    — size FIRST, then full-length constant-time hex compare via
    `MessageDigest.isEqual` (sealed `DownloadVerdict: Match | SizeMismatch | HashMismatch`).
- **`engine/AssistantModelDownloadRunner.kt`** *(new, pure JVM, injectable
  `connectionFactory`)* — the pinned, redirect-safe downloader: manual
  `instanceFollowRedirects = false` loop (the B1-NET-05 / `StrictRedirectPolicy`
  pattern, re-implemented inside the module because `plugins/llm` cannot import
  `app` packages), every 3xx `Location` re-validated before the next connection
  opens, body streamed into a `.part` temp while SHA-256 hashing (64 KiB buffer,
  `yield()` for cancellation, progress via Content-Length), accept ONLY on exact
  size+SHA-256 match, all failures delete the temp and return a typed `Outcome`.
- **`engine/AssistantModelDownloader.kt`** *(rewritten)* — signature is now
  `download(context, onProgress)` (the `url`/`expectedSizeBytes` params are gone);
  an EXISTING on-disk model is re-verified against the pin at every call (stale /
  poisoned file deleted and re-downloaded, never served); StatFs free-space
  preflight via `AssistantStoragePolicy.checkSpace`; atomic rename; cancellation
  deletes the temp.
- **`LocalLlmPlugin.kt`** — the `settings` field + `SETTING_MODEL_URL` are
  deleted; `downloadModel` uses the pinned download and reports
  "downloaded and verified".
- Tests:
  - **`ModelDownloadPolicyTest`** (21) — decision table: host allow-list (incl.
    CDN family + false-positive hosts), entry https/credentials/malformed,
    relative + same-family redirects, HTTPS→HTTP / off-family / credentials /
    loop / malformed hop refusals, `verifyDownload` match / size-mismatch /
    hash-mismatch, case-insensitive full-length compare, hex shape.
  - **`AssistantModelDownloadTest`** (12) — end-to-end pure-JVM flow with a
    scripted `HttpURLConnection` fake (pattern: `B1Net05RedirectDowngradeTest`
    `FakeConnection`): happy path, hash-mismatch, size-mismatch, HF-CDN
    redirect followed, off-family / http-downgrade redirect refused BEFORE the
    next connection opens (asserted via the opened-URL log), loop + too-many-hops
    refusal, non-2xx, entry-host pin before any connection, temp cleanup, and the
    pin constants self-consistency (real size, well-formed 64-hex SHA-256).
  - **`AssistantPromptTest`** — `default model identity and size are fixed` now
    asserts `397_805_248L` + `DEFAULT_MODEL_SHA256` + hex shape.

### `app` — one display literal + pre-existing build blockers (NOT phase work)

- `ui/components/OnDeviceSmartAssistant.kt:144` — display fallback `398L` → `379L`
  (the UI derives from `expectedModelSizeBytes()` when the plugin is loaded; the
  literal only shows pre-install).
- **Pre-existing breakage found at HEAD that blocked EVERY build** (fixed so the
  phase gate can run; independent of B2-DEPS-05):
  1. `ui/screens/HomeScreen.kt` — a missing `}` left `processImportedUris`'s
     `try { for (…) { … } } catch` unbalanced; the app module did not compile
     (`409:23 Expecting ')'`, `2765:2 Expecting '}'`). Added the brace.
  2. `WebToMarkdownExtractor.kt` — `convert`'s `else` branch recursed only over
     `Element` children, silently dropping the direct text of unknown elements;
     the B2-DEPS-01 CVE payload's content (`alert(1)` inside `script<ESC>` on the
     jsoup-1.23.1 fixed line) was therefore lost. The else branch now emits an
     unknown element's direct `TextNode`s before recursing (containers carry no
     direct text, so nothing duplicates). `WebCaptureExtractorTest` still green.
  3. Broken test files (assertions that could never pass):
     - `B2Crypto08RngHygieneTest` — `assertArrayEquals(EncryptionService.newSalt(), a)`
       compared FRESH random bytes; removed the impossible lines (delegation is
       source-pinned by the same class's `EncryptionService contains exactly one
       nextBytes draw` test).
     - `B1Plat03ExportConsentTest` — stale pin `rememberSaFExporter(scope)` vs the
       real `rememberSaFExporter(vaultScope)` (HomeScreen.kt:70).
     - `B2Dos07BackupExportStreamingTest` — stale pin `EncryptionService.GCM_IV_LENGTH`
       vs the phase-114-centralized `EncryptionService.newIv()` (BackupExportPolicy.kt:151).
     - `B2Dos08WebDavListingBoundTest` — (a) contradictory
       `assertTrue(...contains("LISTING_TOO_LARGE_MESSAGE"))` +
       `assertFalse(...contains("LISTING_TOO_LARGE_MESSAGE"))`; the assertFalse now
       checks the constant's own literal line for `$`. (b) `DripInputStream`
       filled buffers with `0x41` ('A') and could never deliver the XML body, so
       the two "split read boundary" tests always returned empty; it now accepts a
       real `payload` byte array and the two boundary tests stream the actual XML
       through it (3-byte and 1-byte chunks). This also proves the production
       `scanBackupHrefs` handles split tags/hrefs/UTF-8 boundaries correctly.

## Files

- `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/policy/AssistantStoragePolicy.kt` (M)
- `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/policy/ModelDownloadPolicy.kt` (new)
- `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/engine/AssistantModelDownloadRunner.kt` (new)
- `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/engine/AssistantModelDownloader.kt` (rewritten)
- `plugins/llm/src/main/kotlin/com/authorss81/noteflow/llm/LocalLlmPlugin.kt` (M)
- `plugins/llm/src/test/kotlin/com/authorss81/noteflow/llm/ModelDownloadPolicyTest.kt` (new, 21)
- `plugins/llm/src/test/kotlin/com/authorss81/noteflow/llm/AssistantModelDownloadTest.kt` (new, 12)
- `plugins/llm/src/test/kotlin/com/authorss81/noteflow/llm/AssistantPromptTest.kt` (M)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/OnDeviceSmartAssistant.kt` (M — 379L literal)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt` (M — brace fix)
- `app/src/main/kotlin/com/authorss81/noteflow/plugins/webcapture/WebToMarkdownExtractor.kt` (M — direct-text preservation)
- `app/src/test/java/com/authorss81/noteflow/B2Crypto08RngHygieneTest.kt` (M)
- `app/src/test/java/com/authorss81/noteflow/B1Plat03ExportConsentTest.kt` (M)
- `app/src/test/java/com/authorss81/noteflow/B2Dos07BackupExportStreamingTest.kt` (M)
- `app/src/test/java/com/authorss81/noteflow/B2Dos08WebDavListingBoundTest.kt` (M)
- Docs: `docs/security-report.md`, `docs/PLUGINS.md`, `docs/ARCHITECTURE.md`, `docs/phase-status.md`

## Verification

- `gradle :plugins:llm:testDebugUnitTest` — 50 green (17 pre-existing + 33 new).
- `gradle testDebugUnitTest` — **1719 green, 0 failures** (app 1669 + plugin-llm 50).
- `gradle assembleDebug` — green (first `:app:packageDebug` invocation hit the
  documented transient flake; immediate retry clean).
- No schema change, no migration, no new dependencies (junit 4.13.2 already pinned
  in `gradle/verification-metadata.xml`), `.github/workflows/` untouched.
- AGENTS.md "MAJOR ARCHITECTURAL CHANGE → ask first" rule: no architecture change;
  the download surface of the assistant plugin was hardened in place. The unrelated
  pre-existing build-blocking fixes above are documented here (bug-fix queue per
  ROADMAP Phase 27).
