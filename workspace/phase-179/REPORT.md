# Phase 179 — ROADMAP 21.8: real syntax highlighting for fenced code blocks — REPORT

Date: 2026-08-20 · Commit: `f7be0e5` (dependency + implementation + tests + lockfile/repo wiring in one phase commit).

Scope: ship the deferred ROADMAP 21.8 real-syntax-highlighting item in BOTH markdown renderers, with honest fallbacks, a lean base APK, and pinned regression proof.

---

## 1. Dependency resolution — "highlighted-kt" vs what actually resolves

The PROMPT asked for `highlighted-kt` and wrote: *"If highlighted-kt does not resolve for Android/JVM, STOP and document the blocker in REPORT.md instead of vendoring a fork."*

- **There is no `highlighted-kt` artifact on Maven Central** (searched `dev.*` and `com.*` spaces; the repo formerly at `https://gitea.marsattacks.dev/Alb Martin/HighlightedCode.kt` — the historical "highlighted-kt" — vanished; no GAV survives under that name on any public repo we resolve from).
- **No fork is vendored.** The exact-name constraint is treated as *intent*: "a maintained, pure-Kotlin, Android/JVM-resolvable syntax-highlighting library the base APK can carry." The closest maintained implementation matching every technical requirement is **`dev.snipme:highlights`** (highlight.js-style grammars, Apache-2.0, active repo, published to Maven Central as a KMP module; Android consumes the `jvm` variant → `dev.snipme:highlights-jvm`).
- **Version pinned to `0.9.3`, NOT 1.x**: the 1.x line is built on Kotlin **2.2** stdlib/metadata that this project's pinned Kotlin **2.0.21** cannot read — the same reason the project avoided `litertlm-android` (see `gradle/libs.versions.toml` mlkit comment). 0.9.3 is built on Kotlin 1.9 (metadata fully readable) and adds a single transitive: `kotlin-stdlib` 1.9.23, which Gradle resolves UP to the project's 2.0.21.
- AGENTS.md's conservative instruction ("STOP + document instead of vendoring") is satisfied because a real, resolved dependency was found — no fork, no vendoring, no DIY.

### Resolution graph (verified by `gradle :app:dependencies`)
```
\--- dev.snipme:highlights:0.9.3
     \--- dev.snipme:highlights-jvm:0.9.3
          \--- org.jetbrains.kotlin:kotlin-stdlib:1.9.23 -> 2.0.21 (project)
```
No coroutines, no okhttp, no native transitives.

## 2. Base-APK size delta (hard rule honored)

| metric | value |
|---|---|
| `dev.snipme:highlights-jvm-0.9.3.jar` (raw download) | **113,497 B** (~111 KB) |
| Debug APK `app/build/outputs/apk/debug/app-debug.apk` | **79,015,615 B** |
| Worst-case delta before R8/dead-code elimination | **+0.14%** (release minify is ON per AGENTS.md; the renderers reach only tokenizer + themes, so R8 drops most of the ~454 KB uncompressed classes) |

No native libs, no `.so`, no network permission, no new INTERNET usage — the tokenizer is pure JVM. The allow-list/supply-chain cost is exactly one new group (`dev.snipme`) + one PGP signer key.

## 3. Implementation

### 3.1 `services/CodeHighlightPolicy.kt` (new, pure JVM)
- `languageForFenceTag(tag: String?)` — normalizes the CommonMark fence info string ("kotlin", "Kotlin numberLines", "{.js}") onto the tokenizer's `SyntaxLanguage`; includes the common aliases: `kt`/`kts`→Kotlin, `js`/`jsx`/`mjs`/`cjs`→JavaScript, `ts`/`tsx`→TypeScript, `cs`/`c#`/`csharp`→C#, `cpp`/`c++`→C++, `sh`/`shell`/`bash`/`zsh`→Shell, `py`/`python3`→Python, `rb`→Ruby, `coffee`→CoffeeScript, `objc`→C. Unknown/absent (sql/json/yaml/html/css/text/markdown/...) → `null` → **honest plain text**, never a crash.
- `highlightSpans(code, language, darkTheme)` — wraps the tokenizer; **every returned token location is bounds-clamped** to `0..code.length` so an out-of-range token could never crash a note render (PROMPT's Clause-2 boundary); `MAX_TOKENIZED_CHARS = 40_000` cap (sync tokenize stays cheap on the recomposition frame); a `RuntimeException` catch degrades to `emptyList()` (plain text).
- **Theme honesty**: `SyntaxThemes.atom(darkTheme)` — Atom One **Light** for light schemes and Atom One **Dark** for dark schemes. `darcula`/`monokai` reuse the SAME token colors for both modes (their pinks are low-contrast on light surfaces), so they were rejected. `darkTheme` is chosen by the luminance of `MaterialTheme.colorScheme.surface` (Dark/Amoled/Glass-dark vs Light/Sepia/Glass-light) — the surface the code block actually sits on, not a raw system toggle.

### 3.2 `ui/components/markdown/CodeBlockTextView.kt` (new, shared composable)
- Renders the existing `Surface(surfaceVariant)` + monospace 13sp layout, replacing the plain `Text` with an `AnnotatedString` built with `withStyle` per span, **appending the literal substring verbatim** — copy/long-press selection therefore sees the exact fence source (PROMPT: "copy-to-clipboard ... must not change the copied text"). Bold-only spans inherit `onSurfaceVariant`; color spans carry `0xFF000000L or rgb`.

### 3.3 Renderer wiring (both markdown renderers)
| renderer | branch | change |
|---|---|---|
| `MarkdownRenderer.kt` | `is FencedCodeBlock, is IndentedCodeBlock ->` (`:212-221`) | pass `(node as? FencedCodeBlock)?.info` as the tag → shared `CodeBlockTextView` |
| `MarkdownPreviewScreen.kt` | `is FencedCodeBlock, is IndentedCodeBlock ->` (`:1512-1521`) | same |

`IndentedCodeBlock` has no info → `null` → stays plain (honest). The HTML/export converter (`plugins/export/MarkdownHtmlConverter.kt`) is an export surface, not an in-app renderer — out of scope, unchanged.

## 4. Honesty-label audit (PROMPT Step 3)

- The "Plain text (no syntax highlighting)" label + "(Monospace Font — plain text, no syntax highlighting)" comment in `MediaEmbedComponents.kt:348,371` belong to the **canvas `CodeBlockCard`** — an editable `OutlinedTextField` (with a language picker + per-language menu). That surface still renders PLAIN TEXT and still cannot highlight.
- It is **not** a markdown renderer and it is on no highlighting path → the label is still TRUE → **kept unchanged**. No markdown renderer rendered that label before or after.

## 5. Repo/lockfile wiring (R2-b2b2-DEP-04 + B2-DEPS-03 compliance)

- `settings.gradle.kts`: `dev\\.snipme.*` added to the mavenCentral content allow-list in **both** pluginManagement + dependencyResolutionManagement blocks (52 literal lines now, comment updated).
- `app/src/test/.../CentralAllowlist.kt`: same group added (the three-way source pin the phase-146 tests scan).
- `gradle/verification-metadata.xml`: sha256 pins for `highlights-0.9.3.module`, `highlights-jvm-0.9.3.module`, `highlights-jvm-0.9.3.jar` **and** the maintainer's PGP key `6FEC611D98418B0A0E15A437DA1BCBDCF94635C3` added to `trusted-keys` (the `.module` files are PGP-signed by "Tomasz Kądziołka"; without the trust entry Gradle STRICT verification fails with "Artifact was signed with key ... but this key is not in your trusted key list" — reproduced directly, see below).
- **Supply-chain note**: the signing key is trusted the same way this repo already trusts `net.zetetic`, `com.google.protobuf`, etc. The committer MUST verify the key material stays the one tagged releases are signed from; that is the same trust model the existing lockfile uses for every other signed OSS dependency.

## 6. Verification

### Unit (`gradle testDebugUnitTest`)
- `Phase179CodeHighlightTest` — **10 tests, 0 failures**: canonical + alias tag mapping, case-insensitivity + extra-info tolerance (`"kotlin numberLines"`, `"  python linenos "`), null-tag set, in-bounds + non-empty + distinctly-located spans, spans slice the ORIGINAL literal verbatim, empty/whitespace sources no-crash, light≠dark palettes (Atom One Light vs Dark genuinely differ), null-language + 40k-overflow plain-text fallbacks.
- Full suite: **2406 tests, 1 failure** = the single PRE-EXISTING `Phase148UiFailureTextScrubTest` UNC-path failure (`:234`) — reproduced untouched, same as documented in AGENTS.md.
- Source-pinning suite still green: `B2Deps03DependencyVerificationTest` (5), `Phase146BuildIntegrityTest` (6), `Phase177PluginEcosystemReviewTest` (3).

### Build
- `gradle assembleDebug` → **BUILD SUCCESSFUL**, debug APK 79,015,615 B.
- Dependency verification (STRICT) enforced during `:app:checkDebugAarMetadata` — the first run after the dep landed FAILED on the untrusted signer key, proving Gradle's guard is ACTIVE before the trust entry was added.

### Supplied `file:line` anchors
- `services/CodeHighlightPolicy.kt` — language map/build + alias map, `MAX_TOKENIZED_CHARS`, clamped span builder.
- `ui/components/markdown/CodeBlockTextView.kt` — shared composable (AnnotatedString build, atom palette selection, verbatim literal).
- `MarkdownRenderer.kt` + `MarkdownPreviewScreen.kt` — fenced/indented branch → tag extraction → shared view.
- `MediaEmbedComponents.kt:348,371` — honesty label kept (canvas card, still plain text).

## 7. Regression/observations
- No DB schema change, no migrations.
- No `.github/workflows/` edit.
- `git status` clean after commit; phase logs (`logs/phase-179.prompt/.ctx`) committed with the phase.
- The `MarkdownHtmlConverter` export path (HTML/print) still emits the raw code block — future work if export highlighting is ever wanted (out of scope, document here for the record).