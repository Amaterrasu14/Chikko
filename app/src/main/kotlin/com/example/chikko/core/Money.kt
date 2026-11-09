package com.example.chikko.core

/**
 * Money and quantity helpers.
 *
 * Units:
 *  - centavos: Long, 100 centavos = ₱1 (sale prices, expenses, totals)
 *  - micro: Long, 1,000 micro = 1 centavo, 100,000 micro = ₱1 (unit costs and ledger values)
 *  - qty: Long thousandths (0.150 kg = 150, 2 pc = 2000)
 */
object Money {
    const val MICRO_PER_CENTAVO = 1_000L
    const val MICRO_PER_PESO = 100_000L
    const val QTY_SCALE = 1_000L

    fun pesosToCentavos(pesos: Long): Long = pesos * 100L

    fun microToCentavos(micro: Long): Long = roundDiv(micro, MICRO_PER_CENTAVO)

    fun centavosToMicro(centavos: Long): Long = centavos * MICRO_PER_CENTAVO

    /** Formats centavos as "₱1,234.50" (negative values as "-₱12.00"). */
    fun formatCentavos(centavos: Long): String {
        val sign = if (centavos < 0) "-" else ""
        val abs = if (centavos < 0) -centavos else centavos
        val whole = abs / 100L
        val cents = abs % 100L
        return "$sign₱${groupThousands(whole)}.${cents.toString().padStart(2, '0')}"
    }

    /** Formats micro-centavo values as pesos with 2 decimals (rounded). */
    fun formatMicro(micro: Long): String = formatCentavos(microToCentavos(micro))

    /** Formats a thousandths quantity without trailing zeros: 1500 -> "1.5", 150 -> "0.15". */
    fun formatQty(qty: Long): String {
        val sign = if (qty < 0) "-" else ""
        val abs = if (qty < 0) -qty else qty
        val whole = abs / QTY_SCALE
        val frac = (abs % QTY_SCALE).toString().padStart(3, '0').trimEnd('0')
        return if (frac.isEmpty()) "$sign$whole" else "$sign$whole.$frac"
    }

    /** Parses "12", "12.5" or "12.50" pesos into centavos. Returns null if invalid or negative. */
    fun parsePesosToCentavos(text: String): Long? {
        val cleaned = text.trim().replace(",", "").removePrefix("₱")
        val parts = cleaned.split(".")
        if (parts.isEmpty() || parts.size > 2) return null
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val cents = when (parts.size) {
            1 -> 0L
            else -> {
                val frac = parts[1].padEnd(2, '0')
                if (parts[1].length > 2) return null
                frac.toLongOrNull() ?: return null
            }
        }
        if (whole < 0 || cents < 0) return null
        return whole * 100L + cents
    }

    /** Parses a quantity string ("0.15", "2") into thousandths. Returns null if invalid. */
    fun parseQty(text: String): Long? {
        val parts = text.trim().split(".")
        if (parts.isEmpty() || parts.size > 2) return null
        val whole = parts[0].ifEmpty { "0" }.toLongOrNull() ?: return null
        val frac = if (parts.size == 1) 0L else {
            if (parts[1].length > 3) return null
            parts[1].padEnd(3, '0').toLongOrNull() ?: return null
        }
        if (whole < 0 || frac < 0) return null
        return whole * QTY_SCALE + frac
    }

    fun roundDiv(numerator: Long, denominator: Long): Long {
        val half = denominator / 2
        return if (numerator >= 0) (numerator + half) / denominator
        else -((-numerator + half) / denominator)
    }

    private fun groupThousands(value: Long): String {
        val digits = value.toString()
        val sb = StringBuilder()
        digits.forEachIndexed { i, c ->
            if (i > 0 && (digits.length - i) % 3 == 0) sb.append(',')
            sb.append(c)
        }
        return sb.toString()
    }
}
