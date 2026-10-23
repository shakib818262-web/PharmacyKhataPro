package com.shakib.pharmacykhata.data.repository

import androidx.room.withTransaction
import com.shakib.pharmacykhata.data.catalog.CatalogSuggestion
import com.shakib.pharmacykhata.data.local.AppDatabase
import com.shakib.pharmacykhata.data.local.entity.*
import com.shakib.pharmacykhata.domain.logic.Fefo
import com.shakib.pharmacykhata.domain.logic.LedgerCalculator
import com.shakib.pharmacykhata.domain.logic.SaleCalculator
import com.shakib.pharmacykhata.domain.model.*
import com.shakib.pharmacykhata.domain.money.Money
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

class InsufficientStockException(val medicineName: String, val available: Int) :
    Exception("Insufficient stock for $medicineName: $available available")
class DuplicateInvoiceException : Exception("This invoice number already exists for this supplier")

data class CartLine(val medicineId: Long, val medicineName: String, val quantity: Int, val unitSalePrice: Money)
data class PurchaseLineInput(
    val medicineId: Long?,              // null = create new medicine
    val newMedicine: CatalogSuggestion?, // used when medicineId == null
    val customName: String?,             // free-text new medicine when no catalog suggestion
    val batchNo: String, val expiryEpochDay: Long, val quantity: Int,
    val purchasePricePerUnit: Money, val salePricePerUnit: Money
)
data class ReportData(
    val salesCount: Int, val revenue: Money, val profit: Money,
    val totalDueGiven: Money, val stockValueAtCost: Money, val stockValueAtSalePrice: Money
)

@Singleton
class PharmacyRepository @Inject constructor(private val db: AppDatabase) {
    private val medicineDao = db.medicineDao()
    private val batchDao = db.batchDao()
    private val saleDao = db.saleDao()
    private val purchaseDao = db.purchaseDao()
    private val partyDao = db.partyDao()
    private val ledgerDao = db.ledgerDao()

    // ---------- Inventory ----------
    fun observeMedicines(pid: String): Flow<List<MedicineEntity>> = medicineDao.observeAll(pid)
    fun searchMedicines(pid: String, q: String): Flow<List<MedicineEntity>> = medicineDao.search(pid, q)
    fun observeBatches(pid: String): Flow<List<BatchEntity>> = batchDao.observeAll(pid)
    suspend fun batchesFor(pid: String, medicineId: Long): List<BatchEntity> = batchDao.forMedicine(pid, medicineId)
    suspend fun medicineByBarcode(pid: String, barcode: String): MedicineEntity? = medicineDao.byBarcode(pid, barcode)
    suspend fun medicineById(id: Long): MedicineEntity? = medicineDao.byId(id)

    suspend fun createMedicine(pid: String, s: CatalogSuggestion): Long {
        val existing = if (s.catalogId != null) medicineDao.byCatalogId(pid, s.catalogId) else null
        if (existing != null) return existing.id
        return medicineDao.insert(
            MedicineEntity(
                pharmacyId = pid, catalogId = s.catalogId, brandName = s.brandName,
                genericName = s.genericName, strength = s.strength, form = s.form,
                manufacturer = s.manufacturer, packSize = s.packSize,
                barcode = s.barcode, mrpPaisa = s.unitMrpPaisa
            )
        )
    }

    /** Add a batch of stock for a catalog suggestion (tap suggestion → fill batch/prices → stock in). */
    suspend fun addStock(
        pid: String, suggestion: CatalogSuggestion, batchNo: String, expiryEpochDay: Long,
        quantity: Int, purchasePrice: Money, salePrice: Money
    ): Long {
        require(quantity > 0) { "quantity must be positive" }
        require(purchasePrice >= Money.ZERO && salePrice >= Money.ZERO) { "prices cannot be negative" }
        val medicineId = createMedicine(pid, suggestion)
        return batchDao.insert(
            BatchEntity(pharmacyId = pid, medicineId = medicineId, batchNo = batchNo,
                expiryEpochDay = expiryEpochDay, quantity = quantity,
                purchasePricePaisa = purchasePrice.paisa, salePricePaisa = salePrice.paisa)
        )
    }

    suspend fun addStockForMedicine(
        pid: String, medicineId: Long, batchNo: String, expiryEpochDay: Long,
        quantity: Int, purchasePrice: Money, salePrice: Money
    ): Long {
        require(quantity > 0) { "quantity must be positive" }
        require(purchasePrice >= Money.ZERO && salePrice >= Money.ZERO) { "prices cannot be negative" }
        return batchDao.insert(
            BatchEntity(pharmacyId = pid, medicineId = medicineId, batchNo = batchNo,
                expiryEpochDay = expiryEpochDay, quantity = quantity,
                purchasePricePaisa = purchasePrice.paisa, salePricePaisa = salePrice.paisa)
        )
    }

    // ---------- POS ----------
    /**
     * Complete a sale atomically: FEFO-allocate and decrement batches, write the sale,
     * and (when there is a due) write the khata ledger entry — all in ONE transaction.
     * Stock is re-read inside the transaction, never from a stale cart snapshot.
     */
    suspend fun completeSale(
        pid: String, cart: List<CartLine>, discountFlat: Money?, discountPercent: Double?,
        paid: Money, method: PaymentMethod, customerId: Long?, newCustomerName: String?, newCustomerPhone: String?
    ): Sale {
        require(cart.isNotEmpty()) { "cart is empty" }
        return db.withTransaction {
            val today = java.time.LocalDate.now().toEpochDay()
            val items = mutableListOf<SaleItem>()
            val allocationsByLine = mutableListOf<Pair<Long, Int>>() // batchId to qty
            for (line in cart) {
                val batches = batchDao.forMedicine(pid, line.medicineId).map { it.toDomain() }
                when (val r = Fefo.allocate(batches, line.quantity, today)) {
                    is Fefo.Result.Insufficient -> throw InsufficientStockException(line.medicineName, r.available)
                    is Fefo.Result.Success -> {
                        for (a in r.allocations) {
                            val updated = batchDao.decrementIfAvailable(a.batch.id, a.quantity)
                            check(updated == 1) { "Stock changed concurrently for ${line.medicineName}" }
                            allocationsByLine += a.batch.id to a.quantity
                        }
                        items += SaleItem(line.medicineId, line.medicineName, line.quantity,
                            line.unitSalePrice, r.weightedCostPerUnit)
                    }
                }
            }
            val calc = SaleCalculator.calculate(items, discountPercent, discountFlat, paid)
            var effectiveCustomerId = customerId
            if (calc.due > Money.ZERO && effectiveCustomerId == null) {
                require(!newCustomerName.isNullOrBlank()) { "A customer is required to record due (বাকি)" }
                effectiveCustomerId = partyDao.insert(
                    PartyEntity(pharmacyId = pid, type = PartyType.CUSTOMER.name,
                        name = newCustomerName.trim(), phone = newCustomerPhone?.trim() ?: "")
                )
            }
            val now = System.currentTimeMillis()
            val customerName = effectiveCustomerId?.let { partyDao.byId(it)?.name } ?: newCustomerName ?: ""
            val invoiceNo = "INV-$now"
            val saleId = saleDao.insertSale(
                SaleEntity(pharmacyId = pid, invoiceNo = invoiceNo, customerId = effectiveCustomerId,
                    customerName = customerName, subtotalPaisa = calc.subtotal.paisa,
                    discountPaisa = calc.discount.paisa, totalPaisa = calc.total.paisa,
                    paidPaisa = calc.paidApplied.paisa, changePaisa = calc.change.paisa,
                    duePaisa = calc.due.paisa, paymentMethod = method.name, createdAt = now)
            )
            saleDao.insertItems(items.map {
                SaleItemEntity(saleId = saleId, medicineId = it.medicineId, medicineName = it.medicineName,
                    quantity = it.quantity, unitSalePricePaisa = it.unitSalePrice.paisa,
                    unitCostPricePaisa = it.unitCostPrice.paisa)
            })
            saleDao.insertAllocations(allocationsByLine.map { (batchId, qty) ->
                SaleAllocationEntity(saleId = saleId, batchId = batchId, quantity = qty)
            })
            if (calc.due > Money.ZERO && effectiveCustomerId != null) {
                ledgerDao.insert(
                    LedgerEntryEntity(pharmacyId = pid, partyId = effectiveCustomerId,
                        type = LedgerType.SALE_DUE.name, amountPaisa = calc.due.paisa,
                        paymentMethod = null, refId = saleId, note = "বিক্রি $invoiceNo", createdAt = now)
                )
            }
            Sale(id = saleId, pharmacyId = pid, invoiceNo = invoiceNo, customerId = effectiveCustomerId,
                customerName = customerName, items = items, subtotal = calc.subtotal, discount = calc.discount,
                total = calc.total, paid = calc.paidApplied, change = calc.change, due = calc.due,
                paymentMethod = method, createdAt = now)
        }
    }

    fun observeSales(pid: String): Flow<List<SaleEntity>> = saleDao.observeAll(pid)
    fun observeSalesBetween(pid: String, from: Long, to: Long): Flow<List<SaleEntity>> =
        saleDao.observeBetween(pid, from, to)
    suspend fun saleItems(saleId: Long): List<SaleItemEntity> = saleDao.itemsFor(saleId)

    // ---------- Purchases ----------
    suspend fun recordPurchase(
        pid: String, supplierId: Long, invoiceNo: String, lines: List<PurchaseLineInput>,
        paid: Money, method: PaymentMethod
    ): Purchase {
        require(lines.isNotEmpty()) { "purchase has no items" }
        require(invoiceNo.isNotBlank()) { "invoice number is required" }
        return db.withTransaction {
            val supplier = partyDao.byId(supplierId) ?: throw IllegalArgumentException("Supplier is required")
            require(supplier.pharmacyId == pid && supplier.type == PartyType.SUPPLIER.name) { "Invalid supplier" }
            if (purchaseDao.countByInvoice(pid, supplierId, invoiceNo.trim()) > 0) throw DuplicateInvoiceException()
            val now = System.currentTimeMillis()
            val total = lines.fold(Money.ZERO) { acc, l -> acc + (l.purchasePricePerUnit * l.quantity) }
            require(paid >= Money.ZERO && paid <= total) { "paid cannot exceed purchase total" }
            val due = total - paid
            val purchaseId = purchaseDao.insertPurchase(
                PurchaseEntity(pharmacyId = pid, invoiceNo = invoiceNo.trim(), supplierId = supplierId,
                    supplierName = supplier.name, totalPaisa = total.paisa, paidPaisa = paid.paisa,
                    duePaisa = due.paisa, paymentMethod = method.name, createdAt = now)
            )
            val itemEntities = mutableListOf<PurchaseItemEntity>()
            val domainItems = mutableListOf<PurchaseItem>()
            for (line in lines) {
                require(line.quantity > 0) { "quantity must be positive" }
                require(line.purchasePricePerUnit >= Money.ZERO && line.salePricePerUnit >= Money.ZERO) { "prices cannot be negative" }
                val medicineId = when {
                    line.medicineId != null -> line.medicineId
                    line.newMedicine != null -> createMedicine(pid, line.newMedicine)
                    !line.customName.isNullOrBlank() -> medicineDao.insert(
                        MedicineEntity(pharmacyId = pid, brandName = line.customName.trim(),
                            mrpPaisa = line.salePricePerUnit.paisa)
                    )
                    else -> throw IllegalArgumentException("Each purchase line needs a medicine")
                }
                // Buying updates the medicine's reference MRP to the latest sale price.
                medicineDao.byId(medicineId)?.let { med ->
                    if (med.mrpPaisa != line.salePricePerUnit.paisa) {
                        medicineDao.update(med.copy(mrpPaisa = line.salePricePerUnit.paisa))
                    }
                }
                val batchId = batchDao.insert(
                    BatchEntity(pharmacyId = pid, medicineId = medicineId, batchNo = line.batchNo,
                        expiryEpochDay = line.expiryEpochDay, quantity = line.quantity,
                        purchasePricePaisa = line.purchasePricePerUnit.paisa,
                        salePricePaisa = line.salePricePerUnit.paisa)
                )
                val name = medicineDao.byId(medicineId)?.brandName ?: line.customName ?: ""
                itemEntities += PurchaseItemEntity(purchaseId = purchaseId, medicineId = medicineId,
                    medicineName = name, batchId = batchId, quantity = line.quantity,
                    purchasePricePaisa = line.purchasePricePerUnit.paisa,
                    salePricePaisa = line.salePricePerUnit.paisa)
                domainItems += PurchaseItem(medicineId, name, line.batchNo, line.expiryEpochDay,
                    line.quantity, line.purchasePricePerUnit, line.salePricePerUnit)
            }
            purchaseDao.insertItems(itemEntities)
            if (due > Money.ZERO) {
                ledgerDao.insert(
                    LedgerEntryEntity(pharmacyId = pid, partyId = supplierId,
                        type = LedgerType.PURCHASE_DUE.name, amountPaisa = due.paisa,
                        refId = purchaseId, note = "ক্রয় ${invoiceNo.trim()}", createdAt = now)
                )
            }
            Purchase(id = purchaseId, pharmacyId = pid, invoiceNo = invoiceNo.trim(), supplierId = supplierId,
                supplierName = supplier.name, items = domainItems, total = total, paid = paid,
                due = due, paymentMethod = method, createdAt = now)
        }
    }

    fun observePurchases(pid: String): Flow<List<PurchaseEntity>> = purchaseDao.observeAll(pid)

    // ---------- Parties & Khata ----------
    fun observeParties(pid: String, type: PartyType): Flow<List<PartyEntity>> = partyDao.observeByType(pid, type.name)
    suspend fun addParty(pid: String, type: PartyType, name: String, phone: String, address: String): Long {
        require(name.isNotBlank()) { "name is required" }
        return partyDao.insert(PartyEntity(pharmacyId = pid, type = type.name, name = name.trim(),
            phone = phone.trim(), address = address.trim()))
    }
    fun observeLedger(pid: String, partyId: Long): Flow<List<LedgerEntryEntity>> = ledgerDao.observeForParty(pid, partyId)
    fun observeAllLedger(pid: String): Flow<List<LedgerEntryEntity>> = ledgerDao.observeAll(pid)
    suspend fun ledgerFor(pid: String, partyId: Long): List<LedgerEntry> =
        ledgerDao.forParty(pid, partyId).map { it.toDomain() }
    suspend fun balanceFor(pid: String, partyId: Long): Money =
        LedgerCalculator.balance(ledgerFor(pid, partyId))

    /** Returns the advance (overpayment credit) portion of this payment, if any. */
    suspend fun recordPayment(pid: String, party: PartyEntity, amount: Money, method: PaymentMethod, note: String = ""): Money {
        require(amount > Money.ZERO) { "payment must be positive" }
        val balance = balanceFor(pid, party.id)
        val (_, advance) = LedgerCalculator.settle(balance, amount)
        val type = if (party.type == PartyType.CUSTOMER.name) LedgerType.SALE_PAYMENT else LedgerType.PURCHASE_PAYMENT
        ledgerDao.insert(
            LedgerEntryEntity(pharmacyId = pid, partyId = party.id, type = type.name,
                amountPaisa = -amount.paisa, paymentMethod = method.name,
                note = note.ifBlank { if (advance > Money.ZERO) "পেমেন্ট (অগ্রিমসহ)" else "পেমেন্ট" },
                createdAt = System.currentTimeMillis())
        )
        return advance
    }

    // ---------- Reports / backup ----------
    suspend fun report(pid: String, salesInPeriod: List<SaleEntity>, batches: List<BatchEntity>): ReportData {
        var revenue = Money.ZERO; var profit = Money.ZERO; var due = Money.ZERO
        for (s in salesInPeriod) {
            revenue += Money(s.totalPaisa)
            due += Money(s.duePaisa)
            val items = saleDao.itemsFor(s.id)
            val cost = items.fold(Money.ZERO) { acc, i -> acc + Money(i.unitCostPricePaisa) * i.quantity }
            profit += Money(s.totalPaisa) - cost // losses included honestly
        }
        val stockCost = batches.fold(Money.ZERO) { acc, b -> acc + Money(b.purchasePricePaisa) * b.quantity }
        val stockSale = batches.fold(Money.ZERO) { acc, b -> acc + Money(b.salePricePaisa) * b.quantity }
        return ReportData(salesInPeriod.size, revenue, profit, due, stockCost, stockSale)
    }

    /** Versioned JSON backup export of this pharmacy's data only. */
    suspend fun exportBackupJson(pid: String): String {
        val root = JSONObject()
        root.put("app", "PharmacyKhata Pro")
        root.put("version", 1)
        root.put("pharmacyId", pid)
        root.put("exportedAt", System.currentTimeMillis())
        root.put("note", "Medicines, batches and sales (with items) for this pharmacy. Restore/import is planned; see README.")
        val meds = JSONArray()
        val medicineList = snapshotMedicines(pid)
        for (m in medicineList) meds.put(JSONObject()
            .put("id", m.id).put("brandName", m.brandName).put("genericName", m.genericName)
            .put("strength", m.strength).put("form", m.form).put("manufacturer", m.manufacturer)
            .put("packSize", m.packSize).put("barcode", m.barcode ?: JSONObject.NULL).put("mrpPaisa", m.mrpPaisa))
        root.put("medicines", meds)
        val batchArr = JSONArray()
        for (b in snapshotBatches(pid)) batchArr.put(JSONObject()
            .put("id", b.id).put("medicineId", b.medicineId).put("batchNo", b.batchNo)
            .put("expiryEpochDay", b.expiryEpochDay).put("quantity", b.quantity)
            .put("purchasePricePaisa", b.purchasePricePaisa).put("salePricePaisa", b.salePricePaisa))
        root.put("batches", batchArr)
        val salesArr = JSONArray()
        for (s in snapshotSales(pid)) {
            val o = JSONObject().put("id", s.id).put("invoiceNo", s.invoiceNo)
                .put("customerId", s.customerId ?: JSONObject.NULL).put("customerName", s.customerName)
                .put("subtotalPaisa", s.subtotalPaisa).put("discountPaisa", s.discountPaisa)
                .put("totalPaisa", s.totalPaisa).put("paidPaisa", s.paidPaisa).put("changePaisa", s.changePaisa)
                .put("duePaisa", s.duePaisa).put("paymentMethod", s.paymentMethod).put("createdAt", s.createdAt)
            val itemsArr = JSONArray()
            for (i in saleDao.itemsFor(s.id)) itemsArr.put(JSONObject()
                .put("medicineId", i.medicineId).put("medicineName", i.medicineName).put("quantity", i.quantity)
                .put("unitSalePricePaisa", i.unitSalePricePaisa).put("unitCostPricePaisa", i.unitCostPricePaisa))
            o.put("items", itemsArr)
            salesArr.put(o)
        }
        root.put("sales", salesArr)
        return root.toString(2)
    }

    private suspend fun snapshotMedicines(pid: String): List<MedicineEntity> =
        medicineDao.observeAll(pid).first()
    private suspend fun snapshotBatches(pid: String): List<BatchEntity> =
        batchDao.observeAll(pid).first()
    private suspend fun snapshotSales(pid: String): List<SaleEntity> =
        saleDao.observeAll(pid).first()
}

// ---------- entity <-> domain mappers ----------
fun BatchEntity.toDomain() = Batch(id = id, pharmacyId = pharmacyId, medicineId = medicineId, batchNo = batchNo,
    expiryEpochDay = expiryEpochDay, quantity = quantity,
    purchasePricePerUnit = Money(purchasePricePaisa), salePricePerUnit = Money(salePricePaisa))

fun LedgerEntryEntity.toDomain() = LedgerEntry(id = id, pharmacyId = pharmacyId, partyId = partyId,
    type = runCatching { LedgerType.valueOf(type) }.getOrDefault(LedgerType.SALE_DUE),
    amount = Money(amountPaisa),
    paymentMethod = paymentMethod?.let { runCatching { PaymentMethod.valueOf(it) }.getOrNull() },
    refId = refId, note = note, createdAt = createdAt)

fun MedicineEntity.displayName(): String =
    listOf(brandName, strength).filter { it.isNotBlank() }.joinToString(" ")
