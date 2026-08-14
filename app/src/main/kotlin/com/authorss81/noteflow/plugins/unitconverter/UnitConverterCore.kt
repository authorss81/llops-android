package com.authorss81.noteflow.plugins.unitconverter

/**
 * PURE JVM unit-conversion core for the Unit Converter plugin (Phase 26).
 *
 * Fully offline, zero dependencies. Supports:
 * - **length**: mm, cm, m, km, in, ft, yd, mi
 * - **mass**: mg, g, kg, t (tonne), lb, oz
 * - **temperature**: °C, °F, K (non-linear)
 * - **currency-basic**: USD, EUR, GBP, JPY, INR (fixed REFERENCE rates to USD —
 *   clearly labelled as such in the result; no live FX, no network)
 *
 * Query grammar: `2 km to mi` (also `2km to mi`, `2 km in mi`, `2km→mi`,
 * `2 km -> mi`). The conversion matrix is unit-tested for correctness.
 */
object UnitConverterCore {

    /** One convertible unit: its canonical symbol + aliases. */
    data class Unit(val symbol: String, val category: Category, val aliases: List<String> = emptyList())

    enum class Category { LENGTH, MASS, TEMPERATURE, CURRENCY }

    // ---- unit tables ------------------------------------------------------

    /** meter factor: value_in_m = amount * factor. */
    private val lengthUnits = mapOf(
        "mm" to 0.001, "cm" to 0.01, "m" to 1.0, "km" to 1000.0,
        "in" to 0.0254, "ft" to 0.3048, "yd" to 0.9144, "mi" to 1609.344
    )
    private val lengthAliases = mapOf(
        "millimeter" to "mm", "millimetre" to "mm", "millimeters" to "mm", "millimetres" to "mm",
        "centimeter" to "cm", "centimetre" to "cm", "centimeters" to "cm", "centimetres" to "cm",
        "meter" to "m", "metre" to "m", "meters" to "m", "metres" to "m",
        "kilometer" to "km", "kilometre" to "km", "kilometers" to "km", "kilometres" to "km",
        "inch" to "in", "inches" to "in",
        "foot" to "ft", "feet" to "ft",
        "yard" to "yd", "yards" to "yd",
        "mile" to "mi", "miles" to "mi"
    )

    /** kilogram factor: value_in_kg = amount * factor. */
    private val massUnits = mapOf(
        "mg" to 0.000001, "g" to 0.001, "kg" to 1.0, "t" to 1000.0,
        "lb" to 0.45359237, "oz" to 0.028349523125
    )
    private val massAliases = mapOf(
        "milligram" to "mg", "milligrams" to "mg",
        "gram" to "g", "grams" to "g",
        "kilogram" to "kg", "kilograms" to "kg",
        "tonne" to "t", "tonnes" to "t", "metric ton" to "t", "metric tons" to "t",
        "pound" to "lb", "pounds" to "lb", "lbs" to "lb",
        "ounce" to "oz", "ounces" to "oz"
    )

    private val temperatureUnits = setOf("c", "f", "k")

    /** USD reference rates (fixed, labelled "basic reference" in the result). */
    private val currencyRatesToUsd = mapOf(
        "USD" to 1.0,
        "EUR" to 1.08,
        "GBP" to 1.27,
        "JPY" to 0.0067,
        "INR" to 0.012
    )
    private val currencyAliases = mapOf(
        "usd" to "USD", "us dollar" to "USD", "dollar" to "USD", "dollars" to "USD", "$" to "USD",
        "eur" to "EUR", "euro" to "EUR", "euros" to "EUR", "€" to "EUR",
        "gbp" to "GBP", "pound sterling" to "GBP", "british pound" to "GBP", "£" to "GBP",
        "jpy" to "JPY", "yen" to "JPY", "¥" to "JPY",
        "inr" to "INR", "rupee" to "INR", "rupees" to "INR", "₹" to "INR"
    )

    /** All recognised symbols per category (for unit detection). */
    private val symbolToUnit: Map<String, Unit> = buildMap {
        lengthUnits.forEach { (sym, _) -> put(sym, Unit(sym, Category.LENGTH)) }
        massUnits.forEach { (sym, _) -> put(sym, Unit(sym, Category.MASS)) }
        temperatureUnits.forEach { sym -> put(sym, Unit(sym, Category.TEMPERATURE)) }
        currencyRatesToUsd.forEach { (sym, _) -> put(sym, Unit(sym, Category.CURRENCY)) }
    }

    private val aliasToSymbol: Map<String, String> = buildMap {
        putAll(lengthAliases); putAll(massAliases); putAll(currencyAliases)
        temperatureUnits.forEach { sym -> put(sym, sym) }
    }

    /** Resolve a unit token (symbol or alias) to its canonical unit, or null. */
    fun resolveUnit(token: String): Unit? {
        val cleaned = token.trim().lowercase().removePrefix("°")
            .removePrefix("degrees").trim()
        val canonical = aliasToSymbol[cleaned] ?: symbolToUnit.keys.firstOrNull { it == cleaned } ?: return null
        return symbolToUnit[canonical] ?: Unit(canonical, categoryOf(canonical))
    }

    private fun categoryOf(symbol: String): Category = when (symbol) {
        in lengthUnits -> Category.LENGTH
        in massUnits -> Category.MASS
        in temperatureUnits -> Category.TEMPERATURE
        else -> Category.CURRENCY
    }

    private val symbolRegex = Regex("""[-+]?\d+(?:[.,]\d+)?\s*[a-zA-Z°]+""")

    /** Parse `"2 km to mi"` (also "in", "→", "->") into (amount, from, to). */
    fun parse(query: String): Triple<Double, String, String>? {
        val trimmed = query.trim().replace("→", " to ").replace("->", " to ")
        if (trimmed.isBlank()) return null
        val normalized = "\\s+(?:to|in)\\s+".toRegex().let { r ->
            trimmed.replace(r, " to ")
        }
        val parts = normalized.split(" to ")
        if (parts.size != 2) return null
        val fromMatch = symbolRegex.find(parts[0].trim()) ?: return null
        val toPart = parts[1].trim()
        val fromToken = fromMatch.value.trim()
        if (fromToken.isEmpty()) return null

        // amount + unit inside the from token ("2 km", "2km", "2.5km")
        val amountMatch = Regex("""^([-+]?\d+(?:[.,]\d+)?)\s*([a-zA-Z°]+)""").find(fromToken) ?: return null
        val amount = amountMatch.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val fromUnit = resolveUnit(amountMatch.groupValues[2]) ?: return null

        val toUnit = resolveUnit(toPart) ?: return null
        return Triple(amount, fromUnit.symbol, toUnit.symbol)
    }

    /** Convert [amount] from [fromSymbol] to [toSymbol]. */
    fun convert(amount: Double, fromSymbol: String, toSymbol: String): Double? {
        val from = resolveUnit(fromSymbol) ?: return null
        val to = resolveUnit(toSymbol) ?: return null
        if (from.category != to.category) return null
        return when (from.category) {
            Category.LENGTH -> amount * lengthUnits.getValue(from.symbol) / lengthUnits.getValue(to.symbol)
            Category.MASS -> amount * massUnits.getValue(from.symbol) / massUnits.getValue(to.symbol)
            Category.TEMPERATURE -> fromKelvin(toKelvin(amount, from.symbol), to.symbol)
            Category.CURRENCY -> {
                val fromRate = currencyRatesToUsd.getValue(from.symbol)
                val toRate = currencyRatesToUsd.getValue(to.symbol)
                amount * fromRate / toRate
            }
        }
    }

    private fun toKelvin(value: Double, symbol: String): Double = when (symbol) {
        "c" -> value + 273.15
        "f" -> (value - 32.0) * 5.0 / 9.0 + 273.15
        else -> value
    }

    private fun fromKelvin(kelvin: Double, symbol: String): Double = when (symbol) {
        "c" -> kelvin - 273.15
        "f" -> (kelvin - 273.15) * 9.0 / 5.0 + 32.0
        else -> kelvin
    }

    /** Format a result value with sensible precision (no trailing zeros). */
    fun formatResult(value: Double): String {
        val rounded = Math.round(value * 1_000_000.0) / 1_000_000.0
        if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
        return rounded.toString()
    }

    /**
     * Convert a full query like `"2 km to mi"`.
     * @return the human result `"2 km = 1.2427 mi"`, or null when unparseable.
     */
    fun convertQuery(query: String): String? {
        val (amount, from, to) = parse(query) ?: return null
        val value = convert(amount, from, to) ?: return null
        val label = if (resolveUnit(from)?.category == Category.CURRENCY) {
            "(basic reference rate) "
        } else {
            ""
        }
        return "${formatResult(amount)} $from = $label${formatResult(value)} $to"
    }
}