# Changelog - NoteFlow (Smooth Notes)

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.0.0] - 2026-08-08

### Added
- **Release Engineering & CI Gates**: Configured environment-injected release signing in Gradle and full CI verification gates (`testDebugUnitTest`, `assembleRelease`, artifact uploads).
- **Room Database Schema Export**: Enabled `exportSchema = true` in `NoteflowDatabase` with version 7 schema tracking.
- **Privacy-First Crash Reporting**: Added `PrivacyCrashReporter` on-device logger with strict message sanitization and zero note content leakage.
- **Competitive Moat Features (Phase 26)**:
  - Handwriting-to-Text conversion via `HandwritingRecognitionService`
  - Live Markdown Editor with Live Preview & Split View modes in `MarkdownPreviewScreen`
  - On-device Audio Transcription Engine for time-synced voice notes
  - WebDAV / Nextcloud End-to-End Encrypted Sync
  - Per-Note Version History with restore bottom sheet
  - Automatic Shape Straightening (Line, Rectangle, Ellipse, Arrow) in Ink Canvas
  - On-device Full-Text & OCR Search engine
- **Play Store Readiness**: Published Privacy Policy (`docs/PRIVACY_POLICY.md`) and Data Safety declaration (`docs/DATA_SAFETY.md`).

### Fixed
- **Markdown Preview Text Duplication**: Resolved AST traversal bug causing duplicate paragraph text and missing word spaces in rendered Markdown previews.
- **Security & Cascading Deletes**: Fully wiped trashing and orphaned media embed files across database and storage layers.
