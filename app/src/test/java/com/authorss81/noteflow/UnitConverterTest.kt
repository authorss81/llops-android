package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManager
import com.authorss81.noteflow.plugins.PluginRegistry
import com.authorss81.noteflow.plugins.PluginResult
import com.authorss81.noteflow.plugins.UnitConversionOutcome
import com.authorss81.noteflow.plugins.UnitConverterPlugin
import com.authorss81.noteflow.plugins.unitconverter.UnitConverterCore
import com.authorss81.noteflow.plugins.unitconverter.UnitConverterPluginImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 26 Unit Converter plugin tests (PURE JVM, fully offline).
 *
 * Covers the conversion-matrix correctness (length, mass, temperature,
 * currency-basic) against hand-computed reference values, query parsing and
 * result formatting.
 */
class UnitConverterTest {

    // ---- length --------------------------------------------------------------

    @Test
    fun `converts 2 km to miles (long-standing reference)`() {
        val text = UnitConverterCore.convertQuery("2 km to mi")
        assertNotNull(text)
        // 2 km = 1.242742 mi
        assertEquals("2 km = 1.242742 mi", text)
    }

    @Test
    fun `converts meters to centimeters exactly`() {
        assertEquals("3 m = 300 cm", UnitConverterCore.convertQuery("3 m to cm"))
    }

    @Test
    fun `converts a decimal amount with comma handling`() {
        val text = UnitConverterCore.convertQuery("1.5 miles to km")
        assertNotNull(text)
        assertTrue(text!!.contains("2.414016 km"))
    }

    @Test
    fun `length round-trips km to mi to km`() {
        val value = UnitConverterCore.convert(2.0, "km", "mi")!!
        val back = UnitConverterCore.convert(value, "mi", "km")!!
        assertEquals(2.0, back, 0.000001)
    }

    // ---- mass --------------------------------------------------------------

    @Test
    fun `converts pounds to kilograms at the reference lb factor`() {
        val value = UnitConverterCore.convert(10.0, "lb", "kg")!!
        assertEquals(4.5359237, value, 0.0000001)
        assertEquals("10 lb = 4.535924 kg", UnitConverterCore.convertQuery("10 lb to kg"))
    }

    @Test
    fun `converts grams to ounces at the reference oz factor`() {
        val value = UnitConverterCore.convert(100.0, "g", "oz")!!
        assertEquals(3.5273962, value, 0.0001)
    }

    // ---- temperature ---------------------------------------------------------

    @Test
    fun `converts celsius to fahrenheit (0 C = 32 F)`() {
        assertEquals("0 c = 32 f", UnitConverterCore.convertQuery("0 C to F"))
    }

    @Test
    fun `converts fahrenheit to celsius at the freezing reference`() {
        assertEquals("100 f = 37.777778 c", UnitConverterCore.convertQuery("100 F to C"))
    }

    @Test
    fun `converts celsius to kelvin`() {
        assertEquals("0 c = 273.15 k", UnitConverterCore.convertQuery("0 C to K"))
    }

    @Test
    fun `temperature round-trips kelvin to celsius`() {
        val value = UnitConverterCore.convert(300.0, "K", "C")!!
        assertEquals(26.85, value, 0.0001)
    }

    // ---- currency-basic -----------------------------------------------------

    @Test
    fun `converts USD to EUR at the basic reference rate`() {
        val value = UnitConverterCore.convert(10.0, "USD", "EUR")!!
        // 10 * 1.0 / 1.08
        assertEquals(9.259259, value, 0.000001)
    }

    @Test
    fun `currency result is labelled as a basic reference rate`() {
        val text = UnitConverterCore.convertQuery("5 USD to EUR")
        assertNotNull(text)
        assertTrue(text!!.contains("(basic reference rate)"))
    }

    @Test
    fun `currency aliases resolve (dollar and euro words)`() {
        val parsed = UnitConverterCore.parse("5 dollars to euros")
        assertNotNull(parsed)
        assertEquals("USD", parsed!!.second)
        assertEquals("EUR", parsed.third)
    }

    // ---- parsing & errors ----------------------------------------------------

    @Test
    fun `parse accepts space, in, and arrow forms`() {
        assertNotNull(UnitConverterCore.parse("2 km to mi"))
        assertNotNull(UnitConverterCore.parse("2km in mi"))
        assertNotNull(UnitConverterCore.parse("2 km -> mi"))
        assertNotNull(UnitConverterCore.parse("2 km → mi"))
        assertNotNull(UnitConverterCore.parse("2 Km to MI"))
    }

    @Test
    fun `parse rejects gibberish and cross-category queries`() {
        assertNull(UnitConverterCore.parse("hello world"))
        assertNull(UnitConverterCore.parse("2"))
        // km (length) vs kg (mass): cross-category is refused.
        assertNull(UnitConverterCore.convert(2.0, "km", "kg"))
    }

    @Test
    fun `format trims trailing zeros`() {
        assertEquals("300", UnitConverterCore.formatResult(300.0))
        assertTrue(UnitConverterCore.formatResult(1.242742).startsWith("1.242"))
    }

    @Test
    fun `format never turns a tiny non-zero result into zero`() {
        assertTrue(UnitConverterCore.formatResult(1e-9) != "0")
        // 1 mg → t is 1e-9 t: it must not read as "0 t".
        val text = UnitConverterCore.convertQuery("1 mg to t")
        assertNotNull(text)
        assertTrue(text!!.contains("E-"))
    }

    // ---- plugin --------------------------------------------------------------

    @Test
    fun `plugin converts a query and errors honestly on unparseable input`() {
        val plugin = UnitConverterPluginImpl()
        assertTrue(plugin.convert("2 km to mi") is UnitConversionOutcome.Success)
        assertTrue(plugin.convert("garbage") is UnitConversionOutcome.Error)
        assertTrue(plugin.convert("") is UnitConversionOutcome.Error)
    }

    @Test
    fun `plugin routes through the manager with the unit conversion capability`() = runBlocking {
        val plugin = UnitConverterPluginImpl()
        val registry = PluginRegistry(
            InMemoryEnableStore(),
            plugins = listOf(plugin),
            currentApiLevel = 26
        )
        registry.setEnabled(plugin.id, enabled = true)
        val manager = PluginManager(registry)
        val result = manager.withPluginAsync(PluginCapability.UnitConversion, null) { p ->
            (p as UnitConverterPlugin).convert("2 km to mi")
        }
        assertTrue(result is PluginResult.Success)
    }

    @Test
    fun `manifest declares unit conversion capability`() {
        val plugin = UnitConverterPluginImpl()
        assertTrue(PluginCapability.UnitConversion in plugin.capabilities)
        assertTrue(plugin.id.startsWith("com.authorss81.noteflow.plugins.unitconverter"))
    }
}