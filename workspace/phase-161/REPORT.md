# Phase 161 Report — Kali round-2 triage → generate next phases

- **Date:** 2026-08-19
- **Trigger:** `workspace/phase-161/PROMPT.md` — triage `docs/kali-report-round2.md`
  and generate the next pipeline phases after the highest existing `phase-NN`.

## Outcome at a glance

- **`docs/kali-report-round2.md` contains NO Kali findings** (27 lines): it
  carries ONLY the phase-159 release-APK target metadata (filename, commit
  `a9d8918c…`, versionCode 2 / versionName 1.0.0, applicationId, APK SHA-256
  `54feb16c…455a`, 142,339,635 B, R8 ON, v2 signing, signer cert `69636edb…`).
  The Kali dynamic pass (phase-160) was **BLOCKED**: `phase-160/.blocked` +
  `.no_work` + 3 attempts + timeout 360 — no rooted Android device/emulator is
  available on the CI runner, so no dynamic findings were ever produced.
- **Therefore there is nothing in the Kali file to bucket/triage per the
  prompt's Step 1.** Instead this phase:
  1. Re-verified the genuinely OPEN round-1 APK packaging findings that are
     STILL live on the phase-159 release binary and have no fix phase:
     **Phase-32-NEW-01 (MEDIUM, lingua 80.2 MB pack)** and **Phase-32-NEW-02
     (LOW, no ABI splits)** → fix phase **170**; **Phase-32-NEW-03 (INFO, v2-only
     signing)** and **Phase-32-NEW-04 (INFO, plugin manifest cert-pin
     placeholder)** → fix phase **171**.
  2. Closed the round-2 source-audit cross-check: **all 43 R2 findings were
     already triaged by phase-119 into phases 129-158** (none unmapped). The one
     still-open row, `R2-b2b2-DEP-01` (CI action pinning), remains
     `PENDING USER APPROVAL` (workflow edit) — already owned by the phase-119
     table; not re-duplicated here.
  3. Generated **5 new phases** numbered 170-174 (max existing = 169).
  4. Updated `workspace/PHASES.md` + `docs/phase-status.md`.

## Triage table (finding → fix phase)

| Finding | Severity | Verdict | Action |
|---|---|---|---|
| `docs/kali-report-round2.md` (no findings — APK metadata only) | — | phase-160 `.blocked` (no rooted env) | Documented; dynamic pass re-deferred to operator with a device. Static re-review done here. |
| Phase-32-NEW-01 (lingua `language-models/` 80.2 MB → 24 of 75 languages used) | MEDIUM | STILL OPEN on phase-159 APK | **phase-170** Part A |
| Phase-32-NEW-02 (no ABI splits) | LOW | STILL OPEN | **phase-170** Part B |
| Phase-32-NEW-03 (v2-only signing, no v3) | INFO | STILL OPEN (fail-closed intact) | **phase-171** Part A |
| Phase-32-NEW-04 (plugin-manifest cert-pin placeholder) | INFO | STILL OPEN (fail-closed by design) | **phase-171** Part B + operator runbook |
| R2-b2b2-DEP-01 (19× `uses:` unpinned in workflows) | MEDIUM | Already triaged by phase-119 → CI pinning docs/dependabot shipped; WORKFLOW EDIT pending user approval | Reference existing phase; NOT re-duplicated |
| R2 round-2 source audit (43 findings: 12 MEDIUM / 26 LOW / 5 INFO) | — | All mapped to phases 129-158 by phase-119 | `resolved at triage` via phase-119 table; ph-134/135 `.done` verified |
| B1-PLAT-1 (debug keystore) | — | FIXED (phase-57 + phase-159 verified real keystore, fail-closed NE-01 test) | `resolved at triage` |

## New phases generated (after the pre-seeded 162-169; max = 169)

| Phase | Kind | Scope |
|---|---|---|
| phase-170 | FIX | Base-APK size: strip unused lingua `language-models/` to the 24 used languages (AGP `packaging.resources.excludes`) + ABI-split release APKs. Real anchors: `app/build.gradle.kts:219` (`implementation(libs.lingua)`), `gradle/libs.versions.toml:26` (`lingua = "1.2.2"`), `plugins/langdetect/LanguageDetectionCore.kt` (`SUPPORTED` 24-lang map + `fromLanguages`), `LanguageDetectionEngine.kt:43-49`. DoD: debug + minified release build green; `language-models/` payload drops ~80 MB packed → ~used subset; before/after byte counts in REPORT; SUPPORTED-set pin test; `apksigner verify` passes on every split output. No new deps. |
| phase-171 | FIX | Release signing v3 (NEW-03) + plugin-update operator runbook + fail-closed `PinnedCertHash.matches` test (NEW-04). Real anchors: `app/build.gradle.kts` signing block `:133-153`, `B1Plat01ReleaseSigningTest`, `plugins/runtime/HostedPluginManifest.kt` (`PLUGIN_MANIFEST_CERT_PIN` ≈ `:220`), `PinnedCertHash.kt`, `CompileTimePluginPinStore.kt`, `docs/RELEASE.md`. DoD: `apksigner` shows v2+v3 true; signing stays fail-closed; runbook with exact pin-substitution commands; pin-test proves placeholder/wrong pins fail and known-good passes. No placeholder substitution (no real host certs yet). |
| phase-172 | FEATURE (UI/UX) | Editor/canvas productivity, 3 related: (1) persistent recent colors + favorites (now `remember`-only at `EditorScreen.kt:3595-3605` → `SettingsManager`-backed `ColorRecentsPolicy`, cap/dedupe/test), (2) minimap zoom-to-fit + jump-home quick actions in `AnnotationCanvas.kt` over `MinimapGeometryPolicy` (pure-JVM `CanvasNavigationPolicy`, motion-aware), (3) layer blend-mode + opacity presets wired to the existing layer update path (`EditorScreen.kt:5298-5304`). |
| phase-173 | FEATURE (plugin) | Plugin ecosystem, 3 related: (1) serve the last real unserved capability `FileTransfer` via the EXISTING LocalSend v2.2 sender (`services/localsend/LocalSendSender.kt`), human-consent kept, opt-in default OFF, pure-JVM routing test; `PluginCapabilityDirectory` moves FileTransfer to served (Assistant-only until LLM plugin), (2) bounded persisted invocation journal (`PluginInvocationJournal.kt`, reuse phase-148 scrub), (3) honest per-plugin store metadata line (compile-time vs downloadable + size + needs-channel). |
| phase-174 | FEATURE (UI/UX) | Reading & authoring UX, 3 related: (1) note stats bar (word count · reading time · chars) from `TextToolsAnalyzer.kt:26-42` — no full re-tokenize per keystroke; (2) outline quick-jump rail in reader mode reusing `OutlineGeneratorCore` (pure-JVM `HeadingScrollIndex`), (3) `[[` wikilink autocomplete + slash-menu "Insert wiki-link" (bounded via `WikiLinkParser`, safe under lock, titles-only, no log leakage). |

Bundling rationale: fix phases bundle 2-3 findings of the same releaseengineering theme (packaging for 170, signing/channel for 171); feature phases bundle 3 related flows per prompt Step 2 (UI/UX + plugin buckets covered; knowledge-graph power-ups deliberately NOT duplicated — phase-154 already owns them).

## UI/UX and plugin idea list (surfaced, not all used)

- Persistent recent colors / favorites (→ phase-172)
- Minimap quick-nav (zoom-to-fit / home) (→ phase-172)
- Layer blend/opacity presets (→ phase-172)
- FileTransfer over the existing LocalSend stack (→ phase-173)
- Plugin invocation journal + store metadata honesty (→ phase-173)
- Note stats + outline quick-jump + `[[` autocomplete (→ phase-174)
- Glass-theme consistency pass, command-palette plugin quick actions (already in
  phase-132) and empty-state work (already in phase-156) = **NOT re-generated**.
- Knowledge-graph power-ups = phase-154 (already scheduled) = **NOT re-generated**.

## Files changed in this phase

- `workspace/phase-170/PROMPT.md` + `.timeout`
- `workspace/phase-171/PROMPT.md` + `.timeout`
- `workspace/phase-172/PROMPT.md` + `.timeout`
- `workspace/phase-173/PROMPT.md` + `.timeout`
- `workspace/phase-174/PROMPT.md` + `.timeout`
- `workspace/PHASES.md` (5 new rows)
- `docs/phase-status.md` (phase-160 `BLOCKED`, phase-161 `DONE`, phases 170-174 `NOT STARTED`; stale "no blocked markers" fact corrected)
- `workspace/phase-161/REPORT.md` (this file)

No app code was changed (planning-only phase). No `.github/workflows/` edits.
Numbering is sequential 170-174, exactly `max(169)+1..`.