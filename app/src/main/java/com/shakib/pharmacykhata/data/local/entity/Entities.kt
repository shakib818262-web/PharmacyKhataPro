package com.shakib.pharmacykhata.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Every table carries pharmacyId — every query is tenant-scoped. Money columns are paisa (Long). */

@Entity(tableName = "medicines", indices = [Index("pharmacyId"), Index("brandName")])
data class MedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val catalogId: Long? = null,
    val brandName: String,
    val genericName: String = "",
    val strength: String = "",
    val form: String = "",
    val manufacturer: String = "",
    val packSize: Int = 1,
    val barcode: String? = null,
    val mrpPaisa: Long = 0
)

@Entity(tableName = "batches", indices = [Index("pharmacyId"), Index("medicineId"), Index("expiryEpochDay")])
data class BatchEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val medicineId: Long,
    val batchNo: String = "",
    val expiryEpochDay: Long,
    val quantity: Int,
    val purchasePricePaisa: Long,
    val salePricePaisa: Long
)

@Entity(tableName = "sales", indices = [Index("pharmacyId"), Index("createdAt")])
data class SaleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val invoiceNo: String,
    val customerId: Long? = null,
    val customerName: String = "",
    val subtotalPaisa: Long,
    val discountPaisa: Long = 0,
    val totalPaisa: Long,
    val paidPaisa: Long,
    val changePaisa: Long = 0,
    val duePaisa: Long = 0,
    val paymentMethod: String = "CASH",
    val createdAt: Long
)

@Entity(tableName = "sale_items", indices = [Index("saleId"), Index("medicineId")])
data class SaleItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val medicineId: Long,
    val medicineName: String,
    val quantity: Int,
    val unitSalePricePaisa: Long,
    val unitCostPricePaisa: Long
)

/** Which batches a sale consumed (FEFO audit trail) and at what cost. */
@Entity(tableName = "sale_allocations", indices = [Index("saleId"), Index("batchId")])
data class SaleAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val saleId: Long,
    val batchId: Long,
    val quantity: Int
)

@Entity(tableName = "purchases", indices = [Index("pharmacyId"), Index("supplierId"), Index("invoiceNo")])
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val invoiceNo: String,
    val supplierId: Long,
    val supplierName: String,
    val totalPaisa: Long,
    val paidPaisa: Long,
    val duePaisa: Long = 0,
    val paymentMethod: String = "CASH",
    val createdAt: Long
)

@Entity(tableName = "purchase_items", indices = [Index("purchaseId"), Index("medicineId")])
data class PurchaseItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val medicineId: Long,
    val medicineName: String,
    val batchId: Long,
    val quantity: Int,
    val purchasePricePaisa: Long,
    val salePricePaisa: Long
)

@Entity(tableName = "parties", indices = [Index("pharmacyId"), Index("type")])
data class PartyEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val type: String, // CUSTOMER | SUPPLIER
    val name: String,
    val phone: String = "",
    val address: String = ""
)

@Entity(tableName = "ledger_entries", indices = [Index("pharmacyId"), Index("partyId"), Index("createdAt")])
data class LedgerEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val partyId: Long,
    val type: String, // LedgerType name
    val amountPaisa: Long, // signed
    val paymentMethod: String? = null,
    val refId: Long? = null,
    val note: String = "",
    val createdAt: Long
)
