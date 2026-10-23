package com.shakib.pharmacykhata.ui.khata

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
import com.shakib.pharmacykhata.data.local.entity.LedgerEntryEntity
import com.shakib.pharmacykhata.data.local.entity.PartyEntity
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
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

@HiltViewModel
class KhataViewModel @Inject constructor(
    private val repo: PharmacyRepository,
    private val session: SessionStore
) : ViewModel() {
    val pid: String = session.pharmacyId ?: ""
    private val _tab = MutableStateFlow(PartyType.CUSTOMER)
    val tab = _tab.asStateFlow()
    val parties: StateFlow<List<PartyEntity>> = _tab
        .flatMapLatestOpt { repo.observeParties(pid, it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val ledgerAll: StateFlow<List<LedgerEntryEntity>> = repo.observeAllLedger(pid)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _selected = MutableStateFlow<PartyEntity?>(null)
    val selected = _selected.asStateFlow()
    val selectedEntries: StateFlow<List<LedgerEntryEntity>> = _selected
        .flatMapLatestOpt { p -> if (p == null) flowOf(emptyList()) else repo.observeLedger(pid, p.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()

    fun setTab(t: PartyType) { _tab.value = t; _selected.value = null }
    fun select(p: PartyEntity?) { _selected.value = p }

    fun balanceOf(partyId: Long, entries: List<LedgerEntryEntity>): Money =
        entries.filter { it.partyId == partyId }.fold(Money.ZERO) { acc, e -> acc + Money(e.amountPaisa) }

    fun addParty(name: String, phone: String, address: String) {
        if (name.isBlank()) { _message.value = "নাম আবশ্যক"; return }
        if (phone.isNotBlank() && !Validators.isValidBdPhone(phone)) { _message.value = "মোবাইল নম্বর সঠিক নয় (01xxxxxxxxx)"; return }
        viewModelScope.launch {
            try {
                repo.addParty(pid, _tab.value, name, Validators.bdPhone(phone) ?: "", address)
                _message.value = "যোগ হয়েছে: $name"
            } catch (e: Exception) { _message.value = e.localizedMessage }
        }
    }

    fun recordPayment(party: PartyEntity, amountText: String, method: PaymentMethod) {
        val amount = Money.parse(amountText) ?: run { _message.value = "সঠিক পরিমাণ দিন"; return }
        if (amount <= Money.ZERO) { _message.value = "পরিমাণ ০-এর বেশি হতে হবে"; return }
        viewModelScope.launch {
            try {
                val advance = repo.recordPayment(pid, party, amount, method)
                _message.value = if (advance > Money.ZERO)
                    "পেমেন্ট নেওয়া হয়েছে। অতিরিক্ত ${advance.bn()} অগ্রিম (credit) হিসেবে রয়ে গেছে — বাড়তি আয় হিসেবে ধরা হয়নি।"
                else "পেমেন্ট নেওয়া হয়েছে: ${amount.bn()}"
            } catch (e: Exception) { _message.value = e.localizedMessage }
        }
    }
}

/** Small helper to avoid annotating every call site with ExperimentalCoroutinesApi. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
private fun <T, R> Flow<T>.flatMapLatestOpt(transform: (T) -> Flow<R>): Flow<R> =
    flatMapLatest(transform)

@Composable
fun KhataScreen(vm: KhataViewModel = hiltViewModel()) {
    val tab by vm.tab.collectAsState()
    val parties by vm.parties.collectAsState()
    val ledgerAll by vm.ledgerAll.collectAsState()
    val selected by vm.selected.collectAsState()
    val entries by vm.selectedEntries.collectAsState()
    val message by vm.message.collectAsState()
    var newName by remember { mutableStateOf("") }
    var newPhone by remember { mutableStateOf("") }
    var newAddress by remember { mutableStateOf("") }
    var payAmount by remember { mutableStateOf("") }
    var payMethod by remember { mutableStateOf(PaymentMethod.CASH) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("খাতা (বাকির হিসাব)", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary); Spacer(Modifier.height(6.dp)) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(tab == PartyType.CUSTOMER, { vm.setTab(PartyType.CUSTOMER) }, { Text("কাস্টমার") })
            FilterChip(tab == PartyType.SUPPLIER, { vm.setTab(PartyType.SUPPLIER) }, { Text("সাপ্লায়ার") })
        }
        Spacer(Modifier.height(10.dp))

        val sel = selected
        if (sel == null) {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(parties) { p ->
                    val bal = vm.balanceOf(p.id, ledgerAll)
                    Card(Modifier.fillMaxWidth().clickable { vm.select(p) }) {
                        Row(Modifier.padding(12.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(p.phone)
                            }
                            Text(
                                when {
                                    bal > Money.ZERO -> "বাকি: ${bal.bn()}"
                                    bal < Money.ZERO -> "অগ্রিম: ${(-bal).bn()}"
                                    else -> "পরিশোধ"
                                },
                                color = if (bal > Money.ZERO) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
            Divider()
            Text(if (tab == PartyType.CUSTOMER) "নতুন কাস্টমার" else "নতুন সাপ্লায়ার", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(newName, { newName = it }, label = { Text("নাম *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(newPhone, { newPhone = it }, label = { Text("মোবাইল (01xxxxxxxxx)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(newAddress, { newAddress = it }, label = { Text("ঠিকানা") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Button(onClick = { vm.addParty(newName, newPhone, newAddress); newName = ""; newPhone = ""; newAddress = "" },
                modifier = Modifier.fillMaxWidth()) { Text("যোগ করুন") }
        } else {
            val bal = vm.balanceOf(sel.id, entries)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sel.name, style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { vm.select(null) }) { Text("← তালিকা") }
            }
            Text("বর্তমান ব্যালেন্স: " + when {
                bal > Money.ZERO -> "বাকি ${bal.bn()}"
                bal < Money.ZERO -> "অগ্রিম ${(-bal).bn()}"
                else -> "পরিশোধ"
            })
            Spacer(Modifier.height(8.dp))
            MoneyField("পেমেন্ট (ট)", payAmount, { payAmount = it })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PaymentMethod.values().filter { it != PaymentMethod.DUE }.forEach { m ->
                    FilterChip(payMethod == m, { payMethod = m }, { Text(methodLabel(m)) })
                }
            }
            Button(onClick = { vm.recordPayment(sel, payAmount, payMethod); payAmount = "" }, modifier = Modifier.fillMaxWidth()) {
                Text("পেমেন্ট রেকর্ড করুন")
            }
            Spacer(Modifier.height(10.dp))
            Text("লেনদেনের ইতিহাস", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries.reversed()) { e ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${millisToDateString(e.createdAt)} · ${e.note.ifBlank { e.type }}")
                        Text((if (e.amountPaisa >= 0) "+" else "") + Money(e.amountPaisa).bn())
                    }
                }
            }
        }
    }
}
