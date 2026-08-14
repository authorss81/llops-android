# Phase 23: Downloadable-plugin runtime — download, verify, load [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a hardened plugin framework (Phases 10–11), a plugin store UI (Phase 21),
and a **hybrid plugin architecture skeleton** (Phase 22): `PluginEntry` (unified
entry for built-in + downloadable), `PluginContext` capability facade
(deny-by-default), `PluginRuntime` seams (`verify`/`load`/`update`/`rollback`
stubbed), `PluginVersion` semver. **Read `docs/plugin-architecture.md` first** — you
are implementing the skeleton's seams, not redesigning them.

**THE CORE GOAL:** heavy/native features (camera OCR/QR, large ML engines, LLM)
must be loadable as **downloadable, signature-verified plugins** WITHOUT growing
the base APK. This phase delivers the runtime that makes that safe.

## What to build (fills in the Phase-22 `PluginRuntime` seams)

1. **`PluginDownloader`** — downloads a plugin artifact (a signed plugin APK/AAR
   containing DEX) over HTTPS to app-private storage. TLS only. Only user-initiated
   (from the Phase-21 store). Size + free-space guards. Resume/cancel. Never to
   shared storage. Reports progress into the store's existing progress flow.
2. **Signature verification (MANDATORY, security-critical)** — before ANY plugin
   code is loaded, verify the downloaded artifact's signature against the pinned
   certificate hash embedded in the host app (`PluginEntry.pinnedCertHash` /
   compile-time constant, not user-editable). Reject with a clear error if the
   signature does not match the pinned hash or the artifact is tampered. Also
   verify `PluginEntry.sha256` matches the downloaded bytes. `file:line`-documented.
3. **`RuntimePluginLoader`** — loads the VERIFIED DEX at runtime via
   `DexClassLoader` + a plugin `ClassLoader`, instantiating plugins through the
   existing `NoteflowPlugin` interface via reflection. Keep the Phase-10/11
   registry API the same for compile-time plugins so existing plugins are
   untouched; downloadable plugins join the same registry as installable entries.
4. **Capability isolation** — plugin code NEVER receives direct handles to the
   Room DB, keystore, `EncryptionService`, or decrypted note content. Wire the
   Phase-22 `PluginContext` facade: per-capability whitelist grants only the
   configured calls (`insertText`, `showResult`, `httpGet(httpsOnly)`,
   `readSelection`, …); everything else stays `CapabilityDenied`. Test the
   deny-by-default contract.
5. **Consent + enablement** — first download requires explicit user consent with
   clear wording ("this plugin adds features from a third party; it is
   signature-verified"). Downloaded plugins are OFF by default and toggleable in
   the Phase-21 store. Persist installed plugins + SHA-256 + pinned cert hash;
   integrity re-checked on every load.
6. **Store wiring** — extend the Phase-21 `PluginStoreDialog`/controller so remote
   entries show download/install/delete/disable states using the unified
   `PluginEntry` (built-ins stay "bundled"). Reuse the Phase-22 `PluginEntryStore`.
7. **Tests (pure-JVM)** — signature-verify accept/reject (valid cert, wrong cert,
   tampered bytes), sha256 mismatch rejection, download-to-install happy path
   (fake transport), tamper rejection before load, capability-facade deny-by-default,
   delete wipes downloaded artifact + settings.

## Definition of done
- `gradle testDebugUnitTest` passes (runtime pure-JVM tests above).
- `gradle assembleDebug` succeeds WITHOUT adding ML Kit barcode / native OCR / LLM
  to the base app. Base APK size delta this phase is minimal (report it).
- End-to-end: download → verify → load → execute works with a small test plugin
  artifact; tampered/wrong-signature artifacts are rejected before any code runs
  (`file:line` evidence).
- Store shows remote vs bundled, download/verify/install/delete/disable flows work.
- REPORT.md records: pinned-cert hash handling, capability surface, base-APK size
  before/after, and which plugins are still deferred (QR, LLM — later phases).

## Constraints
- Signature pinning is a compile-time constant — never user-editable, never
  bypassable in release builds.
- Never log keys, passwords, decrypted content, or downloaded artifact contents.
- Do NOT change the DB schema. Do NOT edit `.github/workflows/`.
- Do NOT bypass `ClipboardGuard` for copy actions.
- No new permissions beyond what the Phase-22 design doc already allows.
- Keep the plugin boundary clean: runtime lives in `plugins/runtime/`; plugin logic
  in its plugin package.