package com.example.chikko.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        MenuItemEntity::class,
        InventoryItemEntity::class,
        RecipeLineEntity::class,
        ExpenseEntity::class,
        PlannedVolumeEntity::class,
        PeriodEntity::class,
        StockMovementEntity::class,
        SaleEntity::class,
        SaleLineEntity::class,
        LedgerEntryEntity::class,
        SaleEventEntity::class,
        RefundEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun menuDao(): MenuDao
    abstract fun inventoryDao(): InventoryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun salesDao(): SalesDao
    abstract fun periodDao(): PeriodDao

    companion object {
        /** Triggers that make ledger tables append-only at the database level. */
        private val LEDGER_TRIGGERS = listOf(
            "stock_movement", "ledger_entry", "sale_event", "refund", "sale_line",
        ).flatMap { table ->
            listOf(
                "CREATE TRIGGER IF NOT EXISTS ${table}_no_update BEFORE UPDATE ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'ledger is append-only: $table'); END",
                "CREATE TRIGGER IF NOT EXISTS ${table}_no_delete BEFORE DELETE ON $table " +
                    "BEGIN SELECT RAISE(ABORT, 'ledger is append-only: $table'); END",
            )
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "chikko.db")
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        LEDGER_TRIGGERS.forEach { db.execSQL(it) }
                    }
                })
                .build()
    }
}
