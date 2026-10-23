package com.shakib.pharmacykhata.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.shakib.pharmacykhata.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MedicineDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(m: MedicineEntity): Long
    @Update suspend fun update(m: MedicineEntity)
    @Query("SELECT * FROM medicines WHERE pharmacyId = :pid ORDER BY brandName")
    fun observeAll(pid: String): Flow<List<MedicineEntity>>
    @Query("SELECT * FROM medicines WHERE pharmacyId = :pid AND (brandName LIKE '%' || :q || '%' OR genericName LIKE '%' || :q || '%' OR barcode = :q) ORDER BY brandName LIMIT 50")
    fun search(pid: String, q: String): Flow<List<MedicineEntity>>
    @Query("SELECT * FROM medicines WHERE pharmacyId = :pid AND barcode = :barcode LIMIT 1")
    suspend fun byBarcode(pid: String, barcode: String): MedicineEntity?
    @Query("SELECT * FROM medicines WHERE id = :id") suspend fun byId(id: Long): MedicineEntity?
    @Query("SELECT * FROM medicines WHERE pharmacyId = :pid AND catalogId = :catalogId LIMIT 1")
    suspend fun byCatalogId(pid: String, catalogId: Long): MedicineEntity?
}

@Dao
interface BatchDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(b: BatchEntity): Long
    @Update suspend fun update(b: BatchEntity)
    @Query("SELECT * FROM batches WHERE pharmacyId = :pid AND medicineId = :medicineId ORDER BY expiryEpochDay ASC")
    suspend fun forMedicine(pid: String, medicineId: Long): List<BatchEntity>
    @Query("SELECT * FROM batches WHERE pharmacyId = :pid ORDER BY expiryEpochDay ASC")
    fun observeAll(pid: String): Flow<List<BatchEntity>>
    @Query("SELECT COALESCE(SUM(quantity),0) FROM batches WHERE pharmacyId = :pid AND medicineId = :medicineId")
    fun observeStock(pid: String, medicineId: Long): Flow<Int>
    @Query("UPDATE batches SET quantity = quantity - :qty WHERE id = :batchId AND quantity >= :qty")
    suspend fun decrementIfAvailable(batchId: Long, qty: Int): Int
}

@Dao
interface SaleDao {
    @Insert suspend fun insertSale(s: SaleEntity): Long
    @Insert suspend fun insertItems(items: List<SaleItemEntity>)
    @Insert suspend fun insertAllocations(a: List<SaleAllocationEntity>)
    @Query("SELECT * FROM sales WHERE pharmacyId = :pid ORDER BY createdAt DESC")
    fun observeAll(pid: String): Flow<List<SaleEntity>>
    @Query("SELECT * FROM sales WHERE pharmacyId = :pid AND createdAt >= :from AND createdAt < :to ORDER BY createdAt DESC")
    fun observeBetween(pid: String, from: Long, to: Long): Flow<List<SaleEntity>>
    @Query("SELECT * FROM sale_items WHERE saleId = :saleId")
    suspend fun itemsFor(saleId: Long): List<SaleItemEntity>
    @Query("SELECT COUNT(*) FROM sales WHERE pharmacyId = :pid")
    suspend fun count(pid: String): Int
}

@Dao
interface PurchaseDao {
    @Insert suspend fun insertPurchase(p: PurchaseEntity): Long
    @Insert suspend fun insertItems(items: List<PurchaseItemEntity>)
    @Query("SELECT * FROM purchases WHERE pharmacyId = :pid ORDER BY createdAt DESC")
    fun observeAll(pid: String): Flow<List<PurchaseEntity>>
    @Query("SELECT COUNT(*) FROM purchases WHERE pharmacyId = :pid AND supplierId = :supplierId AND invoiceNo = :invoiceNo")
    suspend fun countByInvoice(pid: String, supplierId: Long, invoiceNo: String): Int
}

@Dao
interface PartyDao {
    @Insert suspend fun insert(p: PartyEntity): Long
    @Update suspend fun update(p: PartyEntity)
    @Query("SELECT * FROM parties WHERE pharmacyId = :pid AND type = :type ORDER BY name")
    fun observeByType(pid: String, type: String): Flow<List<PartyEntity>>
    @Query("SELECT * FROM parties WHERE id = :id") suspend fun byId(id: Long): PartyEntity?
}

@Dao
interface LedgerDao {
    @Insert suspend fun insert(e: LedgerEntryEntity): Long
    @Query("SELECT * FROM ledger_entries WHERE pharmacyId = :pid AND partyId = :partyId ORDER BY createdAt ASC")
    fun observeForParty(pid: String, partyId: Long): Flow<List<LedgerEntryEntity>>
    @Query("SELECT * FROM ledger_entries WHERE pharmacyId = :pid AND partyId = :partyId ORDER BY createdAt ASC")
    suspend fun forParty(pid: String, partyId: Long): List<LedgerEntryEntity>
    @Query("SELECT * FROM ledger_entries WHERE pharmacyId = :pid ORDER BY createdAt DESC")
    fun observeAll(pid: String): Flow<List<LedgerEntryEntity>>
}
