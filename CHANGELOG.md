# Changelog - NoteFlow (Smooth Notes)

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.0] - 2026-08-13 — Phases 2–14 honest summary

### Added (real, verified — see `workspace/phase-14/AUDIT_REPORT.md` for per-phase verdicts)
- **LocalSend file transfer (Phase 14)**: send a note (HTML), the encrypted
  vault backup, or the Obsidian/HTML vault archives to any device running
  LocalSend on the same Wi-Fi. Local-network only, no internet, no account.
  The receiving device must human-accept before any bytes move. Reachable from
  Home → ⋮ → "Send to Nearby Device (LocalSend)". Sender-only implementation of
  LocalSend Protocol v2.2 (UDP multicast/broadcast discovery + legacy HTTP
  subnet scan + `/prepare-upload` + `/upload` + `/cancel`); HTTPS receivers are
  verified by their announced TLS fingerprint. Pure-JVM protocol layer covered
  by 18 unit tests with no network.
- **WebDAV / Nextcloud E2EE sync now reachable (Phase 14 wiring fix)**: the
  fully-implemented sync dialog (`WebDavSyncDialog`) was previously unreachable
  — the ⋮ menu item called an empty callback. Now opens the dialog. Uploads and
  downloads encrypted backup archives over enforced HTTPS (Phase 6 engine, now
  wired).
- **Real OCR plugin (Phase 12)**: on-device ML Kit text recognition, offline,
  no API key. Wired into the editor's image card ("Extract text").
- **Real web-search plugin (Phase 12)**: keyless DuckDuckGo Instant Answer API,
  HTTPS, query URL-encoded (no injection), response size-limited in Phase 14,
  wired into the Markdown screen.
- **Plugin framework (Phases 10–11)**: compile-time plugin registry, loud
  failures, per-plugin opt-in (off by default), lifecycle states, error
  isolation, settings namespacing, dependency ordering — plus
  `Rot13TransformPlugin` (text transform). Capabilities still unserved:
  `FileTransfer`, `Assistant`, `Export` (fail loudly, honestly).
- **Brush presets, stickers, styled sticky notes, item rotation (Phase 13)**:
  8 named brush presets persisting selection; 16 emoji stickers that are
  draggable/resizable/rotatable and persist in the DB; sticky notes with rounded
  corners/shadows/pin accents in 5 colors; rotation math unit-tested
  (`CanvasItemRotationMathTest`, 8 tests).
- **Painting features (Phase 7)**: stroke stabilizer, pressure remap curves
  (applied to real MotionEvents via `pointerInteropFilter` — no reflection),
  symmetry mirroring, color-harmony swatches, paper textures, WebP export.
- **Performance (Phase 8)**: PBKDF2 off the main thread; bounded bitmap decodes;
  IO-thread image loading; committed-stroke page/layer bitmap caching with
  hash-keyed invalidation; `BitmapPool`, `JankStatsHelper`, thermal-status
  fallback all wired.

### Fixed
- **Data-loss / restore hardening (Phase 2 + 9)**: corrupt/wrong-key vault opens
  are quarantined (`*.corrupt-<ts>`, bytes preserved) with a dedicated recovery
  screen instead of auto-delete; failed restores validate the backup password
  before closing the DB and reopen it on failure; `note_versions` title/extract
  fields now re-encrypted; WAL checkpoint fully steps its cursor.
- **Dead/fake features removed (Phase 3 honesty)**: libmypaint C++ stub and the
  whole engine-selector stack, handwriting-recognition dictionary stub,
  misnamed in-memory FTS search, dead `baseline-prof.txt` all deleted (configs
  for R8/AGSL/FLAG_SECURE verified). Shape auto-snap is now genuinely wired
  (geometry-fit gated, excludes wet/GLOW tools).
- **WebDAV menu no-op (Phase 14)**: see Added above.
- **Security polish (Phase 14)**: fixed a hex-redaction regex bug in
  `PrivacyCrashReporter` (`[a-fA-F0-0]` → `[a-fA-F0-9]`), capped DuckDuckGo
  response size (1 MB), removed raw `e.printStackTrace()`, stopped logging
  private absolute file paths for voice recordings and brush assets.

### Security posture (re-verified 2026-08-13, Phase 14 audit)
- `allowBackup=false` + `data_extraction_rules.xml` exclude everything from
  cloud backup and device transfer; no exported components besides the
  share-target `MainActivity`; `FLAG_SECURE` in non-debug builds; only
  `RECORD_AUDIO`, `USE_BIOMETRIC`, `USE_FINGERPRINT`, `INTERNET` permissions.
- PBKDF2WithHmacSHA256 600k iterations, AES-256-GCM (12-byte IV, 128-bit tag),
  AndroidKeyStore-wrapped DEK, in-memory zeroization on lock; all hero fields
  encrypted at rest; every clipboard write goes through `ClipboardGuard`.
- No secrets logged anywhere; release R8 minification on.

### Not done / honest notes
- Reference layer and persist→load→render round-trip tests for canvas items
  were requested in earlier prompts but were NEVER implemented — flagged in the
  Phase-14 audit. A `.nfb`/HTML file crossing devices is verified only by the
  protocol unit tests this phase (interop against a real LocalSend app must be
  confirmed on hardware before claiming it).
- Baseline profiles still not wired (deferred).
- Full audit is in `workspace/phase-14/AUDIT_REPORT.md`; release signing guide
  in `docs/RELEASE.md`.

## [1.0.0] - 2026-08-08

### Added
- **Release Engineering & CI Gates**: Configured environment-injected release signing in Gradle and full CI verification gates (`testDebugUnitTest`, `assembleRelease`, artifact uploads).
- **Room Database Schema Export**: Enabled `exportSchema = true` in `NoteflowDatabase` with version 7 schema tracking.
- **Privacy-First Crash Reporting**: Added `PrivacyCrashReporter` on-device logger with strict message sanitization and zero note content leakage.
- **Competitive Moat Features (Phase 26)**:
  - Handwriting-to-Text conversion removed pre-release (2026-08-13 Phase-03 honesty audit): the `HandwritingRecognitionService` was a dictionary-hash stub and `HandwritingToTextDialog` was unreachable. A real ML-Kit implementation is deferred pending approval.
  - Live Markdown Editor with Live Preview & Split View modes in `MarkdownPreviewScreen`
  - On-device Audio Transcription Engine for time-synced voice notes
  - WebDAV / Nextcloud End-to-End Encrypted Sync
  - Per-Note Version History with restore bottom sheet
  - Automatic Shape Straightening (Line, Rectangle, Ellipse, Arrow) in Ink Canvas
  - On-device Full-Text & OCR Search relabeled (2026-08-13): no FTS5/OCR; vault search runs over a cached, in-memory decrypted corpus in `NoteRepository`.
- **Play Store Readiness**: Published Privacy Policy (`docs/PRIVACY_POLICY.md`) and Data Safety declaration (`docs/DATA_SAFETY.md`).

### Fixed
- **Markdown Preview Text Duplication**: Resolved AST traversal bug causing duplicate paragraph text and missing word spaces in rendered Markdown previews.
- **Security & Cascading Deletes**: Fully wiped trashing and orphaned media embed files across database and storage layers.
