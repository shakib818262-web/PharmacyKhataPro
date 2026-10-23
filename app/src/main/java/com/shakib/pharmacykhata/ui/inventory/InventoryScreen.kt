package com.shakib.pharmacykhata.ui.inventory

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakib.pharmacykhata.data.catalog.CatalogRepository
import com.shakib.pharmacykhata.data.catalog.CatalogSuggestion
import com.shakib.pharmacykhata.data.local.entity.MedicineEntity
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.money.Money
import com.shakib.pharmacykhata.domain.validation.Validators
import com.shakib.pharmacykhata.ui.common.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class InventoryViewModel @Inject constructor(
    private val repo: PharmacyRepository,
    private val catalog: CatalogRepository,
    private val session: SessionStore
) : ViewModel() {
    val pid: String = session.pharmacyId ?: ""
    private val query = MutableStateFlow("")
    val medicines: StateFlow<List<MedicineEntity>> = query
        .flatMapLatest { q -> if (q.isBlank()) repo.observeMedicines(pid) else repo.searchMedicines(pid, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val batches = repo.observeBatches(pid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _suggestions = MutableStateFlow<List<CatalogSuggestion>>(emptyList())
    val suggestions = _suggestions.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private var searchJob: Job? = null

    init { viewModelScope.launch { runCatching { catalog.ensureSeeded() } } }

    fun setQuery(q: String) { query.value = q }
    fun clearMessage() { _message.value = null }

    fun searchCatalog(q: String) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            _suggestions.value = if (q.trim().length >= 2) runCatching { catalog.search(pid, q) }.getOrDefault(emptyList()) else emptyList()
        }
    }

    fun addStock(s: CatalogSuggestion, batchNo: String, expiry: String, qty: String, purchaseTk: String, saleTk: String) {
        val err = validateStock(expiry, qty, purchaseTk, saleTk)
        if (err != null) { _message.value = err; return }
        viewModelScope.launch {
            try {
                repo.addStock(pid, s, batchNo.trim(), Validators.parseExpiry(expiry)!!,
                    qty.englishDigits().toInt(), Money.parse(purchaseTk)!!, Money.parse(saleTk)!!)
                _message.value = "স্টকে যোগ হয়েছে: ${s.displayName}"
                _suggestions.value = emptyList()
            } catch (e: Exception) { _message.value = e.localizedMessage ?: "যোগ করা যায়নি" }
        }
    }

    fun addStockToMedicine(medicineId: Long, batchNo: String, expiry: String, qty: String, purchaseTk: String, saleTk: String) {
        val err = validateStock(expiry, qty, purchaseTk, saleTk)
        if (err != null) { _message.value = err; return }
        viewModelScope.launch {
            try {
                repo.addStockForMedicine(pid, medicineId, batchNo.trim(), Validators.parseExpiry(expiry)!!,
                    qty.englishDigits().toInt(), Money.parse(purchaseTk)!!, Money.parse(saleTk)!!)
                _message.value = "নতুন ব্যাচ যোগ হয়েছে"
            } catch (e: Exception) { _message.value = e.localizedMessage ?: "যোগ করা যায়নি" }
        }
    }

    fun addCustomMedicine(brand: String, generic: String, strength: String, form: String, maker: String, mrpTk: String,
                          batchNo: String, expiry: String, qty: String, purchaseTk: String, saleTk: String) {
        if (brand.isBlank()) { _message.value = "ওষুধের নাম আবশ্যক"; return }
        val err = validateStock(expiry, qty, purchaseTk, saleTk); if (err != null) { _message.value = err; return }
        viewModelScope.launch {
            try {
                val s = catalog.addCustomMedicine(pid, brand, generic, strength, form, maker, 1,
                    Money.parse(mrpTk)?.paisa ?: Money.parse(saleTk)?.paisa ?: 0, null)
                repo.addStock(pid, s, batchNo.trim(), Validators.parseExpiry(expiry)!!,
                    qty.englishDigits().toInt(), Money.parse(purchaseTk)!!, Money.parse(saleTk)!!)
                _message.value = "কাস্টম ওষুধ স্টকে যোগ হয়েছে: $brand"
            } catch (e: Exception) { _message.value = e.localizedMessage ?: "যোগ করা যায়নি" }
        }
    }

    private fun validateStock(expiry: String, qty: String, purchaseTk: String, saleTk: String): String? = when {
        Validators.parseExpiry(expiry) == null -> "মেয়াদ সঠিকভাবে লিখুন (যেমন 03/2027)"
        Validators.positiveInt(qty) == null -> "পরিমাণ অবশ্যই ০-এর বেশি হতে হবে"
        Money.parse(purchaseTk) == null || Money.parse(purchaseTk)!! < Money.ZERO -> "সঠিক ক্রয়মূল্য দিন (ঋণাত্মক নয়)"
        Money.parse(saleTk) == null || Money.parse(saleTk)!! <= Money.ZERO -> "বিক্রয়মূল্য অবশ্যই ০-এর বেশি হতে হবে"
        else -> null
    }
}

@Composable
fun InventoryScreen(vm: InventoryViewModel = hiltViewModel()) {
    val medicines by vm.medicines.collectAsState()
    val batches by vm.batches.collectAsState()
    val message by vm.message.collectAsState()
    var query by remember { mutableStateOf("") }
    var showAdd by remember { mutableStateOf(false) }
    var addBatchFor by remember { mutableStateOf<MedicineEntity?>(null) }
    val stockByMed = remember(batches) { batches.groupBy { it.medicineId }.mapValues { e -> e.value.sumOf { it.quantity } } }

    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { showAdd = true }) { Icon(Icons.Default.Add, "স্টক যোগ") }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Text("স্টক / ইনভেন্টরি", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(query, { query = it; vm.setQuery(it) }, label = { Text("ওষুধ খুঁজুন (নাম / জেনেরিক / বারকোড)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(10.dp))
            if (medicines.isEmpty()) Text("কোনো ওষুধ নেই। + বাটনে চাপ দিয়ে ক্যাটালগ থেকে স্টক যোগ করুন।")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(medicines) { med ->
                    Card(Modifier.fillMaxWidth().clickable { addBatchFor = med }) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${med.brandName} ${med.strength}", style = MaterialTheme.typography.titleMedium)
                            Text(listOf(med.genericName, med.form, med.manufacturer).filter { it.isNotBlank() }.joinToString(" · "))
                            val medBatches = batches.filter { it.medicineId == med.id }.sortedBy { it.expiryEpochDay }
                            Text("মোট স্টক: ${stockByMed[med.id] ?: 0} টি", color = MaterialTheme.colorScheme.primary)
                            medBatches.forEach { b ->
                                val expired = b.expiryEpochDay < todayEpochDay()
                                Text("ব্যাচ ${b.batchNo.ifBlank { "-" }} · মেয়াদ ${epochDayToDateString(b.expiryEpochDay)} · ${b.quantity} টি · বিক্রি ${Money(b.salePricePaisa).bn()}" +
                                    if (expired) " · মেয়াদোত্তীর্ণ!" else "",
                                    color = if (expired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                            Text("নতুন ব্যাচ যোগ করতে চাপ দিন", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }

    if (showAdd) AddStockDialog(vm, onDismiss = { showAdd = false })
    addBatchFor?.let { med ->
        AddBatchDialog(med, vm, onDismiss = { addBatchFor = null })
    }
    LaunchedEffect(message) { if (message != null && (message!!.startsWith("স্টকে") || message!!.startsWith("নতুন"))) { showAdd = false; addBatchFor = null } }
}

@Composable
private fun StockFields(batchNo: String, setBatch: (String) -> Unit, expiry: String, setExpiry: (String) -> Unit,
                        qty: String, setQty: (String) -> Unit, purchase: String, setPurchase: (String) -> Unit,
                        sale: String, setSale: (String) -> Unit) {
    OutlinedTextField(batchNo, setBatch, label = { Text("ব্যাচ নম্বর") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    OutlinedTextField(expiry, setExpiry, label = { Text("মেয়াদ (MM/YYYY) *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    NumberField("পরিমাণ (টি) *", qty, setQty)
    MoneyField("ক্রয়মূল্য / টি (ট) *", purchase, setPurchase)
    MoneyField("বিক্রয়মূল্য / টি (ট) *", sale, setSale)
}

@Composable
private fun AddStockDialog(vm: InventoryViewModel, onDismiss: () -> Unit) {
    val suggestions by vm.suggestions.collectAsState()
    val message by vm.message.collectAsState()
    var name by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<CatalogSuggestion?>(null) }
    var customMode by remember { mutableStateOf(false) }
    var generic by remember { mutableStateOf("") }; var strength by remember { mutableStateOf("") }
    var form by remember { mutableStateOf("") }; var maker by remember { mutableStateOf("") }
    var batchNo by remember { mutableStateOf("") }; var expiry by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }; var purchase by remember { mutableStateOf("") }; var sale by remember { mutableStateOf("") }

    AlertDialog(onDismissRequest = onDismiss, title = { Text("স্টকে ওষুধ যোগ করুন") }, text = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText(message)
            if (!customMode) {
                OutlinedTextField(name, { name = it; selected = null; vm.searchCatalog(it) },
                    label = { Text("ওষুধের নাম লিখুন — ক্যাটালগ থেকে আসবে") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                LazyColumn(Modifier.heightIn(max = 180.dp)) {
                    items(suggestions) { s ->
                        ListItem(
                            headlineContent = { Text(s.displayName) },
                            supportingContent = { Text("${s.subtitle} · MRP ${Money(s.unitMrpPaisa).bn()}" + if (s.isCustom) " · নিজের যোগ করা" else "") },
                            modifier = Modifier.clickable {
                                selected = s; name = s.displayName
                                sale = (s.unitMrpPaisa / 100.0).let { if (s.unitMrpPaisa % 100 == 0L) (s.unitMrpPaisa / 100).toString() else it.toString() }
                            }
                        )
                    }
                }
                selected?.let { Text("নির্বাচিত: ${it.displayName} (${it.manufacturer})", color = MaterialTheme.colorScheme.primary) }
                TextButton(onClick = { customMode = true }) { Text("ক্যাটালগে নেই? কাস্টম ওষুধ যোগ করুন") }
            } else {
                OutlinedTextField(name, { name = it }, label = { Text("ব্র্যান্ড নাম *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(generic, { generic = it }, label = { Text("জেনেরিক") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(strength, { strength = it }, label = { Text("শক্তি (যেমন 500 mg)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(form, { form = it }, label = { Text("ধরন (Tablet/Capsule…)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(maker, { maker = it }, label = { Text("কোম্পানি") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                TextButton(onClick = { customMode = false }) { Text("ক্যাটালগ থেকে খুঁজুন") }
            }
            StockFields(batchNo, { batchNo = it }, expiry, { expiry = it }, qty, { qty = it }, purchase, { purchase = it }, sale, { sale = it })
        }
    }, confirmButton = {
        Button(onClick = {
            if (customMode) vm.addCustomMedicine(name, generic, strength, form, maker, sale, batchNo, expiry, qty, purchase, sale)
            else selected?.let { vm.addStock(it, batchNo, expiry, qty, purchase, sale) }
        }, enabled = customMode || selected != null) { Text("স্টকে যোগ করুন") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } })
}

@Composable
private fun AddBatchDialog(med: MedicineEntity, vm: InventoryViewModel, onDismiss: () -> Unit) {
    val message by vm.message.collectAsState()
    var batchNo by remember { mutableStateOf("") }; var expiry by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }; var purchase by remember { mutableStateOf("") }
    var sale by remember { mutableStateOf((med.mrpPaisa / 100).toString()) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("নতুন ব্যাচ: ${med.brandName} ${med.strength}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ErrorText(message)
            StockFields(batchNo, { batchNo = it }, expiry, { expiry = it }, qty, { qty = it }, purchase, { purchase = it }, sale, { sale = it })
        }
    }, confirmButton = {
        Button(onClick = { vm.addStockToMedicine(med.id, batchNo, expiry, qty, purchase, sale) }) { Text("যোগ করুন") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("বাতিল") } })
}
