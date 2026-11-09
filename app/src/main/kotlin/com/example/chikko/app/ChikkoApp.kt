package com.example.chikko.app

import android.app.Application
import com.example.chikko.data.ChikkoRepository
import com.example.chikko.data.db.AppDatabase

/** Manual dependency container (plan section 2.2: no Hilt). */
class ChikkoApp : Application() {
    val db: AppDatabase by lazy { AppDatabase.create(this) }
    val repo: ChikkoRepository by lazy { ChikkoRepository(db) }
}
