package com.example.chikko.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Append-only. Triggers block UPDATE and DELETE (see AppDatabase). */
@Entity(
    tableName = "stock_movement",
    foreignKeys = [ForeignKey(
        entity = InventoryItemEntity::class,
        parentColumns = ["id"],
        childColumns = ["inventoryItemId"],
    )],
    indices = [Index("inventoryItemId"), Index("sourceId")],
)
data class StockMovementEntity(
    @PrimaryKey val id: String,
    val inventoryItemId: String,
    val type: String,           // PURCHASE, SALE_CONSUMPTION, SALE_REVERSAL, WASTAGE, COUNT_ADJUST, ...
    val qtyDelta: Long,         // signed thousandths
    val unitCostMicro: Long,    // snapshot unit cost
    val valueMicro: Long,       // signed value in micro-centavos
    val sourceId: String?,      // sale id, purchase id, etc.
    val reversesId: String?,
    val reason: String,
    val createdAt: Long,
    val periodMonth: String,
)

@Entity(tableName = "sale")
data class SaleEntity(
    @PrimaryKey val id: String,
    val receiptNo: String,
    val createdAt: Long,
    val periodMonth: String,
    val status: String,          // COMPLETED, PARTIALLY_REFUNDED, REFUNDED, VOIDED, DELETED
    val subtotalCentavos: Long,
    val discountCentavos: Long,
    val totalCentavos: Long,
    val paymentType: String,     // CASH, EWALLET_GCASH, EWALLET_MAYA, BANK_TRANSFER
    val paymentRef: String,
    val note: String,
)

/** Snapshots of name, price, and costs at sale time. Never changed after creation. */
@Entity(
    tableName = "sale_line",
    foreignKeys = [ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["saleId"])],
    indices = [Index("saleId")],
)
data class SaleLineEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val menuItemId: String?,
    val itemNameSnapshot: String,
    val qty: Long,                    // thousandths of a serving
    val unitPriceCentavos: Long,
    val lineDiscountCentavos: Long,
    val unitFoodCostMicro: Long,      // ingredients only
    val unitPackagingCostMicro: Long, // packaging only
    val unitOverheadMicro: Long,
    val note: String,
)

/** Account entries: revenue, discount, costs, overhead. Append-only with reversesId. */
@Entity(
    tableName = "ledger_entry",
    foreignKeys = [ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["saleId"])],
    indices = [Index("saleId"), Index("periodMonth")],
)
data class LedgerEntryEntity(
    @PrimaryKey val id: String,
    val periodMonth: String,
    val saleId: String?,
    val saleLineId: String?,
    val account: String,     // REVENUE, DISCOUNT, REFUND, FOOD_COST, PACKAGING_COST, OVERHEAD
    val amountCentavos: Long, // signed; costs are stored as positive debits in the view
    val reversesId: String?,
    val reason: String,
    val createdAt: Long,
)

@Entity(
    tableName = "sale_event",
    foreignKeys = [ForeignKey(entity = SaleEntity::class, parentColumns = ["id"], childColumns = ["saleId"])],
    indices = [Index("saleId")],
)
data class SaleEventEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val type: String,        // CREATED, VOIDED, REFUNDED, EDITED, DELETED
    val reason: String,
    val createdAt: Long,
)

@Entity(tableName = "refund")
data class RefundEntity(
    @PrimaryKey val id: String,
    val saleId: String,
    val amountCentavos: Long,
    val restockedPackaging: Boolean,
    val reason: String,
    val createdAt: Long,
    val periodMonth: String,
)
