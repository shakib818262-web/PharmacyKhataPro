package com.shakib.pharmacykhata.ui.purchase

import androidx.compose.foundation.clickable
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
import com.shakib.pharmacykhata.data.catalog.CatalogRepository
import com.shakib.pharmacykhata.data.catalog.CatalogSuggestion
import com.shakib.pharmacykhata.data.local.entity.MedicineEntity
import com.shakib.pharmacykhata.data.local.entity.PartyEntity
import com.shakib.pharmacykhata.data.repository.DuplicateInvoiceException
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
import com.shakib.pharmacykhata.data.repository.PurchaseLineInput
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.model.PartyType
import com.shakib.pharmacykhata.domain.model.PaymentMethod
import com.shakib.pharmacykhata.domain.money.Money
import com.shakib.pharmacykhata.domain.validation.Validators
import com.shakib.pharmacykhata.ui.common.*
import com.shakib.pharmacykhata.ui.dashboard.methodLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PurchaseDraft(
    val medicineId: Long? = null, val suggestion: CatalogSuggestion? = null, val customName: String? = null,
    val displayName: String, var batchNo: String = "", var expiry: String = "",
    var qty: String = "", var purchase: String = "", var sale: String = ""
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class PurchaseViewModel @Inject constructor(
    private val repo: PharmacyRepository,
    private val catalog: CatalogRepository,
    private val session: SessionStore
) : ViewModel() {
    val pid: String = session.pharmacyId ?: ""
    val suppliers: StateFlow<List<PartyEntity>> = repo.observeParties(pid, PartyType.SUPPLIER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val query = MutableStateFlow("")
    val stockResults: StateFlow<List<MedicineEntity>> = query
        .flatMapLatest { q -> if (q.isBlank()) repo.observeMedicines(pid) else repo.searchMedicines(pid, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _catalogResults = MutableStateFlow<List<CatalogSuggestion>>(emptyList())
    val catalogResults = _catalogResults.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    init { viewModelScope.launch { runCatching { catalog.ensureSeeded() } } }
    fun setMessage(m: String?) { _message.value = m }

    fun search(q: String) {
        query.value = q
        viewModelScope.launch {
            _catalogResults.value = if (q.trim().length >= 2) runCatching { catalog.search(pid, q) }.getOrDefault(emptyList()) else emptyList()
        }
    }

    fun addSupplier(name: String, phone: String, onCreated: (Long) -> Unit) {
        if (name.isBlank()) { _message.value = "সাপ্লায়ারের নাম আবশ্যক"; return }
        if (phone.isNotBlank() && !Validators.isValidBdPhone(phone)) { _message.value = "সাপ্লায়ারের মোবাইল নম্বর সঠিক নয়"; return }
        viewModelScope.launch {
            try {
                val id = repo.addParty(pid, PartyType.SUPPLIER, name, Validators.bdPhone(phone) ?: phone, "")
                _message.value = "সাপ্লায়ার তৈরি হয়েছে"; onCreated(id)
            } catch (e: Exception) { _message.value = e.localizedMessage }
        }
    }

    fun submit(supplierId: Long?, invoiceNo: String, paidText: String, method: PaymentMethod, drafts: List<PurchaseDraft>) {
        if (supplierId == null) { _message.value = "সাপ্লায়ার নির্বাচন আবশ্যক"; return }
        if (invoiceNo.isBlank()) { _message.value = "ইনভয়েস নম্বর আবশ্যক"; return }
        if (drafts.isEmpty()) { _message.value = "অন্তত একটি ওষুধ যোগ করুন"; return }
        val paid = Money.parse(paidText) ?: run { _message.value = "সঠিক পেমেন্ট পরিমাণ দিন"; return }
        val lines = mutableListOf<PurchaseLineInput>()
        for (d in drafts) {
            val expiryDay = Validators.parseExpiry(d.expiry) ?: run { _message.value = "মেয়াদ সঠিক নয়: ${d.displayName} (MM/YYYY)"; return }
            val qty = Validators.positiveInt(d.qty) ?: run { _message.value = "পরিমাণ সঠিক নয়: ${d.displayName}"; return }
            val pp = Money.parse(d.purchase) ?: run { _message.value = "ক্রয়মূল্য সঠিক নয়: ${d.displayName}"; return }
            val sp = Money.parse(d.sale) ?: run { _message.value = "বিক্রয়মূল্য সঠিক নয়: ${d.displayName}"; return }
            if (pp < Money.ZERO || sp <= Money.ZERO) { _message.value = "দাম যাচাই করুন: ${d.displayName}"; return }
            lines += PurchaseLineInput(d.medicineId, d.suggestion, d.customName, d.batchNo.trim(), expiryDay, qty, pp, sp)
        }
        viewModelScope.launch {
            try {
                val p = repo.recordPurchase(pid, supplierId, invoiceNo, lines, paid, method)
                _message.value = "ক্রয় সম্পন্ন: মোট ${p.total.bn()}, বাকি ${p.due.bn()}"
            } catch (e: DuplicateInvoiceException) {
                _message.value = "এই সাপ্লায়ারের এই ইনভয়েস নম্বর আগেই আছে — duplicate রোধ করা হয়েছে"
            } catch (e: Exception) { _message.value = e.localizedMessage ?: "ক্রয় সম্পন্ন হয়নি" }
        }
    }
}

@Composable
fun PurchaseScreen(vm: PurchaseViewModel = hiltViewModel()) {
    val suppliers by vm.suppliers.collectAsState()
    val stockResults by vm.stockResults.collectAsState()
    val catalogResults by vm.catalogResults.collectAsState()
    val message by vm.message.collectAsState()
    var supplierId by remember { mutableStateOf<Long?>(null) }
    var newSupplierName by remember { mutableStateOf("") }
    var newSupplierPhone by remember { mutableStateOf("") }
    var invoiceNo by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var search by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }
    var drafts by remember { mutableStateOf(listOf<PurchaseDraft>()) }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("ক্রয় (Purchase)", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        }
        item {
            Text("সাপ্লায়ার *", style = MaterialTheme.typography.titleMedium)
            suppliers.forEach { s ->
                ListItem(headlineContent = { Text(s.name) }, supportingContent = { Text(s.phone) },
                    modifier = Modifier.clickable { supplierId = s.id },
                    colors = if (supplierId == s.id) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.primaryContainer) else ListItemDefaults.colors())
            }
            OutlinedTextField(newSupplierName, { newSupplierName = it }, label = { Text("নতুন সাপ্লায়ারের নাম") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(newSupplierPhone, { newSupplierPhone = it }, label = { Text("মোবাইল") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.addSupplier(newSupplierName, newSupplierPhone) { id -> supplierId = id; newSupplierName = ""; newSupplierPhone = "" } }) {
                Text("সাপ্লায়ার তৈরি করুন")
            }
        }
        item {
            OutlinedTextField(invoiceNo, { invoiceNo = it }, label = { Text("ইনভয়েস নম্বর *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaymentMethod.values().filter { it != PaymentMethod.DUE }.forEach { m ->
                    FilterChip(method == m, { method = m }, { Text(methodLabel(m)) })
                }
            }
            MoneyField("পরিশোধ (ট)", paid, { paid = it })
        }
        item {
            Text("ওষুধ যোগ করুন", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(search, { search = it; vm.search(it) }, label = { Text("স্টক / ক্যাটালগে খুঁজুন") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        items(stockResults.take(5)) { med ->
            ListItem(headlineContent = { Text("স্টক: ${med.brandName} ${med.strength}") },
                modifier = Modifier.clickable {
                    drafts = drafts + PurchaseDraft(medicineId = med.id, displayName = "${med.brandName} ${med.strength}")
                })
        }
        items(catalogResults.take(5)) { s ->
            ListItem(headlineContent = { Text("ক্যাটালগ: ${s.displayName}") }, supportingContent = { Text(s.subtitle) },
                modifier = Modifier.clickable { drafts = drafts + PurchaseDraft(suggestion = s, displayName = s.displayName) })
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(customName, { customName = it }, label = { Text("নতুন ওষুধের নাম") }, modifier = Modifier.weight(1f), singleLine = true)
                Button(onClick = {
                    if (customName.isNotBlank()) { drafts = drafts + PurchaseDraft(customName = customName.trim(), displayName = customName.trim()); customName = "" }
                }) { Text("যোগ") }
            }
        }
        items(drafts) { d ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(d.displayName, style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { drafts = drafts - d }) { Text("মুছুন") }
                    }
                    var batchNo by remember(d) { mutableStateOf(d.batchNo) }
                    var expiry by remember(d) { mutableStateOf(d.expiry) }
                    var qty by remember(d) { mutableStateOf(d.qty) }
                    var purchase by remember(d) { mutableStateOf(d.purchase) }
                    var sale by remember(d) { mutableStateOf(d.sale) }
                    OutlinedTextField(batchNo, { batchNo = it; d.batchNo = it }, label = { Text("ব্যাচ") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(expiry, { expiry = it; d.expiry = it }, label = { Text("মেয়াদ (MM/YYYY) *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    NumberField("পরিমাণ *", qty, { qty = it; d.qty = it })
                    MoneyField("ক্রয়মূল্য / টি *", purchase, { purchase = it; d.purchase = it })
                    MoneyField("বিক্রয়মূল্য / টি *", sale, { sale = it; d.sale = it })
                }
            }
        }
        item {
            val total = drafts.fold(Money.ZERO) { acc, d ->
                val q = d.qty.englishDigits().toIntOrNull() ?: 0
                val p = Money.parse(d.purchase) ?: Money.ZERO
                acc + p * q
            }
            Text("মোট ক্রয়: ${total.bn()}")
            Button(
                onClick = { vm.submit(supplierId, invoiceNo, paid.ifBlank { "0" }, method, drafts) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("ক্রয় সম্পন্ন করুন") }
        }
    }
}
