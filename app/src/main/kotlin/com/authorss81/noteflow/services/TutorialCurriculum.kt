package com.authorss81.noteflow.services

/**
 * Phase 125 — enhanced interactive tutorial.
 *
 * The whole tutorial is modelled here as PURE data + a PURE state machine so the
 * slide-advance / progress-check / skip / resume / completion semantics are
 * unit-testable on the JVM (no Compose, no Android). The Compose layer in
 * `ui/components/InteractiveTutorial.kt` is a thin renderer over this model.
 *
 * Content honesty rule (AGENTS.md): every description only claims a feature that
 * actually exists in this codebase (verified against docs/ARCHITECTURE.md anchors).
 * Interactive action slides map to embedded demos the user actually performs.
 */
enum class TutorialSection(val displayName: String) {
    START("Getting Started"),
    MARKDOWN("Notes & Markdown"),
    CANVAS("Canvas & Brushes"),
    LAYERS("Layers"),
    COLOURS("Colours"),
    ERASERS("Erasers"),
    GRAPH("Knowledge Graph"),
    PLUGINS("Plugins"),
    BACKUP("Backup & Sync"),
    SECURITY("Security")
}

/** A real action the user must perform before the slide can advance. */
sealed class TutorialAction {
    /** Drag on the practice pad to draw a stroke. */
    object DrawStroke : TutorialAction()

    /** Draw then drag over the pad with the eraser. */
    object EraseStroke : TutorialAction()

    /** Tap "+ Add Layer" in the embedded layer demo. */
    object AddLayer : TutorialAction()

    /** Pick a brush colour mode (rainbow/gradient/shimmer) or a swatch. */
    object PickColourMode : TutorialAction()

    /** Type a markdown heading in the mini editor. */
    object TypeMarkdown : TutorialAction()

    val label: String
        get() = when (this) {
            DrawStroke -> "Draw a stroke"
            EraseStroke -> "Erase part of a stroke"
            AddLayer -> "Add a layer"
            PickColourMode -> "Pick a colour mode"
            TypeMarkdown -> "Type a markdown heading"
        }

    companion object {
        val all: List<TutorialAction> =
            listOf(DrawStroke, EraseStroke, AddLayer, PickColourMode, TypeMarkdown)
    }
}

data class TutorialSlide(
    val id: String,
    val section: TutorialSection,
    val title: String,
    val description: String,
    val iconKey: String,
    /** Non-null ⇒ the user must perform this action before "Next" unlocks. */
    val action: TutorialAction? = null,
    /** Short footer hint shown on action slides (or any slide). */
    val tip: String? = null
)

object TutorialCurriculum {

    private fun slide(
        id: String,
        section: TutorialSection,
        iconKey: String,
        title: String,
        description: String,
        action: TutorialAction? = null,
        tip: String? = null
    ) = TutorialSlide(id, section, title, description, iconKey, action, tip)

    /**
     * The full curriculum. Content is honest — each description maps to a real,
     * wired feature (see docs/ARCHITECTURE.md anchors). Some entries carry real
     * limits explicitly (e.g. AGSL wet mixing needs Android 13+, biometric
     * unlock needs a strong-bound key, search covers the recent-pages window)
     * so the tutorial never oversells the app the way the old copy did.
     */
    val slides: List<TutorialSlide> = listOf(
        // ---------------- Getting Started ----------------
        slide(
            "start_welcome", TutorialSection.START, "home",
            "Welcome to InkFlow",
            "A private notes and drawing app that works entirely offline. " +
                "Everything lives in an encrypted vault on your device — there is no account, " +
                "no cloud, and nothing leaves your phone unless you explicitly export or sync it."
        ),
        slide(
            "start_notebooks", TutorialSection.START, "folder",
            "Notebooks & Sections",
            "Organize your notes with notebooks and sections. The home screen shows your " +
                "notebooks on the left, sections in the middle, and pages across the main panel."
        ),
        slide(
            "start_layout", TutorialSection.START, "sidebars",
            "Adaptive Layout",
            "On wide screens (tablets / landscape) the app expands into a multi-pane layout " +
                "with a sidebar that keeps notebooks, sections and pages in view. On phones it " +
                "collapses into a compact single-pane layout. You can toggle this from the menu."
        ),
        slide(
            "start_views", TutorialSection.START, "views",
            "Browse Pages Your Way",
            "The same notes can be viewed as a list, a gallery of page thumbnails, a kanban " +
                "board, a calendar, or a spreadsheet — switch view modes from the home screen.",
            tip = "Try the view-mode switcher in the page area."
        ),
        slide(
            "start_search", TutorialSection.START, "search",
            "Search & Command Palette",
            "Search finds text inside your recent notes. Swipe down with two fingers anywhere to " +
                "open the command palette — a quick switcher that ranks notes and runs plugin actions.",
            tip = "On large vaults a notice marks that search covers the most recent pages."
        ),

        // ---------------- Notes & Markdown ----------------
        slide(
            "markdown_intro", TutorialSection.MARKDOWN, "notes",
            "Plain Notes & Markdown",
            "Open any page and type plain text, or use Markdown: # headings, **bold** and *italic* " +
                "are all supported and render as you type."
        ),
        slide(
            "markdown_type", TutorialSection.MARKDOWN, "type",
            "Try Markdown",
            "Type a heading below — for example # My First Note. The preview updates as you type.",
            action = TutorialAction.TypeMarkdown,
            tip = "Start with a '#' to make a heading."
        ),
        slide(
            "markdown_live", TutorialSection.MARKDOWN, "markdown",
            "Live Editing & Split View",
            "The markdown editor gives you a live-rendered view with editable blocks — toggle " +
                "between edit, view and split panes so you can write and see the result at the same time."
        ),
        slide(
            "markdown_wikilinks", TutorialSection.MARKDOWN, "link",
            "Wikilinks",
            "Link two notes by typing double square brackets: [[Note Title]]. Then jump between " +
                "linked notes from the preview, the backlinks panel, or the knowledge graph.",
            tip = "Try [[Daily Notes]] in the editor."
        ),
        slide(
            "markdown_tags", TutorialSection.MARKDOWN, "tags",
            "Tags",
            "Add tags with #tag inside the editor text or via the tag editor. Tags power the tag " +
                "explorer, filtering and the knowledge graph."
        ),
        slide(
            "markdown_daily", TutorialSection.MARKDOWN, "today",
            "Daily Notes & Journals",
            "Open today's page from the menu to keep a journal. Daily notes are stored like any " +
                "other page — encrypted on your device."
        ),
        slide(
            "markdown_voice", TutorialSection.MARKDOWN, "mic",
            "Time-Synced Voice Notes",
            "Record audio and attach it to a page — lectures, meetings, ideas. Playback is " +
                "controlled with a waveform scrubber right on the page. Voice files are encrypted " +
                "at rest, just like everything else."
        ),

        // ---------------- Canvas & Brushes ----------------
        slide(
            "canvas_intro", TutorialSection.CANVAS, "canvas",
            "The Infinite Canvas",
            "Every page is a canvas. Choose a continuous infinite sheet for free-form thinking, or a " +
                "single-page sheet for linear notes. Pinch to zoom from 0.5x to 4x and pan freely."
        ),
        slide(
            "canvas_tools", TutorialSection.CANVAS, "brush",
            "21 Brushes",
            "InkFlow ships a full brush suite: pens, fineliners, markers, calligraphy, fountain pen, " +
                "airbrush, spray, pencil, charcoal, oil paint and more — each with its own feel. " +
                "Pick a tool, a width and a colour, and draw."
        ),
        slide(
            "canvas_draw", TutorialSection.CANVAS, "draw",
            "Draw a Stroke",
            "This is a practice pad, not a real page. Drag your finger — or stylus — across the pad " +
                "to draw. Pressure and tilt are felt in the real editor.",
            action = TutorialAction.DrawStroke,
            tip = "Drag slowly to see the brush follow your movement."
        ),
        slide(
            "canvas_wet", TutorialSection.CANVAS, "water",
            "Wet Brushes",
            "Watercolour and oil brushes mix wet-on-wet with real-time GPU blending on devices that " +
                "support AGSL (Android 13+). On older devices the same brushes fall back to textured " +
                "rendering, so every device keeps working.",
            tip = "Watch for the droplet icon in the brush toolbar."
        ),
        slide(
            "canvas_pressure", TutorialSection.CANVAS, "pressure",
            "Pressure & Tilt",
            "Drawing with a stylus gives you pressure-sensitive width and tilt; pressing harder " +
                "marks the page more heavily. Fingers work too — the stabilizer smooths wobbly paths."
        ),
        slide(
            "canvas_shapes", TutorialSection.CANVAS, "shapes",
            "Shape Auto-Snap",
            "Draw a freehand rectangle, line or arrow and, when shape snap is enabled, the canvas " +
                "snaps it into a clean geometric shape automatically.",
            tip = "Toggle this in the canvas settings if you prefer raw strokes."
        ),
        slide(
            "canvas_paper", TutorialSection.CANVAS, "paper",
            "Paper & Templates",
            "Choose paper colour and texture, or start from a template — blank, ruled, Cornell and " +
                "more. Templates guide notes and assignments directly on the canvas."
        ),

        // ---------------- Layers ----------------
        slide(
            "layers_intro", TutorialSection.LAYERS, "layers",
            "Layers Keep Things Organised",
            "Put sketches, text and annotations on separate layers. Layers are cached and " +
                "composited on the GPU, so deep stacks stay smooth when you pan and zoom."
        ),
        slide(
            "layers_demo", TutorialSection.LAYERS, "layers",
            "Try Layers",
            "The layer panel works like a real editor: tap + Add Layer to stack a new layer of ink.",
            action = TutorialAction.AddLayer,
            tip = "New layers stack on top of the existing ones."
        ),
        slide(
            "layers_manage", TutorialSection.LAYERS, "eye",
            "Manage Layers",
            "In the editor you can rename, reorder, hide and lock layers. Locked layers can't be drawn " +
                "on by accident — handy for finished backgrounds."
        ),

        // ---------------- Colours ----------------
        slide(
            "colour_intro", TutorialSection.COLOURS, "palette",
            "Colours & Palettes",
            "Pick from curated palettes or build your own with the palette studio. The eyedropper " +
                "samples any colour straight from the canvas."
        ),
        slide(
            "colour_modes", TutorialSection.COLOURS, "rainbow",
            "Solid, Rainbow, Gradient, Shimmer",
            "Beyond plain colours, brushes support colour modes: a rainbow sweep along each stroke, " +
                "a gradient between two colours, or a shimmering sheen. The mode persists across sessions."
        ),
        slide(
            "colour_hsv", TutorialSection.COLOURS, "picker",
            "Precise Colour Picking",
            "Fine-tune H/S/V sliders for an exact colour, save it to the palette, and it applies to " +
                "the very next stroke — no pen-switch dance needed."
        ),
        slide(
            "colour_demo", TutorialSection.COLOURS, "modes",
            "Try a Colour Mode",
            "Tap a mode chip — Rainbow is fun — or a colour swatch below. The preview swatch updates " +
                "so you can see the effect.",
            action = TutorialAction.PickColourMode,
            tip = "Rainbow sweeps the full spectrum along a stroke."
        ),
        slide(
            "colour_complement", TutorialSection.COLOURS, "colour",
            "Complementary Colours",
            "The colour picker offers complementary-harmony suggestions so your palette stays " +
                "cohesive, with a contrast row for readability."
        ),

        // ---------------- Erasers ----------------
        slide(
            "eraser_types", TutorialSection.ERASERS, "eraser",
            "Two Erasers",
            "There are two eraser styles. Whole-stroke removes an entire stroke in one tap. Partial " +
                "erase cuts smooth, round holes through strokes so you can fix details without " +
                "destroying the rest of a drawing."
        ),
        slide(
            "eraser_pressure", TutorialSection.ERASERS, "pressure",
            "Pressure-Aware Erasing",
            "The partial eraser follows your pressure: press harder and the erased swath widens, " +
                "just like a real eraser. A live round cursor previews the cut before you touch down."
        ),
        slide(
            "eraser_demo", TutorialSection.ERASERS, "erase",
            "Try Erasing",
            "Draw something first, switch to the erase chip, then drag across your strokes to erase " +
                "part of them.",
            action = TutorialAction.EraseStroke,
            tip = "Draw at least one stroke, then swipe over it to erase."
        ),

        // ---------------- Knowledge Graph ----------------
        slide(
            "graph_intro", TutorialSection.GRAPH, "graph",
            "Your Notes, Connected",
            "The knowledge graph visualises every note as a node — wikilinks and tags become edges. " +
                "Clusters share colours and you can filter by tag from the top of the graph."
        ),
        slide(
            "graph_backlinks", TutorialSection.GRAPH, "backlinks",
            "Backlinks & Unlinked Mentions",
            "The backlinks panel shows which notes point at the current page, and surfaces notes that " +
                "mention it by title but aren't linked yet — so you can connect them with one tap."
        ),

        // ---------------- Plugins ----------------
        slide(
            "plugin_off", TutorialSection.PLUGINS, "plugin",
            "Plugins Are Off by Default",
            "InkFlow has a plugin system for optional capabilities. Plugins are opt-in: each one " +
                "stays disabled until you enable it in Settings > Plugins, and core note-taking never " +
                "depends on them.",
            tip = "Enable only what you actually use."
        ),
        slide(
            "plugin_store", TutorialSection.PLUGINS, "store",
            "The Plugin Store",
            "Some plugins are bundled; optional ones are installed from the Plugin Store. Installed " +
                "plugins are signature-verified and pinned to trusted releases before anything runs."
        ),
        slide(
            "plugin_tools", TutorialSection.PLUGINS, "capabilities",
            "Offline Capability Tools",
            "Typical capabilities: on-device OCR, keyless web search, translation, ink-to-shape, " +
                "citation and unit conversion — most work fully offline with no API key and no " +
                "network permission."
        ),

        // ---------------- Backup & Sync ----------------
        slide(
            "backup_encrypted", TutorialSection.BACKUP, "backup",
            "Encrypted Backups",
            "Create an encrypted backup containing your whole vault. Password-protected backups use " +
                "a strong password policy and a two-part key design, so the archive is only openable " +
                "with the password you chose.",
            tip = "Remember the backup password — without it the archive can't be restored."
        ),
        slide(
            "backup_webdav", TutorialSection.BACKUP, "webdav",
            "WebDAV Sync",
            "Sync encrypted backup archives to your own WebDAV server (Nextcloud, ownCloud…) over " +
                "HTTPS. Cleartext HTTP is only allowed for explicit on-LAN opt-in hosts.",
            tip = "Sync happens only when you ask it to."
        ),
        slide(
            "backup_localsend", TutorialSection.BACKUP, "nearby",
            "Nearby Transfer with LocalSend",
            "Send a note or a backup to a nearby device with LocalSend. Transfers are local-network " +
                "only and require an explicit pairing check; the receiver confirms before any bytes move."
        ),

        // ---------------- Security ----------------
        slide(
            "security_crypto", TutorialSection.SECURITY, "crypto",
            "Real Encryption",
            "Your vault is encrypted with AES-256-GCM. The key is derived from your master password " +
                "with PBKDF2 (600,000 iterations) and wrapped on-device; the database carries an HMAC " +
                "integrity checksum so tampering is detected.",
            tip = "Keys are zeroized from memory the moment you lock."
        ),
        slide(
            "security_vault", TutorialSection.SECURITY, "vault",
            "The Locked Vault",
            "Set a master password to lock your vault. Unlock with that password — or, on devices with " +
                "a strong-bound biometric key (Android 11+), with face or fingerprint. The vault " +
                "auto-locks after inactivity and locks when the app leaves the screen.",
            tip = "A strong passphrase is the single biggest protection."
        ),
        slide(
            "security_lockout", TutorialSection.SECURITY, "lockout",
            "Brute-Force Protection",
            "After 5 failed attempts the vault enters an exponential cooldown that you can't skip. " +
                "The lock is enforced at the data layer — locked means no keyed database connection exists.",
            tip = "No password? Tap 'Restore from backup' in the recovery screen."
        ),
        slide(
            "security_recovery", TutorialSection.SECURITY, "recovery",
            "Recovery Built In",
            "If the vault is ever detected as corrupt or unreadable, InkFlow never deletes your data: " +
                "it quarantines the bytes, pauses the vault and offers restore-from-backup or an " +
                "explicit re-key — not a silent wipe.",
            tip = "Keep a recent backup and you can always recover."
        ),
        slide(
            "final_ready", TutorialSection.SECURITY, "done",
            "You're Ready",
            "Create notebooks, take notes, draw, link, sync and back up — all on your own device. " +
                "Use the ⋮ menu on the home screen anytime to reopen this tutorial or explore Settings."
        )
    )

    val sectionCount: Int get() = TutorialSection.values().size

    val sectionSlideCounts: Map<TutorialSection, Int>
        get() = TutorialSection.values().associateWith { section -> slides.count { it.section == section } }

    /** Slides that require a real user action (the interactive demos). */
    val actionSlides: List<TutorialSlide>
        get() = slides.filter { it.action != null }

    /** Unique slide ids. */
    val ids: Set<String> get() = slides.map { it.id }.toSet()

    /** Curriculum sanity guard used by tests + the UI (ids unique, sections ordered per enum). */
    private val orderedSections: List<TutorialSection> = TutorialSection.values().toList()
    val isWellFormed: Boolean
        get() {
            if (slides.isEmpty()) return false
            if (ids.size != slides.size) return false
            // Section order must follow the enum declaration (monotonically non-decreasing).
            var lastOrdinal = -1
            for (s in slides) {
                val ordinal = s.section.ordinal
                if (ordinal < lastOrdinal) return false
                lastOrdinal = ordinal
            }
            return slides.all { it.title.isNotBlank() && it.description.isNotBlank() && it.iconKey.isNotBlank() }
        }
}

/**
 * Pure state machine for a tutorial run. One instance per dialog appearance.
 *
 * - [index] tracks the current slide.
 * - action slides (a slide with a non-null [TutorialSlide.action]) cannot advance
 *   until [recordAction] marks that slide's action done — the progress check.
 * - [advance] honours the gate; [forceAdvance] bypasses it (the "skip this step"
 *   escape hatch so nobody is trapped on a demo they can't perform).
 * - persistence = survive across dialog opens purely from [initialIndex]
 *   (the resume point saved by the caller).
 */
class TutorialSession(
    val slides: List<TutorialSlide>,
    initialIndex: Int = 0
) {
    private val actionDones = BooleanArray(slides.size)

    /** Clamped to the valid index range; a resume point never throws. */
    var index: Int = initialIndex.coerceIn(0, (slides.size - 1).coerceAtLeast(0))
        private set

    val total: Int get() = slides.size
    val current: TutorialSlide? get() = slides.getOrNull(index)
    val section: TutorialSection? get() = current?.section

    val isFirst: Boolean get() = index == 0
    val isLast: Boolean get() = index >= slides.size - 1

    fun indexOf(id: String): Int = slides.indexOfFirst { it.id == id }

    /** Marks a slide's action complete (the progress-check trigger). */
    fun recordAction(slideId: String): Boolean {
        val i = indexOf(slideId)
        if (i < 0) return false
        if (actionDones[i]) return false
        actionDones[i] = true
        return true
    }

    fun isActionDone(slideId: String): Boolean {
        val i = indexOf(slideId)
        return i >= 0 && actionDones[i]
    }

    /** True when the current slide has no action, or its action is done. */
    val actionComplete: Boolean
        get() = current?.let { it.action == null || isActionDone(it.id) } == true

    /** "Next" is enabled exactly when the progress check passes. */
    val canAdvance: Boolean get() = actionComplete

    /** Advances one slide; refuses while the current action is not done. */
    fun advance(): Boolean {
        if (!actionComplete) return false
        if (index >= slides.size - 1 || slides.isEmpty()) return false
        index++
        return true
    }

    /** Advances even when the current action is pending — the "skip this step" bypass. */
    fun forceAdvance(): Boolean {
        if (index >= slides.size - 1 || slides.isEmpty()) return false
        index++
        return true
    }

    fun back(): Boolean {
        if (index <= 0) return false
        index--
        return true
    }

    /** Straight-line completion progress 0..100 across the whole deck. */
    val progressPercent: Int
        get() = if (slides.size <= 1) 100
        else (index * 100) / (slides.size - 1)

    val completedSlideCount: Int get() = index + 1

    /** 1-based slide number within the current section (nice "3 of 5" chips). */
    val slideNumberInSection: Int
        get() {
            val sec = section ?: return (index + 1)
            val first = slides.indexOfFirst { it.section == sec }.coerceAtLeast(0)
            return index - first + 1
        }

    val slidesInSection: Int
        get() {
            val sec = section ?: return 0
            return slides.count { it.section == sec }
        }

    val sectionOrdinal: Int
        get() = section?.ordinal ?: 0
}