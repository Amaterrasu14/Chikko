package com.example.chikko.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MenuDao {
    @Query("SELECT * FROM category ORDER BY sortOrder, name")
    fun observeCategories(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategory(row: CategoryEntity)

    @Delete
    suspend fun deleteCategory(row: CategoryEntity)

    @Query("SELECT * FROM menu_item ORDER BY name")
    fun observeItems(): Flow<List<MenuItemEntity>>

    @Query("SELECT * FROM menu_item WHERE id = :id")
    suspend fun getItem(id: String): MenuItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(row: MenuItemEntity)

    @Delete
    suspend fun deleteItem(row: MenuItemEntity)
}

@Dao
interface InventoryDao {
    @Query("SELECT * FROM inventory_item ORDER BY kind, name")
    fun observeItems(): Flow<List<InventoryItemEntity>>

    @Query("SELECT * FROM inventory_item WHERE id = :id")
    suspend fun getItem(id: String): InventoryItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertItem(row: InventoryItemEntity)

    @Delete
    suspend fun deleteItem(row: InventoryItemEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMovement(row: StockMovementEntity)

    @Query("SELECT * FROM stock_movement WHERE inventoryItemId = :itemId ORDER BY createdAt DESC")
    fun observeMovements(itemId: String): Flow<List<StockMovementEntity>>

    @Query("SELECT * FROM stock_movement WHERE sourceId = :sourceId")
    suspend fun movementsForSource(sourceId: String): List<StockMovementEntity>
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipe_line WHERE menuItemId = :menuItemId")
    fun observeLines(menuItemId: String): Flow<List<RecipeLineEntity>>

    @Query("SELECT * FROM recipe_line WHERE menuItemId = :menuItemId")
    suspend fun linesFor(menuItemId: String): List<RecipeLineEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLine(row: RecipeLineEntity)

    @Delete
    suspend fun deleteLine(row: RecipeLineEntity)
}

@Dao
interface ExpenseDao {
    @Query("SELECT * FROM expense WHERE periodMonth = :month ORDER BY name")
    fun observeForMonth(month: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expense WHERE periodMonth = :month")
    suspend fun forMonth(month: String): List<ExpenseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ExpenseEntity)

    @Delete
    suspend fun delete(row: ExpenseEntity)

    @Query("SELECT * FROM planned_volume WHERE periodMonth = :month")
    suspend fun plannedVolume(month: String): PlannedVolumeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPlannedVolume(row: PlannedVolumeEntity)
}

@Dao
interface SalesDao {
    @Query("SELECT * FROM sale ORDER BY createdAt DESC")
    fun observeSales(): Flow<List<SaleEntity>>

    @Query("SELECT * FROM sale WHERE id = :id")
    suspend fun getSale(id: String): SaleEntity?

    @Query("SELECT COUNT(*) FROM sale")
    suspend fun saleCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSale(row: SaleEntity)

    @Query("UPDATE sale SET status = :status WHERE id = :id")
    suspend fun setStatus(id: String, status: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLines(rows: List<SaleLineEntity>)

    @Query("SELECT * FROM sale_line WHERE saleId = :saleId")
    suspend fun linesFor(saleId: String): List<SaleLineEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertLedger(rows: List<LedgerEntryEntity>)

    @Query("SELECT * FROM ledger_entry WHERE saleId = :saleId")
    suspend fun ledgerFor(saleId: String): List<LedgerEntryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEvent(row: SaleEventEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRefund(row: RefundEntity)

    @Query("SELECT * FROM ledger_entry WHERE periodMonth = :month")
    fun observeLedgerForMonth(month: String): Flow<List<LedgerEntryEntity>>

    @Query("SELECT * FROM ledger_entry WHERE createdAt BETWEEN :from AND :to")
    suspend fun ledgerBetween(from: Long, to: Long): List<LedgerEntryEntity>

    @Query("SELECT * FROM sale WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt")
    suspend fun salesBetween(from: Long, to: Long): List<SaleEntity>

    @Query("SELECT * FROM sale_line WHERE saleId IN (SELECT id FROM sale WHERE createdAt BETWEEN :from AND :to)")
    suspend fun linesBetween(from: Long, to: Long): List<SaleLineEntity>
}

@Dao
interface PeriodDao {
    @Query("SELECT * FROM period WHERE month = :month")
    suspend fun get(month: String): PeriodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: PeriodEntity)
}
