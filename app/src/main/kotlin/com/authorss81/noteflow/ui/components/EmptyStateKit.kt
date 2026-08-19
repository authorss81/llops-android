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
    HOME_GRID,       // the main notes grid (also covers empty global search results)
    RECENT,          // the Recent tab with nothing opened yet
    TAG_VAULT,       // tag explorer with zero tags
    TRASH,           // trash tab with no trashed pages
    KNOWLEDGE_GRAPH, // knowledge graph with zero nodes/edges
    VERSION_HISTORY, // version-history sheet with no revision snapshots
    WEB_SEARCH,      // web-search results with zero matches
    NOTEBOOK_PICKER, // notebook picker sheet
    SECTION_PICKER,  // quick-notes & sections picker sheet
    PLUGIN_STORE     // plugin store with no rows (incl. a filter with no match)
}

/** The vector-art motif to draw (drawn with Compose Canvas, no assets). */
enum class IllustrationKind {
    NOTEBOOK,   // isometric notebook + pen
    GRAPH,      // knowledge-graph nodes
    PEN,        // pen nib
    SEARCH,     // magnifier
    TRASH,      // bin
    STACK,      // stacked notebooks/cards
    PUZZLE,     // plugin puzzle piece
    HISTORY     // clock with a circular restore arrow
}

/**
 * What an empty state should say. [actionLabel] is the single primary CTA label
 * (or `null` when the honest state is purely informational); the caller wires
 * the label to the click that opens the right screen.
 */
data class EmptyStateDecision(
    val title: String,
    val suggestion: String,
    val illustration: IllustrationKind,
    val isOnboarding: Boolean = false,
    val actionLabel: String? = null
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
        EmptyStateKind.RECENT -> EmptyStateDecision(
            title = "No recently viewed notes yet",
            suggestion = "Notes you open land here so you can jump straight back. Create your first note and it'll show up.",
            illustration = IllustrationKind.STACK,
            actionLabel = "Create a note"
        )
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
        EmptyStateKind.KNOWLEDGE_GRAPH -> EmptyStateDecision(
            title = "No knowledge graph yet",
            suggestion = "Create a wikilink to start mapping — write [[Another note]] inside any note and your graph lights up as ideas connect.",
            illustration = IllustrationKind.GRAPH,
            actionLabel = "Create a note"
        )
        EmptyStateKind.VERSION_HISTORY -> EmptyStateDecision(
            title = "No revision snapshots yet",
            suggestion = "Every save records a snapshot here so you can restore any earlier version.",
            illustration = IllustrationKind.HISTORY
        )
        EmptyStateKind.WEB_SEARCH -> decideWebSearch(query)
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
        EmptyStateKind.PLUGIN_STORE -> decidePluginStore(hasQuery, query)
    }

    private fun decideWebSearch(query: String): EmptyStateDecision {
        val term = query.trim().ifEmpty { "your search" }
        return EmptyStateDecision(
            title = "No results found",
            suggestion = "Nothing matched \"$term\". Try different words, or search a direct address.",
            illustration = IllustrationKind.SEARCH,
            actionLabel = "New search"
        )
    }

    private fun decidePluginStore(hasQuery: Boolean, query: String): EmptyStateDecision {
        if (hasQuery) {
            val term = query.trim().ifEmpty { "your filter" }
            return EmptyStateDecision(
                title = "No plugin matches",
                suggestion = "Nothing matches \"$term\". Try a different name.",
                illustration = IllustrationKind.SEARCH,
                actionLabel = "Clear filter"
            )
        }
        return EmptyStateDecision(
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
                illustration = IllustrationKind.SEARCH,
                actionLabel = "Clear search"
            )
        }
        if (isFirstRun) {
            return EmptyStateDecision(
                title = "Welcome to your private vault",
                suggestion = "Create your first note with the + button, or draw with the pen. Everything stays on your device.",
                illustration = IllustrationKind.NOTEBOOK,
                isOnboarding = true,
                actionLabel = "Create your first note"
            )
        }
        return EmptyStateDecision(
            title = "Your vault is quiet",
            suggestion = "Link notes with [[wikilinks]] to grow your knowledge graph.",
            illustration = IllustrationKind.GRAPH,
            actionLabel = "Create a note"
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