package com.authorss81.noteflow.plugins.texttools

import com.authorss81.noteflow.plugins.DiffHunk
import com.authorss81.noteflow.plugins.DiffOp

/**
 * A simple, pure line-diff between two note texts (Phase 15, `TextTools`).
 *
 * Uses a longest-common-subsequence DP over the two texts split by `\n`, then
 * groups consecutive same-op lines into [DiffHunk]s with a new-file line number
 * and a short excerpt. Identical texts yield an empty list. No Android or
 * third-party code — fully unit-testable.
 */
object TextNoteDiff {

    private const val EXCERPT_LIMIT = 80

    fun diff(oldText: String, newText: String): List<DiffHunk> {
        val old = oldText.split("\n")
        val new = newText.split("\n")
        val ops = computeOps(old, new)
        return groupHunks(ops)
    }

    private fun computeOps(old: List<String>, new: List<String>): List<Triple<DiffOp, Int, String>> {
        val n = old.size
        val m = new.size
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i][j] = if (old[i] == new[j]) lcs[i + 1][j + 1] + 1
                else maxOf(lcs[i + 1][j], lcs[i][j + 1])
            }
        }

        val ops = mutableListOf<Triple<DiffOp, Int, String>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                old[i] == new[j] -> {
                    ops += Triple(DiffOp.UNCHANGED, j, old[i])
                    i++; j++
                }
                lcs[i + 1][j] >= lcs[i][j + 1] -> {
                    ops += Triple(DiffOp.REMOVED, j, old[i])
                    i++
                }
                else -> {
                    ops += Triple(DiffOp.ADDED, j, new[j])
                    j++
                }
            }
        }
        while (i < n) { ops += Triple(DiffOp.REMOVED, j, old[i]); i++ }
        while (j < m) { ops += Triple(DiffOp.ADDED, j, new[j]); j++ }
        return ops
    }

    private fun groupHunks(ops: List<Triple<DiffOp, Int, String>>): List<DiffHunk> {
        if (ops.isEmpty()) return emptyList()
        val hunks = mutableListOf<DiffHunk>()
        var start = 0
        for (idx in 1..ops.size) {
            val changed = idx == ops.size ||
                ops[idx].first != ops[start].first ||
                ops[idx].second != ops[idx - 1].second + 1 // keep contiguous line numbers
            if (changed) {
                val slice = ops.subList(start, idx)
                if (slice.first().first != DiffOp.UNCHANGED) {
                    hunks += DiffHunk(
                        op = slice.first().first,
                        startLine = slice.first().second + 1,
                        lineCount = slice.size,
                        excerpt = slice.joinToString(" ") { it.third }
                            .replace(Regex("\\s+"), " ")
                            .trim().take(EXCERPT_LIMIT)
                    )
                }
                start = idx
            }
        }
        return hunks
    }
}