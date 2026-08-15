# Phase 46 — B1-AUTH-01 (HIGH): plugin bytecode can reach the vault DEK in-process — FIXED

- **Date:** 2026-08-15
- **Finding:** `B1-AUTH-01` — *Downloadable plugin bytecode executes with the app classloader as parent, letting a signature-verified (or leaked-signer) artifact resolve `VaultKeyHolder`/`SecurityService` and read the vault DEK in-process* (HIGH)
- **Scope:** one finding per phase (tight diff). No DB schema change, no migration, no new dependencies, `.github/workflows/` untouched.

## Root cause (before)

1. `AppClassLoaderFactory.create` (`services/AppClassLoaderFactory.kt:22-28`) handed the plugin `DexClassLoader` the **app classloader as its DIRECT parent**. The JVM/ART parent-first rule means plugin bytecode could `loadClass`/resolve ANY base-app class the app could — there was no security boundary between plugin code and the vault.
2. `RuntimePluginLoader.load` (`plugins/runtime/RuntimePluginLoader.kt:94-121`) materialized that bytecode with `getDeclaredConstructor().newInstance()`; the `PluginContext` capability facade (`plugin-sdk/.../PluginContext.kt:27-29`) is handed to the plugin, but the "never reach for DB/keystore" rule was **documentation, not a boundary**.
3. Proof of exploit: a compromised or leaked-signing-key artifact can execute
   `val dek = com.authorss81.noteflow.services.VaultKeyHolder.dek ?: com.authorss81.noteflow.services.SecurityService(c).getOrCreateDek()` and exfiltrate the vault DEK — the plugin whitelist (`PluginManager.kt:117-126`) never constrained class resolution.

## What changed (after) — `file:line`

Two independent, required layers close the finding. Either alone is insufficient: the classloader sandbox stops *resolution*, the static scan stops *content that mentions the forbidden surface*.

### 1. Runtime sandbox — `plugins/runtime/PluginFrameworkClassLoader.kt` (new)

- Pure-JVM `java.lang.ClassLoader` that sits **between** the plugin loader and the app classloader.
- `isAppPrivateForbidden(name)` (:70-71): any `com.authorss81.noteflow.*` class NOT under the sanctioned `com.authorss81.noteflow.plugins.*` framework surface ⇒ `ClassNotFoundException`. Fail-closed: a future app package is refused as loudly as the known secret-bearing ones (`services`, `data`, `ui`, `theme`, `utils`, activity root).
- `loadClass` (:45-54): non-app namespaces (`java.*`, `javax.*`, `android.*`, `kotlin.*`, `kotlinx.*`, third-party coordinates) still delegate to the app classloader, so platform/JDK/stdio/coroutines stay resolvable.
- Reflection reach-through is closed by the same check: `Class.forName("...")` from plugin code resolves through the plugin's own loader chain, lands here, and is refused identically.

### 2. Production wiring — `services/AppClassLoaderFactory.kt` (:34)

- `DexClassLoader(artifactPath, optimizedDirectory, null, PluginFrameworkClassLoader(parent))` — the plugin DEX parent is now the scoped loader, never the raw app classloader. KDoc updated with the B1-AUTH-01 explanation.

### 3. Install/verify-time content gate — `plugins/runtime/ArtifactStaticScan.kt` (new)

- `ArtifactStaticScan.scan(file)` (:73-112) opens the interface-only artifact as a JAR and inspects every entry — bounded memory, never throws (an unreadable archive returns `Pass` so the signature/identity gates keep their single clear error).
  - `.class` entries: parsed **structurally** via `ClassFileReferenceExtractor` (constant pool `CONSTANT_Class` names + every `CONSTANT_Utf8` string literal) — exact, so a benign plugin can never false-positive, and a `Class.forName("...")` literal is caught with equal certainty (:262-296).
  - `*.dex` entries: parsed structurally via `DexStringExtractor` (full DEX `string_ids` + `type_ids` tables) (:347-411).
  - every other entry (descriptors, manifests, native blobs): raw byte-substring search, chunked with tail-preservation for split patterns (:148-170).
- Rejected patterns:
  1. **App-private package prefixes** — slash + dot spellings of `services/`, `data/`, `ui/`, `theme/`, `utils/` (:186-197);
  2. **Bare sensitive class names** — `VaultKeyHolder`, `EncryptionService`, `NoteflowDatabase`, `SettingsManager`, `NoteRepository`, `SecurityService` anywhere (covers concatenated `Class.forName(pkg + "X")` pastes) (:201-208);
  3. **Raw network egress** — exact canonical `java.net`/`javax.net.ssl` socket/connection primitives (`Socket`, `HttpURLConnection`, `URLConnection`, `URL`, `InetAddress`, `SocketChannel`, `SSLSocket`, …). A descriptor `L...;` is stripped before the set lookup, so `URLClassLoader` does not alias `java/net/URL` (:213-226). Network MUST flow through the host facade's `httpGet`, never plugin-owned sockets.

### 4. Single choke point — `plugins/runtime/ArtifactSignatureVerifier.kt` (:76-81)

- `verify()` now runs `ArtifactStaticScan` **after the SHA-256 digest check, before the signer certificate is parsed**; `Rejected` ⇒ `Result.Invalid("...plugin static security scan...")`. Every plugin-bytecode path funnels through `verify()`: install (`DownloadablePluginInstaller`), **every** load re-verify (`SignatureVerifiedPluginRuntime`), update (`PluginUpdateEngine`), rollback.

### 5. Pure-JVM tests — `app/src/test/java/com/authorss81/noteflow/PluginBytecodeIsolationTest.kt` (new, 15 tests)

- `PluginFrameworkClassLoader` refuses `services.VaultKeyHolder`/`services.SecurityService`/`data.db.NoteflowDatabase`/`ui.viewmodel.NoteflowViewModel`/`utils.ConstantTime`/`MainActivity` while `plugins.*` + platform/JDK classes resolve (:72-103);
- `Class.forName` reach-through refused identically (:105-117); a jar's own classes + the framework surface load (:119-130);
- static scan rejects hostile app-private unmarshalled bytecode (:132-142), raw network egress (:144-154), accepts the whitelisted plugin (:156-164), catches a smuggled resource entry (:166-176), parses a hostile DEX (:178-209) and a clean DEX (:211-224);
- load-time sandbox: a `VaultKeyHolder`-touching plugin **fails to load under the scoped parent** (:238-255); the CONTROL proves the same hostile bytecode genuinely runs under the pre-fix raw parent — the boundary matters (:257-273);
- full runtime (`verify` → `load`) refuses the hostile artifact before any code materializes (:275-296);
- a whitelisted capability plugin loads **and executes** through the sandbox (`transformText("hello") == "white:HELLO"`) with its injected facade, and source-level wiring pins prevent a refactor from silently dropping either gate (:299-349).
- Fixtures: `RecordingHost` (facade), `HostileVaultPlugin` (:413-434 — the B1-AUTH-01 exploit on the TEST source set only, never in the APK), `HostileNetworkPlugin` (:437-455), `TestDexBuilder` (structurally valid minimal DEX; string offsets file-absolute per the DEX spec) (:463-513).
- Benign fixture: `com.authorss81.testplugins.WhitelistedPlugin` (new file) — lives OUTSIDE the app's private namespace like a real plugin, so the sandbox resolves it and its own inner classes; it references only the `plugins.*` surface + `android.content.Context`.

## Verification output

- `gradle :app:testDebugUnitTest` → **996 tests, 0 failures, 0 errors** (94 suites; was 981 before this diff — 15 new).
  - Existing plugin/runtime suites all still green: `PluginFrameworkTest`, `PluginExecutionSimulationTest`, `DownloadablePluginRuntimeTest`, `PluginUpdateEngineTest`, `ArtifactSignatureVerifierTest` — the benign signed test jars pass the new scan gate; the tamper/wrong-key/missing-signature tests still fail with their ORIGINAL (unchanged) error messages.
  - Debugging notes (kept for the next reader): a fresh `kotlin.collections.List`/`Set` `loadClass` fails inside this Gradle test worker's loader chain (an environment quirk, unrelated to the sandbox — Kotlin classes resolve through the exact same delegation rule as every other non-app namespace; the loader test asserts with `java.util.List`/`org.junit.Test` instead). Test artifacts only carry the main `.class` (no inner classes), so the whitelisted fixture lives outside the app namespace — under the sandbox a jar-loaded `TestDownloadablePlugin` legitimately cannot resolve its own `$Companion`, which is exactly the boundary under test.
- `gradle :app:assembleDebug --rerun-tasks` → **BUILD SUCCESSFUL** (1m 16s, 57 tasks executed from scratch). (Note: one earlier `assembleDebug` invocation aborted — a transient packaging/daemon flake; the identical build completed successfully on the next run, including a forced `--rerun-tasks` rebuild.)
- No new test dependencies; `TestArtifactBuilder` machinery reused unchanged.

## Checksums / secrets handling

- No new secrets. No keys/passwords/decrypted note content are logged anywhere in this diff.
- The scan inspects bytecode/resource CONTENT only; a plugin mentioning `VaultKeyHolder` is rejected — the DEK itself is never read, loaded, or logged.
- `allowBackup=false` (data-extraction rules) and FLAG_SECURE untouched.

## API / hardware floor (API 26+)

- `PluginFrameworkClassLoader` is a plain `java.lang.ClassLoader` (API 1+); `ArtifactStaticScan`/its extractors are pure `java.io`/JAR parsing (API 1+). No AGSL, no dynamic color, no new platform calls. No fallback or notice is required for older/lower-end devices.

## Judgements (out-of-scope, documented only)

- **Process execution is a separate boundary.** `java/lang/Runtime`/`ProcessBuilder` are NOT in the scan's rejection table and survive in a verified artifact; a future isolation phase should gate them too (e.g. refuse shell-exec classes in the scan or move plugin execution to a separate `:remote` process). The DEK/vault reachability that is THIS finding's scope is closed: even with exec, a plugin without `VaultKeyHolder`/`SecurityService` etc. cannot resolve the vault classes to read the DEK.
- **In-process sandbox, not a separate OS process.** The prompt's "run in a separate `:remote` process" option was evaluated and rejected for this phase: it is a disproportionate architectural change for the current plugin surface, and the classloader boundary + content gate already close the reported in-process DEK reachability. A future hardware-isolation phase is noted in `docs/ARCHITECTURE.md`.
- **B1-AUTH-02 (phase-47):** the locked-open `NoteflowSqlcipherFactory.create` re-derivation path is NOT this phase's scope.
- **B1-AUTH-03 (phase-48):** the `onEnable` privileged hook running at every launch on the lock screen is a separate finding.
- **B1-CRYPTO-01 / B1-NET-03** (manifest pin, compile-time import pins): already fixed in phases 39/42 — the pin chain that a hostile artifact must defeat to even reach the classloader surfaced in this report's file:line anchors remains intact.

## Related-new-notes

- The capability facade is now an enforceable boundary, not a convention: any plugin class that even MENTIONS a secret-bearing type is rejected before any bytecode materializes, and any class that survives could not resolve those types anyway.
- Benign third-party plugin code (own package, only `plugins.*` + platform imports) is fully unaffected — proven end-to-end by the whitelisted load-and-execute test through a full `verify` → `load` → capability call.

## Files touched

```
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/PluginFrameworkClassLoader.kt   (new)
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/ArtifactStaticScan.kt           (new)
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/ArtifactSignatureVerifier.kt
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/RuntimePluginLoader.kt          (KDoc)
app/src/main/kotlin/com/authorss81/noteflow/plugins/runtime/SignatureVerifiedPluginRuntime.kt (KDoc)
app/src/main/kotlin/com/authorss81/noteflow/services/AppClassLoaderFactory.kt
app/src/test/java/com/authorss81/noteflow/PluginBytecodeIsolationTest.kt                   (new)
app/src/test/java/com/authorss81/testplugins/WhitelistedPlugin.kt                          (new)
docs/ARCHITECTURE.md
docs/phase-status.md
docs/security-report.md
```