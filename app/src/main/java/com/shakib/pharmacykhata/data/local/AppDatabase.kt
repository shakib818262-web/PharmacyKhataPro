package com.shakib.pharmacykhata.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.shakib.pharmacykhata.data.local.dao.*
import com.shakib.pharmacykhata.data.local.entity.*

@Database(
    entities = [
        MedicineEntity::class, BatchEntity::class,
        SaleEntity::class, SaleItemEntity::class, SaleAllocationEntity::class,
        PurchaseEntity::class, PurchaseItemEntity::class,
        PartyEntity::class, LedgerEntryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun medicineDao(): MedicineDao
    abstract fun batchDao(): BatchDao
    abstract fun saleDao(): SaleDao
    abstract fun purchaseDao(): PurchaseDao
    abstract fun partyDao(): PartyDao
    abstract fun ledgerDao(): LedgerDao

    companion object { const val DB_NAME = "pharmacy_pro.db" }
}
