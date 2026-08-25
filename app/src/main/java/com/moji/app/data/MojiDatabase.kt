package com.moji.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TransactionEntity::class,
        CategoryEntity::class,
        CaptureEventEntity::class,
        TransactionCandidateEntity::class,
        RefundLinkEntity::class,
        MerchantRuleEntity::class,
        BudgetEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class MojiDatabase : RoomDatabase() {
    abstract fun dao(): MojiDao

    companion object {
        fun create(context: Context): MojiDatabase = Room.databaseBuilder(
            context.applicationContext,
            MojiDatabase::class.java,
            "moji.db"
        ).build()
    }
}
