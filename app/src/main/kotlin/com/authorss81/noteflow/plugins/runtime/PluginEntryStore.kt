package com.authorss81.noteflow.plugins.runtime

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginPermission
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * Persistence seam for the unified plugin catalog entries (Phase 22).
 *
 * The [PluginEntry] set is the single catalog the store (Phase 21), the runtime
 * (Phase 23) and the update model (Phase 24) read. Two classes of entries flow
 * through it:
 *
 * - **Bundled** entries (built-in compile-time plugins) are derived from the
 *   compile-time registry — [PluginStoreCatalog] builds them fresh each time;
 *   they are never persisted here.
 * - **Remote** (downloadable) entries are fetched from the Phase-24 hosted
 *   manifest and PERSISTED here (over `SettingsManager` in production), so a
 *   downloaded-and-verified plugin survives process restarts with its
 *   downloadUrl / sha256 / pinnedCertHash / updateChannel intact.
 *
 * The store itself is pure JVM (the codec + in-memory impl are unit-tested);
 * the production adapter delegates to
 * [com.authorss81.noteflow.services.SettingsManager].
 */
interface PluginEntryStore {
    /** Upsert [entry] (persisted for remote entries; ignored-as-derivable for bundled). */
    fun save(entry: PluginEntry)

    /** The persisted entry for [pluginId], or null. */
    fun find(pluginId: String): PluginEntry?

    /** Every persisted entry (typically the remote/downloadable set). */
    fun all(): List<PluginEntry>

    /** Remove a persisted entry (e.g. on store Delete). */
    fun remove(pluginId: String)
}

/**
 * In-memory [PluginEntryStore] for JVM tests and as the base of composition
 * tests. Mirrors the production adapter's semantics exactly.
 */
class InMemoryPluginEntryStore : PluginEntryStore {
    private val entries = mutableMapOf<String, PluginEntry>()

    override fun save(entry: PluginEntry) {
        entries[entry.id] = entry
    }

    override fun find(pluginId: String): PluginEntry? = entries[pluginId]

    override fun all(): List<PluginEntry> = entries.values.toList()

    override fun remove(pluginId: String) {
        entries.remove(pluginId)
    }

    /** Test helper: replace the whole persisted set. */
    fun replaceAll(newEntries: Collection<PluginEntry>) {
        entries.clear()
        newEntries.forEach { entries[it.id] = it }
    }
}

/**
 * Stable JSON codec for [PluginEntry] (Phase 22).
 *
 * The persisted representation is a plain DTO that stores capability/permission
 * **keys** (not objects), so the wire format is stable across app builds and
 * survives future enum/object reshuffles. Unknown keys are dropped on decode
 * (forward compatible). Pure JVM + Gson (already a base-app dependency).
 */
class PluginEntryCodec {

    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    /** Encode [entry] into a stable JSON string. */
    fun encode(entry: PluginEntry): String = gson.toJson(toDto(entry))

    /**
     * Decode a JSON string back into a [PluginEntry]. Returns null when the
     * blob is malformed, the version/source cannot be resolved, or the decoded
     * entry violates its own invariants (never throws).
     */
    fun decode(json: String): PluginEntry? = try {
        val dto = gson.fromJson(json, PluginEntryDto::class.java) ?: return null
        fromDto(dto)?.takeIf { it.isValid() }
    } catch (_: Exception) {
        null
    }

    internal data class PluginEntryDto(
        val id: String,
        val name: String,
        val description: String,
        val version: String,
        val capabilityKeys: List<String>,
        val category: String,
        val permissionKeys: List<String>,
        val downloadUrl: String?,
        val installSizeBytes: Long?,
        val updateChannel: String,
        val sha256: String?,
        val pinnedCertHash: String?,
        val source: String
    )

    internal fun toDto(entry: PluginEntry): PluginEntryDto = PluginEntryDto(
        id = entry.id,
        name = entry.name,
        description = entry.description,
        version = entry.version.toString(),
        capabilityKeys = entry.capabilities.map { it.key }.sorted(),
        category = entry.category,
        permissionKeys = entry.permissions.map { it.key }.sorted(),
        downloadUrl = entry.downloadUrl,
        installSizeBytes = entry.installSizeBytes,
        updateChannel = entry.updateChannel,
        sha256 = entry.sha256,
        pinnedCertHash = entry.pinnedCertHash,
        source = entry.source.name
    )

    internal fun fromDto(dto: PluginEntryDto): PluginEntry? {
        val version = PluginVersion.parse(dto.version) ?: return null
        val source = PluginEntrySource.entries.firstOrNull { it.name == dto.source } ?: return null
        return PluginEntry(
            id = dto.id,
            name = dto.name,
            description = dto.description,
            version = version,
            capabilities = dto.capabilityKeys.mapNotNull { PluginCapability.byKey(it) }.toSet(),
            category = dto.category,
            permissions = dto.permissionKeys.mapNotNull { PluginPermission.byKey(it) }.toSet(),
            downloadUrl = dto.downloadUrl,
            installSizeBytes = dto.installSizeBytes,
            updateChannel = dto.updateChannel,
            sha256 = dto.sha256,
            pinnedCertHash = dto.pinnedCertHash,
            source = source
        )
    }
}
