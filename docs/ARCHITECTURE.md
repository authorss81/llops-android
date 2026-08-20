# InkFlow (Noteflow) — Architecture Map for AI Agents

> **Living document.** Read this first in any phase. It is injected as context at the
> start of every pipeline phase. After you implement something, append a 3-5 line
> "Implemented in <phase>:" note to the relevant section below AND update
> `docs/phase-status.md` — so the next phase reads fresh facts instead of re-exploring.
> Root: `app/src/main/kotlin/com/authorss81/noteflow/`. Read `AGENTS.md` for hard rules.

## Package layout

| Subpackage | Key files | Purpose |
|---|---|---|
| `data/model/` | `Entities.kt`, `StrokeModels.kt` | Room entities (8) + stroke/ink types |
| `data/db/` | `NoteflowDatabase.kt`, `Daos.kt` | Room DB (schema v9, 8 DAOs), corrupt-DB quarantine |
| `data/repository/` | `NoteRepository.kt`, `LruBoundedMap.kt` | Encrypted read/write, search corpus, WAL checkpoint, re-key |
| `services/` | `EncryptionService.kt`, `SecurityService.kt`, `DatabaseSecurityHelper.kt`, `VaultKeyHolder.kt`, `ExportSessionPolicy.kt`, `WetBrushEngine.kt`, `WebDavSyncService.kt`, `ImportExportService.kt`, `ImportArchivePolicy.kt`, `PaletteCatalog.kt`, `ColorModePersistencePolicy.kt`, `ShapeRecognitionHelper.kt`, `SsrfHostPolicy.kt`, `VoiceNoteCrypto.kt`, `DecryptFailurePolicy.kt`, `BrushEdgePolicy.kt` | Non-UI: crypto/vault, brush math, sync, import/export, zip-import zip-bomb policy (B1-DB-5), palette, rainbow-mode persistence decision table (phase-122), SSRF blocklist (B1-NET-04), voice-note audio cryptor (B1-DB-3), decrypt-failure render decision (B1-DB-8), brush cap/join roundness policy (phase-121) |
| `services/localsend/` | `LocalSendProtocol.kt`, `LocalSendSender.kt`, `LocalSendPairing.kt`, `SettingsLocalSendPairedDeviceStore.kt`, `LocalSendDiscoveryPolicy.kt`, `FileTransferSender.kt`, `LocalSendSenderFactory.kt` | Pure-JVM LocalSend v2.2 + real network sender + TOFU pairing gate (B1-NET-02) + discovery/sweep gate (B1-NET-06) + FileTransfer seam/factory (phase-173) |
| `plugins/` | `NoteflowPlugin.kt`, `PluginRegistry.kt`, `PluginManager.kt`, `PluginDiagnostics.kt`, `PluginLifecycle.kt` | Compile-time plugin framework + typed serving interfaces + capability routes |
| `plugins/runtime/` | `RuntimePluginLoader.kt`, `SignatureVerifiedPluginRuntime.kt`, `ArtifactSignatureVerifier.kt`, `PinnedCertHash.kt`, `PinnedTlsConnector.kt`, `PluginManifestFetcher.kt`, `HttpsPluginDownloadTransport.kt`, `PluginDownloader.kt`, `PluginUpdateEngine.kt`, `CompileTimePluginPinStore.kt`, `PluginFrameworkClassLoader.kt`, `ArtifactStaticScan.kt` | Downloadable-plugin runtime: pinned-cert verify (manifest + artifact transports, no redirects), DexClassLoader (scoped `plugins.*`-only parent), verify-time static content scan (B1-AUTH-01), updates |
| `plugins/store/` | `PluginStoreCatalog.kt`, `PluginStoreController.kt`, `RemotePluginInstaller.kt`, `PluginInstallStore.kt` | Plugin Store lifecycle (bundled catalog + remote install) |
| `plugins/<capability>/` | `ocr/MlKitOcrEngine.kt`, `websearch/DuckDuckGoWebSearchPlugin.kt`, `translation/MlKitTranslatorEngine.kt`, `inktos/InkToShapePlugin.kt`, `weather/`, `dictation/`, `readaloud/`, `citation/`, `filetransfer/LocalSendFileTransferPlugin.kt`, ... | One impl per capability, registered in `PluginRegistry` |
| `ui/components/` | `AnnotationCanvas.kt` (4535 lines), `AgslShaders.kt`, `ShaderCapabilityHelper.kt`, `PenNibVisualPreview.kt`, `LayerBitmapCache.kt`, `BrushStudioDialog.kt`, `PluginStoreDialog.kt`, `GalleryView.kt` | Compose components: canvas, AGSL shaders, dialogs; gallery grid (`GalleryCardItem` redesigned in phase-165) |
| `ui/screens/` | `EditorScreen.kt` (4805), `MarkdownPreviewScreen.kt`, `HomeScreen.kt`, `KnowledgeGraphScreen.kt`, `LockScreen.kt` | Top-level screens |
| `ui/viewmodel/` | `NoteflowViewModel.kt` (~1500) | God-ViewModel: DB, security, plugins, all state flows |
| `theme/` | `Theme.kt`, `GlassSurfaces.kt`, `GlassThemeMath.kt`, `Motion.kt`, `Type.kt`, `Color.kt` | Material3 + frosted-glass design system |
| `utils/` | `ConstantTime.kt`, `BitmapPool.kt`, `DeviceCompatibilityManager.kt`, `WikiLinkParser.kt` (dup, see notes) | Pure helpers |

> **Implemented in phase-188** (2026-08-20, GalleryView robustness, see
> `workspace/phase-188/REPORT.md`): the user visual-review "exploration" set of 4
> risks, none of which phases 183–187 may regress into — (1) **no stroke
> rasterization**: the grid preview derives ONLY from `NotePageEntity` metadata
> (title/extractedText/tags/pinned/sourceFileType/updatedAt); new
> `Phase188GalleryRobustnessTest` source-pins zero `pointsJson`/`getStrokesForPage`/
> `deserializeStrokes`/`thumbnail`/`rasterize` in `GalleryView.kt`; (2) **large
> font scaling (1.3–1.5x)**: footer visibility is guaranteed STRUCTURALLY — the
> card is content-driven with a MINIMUM floor (`heightIn(min = minCardHeight)` on
> both the Card and the body Column) and is NEVER height-capped (no
> `heightIn(max)`/`height(...)`/`aspectRatio` anywhere on the card path), so a
> growing font scale grows the card and the date/tags footer can never clip;
> both preview paths additionally carry `weight(1f, fill=false)` as a DEFENSIVE
> slack seat — inert under the unbounded layout (a flex child only redistributes
> slack when the parent's main axis is finite), load-bearing the day a finite
> height is introduced. `GalleryCardLayoutPolicy` holds the decision model
> (`measuredCardHeightDp`/`footerAlwaysFits` + line-budget constants) as the
> regression guard's pure-JVM oracle; (3) **dark
> theme**: the card now has an explicit `BorderStroke(1.dp,
> outlineVariant.copy(alpha = 0.35f))` from policy constants
> (`GALLERY_CARD_BORDER_WIDTH_DP`/`GALLERY_CARD_BORDER_ALPHA`) so cards stay
> distinct from near-black surfaces; (4) **tag overflow**: the wrapping `FlowRow`
> is replaced by a single-line `Row` capped at 2 chips + a `+N` badge via new
> pure-JVM `services/GalleryTagRowPolicy.kt` (ALL tag math — parse/cap/badge/
> chip-text — lives in the policy; `GalleryView` renders no inline `.take(`)
> (chips `weight(1f, fill=false)` ellipsize, unweighted badge can never be pushed
> out, the update timestamp stays visible). Tests: `GalleryTagRowPolicyTest` (10) +
> `Phase188GalleryLayoutBoundsTest` (8) + `Phase188GalleryRobustnessTest` (6).
> Review-fix 2026-08-20: corrected the risk-#2 narrative (the weight seat is a
> defensive no-op under the unbounded layout, pinned structurally), wired
> `visibleChips()` into the composable, corrected the line-budget KDocs, restored
> the trailing newline, and re-pinned the layout test on the no-height-cap
> invariant. No schema change, no new deps, `.github/workflows/` untouched.

> **Implemented in phase-186** (2026-08-20, gallery quick actions, see
> `workspace/phase-186/REPORT.md`): gallery cards now expose the SAME quick
> actions as the List view — a compact `MoreVert` overflow menu (`GalleryView.kt`)
> with Pin/Unpin → `viewModel.togglePinPage(page.id, page.pinned)`, Edit Tags →
> `onEditTags(page)` → HomeScreen `tagEditorTargetPage` → the shared
> `TagEditorDialog` → `viewModel.updatePageTags`, and Move to Trash →
> `viewModel.trashPage(page.id)` (text + icon in `scheme.error`). New pure-JVM
> `services/GalleryCardActionsPolicy.kt` owns the labels/order/badge/tint rules
> (no inline literals de-syncing the two views); the pinned badge is now compact
> 18dp and the overflow button 28dp so the header fits the ~140dp grid column at
> 360dp (phase-166 discipline). `GalleryView` gained an optional
> `onEditTags: (NotePageEntity) -> Unit = {}` param (backward compatible);
> "Move to Trash" is recoverable-trash only — the app's hard delete stays behind
> its confirmation gate. Tests: `GalleryCardActionsPolicyTest` (6) +
> `Phase186GalleryQuickActionsTest` (7 source pins). No schema change, no new
> deps.

> **Implemented in phase-187** (2026-08-20, gallery ink-note cards — authentic
> notebook-paper look, see `workspace/phase-187/REPORT.md`): ink canvas notes
> without extracted OCR text no longer render a generic pencil-icon stub.
> `ui/components/GalleryView.kt` now gives any ink page (cards whose
> `sourceFileType` is not pdf/image/text and that have no text preview) a
> notebook **dot-grid paper** texture via the new pure-JVM
> `services/InkCardPaperPolicy.kt` + a private `Modifier.notebookPaper`
> `drawBehind` extension: paper-toned `scheme.surface.copy(alpha=0.7f)` fill +
> `scheme.outlineVariant.copy(alpha=0.3f)` dots (22dp pitch, 1.5dp radius).
> Draw-budget discipline (phase-188 #1): bounded `gridColumns/gridRows` keep the
> loop ≤ 12×8 = 96 dots on any size; pitches/colors computed once per
> composition and captured — no per-frame allocation, NO `pointsJson`/stroke
> rasterization (GalleryView has zero `pointsJson` tokens, source-pinned). The
> placeholder keeps its 44dp draw-Brush icon chip and now uses the honest policy
> label `InkCardPaperPolicy.HANDWRITTEN_LABEL` = "Handwritten note" (never
> claims OCR text exists). Dark-theme safe: the 0.7-alpha surface fill sits over
> the `surfaceVariant@0.55` container so the card stays lighter than the page
> surface (phase-188 adds the explicit border). Tests: `InkCardPaperPolicyTest`
> (11, pure JVM) + `Phase187GalleryInkPaperTest` (4 source pins). No schema
> change, no new deps.

> **Implemented in phase-165** (`ui/components/GalleryView.kt`): the gallery page cards
> were redesigned from the old flat square box to tasteful Material 3 cards — 20 dp
> rounded corners, tonal `surfaceVariant` container with a subtle `primaryContainer`
> wash fading from the top edge, 3 dp elevation, fixed portrait 10:16 aspect ratio so
> `GridCells.Adaptive(168.dp)` keeps balanced proportions on phones AND tablets — NOTE:
> the 10:16 ratio was REMOVED in phase-184 (see below) in favor of a content-driven
> card with a font-scale-scaled `heightIn` minimum floor; the rest of this card design
> (rounded corners, wash, elevation, preview blocks, footer) is unchanged, and a
> rich preview (type badge, ellipsized title, first ~2-3 preview lines, pinned
> indicator, ≤3 tag chips + "+N", updated date). No canvas rasterization, no heavy
> shadow/blur (AGENTS.md low-end rule); ripple from the clickable `Card`; the app has
> no multi-select yet, so no separate selection state. Public `GalleryView(pages,
> viewModel, onOpenPage, modifier)` API unchanged — `HomeScreen.kt:1333` keeps working.

> **Implemented in phase-166** (2026-08-19, compact-screen overflow pass): no fixed-width
> Row may clip on a 360dp portrait screen. (1) `MarkdownPreviewScreen` app bar is
> decluttered — the title row holds only the note title + the Reader toggle chip, and the
> view-mode / split-orientation / Serif chips moved to a full-width horizontally
> scrollable sub-bar beneath the app bar (`MarkdownPreviewScreen.kt:564-635`; Serif stays
> reachable in reader/preview modes). (2) `WebDavSyncDialog` primary actions moved from the
> alert-dialog confirm row to stacked full-width body buttons (`WebDavSyncDialog.kt:305-388`;
> `confirmButton` = Close only, `:391`). (3) `CalendarView` stacks the date summary above
> the "New Note for Date" button (`CalendarView.kt:188-212`). (4) HomeScreen import
> orientation chips row is horizontally scrollable (`HomeScreen.kt:1711-1714`). (5) the
> filtered-by-tag banner text wraps/ellipsizes (`HomeScreen.kt:1233-1245`).
> (6) `InteractiveTutorial` puts "Skip Tutorial" on its own line and end-aligns the
> Back/Skip-step/Next row (`InteractiveTutorial.kt:338-402`). (7) KnowledgeGraph card title
> wraps (`KnowledgeGraphScreen.kt:674-680`). Regression guard:
> `app/src/test/java/com/authorss81/noteflow/Phase166LayoutOverflowTest.kt` (8 source-
> pinning tests, `class Phase166LayoutOverflowTest` at `:29`).

> **Implemented in phase-183** (2026-08-20, gallery title typography — no mid-word breaks,
> no redundant `.md`, see `workspace/phase-183/REPORT.md`): the compact grid card title
> (`GalleryView.kt:148`) previously rendered the RAW stored title (e.g. `2026-08-19.md`)
> and wrapped mid-extension in the ~140dp text column. Display is now derived through the
> pure-JVM `services/GalleryTitleDisplayPolicy.kt` (`displayTitle`): strips ONE redundant
> `.md`/`.markdown`/`.txt` suffix for display only (never the stored DB title;
> `foo.md.md` keeps one suffix), and the title `Text` renders with `maxLines=2`,
> `TextOverflow.Ellipsis`, `softWrap=true`, `Hyphens.None` (applied VIA THE STYLE —
> M3 `Text` in Compose UI 1.7.6 has no `hyphens` parameter, so a direct
> `Text(hyphens=...)` arg cannot compile), `FontWeight.SemiBold`,
> `lineHeight=18.sp` (both `hyphens` + `lineHeight` live in `titleSmall.copy(...)`).
> The footer date also capped `maxLines=1`+Ellipsis (`GalleryView.kt:274-279`).
> **Review-fix (2026-08-20)** extended the same `displayTitle` to the OTHER
> display-only title render sites in the home tab for consistency: list view
> (`HomeScreen.kt:2587`), tag-editor dialog (`HomeScreen.kt:1920`), Kanban cards
> (`KanbanBoardView.kt:218`), Calendar cards (`CalendarView.kt:241`), Spreadsheet
> cells (`SpreadsheetTableView.kt:100`), editor app bar (`EditorScreen.kt:1400`),
> Markdown-preview app bar (`MarkdownPreviewScreen.kt:536`). Stored DB titles,
> rename dialogs, routing (`MainActivity.kt:601,711`) and export call sites keep the
> RAW value — display-only everywhere. Tests: `GalleryTitleDisplayPolicyTest.kt` (11)
> + `Phase183GalleryTypographyTest.kt` (2 source pins). No schema change, no new deps.

> **Implemented in phase-184** (2026-08-20, gallery card proportions — no dead empty
> band, see `workspace/phase-184/REPORT.md`): user visual review found the fixed
> portrait `aspectRatio(10f/16f)` card (phase-165) forced a strict **268.8dp-tall tile
> at the 168dp grid cell** — >60% empty for short notes, and a strict ratio can clip
> the footer at large font scales. The rigid ratio is GONE: cards are now
> **content-driven** with a font-scale-scaled minimum floor. New pure-JVM
> `services/GalleryCardLayoutPolicy.kt` (`minCardHeightDp(fontScale)` =
> `(180f · fontScale).coerceIn(180f, 288f)`, non-finite/≤0 inputs fail safe to 1.0).
> `GalleryView.kt` applies it as
> `heightIn(min = GalleryCardLayoutPolicy.minCardHeightDp(LocalDensity.current.fontScale).dp)`
> (`GalleryView.kt:96-115`) — a FLOOR, never a strict ratio, so content taller than
> it still grows the card. The card body was restructured to wrap content: the wash
> uses `matchParentSize()`, the preview is a plain `maxLines=3` block, and the
> ink/empty placeholder is a compact 84dp-minimum band (`heightIn(min = 84.dp)`,
> review fix: originally a fixed `.height(84.dp)` that overflowed its label at ≥2×
> font scale) instead of stretching across the
> leftover height. Grid stays `LazyVerticalGrid(Adaptive(168.dp))` (staggered grid
> deliberately NOT chosen — same adaptive outcome, simpler recycling/memory for
> large galleries); tag/date footer + filter behavior unchanged. Tests:
> `GalleryCardLayoutPolicyTest.kt` (6, pure JVM) + `Phase184GalleryProportionTest.kt`
> (5 source pins). No schema change, no new deps.

> **Implemented in phase-167** (2026-08-19, bottom-nav-bar overlay fix — dynamic insets,
> see `workspace/phase-167/REPORT.md`): the app draws edge-to-edge (`MainActivity.kt:252`),
> so any bottom-anchored surface outside a Scaffold must consume the system navigation-bar
> inset itself. The root `SnackbarHost` (`MainActivity.kt:804-809`) and the three
> Scaffold-less recovery screens (`RestoreBlockedScreen`/`CorruptionRecoveryScreen`/
> `KeystoreKeyLostScreen`, insets at `MainActivity.kt:1294-1295/1375-1376/1498-1499`) gained
> `.navigationBarsPadding()` (bottom) — review-fix also added `.statusBarsPadding()` (top,
> these Screens draw under the transparent status bar too) — pure runtime insets, works on
> small/large screens, portrait/landscape, gesture/3-button nav. The four content screens
> were audited and already apply the Scaffold `innerPadding` (M3 default
> `contentWindowInsets` = `systemBarsForVisualComponents`, bytecode-verified), so page
> lists / Calendar (`CalendarView.kt:229-231` weight-bounded) / canvas / graph end above
> the bar. Regression guard: `Phase167BottomNavOverlayTest` (8 source-pinning tests,
> review-fix hardened: modifier-order assertion + top-inset pin).
> No fixed-pixel heights anywhere.

> **Implemented in phase-168** (2026-08-19, last-used notebook on cold start,
> see `workspace/phase-168/REPORT.md`): `SettingsManager.lastNotebookId`
> (`last_notebook_id`) was never written and cold-start restore keyed on
> `activeNotebookId` AND a matching `activeSectionId` — a stale section pref fell
> through to the default notebook. Now `NoteflowViewModel.selectNotebook`
> persists `lastNotebookId` on every selection change (single notebook-switcher
> chokepoint) and `onCleared()` on exit; `initializeDataCore` restores
> `lastNotebookId ?: activeNotebookId`, restores the notebook even with a stale
> section (first-section fallback via `observeSections`), falls back to the
> first existing notebook when the last was deleted (persisted back), or
> default_nb+default_sec on a brand-new vault. Prefs only. Tests:
> `Phase168LastNotebookRestoreTest` (10).

> **Implemented in phase-181** (2026-08-20, export/home-return regression fix, see
> `workspace/phase-181/REPORT.md`): phase-168 fixed cold start but the last-used
> notebook was still lost on ANY backgrounding — `MainActivity` calls
> `viewModel.lock()` on `ON_STOP` (SAF export picker, home button, app switch),
> and lock() previously zeroized the DEK and nulled
> `_selectedNotebook`/`_sections`/`_pages` UNCONDITIONALLY even for PASSWORDLESS
> vaults, whose `_authenticated`/`dataInitialized` never flip — so nothing re-ran
> the phase-168 restore and the home page returned with no notebook open. The
> entire lock() session teardown (DEK zeroization, decrypt-failure ledger reset,
> observer cancel, DB dispose, selection/content StateFlow clears, authenticated
> flip) now lives inside `if (settings.hasMasterPassword)`, honoring the B1-AUTH-02
> design ("passwordless has no lock boundary — the device-wrapped DEK IS the boot
> credential"); a passwordless lock() is a session-preserving no-op so the
> pre-export/pre-background notebook stays open on return. All source pins
> updated (`B2Ui4` passwordless branch, `B1Db08` gate-region extraction). Tests:
> `Phase181ExportReturnNotebookRestoreTest` (8), `B2Ui4` + `B1Db08` updated.

> **Implemented in phase-182** (2026-08-20, export/home-return unreadable-title
> regression re-fix, see `workspace/phase-182/REPORT.md`): phase-169's two
> mechanisms re-verified CLOSED (cross-key restore classifies `AuthFailed` per
> row and throws `RestoreReEncryptionException` before any write-back;
> `updatePageBody`/`renamePage`/`updatePageTitleAndTags` refuse the render
> marker with `UnreadableContentWriteException`); every exporter
> (`exportBackup`/`exportVaultToZip`/`exportNoteToHtml`/`exportVaultToHtmlZip`/
> `exportObsidianVaultZip`/`exportPageToPsd`) is a read-only passthrough —
> `ImportExportService` never calls `closeDatabase`/`reopenDatabase`. The
> residual marker-persist surface was `NoteRepository.createNoteVersion` — it
> now refuses the trimmed marker too (guard before encrypt,
> `NoteRepository.kt:1578`), and `NoteflowViewModel.createNoteVersion` surfaces
> `DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE`. "Export Document as PDF" no
> longer undercounts: new pure-JVM `services/DocumentPdfExportPolicy.kt`
> (`pageCountForExport` = `max(1, sourcePdfTotalPages, strokesBased,
> itemsBased)`) — the EditorScreen call site feeds it a page count it
> RE-DERIVES from the source at export time (`getPdfPageCount` /
> `imagePageCountForExport`, closing the async-load race where a tap before the
> initial decode could still export the `pdfTotalPages = 1` default), plus the
> highest stroke AND sticky-note/media-embed page — never the memory-bounded
> `pdfPageBitmaps` window — and threads `sourceFilePath` so the exporter's
> per-page fallback draws every page: PDFs via `renderPdfPageToBitmap`, tall
> images as per-page SLICES via `renderImageSliceForPage` (mirroring the canvas,
> never the whole image stamped onto each page), template background otherwise,
> and the loop recycles ONLY its own allocations — never the caller's in-window
> cache the editor is still displaying. Tests:
> `Phase182ExportReadLockBoundaryTest` (9).
> **Implemented in phase-189** (2026-08-20, backup/restore must keep working
> immediately after a vault export, see `workspace/phase-189/REPORT.md`): the
> export chain stays session read-only, but its staged-snapshot prunes no longer
> re-read the mutable `VaultKeyHolder.dek` singleton at prune time (a mid-export
> lock() — SAF picker ON_STOP → `MainActivity.kt:207-209` — zeroized the DEK and
> failed the current backup + poisoned the next one). `exportBackup`
> (`ImportExportService.kt:1451`) now pins the DEK it was HANDED at export start
> via new pure-JVM `services/ExportSessionPolicy.kt` — `pinnedPruneDek(key) {
> VaultKeyHolder.dek }` resolves a snapshot-at-entry COPY (a mid-export
> zeroization cannot null it) and the prunes take `(stagedDb, dek)` params with
> ZERO `VaultKeyHolder` references; the pin is zeroized in a `finally` right
> after both prunes. Fixed backup texts centralized in the policy
> (`KEEP_CHANGING_ERROR`/`LOCKED_SNAPSHOT_ERROR`, wording unchanged). Tests:
> `Phase189ExportSessionStateLossTest` (7).



> **Implemented in phase-172** (2026-08-19, editor & canvas productivity, see
> `workspace/phase-172/REPORT.md`): three pure-JVM policies +
> `services/ColorRecentsPolicy.kt` (persisted recently-used colors + favorites —
> comma-joined decimal-ARGB prefs wire format in `SettingsManager.recentColors`/
> `favoriteColors`, exposed as `NoteflowViewModel` StateFlows;
> `ColorPickerBottomSheet`'s recents row is now the persisted list and a star
> toggle favorites the current color; explicit picks AND eyedropper samples
> record — slider drags do not, so the 16-slot list can't flood; 12-favorite
> cap), `services/CanvasNavigationPolicy.kt` (minimap Fit + Home: budgeted
> `computeContentBounds` via `MinimapGeometryPolicy` strides,
> world-clamped `zoomToFit` within 0.5x..4x + 48dp margin, zero-area fits
> fall back home, reduce-motion snaps), and `services/LayerBlendPresetPolicy.kt`
> (5 presets Normal/Multiply/Screen/Overlay/Soft-Light chips in the layer panel
> driving the existing `onUpdateLayer` → `saveLayersGated` path; the dropdown
> 12-mode literal now reads `RENDERER_SUPPORTED_MODES`). `AnnotationCanvas.kt`
> animates Fit/Home through its existing `updateZoomAndPan` transform pipeline
> with a `SpringCanvasPan` spring (`navigateCanvasTo` + `LaunchedEffect` on a
> nav-request seq). No schema change, no new deps. Tests: `ColorRecentsPolicyTest`
> (13), `CanvasNavigationPolicyTest` (13), `LayerBlendPresetPolicyTest` (7).

## Core subsystem anchors (file:line)

- **Encryption/vault**: `services/EncryptionService.kt:18` (PBKDF2 600k, AES-256-GCM, NFKC-normalized password);
  `services/SecurityService.kt:14` (AndroidKeyStore-wrapped DEK `noteflow_dek_key`);
  `data/repository/NoteRepository.kt:18` (encrypted field r/w, `zeroizeKey()`, `checkpointWal()`);
  `services/DatabaseSecurityHelper.kt:21` (HMAC tamper checksum over `noteflow.sqlite`);
  `data/db/NoteflowDatabase.kt:43` (schema v9, quarantine);
  `services/VaultKeyHolder.kt:11` (in-memory DEK, zeroized on lock).
  - **Implemented in phase-43** (B1-DB-1, see `workspace/phase-43/REPORT.md`): the corrupt-open
    classifier `NoteflowDatabase.kt` `isDatabaseCorruptException` now matches ONLY genuine
    corruption (`android.database.sqlite.SQLiteDatabaseCorruptException`,
    `net.zetetic.database.sqlcipher.SQLiteNotADatabaseException`, messages "file is not a
    database"/"malformed"/"database disk image is malformed") — transient open failures
    (locked, disk I/O, ENOSPC, can't-open) are NEVER treated as corruption. Quarantine no
    longer auto-creates an empty replacement DB: `SafeSupportSQLiteOpenHelper` rethrows after
    quarantine + a `throwIfVaultQuarantined` guard fails any further open while the flag is
    set, so the empty vault is created only after the user's explicit "start fresh".
    `NoteflowViewModel` surfaces the `CorruptionRecoveryScreen` in-session, gates the six
    Room-backed note flows on `authenticated && !corruptionBlocked`, re-initializes after
    start-fresh, and clears the flag after a successful restore.
  - **Implemented in phase-44** (B1-DB-4 + B1-AUTH-06, see `workspace/phase-44/REPORT.md`): note
    bodies no longer live as PLAINTEXT files. The field-encrypted `pages.extractedText` column is
    now the ONLY body store. `services/NoteBodyVaultPolicy.kt` (pure JVM) classifies note-body
    sources (text/-typed pages and `.md`/`.txt` files only; PDF/image/attachment artifacts are
    never treated as bodies) and provides `resolveBodyForDisplay` + `deleteLegacyNoteTextBody`.
    Single write path: `NotePageDao.updatePageBody` + `NoteRepository.updatePageBody` (AES-GCM,
    per-record AAD). The markdown editor opens/saves via `viewModel.saveMarkdownNoteBody`
    (`MainActivity.kt` both layouts); `File.writeText` body writes are gone. `.md`/`.txt`/DOCX/
    HTML/Obsidian imports and journal/daily/wiki page creation store `sourceFilePath = null` +
    body in `extractedText`. One-time `NoteRepository.migrateLegacyPlaintextNoteBodies` (flagged
    by `SettingsManager.noteBodyPlaintextMigrated`, `fieldAadMigrated` pattern) sweeps pre-fix
    file bodies into the encrypted column then deletes the files; WAL is checkpointed + the DB
    HMAC re-stamped afterwards.
  - **Implemented in phase-45** (B1-CRYPTO-02, see `workspace/phase-45/REPORT.md`): the vault DEK
    is no longer obtainable without the password. `services/SecurityService.kt` now isolates the
    device-wrapped DEK copy behind an internal `DekDeviceStore` seam
    (`SharedPrefsDekDeviceStore` = `noteflow_keystore`/`noteflow_sec_dek`, `clear()` uses
    `commit()` so the removal is disk-acknowledged) and `readDek()` fails closed (absent OR
    `authRequired=true` blob ⇒ null; `getOrCreateDek` never mints over an auth-gated blob). New
    pure-JVM `services/DekAtRestPolicy.kt` is the decision table, wired as
    `NoteflowViewModel.enforceDekAtRestPolicy()` in `setMasterPassword`, `changeMasterPassword`,
    `verifyMasterPassword` (every password unlock), `verifyBiometricsAndUnlock` and
    `setBiometricEnabled`: biometrics OFF ⇒ `security.clearDek()` (only at-rest wrapper = the
    password-derived KEK in settings); biometrics ON ⇒ repersist ONLY `authRequired = true`
    (biometric-gated). The pre-fix `setBiometricEnabled(false,…)` path that re-wrapped non-auth is
    gone. `SecurityService(context)` call sites now use `SecurityService.forDevice(context)`.
    Tests: `DekAtRestPolicyTest` (4) + `B1Crypto02DekAtRestTest` (9, incl. a source-level wiring
    pin); 978 unit tests green + `assembleDebug` green.
  - **Phase-45 review fixes** (see `workspace/phase-45/REPORT.md` "Addendum 2"): a locked open
    never reaches the mint path — `NoteflowSqlcipherFactory.create` reads
    `SettingsManager.hasMasterPassword` and calls `getOrCreateDek(allowPasswordlessMint = !…)`
    (`data/db/NoteflowDatabase.kt:345-362`), so no fresh non-auth DEK is ever minted/dropped into
    prefs over a password-protected vault (which would also open with the wrong SQLCipher key and
    trip the phase-43 quarantiner). `storeDek`/`clearDek`/`DekDeviceStore.clear` now report
    success/failure; `enforceDekAtRestPolicy()` returns it and `setBiometricEnabled` commits the
    setting only when the at-rest blob was actually written/cleared (reverts on failure). Tests:
    B1Crypto02DekAtRestTest now 12 (+3); 981 unit tests green + `assembleDebug` green.
  - **Implemented in phase-47** (B1-AUTH-02, see `workspace/phase-47/REPORT.md`): the lock is
    enforced at the DATA LAYER. `NoteflowViewModel.lock()` (`NoteflowViewModel.kt:2543-2549`) now
    cancels the section/page observer jobs and calls `NoteflowDatabase.dispose()` so NO keyed
    SQLCipher connection survives a password-vault lock (previously only `VaultKeyHolder` was
    zeroized). `NoteflowSqlcipherFactory.create` (`data/db/NoteflowDatabase.kt:343-371`) routes a
    `dek == null` open through the pure-JVM `services/LockedOpenGuard.kt`: a password-protected
    vault with no in-memory DEK THROWS `"Vault is locked: database key not available"` BEFORE any
    `getOrCreateDek()`/persisted-copy access (a passwordless vault still re-reads its
    device-wrapped copy — the boot credential by design). Explicit unlocks
    (`verifyMasterPassword` `:2080`, `verifyBiometricsAndUnlock` `:2220`) reinstate the live
    connection via `reinstateDatabaseAfterLock()` (no-op unless `lock()` disposed it) BEFORE the
    dbGate flows flip on, and an open failure there is zeroized — never counted as a wrong
    password. `onCleared()` also disposes. `databaseDisposedByLock` + `dataInitialized=false` let
    the next unlock re-establish observers against the fresh connection.
  - **Implemented in phase-145** (R2-B1C-03, see `workspace/phase-145/REPORT.md`): the SQLCipher
    passphrase is never an immutable hex `String` — `NoteflowSqlcipherFactory.create`
    (`data/db/NoteflowDatabase.kt:453-461`) builds it directly as ASCII-hex BYTES via
    `ByteArray.toSqlcipherPassphraseBytes()` (`:207-216`, byte-identical to the old lowercase-hex
    String so existing vault files still open) and zeroizes `passphraseBytes.fill(0)` in the same
    try/finally after `SupportOpenHelperFactory(passphraseBytes)` and the plaintext-migration
    `openOrCreateDatabase(tempFile, passphrase, ...)` byte[] overload. The restore pipeline carries
    backup+current DEKs as zeroizable ByteArrays end-to-end (`BackupV2Payload.dek`,
    `ImportExportService.kt:1518`, `importBackup` `finally` zeroizes, `:1985`); hex Strings exist
    ONLY inside `validateAndPrepareRestoredDb` — owned byte copies, both zeroized on exit — and the
    SQLCipher String-API touches (`rekeySqlcipherDb`, `migrateFieldCiphertexts`, candidate opens)
    all feed the passphrase as zeroized ASCII bytes via `String.toAsciiBytes()`.
  - **Implemented in phase-95** (B2-UI-4, VERIFY-ONLY, see `workspace/phase-95/REPORT.md`): the
    post-unlock state re-initialization gap is closed and pinned. `lock()` (`NoteflowViewModel.kt:3638-3694`,
    inside `if (settings.hasMasterPassword)` `:3668-3681`) resets `dataInitialized = false`
    (`:3673`), cancels `sectionsJob`/`pagesJob` (`:3669-3670`), disposes the connection
    (`:3671`), and nulls `_pages`/`_selectedPage`/`_sections`/`_selectedSection`/`_selectedNotebook`
    (`:3686-3690`); BOTH unlock paths (`verifyMasterPassword` `:2780-2789`,
    `verifyBiometricsAndUnlock` `:2985-2995`) reinstate the connection, flip `_authenticated`, then
    call `initializeData()` (`:1400-1432`, re-entry-guarded) which boots `initializeDataCore()`
    (`:1434-1563`) → restores `settings.activeNotebookId`/`settings.activeSectionId` from prefs
    (stale/deleted pair falls back to `ensureDefaultNotebookAndSection()`) → re-arms BOTH
    `observeSections`/`observePages` (`:1541-1542`), so the home lists repopulate WITHOUT manual
    navigation. The six home-list flows (`notebooks`/`allSections`/`allActivePages`/`paletteItems`/
    `recentPages`/`trashedPages`, `:1272-1376`) are dbGate-gated (`_authenticated && !_corruptionBlocked &&
    !_keystoreKeyLost`): locked ⇒ `emptyList()`, unlocked ⇒ re-subscribe+re-emit. Passwordless
    vaults skip the teardown (device-wrapped DEK is the boot credential). Pinned by
    `B2Ui4UnlockReinitializesStateTest` (10 tests).
  - **Implemented in phase-48** (B2-LOG-01, see `workspace/phase-48/REPORT.md`): crash logging is
    single-owner. `utils/AppStartupLogger.kt` is a startup-EVENT timer only — it no longer installs
    an `UncaughtExceptionHandler` and its raw `logCrash` (`printStackTrace` → `Log.e` = unredacted
    vault paths / note-title filenames to logcat) is deleted; its `Log.e` failure paths no longer pass
    the exception object. `services/PrivacyCrashReporter.kt` is the SOLE crash handler; every crash
    entry flows through the pure-JVM `PrivacyCrashReporter.crashLogEntry` (`:64`, sanitized message +
    scrubbed `class.method(file:line)` frames) and the uncaught path writes to the local file only —
    never logcat. Repo-wide pin: `setDefaultUncaughtExceptionHandler` appears only in
    `PrivacyCrashReporter.kt`. Path redaction (B1-PLAT-5) is also closed here:
    `sanitizeMessage` (`:91-93`) redacts ANY `/data/user/<uid>/...` or `/data/data/...`
    path — covers both the namespace and the real applicationId dir. Tests:
    `B2Log01CrashReportingTest` (7) — 1020 unit tests green.
  - **Verified in phase-89** (B1-PLAT-5, verify-only — see `workspace/phase-89/REPORT.md`):
    the path-redaction fix above was re-confirmed against the finding's real
    applicationId (`com.aistudio.inkflow.app.bkxjrz`, `app/build.gradle.kts:15`). The
    generic `/data/user/\d+/...` + `/data/data/...` rules in `sanitizeMessage`
    (`PrivacyCrashReporter.kt:94-95`) cover BOTH the namespace and the runtime app-package
    dir — pinned by `B2Log01CrashReportingTest:80-90` (`crash entry redacts the real
    runtime applicationId data dir too`). No code change was required or made.
  - **Implemented in phase-70** (B2-LOG-02, see `workspace/phase-70/REPORT.md`):
    `app_startup.log` is capped, rotated and pruned — the pre-fix append-only
    `FileWriter(logFile, true)` (no length check / rotation / delete, unbounded growth on
    the vault's partition) is gone. New pure-JVM `services/StartupLogPolicy.kt` is the
    single decision table: `LOG_FILE_NAME`/`BACKUP_SUFFIX=".1"`, `MAX_LOG_BYTES = 500_000L`
    (same ~500KB budget `PrivacyCrashReporter` uses), `MAX_LOG_FILES = 2`, plus
    `wouldExceedCap` (the BEFORE-write rotate decision), `rotateForAppend` (keep-last-N:
    drop the oldest `.1`, promote the active file) and `pruneOnInit` (clears any leftover
    over-cap file). `AppStartupLogger.appendToFile` now gates the write through the policy
    (`AppStartupLogger.kt:71-80`), `init` prunes on the background executor, and the dead
    `getLogs`/`clearLogs` accessors are removed. Active log never exceeds the cap; total
    retention bounded at 2 × 500KB. Tests: `B2Log02StartupLogRotationTest` (14) — 1350
    green (only the 2 pre-existing B1Plat01ReleaseSigningTest asserts + 1 documented
    WikiLinkParserCacheUnitTest flake that passes in isolation).
  - **Implemented in phase-71** (B2-LOG-03, see `workspace/phase-71/REPORT.md`):
    import/export failures never reach logcat as full throwables. The eleven
    `Log.e("ImportExportService", "...", e)` call sites in `ImportExportService.kt`
    (which passed the exception OBJECT, so logcat got path-carrying messages
    embedding note-title filenames under `filesDir/noteflow/imports/` — a
    bypass of PrivacyCrashReporter) are now 2-argument `Log.e` calls whose
    message is built by the new pure-JVM `services/FailureLogPolicy.kt`
    (`safeLogMessage(e, operation)` = FIXED operation label + `classNameToken(e)`
    = the exception's simple class name only; `e.message`/stack are never read).
    Tests: `FailureLogPolicyTest` (8, incl. a mechanical source pin: every
    `Log.(e|w)` call in the file has exactly TWO arguments and routes through the
    policy) — 1359 green in the final run (only the 2 pre-existing
    B1Plat01ReleaseSigningTest asserts + 1 documented WikiLinkParserCacheUnitTest
    flake that passes in isolation); `gradle :app:assembleDebug` green.
  - **Implemented in phase-148** (R2-b2b3-LOG-01/02/03, see
    `workspace/phase-148/REPORT.md`): the phase-71/94 scrub extends to every
    remaining UI + logcat surface — no raw `${e.message}` (or attacker-carried
    `entryName`) reaches a snackbar, dialog, recovery screen, or logcat in the
    named files. New pure-JVM `services/UiFailureTextPolicy.kt` fixed-text
    decision table (`restoreFailureMessage`/`recoveryMessage`/
    `backupFailureMessage`/`importSkippedMessage` + defensive `scrubForUi`
    URL-userinfo/path redactor) is wired into `HomeScreen.kt:200-360,673,1496`,
    `NoteflowViewModel.kt:2407,2498,3843,3863` (which also sanitizes the three
    `MainActivity.kt` recovery screens that render VM text), `Dialogs.kt:86`,
    `EditorScreen.kt:968`, `LocalSendSender.kt:384,606-611`. `ImportExportService.kt`
    throws FIXED "unsafe relative path" text (no `$entryName`). Logcat sites
    `VoiceNoteManager.kt:182,224,347,360,390,401,413,428` + `ProtobufBrushLoader.kt:67,80,93`
    log only `FailureLogPolicy.classNameToken(e)`. Tests: `Phase148UiFailureTextScrubTest`
    (18, incl. source pins) + `B1Db05ImportZipBombTest.kt:326` pin updated —
    2012 green (0 failures).
  - **Implemented in phase-93** (B2-LOG-04, see `workspace/phase-93/REPORT.md`):
    the plugin logcat sink is contract-enforced end to end. New pure-JVM decision
    table `plugin-sdk/src/main/kotlin/com/authorss81/noteflow/plugins/PluginLogPolicy.kt`
    (`hasLineBreak`/`lineBreakError`/`stripLineBreaks`/`safeLine` — `safeLine`
    strips CR/LF and redacts `https?://\S+` tokens to `<url>`); `AndroidPluginLogger`
    (`plugins/PluginLogger.kt:34-48`) routes BOTH `lifecycle` and `error` lines
    through it; `PluginEntry.validationErrors`/`HostedPluginVersion.validationErrors`
    refuse CR/LF in `id`/`name`/`downloadUrl` (fixed-text errors, the hostile value
    never echoed) so a manifest leg / catalog blob / update offer with a newline is
    rejected whole; and every download/install/update/store failure `logger.error`
    detail is a FIXED reason code or stage token (`code=DOWNLOAD_GUARD`…,
    `stage=download`…) — never `reason.substringBefore('.')` / `result.reason`.
    Tests: `B2Log04PluginLogScrubbingTest` (10) — 1602 green (0 failures).
  - **Implemented in phase-49** (B2-UI-1, see `workspace/phase-49/REPORT.md`): the WRITE side of the
    lock boundary fails closed. Every editor page-write now routes through the ViewModel lock-safe
    gate: `NoteflowViewModel.flushEditorPageSave`/`autosaveStrokes`/`saveLayersGated` + private
    `persistOrDefer` (`NoteflowViewModel.kt:2302/2325/2341/2362`) decide persist-vs-defer via the new
    pure-JVM `services/VaultWriteGate.kt` (`requireKey` throws `VaultLockedWriteException` on a
    zeroized DEK; `persistNow` = the persist-vs-defer decision). Locked flushes are stashed in the
    latest-wins `services/EditorFlushPolicy.kt` (`defer`/`drain`) and re-written ENCRYPTED by
    `flushPendingEditorSaves()` (`:2395`) in BOTH unlock paths (`:2120`, `:2242`) — never dropped,
    never plaintext, never crash. `NoteRepository.kt` uses `requireEncryptionKey()` (`:44`) in every
    encrypted-column write (`updatePageBody`, `createPage`, `renamePage`, `updatePageTitleAndTags`,
    `saveStrokesForPage`, `saveMediaEmbedsForPage`, `createNoteVersion`) and the
    `encrypt-or-plaintext` elvis/else fallbacks are grep-verified gone. `EditorScreen.kt` has no
    direct `viewModel.repository.save*` call sites (reads only); `createNoteVersion` is rejected
    while locked. Reads remain direct through the live repository — B1-AUTH-02 governs the read side,
    B2-UI-1 the write side.
  - **Phase-49 review fix (2026-08-15)**: (1) the non-flush page writes
    (`applyWorkspaceTemplate`, `addPage`, `createNoteFromSharedContent`, `renamePage`,
    `updatePageTitleAndTags`, `autoTagLanguageOnSave`, `openOrCreateDailyNote`, `openPageByTitle`)
    now route through `NoteflowViewModel.writeGuardedAgainstLock` / `isLockRacedWrite`
    — a lock racing the create/rename no longer crashes (bare TOCTOU guard), it surfaces a
    non-alarming snackbar. (2) `saveMarkdownNoteBody` is lock-safe now too: `EditorFlushPolicy`
    gained a `DeferredBody` stash (`deferBody`/`drainBodies`), so a body whose save races a lock is
    re-written ENCRYPTED after the next unlock instead of being dropped behind an error snackbar;
    the legacy plaintext file delete follows the encrypted-column write in the flush. (3)
    `createNoteVersion` lock-rejection now shows a notice instead of a silent drop.
    (4) KNOWN TRADE-OFF: the deferral stashes live in VM memory only — a process kill during a
    locked interval loses the last stashed page delta/body. A durable pending-queue is impossible
    without writing the data to disk while locked (i.e. plaintext), which is exactly what this
    finding forbids, so the in-memory stash is deliberate and bounded (latest-wins per page).
  - **Implemented in phase-149** (R2-b2b4-DOS-01, see `workspace/phase-149/REPORT.md`): the
    `note_versions` table is BOUNDED and never decrypted wholesale. New pure-JVM
    `services/NoteVersionRetentionPolicy.kt` owns the budgets (`MAX_VERSIONS_PER_PAGE` = 20,
    `DECRYPT_BATCH_SIZE` = 20, `REENCRYPT_BATCH_SIZE` = 100) plus the shared prune/paged SQL
    (single literal, wired into the DAO `@Query` AND the raw restore/export sanitizers; ties on
    `timestampMs` break on `rowid`). `NoteRepository.createNoteVersion` (`:1436`) inserts AND
    prunes the oldest rows past the cap inside ONE transaction (`NoteVersionDao.pruneVersionsForPage`,
    `Daos.kt`), so a save/autosave/translate loop can never accumulate an unbounded table.
    `getNoteVersions` (`:1461`) returns ONLY the newest bounded initial window via
    `NoteVersionDao.getVersionsForPagePaged` (`LIMIT :limit OFFSET :offset`), and the
    `VersionHistoryBottomSheet` materializes lazily — it keeps the pinned guarded
    `viewModel.getNoteVersions(page.id)` for the first window and streams further windows via the
    new `viewModel.getNoteVersionsPaged` sentinel as the list scrolls. Both whole-table re-key
    sweeps (`migrateFieldRecordAad`, `reencryptPlaintextFields`) now page via
    `getVersionsForReencryptPaged`. The backup writer prunes the STAGED snapshot
    (`pruneStagedSnapshotVersions`, after its checkpoint-then-copy — never the live vault, so a
    failed export can't delete user history) so an export never serializes a page's oversized legacy history;
    the restore path runs `sanitizeRestoredNoteVersions` under the candidate key (raw SQL sharing
    the policy's `PRUNE_KEEP_NEWEST_SQL`) BEFORE re-key/field-migration, so a crafted
    ~5,000-row × ~50 KB-body archive can no longer OOM the process on history open or restore.
    Schema-compatible (no migration). Tests: `Phase149NoteVersionsRetentionTest` (14).
  - **Implemented in phase-74** (B2-UI-5, see `workspace/phase-74/REPORT.md`): the markdown-body
    save+read ARE serialized and latest-wins now, closing the last non-atomic body path (the old
    dispose-flush `File.writeText` / `produceState` `File.readText` truncate-race is gone; bodies
    live only in the field-encrypted `pages.extractedText` column). New pure-JVM
    `services/MarkdownBodySaveCoordinator.kt` serializes every body write through a per-page
    `kotlinx.coroutines.Mutex`, stamps a monotonic `seq` at `issue(...)` time, and `commitLatest`
    refuses (never runs the write; settles so awaiters release) any request that is no longer the
    latest issued one — the latest-wins comparator is issue-ORDER, never time-based, so touching a
    page before a slow older write can never let a stale snapshot win. `saveMarkdownNoteBody`
    (`NoteflowViewModel.kt:2214-2278`) issues on the calling (UI) thread, then
    `commitLatest { repository.updatePageBody(...); NoteBodyVaultPolicy.deleteLegacyNoteTextBody(...Defaults.importsDir) }`
    on `Dispatchers.IO`; the new `readMarkdownNoteBody` (`:2291-2336`) awaits settle + RE-FETCHES
    `repository.getPageById` (never a stale flow snapshot; deflates to the in-memory snapshot only
    on a transient decrypt failure so a key-lost race can never surface as an empty editor that gets
    saved over the real body); `flushPendingEditorSaves` (`:2929-2988`) re-issues each deferred body
    on the calling thread BEFORE any write. Both markdown `produceState` blocks (`MainActivity.kt:438/536`)
    now read `viewModel.readMarkdownNoteBody(page.id, …snapshot fallbacks)` — MainActivity no longer
    resolves note bodies or touches body files at all. B2-UI-1 lock semantics (defer, never drop)
    and B1-AUTH-05 imports-root confinement are retained.
  - **Implemented in phase-50** (B2-DOS-01, see `workspace/phase-50/REPORT.md`): stroke geometry is
    bounded at EVERY hop between DB bytes and the composable, so a crafted backup (B1-DB-7) or an
    organic heavy page can no longer OOM/ANR page-open or scale renderer work linearly. New pure-JVM
    `services/StrokeGeometryPolicy.kt` owns the budgets (20k pts/stroke, 200k pts/page, 2k strokes/
    page; per-stroke plaintext cap 2.5M chars, stored base64-ciphertext cap 3.4M chars — AES-GCM
    doesn't compress, so ciphertext length is an exact proxy) + `applySaveGate`/`gateStroke`/
    `capLoadedPoints`/`storedPointsJsonOverBudget`/`plaintextPointsJsonOverBudget`.
    `NoteRepository.saveStrokesForPage` (`:793`) routes every write through the gate (returns a
    `StrokeGeometryGateResult`); `getStrokesForPage` (`:636`) pages through the new
    `StrokeDao.getStrokesForPageBounded` (`Daos.kt:181`, `WHERE length(pointsJson) <= :maxStoredChars
    … LIMIT :limit OFFSET :offset`), refuses over-budget plaintext pre-Gson, caps legacy over-specified
    strokes and stops at the page budget. `EncryptionService.deserializeStrokes` (`:470`) guards the
    parse length; `ImportExportService.sanitizeRestoredStrokeGeometry` (`:1674`) DELETEs over-budget
    stroke rows from a restored backup DB before re-key/migrate/transplant; `NoteflowViewModel` shows
    ONE non-alarming snackbar per page per session via `maybeNotifyGeometryCapped` (latch cleared on
    lock); `AnnotationCanvas.kt:1415-1434` culls pages whose slab misses the visible world rect
    `(screen − pan)/zoom` in paginated mode. Tests: `B2Dos01StrokeGeometryTest` (18) — 1053 green.
  - **Implemented in phase-73** (B2-UI-3, see `workspace/phase-73/REPORT.md`): the shared
    `NoteRepository.lastSavedStrokeHash` diff cache and per-page saves are serialized, closing the
    concurrent-save interleave (older hash commit landing last drops the newest stroke / interleaved
    delete+upsert rounds drop rows / `ConcurrentModificationException` on the access-order
    `LinkedHashMap`). `lastSavedStrokeHash` (`:911-912`) is now
    `Collections.synchronizedMap(LruBoundedMap<…>)` (per-op atomic, B2-DOS-10 LRU bound preserved);
    new `pageSaveLocks = ConcurrentHashMap<String, Mutex>()` (`:944`) gives each page ONE fair/FIFO
    kotlinx `Mutex` that `saveStrokesForPage` (`:986-988`), `saveMediaEmbedsForPage` (`:1165-1167`)
    and `saveLayersForPage` (`:1244-1246`) all acquire via `lock.withLock { … }` before their Room
    `withTransaction` — so the full strokes→embeds→layers snapshot is atomic per page and different
    pages stay concurrent. `NoteflowViewModel.disposeEditorPageFlush` (`:2875-2892`)
    CANcels→joins the editor's debounce job then flushes the newest snapshot; `autosaveStrokes`
    (`:2907-2920`) is `suspend fun` so the write runs inline in the debounce job (cancellable +
    awaitable). `EditorScreen`'s dispose (`:443-451`) captures/clears the pending `saveJob` and
    routes through the VM helper. Tests: new `B2Ui3StrokeSaveConcurrencyTest` (11, behavioral model
    driven by the same `synchronizedMap`+per-page-`Mutex` primitives + source pins) — 1447 green, 0
    failures, `gradle assembleDebug` green. No schema change, no new deps.
  - **Implemented in phase-53** (B1-DB-2, see `workspace/phase-53/REPORT.md`): the plaintext→SQLCipher
    migration can no longer destroy the original plaintext database on failure.
    `NoteflowDatabase.migratePlaintextIfNeeded` (`:201-258`) swaps atomically — the encrypted scratch
    file is verified (exists, non-empty, no plaintext header) then `tempFile.renameTo(dbFile)` replaces
    the original via `rename()` (atomic on bionic/Linux), killing the old delete-then-rename window in
    which the user had NO database file; stale `-wal`/`-shm` are removed only after the verified
    encrypted file is in place. The catch block routes through the new pure-JVM `quarantineMigrateFailed`
    (`:487-510`): drops ONLY the scratch copy, preserves the original + `-wal`/`-shm`/`-journal` as
    `noteflow.sqlite.migrate-failed-<ts>`, and returns a timestamp so the caller raises the persistent
    corruption flag (`DatabaseSecurityHelper.setCorruptionDetected`) — the phase-43 recovery screen
    surfaces instead of silent data loss — then `throw e`.
  - **Implemented in phase-54** (B1-DB-3, see `workspace/phase-54/REPORT.md`): voice-note audio is
    encrypted at rest. `services/VoiceNoteCrypto.kt` (pure JVM): `*.enc` AES-256-GCM blobs under
    `filesDir/voice_notes/`, DEK + blob-name AAD `Noteflow-Voice-Note-v1|<name>` (blob-bound, never
    renamable), fail-closed encrypt/decrypt (`isEncryptedBlobName` on both paths), in-place re-key,
    legacy `.m4a` migration, orphan/temp sweeps, 40 MB blob cap. `VoiceNoteManager` records to a
    cacheDir temp → encrypt at stop; locked vault fails closed; playback decrypts to a transient
    cacheDir scratch deleted on stop/complete/release. `deletePagePermanently` (`NoteRepository.kt:635`)
    deletes AUDIO_NOTE blobs; `migrateLegacyPlaintextVoiceNotes` (`:667`) retargets rows via
    `MediaEmbedDao.updateContentUrlOrPath` (no schema change), gated on
    `SettingsManager.voiceNotesEncryptedMigrated`, WAL-checkpoints + re-stamps the DB HMAC first.
    `exportBackup` packs only `.enc`; restore re-keys blobs to the restoring device's DEK
    (`ImportExportService.kt:1708`). Tests: `B1Db03VoiceNoteEncryptionTest` (18) — 1129 green.
  - **Implemented in phase-79** (B2-DOS-03, see `workspace/phase-79/REPORT.md`): the voice RECORDER is
    bounded and the amplitude sampler is off-main. New pure-JVM `services/LiveWaveformBuckets.kt`
    (preallocated `FloatArray` accumulator, O(1)-amortized `append`, fold-on-full, `snapshot()` ≤
    `WaveformPeakMath.recordingLiveBuckets`=160) + `services/VoiceRecordingPolicy.kt` (decision table:
    30 min `MAX_RECORDING_DURATION_MS`, 32 MB `MAX_RECORDING_BYTES`, 100 ms tick,
    `MAX_STORED_WAVEFORM_ENTRIES`=600, non-alarming limit messages). `VoiceNoteManager` sampler runs on
    `Dispatchers.Default` (`VoiceNoteManager.kt:150`), appends `waveformBuckets.append(...)` + emits
    `waveformBuckets.snapshot()` (pre-fix `= _waveformAmplitudes.value + amp` full-list copy gone), and
    aborts past the duration/file-size ceilings via `finalizeRecording(limitMessage)` — stops+encrypts
    (B1-DB-3 path), surfaces `recordingError`, publishes `completedRecordingResult` so `EditorScreen`'s
    `LaunchedEffect` auto-attaches the audio embed through the shared `attachVoiceRecording` helper.
    `startRecording`/`stopRecording`/`finalizeRecording` are serialized under `recorderLock`.
    `NoteRepository.parseWaveformJson` (`:997-1009`) is bounded to 600 entries. Tests:
    `LiveWaveformBucketsTest` (9) + `VoiceRecordingPolicyTest` (6) + `B2Dos03VoiceRecordingTest` (12) —
    1429 app tests, only the 2 pre-existing `B1Plat01ReleaseSigningTest` failures.
  - **Implemented in phase-62** (B1-CRYPTO-03, see `workspace/phase-62/REPORT.md`): the master-password
    salt + wrapped-DEK pair is persisted atomically as ONE versioned blob
    (`services/MasterPasswordCredential.kt`, format `MPB1|<saltB64>|<wrappedDek>`, pure JVM). The old two
    independent SharedPreferences `.apply()` writes (`NoteflowViewModel.kt:1794-1795/1829-1830`) whose
    inter-write kill bricked the vault are gone: `SettingsManager.commitMasterPasswordCredential`
    (`SettingsManager.kt:101`) writes the blob + removes the two legacy keys in a single synchronous
    `commit()` (atomic temp-file+rename — a torn write leaves the previous complete blob) and returns the
    disk-acked result; the read accessor `masterPasswordCredentialOrLegacy` (`:84`) prefers the blob and
    falls back to the legacy pair so pre-fix vaults unlock until the next set/change migrates them.
    `setMasterPassword` (`:2094`) / `changeMasterPassword` (`:2145`) round-trip-validate the wrapped DEK
    before committing and abort (`return false`) before any in-memory state flips on commit failure.
    Tests: `B1Crypto03MasterPasswordAtomicTest` (7).
  - **Implemented in phase-63** (B1-CRYPTO-04, see `workspace/phase-63/REPORT.md`): NEW master passwords
    must clear the pure-JVM `services/PasswordStrengthPolicy.kt` (single decision table +
    `PasswordStrengthVerdict` with human-readable messages): ≥ 8 NFKC-normalized graphemes
    (`MIN_STRENGTH_GRAPHEMES` — stronger than the old 6 floor, still ≤ the 128 cap), no
    sequential/keyboard-row/single-run-repeat patterns, ≥ 3 distinct graphemes, and 3-of-4 class
    diversity for passwords < 12 graphemes (passphrases ≥ 12 pass on length alone). The policy judges
    the NFKC-normalized password (the exact bytes `EncryptionService.deriveKey` hashes, B2-CRYPTO-07).
    Authoritative gate in `NoteflowViewModel.setMasterPassword` (`:2071`) + `changeMasterPassword`
    (`:2134`, NEW password only) and surfaced with `verdict.message` by both Dialogs.kt master-password
    dialogs; unlock paths (`verifyMasterPassword`/`unwrapMasterDek`/`isMasterPasswordValid`) never
    strength-gate, so a pre-existing weaker vault keeps unlocking and rotating. The finding's
    "lockout is UI-only / vault only as strong as the password" caveat is documented in the policy KDoc;
    TEE-bound attempt gating / Argon2id remain tracked follow-ups (not introduced, no new deps).
    Tests: `B1Crypto04PasswordStrengthTest` (10).
  - **Implemented in phase-90** (B1-PLAT-8, see `workspace/phase-90/REPORT.md`): the strength floor is
    raised ≥ 10 NFKC-normalized graphemes (`PasswordStrengthPolicy.MIN_STRENGTH_GRAPHEMES` = 10, was 8)
    and common/prefix-suffix words are rejected (`isCommonPasswordVariant` — a widely-leaked base
    (`password`/`sunshine`/`letmein`/…) is refused reach-able whole or with only digit/symbol padding
    around it; structural, so genuine passphrases that merely contain a word keep passing). The policy
    KDoc + `docs/RELEASE.md` document explicitly that offline brute force on a copied vault is only
    mitigated by password ENTROPY, never by the on-device lockout (the 5-attempt UI lockout only
    throttles typing on-device). Enforced only at set/rotate; unlock never strength-gates, so
    pre-existing weaker vaults keep unlocking and rotating. Tests: `B1Crypto04PasswordStrengthTest`
    (17) + `B2Crypto04BackupPasswordTest` updated to the 10 floor (backups reuse the same bar).
    **Phase-90 review fix (commit `llops: phase-90 review fixes`)**: `isCommonPasswordVariant` is now
    evaluated FIRST in `PasswordStrengthPolicy.evaluate` (after the 128-grapheme cap, before the 10-floor
    and before sequential detection), so a bare `password`/`sunshine` and the `password123`/`123password`
    keyspace report `COMMON_PASSWORD` — the review found the phase-90 build gave those a misleading
    `TOO_SHORT`/`SEQUENTIAL` verdict because the length/sequential checks ran first. Accept/reject is
    unchanged (the common check only ever adds rejection); only the user-facing reason string moved.
    Pinned by additional assertions in `B1Crypto04PasswordStrengthTest` (`password`/`sunshine`/
    `iloveyou` bare, `password123`/`monkey1234`/`123password` sequential-pad) and `B2Crypto04BackupPasswordTest`
    (`password123` → COMMON_PASSWORD; `PASSWORD12X` → LOW_DIVERSITY re-annotated as the documented
    letter-embedded-decoration residual).
  - **Implemented in phase-92** (B1-AUTH-07, see `workspace/phase-92/REPORT.md`): BOTH in-app
    master-password verification surfaces now share ONE persisted lockout. `isMasterPasswordValid`
    (`NoteflowViewModel.kt:2908-2923`) — the create-backup dialog's pre-export check at
    `HomeScreen.kt:1316` — was a side-effect-free oracle (ignored `lockoutActive()`, never bumped
    the counters) allowing unlimited full-PBKDF2 guesses that never tripped the LockScreen's
    lockout. New shared helpers in `NoteflowViewModel`: `recordFailedMasterPasswordVerification()`
    (`:2867-2879`, bumps `_failedUnlockAttempts` + persisted `settings.failedUnlockAttempts`; at
    `MAX_FAILED_ATTEMPTS`=5 persists `settings.lockoutUntilEpochMs` via `computeLockoutDelayMs`
    exponential backoff, starts the countdown ticker, AND calls `lock()` so an in-app tripped
    lockout performs the same data-layer teardown as a real lock — B1-AUTH-02 posture) and
    `resetMasterPasswordVerificationCounters()` (`:2886-2891`). `verifyMasterPassword` (`:2806`)
    and `isMasterPasswordValid` (`:2917`/`:2921`) delegate to them — the old inline
    `settings.failedUnlockAttempts = newCount` catch block is gone. `isMasterPasswordValid` now
    checks `lockoutActive()` FIRST (refuses before any PBKDF2 work), guards
    `masterPasswordCredentialOrLegacy == null`, records failures via the shared helper, and still
    zeroizes the DEK + resets on success; it stays deliberately strength-gate-free (set/rotate
    only). The backup dialog still re-authenticates immediately before `exportBackup` and now
    distinguishes a tripped/active lockout message from a plain wrong password. Tests:
    `B1Auth07IsMasterPasswordOracleTest` (11).
  - **Implemented in phase-64** (B1-CRYPTO-05, see `workspace/phase-64/REPORT.md`): a stored DEK
    device wrapper that becomes undecryptable (AndroidKeyStore key lost/unreadable) is NEVER
    silently re-keyed. Pure-JVM `services/DekReadResult.kt` defines sealed `DekReadResult`
    (`NoBlob` / `Unlocked(dek)` / `AuthRequired` / `KeyLost(wrapperAlias)`) + typed
    `KeystoreKeyLostException(message, wrapperAlias)`. `SecurityService.readDekResult()`
    (`SecurityService.kt:162-186`) distinguishes "no blob stored" from "blob present but its
    wrapping key is gone" (the old `readDek()` collapsed both to `null`); `getOrCreateDek`
    (`:203-236`) THROWS `KeystoreKeyLostException` on `KeyLost` at every mint site and mints only
    from `NoBlob` + `allowPasswordlessMint` — the stored wrapper can never be overwritten by a
    fresh DEK, so the phase-43 quarantiner is never tripped for this cause. `storeDek` stamps a
    non-secret `wrapperAlias` + `wrapperVersion = 1` marker persisted/cleared by
    `SharedPrefsDekDeviceStore` (`dek_wrapper_alias`/`dek_wrapper_version`). Recovery UX:
    `NoteflowViewModel` gains a `_keystoreKeyLost` StateFlow gated into `dbGate` (third input
    alongside `_authenticated` + `_corruptionBlocked`), the passwordless init routes through
    `readDekResult` (Unlocked→use, NoBlob→mint, AuthRequired/KeyLost→recovery state — the old
    `var dek = readDek(); if (dek == null) mint()` collapse is gone), `initializeData`'s catch
    surfaces key-lost (not corruption) when the corruption flag is clear, `setMasterPassword`
    throws `KeystoreKeyLostException` on `KeyLost`, and two exits exist:
    `attemptKeystoreKeyLostRecoveryFromBackup` (validates the backup password BEFORE closing the
    DB, mints a fresh DEK in memory, imports the backup re-keyed into it, and persists the device
    wrapper ONLY AFTER the restore succeeds — a failed restore never overwrites the old wrapper)
    and `startFreshAfterKeystoreKeyLoss` (moves the old vault aside as
    `noteflow.sqlite.keystore-lost-<ts>` via `quarantineVaultFiles`, bytes preserved — never
    quarantined as corrupt). `MainActivity` renders the dedicated `KeystoreKeyLostScreen` between
    the corruption and restore screens. Tests: `B1Crypto05SilentRekeyTest` (16).
  - **Implemented in phase-163** (see `workspace/phase-163/REPORT.md`): both recovery screens
    (`CorruptionRecoveryScreen`, `KeystoreKeyLostScreen`) gained a real, PERSISTED "Don't show
    again for this … event" control. Dismissals are keyed to the recovery EVENT, never a bare
    boolean: `RecoveryDismissalPolicy.mayShow(blocking, eventTs, dismissedTs)` suppresses only the
    SAME event timestamp, re-shows on a NEW stamp (fresh `setCorruptionDetected` / a different
    keystore-lost wrapper alias), and fails closed for un-keyable legacy events. Prefs live in
    `DatabaseSecurityHelper` (`corruption_dismissed_timestamp`,
    `keystore_lost_{event_alias,event_timestamp,dismissed_timestamp}`, `.commit()` because the
    restore path exits the process). The keystore-lost event identity is exactly ONE
    `readDekResult()` result: `DekReadResult.AuthRequired` now carries the same non-secret
    `wrapperAlias` as `KeyLost`. The phase-87 DB-integrity banner dismissal remains per-session.
  - **Implemented in phase-65** (B1-CRYPTO-07, see `workspace/phase-65/REPORT.md`): the vault-DEK
    biometric AndroidKeyStore key is now ONLY ever created STRONG-bound (API 30+); on API 26-29 the
    biometric-lock feature is refused/downgraded. Pure-JVM `services/BiometricKeyBindingPolicy.kt`
    is the single decision table: `MIN_API_FOR_STRONG_BIOMETRIC_BINDING = 30`,
    `strongBiometricKeyBindingSupported(apiLevel)`, `refuseEnableMessage(apiLevel)` (non-alarming),
    and `PRE_30_BIOMETRIC_ONLY_VALIDITY_SECONDS = -1` — the ONLY pre-30 validity that excludes a
    device credential (AOSP: non-(-1) validity, incl. the default 0, maps to
    `HW_AUTH_PASSWORD | HW_AUTH_BIOMETRIC`, so a screen PIN satisfies a bare
    `setUserAuthenticationRequired(true)` key). Enforcement layers: `NoteflowViewModel.setBiometricEnabled`
    (`:2531`) REFUSES enabling below API 30 before the setting flips (one-shot `biometricRefusalMessage`
    StateFlow `:1106`); `enforceDekAtRestPolicy` (`:2194`) DOWNGRADES a legacy enabled state below API 30
    to password-only (setting off + `clearDek()`, never re-writes the weak-bound copy); `SecurityService.getOrCreateKey`
    (`:76-98`) binds any pre-30 auth key defensively via `setUserAuthenticationValidityDurationSeconds(-1)`;
    `getDecryptionCipher` (`:105-127`) + `getBiometricCipher` return null below API 30 (LockScreen falls
    back to the master password + `disableBiometricFallback()`). The finding's explicit API-level marker:
    `storeDek` stamps `DekDeviceBlob.wrapperApiLevel = Build.VERSION.SDK_INT` persisted as
    `dek_wrapper_api_level` — informational/auditable only, deliberately NOT read-gated (a pre-fix
    API-30+ blob carries marker 0 and MUST still unlock). `BiometricAuthHelper` now distinguishes
    "strong biometric available at prompt time" (`isBiometricAvailable`) from "key can be STRONG-bound"
    (`canCreateStrongBiometricBoundKey`); the settings dialog gates the switch on the latter and shows
    the refusal message. `DekAtRestPolicy.modeFor` gained `strongBiometricBindingSupported: Boolean = true`
    (3rd arg, default keeps 2-arg call sites compatible). Out of scope (documented, untouched):
    `WebDavCredentialStore`'s positive-duration pre-30 binding is the B1-NET-08 design, and the minSdk
    bump to 30 is a product decision. Tests: `B1Crypto07BiometricKeyBindingTest` (20).
  - **Implemented in phase-87** (B1-DB-6, see `workspace/phase-87/REPORT.md`): the tamper HMAC now
    authenticates `main + -wal` AND the banner dismissal is per-session. The pre-fix main-file-only
    inline loop in `DatabaseSecurityHelper.computeDatabaseHmac` (`DatabaseSecurityHelper.kt:50-65`)
    is replaced by the new pure-JVM `services/DatabaseHmacPolicy.kt`
    (`streamDbAndWal` `:42` streams `noteflow.sqlite` then its `-wal` companion through the same
    initialised `Mac`, returning total bytes consumed) — a WAL-only mutation committed between two
    checkpoints (the vault runs `JournalMode.WRITE_AHEAD_LOGGING`) is now detected at the next
    verification, and every baseline-arming site already checkpoints first or reads a closed raw
    file (the export/migration sites `NoteflowViewModel.kt:1348-1350`/`:1375-1377`/`:2466-2471`/`:3126-3128`,
    `HomeScreen.kt:529-531`/`:1320-1322`, the restore `rearmBaselineFromFile` at `ImportExportService.kt:1805`,
    and the migration stamp at `NoteflowDatabase.kt:250`), so a freshly armed baseline covers
    `(main + empty/absent wal)` with a cleanly-emptied WAL
    contributing byte-identical state to an absent one. `NoteflowViewModel.dismissDatabaseIntegrityWarning`
    (`NoteflowViewModel.kt:1106-1109`) no longer flips `databaseIntegrityCheckEnabled` and neither
    dismissal path touches the persisted `databaseIntegrityWarningDismissed` latch; the banner routes
    through the new pure-JVM per-session `services/IntegrityWarningDismissalGate.kt`
    (`integrityWarningDismissal.mayShow()` `:1091`, `.onDismiss` `:1107`, `.onReenable()` `:1115`),
    re-armed on every launch, and the checkbox is relabelled "Don't show again this session"
    (`MainActivity.kt:362`). Documented trade-offs: a process kill after a baseline arm leaves
    `-wal` frames that flag at the next launch (the intended detection flip-side), and a
    pre-phase-87 stored main-only checksum fails the first post-upgrade verify only when a leftover
    non-empty `-wal` is present at verify time — both surfaced as a per-session-dismissible banner.
    B1-CRYPTO-06's fail-open re-baseline at `verifyDatabaseIntegrity`
    `:147-152` untouched (own phase-91 finding; must account for the re-arm now hashing `main + wal`).
    Tests: `B1Db06WalCoverageAndDismissalTest` (16).
  - **Implemented in phase-91** (B1-CRYPTO-06, see `workspace/phase-91/REPORT.md`): the tamper
    baseline verification is FAIL-CLOSED and write-free. `DatabaseSecurityHelper.verifyDatabaseIntegrity`
    (`DatabaseSecurityHelper.kt:173-178`) returns a sealed three-outcome `DatabaseIntegrityVerdict`
    from the single pure-JVM decision table `services/DatabaseIntegrityPolicy.kt`
    (`verdictFor(storedChecksum, currentChecksum)` — `Verified` = baseline present + matching current
    main+`-wal` HMAC via `ConstantTime.hexEqual`; `Mismatch` = baseline present + differing bytes;
    `CannotVerify` = baseline MISSING or current HMAC un-computable). The pre-fix
    `stored==null → updateStoredChecksum(context); return true` silent re-baseline and the
    `?: return true` collapse are gone — the helper NEVER writes, `hasStoredChecksum`
    (`DatabaseSecurityHelper.kt:115-118`) is the new read-only accessor, and the pref stays
    write-only through `updateStoredChecksum`/`rearmBaselineFromFile` at the trusted arm sites.
    `NoteflowViewModel.verifyDatabaseIntegrityNow` (`NoteflowViewModel.kt:1188-1193`) maps the
    verdict: `Mismatch` → existing per-session tamper banner (B1-DB-6 gate), `CannotVerify` →
    DISTINCT non-alarming notice (`MainActivity.kt` tertiaryContainer banner; wording from the
    policy `CANNOT_VERIFY_NOTICE`; shared per-session dismissal), `Verified` → clears both. The only
    auto-arm is a brand-new vault: `initializeDataCore` (`:1509-1520`) arms iff
    `!vaultFilePresentAtStart && !hasStoredChecksum(appContext)` — an existing vault is never
    re-baselined from a live-file verify. Tests: `B1Crypto06DatabaseIntegrityPolicyTest` (11).
  - **Phase-91 review fixes** (commit `llops: phase-91 review fixes`, B1-CRYPTO-06 review findings):
    (a) the "fresh vault" probe is WAL-aware — `vaultFilePresentAtStart` (`NoteflowViewModel.kt:139-145`)
    now also treats a populated `noteflow.sqlite-wal` as an EXISTING vault, so a WAL-resident vault
    whose main file is 0-length/missing can never be silently re-baselined as a first run, and
    `computeDatabaseHmac` (`DatabaseSecurityHelper.kt:49-60`) returns null (CannotVerify) only when
    the main file is empty AND `-wal` has no frames (an empty main + populated WAL stays computable);
    (b) the FIRST verification of a PASSWORDLESS vault is deferred until the initial data open settles
    (`firstDataInitDone` gate — `NoteflowViewModel.kt:158-168`, released in `initializeData`.finally and
    the key-lost/anomalous DEK branches), removing the false-Mismatch race against concurrent WAL
    recovery; locked vaults still verify immediately at init (at-rest file untouched until unlock);
    (c) `CannotVerify` now honors the SAME per-session dismissal gate as `Mismatch`
    (`_databaseIntegrityUnverified.value = !freshUnarmedVault && integrityWarningDismissal.mayShow()`,
    `NoteflowViewModel.kt:1216-1221`); (d) the two banner blocks in `MainActivity.kt` are deduplicated
    into ONE `IntegrityBannerCard` composable (`:1089-1150`). No schema change, no migration, no new
    deps. Tests: `B1Crypto06DatabaseIntegrityPolicyTest` 14 green (added: WAL-aware probe, empty-main
    + populated-WAL computability, passwordless-deferral pins) + `B1Db06WalCoverageAndDismissalTest`
    (16) green — `gradle testDebugUnitTest` 1580 total green (0 failures).
  - **Implemented in phase-136** (R2-B1D-01, see `workspace/phase-136/REPORT.md`): the tamper
    baseline is re-armed at every SESSION END, not only at event-driven mutations. Pre-fix, the
    WAL-aware baseline (`DatabaseSecurityHelper.updateStoredChecksum`/`rearmBaselineFromFile`) was
    armed only at fresh-vault/migration/re-encrypt/backup/restore sites, so ordinary note edits
    (committed-but-uncheckpointed WAL frames) verified against a baseline that predated them and
    raised a FALSE "Database integrity check failed" banner at the next process start.
    `NoteflowDatabase.dispose()` (`NoteflowDatabase.kt:482-520`) — the single session-end funnel
    (master-password `lock()` `NoteflowViewModel.kt:4056-4060`, app exit `onCleared` `:4089`,
    restore `NoteRepository.closeDatabase` `:501`, reopen `reopenDatabase` `:511`) — now
    FULL-checkpoints the WAL on the still-live keyed connection
    (`db.query("PRAGMA wal_checkpoint(FULL)", null)` fully stepped, `:489-497`), closes the vault
    (`db.close()` `:500`, best-effort), and re-arms the stored baseline against the now-quiescent
    file via `DatabaseSecurityHelper.updateStoredChecksum` (best-effort `runCatching` so a
    keystore/prefs failure never breaks lock/restore). **Review fix:** the expensive full-file
    HMAC + checksum-prefs commit runs on a single-thread daemon executor (`REARM_EXECUTOR`,
    `NoteflowDatabase.kt:69-74`) so `lock()`/`onCleared()` never block on it; ordering is
    preserved because `getDatabase` joins the pending re-arm (`:444-447`) before rebuilding the
    vault and `onCleared` awaits it at app exit (`NoteflowDatabase.awaitPendingRearm()`, `:529-531`,
    called from `NoteflowViewModel.kt:4102`). The baseline helpers persist with `commit()` (not
    `.apply()`) so a hard kill cannot drop the re-arm. The app context needed for the checksum
    prefs is cached when the DB is built (`cachedAppContext`, `getDatabase` `:437`). The session's
    own writes therefore land IN the baseline — the next start verifies clean — while post-exit
    tampering of `main`/`-wal` still trips the tripwire (B1-DB-6 coverage unchanged). The lock
    boundary is only disposed for master-password vaults (the passwordless boot DEK is the
    credential; no session boundary), though a clean app exit re-arms for passwordless vaults too
    (intended: they already hold a baseline from first-run/migration/backup). `verifyDatabaseIntegrity`
    still NEVER re-baselines (B1-CRYPTO-06 invariant preserved). Tests: `Phase136TamperBaselineCadenceTest`
    (8 — pure-JVM cadence decision arm→edit→re-arm→Verified vs arm→edit→Mismatch control, WAL-fold +
    empty-WAL invariance, and source pins for the checkpoint→close→schedule ordering, the
    getDatabase-join, the master-password-gated lock funnel, the onCleared-await, the
    restore/reopen funnel, the commit-not-apply helpers and the verify-never-rebaselines pin).
  - **Implemented in phase-88** (B1-DB-8, see `workspace/phase-88/REPORT.md`): decrypt-failure
    fallbacks never render RAW CIPHERTEXT as note content. Single pure-JVM decision table
    `services/DecryptFailurePolicy.kt` owns `render(storedValue, decrypted, isCiphertext)` — the
    ONLY render outcome (legacy plaintext verbatim, authenticated ciphertext's plaintext, or
    `UNREADABLE_MARKER` = "Unreadable (decryption failed)"), the structural classifier
    `isStructuralCiphertext` (keeps legacy plaintext rows out of the decrypt branch), the
    persistent-failure threshold (`PERSISTENT_FAILURE_THRESHOLD` = 10 DISTINCT records) and the
    non-alarming notices. Every `NoteRepository` display-field decrypt site routes through it:
    `getStrokesForPage` (`:946-960`, text via `decryptFieldForDisplay`, geometry via
    `decryptStoredGeometryOrBlank` — an unreadable row yields an EMPTY payload, never phantom ink or
    raw ciphertext into `deserializeStrokes`), `getMediaEmbedsForPage`, `getNoteVersions` and
    `decryptPageIfNeeded` (the pre-fix catch-all `catch { page }` that returned the page — encrypted
    title/body — unchanged is gone). Each failed auth while a DEK is present (a locked vault never
    records) is counted once per session in a deduped ledger (`NoteRepository.kt:79-99`); when the
    threshold is crossed `decryptFailureListener` fires once and `NoteflowViewModel.initializeDataCore`
    (`:1330-1343`) escalates to the existing corruption/restore event — `setCorruptionDetected` +
    `_corruptionBlocked` (recovery screen: restore-from-backup / re-key / start-fresh) plus a
    non-alarming `PERSISTENT_DECRYPT_FAILURE_NOTICE` snackbar, never silent degradation. The ledger
    + in-memory escalation are reset at every legitimate session boundary (`lock()`, re-key
    `changeMasterPassword`, WebDAV restore, and every `initializeData`), so a fresh unlock recounts.
    Phase-88 review fixes: the ledger is deduped per NOTE (`note:<pageId>`, `NoteRepository.kt:101-106`)
    so a single broken note — however many of its rows/fields fail — can never trip the threshold on
    its own; `decryptPageIfNeeded` no longer early-returns the raw page when the DEK is null (the
    `lock()` zeroize-before-dispose race now renders the marker, consistent with the other three
    sinks); and `loadSearchCorpus` drops undecryptable pages (`decryptPageOrNullForCorpus`, fails
    recorded only by the display reads, never a rankable marker).
  - **Implemented in phase-169** ("pages become Unreadable after export/import", see
    `workspace/phase-169/REPORT.md`): the export path is a faithful checkpoint→verify→stage snapshot
    (`ImportExportService.exportBackup`, no AAD/id/DEK mutation), so export itself never corrupts a
    row. The reported symptom was the cross-key RESTORE path: `migrateFieldCiphertexts` re-keys every
    `fieldEncryptedColumns` row to the restoring device's DEK with the SAME per-record AAD, but a row
    that failed to re-key (damaged/already-unreadable ciphertext) was silently left under the old DEK
    — after the SQLCipher re-key that row is permanently unreadable. NOW the per-value decision is
    explicit pure-JVM `ImportExportService.reencryptFieldOutcome`
    (`FieldReencryptOutcome.{Migrated,LeavePlaintext,AuthFailed}`); `migrateTable` counts
    `AuthFailed` rows and throws `RestoreReEncryptionException` — the restore is REJECTED before any
    swap, the temp DB is quarantined (`quarantineRejectedRestoredDb`), and
    `UiFailureTextPolicy.RESTORE_REENCRYPT_FAIL_TEXT` surfaces as fixed text (never the raw count).
    Second data-loss guard: `NoteRepository.updatePageBody` / `updatePageTitleAndTags` /
    `renamePage` refuse to persist the literal `UNREADABLE_MARKER` (typed
    `UnreadableContentWriteException`) — an editor/rename pre-filled with the marker could otherwise
    permanently overwrite the still-recoverable encrypted original — and every `NoteflowViewModel`
    save/rename/flush surface catches it and shows `DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE`;
    `autoTagLanguageOnSave` skips it silently (cosmetic background merge). Round-trip proof:
    `Phase169ExportImportRoundTripTest` (encrypt→re-key→decrypt for all 7 columns, legacy global-AAD
    migration, same-DEK identity, the missed-re-key control that renders the marker).
- **Canvas**: `ui/components/AnnotationCanvas.kt:83` (ink canvas, gestures, layers, `pointerInteropFilter`);
  `services/WetBrushEngine.kt:13` (AGSL wet-mixing gating); `ui/components/ShaderCapabilityHelper.kt:5`
  (`isAgslSupported` = SDK ≥ 33); `services/ShapeRecognitionHelper.kt:13` (`trySnapShape()` :27).
  Supporting math: `WetCanvasEngine.kt`, `WetMixingMath.kt`, `BrushStrokeMath.kt`, `StrokeStabilizer.kt`.
  - **Implemented in phase-124**: TWO eraser modes — whole-stroke delete (STROKE) & smooth,
    pressure-aware partial erase (PARTIAL). Mode enum + picker + persistence were phase-19
    (`EraserMode` in `services/StrokeSegmenter.kt:14`, `SettingsManager.eraserModeKey`, tool-picker
    chips in `EditorScreen.kt:1813`); phase-124 added the pressure/per-radii plumbing + cursor
    preview + hit-test hardenings: `services/EraserGeometryPolicy.kt:23` is the pure-JVM radius
    decision table (`stampRadius(baseWidth,pressure)` pressure-aware round mask, `coverageRadius`
    = stamp + half nib so the cut is always round, `previewRadius` for the cursor circle,
    `legacyRadius` byte-compatible fallback). `EraseSample(pos,pressure)` (`AnnotationCanvas.kt:85`)
    lets each erase-path sample carry its touch pressure; `applyEraser` (`:699`) stamps them as
    `ErasePoint(..., radius)` (`:705`) → `StrokeSegmenter.segment` (`StrokeSegmenter.kt:110`) now
    splits per-sample via `coverageRadiusFor` (`:52`), so a heavy press carves a wider round swath.
    Cursor preview: non-consuming pointer tracker (`AnnotationCanvas.kt:594`) mirrors the pointer
    into world coords; the canvas draws the round mask (PARTIAL, `:1595`) or the whole-stroke
    highlight (STROKE, `:1604`, symmetry-mirror aware). Hit-test hardenings: pure-JVM
    `StrokeSegmenter.hitStrokeAt` (`:68`, topmost + symmetry) and `strokeContainsPoint` now also
    hits `stroke.end` (`AnnotationCanvas.kt:3753`) so shape-stroke tips erase. Undo covers both
    modes (every erase change flows through `EditorScreen.handleStrokesChange` pre-state capture,
    `EditorScreen.kt:588`). Tests: `Phase124EraserTest` (17) + existing `StrokeSegmenterTest` (16).
  - **Implemented in phase-123**: colour/layer/tool selections are effective for the VERY NEXT stroke.
    `AnnotationCanvas.kt:634` — `activeLayerId` + `layers` were missing from the drawing `pointerInput`
    restart-key list, so a layer switch (unlocked→unlocked) left the stroke-commit closure
    (`:855` `val actLayerId = activeLayerId` → `:877` `layerId = actLayerId`) capturing the PREVIOUS
    layer until another key (tool/colour/width) forced a restart — the "must switch pens first" bug;
    both are now keys. `AnnotationCanvas.kt:247` — the TEXT-tool colour `textSelectedColorInt` was a
    keyless `remember` snapshot of the brush colour at first composition; now `remember(currentColor)`
    so the next text stroke follows a newly picked colour. `EditorScreen.kt:3011/:3026/:3041` — the
    three HSV sliders called `onColorSelect(derivedColor)` (the previous composition's colour, so the
    final slider position never landed); each now converts its just-changed channel inline
    (`HSVToColor(floatArrayOf(it,s,v))` / `(h,it,v)` / `(h,s,it)`). Tests: `Phase123ImmediateSelectionTest` (12).
  - **Implemented in phase-178** (see `workspace/phase-178/REPORT.md`): the per-page reference-image
    underlay (ROADMAP Phase-07 encouraged item). A photo inserted from the editor's overflow menu
    ("Insert Reference Image") persists as a single `media_embeds` row (`MediaEmbedType.REFERENCE_IMAGE`,
    **no schema change**) — the opacity config lives in the field-encrypted `textContent` column
    (`ReferenceImagePolicy.encodeConfig`/`decodeOpacity`, range-gated 30–50%, default 40%), geometry in
    the row's plain columns like a `PHOTO` embed. The underlay is excluded from the draggable embed set
    (`NoteRepository.getCanvasItemsForPage`) and carried forward as a RAW entity across every
    delete+reinsert page save (`saveMediaEmbedsForPage`) so editor flushes never erase it.
    `AnnotationCanvas` renders it above paper/template/page-bitmap yet strictly below the ink pass in
    all three modes (single / seamless / paginated — paginated honors page offset + viewport culling);
    artwork is stored RELATIVE and every read+delete re-resolves it through `InlineImagePathPolicy`
    (B1-AUTH-05 app-private confinement); ingestion uses the bounded `AttachmentIngestPolicy` reader.
    UI: overflow Insert/Remove, SAF picker, an opacity slider + removal card, and `insertPage` shifts
    the underlay's page index/y. It is never exported — the only embed→render export surface draws
    `PHOTO` embeds only. Tests: `Phase178ReferenceImageUnderlayTest` (6 policy + 5 source-pins).
- **Plugins**: `plugin-sdk` → `plugins/FrameworkPlugin.kt:58` (`interface NoteflowPlugin`),
  `plugins/PluginCapability.kt:28` (sealed capability set); `plugins/PluginRegistry.kt:75`,
  `plugins/PluginManager.kt:83`; store: `plugins/store/PluginStoreCatalog.kt:57`, `PluginStoreController.kt:45`.
  - **Implemented in phase-67** (B1-AUTH-03, see `workspace/phase-67/REPORT.md`): the plugin
    lifecycle is vault-lock-gated. `PluginRegistry` gained a pure-JVM pause/resume gate —
    `pauseLifecycle` (`PluginRegistry.kt:219`) tears down every live onEnable hook with
    `onDisable` + clears `enabledNotified`; `resumeLifecycle` (`:238`) re-fires hooks via
    `onProcessStart`; `onProcessStart` early-returns while paused (`:184`) and the `setEnabled`
    enable path is guarded by `!lifecyclePaused` (`:279`). `NoteflowViewModel`'s init block now
    boots the plugin layer ONLY for a passwordless already-authenticated start
    (`if (!settings.hasMasterPassword) startPluginLifecycle()`, `:258-272`); the new idempotent
    `startPluginLifecycle()` (`:285-312`) owns store re-materialization + hook firing, called
    from both unlock paths (`verifyMasterPassword` `:2489`, `verifyBiometricsAndUnlock` `:2643`);
`lock()` pauses the lifecycle + resets the flag (`:3204-3205`). No plugin code runs
    before unlock. Phase-67 review-fix (same commit): the gate now covers the whole
    live-Context surface, not just hook firing — `containedAvailability` reports
    `Unavailable` without invoking `plugin.availability(context)` while paused
    (every derived-state query + capability route fails closed on the LockScreen,
    incl. the previously out-of-scope post-lock dispatch), `setEnabled` disable +
    `uninstallPlugin` only fire `onDisable` for a plugin whose `onEnable` ran this
    process, `notifyConfigChanged` returns early while paused, the ViewModel's
    `refreshPluginStates()`/`testPlugin()` no-op while `pluginRegistry.isLifecyclePaused`
    (`:361`,`:381`), and `pluginLifecycleStarted` is `@Volatile` + double-checked
    (`synchronized`) so racing unlock paths can never boot the layer twice.
  - **Implemented in phase-126** (off-by-default policy, see `workspace/phase-126/REPORT.md`):
    ALL plugins are disabled by default — every bundled/compiled plugin is strictly
    opt-in, verified over the full `defaultPlugins()` set by
    `PluginOffByDefaultTest` (6 tests). Audit found enablement already defaults off:
    `SettingsManager.isPluginEnabled` → `prefs.getBoolean("plugin_enabled_<id>", false)`
    (`services/SettingsManager.kt:340-341`); nothing auto-enables in
    `PluginRegistry` (opt-in is the only write path, `PluginRegistry.setEnabled` `:273`;
    `defaultPlugins()` `:855` registers definitions only); `onProcessStart` fires
    hooks only for enabled plugins (`:195`); capability routing refuses un-opted-in
    plugins with `NONE_ENABLED` (`PluginManager.kt:188-198`); store installs start
    REGISTERED (off) (`PluginRegistry.installPlugin` `:394-405`); upgrade keeps prior
    explicit choices via the persisted `plugin_enabled_<id>` + `plugin_ever_enabled_<id>`
    flags (`SettingsManager.kt:340-359`). CaseChangePlugin remains the store's OPTIONAL
    plugin (NOT in `defaultPlugins()`, downloaded → REGISTERED/off).
  - **Implemented in phase-177** (full ecosystem review, see `workspace/phase-177/REPORT.md`):
    off-by-default re-verified + pinned over the whole compiled set (`SettingsManager.kt:447-448`);
    store rows + settings switch are driven by the SAME enable store the router reads — single
    source of truth, `off ⇔ REGISTERED/DISABLED`, `on ⇔ ENABLED/AVAILABLE/UNAVAILABLE`, never
    both/neither; delete is confirmation-gated on the single delete path
    (`PluginStoreDialog.kt:535-558`) and `PluginStoreController.delete` runs
    `deleteDownloadedAssets` before `uninstallPlugin`; rejected plugins show Delete only.
    New pins: `Phase177PluginEcosystemReviewTest` (3 tests). Bug fix: the phase-146
    R2-b2b2-DEP-03 lockfile pin was stale after phase-175 made `:plugins:mlkit` resolve
    `mlkit:translate` → okhttp-3.0.0/okio-1.6.0 as verified jars; the pin now accepts a
    verified jar (sha256) or a retained POM-only entry (`Phase146BuildIntegrityTest`).
  - **Implemented in phase-129** (see `workspace/phase-129/REPORT.md`): pre-phase-35 floating ink bar
    restored + minimap fixed. Posture is orientation-only via pure-JVM `services/DockPosturePolicy.kt`
    (`InkBarPosture.HORIZONTAL` portrait / `VERTICAL` landscape + default anchors): `FloatingToolDock`
    (`EditorScreen.kt:2233`) renders `InkBarPortraitBar` (`:2498`, 56dp capsule, `surfaceContainerHigh`,
    `tonalElevation 6dp`, `shadowElevation 8dp`, 1dp 50%-alpha `outlineVariant` border, `spacedBy(4.dp)`,
    `BottomCenter` + `bottom 20dp`) and `InkBarLandscapeBar` (`:2657`, 56dp side `Column`, `spacedBy(6.dp)`,
    `HorizontalDivider`, `CenterEnd` + `end 20dp`); all 9 pre-35 features exist in both (tool selector w/
    `getToolIcon`/`displayTool.label` + `primaryContainer` highlight, Scroll/Pan toggle, color swatch,
    width badge, divider, Tune settings, Undo/Redo, `HIDDEN_DRAWING` auto-tuck + tap restore `:1769`,
    default anchors). Drag/snap/session-persist extras are opt-in, OFF by default via
    `services/FloatingWidgetDragPolicy.kt` (defaults fail-closed; `compressedOffset`/`constrainWithinSafeArea`
    pure-JVM), session offset hoisted to EditorScreen state (`:421-459`) and persisted only on opt-in.
    Minimap: `services/MinimapGeometryPolicy.kt` (`aspectFit` uniform scale, `MAX_BOX_WIDTH_DP 120f`/
    `MAX_BOX_HEIGHT_DP 140f`, `MIN_SIDE_DP 48f`, `DEFAULT_MARGIN_DP 16f`) drives the `AnnotationCanvas.kt:1705+`
    HUD so size matches the canvas-world aspect, default anchor bottom-right, pointer-drag + snap-clamp
    gated by `minimapDraggable` (default OFF), collapsible header kept; `minimapHudEnabled` default
    reverted to OFF (`SettingsManager.kt:317` ← `MinimapGeometryPolicy.VISIBLE_BY_DEFAULT=false`).
    Settings sheet gained the 4 toggles (`EditorScreen.kt:4049`). Tests: `Phase129InkBarMinimapPolicyTest` (23).
  - **Implemented in phase-150** (R2-b2b4-DOS-02/03 + R2-b2b5-FEA-04, see `workspace/phase-150/REPORT.md`): canvas
    memory + per-frame render budgets. `services/LayerRenderBudgetPolicy.kt` owns the LIVE layer cap
    (`MAX_LIVE_LAYER_COUNT` = 16, the SAME number as the phase-82 export cap) + `MAX_RESIDENT_BITMAP_BYTES` = 64 MB,
    and the top-`zOrder`-`rowid` ordering SQL (`BOUNDED_TOP_LAYERS_ROOM_SQL` wired verbatim into
    `LayerDao.getTopLayersForPageBounded`, `KEEP_HIGHEST_Z_LAYERS_RAW_SQL` into the raw restore sanitizer).
    `NoteRepository.getLayersForPage` materializes ONLY the top-16 read; `EditorScreen.onAddLayer`/`onDuplicateLayer`
    fail closed at the cap with the non-alarming `layerLimitNotice()`; `NoteflowViewModel.loadEditorCanvasPage`
    raises the one-time `maybeNotifyLayersCapped` notice; the restore path runs `sanitizeRestoredLayerCounts`
    under the candidate key and the export trims the STAGED snapshot (`pruneStagedSnapshotLayers`), both via
    the shared `pruneLayerPagesToLiveCap`. The renderer's resident rasters are now `ui/components/LayerBitmapLruCache.kt`
    — a byte-budgeted LRU (access-order `LinkedHashMap`, evicts to `BitmapPool`, `.clear()` releases on
    invalidation/unmount) replacing the old never-evicted `mutableMapOf`. `services/MinimapGeometryPolicy.kt`
    gained the minimap work budget (`MAX_MINIMAP_SAMPLED_STROKES` 120 + `MAX_MINIMAP_POLYLINE_SEGMENTS` 400 +
    `strokeStepFor`/`pointStepFor`, ceil-div) — the minimap's fixed 1/2/4 re-walk became bounded global strides
    (worst case 520 `drawLine`). `services/CanvasPageBudgetPolicy.kt` clamps end-of-stroke Y (`clampMaxStrokeY`,
    non-finite → 0) to a `MAX_DYNAMIC_PAGES` = 2000 world ceiling and `clampCalculatedPages` bounds
    `dynamicPageCount`; the per-page `filter` is hoisted to one `groupBy` per frame
    (`strokesByPage[pageIdx] ?: emptyList()`). Tests: `Phase150CanvasRenderBudgetTest` (23).
  - **Implemented in phase-157** (plugin ecosystem & store UX, see `workspace/phase-157/REPORT.md`):
    (1) **Capability browser + store filter** — pure-JVM `services/PluginCapabilityDirectory.kt`
    maps every `PluginCapability` to its serving catalog plugins with an honest
    `Coverage` verdict (`INSTALLED` / `AVAILABLE_ON_STORE` / `UNSERVED`), so the still-unserved
    capabilities (Assistant until the downloadable LLM is installed — FileTransfer became
    served in phase-173) are
    surfaced in the store BEFORE a request fails loudly. `PluginStoreDialog` gains a
    "Plugins | What can plugins do?" view-mode toggle (`showCapabilities`) + `StoreCapabilityRow`
    per capability (installed vs store-available plugin lists; "No plugin yet" copy for unserved),
    plus a compact horizontal per-capability `FilterChip` row ("All" + offered capabilities) that
    composes with the phase-156 text filter — the match-less empty state is query-aware and its
    ONE "Clear filter" CTA resets both. (2) **Update UX with notes + "Update all"** — pure-JVM
    `services/PluginUpdatePromptPolicy.kt`: `notesForDisplay` collapses control chars, bounds to
    240 chars, then runs `UiFailureTextPolicy.scrubForUi` (R2-b2b3-LOG-03 — hosted release notes
    never reach the approval dialog raw), `versionDeltaText`, `updateAllPlan` (deterministic,
    deduped per-download `UpdateAllItem`s) and `batchSummary`. The approval dialog renders
    scrubbed "What changed: …" notes; the store's "Update all" button →
    `NoteflowViewModel.updateAll()` checks then walks each offered update through ITS OWN approval
    dialog (`openNextPendingUpdate`); declining any approval ends the batch and nothing updates
    without per-download "Approve & install" (compile-time pins + TLS pinning intact). (3)
    **Per-plugin diagnostics** — pure-JVM `services/PluginDiagnosticsRowPolicy.kt`
    (`servedCapabilitiesLabel`, `optInLabel`, `lifecycleLabel`, `scrub`, `reasonLine`,
    `lastInvocationLine`, `footer`); `PluginSettingsDialog` rows now show the capabilities /
    opt-in / lifecycle footer and `state.reason` + last-invocation summaries are scrubbed
    (phase-148 rule) before rendering. Tests: `PluginCapabilityDirectoryTest` (9),
    `PluginUpdatePromptPolicyTest` (10), `PluginDiagnosticsRowPolicyTest` (9).
  - **Implemented in phase-173** (plugin ecosystem round 2, see `workspace/phase-173/REPORT.md`):
    (1) **FileTransfer served over LocalSend** — `plugins/filetransfer/LocalSendFileTransferPlugin.kt`
    (id `plugins.filetransfer`) sends note-HTML / encrypted vault backup / Obsidian+HTML exports through
    the existing Protocol v2.2 sender via the new `FileTransferSender` seam
    (`services/localsend/FileTransferSender.kt`); `LocalSendSender` implements that seam (reuse, never
    fork), and a host `LocalSendSenderFactory` (`services/localsend/LocalSendSenderFactory.kt`) builds
    the production sender so `plugins.*` never references a vault handle (bytecode-isolation pin).
    Fail-closed: typed `FileTransferOutcome` (Sent/Rejected/Error), scrubbed descriptions, opt-in/off
    by default, B1-NET-06 sweep never auto-enabled. (2) **Invocation journal** —
    `services/PluginInvocationJournal.kt` (bounded 20/plugin, persisted via `plugin_invocation_journal_<id>`,
    scrubbed, `NoOpStore` default keeps callers unchanged); `PluginManager` records every invocation +
    self-check through a `SettingsPluginInvocationJournalStore`. (3) **Store row metadata** —
    `services/PluginStoreRowPolicy.kt` (capability labels folded to 3, exclusive-first then alpha,
    bucket + download-size/"needs the hosted channel" honesty). Tests: `FileTransferPluginPolicyTest`,
    `PluginInvocationJournalPolicyTest`, `PluginStoreRowPolicyTest`.
    **Review-fix (phase-173, commit after the phase):** the FileTransfer plugin gained a REAL production
    caller — when enabled, HomeScreen's `LocalSendSendDialog` routes `doSend` THROUGH
    `NoteflowViewModel.sendFileWithPlugin` (payload→`FileTransferKind`, manager failures mapped to the
    dialog's `SendResult`, sender progress forwarded); the seam/`LocalSendDevice` were documented as host-
    only (downloadable plugins can't resolve `services.*` under the classloader sandbox); the journal now
    sanitizes the capability key on write + serializes the read→record→write and a public
    `NoteflowViewModel.refreshPluginFlows()` wrapper (private `refreshPluginStates()` keeps its B1-AUTH-03
    guard) lets Settings → Plugins refresh "Recent activity" on open.
- **Downloadable runtime**: `plugins/runtime/RuntimePluginLoader.kt:68`; `services/AppClassLoaderFactory.kt:23`
  (`DexClassLoader`); `services/AppFacadeHost.kt:27` (deny-by-default facade, NO direct DB/keystore handles);
  `plugins/runtime/PinnedCertHash.kt:25`; `plugins/runtime/ArtifactSignatureVerifier.kt:52`.
  - **Implemented in phase-76** (B2-DEPS-04, see `workspace/phase-76/REPORT.md`): the downloadable-plugin
    SIGNING identity is no longer a public default or an ephemeral build-bred keystore.
    `plugins/llm/build.gradle.kts` deleted the hardcoded default signing password and the `keytool
    -genkeypair` fallback that minted a fresh self-signed JKS into `build/plugin-signing/` on every local
    build; the signing tasks now FAIL LOUDLY — a `gradle.taskGraph.whenReady` gate
    (`PLUGIN_SIGNING_TASK_NAMES = signPlugin|verifyPluginSignature|pluginMetadata`, mirroring the `:app`
    phase-57 release gate) plus `requirePluginSigningKeystoreB64()`/`requirePluginSigningStorePass()`
    throw a `GradleException` when `PLUGIN_SIGNING_KEYSTORE_B64`/`PLUGIN_SIGNING_STORE_PASS` are unset
    (`PLUGIN_SIGNING_KEY_PASS` optional — the key password defaults to the store password, never a
    committed constant). The dangling `:app:generateLlmPluginSeed` claim became a REAL task in
    `app/build.gradle.kts`: `dependsOn(":plugins:llm:pluginMetadata")` (fails without the signing env),
    validates the signed artifact's `sha256` (64-hex) + `pinnedCertHash` (`sha256/<base64>`), and
    rewrites the committed `app/.../plugins/runtime/GeneratedLlmPluginPin.kt` seed (`null` = fail-closed,
    no release pinned yet). `CompileTimePluginPins.RELEASES` folds that seed in via
    `buildReleaseTable(*listOfNotNull(llmPluginSeedRelease).toTypedArray())`, so the app's compiled-in
    pin can only ever match the ONE real CI key identity. A latent pre-existing bug — `signPlugin`'s
    `dependsOn(pluginSigningKeystore)` passed a `Provider<RegularFile>` as the task dependency (a
    Gradle hard error), so signing could never run even with a keystore — was fixed by materializing the
    keystore inside the task action instead. Positive path proven with a throwaway `/tmp` keystore (seed
    emitted the exact pin of the key that signed the artifact; pin reverted, never committed). Tests:
    `B2Deps04PluginSigningTest` (9).
  - **Implemented in phase-80** (B2-DOS-04, see `workspace/phase-80/REPORT.md`): `AppFacadeHost.httpGet`
    enforces its response-size cap DURING the read, never after `readBytes()` already slurped the whole
    body. New pure-JVM `services/FacadeHttpGetPolicy.kt` is the single decision table
    (`MAX_FACADE_GET_BYTES` = 10 MB, `READ_BUFFER_BYTES` = 64 KiB, `readCapped` — the bounded streaming
    loop mirroring `WebPageFetcher` — throws `ResponseTooLargeException` mid-stream on the first chunk
    that crosses the cap, so a chunked/unknown-length (Content-Length: -1) or slow-chunked response can
    never pin more than the budget + one buffer in heap). `AppFacadeHost.kt:91-94` routes every body
    read through `FacadeHttpGetPolicy.readCapped` (the dead post-check on `readBytes().size` and the
    private `MAX_FACADE_GET_BYTES` companion are gone); the early `contentLengthLong` header pre-check
    (`AppFacadeHost.kt:82-85`) stays, and the B1-NET-05 manual-redirect posture (`instanceFollowRedirects
    = false`, per-hop `StrictRedirectPolicy` re-validation) is retained so every redirect hop carries its
    own 10 MB budget. API-26+ floor, pure java.io, no new deps, no fallback needed.
    Tests: `B2Dos04FacadeGetStreamingCapTest` (7).
  - **Implemented in phase-77** (B2-DEPS-05, see `workspace/phase-77/REPORT.md`): the downloadable-assistant
    GGUF model now carries a PINNED identity. `plugins/llm/.../policy/AssistantStoragePolicy.kt` publishes
    the real git-LFS SHA-256 (`DEFAULT_MODEL_SHA256 = f0a42bb9…ab81a8`) + exact byte count
    (`DEFAULT_MODEL_SIZE_BYTES = 397_805_248`, the stale 398 MiB approximation removed; both re-verified
    against the HF repo tree API) and `DEFAULT_MODEL_URL` is no longer overridable (the
    `plugins.<id>.model_url` setting and `LocalLlmPlugin`'s settings capture were deleted). The download
    is orchestrated by two new pure-JVM classes inside `plugins/llm` (the module can't import `app`
    services, so the B1-NET-05 redirect pattern is re-implemented here): `policy/ModelDownloadPolicy.kt`
    (entry/hop validation vs huggingface.co + `*.huggingface.co` + `*.hf.co` — the real CDN family the
    resolve endpoint 302s to — RFC-3986 hop resolution, `MAX_REDIRECTS = 5`, and `verifyDownload`:
    size FIRST then constant-time full-length SHA-256) and `engine/AssistantModelDownloadRunner.kt`
    (manual `instanceFollowRedirects = false` loop, per-hop re-validation before the next connection,
    body streamed into `.part` while hashing, accept ONLY on exact size+SHA-256 match, type-safe
    failure otherwise). Rewritten `engine/AssistantModelDownloader.kt` — `download(context, onProgress)`
    only, re-verifies an existing on-disk model against the pin at every call (stale/poisoned file
    deleted + re-downloaded), StatFs free-space preflight, atomic rename, cancellation cleans the temp.
    UI display fallback literal `398L`→`379L` in `OnDeviceSmartAssistant.kt`. Tests: `ModelDownloadPolicyTest`
    (21) + `AssistantModelDownloadTest` (12, scripted `HttpURLConnection` fake) + updated `AssistantPromptTest`.
  - **Implemented in phase-46** (B1-AUTH-01, see `workspace/phase-46/REPORT.md`): plugin bytecode no
    longer resolves app-private classes AND artifacts that merely mention them are rejected before any
    bytecode materializes. `plugins/runtime/PluginFrameworkClassLoader.kt:45` — a scoped parent between
    the plugin DEX and the app classloader: every `com.authorss81.noteflow.*` class OUTSIDE the
    `plugins.*` framework surface throws `ClassNotFoundException` (`isAppPrivateForbidden`,
    `PluginFrameworkClassLoader.kt:70-71`); the same check blocks `Class.forName(...)` reach-through;
    `java.*`/`javax.*`/`android.*`/`kotlin.*`/third-party classes still delegate. Wired in
    `services/AppClassLoaderFactory.kt:34`. `plugins/runtime/ArtifactStaticScan.kt` (pure JVM) runs
    inside `ArtifactSignatureVerifier.verify` (`ArtifactSignatureVerifier.kt:76-81`) — the single funnel
    for install / every load re-verify / update / rollback — and rejects app-private package prefixes
    (`services|data|ui|theme|utils`, slash+dot), bare secret-bearing class names (`VaultKeyHolder`,
    `EncryptionService`, `NoteflowDatabase`, `SettingsManager`, `NoteRepository`, `SecurityService`) and
    raw `java.net`/`javax.net.ssl` egress primitives, parsing `.class` constant pools + DEX string/type
    tables structurally. Phase-46 review additions to the scan: net-egress + `ProcessBuilder`/`Runtime`
    classes matched in slash AND dot form (a `Class.forName("java.net.HttpURLConnection")` reflection
    literal is refused too), sensitive class names matched as whole tokens (no false-positive on a
    benign plugin's own compound identifiers), and a source-level pin test holds the invariant that
    `plugins.*` host code (the artifact-resolvable surface) never references a vault-handle type.
    Native (`System.loadLibrary`) / `sun.misc.Unsafe` gating and a separate `:remote` process remain
    out-of-scope (future isolation phases), noted in the phase-46 REPORT. Tests: `PluginBytecodeIsolationTest` (20).
  - **Implemented in phase-144** (R2-B1N-03, see `workspace/phase-144/REPORT.md`): the egress/exec
    gate can no longer be bypassed with string-built class names. `PluginFrameworkClassLoader`
    (`isEgressForbidden`, `PluginFrameworkClassLoader.kt:73,132-134`) additionally refuses
    `java.net.*` / `javax.net.*` and the exact `java.lang.Runtime` / `java.lang.ProcessBuilder`
    classes at resolution time — benign `java.lang.String`/`Integer`/`List`… still delegate — so
    string-built `Class.forName("java.net." + "Sock" + "et")` is refused by the loader chain, not
    just the scan. `ArtifactStaticScan` gained an advisory dot-form package-prefix-fragment check
    (`java.net.` / `java.lang.` / `javax.net.ssl.` / `javax.net.` at a word boundary) so a
    fragment-assembled reflection target fails noisy at verify too, without false-posing on
    slash-form descriptors or benign compound identifiers. `PluginBytecodeIsolationTest` is now 24.
  - **Implemented in phase-66** (B1-CRYPTO-08, see `workspace/phase-66/REPORT.md`): the artifact-signer
    pin binds the FULL signer set — not a "last signed entry seen" cert — and the pinned cert must be
    currently usable. `ArtifactSignatureVerifier.collectSignerSet` (`ArtifactSignatureVerifier.kt:162`,
    replacing `findSignerCertificate`) force-verifies the JAR (`JarFile(verify=true)`) and rejects ANY
    unsigned non-META-INF entry (`:173`), any multi-signer entry, any archive mixing different signers
    across entries (`:189`, whole-chain comparison), and an EMPTY verified signer set (`:199`) — never
    a fallback to a last-seen value. "One signer" is judged PER SIGNER CHAIN, not per certificate:
    `singleSignerChain` (`:235`) splits the JAR verifier's leaf-first chain list on certificate
    boundaries (issuer-DN match AND a verifiable signature), so a single CA-issued signer (leaf +
    issuers) is accepted while a second signer's chain is detected as a boundary and rejected. New
    pure-JVM `plugins/runtime/SignerCertificatePolicy.kt` is the single decision table run by `verify()`
    (`:115`): `checkValidity(now)` rejects expired/not-yet-valid certs and a `KeyUsage` extension
    lacking the digitalSignature bit (bit 0) is rejected (absent extension = unrestricted, RFC 5280); a
    key-usage-invalid cert is also refused by the signer-set gate when the platform JAR verifier
    surfaces such entries with `null` certificates. Pin compare runs first (`:107`) so a wrong key
    reports the accurate "pinned certificate hash" reason. Pure JVM, API 26+ floor, no new deps. Tests:
    `B1Crypto08SignerSetTest` (19 — includes a CA-chain-signed positive control and synthetic
    `singleSignerChain`/`sameChain` decision-table tests).
  - **Implemented in phase-39**: update-manifest + artifact transports share
    `plugins/runtime/PinnedTlsConnector.kt` (`open` pins the leaf via constant-time
    `PinnedCertHash.matches`, `instanceFollowRedirects = false`; 3xx refused in both
    `PluginManifestFetcher.kt` `HttpsManifestTransport:109` and
    `HttpsPluginDownloadTransport.kt:55`). The manifest host is allow-listed
    (`HostedPluginManifest.kt:197 DEFAULT_MANIFEST_HOST`) and pinned to the
    compile-time `PLUGIN_MANIFEST_CERT_PIN` (`:220`, placeholder pending operator
    substitution; fails closed without it), so update offers can never redefine
    `downloadUrl`/`sha256`/`pinnedCertHash` from an unauthenticated source
    (closes B1-CRYPTO-01, commit `4d72a6a`).
  - **Implemented in phase-42** (B1-NET-03, see `workspace/phase-42/REPORT.md`): the
    per-plugin **update trust anchor now lives in the APK**, not the manifest.
    `plugins/runtime/CompileTimePluginPinStore.kt` (`CompileTimePluginPinStore`,
    `PinnedPluginRelease`, `PinVerdict`, `CompileTimePluginPins`, `isHostAllowListed`)
    carries `id → version → {sha256, pinnedCertHash}` release pins + a download-host
    allow-list (`DEFAULT_DOWNLOAD_HOSTS = {DEFAULT_MANIFEST_HOST}`); the production
    `CompileTimePluginPins.RELEASES` is empty ⇒ **fail closed** (publishing a
    downloadable plugin REQUIRES adding its pin rows here + an app bump). Enforced at
    three independent gates: `PluginUpdateChecker.check` offers only compile-time-pinned
    values (`PluginUpdateChecker.kt`, new `pins` arg), `PluginUpdateEngine.update`
    re-verifies the persisted target before any byte moves / rollback-root write
    (`PluginUpdateEngine.kt:110-116`), and `PluginDownloader` refuses artifact hosts off
    the allow-list before connecting (`PluginDownloader.kt`, `allowedDownloadHosts`).
    `PluginStoreController` threads the pins into both check calls.
- **Markdown**: `ui/screens/MarkdownPreviewScreen.kt:137` (renders via **commonmark 0.29.0 +
  gfm-tables**). Phase 37 hybrid-editor slice: pure-JVM block tokenizer
  `services/MarkdownBlockTokenizer.kt` (exact source round-trip), code-span-aware
  inline-math scanner `services/MarkdownInlineMath.kt`, waveform decimation
  `services/WaveformPeakMath.kt`, and the shared renderer + editor
  `ui/components/markdown/MarkdownRenderer.kt` + `HybridMarkdownEditor.kt`
  (replaces the raw text field in EDIT/SPLIT panes; typed callouts + interactive
  checkboxes; `AnimatedCheckmark.kt` respects reduce-motion).
  - **Implemented in phase-158** (reader/focus mode, see `workspace/phase-158/REPORT.md`):
    `MarkdownPreviewScreen` gains an instant (no-animation, reduce-motion-safe) reader
    `FilterChip` toggle + `initialReaderMode`/`onConsumeReaderMode` (one-shot, consumed per page).
    Reader mode renders a read-only, centered `widthIn(max=680.dp)` capped column with widened
    leading — `HybridMarkdownEditor` is NEVER composed in reader mode (long-press-safe) and the
    editing chrome (Save / Smart-Assistant / Plugins) is hidden. All numbers/decisions live in the
    pure-JVM `services/ReaderModePolicy.kt` (`MAX_COLUMN_WIDTH_DP`=680f,
    `BODY_LINE_HEIGHT_MULTIPLIER`=1.15f applied to the style's OWN already-scaled line height so
    reader leading is always WIDER than the default (phase-158 review-fix — the original 1.35x
    fraction of the type size produced 21.6sp, tighter than the 24sp bodyLarge default), no
    absolute `.sp`, `defaultReaderForCapturedNote`, `DEFAULT_BASE_LEADING_RATIO`); the
    leading flows into headings/paragraphs via a file-local `LocalReaderMode` CompositionLocal.
    Reader-mode toggle survives rotation (`rememberSaveable`, review-fix); version-RESTORE is
    disabled in reader mode (`VersionHistoryBottomSheet readOnly`, review-fix) so no write action
    is reachable from the reading surface.
    Captured notes open in reader mode by default (`MainActivity.kt` `readerModeRequestedFor`,
    `rememberSaveable`); `.txt`/`sourceFileType=="text"` pages route to this screen too.
  - **Implemented in phase-174** (reading & authoring UX, see `workspace/phase-174/REPORT.md`):
    (1) **note-stats footer** — `services/NoteStatsFormatPolicy.kt` (pure-JVM: locale-safe
    `NumberFormat` counts, `~N min read` ceil-to-minute, blank-note null, `MIN_MATERIAL_LENGTH_DELTA=8`
    recompute guard + `STATS_DEBOUNCE_MILLIS=250`); the screen runs `snapshotFlow{contentText}` →
    `debounce` → `TextToolsAnalyzer.analyze` (single O(n) pass, never per-keystroke re-tokenize) →
    `NoteStatsFormatPolicy.statsLabel`, rendered as a static non-animated `Text` hidden under
    reduce-motion / blank notes. (2) **outline quick-jump rail** — `services/HeadingScrollIndex.kt`
    (pure-JVM: stable occurrence-suffixed labels, registered px offsets); reader mode collects the
    ALREADY-parsed CommonMark `Heading` nodes via DFS (`collectHeadingNodes`, same order RenderBlocks
    renders), builds the index ONCE, and a file-local `HeadingMeasureScope` (`LocalHeadingMeasure`
    CompositionLocal) turns each heading's `boundsInRoot().top − viewport top + scroll` into a
    content offset; the anchored collapsible `ReaderOutlineRail` (phase-166-safe fixed 168dp width,
    nested-scroll list ≤300dp) jumps via `scrollState.scrollTo`/`animateScrollTo` (reduce-motion
    instant). (3) **wiki-link `[[` autocomplete** — `services/WikiSuggestionPolicy.kt` (pure-JVM:
    prefix-then-substring ranking, case-insensitive dedup, cap 6, syntax-breaking `[`/`]`/`|` titles
    never offered, `[[Raw.md|Clean]]` alias snippet, `locateQuery` bounds) — candidates are the cached
    bounded search corpus `repository.cachedCorpus()` TITLES ONLY (no per-keystroke DB reads, fail
    closed when locked, surfaced via `NoteflowViewModel.cachedWikiLinkTitles`); `RawBlockEditor`
    (`HybridMarkdownEditor.kt`) shows an in-field popup over the cursor for an unterminated `[[` and
    replaces the whole region; the slash menu (`SlashCommandMenu.kt` `onInsertWikiLink` first entry,
    `Icons.AutoMirrored.Outlined.ListAlt`) opens the FLAG_SECURE `WikiLinkPickerDialog`
    (`ui/components/WikiLinkSuggestions.kt`). Tests: `Phase174NoteStatsFormatPolicyTest` (10),
    `Phase174HeadingScrollIndexTest` (7), `Phase174WikiSuggestionPolicyTest` (13).
  - **Implemented in phase-179** (ROADMAP 21.8 — real syntax highlighting, see
    `workspace/phase-179/REPORT.md`): fenced code blocks in BOTH markdown renderers
    (`MarkdownRenderer.kt` + `MarkdownPreviewScreen.kt`) now render with token colors from the
    pure-Kotlin tokenizer `dev.snipme:highlights` (jvm variant, 0.9.3 pinned — Kotlin 1.9 metadata,
    readable by this build's 2.0.21; the 1.x line ships Kotlin-2.2 metadata this build cannot read
    yet). All decisions live in pure-JVM `services/CodeHighlightPolicy.kt` (`languageForFenceTag`
    maps fence infos incl. aliases kt/js/ts/bash/c#/objc/... onto the tokenizer grammars; unknown or
    absent tags → `null` → honest plain text; `highlightSpans` bounds-clamps every token location to
    the code string and returns spans ordered by the tokenizer's category priority, capped at
    `MAX_TOKENIZED_CHARS`=40k with try/catch → plain text, so a single bad token can never crash a
    note render). Rendering goes through the shared composable
    `ui/components/markdown/CodeBlockTextView.kt` — the fence literal is emitted VERBATIM into an
    `AnnotatedString` (copy/selection unchanged), `atom` theme selected by the luminance of the
    scheme the code surface sits on (Atom One Light/Dark — darcula/monokai reuse the same token
    colors for both modes, so they were rejected for light contrast). Tests:
    `Phase179CodeHighlightTest` (10). The canvas `CodeBlockCard` (`MediaEmbedComponents.kt`) is an
    EDITABLE text field — still plain text, so its "Plain text (no syntax highlighting)" label stays
    honest.
  - **Implemented in phase-151** (R2-b2b5-FEA-02 + R2-b2b5-FEA-03, see
    `workspace/phase-151/REPORT.md`): the markdown main-thread performance holes
    are gone. `services/MarkdownInlineMath.kt` pre-computes every maximal backtick
    run in ONE left-to-right pass and answers each closer lookup with a binary
    search over per-length position buckets (`closingPositionIndex`,
    `findClosingBacktick`), and every code-span membership test goes through the
    interval index `CodeRangeIndex` (O(log R)) instead of
    `codeRanges.any { index in it }`. The hybrid editor holds the document as ONE
    one-pass tokenized `MarkdownDocument` (blocks + checkbox candidates +
    candidates-by-block preindex; `MarkdownBlockTokenizer.tokenize`), and the
    keystroke path is `replaceBlock` — it reuses the cached lines (no
    full-document `content.lines()`), re-classifies ONLY the edited window
    (`classifyWindow`), shifts the untouched later blocks by the line delta, and
    recomputes checkbox candidates only around the window
    (`incrementalCandidates` reuses the unchanged before/after candidates);
    `toggleCheckbox(doc, …)` flips a marker without any re-tokenization.
    Tests: `Phase151MarkdownMainThreadPerfTest` (18) — reference-equivalence vs
    the old scanner, length-scaling linearity, and source pins proving the editor
    never calls the full-document paths on the keystroke path.
  - **Implemented in phase-68** (B1-AUTH-04, see `workspace/phase-68/REPORT.md`):
    markdown inline-image destinations resolve ONLY inside an allowlisted
    app-private subtree. New pure-JVM `services/InlineImagePathPolicy.kt` is the
    single resolver behind `MarkdownInlineImage` (`ui/components/ImageViewer.kt:129-131`):
    absolute paths rejected outright, any `..` path segment (incl. backslash-aware
    `..\..`) rejected before file I/O, and the candidate must exist + be a
    non-directory whose canonical path is a STRICT descendent of the canonical
    `baseDir` (symlink escape refused) — the old `file.isAbsolute && file.exists()`
    / `File(baseDir, dest).exists()` accept branches are deleted, so a crafted
    note can no longer read-and-display arbitrary process-readable files; a
    blocked reference (absolute / `..`) is classified by
    `InlineImagePathPolicy.isBlockedDestination` and shown a distinct non-alarming
    "Image location blocked" note rather than echoed as "File not found", so
    out-of-subtree existence is not disclosed, and the decode path re-canonicalizes
    before reading (symlink-swap refused). Covers preview, split,
    and hybrid-editor panes via the single composable. Tests:
    `B1Auth04InlineImagePathTest` (14).
  - **Implemented in phase-69** (B1-AUTH-05, see `workspace/phase-69/REPORT.md`):
    a note's `pages.sourceFilePath` may only ever point inside the app-private
    imports root (`ImportExportService.getImportsDir(context)` =
    `File(filesDir,"noteflow/imports")`). New pure-JVM
    `services/SourceFilePathPolicy.kt` is the single confinement decision
    (`confine`/`isConfined`/`isBlocked`: blank/null and RELATIVE values refused,
    any `..` segment in either `/` or `\` refused before file I/O, canonical
    value must be a STRICT canonical descendent of the canonical root — symlink
    escapes refused; null/non-directory root fails closed). Enforcement is at
    every boundary: `ImportExportService.sanitizeRestoredSourceFilePaths`
    (`ImportExportService.kt:1794-1834`, run in `validateAndPrepareRestoredDb`
    `:1704-1708` right after `sanitizeRestoredStrokeGeometry`) NULLs
    `sourceFilePath`+`sourceFileType` for every unconfined restored row;
    `NoteRepository` owns `importsRoot` (constructor `:22`) and confines in
    `createPage`/`updatePageSource`/`migrateLegacyPlaintextNoteBodies`;
    `NoteBodyVaultPolicy.resolveBodyForDisplay`/`deleteLegacyNoteTextBody` and
    `WikiLinkParser.getFullTextForPage`/`readFullText` (full-text cache keyed by
    `(pageId, importsRootPath)`) only read/delete a CONFINED stored path, with
    every caller passing the root (both `MainActivity.kt:438/539` body reads,
    `DocumentTextExtractor`, VM `updatePageSource` + both unlock-flush deletes,
    KnowledgeGraph/Backlinks/TagExplorer builders, command-palette index). Since
    phase-44 no plaintext `.writeText()` source-path write survives; this closes
    every remaining read/delete surface. Tests: `B1Auth05SourceFilePathTest` (17).
- **Knowledge graph**: `ui/screens/KnowledgeGraphScreen.kt`
  (Phase 38 rewrite — deterministic force-directed layout, cluster colouring, tag
  filter chips, link pulses, collision bounding, low-RAM cull + notice) built on
  `services/graph/GraphLayoutMath.kt` (forces `GraphPhysicsConfig`, layout +
  collision `GraphLayoutMath`, clusters `assignClusters`, tiers
  `GraphTierSelector`) and `services/WikiLinkParser.kt:66` `buildWikiLinkEdges`
  + `:373` `buildTagHierarchy`. Serverless tier detection: `utils/DeviceCompatibilityManager.kt`.
  - **Implemented in phase-152** (R2-b2b5-FEA-01, see
    `workspace/phase-152/REPORT.md`): the per-frame edge iteration is bounded.
    Edge culling lives in the new pure-JVM `services/graph/KnowledgeGraphEdgePolicy.kt`
    (`edgeCapFor` = 300 low-end / 600 mid / 1000 >240 nodes).
    `KnowledgeGraphScreen` culls nodes first (`GraphTierSelector.cullToCap`),
    then `KnowledgeGraphEdgePolicy.cullEdgesToSurvivors` keeps only edges whose
    BOTH endpoints survived (drops self-edges), dedups deterministically, and
    keeps top-K by max(endpoint `updatedAt`) with a sourceId→targetId tie-break
    (fails closed). The SAME culled list feeds both `graphEdges` (draw) and
    physics `edgeRefs`, so drawn edges ≤ physics edges and both ≤ 1000; the
    Canvas tag-filter verdict is memoized in `filteredById`/`pageFiltered` (no
    per-edge tag re-split per frame). Discovery is bounded too:
    `WikiLinkParser.kt` `MAX_LINKS_PER_PAGE` = 200 (`extractWikiLinks` caps the
    regex scan) and `MAX_TOTAL_EDGES` = 100_000 (inline HashSet dedup +
    break-on-cap in `buildWikiLinkEdges`); the whole-edge-set
    `edgeList.distinct()` materialization is gone. Tests:
    `KnowledgeGraphEdgePolicyTest` (15) + source pins in
    `Phase152FeatureDataBoundsWiringTest`.
  - **Implemented in phase-164** (tag vault notebook-scoping, see
    `workspace/phase-164/REPORT.md`): the tag vault/explorer now shows ONLY the
    currently selected notebook's tags. New `WikiLinkParser.buildScopedTagHierarchy`
    (`services/WikiLinkParser.kt`, `:526`) scans ONLY the selected notebook's active
    pages (text `#tag`s + CSV `tags`-field) plus the notebook's OWN tag list,
    reusing the shared bounded/cancellable `collectTextTags` + depth-bounded
    `buildTagTree` (the whole-vault `buildTagHierarchy` behavior is byte-identical);
    cached per unlock epoch + scope fingerprint (pages + notebook tags), new
    `scopedTagRecomputes` metric. `NoteflowViewModel.loadScopedTagHierarchy`
    (`:4064`) queries only that notebook via `getPagesForNotebookOnce`
    (page→section→notebookId, no schema change) inside `withLockedPoolGuard`.
    `TagExplorerView` keys its LaunchedEffect on `(notebookId, notebook tags)` and
    runs the build in-effect (stale notebook's scan is cancelled on switch);
    `HomeScreen` clears a stale tag filter on notebook switch. Tests:
    `Phase164TagVaultScopingTest` (8) + updated `Phase134LockVaultInflightTest` pin.
    **Review fixes (2026-08-19):** tag identity is normalized identically across all
    three sources (notebook tag list lowercased like text `#tag`s + CSV tags — mixed-case
    inputs merge into one node, `:550`); the CSV-tags pass uses the same
    `MAX_SCAN_PAGES`-bounded scan set as the text scan; `loadScopedTagHierarchy` now takes
    the caller's captured `notebookId` (the effect key) instead of re-reading
    `selectedNotebook.value`, `getNotebookById` for the notebook's own tags; `TagExplorerView`
    clears stale tag state when the post-read auth re-check fails; `HomeScreen` ignores
    empty `matchingPageIds` tag pickups (no dead-end filter) and filters the active tag
    across the whole notebook's pages (`allActivePages`) rather than the current section.
- **Command Palette (Phase 38 HUD)**: `ui/components/CommandPaletteOverlay.kt`
  (global quick-switcher; two-finger swipe down in `MainActivity.kt`
  `detectTwoFingerSwipeDown`, keyboard icon in `HomeScreen.kt`), ranking/tag
  combination/action routing in `services/graph/CommandPaletteMath.kt`, search +
  plugin-action execution in `NoteflowViewModel.commandPaletteSearch` /
  `runPaletteAction` over the cached decrypted corpus
  (`NoteRepository.cachedCorpus`, generation `currentSearchCorpusGeneration`).
  - **Implemented in phase-78** (B2-DOS-02, see `workspace/phase-78/REPORT.md`):
    vault search is bounded at every layer. New pure-JVM
    `services/VaultSearchPolicy.kt` is the single decision table
    (`SEARCH_CORPUS_CAP = 1500`, `DEEP_SCAN_BATCH_SIZE = 1500`,
    `exceedsCorpusCap`, `cachedWindowSize`, `isBlankQuery`, `pageMatches`,
    `refineNoticeMessage`). `NoteRepository.loadSearchCorpus`
    (`NoteRepository.kt:107-126`) now ALWAYS caches the decrypted window —
    loaded through the bounded DAO read `NotePageDao.getAllActivePagesBounded`
    (`Daos.kt`, `LIMIT :limit`) — so a keystroke never re-decrypts the vault;
    a vault over the cap is flagged via `NoteRepository.searchCorpusCapped`
    (recomputed per load) instead of silently dropping the cache. `searchPages`
    filters the cached window only; the explicit user-approved refine path is
    `NoteRepository.deepSearchPages` (`:412-434`), paged in bounded batches via
    `getAllActivePagesPaged` (`LIMIT :limit OFFSET :offset`) with only matches
    retained (never the whole vault pinned). `NoteflowViewModel.searchVault`
    shares ONE cancellable `Job` (`searchVaultJob?.cancel()` before every new
    launch, `if (isActive)` callback guard) and `deepSearchVault`
    (`NoteflowViewModel.kt:1926-1948`) shares it, so a keystroke pre-empts an
    in-flight deep scan. `HomeScreen` shows a one-time non-alarming
    "Search covers the most recent pages" banner + "Search all pages" action
    (`HomeScreen.kt`, gated on `viewModel.repository.searchCorpusCapped` +
    `refinedSearchDone`). The command palette / quick-switcher now indexes the
    same bounded cached window (consistent, bounded). Tests:
    `B2Dos02VaultSearchBoundedTest` (10).
  - **Implemented in phase-132** (UI/UX, see `workspace/phase-132/REPORT.md`): the
    header no longer squishes "Command Palette" on portrait/mobile viewports. The
    pre-fix header Row put the unconstrained 45-char shortcut hint
    `"⌘ ↑/↓ · Enter · two-finger swipe down to open"` directly beside the
    `Modifier.weight(1f)` title, so on narrow viewports the hint consumed the
    width and the title collapsed to a vertical strip. The header is now a
    **nested Column** (`CommandPaletteOverlay.kt:188-204`): the title on its own
    full-width line (`maxLines = 1` + `TextOverflow.Ellipsis`) with the shortcut
    hint rendered BENEATH it (also single-line/ellipsized); the search icon +
    close `IconButton` frame it and the weighted Column gets the remaining width,
    so the title keeps proper horizontal width at every viewport. Header strings
    moved to the pure-JVM single-source-of-truth
    `services/CommandPaletteHeaderPolicy.kt` (`TITLE`/`SHORTCUT_HINT`);
    truncation is left to Compose's pixel-based `TextOverflow.Ellipsis` (an
    initial `shortcutHint`/`truncate` char-budget decision table was dead
    production code and was removed in the Phase 132 review fixes). Tests:
    `Phase132CommandPaletteHeaderTest` (3).
  - **Implemented in phase-152** (R2-b2b5-FEA-05, see
    `workspace/phase-152/REPORT.md`): the palette no longer lowercases the
    corpus per keystroke. `services/graph/CommandPaletteMath.kt` `PaletteDoc`
    precomputes `lowerTitle`/`lowerBody`/`lowerTags` ONCE at construction (i.e.
    at index build — once per corpus generation), `score()`/`rank()`/
    `matchesTagFilter` consume the cached lowercase and the query is lowercased
    once per `rank()`, the per-keystroke `lowerLog` HashMap is deleted, and
    `makeSnippet(body, lowerBody, lq)` locates the hit via `lowerBody` but slices
    the original-case body (highlight keeps the user's casing). The ~75 MB of
    per-keystroke lowercase allocations at the corpus cap are gone.
- **Waveform / audio**: `services/WaveformPeakMath.kt` (recording live buckets +
  render-buffer decimation), `services/VoiceRecordingPolicy.kt`, and
  `ui/components/AudioPlaybackCard.kt`.
  - **Implemented in phase-152** (R2-b2b5-FEA-06, see
    `workspace/phase-152/REPORT.md`): non-finite samples can no longer reach bar
    geometry. `WaveformPeakMath.finiteOrZero` replaces NaN/±Inf with `0f` at
    BOTH `NoteRepository.parseWaveformJson` paths (JSONArray + split fallback),
    inside `downsample` (identity pass and min/max decimation), and
    `WaveformPeakMath.renderAmp` clamps to `0.1f..1f` then refuses non-finite
    values (`NaN`/`-Inf` → `0.1f`) in `AudioPlaybackCard` — defense in depth so
    a crafted stored `waveformJson` cannot feed NaN into `drawRoundRect`.
- **WebDAV sync**: `services/WebDavSyncService.kt:28` (encrypted vault archives, HTTPS enforced).
  - **Implemented in phase-40**: server-supplied PROPFIND hrefs are re-resolved against the
    configured server origin by the new pure-JVM `services/WebDavHrefResolver.kt`
    (`resolveDownloadHref`), and EVERY connection is origin-gated in `createConnection`
    (`WebDavSyncService.kt:147`) before the `Authorization: Basic` header is attached
    (`:164`), with `instanceFollowRedirects=false` (`:158`). Off-origin/private-IP hrefs
    and 3xx redirects are refused with a clear `SyncResult(false, "Sync refused: ...")` —
    closes B1-NET-01 + the WebDAV slice of B1-NET-05 (see `workspace/phase-40/REPORT.md`).
  - **Implemented in phase-86** (B1-NET-07): the remote-listing → download slice is a
    pure-JVM decision table `services/WebDavRemoteListingPolicy.kt` — the "latest"
    backup is chosen by the MAXIMUM FILENAME TIMESTAMP across both name generations
    (`noteflow_vault_backup_<epochMillis>.nfb` legacy + `noteflow_vault_backup_<yyyy-MM-dd>_<token>.nfb`
    B2-CRYPTO-06), never the last href in XML document order (`newestBackupHref`,
    timestamps compared ASCENDING so `maxWithOrNull` yields the newest — unparseable
    names score lowest, same-timestamp ties break deterministically by href); the `.nfb`
    GET is streamed under the `MAX_DOWNLOAD_BYTES` (400 MB) cap by `copyBounded`
    (mid-stream abort, typed `DownloadTooLargeException`, no target-file over-budget,
    IDLE_READ_LIMIT stall guard); `remoteFolderName` is validated as ONE path segment
    and RFC 3986 percent-encoded by `encodedRemoteFolderSegment` (blank/`.`/`..`/
    separators/control chars rejected) at every URL interpolation. Review-fix (same
    lineage): the too-large catch deletes the partial cache file, and the download
    source pins are scoped to the download path only (the upload PUT legitimately keeps
    `input.copyTo(output)`). See `workspace/phase-86/REPORT.md`.
  - **Implemented in phase-94** (B2-LOG-05, see `workspace/phase-94/REPORT.md`):
    the WebDAV failure surfaces are credential/URL-proof. New pure-JVM
    `services/WebDavFailurePolicy.kt` maps every failure category to a FIXED string
    (`CONNECT_FAILURE_MESSAGE`/`UPLOAD_FAILURE_MESSAGE`/`DOWNLOAD_FAILURE_MESSAGE`/
    `TOO_LARGE_DOWNLOAD_MESSAGE`/`INVALID_URL_MESSAGE`) and provides the two
    sanitizers `stripUrlUserInfo` (regex-drops `scheme://<userinfo>@`) and
    `scrubForDisplay` (userinfo + `scheme://host/path` → `host/...`). `WebDavSyncService.kt`
    no longer reads-hostile-path text: the malformed-catch throws the FIXED
    `INVALID_URL_MESSAGE` (was `"Invalid WebDAV server URL: ${e.message}"`, which
    exported the raw paste including any `user:pass@`), `validateServerUrl` returns
    the userinfo-stripped URL, connect/upload/download blanket catches return the
    category FIXED string, the too-large catch returns `TOO_LARGE_DOWNLOAD_MESSAGE`,
    and the resolver-defusal + origin-gate paths report FIXED tokens
    (`refusalReason(e)`, never the href/URL). `WebDavSyncDialog.kt` scrubs all three
    status renders via `scrubForDisplay` (defense in depth). Grep-pinned: no
    `localizedMessage`, no `${e.message}`, no bare `res.message` assign in either file.
    Tests: `B2Log05WebDavFailureTextTest` (13) — 1619 green (0 failures).
  - **Implemented in phase-145** (R2-B1C-02, see `workspace/phase-145/REPORT.md`):
    auth-bound remembered WebDAV credentials are never silently deleted when the
    AndroidKeyStore biometric window expires. `WebDavCredentialStore.load()` is now a
    convenience over the classified `loadDetailed()` sealed result
    (`WebDavCredentialLoadResult` = `None`/`Credentials`/`AuthRequired`/`Corrupt`,
    `WebDavCredentialStore.kt:26-47`); `UserNotAuthenticatedException` and the keystore's
    `KeyStoreException("user not authenticated")` map to `AuthRequired` (blob INTACT, never
    cleared — `clear()` runs only on a genuine AEAD/tag/decrypt failure). The biometric
    re-auth path the phase-119 finding said was missing is real:
    `prepareReauthCipher()` (`WebDavCredentialStore.kt:326`) prepares a DECRYPT cipher over
    the stored auth-bound blob → `BiometricPrompt.CryptoObject` → on biometric success
    `decryptWithReauthCipher(cipher)` (`:349`) completes the decrypt (mirrors
    `SecurityService.getDecryptionCipher`/`decryptWithCipher`); a failed re-auth returns null
    WITHOUT clearing. `WebDavSyncDialog` classifies on launch (`WebDavSyncDialog.kt:123-139`):
    `AuthRequired` keeps remember-me checked and shows a non-alarming notice + an
    "Unlock with biometrics" `OutlinedButton` wired through
    `BiometricAuthHelper.promptBiometricAuth(..., cryptoObject, ...)`
    (`WebDavSyncDialog.kt:82-119`). No new permission/dependency.
- **LocalSend**: `services/localsend/LocalSendProtocol.kt:29`, `LocalSendSender.kt:48`.
  - **Implemented in phase-41**: confirmed-pairing gate for sends. Pure-JVM
    `services/localsend/LocalSendPairing.kt` (`gate` = HTTPS-only +
    fingerprint-present + TOFU-paired, `startPairing` derives a 6-digit out-of-band
    code + formatted fingerprint, `confirmPairing`/`pair` persist a constant-time
    verified TOFU anchor), stores `InMemoryLocalSendPairedDeviceStore` (tests) +
    `SettingsLocalSendPairedDeviceStore` (SharedPreferences `localsend_paired_<fp>`).
    `LocalSendSender.sendFile` refuses unpaired/http receivers before any I/O
    (`:313-326`) and pins every payload connection to the STORED paired
    fingerprint (`trustedFingerprint` `:325`), never the wire-announced one;
    `openConnection` refuses non-https payload URLs; the announce never says
    `protocol:"http"`. `LocalSendSendDialog` shows a pairing sub-view that
    requires either a verification code typed from the receiving device
    (constant-time checked, mismatch refuses) or an explicit "fingerprints match"
    acknowledgement, plus a per-send confirmation; `200` to `/prepare-upload` is
    zero evidence of consent.
  - **Implemented in phase-85 (B1-NET-06, LOW)**: the /24 `legacyHttpScan`
    register sweep is now an EXPLICIT per-search opt-in, never a default.
    Single pure-JVM decision table `services/localsend/LocalSendDiscoveryPolicy.kt`
    (`DISCOVERY_REQUIRES_EXPLICIT_USER_ACTION = true`,
    `LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT = false`, `SENDER_ALIAS = "InkFlow"`,
    `senderDeviceModel = null`; `mayRunDiscovery(userInitiated)` /
    `mayRunLegacyHttpScan(userOptedIn)` fail closed). `LocalSendSender.discoverDevices`
    (`LocalSendSender.kt:104-118`) defaults `includeLegacyHttpScan` to
    `LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT` and only consults the sweep when UDP
    discovery found nothing (`udpResults.isEmpty()`); `LocalSendSendDialog` seeds
    a "Also check every address on this Wi-Fi…" Checkbox from the same constant
    and feeds `legacyHttpScanOptIn` into the single discovery call (the old
    hard-coded `= true` is gone). `discover()` still fires ONLY from the explicit
    "Find nearby devices"/"Refresh" `onClick` handlers, so opening the dialog
    transmits nothing. The announce/identity (`LocalSendProtocol.senderIdentity`
    `:105-114`) is now wired to the same policy constants (alias `InkFlow`,
    no device model — `Build.MODEL` long gone since phase-110/B1-NET-09).
- **Web Capture / Citation fetch (SSRF)**: `services/SsrfHostPolicy.kt:30` (shared pure-JVM host
  blocklist — loopback/RFC-1918/link-local-metadata/CGNAT/ULA/`.local`/embedded-IPv4, structural, no DNS),
  `plugins/webcapture/WebPageFetchPolicy.kt:31` (`validateUrl`) + `:80` (`rejectHop`),
  `plugins/webcapture/WebPageFetcher.kt:22` (every-hop revalidation + redirect-advance fix),
  `plugins/citation/HttpsTitleFetcher.kt:39` (manual 5-hop redirect loop, hop-scheme+blocklist
  revalidation, `instanceFollowRedirects=false`), `plugins/citation/CitationFormatterCore.kt:26`.
  - **Implemented in phase-51**: B1-NET-04 closed — Web Capture and Citation title-fetch can no
    longer reach localhost/LAN/cloud-metadata endpoints, either directly or via a redirect hop:
    entry gates refuse blocked hosts (`WebPageFetchPolicy.validateUrl`,
    `CitationFormatterCore.validateUrl`) and every redirect `Location` is re-parsed and re-validated
    against the same scheme allow-list + `SsrfHostPolicy` before connecting (incl. an HTTPS→HTTP
    downgrade refusal under the citation fetcher's default `httpsOnly`). See `workspace/phase-51/REPORT.md`.
    Review fix (2026-08-15): `SsrfHostPolicy.isOpaqueIpv4Literal` refuses ambiguous numeric encodings
    (`0x7f.0.0.1`, `0177.0.0.1`) whose per-segment value differs by resolver; `normalize` strips a bare
    `host:port`; `WebCaptureEngine.captureWebPage` fetches the normalized `Validation.url`. Name-based
    DNS-rebinding remains a tracked out-of-scope residual (`docs/security-report.md`).
  - **Implemented in phase-143** (R2-B1N-04): Web Capture is HTTPS-by-default.
    `WebPageFetchPolicy` `ALLOWED_SCHEMES` is now `{"https"}` only; `validateUrl(input,
    allowInsecureHttp=false)` and `rejectHop(url, allowInsecureHttp=false)` refuse any
    `http://` entry or redirect unless the caller passes the explicit per-fetch
    cleartext opt-in (`WebPageFetcher.fetch(url, allowInsecureHttp)` threads it to every
    hop). `WebCaptureEngine.captureWebPage`/the `WebCapturePlugin` interface
    (`NoteflowPlugin.kt`) gained the same `allowInsecureHttp` parameter (default false);
    the WebCapture dialog mirrors the WebDAV `allowInsecureHttp` UX — an address that
    names the `http` scheme (detected by the policy's own `WebPageFetchPolicy.namesHttpScheme`)
    shows a one-time "allow insecure HTTP" checkbox, never fetches otherwise, and the
    flag is cleared if the address is edited away from http. Unlike WebDAV's
    local-network-only cleartext, the Web Capture opt-in applies to any host (SSRF
    blocklist still enforced).
    Bare/host-only input still defaults to `https://`. See `workspace/phase-143/REPORT.md`.
  - **Implemented in phase-144** (R2-B1N-02): DNS-rebinding mitigation via resolve-and-pin.
    New pure-JVM `services/DnsRebindingPolicy.kt` — `resolveAndPin(host, resolver)` resolves
    the hop once (injectable resolver; production default `InetAddress.getAllByName`) and
    fails closed if ANY returned A/AAAA is internal/reserved (`SsrfHostPolicy` ranges, incl.
    IPv4-mapped forms); `applyPinToConnection` layers a `PinnedSslSocketFactory` whose
    `createSocket(Socket,…)` closes + rebuilds a platform pre-connected socket that reached a
    non-pinned address, + a `PinnedHostnameVerifier` that re-checks the hop host. Wired per hop
    into every user-influenced/plugin transport (`HttpsTitleFetcher`, `WebPageFetcher`,
    `AppFacadeHost`, `DuckDuckGoClient`, `WeatherClient`, `DictionaryClient`), each gaining an
    injectable `dnsResolver` constructor param. The plugin manifest/download transports remain
    pinned-identity/allow-listed and are intentionally unwired. See `workspace/phase-144/REPORT.md`.
- **Implemented in phase-52** (B1-NET-05): HTTPS→HTTP redirect downgrades are
    closed at EVERY base `HttpURLConnection` transport. New pure-JVM
    `services/StrictRedirectPolicy.kt` (`checkTlsHop` `:31`, `resolveNextTlsHop`
    `:57`, `RedirectRefusedException`, `MAX_REDIRECTS = 5`) is the single hop
    policy: every hop — the entry URL AND every resolved 3xx `Location` — must
    be `https` and pass the B1-NET-04 `SsrfHostPolicy` blocklist; loops,
    malformed and blank targets are rejected. Wired with
    `instanceFollowRedirects = false` (+ manual loop) into `DuckDuckGoClient`
    `:163`, `OpenMeteoClient` (`WeatherClient.kt:104`), `DictionaryClient.kt:69`,
    and `AppFacadeHost.httpGet` `:67` (previously `= true`); `LocalSendSender`
    `:512` now also refuses redirects on its pinned payload connections. All
    four transport constructors gained an injectable `connectionFactory` (default
    = `openConnection`) so each is behavior-tested with a fake `HttpURLConnection`
    (`B1Net05RedirectDowngradeTest`, 28 tests). Review fix (2026-08-15): the
    last base-app redirect-following hole was closed — `LocalSendSender.httpRegisterProbe`
    (`LocalSendSender.kt:235`) now also sets `instanceFollowRedirects = false`
    (`:240`, previously the platform's implicit `true`); the source-pin test now
    enforces a per-file count invariant (every `openConnection()` must be matched
    by an `instanceFollowRedirects = false`, 29 tests) so no future un-paired
    connection can slip through. See `workspace/phase-52/REPORT.md`.
- **Palette**: `services/PaletteCatalog.kt:131` (swatches + `familyFor`), `PaletteMath` :24.
  - **Implemented in phase-122**: rainbow brush MODE is now selectable, persisted and quick-picker-reachable. Pure-JVM `services/ColorModePersistencePolicy.kt:17` is the persistence decision table (`PREF_KEY_COLOR_MODE = "brush_color_mode_key"`, DEFAULT=SOLID, fail-closed `modeFromPref`); `SettingsManager.brushColorModeKey` (`SettingsManager.kt:203`) routes through it, alongside the persisted base/gradient-end colours `brushColorArgb`/`brushGradientToArgb` (`SettingsManager.kt:212-221`) so a reopened GRADIENT/SHIMMER session keeps its real colours; `EditorScreen` restores mode + colours on open (`EditorScreen.kt:127`, `:135-142`). ONE shared `ui/components/ColorModeChipsRow.kt:40` renders Solid/Rainbow/Gradient/Shimmer chips + gradient-end swatches in BOTH the colour picker (`EditorScreen.kt:3133`) and the width/quick picker (`EditorScreen.kt:3451`); both sheets share the single `handleColorModeChange`/`handleGradientToColorSelect` handler pair (`EditorScreen.kt:144-171`) which persists via `viewModel.settings`. Chips are idempotent (re-tap = no-op) and both bottom sheets + the chips row are scrollable. `BrushColorModeMath.hueAdvance` (`BrushColorModeMath.kt:145`) = per-stroke-length full-360° deterministic sweep (`normalizeHue(seedHueDeg + progress*360)`); `rainbowColorAt` (`:166`) is now allocation-free per point (scalar `valueOf`, no `argbToHsv`). Per-stroke mode/seed round-trip in the stroke payload (phase-27), so reopened notes keep rainbow strokes with NO schema change. Tests: `Phase122RainbowColorTest` (17, incl. exact ARGB goldens).
- **Brush preview**: `ui/components/PenNibVisualPreview.kt:50` (driven by `services/NibPreviewMath.kt`).
  - **Phase 122**: shared colour-mode chip row `ui/components/ColorModeChipsRow.kt:40` (Solid/Rainbow/Gradient/Shimmer + gradient-end swatches) used by both the colour picker and the width/quick picker in `EditorScreen.kt`.
  - **Implemented in phase-155** (canvas & brush workshop, see `workspace/phase-155/REPORT.md`): canvas gestures + brush-preset files. (1) TWO-FINGER UNDO/REDO — pure-JVM `services/GestureRedoUndoClassifier.kt` classifies swipe-left/-right (undo/redo) and two-finger double-tap (undo) while a pinch-guard (separation-ratio band 0.6..1.65) ensures zoom is NEVER hijacked; wired in `AnnotationCanvas.kt` via a `pointerInput` block gated by `SettingsManager.twoFingerUndoRedoEnabled` + one-time hint; the block uses the scope's OWN `withTimeoutOrNull` member (restricted suspending scope). (2) QUICK-COLOR RING — long-press pops a radial swatch ring (filled center = keep current color); pure-JVM layout/hit-test in `services/QuickColorRingMath.kt` (`kotlin.math` exposes no `TWO_PI`, so `*= 2.0 * PI`), seeded from the active DesignerPalette SWATCHes, gated by `SettingsManager.quickColorRingEnabled`. (3) `.inkbrush` BRUSH-PRESET import/export — `.inkbrush` is a versioned JSON bundle (`FORMAT=inkflow.brushpreset`, MIME `application/octet-stream`, single file) via pure-JVM `services/BrushPresetFileCodec.kt` (encode/decode + `encodeList`/`decodeList` for persisting "My presets" in shared prefs; decode SKIPS a UTF-8 BOM + leading whitespace before classifying, and forwards non-JSON bytes UNTOUCHED as `DecodeResult.RawProtobuf` to the dormant `ProtobufBrushLoader.loadFromByteArray`); import caps via `services/BrushPresetImportPolicy.kt` (256KB file, 32 presets, freehand-only, 48-char name), bounded read through `AttachmentIngestPolicy.boundedReadBytes`, SAF export via `ExportKind.BRUSH_PRESET` in `services/ExportDestinationPolicy.kt` (`brush_preset.inkbrush`), per-tool/param deterministic ids so re-import dedupes. All three features default OFF (opt-in toggles in Canvas & Paper Options). Tests: `GestureRedoUndoClassifierTest` (15), `QuickColorRingMathTest` (10), `BrushPresetFileCodecTest` (19); `gradle assembleDebug` green; full suite 2164 tests / 2 pre-existing failures (Phase148 UNC-path scrub — documented, WikiLinkParser cache-cancel timing flake).
- **Glass theme**: `theme/GlassSurfaces.kt:44` (`GlassBlurGate`), :80 (`GlassSurfaceMath`), :140
  (`FrostedGlassSurface`), :192 (`innerLuminescence`); `theme/Motion.kt:50` (`MotionSystem`, `LocalReduceMotion` :16);
  `theme/Type.kt:70`; `theme/Theme.kt:239` (`NoteflowTheme`, dynamic + paper/sepia/dark/AMOLED).
  - **Implemented in phase-128** (UI text hygiene, see `workspace/phase-128/REPORT.md`): UI chrome
    typography normalised to the standard `theme/TypeScale.kt` M3 ladder. All-caps gone:
    `UnifiedSidebar.kt:124/:164` ("QUICK NOTES"/"ALL NOTEBOOKS" → title case) and the tutorial
    section chip's `.uppercase()` (`InteractiveTutorial.kt:181`). Extreme weight/tracking gone: the
    Step-kicker was the app's ONLY `FontWeight.ExtraBold` + `letterSpacing=1.sp`
    (`InteractiveTutorial.kt:210-214` → plain `labelLarge`); the tutorial slide title
    `headlineSmall`+`Bold` → `titleLarge` (`:218-223`, dialog-chrome size cap). Near-sibling fix:
    `EditorScreen.kt:3533` "×" delete glyph `titleMedium`→`labelSmall` (matches `:3179`).
    Documented-as-fine: markdown H1/H2 heading ladders, the consistent `headlineMedium`
    lock/recovery screen-title family, canvas ink UI, emoji glyphs, capped data values.
    Also fixed a phase-127 **pre-existing build blocker**: `PluginStoreDescriptionBlock.kt:12-13/:72-74`
    used non-existent `Icons.AutoMirrored.Outlined.KeyboardArrowUp/Down` → `Icons.Outlined.*`
    (as `UnifiedSidebar.kt:296`). No schema change, no new deps.
- **ViewModel/nav**: `ui/viewmodel/NoteflowViewModel.kt:105` (builds SecurityService/NoteRepository/PluginRegistry
  :121/PluginManager :131/PluginRuntime :170/PluginStoreController :196; ~60 capability suspend fns);
  `MainActivity.kt:73` (single activity, **`mutableStateOf` nav** — NOT Navigation Compose).
  - **Implemented in phase-60** (B1-PLAT-4, see `workspace/phase-60/REPORT.md`): the vault lock
    boundary is no longer reachable only via ON_STOP / next-touch. Pure-JVM
    `services/AutoLockPolicy.kt` owns the default (`DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS = 300`,
    read by `SettingsManager.autoLockTimeoutSeconds` — auto-lock ships ENABLED), the decision
    (`shouldAutoLock`, `>=` boundary, 0/negative = off) and the poll cadence
    (`IDLE_CHECK_INTERVAL_MS`). `MainActivity` runs a continuous 1 s idle poll while the vault is
    authenticated (`LaunchedEffect(autoLockTimeoutSeconds, authenticated)`), stamps a fresh idle
    baseline at each unlock, keeps the `pointerInput` touch handler timestamp-only, locks instantly
    on a runtime `ACTION_SCREEN_OFF` receiver (register in onCreate / deregister in onDestroy;
    API 33+ uses the flagged registration, below that the plain system-broadcast registration), and
    applies FLAG_SECURE unconditionally (debug clearFlags carve-out deleted). `ON_STOP` → lock
    retained. `ON_PAUSE` → lock explicitly NOT chosen (system-overlay pauses like phase-59's SAF
    pickers, biometric prompts and the share sheet must not force a lock).
  - **Implemented in phase-130** (user-requested UI/UX, see `workspace/phase-130/REPORT.md`):
    FLAG_SECURE is gated on `!BuildConfig.DEBUG` per the AGENTS.md hard rule. New pure-JVM
    `services/SecureWindowPolicy.kt` = single decision `shouldApplySecureFlag(debug)` (debug →
    false, release → true); `MainActivity.onCreate` (`MainActivity.kt:144-146`) applies the flag
    ONLY via `if (SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG)) { window.addFlags(FLAG_SECURE) }`
    — the phase-60 unconditional `addFlags` (and the old `B1Plat04AutoLockTest` pin that forbade a
    debug gate) is superseded. Debug/cloud-emulator streaming environments (which mirror the
    display buffer) render the UI instead of a black surface; release builds keep the
    screenshot/recording/recents-thumbnail ban, and R8 keeps the call because `onCreate` is an
    activity entry point and `BuildConfig.DEBUG` = false folds the branch to always-true in release.
    `buildConfig = true` is already on (`app/build.gradle.kts:89`). Tests: `B1Plat04AutoLockTest`
    now 11 (decision-helper tests + a source pin that the ONLY `window.addFlags` in MainActivity
    sits inside the BuildConfig.DEBUG guard). `gradle testDebugUnitTest` 1842 green (0 failures) +
    `assembleDebug` green.
  - **Implemented in phase-72** (B2-UI-2, see `workspace/phase-72/REPORT.md`): `NoteflowViewModel.lock()`
    (`NoteflowViewModel.kt:3219-3234`) scrubs the system clipboard as its FIRST statement —
    `ClipboardGuard.scrubIfOwnCopy(appContext)`, before `repository.zeroizeKey()` and before the
    passwordless-vault gate — so EVERY lock path (manual "Lock Vault Now", idle auto-lock, ON_STOP,
    ACTION_SCREEN_OFF) clears an app-owned clipboard copy inside its window even though the app stays
    foregrounded (ON_PAUSE may never fire). The decide → clear → forget decision is the new pure-JVM
    `services/ClipboardScrubPolicy.kt` (single decision table, `SCRUB_WINDOW_MS = 60_000`); the
    Android clipboard write stays in `services/ClipboardGuard.kt` (clearPrimaryClip API 28+ / empty
    setPrimaryClip API 26-27, best-effort, `clearPrimaryClipOverride` = pure-JVM test seam) and after a
    scrub the guard forgets its timestamp so a foreign (other-app) copy is never wiped. ON_PAUSE scrub
    retained as defense-in-depth; both note-content copy sources stamp the guard before writing
    (`OcrResultDialog.kt:149-150`, `MediaEmbedComponents.kt:352-354`). Tests: `B2Ui2ClipboardScrubTest` (13).
    - **Implemented in phase-139** (R2-B1P-01, see `workspace/phase-139/REPORT.md`): the primary
      note-content copy surfaces that stamp no `recordCopy()` (the markdown editor's platform
      selection Copy, `HybridMarkdownEditor.kt:219` `OutlinedTextField`; the OCR dialog's native
      `SelectionContainer` Copy, `OcrResultDialog.kt:121`) are covered by making the LOCK clear the
      clip UNCONDITIONALLY. `NoteflowViewModel.lock()` (`NoteflowViewModel.kt:4057`, scrub `:4073`)
      now calls `ClipboardGuard.scrubUnconditionally(appContext)` (new, `ClipboardGuard.kt:84`,
      shared private `clearPrimaryClip` `:88` — no stamp check, no 60s window, API 28+
      `clearPrimaryClip` / API 26-27 empty `setPrimaryClip`, best-effort, forgets the stamp) so a
      decrypted note body copied via an untracked native path can never survive a lock. The windowed
      own-copy decision (`ClipboardScrubPolicy.shouldScrub`) stays on the ON_PAUSE hook
      (`MainActivity.kt:154`) so a brief app switch never wipes a foreign copy, and both stamped
      copy sites keep `recordCopy()`. Grep-pins: `B2Ui2ClipboardScrubTest` (18) enumerates every
      `clipboardManager.setText(` in `ui/` (must be preceded by `recordCopy()`) and pins that raw
      `{set,clear}PrimaryClip` lives only inside `ClipboardGuard.kt`. `B1Db08DecryptFailureTest`
      lock-region boundary token updated to `scrubUnconditionally`.
    - **Implemented in phase-140** (R2-B1A-03 + R2-b2b1-UI-02 + R2-B1P-05, see `workspace/phase-140/REPORT.md`):
      (1) **ON_PAUSE opaque cover** — new pure-JVM `services/OnPauseCoverPolicy.kt` (decision table:
      cover only `hasMasterPassword && authenticated`; ANY resume dismisses) drives a last-child
      full-screen opaque `Surface` composed in `MainActivity.kt` whenever `pauseCoverActive`
      (`MainActivity.kt:112`) is set by the ON_PAUSE lifecycle hook (`:170-179`, which also dismisses
      the share-confirm dialog + Command Palette — both separate windows that would float above the
      activity cover) and cleared on ON_RESUME. Locking on ON_PAUSE stays rejected (phase-60);
      covers never break picker/biometric/share-sheet returns. (2) **per-dialog FLAG_SECURE** — shared
      `@Composable secureDialogProperties(...)` (`ui/components/SecureDialogProperties.kt`) maps the
      pure-JVM `services/SecureDialogPolicy.kt` gate (reuses `SecureWindowPolicy.shouldApplySecureFlag(
      BuildConfig.DEBUG)`) to `DialogProperties(securePolicy = SecureOn|Inherit)`; wired into every
      content-bearing dialog window (CommandPalette, OcrResultDialog, MarkdownPreviewScreen's
      transform/TextTools/LanguageDetection, WebSearchDialog, Phase16PluginDialogs, Phase26PluginDialogs)
      since Compose dialog windows do NOT inherit the activity's flags. (3) **share-confirm state** —
      `pendingShareConfirm`/`pendingShare` hoisted from activity fields into the ViewModel
      (`NoteflowViewModel.kt:1377-1381`, models + `PendingSharePolicy` in `services/PendingShareState.kt`);
      `readShareIntent` bails while a share is in flight (`MainActivity.kt:787`) so rotation
      (singleTask, no configChanges) cannot re-prompt an answered confirm; the confirm `AlertDialog`
      renders only under `authenticated`; `lock()` drops both states via `PendingSharePolicy.clearOnLock`
      (`NoteflowViewModel.kt:4154-4163`) so a pre-lock "Clip" never auto-applies at the next unlock.
    - **Implemented in phase-153** (R2-b2b1-UI-04 + R2-b2b1-UI-05, see `workspace/phase-153/REPORT.md`):
      **post-lock snackbar channel** — the message pipeline is now a bounded
      `StateFlow<List<SnackbarMessage>>` FIFO (`NoteflowViewModel.kt:1344-1345`, clearable; the
      `showSnackbar(text, isLong)` emission API is unchanged for its ~140 call sites) gated by the
      pure-JVM `services/SnackbarLockPolicy.kt` decision table (`mayBufferWhileLocked(isAuthenticated,
      text)` — unlocked buffers everything, locked DROPS every vault-content message; only
      `messageSurvivesLock` = the single fixed `VOICE_RECORD_DISCARDED_NOTICE` passes);
      `lock()` CLEARS the queue inside the hasMasterPassword teardown (`:4252`); the root snackbar
      collector in `MainActivity.kt` is `LaunchedEffect(authenticated)` (`:257`) — locked = no
      vault-content snackbar can render over the LockScreen (boundary dismiss via `:259`), unlock =
      drain only survive-lock notices via `nextSnackbarMessage`/`consumeSnackbar`. The pre-fix
      channel was a `MutableSharedFlow` the lock could not purge. **Lock-during-voice-recording** —
      `VoiceNoteManager.release()` returns a one-shot discard flag set only in the DEK-null
      `finalizeRecording` fail-closed branch (`:268`, plaintext temp still swept); `EditorScreen`'s
      teardown (`:255`) republishes `VOICE_RECORD_DISCARDED_NOTICE` through the persistent pipeline so
      a finished-but-discarded recording is honestly announced after unlock instead of dying with the
      editor's short-lived `recordingError` banner.
    - **Implemented in phase-158** (deferred ROADMAP 22.5, see `workspace/phase-158/REPORT.md`):
      **share-capture polish** on top of phase-140 — `services/PendingShareState.kt` gains
      `ShareCaptureMode` (`NEW_NOTE`/`APPEND_TO_ACTIVE`, `fromToken` fails closed),
      `PendingShareConfirmState.stagedAtMs`, `PendingShareState.captureMode`, and
      `PendingSharePolicy` additions: `CONFIRM_HOLD_EXPIRY_MS`=10min + `isExpired` (per-session
      expiry for an UN-confirmed hold), `resolveAppendTarget(hasActivePage, clipHasImages, mode)`
      (append ONLY for a text-only clip with an active page; images / no active page degrade
      honestly to `CREATE_NEW_NOTE`), `deferredAppliesNow(authenticated)`, and the NON-SECRET
      captured marker (`capturedMarkerPayload` = flag + wall-clock stamp, NEVER clip content,
      persisted via `SettingsManager.capturedSharePending/AtMs`). The confirm `AlertDialog`
      (`MainActivity.kt`) now carries the new-vs-append radio choice (append disabled + explained
      when unusable), auto-cancels a stale hold at the expiry deadline (re-checked so an
      answered confirm survives), and keeps its `rememberSaveable` choice; the apply effect routes
      through `resolveAppendTarget` → `appendSharedContentToPage` (`NoteflowViewModel.kt:2241`, reads
      the LATEST committed body via `readMarkdownNoteBody`, writes via `saveMarkdownNoteBody` —
      B2-UI-1 lock-gated) or `createNoteFromSharedContent`. Honest defer/drop: applies only on an
      authenticated frame, password-vault `lock()` drops both states + clears the marker (no content
      survives a lock, no stale flag), and only the non-secret marker ever touches disk.
      Phase-158 review-fixes: the captured marker is now READ — after a process death mid-capture
      (the clip is never persisted) the next unlock shows an honest "previous capture wasn't
      captured" notice (`MainActivity.kt`, cleared once shown, skipped while a confirm/deferred clip
      is live); `appendSharedContentToPage`'s `onDone` returns the combined body which `MainActivity`
      pushes into the open `MarkdownPreviewScreen` (`externalBodyUpdate`, one-shot) so the appended
      text is visible immediately and a stale pre-append snapshot can no longer be saved back over it
      (snackbar shows only on real success); the mode-choice + phase-158 snackbar strings moved into
      `strings.xml`.
  - **Implemented in phase-133** (user-requested UI/UX, see `workspace/phase-133/REPORT.md`): new
    pages (Add Page FAB) and Daily Journal entries now open IMMEDIATELY on click. Root cause: the
    active page was resolved only via `pages.find { it.id == activePageId }` against the
    section-filtered async Room flow → null on the creation frame → transition dropped. New pure-JVM
    `services/ActivePageResolution.kt` = `ActivePageResolution.resolve(activePageId, synchronous,
    allActivePages, sectionPages)` (precedence: synchronous in-memory copy → `allActivePages` →
    section `pages`) + `ActivePageTracker`/`ActivePageTrackerState` (open captures the page
    synchronously, `onAuthoritative` refreshes from Room emits and drops confirmed-but-deleted pages,
    `restore` re-arms the saved page id). `MainActivity.kt:226-289` collects `allActivePages`, holds
    the synchronous copy in a `remember`-ed tracker, resolves through the fallback helper, and re-arms
    via `LaunchedEffect(allActivePages, pages)`; a second effect drops the id/editor when a confirmed
    page disappears (delete/trash). `NoteflowViewModel.openOrCreateDailyNote`/`openPageByTitle` (and
    the same latent fix in `createNoteFromSharedContent`) return the page, re-synchronize
    `observePages(sec.id)`, and dispatch `onOpen` via `withContext(Dispatchers.Main)`. Tests:
    `Phase133ActivePageResolutionTest` (19). 1825 app tests green + `assembleDebug` green.
- **Import/export**: `services/ImportExportService.kt:30` (encrypted backup/restore, `validateBackupPassword`,
  PDF/HTML/image export).
  - **Implemented in phase-55** (B1-DB-5, see `workspace/phase-55/REPORT.md`): the HTML/Obsidian
    zip import readers are zip-bomb-safe. New pure-JVM `services/ImportArchivePolicy.kt` owns the
    budgets (50MB/entry, 200MB total, 100× declared-vs-actual ratio 4KB floor, 10k entries, 200MB
    archive input) with single-settle accounting (`checkEntryChunk` per chunk, `settleEntryRead`
    once per completed entry) and raises `ImportSizeLimitException` (an `IllegalStateException`).
    `readUriBytes` (`ImportExportService.kt:89-118`) streams under a hard cap and re-throws the
    dedicated exception; `importHtmlZipOrFolder` (`:2063`) and `importObsidianVaultZip` (`:2250`,
    single-pass) route every entry through `claimEntry`/`readEntryBounded`; wholesale `zis.readBytes()`
    is gone. Restore callers keep the 400MB `MAX_BACKUP_INPUT_BYTES` cap; HomeScreen surfaces a
    non-alarming `"Import skipped: …"` snackbar. Tests: `B1Db05ImportZipBombTest` (13) — 1142 green.
  - **Implemented in phase-56** (B1-DB-7, see `workspace/phase-56/REPORT.md`): restore can no longer
    accept a legacy PLAIN (unencrypted) zip nor open a backup's SQLCipher DB with the empty
    passphrase. Two pure-JVM file-level helpers in `ImportExportService.kt`:
    `isPlainPkBackupBytes` (raw `PK`-header classifier) + `backupRestoreOpenCandidates`
    (`listOfNotNull(backupDekHex, currentDekHex).filter { it.isNotBlank() }.distinct()`, the
    historic `""` empty-key entry is gone AND stripped fail-closed). `importBackup` rejects a raw
    plain zip BEFORE any decrypt/extract; the authenticated device-DEK-encrypted legacy path and
    the NFLB2 password-v2 path are untouched. `HomeScreen` picker refuses a PK zip with a
    snackbar before any confirm dialog; the device-keyed legacy dialog warns UNTRUSTED/UNSIGNED.
    Tests: `B1Db07PlainZipRestoreRejectedTest` (12) — 1154 green.
  - **Implemented in phase-59** (B1-PLAT-3, see `workspace/phase-59/REPORT.md`): no export
    auto-writes to public Downloads. New pure-JVM `services/ExportDestinationPolicy.kt` classifies
    every `ExportKind` (`ENCRYPTED_BACKUP`, `OBSIDIAN_VAULT`, `HTML_SITE`, `VAULT_ZIP`, `PAGE_PNG`,
    `PAGE_WEBP`, `PAGE_PDF`, `DOCUMENT_PDF`, `NOTE_HTML`, `LAYERED_PSD`) — MIME + suggested name +
    `requiresPlaintextWarning` (true only for the whole-vault plaintext kinds). New
    `ui/components/SaFExporter.kt` (`rememberSaFExporter`) is the SINGLE route for every user-facing
    export: `ACTION_CREATE_DOCUMENT` picker (API 19+, below minSdk 26, no fallback) with a bold
    "Export is NOT encrypted" consent dialog ahead of the picker for the 3 whole-vault plaintext
    kinds, and transfer-then-delete of the cacheDir staging copy on success. All 6 public-Downloads
    copies in `ImportExportService.kt` + the PSD copy are removed; HomeScreen (5 flows) +
    EditorScreen (7 flows) route through the exporter; LocalSend's cacheDir payload path unchanged.
    Tests: `ExportDestinationPolicyTest` (11) + `B1Plat03ExportConsentTest` (5) — 1196 total.
  - **Implemented in phase-141** (R2-B1P-02/03 + R2-b2b3-LOG-04, see
    `workspace/phase-141/REPORT.md`): export/share hygiene. `SaFExporter.kt` now routes EVERY
    picker outcome (ok / ok-but-copy-failed / cancel / no-data) through the pure-JVM
    `services/ExportStagingPolicy.kt` decision table (`cleanupAfterSaF`): delivered → DELETE,
    copy-FAILED → KEEP (a dropped write never destroys the fresh export), cancel/no-data →
    DELETE (no decrypted archive lingers in the cache dirs); the plaintext-warning consent
    dialog's dismiss + Cancel also delete the staged file. `plugins/export/ExportShareHelper`
    (`ExportEnginePlugin.kt`) is now `chooserForExport` — the Export Engine `ACTION_SEND` is
    ALWAYS wrapped in `Intent.createChooser(...)` (target always user-chosen) and
    `EditorScreen.kt` launches it via an ActivityResult launcher that deletes the plaintext
    staging file on dismiss / deliver / no-receiver. Share subject is the generic
    `SHARE_SUBJECT = "Exported note"` (never the note title / filename-derived subject).
    Tests: `ExportStagingPolicyTest` (8) + `Phase141ExportHygieneTest` (7) — 1978 total green. **Review
    fixes (2026-08-18):** the two new in-flight states survive rotation/recreation
    (`SaFExporter.pendingRequest`/`pendingWarningKind` and `EditorScreen.pendingExportFilePath`
    are `rememberSaveable`, path-backed for the share file), so a recreation mid-picker/chooser
    still resolves staging cleanup; the `(Boolean) -> Unit` export callback became the 3-way
    `SaFExportResult` (SAVED/CANCELLED/FAILED) so a copy-failure is no longer shown as a cancel;
    `chooserForExport` moved inside the launch try so a chooser-build failure also deletes the
    staging file.
  - **Implemented in phase-142** (R2-B1N-01 + R2-B1P-04 + R2-B1N-05, see
    `workspace/phase-142/REPORT.md`): network/stream I/O hardening. (1) LocalSend peer bodies are
    read CAPPED mid-stream via the new pure-JVM `services/localsend/LocalSendBodyReadPolicy.kt`
    (`readText(reader, limit)` aborts with `ResponseTooLargeException` on the first window that
    crosses the cap — never slurp-then-truncate): register probe `REGISTER_BODY_LIMIT` 2048
    (`LocalSendSender.kt:264`), `httpPost` success 8192 + errorStream 512 (`:454-463`). (2)
    `services/BoundedStreamCopier.kt` `copyBounded` gained the 16-consecutive-idle-reads bailout
    (`MAX_CONSECUTIVE_IDLE_READS`, mirroring the phase-81 `AttachmentIngestPolicy` sibling) so a
    hostile ContentProvider stream returning 0 forever can no longer hot-spin the IO thread. (3)
    Plugin allow-lists normalized to `(scheme, host, effective-port)` triples via the new pure-JVM
    `services/HostPortAllowList.kt` (reuses `WebDavHrefResolver.Origin`; bare `host`/`host:port`
    entries default to `https://host:443`, full `http(s)://host[:port]` entries keep their own
    scheme/port; unparseable → null, fail closed, additive-only): wired into
    `CompileTimePluginPinStore.isAllowedDownloadHost` (`:112`), the shared `isHostAllowListed`
    gate (`:216`), `HttpsManifestTransport` (`PluginManifestFetcher.kt:130`) and
    `PluginDownloader` (`:147`) — `https://<allowed-host>:8443/...` can no longer pass. Review
    fixes: `WebDavHrefResolver.originOf` now rejects empty-host (`URL("https:///no-host")` → host
    `""`) and non-http(s) schemes (`WebDavHrefResolver.kt:50-58`) so `originOfOrNull` fails
    closed. Tests: `LocalSendBodyReadPolicyTest` (11), `HostPortAllowListTest` (16),
    `BoundedStreamCopierTest` (9), `CompileTimePluginPinStoreTest`/`PluginDownloaderTest`/
    `HttpsManifestTransportTest` (+1 each) — 1954 total green.
  - **Implemented in phase-81** (B2-DOS-05, see `workspace/phase-81/REPORT.md`): attachment/import
    ingestion is bounded DURING the read. New pure-JVM `services/AttachmentIngestPolicy.kt` is the
    single decision table (`MAX_ATTACHMENT_BYTES` = 25 MB, `READ_BUFFER_BYTES` = 64 KiB):
    `boundedReadBytes(input, maxBytes)` streams over a fixed buffer and throws
    `ImportArchivePolicy.ImportSizeLimitException` mid-stream on the first chunk crossing the cap
    (heap never exceeds budget + one buffer); `readTextHead(file, maxBytes)` is a head-bounded,
    prefix-preserving UTF-8 text head read (a multi-byte char split at the cap decodes lossily to a
    single replacement char — no over-read past the budget; empty for missing/unreadable/empty).
    `EditorScreen`'s 3 pickers (`:236` custom-bg, `:263` paper-texture, `:829` photo embed) route
    through `boundedReadBytes` with a dedicated per-site size-limit snackbar;
    `NoteflowViewModel.restoreEncryptedBackupFromZip` (`:3145-3147`) replaced `sourceZip.readBytes()`
    with `boundedReadBytes(it, ImportExportService.MAX_BACKUP_INPUT_BYTES)` (400 MB restore budget
    preserved, enforced in-flight); `DocumentTextExtractor` reads only a 25 MB PDF-head
    (`MAX_EXTRACT_BYTES`) / 1 MB text-head (`MAX_TEXT_HEAD_BYTES`); legacy plaintext-file-body reads
    in `NoteBodyVaultPolicy.kt:64` and `WikiLinkParser.kt:274` use `readTextHead`. Tests:
    `B2Dos05AttachmentIngestTest` (14) — 1470 total green.
    **Phase-81 review fixes** (same discovering commit lineage):
    - `NoteRepository.migrateLegacyPlaintextNoteBodies` (`:515` pre-fix) — the last unbounded
      `file.readText()` on the legacy-body surface — now refuses files > `MAX_ATTACHMENT_BYTES`
      (never read into the column, never deleted) and reads within-cap bodies head-bounded;
    - `restoreEncryptedBackupFromZip` over-budget archives now fail CLOSED with a truthful
      "Backup is too large to restore (max 400 MB)" message (`(Boolean, String?) -> Unit`
      callback consumed by `WebDavSyncDialog`); the pre-fix generic "Failed to restore…" only
      remains for non-budget failures;
    - `NoteBodyVaultPolicy.resolveBodyForDisplay` no longer returns a truncated head for a legacy
      body > `MAX_ATTACHMENT_BYTES` — it falls through to the full encrypted column and leaves
      the oversized file untouched (a truncated head would be written back on the next save and
      the full file deleted);
    - `EditorScreen`'s 3 picker reads moved off the main thread (`withContext(Dispatchers.IO)`);
    - `AttachmentIngestPolicy.boundedReadBytes` guards a contract-breaking stream that returns 0
      repeatedly (throws `IOException` after 16 idle reads instead of busy-spinning).
    Tests: `B2Dos05AttachmentIngestTest` (15) — 1471 total green.
  - **Implemented in phase-82** (B2-DOS-06, see `workspace/phase-82/REPORT.md`): layered PSD
    export no longer materializes N full-page ARGB bitmaps + N per-layer channel buffers at
    once (a 25-layer export dropped from ~350 MB peak to a bounded ~125-132 MB). New pure-JVM
    `services/PsdExportPolicy.kt` (`MAX_EXPORT_LAYER_COUNT` = 16, `capLayerCount`/
    `omittedLayerCount`/`noticeMessage`) is the single layer-budget decision table;
    `ImportExportService.exportPageToPsd` (`:2422`) keeps the TOP 16 layers of
    `layers.sortedBy { it.zOrder }` (highest zOrder = front-most) via
    `takeLast(exportedDataLayers)`/`dropLast(exportedDataLayers)` BEFORE any per-layer bitmap
    is created, and returns the new `PsdExportService.PsdExportOutcome(file,
    exportedLayerCount, omittedLayerCount)` so `EditorScreen.kt:1407-1420` shows a one-time
    non-alarming "layers omitted (max 16)" snackbar when the cap bites (shown only after a
    successful export so a cancelled picker cannot hide it). The omitted BOTTOM layers are
    folded into ONE bounded merged-preview bitmap (`compositeExtras`) so the PSD's flattened
    composite still shows the full page. `PsdExportService.exportLayersToPsd` writes the
    layer-and-mask section STREAMING: info records in a tiny bounded buffer
    (`layerRecordBytes`), per-layer channel pixels straight to the destination
    `DataOutputStream` one channel at a time (`writeChannelPixels`) — the pre-fix
    `layerPixelBlocks` full-size channel-block accumulation and per-layer `IntArray` are
    gone, and ONE `IntArray(width*height)` is reused for every layer + the composite
    (additionally clamped to `MAX_EXPORT_LAYER_COUNT + 1`; composite extras bounded too);
    pure-JVM helpers `channelSizeFor`/`channelDataLength`/`layerSectionLength` keep the
    section length byte-identical.
    Tests: `B2Dos06PsdExportLayerCapTest` (16) — 1487 total green.
  - **Implemented in phase-83** (B2-DOS-07, see `workspace/phase-83/REPORT.md`): backup EXPORT no
    longer builds the ENTIRE vault zip in heap and then makes a second full-size AES-GCM copy
    (pre-fix `baos.toByteArray()` + `cipher.doFinal(zipData)` / `encrypt(zipData, key)` → Base64 —
    ~600 MB+ peak on a few-hundred-MB vault, OOM on every attempt). New pure-JVM
    `services/BackupExportPolicy.kt` owns the bounded streamers: `zipVaultEntriesToStream`
    (zip written entry-by-entry straight into a `ZipOutputStream(FileOutputStream)` staging FILE,
    never a `ByteArrayOutputStream`), `encryptStreamGcm` (file-to-file AES-GCM: header, then a
    bounded `ENCRYPT_CHUNK_BYTES`=64 KiB `Cipher.update` loop, then `doFinal` tail+tag — the JCE
    stream-mode contract makes it BYTE-IDENTICAL to the old single `doFinal`, so the `NFLB2`
    on-disk layout is unchanged and legacy restores read it unmodified), and
    `encryptStreamDeviceKeyedBase64` (GCM chunked + `java.util.Base64.Encoder.wrap(NonClosingSink)`
    — same legacy `[version][iv][ciphertext+tag]` wire format, no ~1.37x in-heap expansion).
    `ImportExportService.exportBackup` (`:1268-1369`) stages the zip under
    `File(context.cacheDir, BackupExportPolicy.stagingFileName(backupName))` (`.zip-staging`,
    deleted in `finally` :1366) and encrypts file-to-file; all callers (HomeScreen device-keyed +
    password, WebDAV C2b, LocalSend VAULT_BACKUP) consume the returned cacheDir `File` unchanged.
    Peak heap is one 64 KiB chunk + one `Cipher.update` output + the tag, never the archive.
    `EncryptionService` GCM constants flipped `private`→`internal` for the policy.
    Tests: `B2Dos07BackupExportStreamingTest` (8) — full suite green + `assembleDebug` green.
  - **Implemented in phase-137** (R2-B1D-05 + R2-B1D-03, see `workspace/phase-137/REPORT.md`):
    every DB-file copy producer now runs checkpoint-then-copy. `exportBackup` is the SINGLE
    disciplined producer — it runs `repository.checkpointWal()` (FULL) + the HMAC re-stamp
    BEFORE copying (`ImportExportService.kt:1335-1336`), then copies the main file through the
    new pure-JVM `services/VaultSnapshotCopyPolicy.kt` verified snapshot
    (`checkpointThenCopy` `:1337`, `VaultSnapshotCopyPolicy.kt:99-125`): SHA-256 of the source
    before + after the copy AND of the staging — accepted only when all three match, retried
    up to `MAX_VERIFY_ATTEMPTS=3` if a concurrent WAL auto-checkpoint mutates the source
    mid-copy, FAILS CLOSED (loud `IllegalStateException`, torn staging deleted) if the source
    never stabilizes. The DB zip entry reads the verified `stagedDb` (`:1346`), never the raw
    live file. `exportBackup` REQUIRES the `NoteRepository`; all four producers pass it:
    HomeScreen plain + password backups (`HomeScreen.kt:613-617`, `:1419-1424`), WebDAV
    (`NoteflowViewModel.exportEncryptedBackupToZip`), and LocalSend `VAULT_BACKUP`
    (`LocalSendSendDialog.kt:147-152`) — which previously called `exportBackup` with NO
    checkpoint and shipped WAL-stale archives (R2-B1D-03). The now-redundant explicit
    checkpoint/re-stamp were removed from HomeScreen/WebDAV (single owner, no double HMAC).
    Tests: `Phase137BackupCopyConsistencyTest` (7).
  - **Implemented in phase-138** (R2-B1D-04, see `workspace/phase-138/REPORT.md`): restore DECRYPT is now
    FILE-TO-FILE (was ~800MB in-heap peak: encrypted archive + decrypted zip both materialized). The v2/v3
    payload decrypt streams to a transient staging FILE (`ImportExportService.decryptPayloadToFile` `:1528`,
    `tryParseBackupV2File` `:1661`, `validateBackupPasswordFile` `:1779`, legacy `decryptDeviceKeyedToFile`
    `:1992` — streaming Base64 + GCM chunk loop); picked URIs stage to cache files (`stageBackupUriToFile`
    `:1879`); `importBackup` takes a `File` (`:1910`); the v3 16-byte split-key prefix is skipped at extract
    (`restoreFromZip(zipFile, offsetBytes…)` `:2053` + `BackupExportPolicy.skipFully` `:239`). Mirror
    streamers `decryptStreamGcm`/`decryptStreamGcmLegacyZeroAad` live in `services/BackupExportPolicy.kt`
    (`:150`, `:200`). Budgets are now ONE pure-JVM `services/BackupBudgetPolicy.kt`: 100MB/entry, total =
    400MB wire cap (GCM out = in + 16 ⇒ a decryptable archive is always under the total), 40k entry belt,
    100x ratio seal — the export packer refuses at pack time (`claimPackFile` `:118`) exactly what the
    extractor refuses (`claimRestoreChunk` `:136`), killing "exportable-unrestorable" archives (was
    200MB/50MB asymmetric). Post-restore-failure reopen is guaranteed by the pure-JVM
    `services/RestoreFailSafe.kt` seam (`guaranteeReopenAfterRestore` `:28`, catches ANY Throwable incl.
    OOM) wired into all four entry points (`NoteflowViewModel.kt:2313/:2403/:3772`, `HomeScreen.kt:172`).
    The HomeScreen picker now classifies by FILE (`isNflbBackupFile`/`isPlainPkBackupFile`
    `ImportExportService.kt:3116/:3129`) — NFLB3 backups finally get the password dialog, not the legacy
    UNTRUSTED dialog. Tests: `Phase138RestoreStreamingTest` (12).
    **Review fixes (2026-08-18):** staged restore files are now deleted on EVERY
    HomeScreen outcome (`clearPendingRestore` + `performRestore` finally); the
    400MB wire cap is enforced on the ENCRYPTED export output (closes the
    incompressible-at-ceiling "exportable-unrestorable" band,
    `ImportExportService.kt:1455-1467`); the restore entry-count belt charges
    LEAF entries only (symmetric with the packer); the keystore-lost recovery
    defers `repository.encryptionKey = newDek` until AFTER the swap so a failed
    restore never reopens the old vault under the new key
    (`NoteflowViewModel.kt:2398-2442`); a dead "corrupted" rethrow was removed.
- **Update / self-install**: `services/UpdateService.kt:128` (`checkForDownloadedUpdates` — scans ONLY app-private
  `filesDir`/`cacheDir` through `UpdateTrustPolicy.isScanSafeDirectory`; public Downloads/external dirs are NEVER
  scanned, B1-PLAT-7), `:60` (`inspectApkFile` — classifies via the policy, trust-neutral copy), `:175`
  (`installApk` — first check is `UpdateTrustPolicy.mayInstall(trust, userConfirmedUntrusted)`, refuses unconfirmed
  UNTRUSTED files before any staging); `ui/components/Dialogs.kt` `AppUpdateDialog` — "Scan App Storage for APK"
  + strong untrusted-confirmation gate on "Install Update".
  - **Implemented in phase-61** (B1-PLAT-7, see `workspace/phase-61/REPORT.md`): new pure-JVM
    `services/UpdateTrustPolicy.kt` owns the trust model — no official channel ⇒ every locally-present APK is
    `UpdateSourceTrust.UNTRUSTED_LOCAL` (`classifySource`/`hasOfficialChannel`), `isPubliclyWritableDirectory`
    structurally refuses `/sdcard`·`/storage/emulated`·`…/Android/data/…` mounts, and `mayInstall` fail-closed-gates
    UNTRUSTED installs behind explicit user confirmation. The old scan of `getExternalFilesDir` + `/sdcard/Download`
    + `/storage/emulated/0/Download` and the "New update detected in local storage" conditioning wording are gone.
    Tests: `B1Plat07UpdateTrustTest` (11 test methods; review fix 2026-08-15 — see `workspace/phase-61/REPORT.md` "Addendum").
- **Onboarding / empty states / home glanceable stats** (Phase 156, see `workspace/phase-156/REPORT.md`):
  pure-JVM `services/OnboardingPolicy.kt` (first-run gate `shouldAutoShow(isFirstRun, hasMasterPassword,
  onboardingCompleted)` + 3 `OnboardingStep`s + privacy-stance copy), `services/HomeStatsMath.kt`
  (`countDistinctWikiLinks` bounded by `WikiLinkParser.MAX_LINKS_PER_PAGE`, `daysSinceBackup`,
  `backupChip`, `chips`); `ui/components/FirstRunOnboarding.kt` `FirstRunOnboardingSheet` (non-blocking
  `ModalBottomSheet`, 3 instant steps, no animated transitions — reduce-motion by construction);
  `ui/components/EmptyStateKit.kt` (`EmptyStateKind` incl. RECENT/KNOWLEDGE_GRAPH/VERSION_HISTORY/
  WEB_SEARCH, nullable `actionLabel` on `EmptyStateDecision`, query-aware PLUGIN_STORE/HOME_GRID);
  `ui/components/EmptyStateArt.kt` `IllustrationKind.HISTORY` + `drawHistory`. Wired: `HomeScreen.kt`
  (auto-show + stats chips + "Show help again" + no-backup nudge + RECENT/TRASH empty states),
  `KnowledgeGraphScreen.kt` (zero-node empty overlay behind a `graphLoaded` flag),
  `VersionHistoryBottomSheet.kt`, `WebSearchDialog.kt` (`SearchStage.NoResults`), `PluginStoreDialog.kt`
  (filter + "Clear filter"). `ImportExportService.exportBackup` records `SettingsManager.lastBackupTimestamp`
  at its single success chokepoint; `NoteflowViewModel` exposes `onboardingCompleted`/`lastBackupTimestamp`
  StateFlows + `countCachedWikiLinks()`. No DB schema change, no new dependencies.

- **Home widget "New note" quick-capture** (implemented in phase-158, deferred ROADMAP 22.5b — see
  `workspace/phase-158/REPORT.md`): a LIGHTWEIGHT in-base AppWidget that is a launcher shortcut ONLY.
  `ui/widget/QuickCaptureWidget.kt` (`AppWidgetProvider`) builds a RemoteViews (icon + fixed label,
  `res/layout/widget_quick_capture.xml`) whose single click is a `PendingIntent` to `MainActivity`
  carrying the explicit boolean `WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE`
  (`services/WidgetLaunchPolicy.kt` — pure-JVM intent contract incl. `WIDGET_PENDING_INTENT_REQUEST_CODE`,
  `hasQuickCaptureExtra(Map<String, Boolean?>)` true-only parse, geometry constants). Hard constraints:
  NO note content on the widget, NO vault/Room/keystore code in the widget process, NO periodic refresh
  (`res/xml/quick_capture_widget_info.xml` `updatePeriodMillis="0"`, 1x1/2x1, `home_screen`, resizeable),
  NO new permission, receiver `android:exported="false"` (`AndroidManifest.xml:37-47`). `MainActivity`
  reads the extra in both `onCreate` and `onNewIntent` (`handleQuickCaptureIntent`, typed extras map) and a
  `LaunchedEffect(authenticated, quickCaptureRequested)` fires `addPage("New Page", onCreated = setActivePage)`
  ONLY once the vault is authenticated — a locked-vault tap creates nothing (no DEK) and the flag is
  consumed so a later unlock never re-creates.

## Build / CI essentials

- `app/build.gradle.kts`: `namespace = "com.authorss81.noteflow"` (:11),
  `applicationId = "com.aistudio.inkflow.app.bkxjrz"` (:15), compileSdk 36, minSdk 26, JVM 17,
  Room schema → `app/schemas`, R8 minify on for release, `jniLibs.useLegacyPackaging = true`.
- **Gradle provisioning** — a committed wrapper exists for INTEGRITY-PINNING only: `gradle/wrapper/gradle-wrapper.properties:7`
  pins `distributionSha256Sum` for 8.13, but CI and grading builds run system `gradle` (8.13) directly, so the
  wrapper's checksum is NOT enforced on CI. Wiring `distribution-sha256-sum` / switching CI to `./gradlew` and
  pinning every workflow action to a commit SHA (R2-b2b2-DEP-01) are **PENDING USER APPROVAL** (workflow edits gate):
  the ready-to-apply mapping lives in `docs/CI_PINNING.md` and `.github/dependabot.yml` is committed. Tests: `gradle testDebugUnitTest`; build: `gradle assembleDebug`
  / `assembleRelease`. Runs in GitHub Actions (gradle 8.13, Temurin JDK 21).
  - **Implemented in phase-147** (R2-b2b2-DEP-01, `workspace/phase-147/REPORT.md` + `docs/CI_PINNING.md`):
    NOT-APPROVED path — all 19 `uses:` tags in `release.yml`/`android.yml`/`llops.yml` are mapped to full
    commit SHAs (GitHub-API resolved 2026-08-18) and `.github/dependabot.yml` (github-actions, weekly, grouped)
    now maintains SHA pins once applied; the workflow edits themselves stay PENDING USER APPROVAL per AGENTS.md.
  - **Implemented in phase-146** (R2-b2b2-DEP-02/03/04, `workspace/phase-146/REPORT.md`): the wrapper
    (`gradlew`/`gradlew.bat`/`gradle/wrapper/gradle-wrapper.jar` + pinned `.properties`) is committed; the
    lockfile now runs `<verify-signatures>true</verify-signatures>` against a committed 57-key `<trusted-keys>`
    block + local keyring (`gradle/verification-keyring.gpg` + `.keys`), and `mavenCentral()` is one-way
    allow-listed in `dependencyResolutionManagement` AND `pluginManagement`
    (full-match `includeGroupByRegex` — adding a library MUST add its group to the settings allow-lists AND
    `CentralAllowlist` in `app/src/test/.../CentralAllowlist.kt` in the same commit).
  - **Implemented in phase-75** (B2-DEPS-03, `workspace/phase-75/REPORT.md`): `gradle/verification-metadata.xml`
    is committed — Gradle 8.13 auto-enables STRICT checksum verification (deps + metadata + build plugins)
    whenever that file exists. To add/upgrade a dependency, regenerate with
    `gradle --write-verification-metadata sha256,pgp --export-keys testDebugUnitTest assembleDebug`, review
    the diff (trusted-keys, pgp entries, ignored-keys, keyring), then commit. There is NO
    `dependencyVerification {}` settings DSL in Gradle 8.13 — do NOT add one (it breaks settings
    compilation). The `google()` content filters (`com.android.*`/`com.google.*`/`androidx.*`) are
    mirrored in `dependencyResolutionManagement` and must stay in sync with `pluginManagement`.
- **`metadata.json` (repo root) is the committed project metadata source of truth** — name, namespace,
  applicationId, version (VERSION_CODE/VERSION_NAME env overridable, default 2/"1.0.0"), SDK levels,
  build toolchain (Gradle 8.13, wrapper committed for integrity-pinning while CI runs system gradle, AGP 8.7.3, Kotlin 2.0.21, KSP, JVM 17), module list, the
  capability-bucket partition (compile-time / downloadable / unserved) and the downloadable-plugin
  records. Parsed/validated by pure-JVM `app/src/test/java/com/authorss81/noteflow/services/ProjectMetadata.kt`
  (Gson; fail-closed fields; requires `usesGradleWrapper=false`, `inBaseApk=false`, unknown-key/gap/dup/sdk-floor
  detection vs `PluginCapability.ALL`). The alignment suite `Phase131MetadataAlignmentTest` (11 tests) cross-checks it
  against the build files + framework, so drift fails `testDebugUnitTest`.
  - **Implemented in phase-131** (see `workspace/phase-131/REPORT.md`): `plugins/llm` build script is fully
    catalog-aligned — its last raw coordinate `junit:junit:4.13.2` → `libs.junit`
    (`gradle/libs.versions.toml:32-34,88`); the module builds STANDALONE under system Gradle
    (`gradle :plugins:llm:packagePlugin` green) and its B2-DEPS-04 fail-closed signing gate
    (`PLUGIN_SIGNING_KEYSTORE_B64`+`PLUGIN_SIGNING_STORE_PASS`) was re-verified loud-refusal without env
    AND positive (`signPlugin`→`verifyPluginSignature`→`pluginMetadata` emits `sha256`+`pinnedCertHash`
    on `llm-plugin-signed.jar`) with a throwaway keystore that was deleted afterwards (no keystore/signed
    pin committed; `GeneratedLlmPluginPin.kt` stays `null` fail-closed). Base debug APK binary-verified 0
    mediapipe classes / 0 native libs. `gradle testDebugUnitTest` 1853 total green (0/0/0) + `assembleDebug` green.
  - **Implemented in phase-162** (see `workspace/phase-162/REPORT.md`): `plugins/llm/build.gradle.kts` no longer
    uses the deprecated `project.exec` (breaks Gradle 9 config-cache isolation) — both `signPlugin` and
    `verifyPluginSignature` run `jarsigner` through a list-form `ProcessBuilder` inside `doLast` via the
    `runExternal(cmd, failureMessage)` helper (`plugins/llm/build.gradle.kts:283-292`; output streamed to a
    discarded buffer so keystore paths/passwords never reach the build log; `GradleException("… exit code X")`
    on non-zero). `gradle/verification-metadata.xml:6-14` gained the `<trusted-artifacts>` allow-list for
    `com.android.tools.build` / `org.jetbrains.kotlin` / `androidx.databinding` so a NEW toolchain artifact
    resolves without failing closed on a missing checksum entry (verification still on). Part B (verify-only)
    re-confirmed all six decryption-failure/data-integrity safeguards with `file:line` evidence — see REPORT.
  - **Implemented in phase-170** (Phase-32-NEW-01 + NEW-02, see `workspace/phase-170/REPORT.md`): the base APK's
    lingua `language-models/` corpus is trimmed from 75 to the 24 `LanguageDetectionCore.SUPPORTED` languages —
    `packaging.resources.excludes` strips `language-models/<iso>/**` for the 51 unused codes
    (`app/build.gradle.kts:125-127`, globs compiled from the `LINGUA_UNUSED_LANGUAGE_ISOS` list `:13`, which is
    source-pinned against the code subset by `Phase170LinguaTrimTest`) — and release builds emit ABI-split APKs
    (`splits { abi { isUniversalApk = true … } }`, `:140-147`, enabled ONLY when a release task is requested so
    `assembleDebug` stays monolithic). Release outputs: `app-{arm64-v8a,armeabi-v7a,x86,x86_64}-release.apk`
    (~53.3-56.3 MB each) + `app-universal-release.apk` (~96.9 MB, all 4 ABIs, sideload/emulator channel); every
    split is signed by `releaseConfig` and passes `apksigner verify` (B1-PLAT-1 fail-closed untouched).
    `docs/RELEASE.md` Artifacts table updated. `gradle assembleDebug` + `assembleRelease` green.
  - **Implemented in phase-171** (Phase-32-NEW-03 + NEW-04, see `workspace/phase-171/REPORT.md`): the release
    APK now carries **APK Signature Scheme v3** alongside v2 — `enableV3Signing = true` in
    `signingConfigs.create("releaseConfig")` (`app/build.gradle.kts` ~`:57`) because AGP 8.7.3 only auto-enables
    v3 at `minSdk >= 28` and this app floors at 26 (empirically confirmed: baseline release APK was v2-only;
    post-fix all 5 outputs `apksigner verify --print-certs -v` = `v2:true` + `v3:true`, real `CN=InkFlow Release`
    identity — v1/v4 untouched, minSdk NOT bumped, B1-PLAT-1 fail-closed gate re-verified: keystore-less
    `assembleRelease` still fails "Release build refused"). The plugin-update channel's operator substitution is
    now runbooked: `docs/PLUGIN_CHANNEL.md` (the compile-time `PLUGIN_MANIFEST_CERT_PIN`
    `HostedPluginManifest.kt:242-243` pin recipe is the LEAF-cert-DER hash `PinnedCertHash.base64Sha256`
    computes — NOT the RFC-7469 `-pubkey`/SPKI pin, which this app rejects, verified), go-live checklist,
    cert-renewal procedure; the placeholder is intentionally NOT substituted. `PinnedCertHashTest` (4→9) pins
    the fail-closed `matches` contract (placeholder/wrong/near-miss pins never match; known-good does) and the
    exact placeholder value, so a changed-but-wrong constant can never silently slip in.
- Tests: `app/src/test/java/com/authorss81/noteflow/` (~110 unit tests, pure JVM, no androidTest).
- **Do NOT run Gradle on the Windows dev machine** (no SDK; CI-only builds).
- Version: `VERSION_CODE`/`VERSION_NAME` env (default 2 / "1.0.0"). Release signing is FAIL-CLOSED
  (B1-PLAT-1): `KEYSTORE_FILE`+`KEYSTORE_PASSWORD`+`KEY_ALIAS`+`KEY_PASSWORD` must be supplied or
  `assembleRelease` refuses — there is NO debug-keystore fallback (corrected 2026-08-17; the old "falls
  back to debug keystore" note was stale). **Since phase-171 the release APK is signed with BOTH
  v2 and v3 (APK Signature Scheme v3, `enableV3Signing = true`, for in-place key rotation) — verify with
  `apksigner verify --print-certs -v` expecting `v2 scheme: true` AND `v3 scheme: true`.**
  - **Implemented in phase-32** (APK attack, see `workspace/phase-32/REPORT.md`): the release APK was built and audited with apktool/jadx/androguard/APKiD/strings/apksigner/readelf. Confirmed at binary level: release release signing is the well-known Android **debug** cert (`CN=Android Debug`, SHA-256 `81a2980a…`, v2-only scheme — B1-PLAT-1 + new Phase-32-NEW-03); base APK bundles an **80.2 MB packed `language-models/` n-gram pack (~199 MB raw = 56% of the 142 MB release APK) that is the compile-time `lingua` language-detection library's corpus** (Phase-32-NEW-01 — identical byte-for-byte to the lingua JAR; note the review corrected the initial "ML Kit translation models" attribution: ML Kit translate models are runtime-downloaded, only its `libtranslate_jni.so`/`libmlkit_google_ocr_pipeline.so` natives are baked in) despite the downloadable-plugin hard rule; no ABI splits (Phase-32-NEW-02); plugin-manifest cert pin is still the placeholder `sha256/AAECAwQFBgcI…` so hosted plugin updates fail closed until the operator substitutes the real pin (Phase-32-NEW-04, B1-CRYPTO-01 fix wiring verified). Positives re-verified: release not debuggable, FLAG_SECURE wired, allowBackup=false, R8 ON, no tasks-genai/GGUF in base, no hardcoded secrets in 1M+ strings.
- **Implemented in phase-32 review fix (2026-08-15)**: `scripts/phase_runner.sh` only writes a phase's `.done` if the `opencode run` left working-tree changes outside `logs/` + the phase's own markers (`tree_work`/`has_new_work` in `phase_runner.sh`). A zero-work run (opencode exit 0 with no delta — the phase-32 false completion at commit `6b17422`) counts as a failed attempt and leaves a `.no_work` marker; phase-32's bogus `.done` was removed so the pipeline re-selects it. **Second fix (same day)**: normal-run mode now also short-circuits when `.done` already exists (`phase_runner.sh` "Already-done guard") and clears stale failure markers (`.deferred`/`.no_work`/`.session`/`.deferred_attempts`/`.attempts`), so a completed phase is never re-run — phase-32 had been re-selected after completion, leaving contradictory `.no_work`+`.deferred` alongside `.done` (commits `44a7210`+`27b93fd`); those stale markers are now removed.

## Libraries

AGP 8.7.3 · Kotlin 2.0.21 · KSP · Compose BOM 2024.12.01 · Room 2.6.1 over SQLCipher 4.9.0 ·
androidx.ink 1.0.0 · ML Kit text-recognition 16.0.1 + translate 17.0.3 (base) ·
**MediaPipe tasks-genai 0.10.25 = downloadable plugin only, NEVER base APK** ·
commonmark 0.29.0 + gfm-tables · Lingua 1.2.2 · jsoup 1.17.2 · Coil 2.7.0 · Gson 2.11.0 ·
androidx.biometric 1.1.0 · coroutines 1.9.0.

## Known broken / gotchas (agent must know)

1. `applicationId = "com.aistudio.inkflow.app.bkxjrz"` vs `namespace = "com.authorss81.noteflow"`
   (intentional mismatch; AGENTS.md's applicationId value is stale — trust the build file).
2. Wrapper committed (phase-146) but CI still runs system gradle 8.13 (checksum pin not enforced on CI
   until phase-147 wires it). Local machine can't build.
3. ROADMAP.md `[x]` claims are not all true — trust `AGENTS.md` + `docs/phase-status.md` truth tables.
4. **Base-APK size is a hard constraint**: heavy native libs (tasks-genai LLM, heavy OCR) MUST stay
   downloadable plugins. Never add them to the base app.
5. `extractNativeLibs="true"` (`useLegacyPackaging=true`) required for SQLCipher `.so` on SDK 36 (16KB pages).
6. `allowBackup="false"` + data-extraction rules — never re-enable. FLAG_SECURE in non-debug.
7. Baseline profiles disabled (AGP bug); unit tests use `isReturnDefaultValues = true` (no Robolectric).
8. `INTERNET` used only by WebDAV sync + LocalSend. WebDAV HTTPS-only unless local-network opt-in.
9. Duplicate `WikiLinkParser` (`utils/` vs `services/`) — `services/` is the one screens use.
10. Two plugin-state persistence layers exist (`SettingsPlugin*Store.kt` vs `plugins/runtime/Plugin*Store.kt`) —
    keep them in sync when changing install/update state.
11. **Round-2 security audit** (phase-116, 2026-08-17): full source re-audit on the post-fix tree — 43 findings
     (0 CRITICAL · 0 HIGH · 12 MEDIUM · 26 LOW · 5 INFO) live in `docs/security-report-round2.md`; phase-118
     appends dynamic/APK findings to the same file. Audit-only, no code changed; fixes are phase-119's job.

Implemented in phase-161 (2026-08-19): Kali round-2 triage → generated the next
pipeline phases starting at **phase-170** (max existing was 169). The Kali
dynamic pass (phase-160) is `.blocked` — `docs/kali-report-round2.md` is
APK-metadata only, no findings. Genuinely-OPEN round-1 APK packaging findings
(fix phases): Phase-32-NEW-01 lingua corpus trim + ABI splits → **170**;
Phase-32-NEW-03 v3 signing + NEW-04 plugin cert-pin runbook → **171**.
Feature phases 172 (editor/canvas productivity), 173 (FileTransfer plugin over
LocalSend + invocation journal), 174 (reading & authoring UX). All `file:line`
anchors and DoD live in each `workspace/phase-NN/PROMPT.md`. `R2-b2b2-DEP-01`
(CI action pinning) remains `PENDING USER APPROVAL` (workflow edit) — not
re-triaged.

## Phase status truth table
See `docs/phase-status.md` — per-phase `DONE`/`PARTIAL`/`NOT STARTED` with verified commit evidence.
`docs/phase-status-gaps.md` lists deferred sub-items.

### Implemented in phase-160 (Kali STATIC pass)
Release-APK static security audit now has a complete deliverable + file-path anchors:
`docs/kali-report-round2.md` (27 rows `R2-KS-01..07`, `R2-KS-10..19`, `R2-KS-20..29`) + `workspace/phase-160/REPORT.md` +
`docs/pentest-findings-2026-08-19.md`. Key verified anchors: DB quarantine +
FULL WAL checkpoint + HMAC recompute (`com/authorss81/noteflow/data/db/NoteflowDatabase.java:230-301,420-426`, `w2/C3697c.java:153,249-255,581-585`); AndroidKeyStore DEK `noteflow_dek_key[_auth]` + biometric invalidation (`w2/C3694b0.java`); plugin sandbox loader (`n2/G.java`) + static scan (`n2/C2548g`) + constant-time pin compare (`o3/J3`); plugin cert-pin placeholder still default → updates FAIL CLOSED (`m0/C2256b.java:772-773`); WebDAV HTTP opt-in allowlist + cross-host auth-strip (`w2/J2.java`, `X5/a.java:347`); MediaKit-baked-in base-APK **MEDIUM** findings (lingua 207.6 MB/75 langs `X2/g.java:77`; ML Kit OCR+translation) — fix-phase candidates pending phase-167 triage; 12 dynamic checks D1–D12 declared deferred with operator reproducers.
