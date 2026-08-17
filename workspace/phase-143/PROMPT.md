# Phase 143: Web Capture HTTPS-only — default bare/host input to https, refuse cleartext unless explicitly opted-in [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (finding R2-B1N-04) and `docs/phase-status.md` + `docs/ARCHITECTURE.md`.
This phase closes the sole remaining user-driven cleartext fetch.

## Source finding (OPEN, LOW)

**R2-B1N-04** — Web Capture still fetches plain-HTTP entry URLs over cleartext:
`plugins/webcapture/WebPageFetchPolicy.kt:18` (`ALLOWED_SCHEMES =
setOf("http","https")`), `:46` (explicit `http://` accepted), enforced per hop in
`WebPageFetcher.kt:28`/`:49`. Every other transport is HTTPS-only (WebDAV
`requireSecureUrl` `WebDavSyncService.kt:134-142`; `HttpsTitleFetcher.kt:51`
refuses HTTP by default). On open/guest Wi-Fi, an on-path attacker rewrites the
body mid-stream and the Markdown (with `[[wikilinks]]`, tags, images, raw HTML
rendered by `MarkdownPreviewScreen`) is derived from attacker bytes
(`WebCaptureEngine.kt:65-77`).

## The fix (where & how)

- Remove `http` from `ALLOWED_SCHEMES` (`WebPageFetchPolicy.kt:18`); default a
  bare/host-only input and any `https://` input to the https scheme; refuse
  explicit `http://` UNLESS the user opts in per-fetch with the same model as
  WebDAV's `allowInsecureHttp` (`WebDavSyncService.kt:134-142`).
- Keep the per-hop re-validation in `WebPageFetcher` (`:28`,`:49`) aligned to
  the scheme set so redirects to http stay refused.

## Verification

- New/updated pure-JVM unit tests: `http://` entry refused by default;
  bare host input defaults to https; explicit per-fetch opt-in allows cleartext
  (documented, one-time, non-alarming); an https→http redirect hop is refused.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-143/REPORT.md`.

## Definition of done

- R2-B1N-04 closed with `file:line` before/after evidence.
- Web Capture is HTTPS-by-default with an explicit per-fetch cleartext opt-in.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep `allowBackup=false`.
- Do not alter the WebDAV `allowInsecureHttp` model (only reuse its UX).
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.