package com.shakib.pharmacykhata.ui.settings

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakib.pharmacykhata.data.remote.AuthRepository
import com.shakib.pharmacykhata.data.repository.PharmacyRepository
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.model.Subscription
import com.shakib.pharmacykhata.domain.model.SubscriptionStatus
import com.shakib.pharmacykhata.ui.common.millisToDateString
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepo: AuthRepository,
    private val repo: PharmacyRepository,
    val session: SessionStore
) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val _backupJson = MutableStateFlow<String?>(null)
    val backupJson = _backupJson.asStateFlow()

    val profile get() = authRepo.cachedProfile()
    val subscription: Subscription get() = Subscription(
        status = runCatching { SubscriptionStatus.valueOf(session.subscriptionStatus) }.getOrDefault(SubscriptionStatus.TRIAL),
        plan = if (session.subscriptionStatus == "ACTIVE") "ACTIVE" else "TRIAL",
        trialEndsAt = session.trialEndsAt, expiresAt = session.expiresAt
    )

    fun setPin(pin: String) {
        if (pin.length !in 4..6 || !pin.all { it.isDigit() }) { _message.value = "PIN ৪–৬ সংখ্যার হতে হবে"; return }
        session.pinHash = SessionStore.hashPin(pin)
        _message.value = "অ্যাপ লক PIN সেট হয়েছে"
    }
    fun removePin() { session.pinHash = null; _message.value = "PIN সরানো হয়েছে" }

    fun exportBackup() {
        val pid = session.pharmacyId ?: return
        viewModelScope.launch {
            try {
                _backupJson.value = repo.exportBackupJson(pid)
                _message.value = "ব্যাকআপ তৈরি হয়েছে (version 1) — শেয়ার করুন"
            } catch (e: Exception) { _message.value = e.localizedMessage ?: "ব্যাকআপ ব্যর্থ" }
        }
    }

    fun signOut(onDone: () -> Unit) {
        authRepo.signOut()
        onDone()
    }
}

@Composable
fun SettingsScreen(onSignedOut: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val message by vm.message.collectAsState()
    val backupJson by vm.backupJson.collectAsState()
    var pin by remember { mutableStateOf("") }
    val profile = vm.profile
    val sub = vm.subscription
    val now = System.currentTimeMillis()

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("সেটিংস", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("ফার্মেসি প্রোফাইল", style = MaterialTheme.typography.titleMedium)
                Text("ফার্মেসি: ${profile?.pharmacyName ?: vm.session.pharmacyName}")
                Text("মালিক: ${profile?.ownerName ?: vm.session.ownerName}")
                Text("ইমেইল: ${profile?.email ?: ""}")
                if (!profile?.phone.isNullOrBlank()) Text("মোবাইল: ${profile?.phone}")
                if (!profile?.district.isNullOrBlank()) Text("জেলা: ${profile?.district}")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("সাবস্ক্রিপশন", style = MaterialTheme.typography.titleMedium)
                Text("অবস্থা: ${sub.effectiveStatus(now).name}")
                if (sub.status == SubscriptionStatus.TRIAL && sub.trialEndsAt > 0)
                    Text("ট্রায়াল শেষ: ${millisToDateString(sub.trialEndsAt)}")
                if (sub.status == SubscriptionStatus.ACTIVE && sub.expiresAt > 0)
                    Text("মেয়াদ শেষ: ${millisToDateString(sub.expiresAt)}")
                Text("প্ল্যান সক্রিয় করতে Shakib-এর সঙ্গে যোগাযোগ করুন (বিকাশ/নগদ পেমেন্টের পর ম্যানুয়াল সক্রিয়করণ)।")
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("অ্যাপ লক PIN", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(pin, { pin = it.filter { c -> c.isDigit() }.take(6) },
                    label = { Text("নতুন PIN (৪–৬ সংখ্যা)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.setPin(pin); pin = "" }) { Text("PIN সেট করুন") }
                    OutlinedButton(onClick = { vm.removePin() }) { Text("PIN সরান") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ব্যাকআপ", style = MaterialTheme.typography.titleMedium)
                Text("সব ডেটার JSON export (version সহ) — নিরাপদ জায়গায় রেখে দিন।")
                Button(onClick = { vm.exportBackup() }) { Text("ব্যাকআপ তৈরি করুন") }
                backupJson?.let { json ->
                    OutlinedButton(onClick = {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_TEXT, json)
                            putExtra(Intent.EXTRA_SUBJECT, "PharmacyKhata Pro Backup")
                        }
                        context.startActivity(Intent.createChooser(intent, "ব্যাকআপ শেয়ার"))
                    }) { Text("ব্যাকআপ শেয়ার করুন") }
                }
            }
        }

        Spacer(Modifier.weight(1f))
        OutlinedButton(
            onClick = { vm.signOut(onSignedOut) },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
        ) { Text("সাইন আউট") }
    }
}
