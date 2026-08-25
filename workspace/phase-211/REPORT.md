# Phase 211 — Release Hygiene: shrink the Ink keep, drop dead deps, delete dead font, Compose metrics, Gradle flags [BUILD]

Date: 2026-08-25 · Runner: linux (Gradle 8.13 system install, no wrapper jar in repo)

## Scope

Five build/release-hygiene wins after phases 170/175/176/199. No app-logic
change beyond deleting one never-referenced font accessor; no schema change;
`.github/workflows/` untouched; B1-PLAT-1 fail-closed release signing intact.

**Method:** BEFORE baseline builds (clean tree) → all edits → AFTER builds →
`usage.txt` diff → full unit suite ×2 → configuration-cache hazard matrix.
Throwaway keytool keystore at `/tmp/opencode/p211/`
(`CN=InkFlow Phase211 Throwaway`, never committed — `B1Plat01ReleaseSigningTest`
walks the tree for `*.keystore`; CI releases stay on the workflow secret).

## 1. Blanket `-keep class androidx.ink.** { *; }` REMOVED

`app/proguard-rules.pro` previously kept every member of all 5 ink artifacts
unshrunk and unobfuscated. Evidence that removal is safe:

- **The ink libraries declare themselves shrink-safe.** Extracted
  `proguard.txt` from every ink AAR 1.0.0:
  `ink-geometry`, `ink-strokes`, `ink-authoring`, `ink-brush`,
  `ink-rendering` all ship exactly
  *"Intentionally empty proguard rules to indicate this library is safe to
  shrink"*. Only `ink-nativeloader` carries real rules (precise
  `@UsedByNative` JNI keeps) — those AAR consumer rules still apply
  automatically; nothing was lost by deleting our blanket.
- **No reflective ink access:** grep over `app/src/main/kotlin` shows exactly
  seven compiled ink entrypoints (`Brush`, `BrushFamily`, `InputToolType`,
  `StockBrushes`, `CanvasStrokeRenderer`, `MutableStrokeInputBatch`,
  `Stroke`). Post-shrink mapping check: **all 7 retained**, now OBFUSCATED
  (e.g. `androidx.ink.strokes.Stroke -> androidx.ink.strokes.c`) instead of
  name-pinned wholesale.
- **R8 green with zero missing-class errors** (clean `assembleRelease`).
- The app does not Parcel ink types anywhere (strokes persist through its own
  AES-GCM-encrypted `pointsJson` codec), so no Parcelable keep is needed.
  **No keep rule was re-added — see §6.**

Result: 1,047 additional ink members stripped vs BEFORE
(`/tmp` diff saved as `workspace/phase-211/usage-diff.txt`);
`mapping.txt` androidx.ink mentions 7,746 → 5,984.

Stale `-keep com.google.protobuf.** { *; }` + `-dontwarn` also deleted:
the MediaPipe tasks-genai engine left the base APK in phase-29/175, and the
BEFORE/AFTER usage diff contains **zero protobuf lines either direction** —
the rule matched literally nothing (kept nothing, warned about nothing).

## 2. Three declared-but-unused dependencies REMOVED

Grep re-verified before deletion (zero source references in any module):

| Dep | Was | Proof of dead weight |
|---|---|---|
| `androidx.navigation:navigation-compose` | `app/build.gradle.kts` :299 | no `NavHost`/`rememberNavController`/`androidx.navigation` import anywhere |
| `io.coil-kt:coil-compose` | :332 | no `AsyncImage`/Coil call site anywhere |
| `material3-window-size-class` | :297 | app rolls its own `WindowSizeCategory` (`MainActivity.kt:83`) |

Removed together with: catalog entries + versions (`navigationCompose`,
`coil`, 3 library rows), BOTH mavenCentral `includeGroupByRegex("io\\.coil.*")`
lines in `settings.gradle.kts`, the matching `CentralAllowlist.groups` entry
(the three lists cannot drift — pinned by `B2Deps03DependencyVerificationTest`
+ `Phase146BuildIntegrityTest`, both still green), and 11 stale component
blocks from `gradle/verification-metadata.xml` (5 `androidx.navigation:*`,
4 `io.coil-kt:*`, 2 `material3-window-size-class*`) plus the now-unused
`io.coil-kt` trusted-key and `androidx.navigation` trusting refs.
Lockfile note: Gradle's `--write-verification-metadata` PRESERVES entries it
didn't resolve during regeneration (verified empirically — a regen rewrite was
byte-identical to the committed file), so the prune had to be done deliberately;
strict verification then proven by `gradle --refresh-dependencies assembleDebug`
green (605 → 594 components; anything still needed would have failed loudly).

Release bytes ≈ unchanged by this alone (R8 already strip'd unused deps);
debug APK −1.15 MB (see §7 table), faster minify input, smaller verification
surface.

## 3. Dead font asset DELETED (−221 KB raw / ~115 KB packed)

`res/font/lora_italic.ttf` (221,232 bytes) was referenced ONLY by
`AppFonts.SerifItalic` (`theme/Fonts.kt:66`), itself referenced nowhere.
Deleted both (serif italic = synthetic `FontStyle.Italic` on `lora.ttf` if
ever wanted). Verified shipping evidence: BEFORE release APK carried
`res/8c.ttf` = 221,232 bytes (resource-obfuscated name); AFTER ships only two
TTFs (`212196` lora + `176288` plus-jakarta). Same file gone from debug APK.

## 4. Compose compiler observability (opt-in) + audit

New `composeCompiler {}` block in `app/build.gradle.kts`: metrics/report
destinations are set ONLY under `-Pinkflow.composeMetrics` (deliberate opt-in,
not a variant switch — ordinary builds compile byte-for-byte unchanged):

```
gradle :app:compileDebugKotlin -Pinkflow.composeMetrics --rerun
# → app/build/compose-compiler-{reports,metrics}/app_debug-*
```

Audit of the one report pass (committed tooling; reports NOT committed):
153 composables, **140 skippable (92%)**; ALL HomeScreen / EditorScreen /
AnnotationCanvas composables are skippable/restartable. The god-ViewModel IS
unstable per the classes report (Lazy delegates, KeySetView fields) but
**zero composables take `NoteflowViewModel` as a parameter**, so its
instability propagates into no skip decision. The 13 non-skippable names are
one-shot value-returning helpers (`overflowMenuScrollModifier`,
`secureDialogProperties`, `rememberGlassBlurDecision`, LocalSend dialog
bodies, …) whose cost is negligible; "stabilizing" them would touch UI paths
for no measurable win. **Decision: no code change — documented here for a
follow-up phase if profiling ever shows recomposition cost there.**

## 5. Gradle flags

`gradle.properties`:

| Flag | Status | Evidence |
|---|---|---|
| `org.gradle.parallel=true` | added | multi-module build (:app/:plugin-sdk/:plugins/*) configures/executes in parallel; green everywhere |
| `org.gradle.caching=true` | added | task outputs normal; `113 actionable tasks: 113 up-to-date` reuse seen |
| `android.nonFinalResIds=true` | flipped from false | no `getIdentifier()`/dynamic resource lookup (re-grepped; cf. shrinkResources safety note `app/build.gradle.kts`), Kotlin-only modules so no Java `switch` on R constants. Visible effect: ~2,375 R-field removals newly recorded in usage.txt |
| `org.gradle.configuration-cache=true` | added LAST, kept | hazard matrix below |

### Configuration-cache validation matrix

| Hazard | Result under CC |
|---|---|
| B1-PLAT-1 gate (`taskGraph.whenReady`) | INTACT. With keystore env unset: CC entry correctly invalidated ("environment variable 'KEYSTORE_FILE' has changed") and build refused verbatim: *"Release build refused: no release keystore configured (B1-PLAT-1)…"*; with env restored: green again. Env vars are CC inputs, so the gate can never be served stale |
| splits DSL reads `startParameter.taskNames` (:192) | CORRECT. Task names are part of the CC entry key: `assembleDebug` stores/reuses a no-split entry (single `app-debug.apk`); `assembleRelease` gets its own entry producing all 5 split APKs; repeated runs log "Configuration cache entry reused." |
| Paparazzi plugin | RESOLVES. `:app:verifyPaparazziDebug --dry-run` computes the full task graph under CC without error |

CC kept ON.

## 6. Keep-rule re-add decision: NONE

Per DoD, line-by-line justification for re-adding nothing:

- *Parcelable CREATOR (ink `Stroke` etc.)* — not needed: app never Parcels ink
  types (own encrypted JSON codec), and ink's own consumer rules govern the
  library surface; R8 emitted no missing-member errors.
- *Ink JNI bridge* — already covered precisely by `ink-nativeloader`'s bundled
  `@UsedByNative` conditional keeps (extracted + reviewed, §1).
- *protobuf* — proven matched-nothing by the empty BEFORE/AFTER usage delta.
- Existing scoped Gson keeps (`data.model/**`, plugin wire DTOs, localsend,
  hosted-manifest) untouched — unrelated to ink and still required by their
  own phase-199 rationale.

## 7. Build gates + APK sizes

All builds run with B1-PLAT-1 env set (throwaway keystore, outside the tree).

| Artifact | BEFORE (bytes) | AFTER (bytes) | Δ |
|---|---|---|---|
| app-universal-release.apk | 50,679,572 | 50,319,866 | **−359,706** |
| app-arm64-v8a-release.apk | 42,257,599 | 41,897,893 | −359,706 |
| app-armeabi-v7a-release.apk | 41,718,607 | 41,358,901 | −359,706 |
| app-x86-release.apk | 42,340,751 | 41,981,045 | −359,706 |
| app-x86_64-release.apk | 42,514,799 | 42,155,093 | −359,706 |
| monolithic debug APK | 79,215,572¹ | 78,005,308 | **−1,210,264** |

¹ captured as `app-universal-debug.apk` under a combined invocation
(splits activate when ANY requested task matches "release"); comparable — both
are the all-ABI artifact.

Every split shrank by EXACTLY 359,706 bytes (deterministic deltas: font
~115 KB packed + unkept-ink dex + dep metadata).

- `gradle assembleRelease` GREEN (×3 across the phase incl. CC trial);
  `apksigner verify`: Verifies, v2=true, v3=true, v1/v3.1/v4=false — the
  phase-171 scheme shape, signed by this run's throwaway key.
- `gradle assembleDebug` GREEN (×5 incl. refresh-dependencies + CC runs).
- `gradle testDebugUnitTest` on final tree, full suite ×2:
  **3006 completed, 3 failed — all three pre-existing/environmental**
  (documented since phase-149/176): `Phase148UiFailureTextScrubTest` UNC-path;
  `PaparazziSmokeTest rendersLightTheme/DarkTheme` layoutlib-env. One run
  additionally hit `Phase151MarkdownMainThreadPerfTest` — the known
  timing/concurrency flake (passes in isolation, verified; absent on rerun).
  Count 3006 ≥ prior phases (2943 at phase-208) — "count unchanged or +".

## Artifacts

- `workspace/phase-211/usage-before.txt` (103,105 lines),
  `usage-after.txt` (102,748), `usage-diff.txt` (11,099 diff lines;
  5,204 removed / 4,847 added — ink members newly stripped, protobuf absent,
  nonFinalResIds R-field churn).

## Honest caveats / deferred

- No device/emulator on this runner: runtime ink rendering under the shrunk
  library is proven by reachability (retained+obfuscated entrypoints), R8
  success, and the libraries' own shrink-safety declaration — but not by an
  on-device stroke session. Same caveat class as phases 170/175/176.
- Compose stability follow-up candidates listed in §4 are intentionally NOT
  actioned (no measurable win, UI-path risk).
- CI archival of compose reports (like mapping archival) remains a workflow
  edit gated on user approval.
