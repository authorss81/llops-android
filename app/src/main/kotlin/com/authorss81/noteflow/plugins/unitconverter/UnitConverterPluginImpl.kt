package com.authorss81.noteflow.plugins.unitconverter

import android.content.Context
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.UnitConversionOutcome
import com.authorss81.noteflow.plugins.UnitConverterPlugin

/**
 * The Unit Converter plugin (Phase 26) — serves [PluginCapability.UnitConversion].
 *
 * PURE JVM, fully offline, zero dependencies: `"2 km to mi"` → `"2 km = 1.2427 mi"`.
 * Supports length, mass, temperature and basic (fixed reference-rate) currency
 * conversion. The conversion matrix lives in [UnitConverterCore] and is unit-tested.
 *
 * - Serves the `UnitConversion` capability via [UnitConverterPlugin].
 * - `availability()` is always `Ok` — conversion needs no device capability
 *   and no network.
 * - Opt-in off by default; toggle in Settings → Plugins / the Plugin Store.
 */
class UnitConverterPluginImpl : NoteflowPlugin, UnitConverterPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Unit Converter",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Converts \"2 km to mi\" inline — length, mass, temperature and basic currency (offline, no deps).",
        capabilities = setOf(PluginCapability.UnitConversion)
    )

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {}

    override fun convert(query: String): UnitConversionOutcome {
        if (query.isBlank()) {
            return UnitConversionOutcome.Error("Enter a conversion like \"2 km to mi\" or \"100 F to C\".")
        }
        val result = UnitConverterCore.convertQuery(query)
            ?: return UnitConversionOutcome.Error(
                "Could not parse \"${query.trim()}\" — try \"2 km to mi\", \"100 F to C\" or \"5 USD to EUR\"."
            )
        return UnitConversionOutcome.Success(result)
    }

    companion object {
        const val MIN_API = 26
        const val ID = "com.authorss81.noteflow.plugins.unitconverter"
    }
}