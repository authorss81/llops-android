# Phase 170 REPORT — Base-APK size: lingua `language-models/` corpus trimmed to the 24 used languages + release ABI splits

Date: 2026-08-19
Phase: `workspace/phase-170/PROMPT.md` (round-2 Kali triage fix; closes **Phase-32-NEW-01** MEDIUM + **Phase-32-NEW-02** LOW)

## 1. Task

Base-APK-size fix phase. Two APK-packaging findings confirmed live on the current
build by the phase-161 Kali round-2 triage (`docs/kali-report-round2.md` rows
`R2-KS-20` `R2-KS-22`) and the phase-32/159 audits:

- **Phase-32-NEW-01 (MEDIUM):** the base APK shipped lingua 1.2.2's ENTIRE
  75-language `language-models/` n-gram corpus (`language-models/` packed
  80,227,557 B / raw 207,608,234 B = 56% of the 142 MB release APK) even though
  `LanguageDetectionCore.SUPPORTED` only compiles a 24-language subset
  (`LanguageDetectionCore.kt:25-51`). Violates the AGENTS.md base-APK-size hard
  rule and the "lightweight pure-JVM plugins stay compile-time" carve-out.
- **Phase-32-NEW-02 (LOW):** no ABI splits — every device downloaded all four
  native ABIs (~55.4 MB packed).

Constraints honored: NO `.github/workflows/` edits; NO Room schema change; NO new
dependencies; offline-only detection kept; signing stays fail-closed (B1-PLAT-1);
`assembleDebug` behavior unchanged; no switch of language detection to a
downloadable plugin (that is documented as a possible leaner future follow-up, not
implemented here).

## 2. Part A — lingua corpus trimmed to the 24 used languages (REAL, no new deps)

### 2.1 Payload layout proven (not guessed)

Lingua 1.2.2 is a plain JAR (`com.github.pemistahl:lingua:1.2.2`,
`app/build.gradle.kts:264` = `implementation(libs.lingua)`; version
`gradle/libs.versions.toml:26` + library `:77`). `unzip -l` on
`~/.gradle/caches/modules-2/files-2.1/com.github.pemistahl/lingua/1.2.2/…/lingua-1.2.2.jar`
proves the layout: **75** directories `language-models/<iso>/` at the JAR ROOT,
each holding `{unigrams,bigrams,trigrams,quadrigrams,fivegrams}*.json`
(non-Latin scripts ship `unigrams.json` only), loaded at runtime via
classloader resources. In the packaged APK the same relative paths appear at the
APK root (verified: apktool showed `unknown/language-models/` in phase-32; this
run re-verified via `zipinfo -1`). Because AGP merges opaque-JAR resources
through `mergeDebugJavaResource`/`mergeReleaseJavaResource`, AGP's
`packaging.resources.excludes` exactly governs those entries —
`ParsedPackagingOptions.getAction -> PackagingFileAction.EXCLUDE`
(`com.android.build.gradle.internal.packaging.ParsedPackagingOptions`,
`getAction`/`compileGlob`, consumed by `MergeJavaResourcesDelegate$run$inputFilter$1`)
returns EXCLUDE for patterns compiled by `compileGlob`; `**` matches recursively.
So a `language-models/<iso>/**` glob strips exactly one unused language dir.
**The trim IS reachable by build-config alone** — the package task then verified this
empirically (section 2.3); no fallback approach needed.

### 2.2 The exclude list

24 kept = `LanguageDetectionCore.SUPPORTED` keys
(`app/src/main/kotlin/com/authorss81/noteflow/plugins/langdetect/LanguageDetectionCore.kt:25-51`):
`en de fr es it pt nl pl ru uk tr sv da nb fi cs hu ro hi zh ja ko ar el`.

51 trimmed = the full 75-language corpus minus those 24, computed from the actual
lingua JAR directory listing:
`af az be bg bn bs ca cy eo et eu fa ga gu he hr hy id is ka kk la lg lt lv mi mk
mn mr ms nn pa sk sl sn so sq sr st sw ta te th tl tn ts ur vi xh yo zu`.

Wired into `app/build.gradle.kts`:

- `app/build.gradle.kts:13-19` — `private val LINGUA_UNUSED_LANGUAGE_ISOS` (the 51
  ISO codes; full audit comment at `:3-12`).
- `app/build.gradle.kts:120-127` — inside `android { packaging { } }`:
  `resources { excludes += LINGUA_UNUSED_LANGUAGE_ISOS.map { "language-models/$it/**" } }`
  (`:125-126`). The kept 24 dirs are NOT excluded and are byte-identical to the
  lingua JAR entries, so `LanguageDetectorBuilder.fromLanguages(subset)`
  (`LanguageDetectionCore.kt:52-58`) loads the exact same bytes as before.

Anti-drift pin (pure-JVM, no APK needed):
`app/src/test/java/com/authorss81/noteflow/Phase170LinguaTrimTest.kt` —
(1) pins `SUPPORTED` to exactly the 24 codes with non-blank display names;
(2) asserts every SUPPORTED code still resolves in lingua's corpus (a future
lingua upgrade that drops a language fails loudly); (3) asserts
`LINGUA_UNUSED_LANGUAGE_ISOS` == 75-corpus minus SUPPORTED (51, no overlap/gap);
(4) asserts `packaging.resources.excludes` derives from that list, forbids a
blanket `language-models/**` exclude, and never globs a SUPPORTED language. So the
exclude list and the code cannot drift.

### 2.3 Measured bite (HARD DONE GATE evidence)

Byte counts from `python3 zipfile`/`zipinfo`/`ls` on built artifacts:

| Metric (language-models `language-models/`) | BEFORE | AFTER | Δ |
|---|---|---|---|
| dirs (ISO-639-1) | 75 | **24** | −51 |
| files | 327 | 104 | −223 |
| raw bytes | 207,608,234 | 88,241,733 | −119,366,501 |
| packed bytes | 80,227,557 | 34,791,639 | −45,435,918 |

| Android artifact | BEFORE | AFTER | Δ |
|---|---|---|---|
| debug `app-debug.apk` | 174,184,662 B (measured 2026-08-19 pre-change) | **128,717,267 B** (`b07b4cd2…`) | **−45,467,395 B** |
| release universal (all 4 ABIs, R8) | 142.0 MB (phase-32 audit, SHA `d7cdbebe…`; `R2-KS-20` re-measured LM raw 207,608,234 on the current build) | `app-universal-release.apk` **96,878,100 B** (`e551ac59…`) | −45.1 MB |
| release ABI split `arm64-v8a` | — (no split existed) | `app-arm64-v8a-release.apk` 55,528,871 B (`d1fea4a7…`) | — |
| release ABI split `armeabi-v7a` | — | `app-armeabi-v7a-release.apk` 53,312,803 B (`9c4f24e8…`) | — |
| release ABI split `x86` | — | `app-x86-release.apk` 56,276,419 B (`e13221e3…`) | — |
| release ABI split `x86_64` | — | `app-x86_64-release.apk` 56,169,569 B (`418f9a37…`) | — |

The debug/release `language-models` payload is the same resource set regardless of
R8, so the LM delta (−45,435,918 B packed) transfers 1:1; the release universal
dropped from 142.0 MB to 96,878,100 B exactly as expected. The 51 unused languages
are PROVEN gone in every produced APK (`zipinfo -1 … | grep -oE '^language-models/[a-z]+/'
| sort -u` lists exactly the 24 SUPPORTED codes). The kept 24 dirs are
byte-identical to their lingua-JAR originals (per-file `file_size`/`compress_size`
in the trimmed APK match the pre-trim APK's kept entries exactly:
104 files, raw 88,241,733, packed 34,791,639 in both).

Note on the incremental-packager: the FIRST post-change `packageDebug` produced a
174,167,139 B APK with the 51 dirs already gone but ~45 MB of zero-fill holes left
in place of the removed entries (AGP's incremental packager keeps removed-entry byte
regions; measured via `header_offset` gap analysis). A clean package regeneration
(delete `build/intermediates/incremental` + `build/outputs/apk`, i.e. what a fresh
CI checkout does) yields the honest 128,717,267 B. Shipped APKs are always clean;
report the clean-package numbers.

### 2.4 Runtime behavior preserved

- `LanguageDetectionTest` (English/German detection on real ≥20-char paragraphs,
  too-short gate, auto-tag override semantics) stays green — the detector only ever
  built the 24-language `subset` and now finds exactly those models in the APK.
- Offline-only: unchanged (`LanguageDetectionCore.detectLanguage`, no network).
- No new dependency; the only edits are `app/build.gradle.kts` (build-configure) +
  the new test.

## 3. Part B — ABI splits (release packaging, Phase-32-NEW-02)

### 3.1 Config

`app/build.gradle.kts:140-147`: `splits { abi { … } }` (design comment `:130-139`) with `isEnable` gated on the
requested task list containing a release-variant task
(`gradle.startParameter.taskNames.any { it.substringAfterLast(':').lowercase().contains("release") }`,
`:142`), `reset()` + explicit `include("arm64-v8a", "armeabi-v7a", "x86", "x86_64")`
(`:143-144`), `isUniversalApk = true` (`:145`).

Rationale for the gate: the legacy `splits` DSL is NOT build-type-aware (it is a
single `android.splits` object, `com.android.build.api.dsl.Splits`), so enabling it
unconditionally would also split debug output. Gating on the requested task list
keeps `gradle assembleDebug` producing the single monolithic `app-debug.apk` exactly
as before (verified: debug output unchanged shape, single APK) while `gradle
assembleRelease` splits. Edge case documented: one invocation requesting BOTH
`assembleDebug` and `assembleRelease` would also split debug (harmless, DP-PKAPK still
valid; the CI workflow runs them separately).

ABI set decision: keep all FOUR ABIs — every ABI currently ships the same 6 native
libs (`libandroidx.graphics.path.so`, `libgraphics-core.so`, `libink.so`,
`libmlkit_google_ocr_pipeline.so`, `libsqlcipher.so`, `libtranslate_jni.so`);
x86/x86_64 are dead weight on phones but are real consumer test-beds (emulators).
No ABI silently dropped.

### 3.2 Universal-Apk decision (explicit)

`isUniversalApk = true`. The distribution channel is self-hosted GitHub release +
manual sideload (no Play Console / bundletool), and emulators/x86 test-beds need a
full-fat build. The universal APK (`app-universal-release.apk`, 96,878,100 B)
therefore REMAINS published. Phase-32-NEW-02 is **fully addressed** for the 4
per-ABI download channels and **partially** for any channel that serves the
universal APK — a device installing the universal still downloads all 4 ABI libs
(~55 MB native packed). Recommendation for a future Play onboard: publish the AAB
(`bundleRelease`) and drop the universal from distribution. `docs/RELEASE.md`
Artifacts table + signature-verification snippet updated accordingly.

### 3.3 Outputs, signed and verified (HARD gate evidence)

`gradle assembleRelease` (R8 minify ON, real keystore via
`KEYSTORE_FILE`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/`KEY_PASSWORD`) produced 5 APKs in
`app/build/outputs/apk/release/` — see sizes/hashes above. EVERY one passes
`apksigner verify --verbose` (`/usr/local/lib/android/sdk/build-tools/37.0.0/apksigner`):

```
Verifies
Verified using v2 scheme (APK Signature Scheme v2): true
```
(`app-{arm64-v8a,armeabi-v7a,x86,x86_64,universal}-release.apk`, all 5). Signer is the
REAL release identity (`CN=InkFlow Release …`, SHA-256 `69636edb…c50196`), NOT the
Android debug key — the B1-PLAT-1 fail-closed `releaseConfig`
(`app/build.gradle.kts:54-77`; bound to the release variant at `:87-94`, R8 at
`:88`) is intact: with a missing keystore the build still fails at
`validateSigningRelease` rather than emitting a debug-signed artifact, and splitting
did NOT weaken that (each split carries the same signing config). Per-ABI native
payloads measured: arm64-v8a 14,058,151 B /
armeabi-v7a 11,842,058 B / x86 14,805,773 B / x86_64 14,698,884 B packed (from
59,512,557 B for all four in the monolith).

## 4. Verification

```
gradle assembleDebug    # green (single monolithic debug APK, no splits)
gradle assembleRelease  # green — 4 ABI splits + universal, all signed, R8 ON
gradle testDebugUnitTest# app suite green except:
                        #   Phase148UiFailureTextScrubTest "scrubForUi… UNC path"
                        #     (documented PRE-EXISTING, reproduced on a clean stash in
                        #      phases 149/153/158/166 — untouched by this diff)
                        # plus the documented WikiLinkParserCacheUnitTest
                        #   cancellation-timing flake (failed once in the full run,
                        #   passed on every isolated re-run — pre-existing, untouched)
```

`Phase170LinguaTrimTest` (5 tests) + `LanguageDetectionTest` (11 tests) green.
`B1Plat01ReleaseSigningTest` green (its build-file/docs assertions still hold).

## 5. Files changed (with `file:line` anchors)

- `app/build.gradle.kts` — `LINGUA_UNUSED_LANGUAGE_ISOS` `:13-19`; lingua-trim
  `packaging.resources.excludes` `:120-127`; release ABI `splits { abi { } }` `:140-147`.
- `app/src/test/java/com/authorss81/noteflow/Phase170LinguaTrimTest.kt` — NEW
  anti-drift pins (SUPPORTED == 24, corpus-minus-SUPPORTED == the build list,
  packaging-glob coverage, no blanket/SUPPORTED globs) + full-corpus constant.
- `docs/RELEASE.md` — Artifacts table + signature verification updated for the
  split/universal artifact set.
- `docs/security-report.md` — Phase-32-NEW-01/NEW-02 status → `FIXED` (phase-170);
  top-risk summary note updated.
- `docs/phase-status.md` — phase-170 row → `DONE`.
- `docs/ARCHITECTURE.md` — "Implemented in phase-170" note in Build/CI essentials.
- `workspace/phase-170/REPORT.md` — this report.

No `.github/workflows/` change. No Room/migration. No new dependency. No behavior
change to debug builds or to the runtime of the 24-language detector.

## 6. Out of scope / documented follow-ups (for user decision, NOT implemented)

- A leaner future step could rebuild a half-size lingua model JAR (24 languages
  only) — unnecessary today since the packaging glob achieves the same shipped size
  with zero binary changes.
- Distributing the LLM / ML-Kit-native payloads as signature-verified downloadable
  plugins remains a separate AGENTS.md item; the OCR/translate JNI `.so`s
  (singleton per ABI) are orthogonal to this phase and were deliberately NOT moved.