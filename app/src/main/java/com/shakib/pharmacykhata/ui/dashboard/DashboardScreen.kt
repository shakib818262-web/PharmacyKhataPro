package com.shakib.pharmacykhata.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakib.pharmacykhata.data.local.entity.BatchEntity
import com.shakib.pharmacykhata.data.local.entity.MedicineEntity
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.model.PartyType
import com.shakib.pharmacykhata.domain.model.PaymentMethod
import com.shakib.pharmacykhata.domain.money.Money
import com.shakib.pharmacykhata.ui.common.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject

enum class AlertFilter { ALL, LOW_STOCK, NEAR_EXPIRY, EXPIRED }

data class DashboardUiState(
    val todaySales: Money = Money.ZERO,
    val todayProfit: Money = Money.ZERO,
    val todayCount: Int = 0,
    val cashByMethod: Map<PaymentMethod, Money> = emptyMap(),
    val totalCustomerDue: Money = Money.ZERO,
    val lowStock: List<Pair<MedicineEntity, Int>> = emptyList(),
    val nearExpiry: List<Pair<MedicineEntity, BatchEntity>> = emptyList(),
    val expired: List<Pair<MedicineEntity, BatchEntity>> = emptyList()
)

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repo: PharmacyRepository,
    private val session: SessionStore
) : ViewModel() {
    private val pid: String = session.pharmacyId ?: ""
    private val _state = MutableStateFlow(DashboardUiState())
    val state = _state.asStateFlow()
    val pharmacyName: String get() = session.pharmacyName
    val banner: String? get() = subscriptionBannerText(session.subscriptionStatus, session.trialEndsAt, session.expiresAt)

    init { if (pid.isNotBlank()) load() }

    private fun load() {
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val end = LocalDate.now().atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()
        viewModelScope.launch {
            combine(
                repo.observeSalesBetween(pid, start, end),
                repo.observeMedicines(pid),
                repo.observeBatches(pid),
                repo.observeAllLedger(pid),
                repo.observeParties(pid, PartyType.CUSTOMER)
            ) { sales, meds, batches, ledger, customers -> Quint(sales, meds, batches, ledger, customers) }
                .collect { (sales, meds, batches, ledger, customers) ->
                    var profit = Money.ZERO
                    for (s in sales) {
                        val cost = repo.saleItems(s.id).fold(Money.ZERO) { acc, i -> acc + Money(i.unitCostPricePaisa) * i.quantity }
                        profit += Money(s.totalPaisa) - cost
                    }
                    val today = todayEpochDay()
                    val medById = meds.associateBy { it.id }
                    val stockByMed = batches.groupBy { it.medicineId }.mapValues { e -> e.value.sumOf { it.quantity } }
                    val customerIds = customers.map { it.id }.toSet()
                    val dueTotal = ledger.filter { it.partyId in customerIds }
                        .groupBy { it.partyId }.values
                        .fold(Money.ZERO) { acc, es ->
                            val bal = es.fold(Money.ZERO) { a, e -> a + Money(e.amountPaisa) }
                            acc + if (bal > Money.ZERO) bal else Money.ZERO
                        }
                    _state.value = DashboardUiState(
                        todaySales = Money(sales.sumOf { it.totalPaisa }),
                        todayProfit = profit,
                        todayCount = sales.size,
                        cashByMethod = sales.groupBy { runCatching { PaymentMethod.valueOf(it.paymentMethod) }.getOrDefault(PaymentMethod.CASH) }
                            .mapValues { e -> Money(e.value.sumOf { it.paidPaisa }) },
                        totalCustomerDue = dueTotal,
                        lowStock = meds.map { it to (stockByMed[it.id] ?: 0) }.filter { it.second in 0..LOW_STOCK_THRESHOLD },
                        nearExpiry = batches.filter { it.quantity > 0 && it.expiryEpochDay in today..(today + 30) }
                            .mapNotNull { b -> medById[b.medicineId]?.let { m -> m to b } },
                        expired = batches.filter { it.quantity > 0 && it.expiryEpochDay < today }
                            .mapNotNull { b -> medById[b.medicineId]?.let { m -> m to b } }
                    )
                }
        }
    }

    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
    companion object { const val LOW_STOCK_THRESHOLD = 10 }
}

@Composable
fun DashboardScreen(vm: DashboardViewModel = hiltViewModel()) {
    val s by vm.state.collectAsState()
    var filter by remember { mutableStateOf(AlertFilter.ALL) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(vm.pharmacyName.ifBlank { "আমার ফার্মেসি" }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        vm.banner?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("আজকের বিক্রি", s.todaySales.bn(), Modifier.weight(1f))
            StatCard("আজকের লাভ", s.todayProfit.bn(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("মোট বাকি (কাস্টমার)", s.totalCustomerDue.bn(), Modifier.weight(1f))
            StatCard("বিল সংখ্যা", s.todayCount.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("আজকের সংগ্রহ (পদ্ধতি অনুযায়ী)", style = MaterialTheme.typography.titleMedium)
                if (s.cashByMethod.isEmpty()) Text("আজ এখনো বিক্রি হয়নি")
                s.cashByMethod.forEach { (method, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(methodLabel(method)); Text(amount.bn())
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("সতর্কতা", style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(filter == AlertFilter.ALL, { filter = AlertFilter.ALL }, { Text("সব") })
            FilterChip(filter == AlertFilter.LOW_STOCK, { filter = AlertFilter.LOW_STOCK }, { Text("কম স্টক (${s.lowStock.size})") })
            FilterChip(filter == AlertFilter.NEAR_EXPIRY, { filter = AlertFilter.NEAR_EXPIRY }, { Text("মেয়াদ শেষের পথে (${s.nearExpiry.size})") })
            FilterChip(filter == AlertFilter.EXPIRED, { filter = AlertFilter.EXPIRED }, { Text("মেয়াদোত্তীর্ণ (${s.expired.size})") })
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (filter == AlertFilter.ALL || filter == AlertFilter.LOW_STOCK) items(s.lowStock) { (med, qty) ->
                AlertRow("কম স্টক: ${med.brandName} ${med.strength} — মাত্র $qty টি আছে")
            }
            if (filter == AlertFilter.ALL || filter == AlertFilter.NEAR_EXPIRY) items(s.nearExpiry) { (med, b) ->
                AlertRow("মেয়াদ শেষের পথে: ${med.brandName} — ${epochDayToDateString(b.expiryEpochDay)} (ব্যাচ ${b.batchNo}, ${b.quantity} টি)")
            }
            if (filter == AlertFilter.ALL || filter == AlertFilter.EXPIRED) items(s.expired) { (med, b) ->
                AlertRow("মেয়াদোত্তীর্ণ: ${med.brandName} — ${epochDayToDateString(b.expiryEpochDay)} (ব্যাচ ${b.batchNo}, ${b.quantity} টি)")
            }
        }
    }
}

@Composable
private fun AlertRow(text: String) {
    Card(Modifier.fillMaxWidth()) { Text(text, Modifier.padding(12.dp)) }
}

fun methodLabel(m: PaymentMethod): String = when (m) {
    PaymentMethod.CASH -> "নগদ"; PaymentMethod.BKASH -> "বিকাশ"; PaymentMethod.NAGAD -> "নগদ অ্যাপ"
    PaymentMethod.CARD -> "কার্ড"; PaymentMethod.DUE -> "বাকি"
}
