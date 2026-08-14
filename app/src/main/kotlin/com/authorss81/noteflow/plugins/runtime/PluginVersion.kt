package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.SemanticVersion

/**
 * The catalog/semver type used by the hybrid plugin architecture (Phase 22).
 *
 * The compile-time framework already has [SemanticVersion] for manifest
 * versions; [PluginVersion] is the version type carried by the unified
 * [PluginEntry] seam, adding strict parsing, ordering and **bumping** so the
 * Phase-24 update model (discover → compare → bump → re-verify) has a single,
 * tested vocabulary. It follows the exact same strict rule as
 * [SemanticVersion]: exactly three non-negative integer components, otherwise
 * unparseable.
 */
data class PluginVersion(
    val major: Int,
    val minor: Int,
    val patch: Int
) : Comparable<PluginVersion> {

    override fun compareTo(other: PluginVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    /** True when this version is strictly newer than [other]. */
    fun isNewerThan(other: PluginVersion): Boolean = this > other

    /**
     * Produce the next version for [kind]: PATCH bumps patch, MINOR bumps minor
     * and resets patch, MAJOR bumps major and resets both. Used by the
     * Phase-24 update flow to derive expected new versions from a manifest and
     * to sanity-check that an offered update is actually newer.
     */
    fun bump(kind: BumpKind): PluginVersion = when (kind) {
        BumpKind.MAJOR -> PluginVersion(major + 1, 0, 0)
        BumpKind.MINOR -> PluginVersion(major, minor + 1, 0)
        BumpKind.PATCH -> PluginVersion(major, minor, patch + 1)
    }

    /** Bridge to the framework's manifest version type. */
    fun toSemanticVersion(): SemanticVersion = SemanticVersion(major, minor, patch)

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        val ZERO = PluginVersion(0, 0, 0)

        /** Parse `"Major.Minor.Patch"`. Returns null when not strictly valid. */
        fun parse(value: String): PluginVersion? {
            val parts = value.trim().split('.')
            if (parts.size != 3) return null
            val nums = parts.map { it.toIntOrNull() ?: return null }
            if (nums.any { it < 0 }) return null
            return PluginVersion(nums[0], nums[1], nums[2])
        }

        /** Adopt a framework [SemanticVersion] unchanged. */
        fun from(semver: SemanticVersion): PluginVersion =
            PluginVersion(semver.major, semver.minor, semver.patch)
    }
}

/**
 * The part of the version an update bumps. Only ever used to DERIVE expected
 * versions — actual shipped versions come from the plugin manifest, never from
 * a guess.
 */
enum class BumpKind { MAJOR, MINOR, PATCH }
