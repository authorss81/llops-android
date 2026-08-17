# Phase 142: Network/stream I/O — capped mid-stream readers everywhere + idle-progress guard + port-aware allow-lists [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/security-report-round2.md`**
first (findings R2-B1N-01, R2-B1P-04, R2-B1N-05) and `docs/phase-status.md` +
`docs/ARCHITECTURE.md`. This phase makes every raw stream read bounded
mid-stream and expresses the plugin-allow-list invariant at port level.

## Source findings (all OPEN — LOW, LOW, INFO)

1. **R2-B1N-01** (LOW) — LocalSend sender reads LAN-peer response bodies
   unboundedly before truncating: `LocalSendSender.kt:260`
   (`` `…use { it.readText() }.take(2048)` ``), `:445` (`.take(8192)`), `:447`
   (`.take(512)`). Every other network client uses a bounded `readText(limit)`
   loop (`DuckDuckGoClient.kt:211-225`, `HttpsTitleFetcher.kt:128-140`,
   `WeatherClient.kt:148-160`, `DictionaryClient.kt:118-130`).
2. **R2-B1P-04** (LOW) — `BoundedStreamCopier.copyBounded` lacks the idle-read
   guard phase-81 added to `AttachmentIngestPolicy`: `BoundedStreamCopier.kt:
   40-51` keeps `if (read == 0) continue` with no progress counter (sibling fix
   `AttachmentIngestPolicy.kt:96-109` `if (++idleReads > 16) throw …`). A
   malicious ContentProvider InputStream that returns 0 forever hot-spins the
   IO thread.
3. **R2-B1N-05** (INFO) — Plugin-manifest/artifact host allow-lists match host
   only, ignoring the port: `CompileTimePluginPinStore.kt:205-211`
   (`isHostAllowListed` uses `URL(url).host`), gate call `:147`;
   `HttpsManifestTransport.kt:125` (host-only compare); `PluginDownloader.kt:147`.
   `https://<allowed-host>:8443/…` passes every allow-list.

## The fix (where & how)

- **R2-B1N-01:** Apply the same capped mid-stream reader
  (`FacadeHttpGetPolicy.readCapped` / `readText(limit)`) to `inputStream` +
  `errorStream` in `httpPost` and the register probe in LocalSend.
- **R2-B1P-04:** Add the 16-consecutive-idle-read bailout to `copyBounded`
  (`BoundedStreamCopier.kt:43`), pin with `BoundedStreamCopierTest`.
- **R2-B1N-05:** Normalize allow-lists to `(scheme, host, effective-port)`
  triples (reuse the `WebDavHrefResolver.Origin` shape,
  `WebDavHrefResolver.kt:30-55`) in `CompileTimePluginPinStore.isHostAllowListed`
  + the `HttpsManifestTransport` host gate + `PluginDownloader` gate, and
  compare ports too.

## Verification

- New/updated pure-JVM unit tests: LocalSend capped-read behaviors (register
  probe + prepare-upload + errorStream all capped mid-stream); `copyBounded`
  idle-bail on a zero-progress stream; allow-list rejects `<allowed-host>:8443`
  while accepting the default-port target (and old host-only form documented).
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-142/REPORT.md`.

## Definition of done

- All three findings closed with `file:line` before/after evidence.
- No raw `readText().take()` slurp remains in LocalSend; every stream read is
  capped mid-stream; allow-lists are (scheme, host, port) aware.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never log keys, passwords, or decrypted note content. Keep the plugin
  fail-closed pin posture unchanged (allow-list is permissive-additive only).
- Do not fix OTHER findings in this phase — document new bugs in REPORT.md.