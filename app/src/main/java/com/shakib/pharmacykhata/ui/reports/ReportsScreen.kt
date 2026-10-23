package com.shakib.pharmacykhata.ui.reports

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
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
import com.shakib.pharmacykhata.data.repository.ReportData
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.money.Money
import com.shakib.pharmacykhata.ui.common.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

enum class Period(val label: String) { TODAY("আজ"), WEEK("এই সপ্তাহ"), MONTH("এই মাস"), LAST_30("শেষ ৩০ দিন") }

data class ReportsUiState(
    val report: ReportData? = null,
    val perMedicine: List<Pair<String, Pair<Int, Money>>> = emptyList()
)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    private val repo: PharmacyRepository,
    private val session: SessionStore
) : ViewModel() {
    private val pid: String = session.pharmacyId ?: ""
    private val _period = MutableStateFlow(Period.TODAY)
    val period = _period.asStateFlow()
    private val _state = MutableStateFlow(ReportsUiState())
    val state = _state.asStateFlow()

    init { setPeriod(Period.TODAY) }

    fun setPeriod(p: Period) {
        _period.value = p
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val (fromDate, toDate) = when (p) {
            Period.TODAY -> today to today
            Period.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)) to today
            Period.MONTH -> today.withDayOfMonth(1) to today
            Period.LAST_30 -> today.minusDays(29) to today
        }
        val from = fromDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val to = toDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        viewModelScope.launch {
            combine(
                repo.observeSalesBetween(pid, from, to),
                repo.observeBatches(pid)
            ) { sales, batches -> sales to batches }.collect { (sales, batches) ->
                val report = repo.report(pid, sales, batches)
                val agg = mutableMapOf<String, Pair<Int, Money>>()
                for (s in sales) {
                    for (item in repo.saleItems(s.id)) {
                        val cur = agg[item.medicineName] ?: (0 to Money.ZERO)
                        agg[item.medicineName] = (cur.first + item.quantity) to (cur.second + Money(item.unitSalePricePaisa) * item.quantity)
                    }
                }
                _state.value = ReportsUiState(report, agg.entries.sortedByDescending { it.value.second.paisa }
                    .map { it.key to it.value })
            }
        }
    }
}

@Composable
fun ReportsScreen(vm: ReportsViewModel = hiltViewModel()) {
    val period by vm.period.collectAsState()
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("রিপোর্ট", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Period.values().forEach { p ->
                FilterChip(period == p, { vm.setPeriod(p) }, { Text(p.label) })
            }
        }
        Spacer(Modifier.height(12.dp))
        val r = s.report
        if (r == null) {
            Text("হিসাব করা হচ্ছে…")
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("বিক্রি (${r.salesCount} বিল)", r.revenue.bn(), Modifier.weight(1f))
                StatCard("লাভ", r.profit.bn(), Modifier.weight(1f))
            }
            if (r.profit < Money.ZERO) Text("এই সময়ে ক্ষতি হয়েছে: ${r.profit.bn()} — ক্ষতি লাভ হিসেবে দেখানো হয়নি।", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard("বাকি দেওয়া হয়েছে", r.totalDueGiven.bn(), Modifier.weight(1f))
                StatCard("স্টকের মূল্য (ক্রয়)", r.stockValueAtCost.bn(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Text("স্টকের মূল্য (বিক্রয়মূল্যে): ${r.stockValueAtSalePrice.bn()}")
        }
        Spacer(Modifier.height(16.dp))
        Text("ওষুধ অনুযায়ী বিক্রি", style = MaterialTheme.typography.titleLarge)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(s.perMedicine) { (name, qtyAndTotal) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(name); Text("${qtyAndTotal.first} টি · ${qtyAndTotal.second.bn()}")
                }
            }
        }
    }
}
