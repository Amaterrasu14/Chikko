package com.example.chikko.domain

import com.example.chikko.core.Money

/**
 * Pure costing formulas. All costs are micro-centavos (see [Money]).
 * Quantities are thousandths. No Android imports, so these run in JVM unit tests.
 */
object Costing {

    /** Value of [qty] thousandths at [unitCostMicro] per base unit, in micro-centavos. */
    fun valueMicro(qty: Long, unitCostMicro: Long): Long =
        Money.roundDiv(qty * unitCostMicro, Money.QTY_SCALE)

    /**
     * Weighted-average cost after a purchase.
     * Negative or zero on-hand stock does not drag the average (plan section 4.2).
     */
    fun weightedAverage(onHandQty: Long, avgUnitCostMicro: Long, inQty: Long, inUnitCostMicro: Long): Long {
        require(inQty > 0) { "Purchase quantity must be positive" }
        val posQty = if (onHandQty > 0) onHandQty else 0L
        if (posQty == 0L) return inUnitCostMicro
        val totalValue = valueMicro(posQty, avgUnitCostMicro) + valueMicro(inQty, inUnitCostMicro)
        return Money.roundDiv(totalValue * Money.QTY_SCALE, posQty + inQty)
    }

    /**
     * Overhead per serving: pool (centavos) divided by planned servings for the month.
     * Returns 0 when no planned volume is set (the UI shows a warning).
     */
    fun overheadPerServingMicro(poolCentavos: Long, plannedServings: Long): Long {
        if (plannedServings <= 0L) return 0L
        return Money.roundDiv(poolCentavos * Money.MICRO_PER_CENTAVO, plannedServings)
    }

    /**
     * Suggested sale price (centavos, rounded up) that gives [targetMarginPct] of price as margin
     * over [fullCostMicro]. price = cost / (1 - margin).
     */
    fun suggestedPriceCentavos(fullCostMicro: Long, targetMarginPct: Int): Long {
        require(targetMarginPct in 0..99) { "Margin must be 0..99" }
        val costCentavos = Money.microToCentavos(fullCostMicro)
        // ceil(cost * 100 / (100 - margin))
        val denom = 100L - targetMarginPct
        return (costCentavos * 100L + denom - 1) / denom
    }

    /** Ingredient-line consumption in thousandths for [servings] servings of one recipe line. */
    fun consumptionQty(qtyPerServing: Long, servings: Long): Long = qtyPerServing * servings
}
