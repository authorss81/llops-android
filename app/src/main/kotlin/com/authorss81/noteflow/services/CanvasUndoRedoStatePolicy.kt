package com.authorss81.noteflow.services

/**
 * Canvas undo/redo toolbar-state policy (phase-194).
 *
 * Pure-JVM decision table for the enabled/dimmed appearance of the Undo/Redo
 * buttons on the canvas ink bar. The toolbar renders a button disabled
 * (non-actionable, dimmed to [DISABLED_ALPHA]) when its stack holds no
 * snapshot, and bright (full alpha, enabled) when one is available.
 *
 * The DATA logic itself (stack contents, 30-cap drop-oldest, redo-clear on new
 * stroke) lives in `EditorScreen.kt` (`handleStrokesChange` / `handleUndo` /
 * `handleRedo`) and is NOT duplicated here. The transition helpers
 * ([afterUndo], [afterRedo], [afterNewStroke]) are an ORACLE that mirrors those
 * stack-size transitions so the reactive-dim regression tests can drive the
 * state machine in pure JVM without instantiating a composable.
 */
object CanvasUndoRedoStatePolicy {

    /** Material 3's standard disabled-content alpha. */
    const val DISABLED_ALPHA: Float = 0.38f

    /** Undo is actionable while the undo stack holds at least one snapshot. */
    fun canUndo(undoStackSize: Int): Boolean = undoStackSize > 0

    /** Redo is actionable while the redo stack holds at least one snapshot. */
    fun canRedo(redoStackSize: Int): Boolean = redoStackSize > 0

    /**
     * Icon content alpha for a button: full opacity when actionable, else
     * [DISABLED_ALPHA]. Applied to the icon tint (the base `onSurfaceVariant`
     * stays unchanged — the alpha carries the dim).
     */
    fun iconAlpha(actionable: Boolean): Float = if (actionable) 1f else DISABLED_ALPHA

    /**
     * Oracle mirror of `EditorScreen.handleUndo`: one snapshot pops off the
     * undo stack and the pre-undo canvas state is pushed onto the redo stack.
     * Returns `(undoStackSize, redoStackSize)`.
     */
    fun afterUndo(undoStackSize: Int, redoStackSize: Int): Pair<Int, Int> =
        if (undoStackSize > 0) (undoStackSize - 1) to (redoStackSize + 1) else undoStackSize to redoStackSize

    /**
     * Oracle mirror of `EditorScreen.handleRedo`: one snapshot pops off the
     * redo stack and the pre-redo canvas state is pushed onto the undo stack.
     * Returns `(undoStackSize, redoStackSize)`.
     */
    fun afterRedo(undoStackSize: Int, redoStackSize: Int): Pair<Int, Int> =
        if (redoStackSize > 0) (undoStackSize + 1) to (redoStackSize - 1) else undoStackSize to redoStackSize

    /**
     * Oracle mirror of `EditorScreen.handleStrokesChange`: a new stroke batch
     * pushes one snapshot onto the undo stack and clears the redo stack.
     * Returns `(undoStackSize, redoStackSize)`.
     */
    fun afterNewStroke(undoStackSize: Int): Pair<Int, Int> = undoStackSize + 1 to 0
}