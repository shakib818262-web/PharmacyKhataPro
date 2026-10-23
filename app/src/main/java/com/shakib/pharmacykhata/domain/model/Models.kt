package com.shakib.pharmacykhata.domain.model

import com.shakib.pharmacykhata.domain.money.Money

/** All timestamps are epoch millis. All money is integer paisa via [Money]. */

enum class SubscriptionStatus { TRIAL, ACTIVE, EXPIRED }

enum class PaymentMethod { CASH, BKASH, NAGAD, CARD, DUE }

enum class PartyType { CUSTOMER, SUPPLIER }

enum class LedgerType {
    SALE_DUE,        // customer bought on credit  (+balance owed to pharmacy)
    SALE_PAYMENT,    // customer paid against due  (-balance)
    PURCHASE_DUE,    // pharmacy bought on credit (+balance pharmacy owes supplier)
    PURCHASE_PAYMENT // pharmacy paid supplier    (-balance)
}

data class Subscription(
    val status: SubscriptionStatus = SubscriptionStatus.TRIAL,
    val plan: String = "TRIAL",
    /** Server-written (Firestore serverTimestamp mirrored as millis); device clock cannot extend it. */
    val trialEndsAt: Long = 0L,
    val expiresAt: Long = 0L
) {
    fun effectiveStatus(now: Long): SubscriptionStatus = when (status) {
        SubscriptionStatus.TRIAL -> if (trialEndsAt > 0L && trialEndsAt < now) SubscriptionStatus.EXPIRED else SubscriptionStatus.TRIAL
        SubscriptionStatus.ACTIVE -> if (expiresAt > 0L && expiresAt < now) SubscriptionStatus.EXPIRED else SubscriptionStatus.ACTIVE
        SubscriptionStatus.EXPIRED -> SubscriptionStatus.EXPIRED
    }
    fun canSell(now: Long): Boolean = effectiveStatus(now) != SubscriptionStatus.EXPIRED
}

data class PharmacyProfile(
    val uid: String,
    val pharmacyId: String,
    val pharmacyName: String,
    val ownerName: String,
    val phone: String,
    val email: String,
    val address: String,
    val district: String,
    val drugLicenceNo: String? = null,
    val createdAt: Long = 0L,
    val lastActiveAt: Long = 0L,
    val subscription: Subscription = Subscription()
)

data class Medicine(
    val id: Long = 0,
    val pharmacyId: String,
    val catalogId: Long? = null,
    val brandName: String,
    val genericName: String = "",
    val strength: String = "",
    val form: String = "",
    val manufacturer: String = "",
    val packSize: Int = 1,
    val barcode: String? = null,
    /** Reference MRP per unit, from the catalog. The pharmacy's own sale price lives on batches/sale items. */
    val mrpPerUnit: Money = Money.ZERO
)

data class Batch(
    val id: Long = 0,
    val pharmacyId: String,
    val medicineId: Long,
    val batchNo: String = "",
    /** Expiry as epoch-day for easy comparison/sorting (FEFO). */
    val expiryEpochDay: Long,
    val quantity: Int,
    val purchasePricePerUnit: Money,
    val salePricePerUnit: Money
) {
    fun isExpired(todayEpochDay: Long): Boolean = expiryEpochDay < todayEpochDay
    fun daysUntilExpiry(todayEpochDay: Long): Long = expiryEpochDay - todayEpochDay
}

data class SaleItem(
    val medicineId: Long,
    val medicineName: String,
    val quantity: Int,
    val unitSalePrice: Money,
    val unitCostPrice: Money // weighted cost of allocated batches, for honest profit (losses allowed)
) {
    val lineTotal: Money get() = unitSalePrice * quantity
    val lineCost: Money get() = unitCostPrice * quantity
}

data class Sale(
    val id: Long = 0,
    val pharmacyId: String,
    val invoiceNo: String,
    val customerId: Long? = null,
    val customerName: String = "",
    val items: List<SaleItem> = emptyList(),
    val subtotal: Money,
    val discount: Money = Money.ZERO,
    val total: Money,
    val paid: Money,
    val change: Money = Money.ZERO,
    val due: Money = Money.ZERO,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val createdAt: Long
) {
    val profit: Money get() = total - items.fold(Money.ZERO) { acc, i -> acc + i.lineCost }
}

data class PurchaseItem(
    val medicineId: Long,
    val medicineName: String,
    val batchNo: String = "",
    val expiryEpochDay: Long,
    val quantity: Int,
    val purchasePricePerUnit: Money,
    val salePricePerUnit: Money
) {
    val lineTotal: Money get() = purchasePricePerUnit * quantity
}

data class Purchase(
    val id: Long = 0,
    val pharmacyId: String,
    val invoiceNo: String,
    val supplierId: Long,
    val supplierName: String,
    val items: List<PurchaseItem> = emptyList(),
    val total: Money,
    val paid: Money,
    val due: Money = Money.ZERO,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val createdAt: Long
)

data class Party(
    val id: Long = 0,
    val pharmacyId: String,
    val type: PartyType,
    val name: String,
    val phone: String = "",
    val address: String = ""
)

/**
 * Ledger is the SOURCE OF TRUTH for khata balances: a party's balance is derived by
 * summing its entries, never stored as a mutable running total on the party row.
 * Sign convention: positive = party owes pharmacy (customers) / pharmacy owes party is
 * represented the same way from the pharmacy's perspective for suppliers:
 *   customer: SALE_DUE +, SALE_PAYMENT -
 *   supplier: PURCHASE_DUE +, PURCHASE_PAYMENT -
 */
data class LedgerEntry(
    val id: Long = 0,
    val pharmacyId: String,
    val partyId: Long,
    val type: LedgerType,
    val amount: Money, // signed
    val paymentMethod: PaymentMethod? = null,
    val refId: Long? = null, // sale/purchase id when applicable
    val note: String = "",
    val createdAt: Long
)
