package com.shakib.pharmacykhata.domain.logic

import com.shakib.pharmacykhata.domain.model.Batch
import com.shakib.pharmacykhata.domain.model.LedgerEntry
import com.shakib.pharmacykhata.domain.model.SaleItem
import com.shakib.pharmacykhata.domain.money.Money

object Fefo {
    data class Allocation(val batch: Batch, val quantity: Int)

    sealed interface Result {
        data class Success(val allocations: List<Allocation>, val weightedCostPerUnit: Money) : Result
        data class Insufficient(val available: Int) : Result
    }

    /**
     * First-Expiry-First-Out allocation. Expired batches are NEVER allocated.
     * Batches are considered in ascending expiry order regardless of input order.
     */
    fun allocate(batches: List<Batch>, requestedQty: Int, todayEpochDay: Long): Result {
        require(requestedQty > 0) { "quantity must be positive" }
        val usable = batches
            .filter { it.quantity > 0 && !it.isExpired(todayEpochDay) }
            .sortedBy { it.expiryEpochDay }
        val available = usable.sumOf { it.quantity }
        if (available < requestedQty) return Result.Insufficient(available)
        var remaining = requestedQty
        var costTotal = Money.ZERO
        val allocations = mutableListOf<Allocation>()
        for (batch in usable) {
            if (remaining == 0) break
            val take = minOf(batch.quantity, remaining)
            allocations += Allocation(batch, take)
            costTotal += batch.purchasePricePerUnit * take
            remaining -= take
        }
        val weighted = Money(costTotal.paisa / requestedQty)
        return Result.Success(allocations, weighted)
    }
}

object SaleCalculator {
    data class Result(
        val subtotal: Money,
        val discount: Money,
        val total: Money,
        val paidApplied: Money,
        val change: Money,
        val due: Money,
        val costTotal: Money,
        val profit: Money // may be negative — losses are shown honestly, never floored at 0
    )

    /**
     * @param discountPercent 0..100, or [discountFlat] flat amount; flat wins if both given.
     * Discount is clamped to the subtotal (never negative total, never negative discount).
     * Overpayment becomes change (returned to customer), never silently kept or written as due.
     */
    fun calculate(
        items: List<SaleItem>,
        discountPercent: Double? = null,
        discountFlat: Money? = null,
        paid: Money
    ): Result {
        require(items.isNotEmpty()) { "sale must have at least one item" }
        require(items.all { it.quantity > 0 }) { "item quantities must be positive" }
        require(paid >= Money.ZERO) { "paid cannot be negative" }
        val subtotal = items.fold(Money.ZERO) { acc, i -> acc + i.lineTotal }
        val costTotal = items.fold(Money.ZERO) { acc, i -> acc + i.lineCost }
        val rawDiscount = when {
            discountFlat != null -> discountFlat
            discountPercent != null -> {
                val pct = discountPercent.coerceIn(0.0, 100.0)
                Money(Math.round(subtotal.paisa * pct / 100.0))
            }
            else -> Money.ZERO
        }
        val discount = rawDiscount.coerceIn(Money.ZERO, subtotal)
        val total = subtotal - discount
        val change = if (paid > total) paid - total else Money.ZERO
        val due = if (paid < total) total - paid else Money.ZERO
        val paidApplied = paid - change
        return Result(
            subtotal = subtotal,
            discount = discount,
            total = total,
            paidApplied = paidApplied,
            change = change,
            due = due,
            costTotal = costTotal,
            profit = total - costTotal
        )
    }
}

object LedgerCalculator {
    /** Balance derived from entries (source of truth). Negative balance = advance/credit in party's favour. */
    fun balance(entries: List<LedgerEntry>): Money =
        entries.fold(Money.ZERO) { acc, e -> acc + e.amount }

    /**
     * When a payment exceeds the outstanding balance, only the outstanding part settles
     * the due; the remainder is the party's advance credit (it must NOT be written as
     * extra cash gain the way the old app did).
     */
    fun settle(balance: Money, payment: Money): Pair<Money, Money> {
        require(payment > Money.ZERO) { "payment must be positive" }
        val outstanding = if (balance > Money.ZERO) balance else Money.ZERO
        val settled = minOf(outstanding, payment)
        val advance = payment - settled
        return settled to advance
    }
}
