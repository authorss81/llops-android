# Phase 176 — Release packaging hygiene: exclude debug/dev artifacts + retain R8 mapping (R2-KS-27 LOW + R2-KS-24 INFO)

Date: 2026-08-24 · Commit: see git log `llops: phase-176` (this repo, main)

## Scope

Kali round-2 static analysis (`docs/kali-report-round2.md` rows R2-KS-27 LOW,
R2-KS-24 INFO) flagged two release-packaging hygiene issues. This phase is
packaging-only: no app logic, no schema change, no new dependencies, no
workflow edits.

**History note (honesty):** a prior bot run (commit `55d3695`) added the
exclude line to `app/build.gradle.kts` and marked `.done` WITHOUT any of the
required verification; the maintainer deleted `.done` (commit `a0b7f2b`) and
this run redid the phase properly — BEFORE/AFTER payload builds, mapping
round-trip + idempotency proof, apksigner verification, extractNativeLibs
measurement, and this report.

## 1. R2-KS-27 — what actually ships, and the fix

### 1a. Artifact origins verified empirically (BEFORE build)

Method: temporarily disabled the excludes line → `gradle clean assembleRelease`
with a throwaway keytool keystore (B1-PLAT-1 env vars, keystore in `/tmp`,
never committed) → `unzip -l` of every produced APK.

BEFORE payload (`app-universal-release.apk`, excludes disabled), grep over all
598 entries:

```
$ unzip -l app-universal-release.apk | awk '{print $4}' | sort | grep -iE "DebugProbesKt\.bin|kotlin-tooling-metadata|firebase-"
DebugProbesKt.bin
kotlin-tooling-metadata.json
```

- **`DebugProbesKt.bin`** — ships at APK ROOT in ALL 5 splits. Origin: it is a
  root-level resource bundled inside `kotlinx-coroutines-core-jvm:1.8.1`
  itself (`unzip -l ~/.gradle/caches/.../kotlinx-coroutines-core-jvm-1.8.1.jar`
  → `1738 bytes ... DebugProbesKt.bin`). The prompt's hypothesis that it comes
  from a removable on-device "debugAgent" dependency is DISPROVEN:
  `gradle :app:dependencies --configuration releaseRuntimeClasspath` contains
  NO `kotlinx-coroutines-debug`. It is part of a REQUIRED runtime dep, so the
  only correct removal is packaging-level exclusion.
- **`kotlin-tooling-metadata.json`** — ships at APK ROOT in all 5 splits. Not
  present in ANY dependency jar in the Gradle cache (exhaustive jar scan); its
  content identifies it as BUILD-INJECTED by the Kotlin Gradle plugin
  (`"buildPlugin": "...KotlinAndroidPluginWrapper"`, `"buildPluginVersion":
  "2.0.21"`, `"buildSystemVersion": "8.13"`). Never used at runtime.
- **`firebase-*.properties`** — NONE ship anywhere in the current base-APK
  payload (phase-175 removed ML Kit → Firebase left the closure entirely). The
  exclude glob is kept as defense-in-depth against a future dependency
  re-introducing them, and this report documents that it matches nothing today
  (no false claim of removing existing bytes).

### 1b. Fix (already-committed line retained, now with origin evidence)

`app/build.gradle.kts` `packaging { resources { excludes += listOf(
"DebugProbesKt.bin", "kotlin-tooling-metadata.json", "firebase-*.properties")
} }` — comment block expanded with the verified origins above.

### 1c. AFTER payload (committed config, clean build)

```
app-arm64-v8a-release.apk:    target-artifacts=0   size=42,424,077
app-armeabi-v7a-release.apk:  target-artifacts=0   size=41,885,085
app-universal-release.apk:    target-artifacts=0   size=50,846,050
app-x86-release.apk:          target-artifacts=0   size=42,507,229
app-x86_64-release.apk:       target-artifacts=0   size=42,681,277
```

Full-path diff BEFORE → AFTER (universal): exactly two lines removed, nothing
else changed —

```
6d5      < DebugProbesKt.bin
105d103  < kotlin-tooling-metadata.json
```

### 1d. okhttp3/ org/ dirs

Left untouched per the prompt — real runtime dependencies (R2-KS-27's bloat
note only).

## 2. R2-KS-24 — R8 mapping retention

- `mapping.txt` IS produced by `assembleRelease` at
  `app/build/outputs/mapping/release/mapping.txt` (84,840,019 bytes) plus
  `configuration.txt` / `seeds.txt` / `usage.txt`. No `-printmapping` directive
  exists or was added — AGP writes the map there by default when
  `isMinifyEnabled = true`.
- **Idempotency:** two consecutive CLEAN release builds of the same tree
  produce byte-identical maps:
  - build #1: `sha256 b76afe6cd94341add1081d13454a11daeda4248ab9282f73e1f2bf550bad45e7`
  - build #2 (after `clean`): identical hash
  - final rebuild after the extractNativeLibs measurement round-trip: identical hash
- **Round-trip evidence:** the literal symbol from the phase-160 Kali pass
  (`w2/C3694b0`) NO LONGER EXISTS in current mappings — R8 renames shift across
  code versions (the `w2` package now holds
  `CommandPaletteMath* -> w2.g/w2.a/...`). This is precisely why per-release
  retention matters. Round-trip demonstrated with today's map instead:

```
$ grep "EncryptionService ->" app/build/outputs/mapping/release/mapping.txt
com.authorss81.noteflow.services.EncryptionService -> v2.l0:        # forward
$ sed -n '299489p' mapping.txt                                       # reverse lookup v2.l0
com.authorss81.noteflow.services.EncryptionService -> v2.l0:
```

  i.e. an obfuscated artifact name back-maps to its source class (the
  AndroidKeyStore DEK crypto service that R2-KS-15 attributed to the old
  `w2/C3694b0.java`).
- **Retention policy:** mapping.txt stays OUT of git (secrets-adjacent, runner
  only). `docs/RELEASE.md` now documents the path, determinism, and the
  **deferred CI task**: archiving `mapping/release/` per release needs a
  workflow edit (upload-artifact step) which is gated on user approval — noted
  in RELEASE.md, NOT implemented here.

## 3. extractNativeLibs decision (R2-KS-28 noise) — KEEP `true`

Measured both ways on otherwise-identical clean release builds (throwaway
keystore, same commit):

| APK | extractNativeLibs="true" (current) | extractNativeLibs="false" | Δ download |
|---|---|---|---|
| universal | 50,846,050 | 64,578,578 | **+13.73 MB (+27%)** |
| arm64-v8a | 42,424,077 | 46,242,761 | +3.82 MB |
| armeabi-v7a | 41,885,085 | 44,009,105 | +2.12 MB |
| x86 | 42,507,229 | 46,109,737 | +3.60 MB |
| x86_64 | 42,681,277 | 46,833,789 | +4.15 MB |

`false` stores every `.so` uncompressed (`unzip -v`: `libsqlcipher.so` Defl:N
57% → Stored 0%), growing EVERY download while re-exposing the documented
Phase-31 Part C2 SDK-36 16KB-page dlopen crash. Decision: keep `true`.

**Finding fixed along the way:** the operative control for extraction is the
manifest attribute `android:extractNativeLibs="true"`
(`AndroidManifest.xml:14`), which OVERRIDES `jniLibs.useLegacyPackaging` when
they disagree — flipping ONLY the DSL flag to `false` produced byte-identical
APKs (proven before flipping the manifest too). The stale Phase-31 comment
("useLegacyPackaging makes AGP emit extractNativeLibs") was corrected;
both sites stay `true`.

## 4. Signature verification

All five rebuilt APKs pass `apksigner verify` (build-tools 36.0.0) with the
phase-171 scheme shape — `Verifies`, **v2 = true, v3 = true**, v1/v3.1/v4 =
false — signed by the throwaway local keystore (`CN=InkFlow Phase176
Throwaway`, created for this run only, stored under `/tmp/opencode/p176/`,
NEVER committed). CI releases remain signed by the workflow secret keystore
(fail-closed B1-PLAT-1 untouched).

## 5. Build/test gates

- `gradle assembleRelease` green ×4 during evidence gathering (final state =
  committed config).
- `gradle assembleDebug` green.
- `gradle testDebugUnitTest`: 2566 tests, 3 failed — ALL pre-existing /
  environmental, none touched by this phase:
  - `Phase148UiFailureTextScrubTest` — the long-documented UNC-path failure.
  - `PaparazziSmokeTest rendersLightTheme/DarkTheme` — proven pre-existing on
    THIS RUNNER via `git stash` → same 2 failures on clean HEAD (paparazzi
    layoutlib environment issue from phase-195's test-only plugin; unrelated to
    packaging).
- No new dependencies, no schema change, `.github/workflows/` untouched,
  `allowBackup` untouched, base-APK-size rule intact (payload got SMALLER).

## Deferred / out of scope

- CI archival of `mapping/release/` (workflow edit → user approval required).
  Documented in `docs/RELEASE.md`.
