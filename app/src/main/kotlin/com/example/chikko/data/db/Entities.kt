package com.example.chikko.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Money: *Centavos columns are Long centavos. *Micro columns are Long micro-centavos (1/1000 centavo).
// Quantities: Long thousandths. Times: UTC epoch millis. IDs: UUID strings.

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val sortOrder: Int,
    val active: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "menu_item",
    foreignKeys = [ForeignKey(
        entity = CategoryEntity::class,
        parentColumns = ["id"],
        childColumns = ["categoryId"],
    )],
    indices = [Index("categoryId")],
)
data class MenuItemEntity(
    @PrimaryKey val id: String,
    val categoryId: String,
    val name: String,
    val priceCentavos: Long,
    val active: Boolean,
    val notes: String,
    val createdAt: Long,
)

/** Ingredient or packaging item. onHandQty and avgUnitCostMicro are projections of the ledger. */
@Entity(tableName = "inventory_item")
data class InventoryItemEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,          // INGREDIENT or PACKAGING
    val unit: String,          // kg, g, L, ml, pc, pack
    val lowStockQty: Long,
    val onHandQty: Long,
    val avgUnitCostMicro: Long,
    val active: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "recipe_line",
    foreignKeys = [
        ForeignKey(entity = MenuItemEntity::class, parentColumns = ["id"], childColumns = ["menuItemId"]),
        ForeignKey(entity = InventoryItemEntity::class, parentColumns = ["id"], childColumns = ["inventoryItemId"]),
    ],
    indices = [Index("menuItemId"), Index("inventoryItemId")],
)
data class RecipeLineEntity(
    @PrimaryKey val id: String,
    val menuItemId: String,
    val inventoryItemId: String,
    val qtyPerServing: Long,   // thousandths
    val yieldPct: Int,         // 1..100
)

@Entity(tableName = "expense")
data class ExpenseEntity(
    @PrimaryKey val id: String,
    val name: String,
    val category: String,      // RENT, UTILITIES, LABOR, GAS, PERMITS, OTHER
    val amountCentavos: Long,
    val periodMonth: String,   // YYYY-MM
    val allocationMethod: String, // EQUAL_PER_SERVING, BY_REVENUE, BY_INGREDIENT_COST
    val createdAt: Long,
)

/** Monthly planned servings: the denominator for overhead per serving. */
@Entity(tableName = "planned_volume")
data class PlannedVolumeEntity(
    @PrimaryKey val periodMonth: String,
    val plannedServings: Long,
)

@Entity(tableName = "period")
data class PeriodEntity(
    @PrimaryKey val month: String,   // YYYY-MM
    val status: String,              // OPEN or CLOSED
    val closedAt: Long?,
)
