package com.shakib.pharmacykhata.ui.pos

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakib.pharmacykhata.data.local.entity.MedicineEntity
import com.shakib.pharmacykhata.data.local.entity.PartyEntity
import com.shakib.pharmacykhata.data.repository.CartLine
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.logic.SaleCalculator
import com.shakib.pharmacykhata.domain.model.PartyType
import com.shakib.pharmacykhata.domain.model.PaymentMethod
import com.shakib.pharmacykhata.domain.model.Sale
import com.shakib.pharmacykhata.domain.model.SaleItem
import com.shakib.pharmacykhata.domain.model.Subscription
import com.shakib.pharmacykhata.domain.model.SubscriptionStatus
import com.shakib.pharmacykhata.domain.money.Money
import com.shakib.pharmacykhata.domain.validation.Validators
import com.shakib.pharmacykhata.ui.common.*
import com.shakib.pharmacykhata.ui.dashboard.methodLabel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@HiltViewModel
class PosViewModel @Inject constructor(
    private val repo: PharmacyRepository,
    private val session: SessionStore
) : ViewModel() {
    val pid: String = session.pharmacyId ?: ""
    private val query = MutableStateFlow("")
    val results: StateFlow<List<MedicineEntity>> = query
        .flatMapLatest { q -> if (q.isBlank()) repo.observeMedicines(pid) else repo.searchMedicines(pid, q) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val batches = repo.observeBatches(pid).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val customers: StateFlow<List<PartyEntity>> = repo.observeParties(pid, PartyType.CUSTOMER)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _cart = MutableStateFlow<List<CartLine>>(emptyList())
    val cart = _cart.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _receipt = MutableStateFlow<Sale?>(null)
    val receipt = _receipt.asStateFlow()

    val canSell: Boolean = Subscription(
        status = runCatching { SubscriptionStatus.valueOf(session.subscriptionStatus) }.getOrDefault(SubscriptionStatus.TRIAL),
        trialEndsAt = session.trialEndsAt, expiresAt = session.expiresAt
    ).canSell(System.currentTimeMillis())

    fun setQuery(q: String) { query.value = q }

    fun addToCart(med: MedicineEntity, availableQty: Int) {
        val existing = _cart.value.firstOrNull { it.medicineId == med.id }
        val salePrice = batches.value.filter { it.medicineId == med.id && it.quantity > 0 }
            .minByOrNull { it.expiryEpochDay }?.salePricePaisa?.let { Money(it) } ?: Money(med.mrpPaisa)
        when {
            availableQty <= 0 -> _message.value = "স্টক নেই: ${med.brandName}"
            existing == null -> _cart.value = _cart.value + CartLine(med.id, "${med.brandName} ${med.strength}", 1, salePrice)
            existing.quantity >= availableQty -> _message.value = "স্টকের বেশি যোগ করা যাবে না (${med.brandName}: $availableQty টি আছে)"
            else -> _cart.value = _cart.value.map { if (it.medicineId == med.id) it.copy(quantity = it.quantity + 1) else it }
        }
    }

    fun setQty(medicineId: Long, qty: Int, availableQty: Int) {
        if (qty <= 0) { _cart.value = _cart.value.filter { it.medicineId != medicineId }; return }
        if (qty > availableQty) { _message.value = "স্টক মাত্র $availableQty টি"; return }
        _cart.value = _cart.value.map { if (it.medicineId == medicineId) it.copy(quantity = qty) else it }
    }

    fun clearMessage() { _message.value = null }
    fun clearReceipt() { _receipt.value = null }

    fun scanBarcode(barcode: String) {
        viewModelScope.launch {
            val med = repo.medicineByBarcode(pid, barcode)
            if (med == null) { _message.value = "এই বারকোডের ওষুধ স্টকে নেই: $barcode"; return@launch }
            val avail = batches.value.filter { it.medicineId == med.id && it.expiryEpochDay >= todayEpochDay() }.sumOf { it.quantity }
            addToCart(med, avail)
            if (_message.value == null) _message.value = "স্ক্যান: ${med.brandName} কার্টে যোগ হয়েছে"
        }
    }

    fun completeSale(
        discountFlatText: String, paidText: String, method: PaymentMethod,
        customerId: Long?, newCustomerName: String, newCustomerPhone: String
    ) {
        if (!canSell) { _message.value = "সাবস্ক্রিপশন শেষ — নতুন বিক্রি বন্ধ আছে"; return }
        val paid = Money.parse(paidText) ?: run { _message.value = "সঠিক পেমেন্ট পরিমাণ দিন"; return }
        if (paid < Money.ZERO) { _message.value = "পেমেন্ট ঋণাত্মক হতে পারে না"; return }
        val discount = discountFlatText.takeIf { it.isNotBlank() }?.let {
            Money.parse(it) ?: run { _message.value = "সঠিক ডিসকাউন্ট দিন"; return }
        }
        val phoneOk = newCustomerPhone.isBlank() || Validators.isValidBdPhone(newCustomerPhone)
        if (!phoneOk) { _message.value = "কাস্টমারের মোবাইল নম্বর সঠিক নয়"; return }
        viewModelScope.launch {
            try {
                val sale = repo.completeSale(
                    pid, _cart.value, discount, null, paid, method,
                    customerId, newCustomerName.ifBlank { null },
                    newCustomerPhone.ifBlank { null }?.let { Validators.bdPhone(it) }
                )
                _cart.value = emptyList()
                _receipt.value = sale
            } catch (e: Exception) {
                _message.value = when (e) {
                    is com.shakib.pharmacykhata.data.repository.InsufficientStockException ->
                        "স্টক অপর্যাপ্ত: ${e.medicineName} (আছে ${e.available} টি)"
                    else -> e.localizedMessage ?: "বিক্রি সম্পন্ন হয়নি"
                }
            }
        }
    }
}

@Composable
fun PosScreen(onOpenScanner: () -> Unit, scannedBarcode: String? = null, onBarcodeConsumed: () -> Unit = {}, vm: PosViewModel = hiltViewModel()) {
    LaunchedEffect(scannedBarcode) {
        if (!scannedBarcode.isNullOrBlank()) { vm.scanBarcode(scannedBarcode); onBarcodeConsumed() }
    }
    val results by vm.results.collectAsState()
    val batches by vm.batches.collectAsState()
    val cart by vm.cart.collectAsState()
    val customers by vm.customers.collectAsState()
    val message by vm.message.collectAsState()
    val receipt by vm.receipt.collectAsState()
    var query by remember { mutableStateOf("") }
    var paid by remember { mutableStateOf("") }
    var discount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PaymentMethod.CASH) }
    var customerId by remember { mutableStateOf<Long?>(null) }
    var newCustomerName by remember { mutableStateOf("") }
    var newCustomerPhone by remember { mutableStateOf("") }

    val stockByMed = remember(batches) {
        batches.filter { it.expiryEpochDay >= todayEpochDay() }
            .groupBy { it.medicineId }.mapValues { e -> e.value.sumOf { it.quantity } }
    }
    val preview = remember(cart, paid, discount) {
        if (cart.isEmpty()) null else runCatching {
            SaleCalculator.calculate(
                cart.map { SaleItem(it.medicineId, it.medicineName, it.quantity, it.unitSalePrice, Money.ZERO) },
                discountFlat = discount.takeIf { d -> d.isNotBlank() }?.let { Money.parse(it) },
                paid = Money.parse(paid) ?: Money.ZERO
            )
        }.getOrNull()
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("বিক্রি (POS)", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onOpenScanner) { Icon(Icons.Default.QrCodeScanner, "বারকোড স্ক্যান") }
        }
        if (!vm.canSell) Text("সাবস্ক্রিপশন শেষ — নতুন বিক্রি বন্ধ আছে। ডেটা দেখা ও export চালু আছে।", color = MaterialTheme.colorScheme.error)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(4.dp)) }
        OutlinedTextField(query, { query = it; vm.setQuery(it) }, label = { Text("ওষুধ খুঁজুন — চাপ দিলে কার্টে যাবে") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (cart.isNotEmpty()) {
                item { Text("কার্ট", style = MaterialTheme.typography.titleLarge) }
                items(cart) { line ->
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(line.medicineName, style = MaterialTheme.typography.titleMedium)
                                Text("${line.unitSalePrice.bn()} × ${line.quantity} = ${(line.unitSalePrice * line.quantity).bn()}")
                            }
                            Row {
                                TextButton(onClick = { vm.setQty(line.medicineId, line.quantity - 1, stockByMed[line.medicineId] ?: 0) }) { Text("−") }
                                TextButton(onClick = { vm.setQty(line.medicineId, line.quantity + 1, stockByMed[line.medicineId] ?: 0) }) { Text("+") }
                            }
                        }
                    }
                }
                item { Divider() }
            }
            item { Text("স্টকের ওষুধ", style = MaterialTheme.typography.titleMedium) }
            items(results) { med ->
                val avail = stockByMed[med.id] ?: 0
                ListItem(
                    headlineContent = { Text("${med.brandName} ${med.strength}") },
                    supportingContent = { Text("${med.genericName} · স্টক $avail টি") },
                    modifier = Modifier.clickable(enabled = avail > 0) { vm.addToCart(med, avail) }
                )
            }
        }
        if (cart.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f)) { MoneyField("ডিসকাউন্ট (ট)", discount, { discount = it }) }
                Box(Modifier.weight(1f)) { MoneyField("পেমেন্ট (ট) *", paid, { paid = it }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaymentMethod.values().filter { it != PaymentMethod.DUE }.forEach { m ->
                    FilterChip(method == m, { method = m }, { Text(methodLabel(m)) })
                }
            }
            OutlinedTextField(
                value = customers.firstOrNull { it.id == customerId }?.name ?: newCustomerName,
                onValueChange = { newCustomerName = it; customerId = null },
                label = { Text("কাস্টমার (বাকি থাকলে আবশ্যক)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (customers.isNotEmpty()) {
                LazyColumn(Modifier.heightIn(max = 90.dp)) {
                    items(customers) { c ->
                        ListItem(headlineContent = { Text(c.name) }, supportingContent = { Text(c.phone) },
                            modifier = Modifier.clickable { customerId = c.id; newCustomerName = "" })
                    }
                }
            }
            if (customerId == null) {
                OutlinedTextField(newCustomerPhone, { newCustomerPhone = it }, label = { Text("নতুন কাস্টমারের মোবাইল") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
            preview?.let {
                Text("মোট: ${it.subtotal.bn()} · ডিসকাউন্ট: ${it.discount.bn()} · দিতে হবে: ${it.total.bn()} · ফেরত: ${it.change.bn()} · বাকি: ${it.due.bn()}")
            }
            Button(
                onClick = { vm.completeSale(discount, paid, method, customerId, newCustomerName, newCustomerPhone) },
                enabled = vm.canSell, modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("বিক্রি সম্পন্ন করুন") }
        }
    }

    receipt?.let { sale -> ReceiptDialog(sale) { vm.clearReceipt() } }
}

@Composable
private fun ReceiptDialog(sale: Sale, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val shareText = remember(sale) { buildReceiptText(sale) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("রসিদ — ${sale.invoiceNo}") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            sale.items.forEach { Text("${it.medicineName} × ${it.quantity} = ${it.lineTotal.bn()}") }
            Divider()
            Text("মোট: ${sale.total.bn()}")
            Text("পরিশোধ: ${sale.paid.bn()}" + if (sale.change > Money.ZERO) " · ফেরত: ${sale.change.bn()}" else "")
            if (sale.due > Money.ZERO) Text("বাকি: ${sale.due.bn()}" + if (sale.customerName.isNotBlank()) " (${sale.customerName})" else "")
        }
    }, confirmButton = {
        Button(onClick = {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText)
            }
            context.startActivity(Intent.createChooser(intent, "রসিদ শেয়ার"))
        }) { Text("শেয়ার") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("বন্ধ করুন") } })
}

fun buildReceiptText(sale: Sale): String = buildString {
    appendLine("ফার্মেসি খাতা Pro — রসিদ ${sale.invoiceNo}")
    appendLine("তারিখ: ${millisToDateString(sale.createdAt)}")
    sale.items.forEach { appendLine("${it.medicineName} x${it.quantity} = ${it.lineTotal.bn()}") }
    appendLine("মোট: ${sale.total.bn()}")
    appendLine("পরিশোধ: ${sale.paid.bn()}")
    if (sale.change > Money.ZERO) appendLine("ফেরত: ${sale.change.bn()}")
    if (sale.due > Money.ZERO) appendLine("বাকি: ${sale.due.bn()}")
}
