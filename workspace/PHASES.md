# LLOPS — InkFlow Android App — Phase Plan

This repo builds **InkFlow** (a.k.a. Noteflow), an offline-first, encrypted
notes + digital painting canvas Android app, entirely via the LLOPS auto-pipeline
(opencode agents running in GitHub Actions, cron-driven every 30 min).

The InkFlow source (from `inkflow (1).zip`) is the base. Phases 1-35 of the
app's own ROADMAP.md are largely done; this plan is the AUDIT-DRIVEN CONTINUATION
based on 3 batches of strict subagent audits (security, UX, false-implementations,
painting engine, free features).

## Phase list

| Phase | Goal | Source |
|-------|------|--------|
| phase-01 | App scaffold [DONE] | scaffold |
| phase-02 | **Security: fix restore/sync data-loss** [PARTIAL] (C1/H3/H4 shipped; H1/H2 completed in phase-09) | Batch 1 security audit |
| phase-03 | **Honesty: remove/wire dead & fake features** [DONE] | Batch 1 honesty audit |
| phase-04 | **Real AGSL watercolor/oil** [DONE] (marker-only commit 22cc99c; real impl in 7854551) | Batch 1 graphics audit |
| phase-05 | **UX/accessibility quick wins** [DONE] | Batch 1 UX audit |
| phase-06 | **WebDAV sync made real+safe+honest** [DONE] | Batch 1 security audit |
| phase-07 | **Free painting features** [DONE] (reference layer not shipped) | Batch 1 feature audit |
| phase-08+ | Pending Batch 2/3 verification findings [phase-08 DONE, phase-09 PARTIAL, phase-10/11/12 DONE, phase-13 PARTIAL, phase-14/15/16 DONE, phase-17 CANCELLED, phase-18/19 DONE, phase-20 DONE, phase-21/22/23/24/25/26/27/28/29 DONE, phase-30 DONE, phase-31 DONE, phase-32 DONE, phase-33 DONE, phase-34 DONE, phase-35 DONE, phase-36 DONE, phase-37 DONE, phase-38 DONE — see docs/phase-status.md] | Batch 2/3 |
| phase-39..phase-114 | Security-fix pipeline (B1/B2 findings; every phase DONE except phase-77 = NOT STARTED, false `.done` removed 2026-08-17) | Batch 1/2 security audits |
| phase-115 | **Final doc-fix — status & consistency sweep (this phase)** | Batch 2/3 |
| phase-116 | Round-2 Security Audit (full source review) | Round-2 audit |
| phase-117 | **Build Release APK + verify it assembles without error** (feeds phase-118) | Round-2 audit |
| phase-118 | Kali full-environment dynamic pentest on the phase-117 APK | Round-2 audit |
| phase-119 | Round-2 Triage → generate new phases | Round-2 audit |
| phase-120..133 | Pre-seeded user-requested UI/UX feature phases (added 2026-08-17 commit `7a4b6e6`, renumbered by `c3a1789`; run before 134+ per numeric order) | user requests |
| phase-134..153 | Round-2 fix phases (all 43 findings bundled 2-3 per phase by area/root cause; generated 2026-08-17 by phase-119) | Round-2 audit |
| phase-154..158 | Round-2 feature phases (2-3 related features each, incl. UI/UX + plugin ideas; generated 2026-08-17 by phase-119) | Round-2 audit |
| phase-159 | **Build Release APK + verify it assembles without error** (feeds phase-160) | Kali redo |
| phase-160 | Kali STATIC security analysis of the phase-159 APK (jadx/apktool/MobSF; dynamic checks declared DEFERRED - no rooted device on runner) → `docs/kali-report-round2.md` | Kali redo |
| phase-161 | Triage the Kali round-2 report → generate new phases | Kali redo |
| phase-162 | Build & code failure fixes (dup methods, deprecated project.exec, trusted-artifacts) + verify decryption-safety edge cases | build/code hardening |
| phase-163 | "Don't show again" must persist for data-recovery screens (per-event, not per-launch) | user-reported |
| phase-164 | Tag vault scoped to current notebook only (not all notebooks) | user-reported |
| phase-165 | Beautify Gallery page thumbnails (Material 3 card design) | user-reported |
| phase-166 | Buttons/text fit screens & cards — wrap or shorten (paging arrows) | user-reported |
| phase-167 | Bottom nav bar no longer overlays messages & calendar (dynamic padding/insets) + triage Kali report → generate next phases (no dupes with 167-174) — `DONE` (see `workspace/phase-167/REPORT.md` + `docs/phase-status.md`) | user-reported |
| phase-168 | App opens the last-used notebook on cold start | user-reported |
| phase-169 | Fix post-export "Unreadable (decryption failed)" + keep fail-closed UX | user-reported |
| phase-170 | **Base-APK size: strip unused lingua `language-models/` (24 of 75 languages) + ABI splits** (Phase-32-NEW-01 MEDIUM + NEW-02 LOW) | phase-161 Kali triage |
| phase-171 | **Release signing v3 + plugin-channel operator runbook + fail-closed pin test** (Phase-32-NEW-03 + NEW-04 INFO) | phase-161 Kali triage |
| phase-172 | Feature: editor & canvas productivity — persistent color/brush recents + favorites, minimap zoom-to-fit & jump-home, layer blend/opacity presets | phase-161 UI/UX bucket |
| phase-173 | Feature: plugin ecosystem — serve FileTransfer over LocalSend + invocation journal + honest store metadata | phase-161 plugin bucket |
| phase-174 | Feature: reading & authoring UX — note stats bar, outline quick-jump in reader mode, wiki-link autocomplete + slash-menu insert | phase-161 UI/UX bucket |
| phase-175 | **Move ML Kit OCR + translation OUT of the base APK into the signature-verified downloadable-plugin runtime** (R2-KS-21 MEDIUM — `mlkit-google-ocr-models/` assets + OCR/translate JNI libs + `translate_models_metadata.json` in payload; base-APK-size hard constraint; 170 covers lingua/ABI only, this is the dedicated ML Kit move-out) | phase-167 Kali triage |
| phase-176 | **Release packaging hygiene: exclude `DebugProbesKt.bin` / `kotlin-tooling-metadata.json` / `firebase-*.properties` from the release APK + retain R8 `mapping.txt` for forensic back-mapping** (R2-KS-27 LOW + R2-KS-24 INFO; one area; workflow archival deferred — no `.github/workflows/` edits) | phase-167 Kali triage |
| phase-177 | **Plugin ecosystem full review** — verify off-by-default, accurate on/off state in store/settings, enable/disable/delete correctness, delete-confirmation dialog on every path, invocation journal honesty + regression proof (all plugin tests green) | user-reported |
| phase-178 | **Reference-image layer** - insert a dimmed, non-inking photo underlay on the canvas (ROADMAP Phase-07 encouraged item), persisted per page, excluded from back-save/share, path-confined | user-reported |
| phase-179 | **Real syntax highlighting for code blocks** via highlighted-kt (ROADMAP 21.8 deferred item, user-approved new dependency) in both markdown renderers, theme-aware, copy stays raw | user-reported |
| phase-181 | **Re-fix: last-used notebook must open after app start AND after export/Home return** (phase-168 regression still reported) | user-reported |
| phase-182 | **Re-fix: note titles must NOT become "Unreadable (decryption failed)" after export/Home return** (phase-169 regression still reported) | user-reported |
| phase-183 | **GalleryView typography & title wrapping** - no mid-word breaks, strip `.md`, 2-line ellipsis | user-reported |
| phase-184 | **GalleryView card proportions** - balanced notebook tile, no dead empty space from rigid 10:16 ratio | user-reported |
| phase-185 | **GalleryView preview scrubber** - strip raw Markdown syntax from card snippets (pure-JVM policy) | user-reported |
| phase-186 | **GalleryView quick actions** - pinned badge + Pin/Unpin, Edit Tags, Move to Trash via shared ViewModel path | user-reported |
| phase-187 | **GalleryView ink-note paper look** - ruled/dot-grid notebook texture instead of generic placeholder | user-reported |
| phase-188 | **GalleryView robustness** - no stroke rasterization, large-font-safe layout, dark-theme border, tag-chip cap +N | user-reported |
| phase-189 | **Fix: Backup-to-file and Backup-from-file FAIL after a vault export** - export leaves the in-memory session (key/DB handle/notebook state) degraded; restore must be side-effect-free + next backup/restore works with the same key | user-reported |
| phase-190 | **APK self-update** - uploading an APK of the SAME app (same package + signer, newer version) must update the installed app end-to-end; add missing package-identity gate, fix install launch, keep B1-PLAT-7 trust gates | user-reported |
| phase-192 | **Fix: voice recording shows "The recording could not be saved securely" and never saves** - VoiceNoteManager save path fails (DEK null / blob dir missing / cipher error); make it save for passwordless + password vaults, fail closed only on real lock | user-reported |
| phase-193 | **Hide resize handles on code blocks / sticky notes / photo attachments until dragging** - corner resize symbols only visible while dragging/resizing, still resizable from item body | user-reported |
| phase-194 | **Dim Undo/Redo buttons on canvas when stack is empty** - disabled/dimmed appearance when nothing to undo/redo, bright when available | user-reported |
| phase-195 | **Screenshot render suite** - Paparazzi (JVM, no emulator) PNGs for every screen x state x theme, saved to `visual-qa/screenshots/` curated baseline + documented artifact-upload path | user-reported |
| phase-203 | **Symmetry = capture-time drawing aid** - strokes drawn while mirror is ON persist a real mirrored twin; toggling ON/OFF never adds/removes/changes existing content (kills the render-time global mirror flip-flop) | user-reported |
| phase-204 | **Silent data-loss batch** - voice recording orphaned on rotation; legacy migration readTextHead swallows IO errors → overwrites good column with "" + deletes source; start-fresh proceeds on failed renames; media pickers silent no-op (4-agent audit 2026-08-24) | audit |
| phase-205 | **Canvas commit integrity** - stroke-commit race (lost/out-of-order/resurrected strokes), LASER fade 25 Hz poll driving undo+Room churn, AGSL reflection silent fallback → direct setRenderEffect (audit) | audit |
| phase-206 | **Kill perpetual pollers** - Choreographer frame pump leaks & stacks per editor open, auto-lock 1 s poll runs when OFF, voice playback 20 Hz StateFlow, lockout 1 Hz ticker → event-driven/coarse schedules (audit) | audit |
| phase-207 | **Crypto/DB efficiency** - memoize page decryption vs Room table-level fan-out re-decrypting all rows per keystroke save; lazy search-corpus invalidation w/ hash reuse; BitmapPool byte budget + clear-on-lock (audit) | audit |
| phase-208 | **Page management UX** - CRITICAL: Trash-tab search renders live notes as trash cards (permanent-delete risk); sort control; Move-to-section + Duplicate UI for existing backend; multi-select bulk actions; palm-rejection persisted + surfaced (audit) | audit |
| phase-209 | **Search quality & discovery** - recent-search chips, shared pure-JVM fuzzy/subsequence tier in VaultSearchPolicy + CommandPaletteMath, Plugin Store deep-links from empty capability menus + palette action (audit) | audit |
| phase-210 | **Knowledge graph depth** - neighborhood focus via the imported-but-unused GraphSubgraphFilter, search auto-pan/cycle, TalkBack semantics overlay for nodes + Backlinks sheet entry (audit) | audit |
| phase-211 | **Release hygiene** - remove blanket androidx.ink keep + stale protobuf keep, drop dead deps (navigation-compose/coil/window-size-class), delete unused lora_italic.ttf (221 KB), Compose compiler metrics, gradle parallel/cache/nonFinalResIds (+config-cache trial) (audit) | audit |
| phase-212 | **JVM test hardening** - first tests for OrphanImportCleanupPolicy (deletes files!), ShapeRecognitionHelper (runs every freehand commit), 6 plugin stores, plugin installer/updater atomicity, HtmlToMarkdownConverter, wet-engine boundaries (audit) | audit |
| phase-213 | **Brush shading & drop shadows** — per-stroke soft shadows (vector + GPU blur) like pro drawing apps, theme-aware, low-end gated | user-requested |
| phase-214 | **Stroke smoothing v2** — pressure/tilt low-pass, historical MotionEvent batch, velocity-adaptive EWMA, optional One-Euro/spline fairing | user-requested |
| phase-215 | **Lasso stroke select** — repurpose dead SELECT tool into lasso + box marquee with pure-JVM hit policy and overlay | user-requested |
| phase-216 | **Lasso copy/duplicate + shape select & move** — clipboard, duplicate, delete, shape-aware move/translate for any selected strokes/shapes | user-requested |
| phase-217 | **Block resize polish** — markdown code-block handle, canvas embed dim-visible handles + aspect-lock for photos, voice block collapsed behavior | user-requested |
| phase-218 | **Markdown polish** — code block line-numbers + gutter + scroll, tables horizontal-scroll, task strikethrough, callout colors/icons, typography (no new deps) | user-requested |
| phase-219 | **Templates + pencil soft-shade** — per-template color/spacing/opacity controls + 2 new templates; `soft_shade` pencil preset for portrait shading (shade below eye etc.) + SMUDGE discoverability | user-requested |
| phase-220 | **Blender + dual-brush scatter** — `blenderStrength` → `mixStrength` and `scatterAmount` → spacing/scatter per-preset sliders | painting |
| phase-221 | **Fill bucket + gradient** — flood fill tolerance 12% on active layer bitmap + drag gradient (`color → gradientTo`) | painting |
| phase-222 | **Alpha-lock + clipping mask + tilt shading** — `DST_IN` mask for alpha-lock/clipping, tilt `0-90°` → width/alpha (`TiltShadingPolicy`) | painting |
| phase-223 | **Perspective grid + canvas rotate + ruler** — 1pt/2pt/isometric grid, `rotationZ` gesture, straight-line ruler snap | painting |
| phase-224 | **Timelapse replay** — timestamp-ordered replay `withFrameNanos` + `MediaCodec`/`MediaMuxer` MP4 export 720p30 | painting |
| phase-225 | **Eyedropper from reference image** — samples `referenceImage` bitmap / `LayerBitmapLruCache` pixel, applies to `currentColor` | painting |
| phase-226 | **Selection transform** — scale (corner handles) + rotate (`CanvasItemRotationMath`) for lasso selection, baked into points | painting |
| phase-227 | **Paper deckled edge + texture + layered export** — wavy `Path` clip, `paperTextureStrength` slider, PSD per-layer + PNG transparent toggle | painting |
| phase-228 | **Fix all failing tests + green APK/CI** — `testDebugUnitTest` 0 failures, `assembleDebug`/`assembleRelease` green, `lintDebug` 0 errors, full `gradle build` CI green, no regressions (6h) | quality |
| phase-240 | **Pinch/touch regression fixes** — two-finger pinch no longer rotates the page (`CanvasRotationPolicy` dead-zone 2° + zoom >3% / pan >12px dominance gates) and stroke dots land exactly on the touch (remove the double `canvasBoxWindowOffset` subtraction: `pointerInteropFilter` events are already node-local) — see `workspace/phase-240/REPORT.md`, status `docs/phase-status.md` [DONE] | user-requested |

## How phases run

- The workflow's cron (`*/30 * * * *`) wakes; `select-phase` picks the lowest
  phase-NN without a `.done` marker (or resumes a `.deferred` one).
- `phase_runner.sh` runs `opencode run --prompt <PROMPT.md>` on
  `opencode/deepseek-v4-flash-free` via OpenCode Zen. The agent edits code, runs
  `gradle assembleDebug` + `gradle testDebugUnitTest`, and the reviewer subagent
  verifies. Success → `.done` → next phase on the next tick. Rate limit →
  `.deferred` → retried by the next cron tick.
- Prompts must be self-contained and honest. "Definition of done" always includes
  a green build + unit tests. Phases must not change the Room DB schema, must not
  edit `.github/workflows/`, and must follow AGENTS.md hard rules.

## Writing a new phase

1. Create `workspace/phase-NN/` (zero-padded).
2. Add `PROMPT.md`: verified problem → exact fix → definition of done → constraints.
3. Push. The cron picks it up automatically.
