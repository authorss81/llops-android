# Phase 68 — B1-AUTH-04 (MEDIUM): Markdown image references resolve arbitrary absolute and `..`-traversing paths

**Status:** `DONE`
**Finding:** [B1-AUTH-04](`../docs/security-report.md:468`) — MEDIUM
**See also:** `docs/phase-status.md` (workspace pipeline row), `docs/ARCHITECTURE.md` ("Markdown" section).

## Summary

A crafted note arriving via the Obsidian/HTML vault-import zip, WebDAV sync, the
share sheet, or LocalSend could contain `![x](/data/user/0/<appId>/files/voice_notes/…)`
or `![x](../../../… )`; opening the note would trick the app into
`BitmapFactory.decodeFile`-reading ANY file the process can read (voice-note
blobs, imports, exports, shared staging) and displaying it, and the
"File not found: <path>" fallback doubled as an in-app existence oracle.

The fix moves markdown inline-image destination resolution out of the composable
and into a single pure-JVM policy that confines every resolution to an
allowlisted app-private subtree.

## What changed (`file:line`)

### New file — `app/src/main/kotlin/com/authorss81/noteflow/services/InlineImagePathPolicy.kt`
Pure-JVM resolver, the single decision table (mirrors the `SsrfHostPolicy` /
`NoteBodyVaultPolicy` pattern):

- `InlineImagePathPolicy.resolve(destination: String?, baseDir: File?): File?`
  - blank/null destination ⇒ `null` (`:31-32`);
  - ABSOLUTE destination (leading `/`) ⇒ `null`, rejected before any existence
    probe (`:36-38`) — a note never names files by absolute location;
  - any `..` path segment ⇒ `null`, rejected before reading; backslash `\` is
    treated as a segment separator too, so a Windows-style `..\..` sequence in an
    imported Obsidian note cannot hide traversal inside a single filename
    (`:42-45`);
  - non-directory / null `baseDir` ⇒ `null` (`:48-49`);
  - candidate must EXIST and not be a directory (`:51`);
  - after canonicalization (symlink resolution), the candidate must be a STRICT
    descendent of the canonical base dir (`:53-59` + `isStrictlyInside` `:65-70`)
    — a symlink planted under `baseDir` can never reach outside the subtree.

### Modified — `app/src/main/kotlin/com/authorss81/noteflow/ui/components/ImageViewer.kt`
`MarkdownInlineImage` (before `:123-131` / after `:129-131`):

- **Before** (the vulnerability):
  ```kotlin
  val file = File(dest)
  when {
      file.isAbsolute && file.exists() -> dest            // absolute accept ANY readable file
      baseDir != null && File(baseDir, dest).exists() -> File(baseDir, dest).absolutePath
      else -> null
  }
  ```
- **After**:
  ```kotlin
  val resolvedPath = remember(destination, baseDir) {
      InlineImagePathPolicy.resolve(destination, baseDir)?.absolutePath
  }
  ```

Both markdown renderers (`MarkdownPreviewScreen.kt:1304/1312`,
`MarkdownRenderer.kt:609/617`) feed into this single composable, so one fix
covers the preview, split, and hybrid-editor panes. The fullscreen viewer gets
the already-gated `resolvedPath`, so it never shows a forbidden file either.

## Checksum / secrets handling

- No keys, passwords, or decrypted note content are touched, logged, or stored.
- No new `INTERNET`, backup (`allowBackup=false`), `ClipboardGuard`, or
  FLAG_SECURE behavior was changed.
- The policy performs no network I/O; it is PURE JVM (`java.io.File` only).

## Verification output

`gradle testDebugUnitTest` — **1321 tests, 2 failed** (pre-addendum run; the
post-addendum run is 1322 total — see Addendum below. The 2 failures are the
pre-existing, untouched `B1Plat01ReleaseSigningTest` asserts documented in
phases 55/59/60/61/62/63/64/66/67 — `docs/RELEASE.md` + `app/build.gradle.kts`
signing-config asserts; they fail identically on a clean tree and are unrelated
to this diff).

New test class `app/src/test/java/com/authorss81/noteflow/B1Auth04InlineImagePathTest.kt`
(14 tests, all green in isolation and in the full run):

- absolute destinations rejected even when the file exists and is readable
  (incl. the crafted `/data/user/0/<appId>/files/…` voice-notes path);
- `../`, `../../../`, interleaved, and backslash-smuggled `..\..` traversal all
  rejected BEFORE any file I/O;
- `..` and `.` alone rejected;
- in-subtree relative paths (incl. sub-directories) resolve to their canonical
  file;
- nonexistent relative destinations do not resolve;
- blank/null destinations never resolve;
- a symlink under `baseDir` pointing outside is refused while a plain sibling
  still resolves (canonicalization + strict-prefix gate);
- a destination that lands on the root directory itself (`.`, `child`) never
  resolves;
- null / non-directory `baseDir` yields no resolution;
- `isBlockedDestination` classifies absolute / `..` references as blocked
  (regardless of existence) and plain relative references as not;
- source-level wiring pins: `MarkdownInlineImage` routes through
  `InlineImagePathPolicy.resolve(destination, baseDir)`; the
  `file.isAbsolute && file.exists()`, `File(baseDir, dest).exists()`, and
  `else -> dest` branches are gone; no `isAbsolute` / `File(baseDir, dest)`
  resolution remains anywhere in `ImageViewer.kt`; the blocked-vs-missing
  message and the pre-decode re-canonicalization guard are present.

`gradle assembleDebug` — **green** (173.7 MB `app-debug.apk`,
SHA-256 `4735b13c25a3b1f41c12cac04fa2b3585349e6e83d19daca7d883babf0d20f01`).

## Definition of done

- Vulnerability path closed with `file:line` evidence (see above, before/after).
- OS/API floor: pure-JVM policy, `java.io.File`/`java.io.nio` only — runs
  unchanged on the API 26+ floor; no newer-API requirement, so no fallback or
  notice is needed (AGENTS.md hardware reality satisfied).
- New tests prove the fix; no existing test regressed (only the 2 documented
  pre-existing `B1Plat01ReleaseSigningTest` failures remain).
- Both verification commands run and reported above.
- `workspace/phase-68/REPORT.md` committed.

## Out of scope (documented, NOT fixed — separate phases)

- **Canvas photo embeds** — `MediaEmbedComponents.kt:97/275` feed
  `FullscreenImageDialog`/`decodeBoundedImage` directly from the stored
  `embed.contentUrlOrPath` column. A crafted vault backup could point that column
  at arbitrary files — that is B1-AUTH-05's sourceFilePath-style vector, not a
  markdown destination, and belongs to a different finding/phase.
- **`note.sourceFilePath`** (B1-AUTH-05) and the **`sourceFilePath` → `baseDir`**
  anchor: `baseDir` is derived from `page.sourceFilePath.parentFile` or the imports
  dir (`MarkdownPreviewScreen.kt:162-165`, already DB-encrypted-store-fallback per
  phase-44). The value feeding `baseDir` is B1-AUTH-05's subject; this phase only
  bounds resolution for a given `baseDir`.
- The `SupportedImageFormat` allowlist (only actually decodable image bytes render)
  was already inherent in `decodeBoundedImage`'s bounds/`decodeFile` behavior — no
  new whitelist added (not required by the finding).
- No DB schema change, no new dependency, `.github/workflows/` untouched.

## Addendum — review fixes (same commit, after review)

The phase review surfaced five items; all are applied here:

1. **Blocked-vs-missing UI distinction (AGENTS.md "never silent degradation").**
   New pure-JVM `InlineImagePathPolicy.isBlockedDestination`
   (`InlineImagePathPolicy.kt:84-92`) is the shared ineligibility classifier
   (absolute, or a `..` segment in either separator). A policy-blocked reference
   is shown a distinct, non-alarming note — "Image location blocked (must be
   inside the note's folder)" (`ImageViewer.kt:184-188`) — instead of being
   echoed as "File not found: <path>". A settings "re-enable" toggle is
   deliberately NOT offered: re-allowing absolute / `..` references would
   re-open the vulnerability itself. Genuinely missing in-subtree files keep the
   original "File not found: <path>" text. This ALSO closes the residual
   existence echo: a blocked destination is never probed nor echoed, so
   out-of-subtree existence stays undisclosed.
2. **Existence-oracle claim scoped.** The oracle is closed for every path
   OUTSIDE the allowlisted subtree. Existence of files INSIDE `baseDir` remains
   observable through the "File not found" fallback — that is the feature's
   intended allowlist, is low-value (the subtree already holds the note's own
   attachments), and is acknowledged as an accepted residual here.
3. **TOCTOU hardening before decode.** `MarkdownInlineImage` re-canonicalizes the
   validated path immediately before `decodeBoundedImage` and refuses when it no
   longer equals the policy-time canonical path (`ImageViewer.kt:139-153`) — a
   file swapped for a symlink after resolution is never followed.
4. **remember key uses the path string, not the `File` instance** (java.io.File
   has identity equality) so a newly-constructed `baseDir` for the same folder
   cannot force an avoidable re-resolution (`ImageViewer.kt:132-137`).
5. **editorconfig compliance:** `insert_final_newline = true` now holds for the
   new source/test files and this report.

Tests: `B1Auth04InlineImagePathTest` is now 14 — the added classifier test
(`blocked destination classifier flags absolute and traversal references`)
proves `isBlockedDestination` flags absolute / `..` (incl. backslash-smuggled)
regardless of existence and leaves plain in-subtree references unblocked; the
wiring pins additionally assert the blocked-vs-missing message and the
re-canonicalization guard exist in `ImageViewer.kt`. `gradle testDebugUnitTest`
**:app** full rerun: 1322 total (+1 from the new classifier test), the same 2
pre-existing `B1Plat01ReleaseSigningTest` failures only.
`gradle :app:assembleDebug` green.
