# Phase 110 — B1-NET-09 (INFO) User-Agent / metadata fingerprinting

Finding fixed: `B1-NET-09` — outbound transports sent app-identifying, versioned
User-Agent strings (`Noteflow-Android-WebDAV-Sync/2026`,
`Noteflow-Plugin-Runtime/2026`, `InkFlow/1.0`), and LocalSend announces leaked
`Build.MODEL`. A monitoring server could fingerprint the exact app, version and
handset, then serve version-specific payloads (see B1-NET-01).

## What changed (file:line)

### Shared constant (new)
- `app/src/main/kotlin/com/authorss81/noteflow/utils/HttpUserAgent.kt`
  — `object HttpUserAgent { const val GENERIC = "Mozilla/5.0" }`. A single,
  generic, version-less User-Agent used by every outbound transport so no
  request identifies the client beyond "Mozilla-compatible".

### Transports now send the generic UA
- `services/WebDavSyncService.kt:156` — was
  `conn.setRequestProperty("User-Agent", "Noteflow-Android-WebDAV-Sync/2026")`
  (line 154), now `HttpUserAgent.GENERIC`. Import added at line 11.
- `plugins/runtime/PluginManifestFetcher.kt:101` — was
  `"Noteflow-Plugin-Runtime/2026"` (line 100), now `HttpUserAgent.GENERIC`.
- `plugins/runtime/HttpsPluginDownloadTransport.kt:163` — was
  `"Noteflow-Plugin-Runtime/2026"` (line 162), now `HttpUserAgent.GENERIC`.
- `plugins/webcapture/WebPageFetcher.kt:38` — was
  `Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) InkFlow/1.0`
  (private `USER_AGENT` const, lines 85-86); the old const was deleted and the
  header now uses `HttpUserAgent.GENERIC`.
- **Review sweep (same finding, same leak via the implicit default UA):** the
  remaining outbound transports that called `URL.openConnection()` with NO
  User-Agent sent Android's default `Dalvik/2.1.0 (Linux; U; Android <ver>;
  <MODEL> Build/...)`, leaking OS+model exactly like the named sites. All now
  send `HttpUserAgent.GENERIC`:
  - `plugins/citation/HttpsTitleFetcher.kt:43` (arbitrary user-supplied URLs)
  - `services/AppFacadeHost.kt:52` (facade `httpGet`, arbitrary HTTPS)
  - `plugins/weather/WeatherClient.kt:86`
  - `plugins/websearch/DuckDuckGoClient.kt:145`
  - `plugins/dictionary/DictionaryClient.kt:51`

### LocalSend — remove `Build.MODEL` + generic UA on LAN HTTP
- `services/localsend/LocalSendProtocol.kt:93-114` — new pure-JVM
  `LocalSendMessages.senderIdentity(fingerprint)` factory: fixed `alias =
  "InkFlow"`, `deviceModel = null`. Deliberately does NOT put `Build.MODEL` into
  the announce/register/prepare-upload bodies.
- `services/localsend/LocalSendSender.kt:75-77` — `senderInfo()` now delegates
  to `LocalSendMessages.senderIdentity(...)`; the `Build.MODEL` import (line 3)
  and both `Build.MODEL` usages (old lines 76/78) are gone.
- `services/localsend/LocalSendSender.kt:234` and `:473` — the legacy HTTP scan
  and the `openConnection()` helper now set `User-Agent: HttpUserAgent.GENERIC`.
  Without this, Android's default `HttpURLConnection` UA
  (`Dalvik/... (Linux; U; Android <X>; <MODEL> Build/...)`) leaks the same
  handset/OS metadata to every LAN host — closing that along with the announce.

### Units tests (new / updated)
- `app/src/test/java/com/authorss81/noteflow/HttpUserAgentTest.kt` (new, 3 tests):
  `HttpUserAgent.GENERIC` is generic, version-less, app-name-less, OS-less, and
  a well-formed HTTP header value (no CR/LF/NUL). Regression tripwire against
  the old `Noteflow-*`/`InkFlow/1.0` values.
- `app/src/test/java/com/authorss81/noteflow/LocalSendProtocolTest.kt` (+5 tests,
  18→23): `senderIdentity` exposes no device model; the announce JSON, register
  body, and prepare-upload body built from it carry neither a `deviceModel`
  marker nor a model string. (Test renamed `senderIdentity_exposesNoDeviceModel`
  in review fixes — the wire bodies still carry the LocalSend *protocol*
  version `2.0` by design, so the old name "…OrVersion" overclaimed.)

## Before/after evidence

| Site | Before | After |
|------|--------|-------|
| `WebDavSyncService.kt:156` | `User-Agent: Noteflow-Android-WebDAV-Sync/2026` | `User-Agent: Mozilla/5.0` |
| `PluginManifestFetcher.kt:101` | `User-Agent: Noteflow-Plugin-Runtime/2026` | `User-Agent: Mozilla/5.0` |
| `HttpsPluginDownloadTransport.kt:163` | `User-Agent: Noteflow-Plugin-Runtime/2026` | `User-Agent: Mozilla/5.0` |
| `WebPageFetcher.kt:38` | `...InkFlow/1.0` | `User-Agent: Mozilla/5.0` |
| `LocalSendSender.kt:75-84` | `alias = "InkFlow (<Build.MODEL>)"`, `deviceModel = Build.MODEL` | `alias = "InkFlow"`, `deviceModel = null` |

Grep-verification across `app/src/main/**/*.kt`: no `Build.MODEL`,
`Noteflow-Android-WebDAV-Sync`, `Noteflow-Plugin-Runtime`, or `InkFlow/1.0`
remain (only doc comments in the new test + the `senderIdentity` KDoc mention
them as "must not regress to"). Every `URL.openConnection()` / `HttpURLConnection`
site either sets `User-Agent: HttpUserAgent.GENERIC` or is the WebDAV
`createConnection` factory that applies it — a fresh
`rg "openConnection"` sweep (11 sites) confirms none is left with the implicit
Dalvik UA. The two connections inside `WebDavSyncService` that carry
`Depth`/`Content-Type` headers only (`WebDavSyncService.kt:167,174,213,257`)
all come from `createConnection`, so they inherit the generic UA.

## OS/API floor (AGENTS.md hardware reality)

No new Android API is used — this is a pure string/HTTP-header change valid on
API 26+. No fallback or user notice needed; nothing silently degrades.

## Checksum / secrets handling

None affected. No key/password content changed; the WebDAV `Authorization:
Basic` header path is untouched (that is B1-NET-01's lane, different phase).
`allowBackup="false"`, `ClipboardGuard`, and `FLAG_SECURE` are all untouched.

## Verification

- Targeted: `gradle :app:testDebugUnitTest --tests HttpUserAgentTest --tests
  LocalSendProtocolTest --tests WebDavSyncServiceTest --tests
  WebCaptureExtractorTest` → BUILD SUCCESSFUL. HttpUserAgentTest = 3, 0 failed;
  LocalSendProtocolTest = 23 (was 18), 0 failed.
- Full: `gradle testDebugUnitTest` (app) — 693 tests. Multiple runs:
  `BUILD SUCCESSFUL in 28-32s` (green runs captured) AND intermittent failures
  of a single pre-existing test.
- `gradle :app:assembleDebug --rerun-tasks` → BUILD SUCCESSFUL (57 tasks
  executed, forced recompile included); subsequent run up-to-date green.

### Pre-existing failure (proven unrelated — see DoD clause)

`PluginUpdateEngineTest > a hash mismatch on the downloaded artifact is never
applied` (`PluginUpdateEngineTest.kt:224`) fails intermittently (~50-60% of
full-suite runs, never in isolation: 5/5 pass alone). Reproduced identically on
the **clean pre-change tree** (`git stash && gradle testDebugUnitTest` → same
`PluginUpdateEngineTest` failure, plus `WikiLinkParserCacheUnitTest` under
`--rerun-tasks` load). This is a known test-harness race: `TestArtifactBuilder`
signs v1 and v2 with the SAME keystore back-to-back
(`TestDownloadablePlugin.kt:126-148`); when both JARs are signed within the
same millisecond the signed bytes are identical, so their SHA-256 collides and
the "v1 served under v2's digest → must fail" assertion trips. It is unrelated
to this phase (my change touches only UA strings + LocalSend identity, none of
which the engine/verifier tests exercise). Left for a test-harness phase per
"one finding per phase" scope; documented here only.

## Out-of-scope / notes

- The WebDAV default-`instanceFollowRedirects` + credential-forwarding issue is
  B1-NET-01, NOT touched here.
- The plugin manifest/artifact `instanceFollowRedirects=true` is B1-NET-03/05,
  NOT touched here.
- LocalSend feature scope (HTTP-only protocol, announce cadence, dialog gating,
  fake-receiver TOFU) belongs to B1-NET-02 / B1-NET-06 (phase-85), NOT this
  phase. Only Build.MODEL removal + generic UA for the existing connections
  were in-scope and applied.
- No DB schema change, no dependency added, `.github/workflows/` untouched.