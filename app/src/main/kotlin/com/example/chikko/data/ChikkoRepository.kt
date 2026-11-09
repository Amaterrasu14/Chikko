package com.example.chikko.data

import androidx.room.withTransaction
import com.example.chikko.core.Money
import com.example.chikko.data.db.AppDatabase
import com.example.chikko.data.db.InventoryItemEntity
import com.example.chikko.data.db.LedgerEntryEntity
import com.example.chikko.data.db.SaleEntity
import com.example.chikko.data.db.SaleEventEntity
import com.example.chikko.data.db.SaleLineEntity
import com.example.chikko.data.db.StockMovementEntity
import com.example.chikko.domain.Costing
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * One POS cart line. qty is thousandths of a serving.
 * unitPriceCentavos is the menu price at the moment of sale (snapshotted).
 */
data class CartLine(
    val menuItemId: String,
    val qty: Long,
    val unitPriceCentavos: Long,
    val lineDiscountCentavos: Long = 0L,
    val note: String = "",
)

data class NewSale(
    val lines: List<CartLine>,
    val saleDiscountCentavos: Long,
    val paymentType: String,      // CASH, EWALLET_GCASH, EWALLET_MAYA, BANK_TRANSFER
    val paymentRef: String,
    val paidCentavos: Long,
    val note: String,
)

/**
 * Single transaction boundary for every money or stock write.
 *
 * Ledger sign convention (all amounts in centavos):
 *  - REVENUE is positive. DISCOUNT, FOOD_COST, PACKAGING_COST and OVERHEAD are negative.
 *  - So the sum of a sale's ledger entries is its net profit contribution.
 *  - A reversal is a new entry with the negated amount and reversesId set to the original.
 */
class ChikkoRepository(
    private val db: AppDatabase,
    private val zone: ZoneId = ZoneId.of("Asia/Manila"),
) {
    private val menu = db.menuDao()
    private val inv = db.inventoryDao()
    private val recipes = db.recipeDao()
    private val expenses = db.expenseDao()
    private val sales = db.salesDao()
    private val periods = db.periodDao()

    fun monthOf(epochMillis: Long): String =
        YearMonth.from(Instant.ofEpochMilli(epochMillis).atZone(zone)).toString()

    private suspend fun requireOpen(month: String) {
        check(periods.get(month)?.status != "CLOSED") { "Period $month is closed" }
    }

    /** Overhead per serving for [month], using equal allocation (other methods: later step). */
    private suspend fun overheadPerServingMicro(month: String): Long {
        val pool = expenses.forMonth(month).sumOf { it.amountCentavos }
        val planned = expenses.plannedVolume(month)?.plannedServings ?: 0L
        return Costing.overheadPerServingMicro(pool, planned)
    }

    /**
     * Posts a completed sale: snapshots prices and costs, deducts stock per recipe,
     * writes ledger entries, and records a CREATED event. Returns the new sale id.
     */
    suspend fun placeSale(input: NewSale, now: Long = System.currentTimeMillis()): String =
        db.withTransaction {
            require(input.lines.isNotEmpty()) { "Cart is empty" }
            val month = monthOf(now)
            requireOpen(month)

            val saleId = uuid()
            val receiptNo = "R-" + (sales.saleCount() + 1).toString().padStart(6, '0')
            val ohMicro = overheadPerServingMicro(month)
            val book = StockBook(inv)

            val lineRows = mutableListOf<SaleLineEntity>()
            val ledger = mutableListOf<LedgerEntryEntity>()
            val movements = mutableListOf<StockMovementEntity>()
            var gross = 0L
            var lineDiscounts = 0L

            for (line in input.lines) {
                require(line.qty > 0) { "Quantity must be positive" }
                val item = menu.getItem(line.menuItemId) ?: error("Menu item not found")
                check(item.active) { "${item.name} is inactive" }

                val lineId = uuid()
                val lineGross = Money.roundDiv(line.unitPriceCentavos * line.qty, Money.QTY_SCALE)
                val lineNet = lineGross - line.lineDiscountCentavos
                require(lineNet >= 0) { "Line discount exceeds line amount" }
                gross += lineGross
                lineDiscounts += line.lineDiscountCentavos

                var foodMicro = 0L
                var packMicro = 0L
                var unitFoodMicro = 0L
                var unitPackMicro = 0L
                for (r in recipes.linesFor(item.id)) {
                    val stock = book.get(r.inventoryItemId)
                    val consumed = consumedQty(r.qtyPerServing, r.yieldPct, line.qty)
                    val valueMicro = Costing.valueMicro(consumed, stock.avgUnitCostMicro)
                    val perServingMicro = Costing.valueMicro(
                        consumedQty(r.qtyPerServing, r.yieldPct, Money.QTY_SCALE),
                        stock.avgUnitCostMicro,
                    )
                    book.adjust(stock.id, -consumed)
                    movements += StockMovementEntity(
                        id = uuid(), inventoryItemId = stock.id, type = "SALE_CONSUMPTION",
                        qtyDelta = -consumed, unitCostMicro = stock.avgUnitCostMicro,
                        valueMicro = -valueMicro, sourceId = saleId, reversesId = null,
                        reason = item.name, createdAt = now, periodMonth = month,
                    )
                    if (stock.kind == "PACKAGING") {
                        packMicro += valueMicro; unitPackMicro += perServingMicro
                    } else {
                        foodMicro += valueMicro; unitFoodMicro += perServingMicro
                    }
                }

                val ohLineMicro = Costing.valueMicro(line.qty, ohMicro)
                lineRows += SaleLineEntity(
                    id = lineId, saleId = saleId, menuItemId = item.id,
                    itemNameSnapshot = item.name, qty = line.qty,
                    unitPriceCentavos = line.unitPriceCentavos,
                    lineDiscountCentavos = line.lineDiscountCentavos,
                    unitFoodCostMicro = unitFoodMicro, unitPackagingCostMicro = unitPackMicro,
                    unitOverheadMicro = ohMicro, note = line.note,
                )
                ledger += entry(month, saleId, lineId, "REVENUE", lineGross, now)
                if (line.lineDiscountCentavos > 0) {
                    ledger += entry(month, saleId, lineId, "DISCOUNT", -line.lineDiscountCentavos, now)
                }
                ledger += entry(month, saleId, lineId, "FOOD_COST", -Money.microToCentavos(foodMicro), now)
                ledger += entry(month, saleId, lineId, "PACKAGING_COST", -Money.microToCentavos(packMicro), now)
                ledger += entry(month, saleId, lineId, "OVERHEAD", -Money.microToCentavos(ohLineMicro), now)
            }

            require(input.saleDiscountCentavos >= 0) { "Sale discount cannot be negative" }
            val total = gross - lineDiscounts - input.saleDiscountCentavos
            require(total >= 0) { "Total cannot be negative" }
            require(input.paidCentavos == total) {
                "Payment ${Money.formatCentavos(input.paidCentavos)} does not match total ${Money.formatCentavos(total)}"
            }
            if (input.saleDiscountCentavos > 0) {
                ledger += entry(month, saleId, null, "DISCOUNT", -input.saleDiscountCentavos, now)
            }

            sales.insertSale(
                SaleEntity(
                    id = saleId, receiptNo = receiptNo, createdAt = now, periodMonth = month,
                    status = "COMPLETED", subtotalCentavos = gross,
                    discountCentavos = lineDiscounts + input.saleDiscountCentavos,
                    totalCentavos = total, paymentType = input.paymentType,
                    paymentRef = input.paymentRef, note = input.note,
                ),
            )
            sales.insertLines(lineRows)
            sales.insertLedger(ledger)
            movements.forEach { inv.insertMovement(it) }
            book.flush()
            sales.insertEvent(SaleEventEntity(uuid(), saleId, "CREATED", "", now))
            saleId
        }

    /** Voids a completed sale in the same period it was posted to. Keeps the receipt number. */
    suspend fun voidSale(saleId: String, reason: String, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            require(reason.isNotBlank()) { "A reason is required" }
            val sale = sales.getSale(saleId) ?: error("Sale not found")
            check(sale.status == "COMPLETED") { "Only completed sales can be voided (status ${sale.status})" }
            reverseSale(sale, "VOIDED", "VOIDED", reason, now)
        }

    /** Deletes a sale: same reversals as void, status DELETED. Rows are kept for audit. */
    suspend fun deleteSale(saleId: String, reason: String, now: Long = System.currentTimeMillis()) =
        db.withTransaction {
            require(reason.isNotBlank()) { "A reason is required" }
            val sale = sales.getSale(saleId) ?: error("Sale not found")
            check(sale.status == "COMPLETED" || sale.status == "VOIDED") {
                "Sale cannot be deleted (status ${sale.status})"
            }
            if (sale.status == "COMPLETED") {
                reverseSale(sale, "DELETED", "DELETED", reason, now)
            } else {
                requireOpen(sale.periodMonth)
                sales.setStatus(sale.id, "DELETED")
                sales.insertEvent(SaleEventEntity(uuid(), sale.id, "DELETED", reason, now))
            }
        }

    private suspend fun reverseSale(
        sale: SaleEntity,
        newStatus: String,
        eventType: String,
        reason: String,
        now: Long,
    ) {
        requireOpen(sale.periodMonth)

        val originals = sales.ledgerFor(sale.id).filter { it.reversesId == null }
        sales.insertLedger(
            originals.map {
                it.copy(id = uuid(), amountCentavos = -it.amountCentavos, reversesId = it.id, reason = reason, createdAt = now)
            },
        )

        val book = StockBook(inv)
        val consumption = inv.movementsForSource(sale.id).filter { it.type == "SALE_CONSUMPTION" }
        for (m in consumption) {
            book.get(m.inventoryItemId)
            book.adjust(m.inventoryItemId, -m.qtyDelta)
            inv.insertMovement(
                m.copy(
                    id = uuid(), type = "SALE_REVERSAL", qtyDelta = -m.qtyDelta,
                    valueMicro = -m.valueMicro, reversesId = m.id, reason = reason,
                    createdAt = now, periodMonth = sale.periodMonth,
                ),
            )
        }
        book.flush()

        sales.setStatus(sale.id, newStatus)
        sales.insertEvent(SaleEventEntity(uuid(), sale.id, eventType, reason, now))
    }

    /** Stock purchase. Updates the weighted-average unit cost (plan section 4.2). */
    suspend fun recordPurchase(
        itemId: String,
        qty: Long,
        totalCentavos: Long,
        note: String,
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        require(qty > 0) { "Quantity must be positive" }
        require(totalCentavos >= 0) { "Cost cannot be negative" }
        val month = monthOf(now)
        requireOpen(month)
        val item = inv.getItem(itemId) ?: error("Inventory item not found")

        val unitMicro = Money.roundDiv(totalCentavos * Money.MICRO_PER_CENTAVO * Money.QTY_SCALE, qty)
        val newAvg = Costing.weightedAverage(item.onHandQty, item.avgUnitCostMicro, qty, unitMicro)
        inv.insertMovement(
            StockMovementEntity(
                id = uuid(), inventoryItemId = itemId, type = "PURCHASE", qtyDelta = qty,
                unitCostMicro = unitMicro, valueMicro = Costing.valueMicro(qty, unitMicro),
                sourceId = null, reversesId = null, reason = note, createdAt = now, periodMonth = month,
            ),
        )
        inv.upsertItem(item.copy(onHandQty = item.onHandQty + qty, avgUnitCostMicro = newAvg))
    }

    /** Wastage or spoilage. Leaves the average cost unchanged. */
    suspend fun recordWastage(
        itemId: String,
        qty: Long,
        reason: String,
        now: Long = System.currentTimeMillis(),
    ) = db.withTransaction {
        require(qty > 0) { "Quantity must be positive" }
        val month = monthOf(now)
        requireOpen(month)
        val item = inv.getItem(itemId) ?: error("Inventory item not found")
        inv.insertMovement(
            StockMovementEntity(
                id = uuid(), inventoryItemId = itemId, type = "WASTAGE", qtyDelta = -qty,
                unitCostMicro = item.avgUnitCostMicro,
                valueMicro = -Costing.valueMicro(qty, item.avgUnitCostMicro),
                sourceId = null, reversesId = null, reason = reason, createdAt = now, periodMonth = month,
            ),
        )
        inv.upsertItem(item.copy(onHandQty = item.onHandQty - qty))
    }

    // ----- Plain reads and master-data writes used by the UI -----

    fun observeCategories() = menu.observeCategories()
    fun observeItems() = menu.observeItems()
    fun observeInventory() = inv.observeItems()
    fun observeSales() = sales.observeSales()
    fun observeMovements(itemId: String) = inv.observeMovements(itemId)
    fun observeRecipe(menuItemId: String) = recipes.observeLines(menuItemId)
    fun observeExpenses(month: String) = expenses.observeForMonth(month)
    suspend fun saleLines(saleId: String): List<SaleLineEntity> = sales.linesFor(saleId)
    suspend fun saleLedger(saleId: String): List<LedgerEntryEntity> = sales.ledgerFor(saleId)

    suspend fun upsertInventoryItem(row: InventoryItemEntity) = db.withTransaction {
        val existing = inv.getItem(row.id)
        // Stock figures are ledger projections: keep them when editing names or thresholds.
        inv.upsertItem(row.copy(onHandQty = existing?.onHandQty ?: 0L, avgUnitCostMicro = existing?.avgUnitCostMicro ?: 0L))
    }

    private fun entry(
        month: String, saleId: String, lineId: String?, account: String, amountCentavos: Long, now: Long,
    ) = LedgerEntryEntity(
        id = uuid(), periodMonth = month, saleId = saleId, saleLineId = lineId,
        account = account, amountCentavos = amountCentavos, reversesId = null,
        reason = "SALE", createdAt = now,
    )

    private companion object {
        fun uuid(): String = UUID.randomUUID().toString()

        /** Consumed thousandths of a base unit for [lineQty] thousandths of servings, adjusted for yield. */
        fun consumedQty(qtyPerServing: Long, yieldPct: Int, lineQty: Long): Long {
            require(yieldPct in 1..100) { "Yield must be 1..100" }
            return Money.roundDiv(qtyPerServing * lineQty * 100L, Money.QTY_SCALE * yieldPct)
        }
    }
}

/**
 * Holds inventory rows touched within one transaction so repeated lines for the same
 * ingredient see each other's deductions. Flush writes them back once.
 */
private class StockBook(private val inv: com.example.chikko.data.db.InventoryDao) {
    private val items = HashMap<String, InventoryItemEntity>()

    suspend fun get(id: String): InventoryItemEntity =
        items[id] ?: (inv.getItem(id) ?: error("Inventory item not found: $id")).also { items[id] = it }

    fun adjust(id: String, qtyDelta: Long) {
        val cur = items[id] ?: error("Load $id before adjusting")
        items[id] = cur.copy(onHandQty = cur.onHandQty + qtyDelta)
    }

    suspend fun flush() {
        items.values.forEach { inv.upsertItem(it) }
    }
}
