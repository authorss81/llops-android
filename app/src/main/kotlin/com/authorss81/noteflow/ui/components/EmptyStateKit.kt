package com.authorss81.noteflow.ui.components

/**
 * Phase 34: pure, JVM-testable decision logic for tactile empty states.
 *
 * Given *what* is empty and *why* (query/first-run), it resolves which vector
 * illustration and which contextual onboarding suggestion to show. No Android
 * dependencies — the entire module is unit-testable on the JVM.
 */

/** The screen/container that is empty. */
enum class EmptyStateKind {
    HOME_GRID,      // the main notes grid (also covers empty global search results)
    TAG_VAULT,      // tag explorer with zero tags
    TRASH,          // trash tab with no trashed pages
    NOTEBOOK_PICKER, // notebook picker sheet
    SECTION_PICKER, // quick-notes & sections picker sheet
    PLUGIN_STORE    // plugin store with no rows
}

/** The vector-art motif to draw (drawn with Compose Canvas, no assets). */
enum class IllustrationKind {
    NOTEBOOK,   // isometric notebook + pen
    GRAPH,      // knowledge-graph nodes
    PEN,        // pen nib
    SEARCH,     // magnifier
    TRASH,      // bin
    STACK,      // stacked notebooks/cards
    PUZZLE      // plugin puzzle piece
}

/** What an empty state should say. */
data class EmptyStateDecision(
    val title: String,
    val suggestion: String,
    val illustration: IllustrationKind,
    val isOnboarding: Boolean = false
)

object EmptyStateResolver {

    /**
     * Resolve the empty state for [kind]. [hasQuery] is true when the empty list
     * is the result of a (global or picker) search filter; [isFirstRun] enables
     * the welcoming "new vault" copy; [query] is the user's filter text (only
     * used to build the "no match" suggestion).
     */
    fun decide(
        kind: EmptyStateKind,
        hasQuery: Boolean = false,
        isFirstRun: Boolean = false,
        query: String = ""
    ): EmptyStateDecision = when (kind) {
        EmptyStateKind.HOME_GRID -> decideHomeGrid(hasQuery, isFirstRun, query)
        EmptyStateKind.TAG_VAULT -> EmptyStateDecision(
            title = "No tags yet",
            suggestion = "Tag your notes and they'll light up here ready to connect into a knowledge graph.",
            illustration = IllustrationKind.GRAPH
        )
        EmptyStateKind.TRASH -> EmptyStateDecision(
            title = "Trash is empty",
            suggestion = "Deleted notes land here for a while — nothing was thrown away yet.",
            illustration = IllustrationKind.TRASH
        )
        EmptyStateKind.NOTEBOOK_PICKER -> decidePicker(
            name = "notebooks",
            action = "Create your first notebook from the ⋮ menu, then pick it here.",
            hasQuery = hasQuery,
            query = query
        )
        EmptyStateKind.SECTION_PICKER -> decidePicker(
            name = "sections",
            action = "Create a quick-notes section from the home screen, then pick it here.",
            hasQuery = hasQuery,
            query = query
        )
        EmptyStateKind.PLUGIN_STORE -> EmptyStateDecision(
            title = "Nothing in the store",
            suggestion = "Bundled plugins ship with the app — Enable them from here. Downloadable plugins appear after you install them.",
            illustration = IllustrationKind.PUZZLE
        )
    }

    private fun decideHomeGrid(
        hasQuery: Boolean,
        isFirstRun: Boolean,
        query: String
    ): EmptyStateDecision {
        if (hasQuery) {
            val term = query.trim().ifEmpty { "your search" }
            return EmptyStateDecision(
                title = "No notes found",
                suggestion = "Nothing matches \"$term\". Try a different word, or clear the search to see everything.",
                illustration = IllustrationKind.SEARCH
            )
        }
        if (isFirstRun) {
            return EmptyStateDecision(
                title = "Welcome to your private vault",
                suggestion = "Create your first note with the + button, or draw with the pen. Everything stays on your device.",
                illustration = IllustrationKind.NOTEBOOK,
                isOnboarding = true
            )
        }
        return EmptyStateDecision(
            title = "Your vault is quiet",
            suggestion = "Link notes with [[wikilinks]] to grow your knowledge graph.",
            illustration = IllustrationKind.GRAPH
        )
    }

    private fun decidePicker(
        name: String,
        action: String,
        hasQuery: Boolean,
        query: String
    ): EmptyStateDecision {
        if (hasQuery) {
            return EmptyStateDecision(
                title = "No $name match",
                suggestion = "Nothing matches \"${query.trim()}\". Try a different term.",
                illustration = IllustrationKind.SEARCH
            )
        }
        return EmptyStateDecision(
            title = "No $name yet",
            suggestion = action,
            illustration = IllustrationKind.STACK
        )
    }
}