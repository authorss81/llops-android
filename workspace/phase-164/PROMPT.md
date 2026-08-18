# Phase 164: Tag vault must scope to the current notebook, not all notebooks [NOT STARTED]

You are working on **InkFlow/Noteflow**. Bug: the tag vault/explorer shows tags
from ALL notebooks instead of only the currently selected notebook.

Read `docs/ARCHITECTURE.md` and `docs/phase-status.md` first.

## Context
- Notebooks and pages both carry a comma-separated `tags` string. Notebooks also
  have their own tag list (`NoteflowViewModel.updateNotebookNameAndTags`,
  `addNotebook`, `renameTag`/`deleteTag` at ~L2064-2120 walk BOTH `nb.tags` and
  `pg.tags`).
- `TagExplorerView.kt` (`ui/components/TagExplorerView.kt`) renders the tag vault.
  Its tag list appears to be GLOBAL (aggregates tags across every notebook's
  pages) instead of filtered by the active notebook
  (`viewModel.selectedNotebook`, `NotebookEntity`, `selectedNotebook` StateFlow).
- Locate the tag aggregation source (a StateFlow/fun in `NoteflowViewModel` that
  collects `pg.tags`), find the notebook↔page relationship (page entity has a
  notebookId or is scoped by parent notebook), and scope it.

## Definition of done
- Tag vault/explorer shows ONLY tags belonging to the currently selected
  notebook (tags on that notebook's pages + that notebook's own tag list).
- Switching notebooks updates the tag vault to that notebook's tags.
- Empty state handled when the notebook has no tags.
- `workspace/phase-164/REPORT.md` documents the previous global source with
  `file:line` and the new scoped query.
- Unit tests: tags from notebook A do not appear in notebook B's vault;
  switching notebooks changes the vault; a page moved to another notebook's
  tags follow it.
- Commit + push.

## Constraints
- Do NOT edit `.github/workflows/`. Do NOT change the security model.
- Keep the data model stable — if the page↔notebook link is implicit, do the
  scoping in the ViewModel/query layer, not by adding a DB schema change
  (that would need USER approval per AGENTS.md).
- Respect existing code style and the single-ViewModel architecture.