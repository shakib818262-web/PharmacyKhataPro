package com.shakib.pharmacykhata.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.shakib.pharmacykhata.data.remote.AuthRepository
import com.shakib.pharmacykhata.domain.validation.Validators
import com.shakib.pharmacykhata.ui.common.ErrorText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(val loading: Boolean = false, val error: String? = null, val done: Boolean = false)

@HiltViewModel
class AuthViewModel @Inject constructor(private val authRepo: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state = _state.asStateFlow()
    val isSignedIn: Boolean get() = authRepo.isSignedIn

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            _state.value = AuthUiState(error = "ইমেইল ও পাসওয়ার্ড দিন"); return
        }
        run("লগইন ব্যর্থ হয়েছে") { authRepo.login(email, password) }
    }

    fun register(
        email: String, password: String, pharmacyName: String, ownerName: String,
        phone: String, address: String, district: String, drugLicence: String
    ) {
        val validPhone = Validators.bdPhone(phone)
        when {
            pharmacyName.isBlank() || ownerName.isBlank() -> { _state.value = AuthUiState(error = "ফার্মেসির নাম ও মালিকের নাম আবশ্যক"); return }
            validPhone == null -> { _state.value = AuthUiState(error = "সঠিক মোবাইল নম্বর দিন (01xxxxxxxxx)"); return }
            password.length < 6 -> { _state.value = AuthUiState(error = "পাসওয়ার্ড অন্তত ৬ অক্ষরের হতে হবে"); return }
            email.isBlank() -> { _state.value = AuthUiState(error = "ইমেইল দিন"); return }
        }
        run("রেজিস্ট্রেশন ব্যর্থ হয়েছে") {
            authRepo.register(email, password, pharmacyName, ownerName, validPhone, address, district, drugLicence.ifBlank { null })
        }
    }

    private fun run(failMsg: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = AuthUiState(loading = true)
            try {
                block()
                _state.value = AuthUiState(done = true)
            } catch (e: Exception) {
                _state.value = AuthUiState(error = e.localizedMessage ?: failMsg)
            }
        }
    }
}

@Composable
fun AuthScreen(onAuthenticated: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var pharmacyName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var drugLicence by remember { mutableStateOf("") }

    LaunchedEffect(state.done) { if (state.done) onAuthenticated() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("ফার্মেসি খাতা Pro", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(if (isRegister) "নতুন ফার্মেসি রেজিস্ট্রেশন — ১৪ দিনের ফ্রি ট্রায়াল" else "আপনার ফার্মেসি অ্যাকাউন্টে লগইন করুন")
        Spacer(Modifier.height(20.dp))
        ErrorText(state.error)

        if (isRegister) {
            OutlinedTextField(pharmacyName, { pharmacyName = it }, label = { Text("ফার্মেসির নাম *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(ownerName, { ownerName = it }, label = { Text("মালিকের নাম *") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(phone, { phone = it }, label = { Text("মোবাইল নম্বর * (01xxxxxxxxx)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(email, { email = it }, label = { Text("ইমেইল *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(password, { password = it }, label = { Text("পাসওয়ার্ড *") },
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), singleLine = true)
        if (isRegister) {
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(address, { address = it }, label = { Text("ঠিকানা") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(district, { district = it }, label = { Text("জেলা") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(drugLicence, { drugLicence = it }, label = { Text("ড্রাগ লাইসেন্স নম্বর (ঐচ্ছিক)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (isRegister) vm.register(email, password, pharmacyName, ownerName, phone, address, district, drugLicence)
                else vm.login(email, password)
            },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text(if (state.loading) "অপেক্ষা করুন…" else if (isRegister) "রেজিস্ট্রেশন করুন" else "লগইন") }
        TextButton(onClick = { isRegister = !isRegister }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRegister) "ইতিমধ্যে অ্যাকাউন্ট আছে? লগইন করুন" else "নতুন ফার্মেসি? রেজিস্ট্রেশন করুন")
        }
    }
}
