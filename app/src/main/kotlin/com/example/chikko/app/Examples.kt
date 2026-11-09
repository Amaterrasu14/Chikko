package com.example.chikko.app

import com.example.chikko.data.ChikkoRepository
import com.example.chikko.data.db.AppDatabase
import com.example.chikko.data.db.CategoryEntity
import com.example.chikko.data.db.InventoryItemEntity
import com.example.chikko.data.db.MenuItemEntity
import com.example.chikko.data.db.RecipeLineEntity
import java.util.UUID

/**
 * Clearly labeled placeholder data. Every name starts with "EXAMPLE:" so it can be
 * found and removed. Nothing is inserted unless the owner taps "Add examples".
 */
object Examples {
    const val PREFIX = "EXAMPLE:"

    suspend fun add(db: AppDatabase, repo: ChikkoRepository) {
        val now = System.currentTimeMillis()
        val cat = CategoryEntity(UUID.randomUUID().toString(), "$PREFIX Rice Meals", 0, true, now)
        db.menuDao().upsertCategory(cat)
        val item = MenuItemEntity(
            UUID.randomUUID().toString(), cat.id, "$PREFIX Chicken Adobo Rice Meal",
            9000L, true, "Placeholder for testing", now,
        )
        db.menuDao().upsertItem(item)

        val chicken = inv("$PREFIX Chicken thigh", "INGREDIENT", "kg", now)
        val rice = inv("$PREFIX Rice", "INGREDIENT", "kg", now)
        val box = inv("$PREFIX Rice box", "PACKAGING", "pc", now)
        val bag = inv("$PREFIX Plastic bag", "PACKAGING", "pc", now)
        listOf(chicken, rice, box, bag).forEach { db.inventoryDao().upsertItem(it) }

        repo.recordPurchase(chicken.id, 5_000, 105_000L, "EXAMPLE stock", now)
        repo.recordPurchase(rice.id, 10_000, 60_000L, "EXAMPLE stock", now)
        repo.recordPurchase(box.id, 100_000, 60_000L, "EXAMPLE stock", now)
        repo.recordPurchase(bag.id, 200_000, 30_000L, "EXAMPLE stock", now)

        listOf(
            RecipeLineEntity(UUID.randomUUID().toString(), item.id, chicken.id, 150, 100),
            RecipeLineEntity(UUID.randomUUID().toString(), item.id, rice.id, 200, 100),
            RecipeLineEntity(UUID.randomUUID().toString(), item.id, box.id, 1000, 100),
            RecipeLineEntity(UUID.randomUUID().toString(), item.id, bag.id, 1000, 100),
        ).forEach { db.recipeDao().upsertLine(it) }
    }

    private fun inv(name: String, kind: String, unit: String, now: Long) = InventoryItemEntity(
        id = UUID.randomUUID().toString(), name = name, kind = kind, unit = unit,
        lowStockQty = 1000L, onHandQty = 0L, avgUnitCostMicro = 0L, active = true, createdAt = now,
    )
}
