package com.shakib.pharmacykhata

import com.shakib.pharmacykhata.domain.logic.Fefo
import com.shakib.pharmacykhata.domain.logic.LedgerCalculator
import com.shakib.pharmacykhata.domain.logic.SaleCalculator
import com.shakib.pharmacykhata.domain.model.Batch
import com.shakib.pharmacykhata.domain.model.LedgerEntry
import com.shakib.pharmacykhata.domain.model.LedgerType
import com.shakib.pharmacykhata.domain.model.SaleItem
import com.shakib.pharmacykhata.domain.money.Money
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CalculatorsTest {
    private fun item(price: Long, cost: Long, qty: Int) =
        SaleItem(medicineId = 1, medicineName = "Napa 500mg", quantity = qty,
            unitSalePrice = Money(price), unitCostPrice = Money(cost))

    @Test fun partialPayment_350bill_200paid_150due() {
        val r = SaleCalculator.calculate(listOf(item(35000, 20000, 1)), paid = Money(20000))
        assertEquals(Money(35000), r.total)
        assertEquals(Money(20000), r.paidApplied)
        assertEquals(Money(15000), r.due)   // ৳150 due — the core khata scenario the old app could not record
        assertEquals(Money.ZERO, r.change)
        assertEquals(Money(15000), r.profit)
    }

    @Test fun overpayment_returnsChange() {
        val r = SaleCalculator.calculate(listOf(item(35000, 20000, 1)), paid = Money(50000))
        assertEquals(Money(15000), r.change) // ৳150 handed back, not booked as revenue
        assertEquals(Money.ZERO, r.due)
        assertEquals(Money(35000), r.paidApplied)
    }

    @Test fun discount_clampedToSubtotal() {
        val r = SaleCalculator.calculate(listOf(item(10000, 5000, 1)), discountFlat = Money(9999999), paid = Money.ZERO)
        assertEquals(Money(10000), r.discount)
        assertEquals(Money.ZERO, r.total)
        val neg = SaleCalculator.calculate(listOf(item(10000, 5000, 1)), discountFlat = Money(-500), paid = Money.ZERO)
        assertEquals(Money.ZERO, neg.discount) // negative discount must not inflate the bill
    }

    @Test fun percentDiscount_roundsToPaisa() {
        val r = SaleCalculator.calculate(listOf(item(9999, 5000, 1)), discountPercent = 10.0, paid = Money.ZERO)
        assertEquals(Money(1000), r.discount) // 10% of ৳99.99 = ৳10.00
        assertEquals(Money(8999), r.total)
    }

    @Test fun loss_isShownNotFloored() {
        val r = SaleCalculator.calculate(listOf(item(8000, 10000, 1)), paid = Money(8000))
        assertEquals(Money(-2000), r.profit) // sold below cost: -৳20 loss, honestly reported
    }

    // ---- FEFO ----
    private val today = LocalDate.of(2026, 10, 23).toEpochDay()
    private fun batch(id: Long, expiryDay: Long, qty: Int, cost: Long = 100) = Batch(
        id = id, pharmacyId = "p1", medicineId = 1, expiryEpochDay = expiryDay,
        quantity = qty, purchasePricePerUnit = Money(cost), salePricePerUnit = Money(200))

    @Test fun fefo_earliestExpiryFirst_regardlessOfInputOrder() {
        val later = batch(1, today + 300, 10)
        val sooner = batch(2, today + 30, 10)
        val r = Fefo.allocate(listOf(later, sooner), 12, today) as Fefo.Result.Success
        assertEquals(2L, r.allocations[0].batch.id); assertEquals(10, r.allocations[0].quantity)
        assertEquals(1L, r.allocations[1].batch.id); assertEquals(2, r.allocations[1].quantity)
    }

    @Test fun fefo_neverAllocatesExpired_andReportsInsufficient() {
        val expired = batch(1, today - 1, 50)
        val good = batch(2, today + 10, 5)
        val r = Fefo.allocate(listOf(expired, good), 6, today)
        assertTrue(r is Fefo.Result.Insufficient)
        assertEquals(5, (r as Fefo.Result.Insufficient).available)
    }

    @Test fun fefo_weightedCost() {
        val a = batch(1, today + 10, 5, cost = 100)
        val b = batch(2, today + 20, 5, cost = 300)
        val r = Fefo.allocate(listOf(a, b), 10, today) as Fefo.Result.Success
        assertEquals(Money(200), r.weightedCostPerUnit)
    }

    // ---- Ledger ----
    private fun entry(type: LedgerType, paisa: Long) = LedgerEntry(
        pharmacyId = "p1", partyId = 1, type = type, amount = Money(paisa), createdAt = 0)

    @Test fun ledger_balanceFromEntries() {
        val entries = listOf(
            entry(LedgerType.SALE_DUE, 15000),
            entry(LedgerType.SALE_DUE, 10000),
            entry(LedgerType.SALE_PAYMENT, -20000))
        assertEquals(Money(5000), LedgerCalculator.balance(entries))
    }

    @Test fun ledger_overpayment_becomesAdvanceNotCashGain() {
        val (settled, advance) = LedgerCalculator.settle(Money(15000), Money(20000))
        assertEquals(Money(15000), settled)  // only ৳150 settles the due
        assertEquals(Money(5000), advance)   // ৳50 is the customer's credit, not pharmacy income
    }

    @Test fun ledger_paymentWithNoDue_isAllAdvance() {
        val (settled, advance) = LedgerCalculator.settle(Money.ZERO, Money(10000))
        assertEquals(Money.ZERO, settled)
        assertEquals(Money(10000), advance)
    }
}
