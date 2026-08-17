# Phase 156: Onboarding, empty states & first-run polish [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with an encrypted SQLCipher vault. **Read `docs/phase-status.md` +
`docs/ARCHITECTURE.md` + `R2-b2b5-FEA-01`/knowledge-graph work first.** This is
a PRODUCT feature phase. One coherent slice of first-run/empty-state experience
built on existing surfaces (`HomeScreen`, `EmptyStateResolver`,
`KnowledgeGraphScreen`, `PluginStoreDialog`, the glass theme).

## Features (2-3 related, bundle deeply)

1. **Onboarding / first-run flow:** when `hasMasterPassword == false` (passwordless
   vault), show a 3-step non-blocking intro (Create note / Draw on ink canvas /
   Plugins & backup) after first install — one-time, dismissible, never re-shown
   (persist a `SettingsManager.onboardingCompleted` flag). Respect reduce-motion.
   If a master password IS set later, the guide is still available from Settings
   (a "Show help again" entry). Do NOT add a tutorial that competes with
   phase-125's enhanced tutorial — keep this strictly to first-run triage.
2. **Empty states everywhere:** Home (`EmptyStateResolver.decide`
   `HomeScreen.kt:1139`), trash, recent, knowledge graph (zero nodes/edges —
   "Create a wikilink to start mapping"), tag explorer, version history, web
   search results, and the plugin store when nothing matches a filter. Each:
   honest, actionable, non-alarming, one CTA that opens the right screen.
3. **Home glanceable stats + search polish:** small home header chips
   (n notes · n links · n days since last backup) computed from the already-cached
   corpus / `SettingsManager.lastBackupTimestamp`, plus "no backup yet" nudge on
   the ⋮ menu. Keep it cheap (no new DB reads beyond the cached corpus).

## UI/UX + plugin ideas

- First-run also surfaces the already-built privacy stance (encrypted vault,
  no backup to the cloud) as the very first card — matches the app's honest
  positioning.
- The "Plugins & backup" step links to `PluginStoreDialog` and `WebDavSyncDialog`
  reachable from the Home ⋮ menu today.

## Verification

- Unit tests for new pure-JVM decision logic (e.g. an `OnboardingPolicy` gate —
  when to show, when to persist; `HomeStatsMath` from a fake corpus/last-backup
  seam). Follow repo test layout `app/src/test`.
- `gradle testDebugUnitTest` then `gradle assembleDebug`, report in
  `workspace/phase-156/REPORT.md`.

## Definition of done

- All three features shipped with `file:line` evidence. First-run shows once and
  is dismissible; every named empty surface shows a CTA, no content leaks when
  the vault is locked.
- New tests green + no existing test regressed.

## Constraints

- NO DB schema change. Do NOT edit `.github/workflows/`. No new dependencies.
- Never display decrypted content when locked. Keep reduce-motion + low-end /
  48dp touch-target rules intact.
- Keep base-APK small: no heavy native assets (icons/illustrations drawn in
  Compose).