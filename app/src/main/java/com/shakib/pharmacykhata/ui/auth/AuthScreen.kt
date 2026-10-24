package com.shakib.pharmacykhata.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.shakib.pharmacykhata.data.remote.AuthRepository
import com.shakib.pharmacykhata.domain.validation.Validators
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val loading: Boolean = false,
    val error: String? = null,
    val info: String? = null,
    val done: Boolean = false
)

@HiltViewModel
class AuthViewModel @Inject constructor(private val authRepo: AuthRepository) : ViewModel() {
    private val _state = MutableStateFlow(AuthUiState())
    val state = _state.asStateFlow()
    val isSignedIn: Boolean get() = authRepo.isSignedIn

    fun login(email: String, password: String) {
        val e = email.trim()
        when {
            !Validators.isValidEmail(e) -> { _state.value = AuthUiState(error = "সঠিক ইমেইল ঠিকানা দিন"); return }
            password.isBlank() -> { _state.value = AuthUiState(error = "পাসওয়ার্ড দিন"); return }
        }
        run { authRepo.login(e, password) }
    }

    fun register(
        email: String, password: String, confirmPassword: String, pharmacyName: String,
        ownerName: String, phone: String, address: String, district: String, drugLicence: String
    ) {
        val validPhone = Validators.bdPhone(phone)
        when {
            pharmacyName.isBlank() || ownerName.isBlank() ->
                { _state.value = AuthUiState(error = "ফার্মেসির নাম ও মালিকের নাম আবশ্যক"); return }
            validPhone == null ->
                { _state.value = AuthUiState(error = "সঠিক মোবাইল নম্বর দিন (01xxxxxxxxx)"); return }
            !Validators.isValidEmail(email.trim()) ->
                { _state.value = AuthUiState(error = "সঠিক ইমেইল ঠিকানা দিন"); return }
            password.length < 6 ->
                { _state.value = AuthUiState(error = "পাসওয়ার্ড অন্তত ৬ অক্ষরের হতে হবে"); return }
            password != confirmPassword ->
                { _state.value = AuthUiState(error = "পাসওয়ার্ড দুটি মিলছে না — আবার লিখুন"); return }
        }
        run {
            authRepo.register(
                email.trim(), password, pharmacyName.trim(), ownerName.trim(),
                validPhone, address.trim(), district.trim(), drugLicence.ifBlank { null }
            )
        }
    }

    fun resetPassword(email: String) {
        val e = email.trim()
        if (!Validators.isValidEmail(e)) {
            _state.value = AuthUiState(error = "পাসওয়ার্ড রিসেটের জন্য আগে সঠিক ইমেইল ঠিকানা লিখুন"); return
        }
        viewModelScope.launch {
            _state.value = AuthUiState(loading = true)
            try {
                authRepo.sendPasswordReset(e)
                _state.value = AuthUiState(info = "পাসওয়ার্ড রিসেটের লিংক আপনার ইমেইলে পাঠানো হয়েছে। ইমেইল চেক করুন।")
            } catch (ex: Exception) {
                _state.value = AuthUiState(error = friendlyError(ex))
            }
        }
    }

    fun clearMessages() { _state.value = AuthUiState() }

    private fun run(block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.value = AuthUiState(loading = true)
            try {
                block()
                _state.value = AuthUiState(done = true)
            } catch (e: Exception) {
                _state.value = AuthUiState(error = friendlyError(e))
            }
        }
    }

    companion object {
        /**
         * Raw Firebase exception text ("An internal error has occurred…") must never
         * reach the user — map everything to a clear Bengali message.
         */
        fun friendlyError(e: Exception): String = when {
            e is FirebaseAuthWeakPasswordException ->
                "পাসওয়ার্ডটি খুব দুর্বল। অন্তত ৬ অক্ষরের শক্ত পাসওয়ার্ড দিন।"
            e is FirebaseAuthUserCollisionException ->
                "এই ইমেইল দিয়ে ইতিমধ্যে একটি অ্যাকাউন্ট আছে। লগইন করুন, অথবা অন্য ইমেইল ব্যবহার করুন।"
            e is FirebaseAuthInvalidUserException ->
                "এই ইমেইল দিয়ে কোনো অ্যাকাউন্ট পাওয়া যায়নি। আগে রেজিস্ট্রেশন করুন।"
            e is FirebaseAuthInvalidCredentialsException ->
                "ইমেইল অথবা পাসওয়ার্ড ভুল হয়েছে। আবার চেষ্টা করুন।"
            e is FirebaseNetworkException ->
                "ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। সংযোগ চেক করে আবার চেষ্টা করুন।"
            e.message?.contains("API key not valid") == true ->
                "সার্ভারের সাথে সংযোগে সমস্যা হচ্ছে। কিছুক্ষণ পর আবার চেষ্টা করুন; সমস্যা থাকলে সাপোর্টে জানান।"
            else -> "কিছু একটা সমস্যা হয়েছে। আবার চেষ্টা করুন।"
        }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
    supportingText: String? = null,
    imeAction: ImeAction = ImeAction.Next
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        supportingText = if (supportingText != null) ({ Text(supportingText) }) else null,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = imeAction),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "পাসওয়ার্ড লুকান" else "পাসওয়ার্ড দেখুন"
                )
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
fun AuthScreen(onAuthenticated: () -> Unit, vm: AuthViewModel = hiltViewModel()) {
    val state by vm.state.collectAsState()
    var isRegister by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var pharmacyName by remember { mutableStateOf("") }
    var ownerName by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var district by remember { mutableStateOf("") }
    var drugLicence by remember { mutableStateOf("") }

    LaunchedEffect(state.done) { if (state.done) onAuthenticated() }

    val confirmMismatch = isRegister && confirmPassword.isNotEmpty() && confirmPassword != password

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("ফার্মেসি খাতা Pro", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(if (isRegister) "নতুন ফার্মেসি রেজিস্ট্রেশন — ১৪ দিনের ফ্রি ট্রায়াল" else "আপনার ফার্মেসি অ্যাকাউন্টে লগইন করুন")
        Spacer(Modifier.height(20.dp))

        if (!state.error.isNullOrBlank()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(state.error!!, color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }
        if (!state.info.isNullOrBlank()) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
                Text(state.info!!, color = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.padding(12.dp))
            }
            Spacer(Modifier.height(12.dp))
        }

        if (isRegister) {
            OutlinedTextField(pharmacyName, { pharmacyName = it }, label = { Text("ফার্মেসির নাম *") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(ownerName, { ownerName = it }, label = { Text("মালিকের নাম *") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(phone, { phone = it }, label = { Text("মোবাইল নম্বর * (01xxxxxxxxx)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("এই নম্বরেই সাপোর্ট ও অ্যাকাউন্ট যাচাই হবে") })
            Spacer(Modifier.height(10.dp))
        }
        OutlinedTextField(email, { email = it }, label = { Text("ইমেইল *") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next), modifier = Modifier.fillMaxWidth(), singleLine = true)
        Spacer(Modifier.height(10.dp))
        PasswordField(password, { password = it }, "পাসওয়ার্ড *")
        if (isRegister) {
            Spacer(Modifier.height(10.dp))
            PasswordField(
                confirmPassword, { confirmPassword = it }, "পাসওয়ার্ড আবার লিখুন *",
                isError = confirmMismatch,
                supportingText = if (confirmMismatch) "পাসওয়ার্ড দুটি মিলছে না" else "নিশ্চিত করতে একই পাসওয়ার্ড আবার লিখুন"
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(address, { address = it }, label = { Text("ঠিকানা") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(district, { district = it }, label = { Text("জেলা") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(drugLicence, { drugLicence = it }, label = { Text("ড্রাগ লাইসেন্স নম্বর (ঐচ্ছিক)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done))
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                if (isRegister) vm.register(email, password, confirmPassword, pharmacyName, ownerName, phone, address, district, drugLicence)
                else vm.login(email, password)
            },
            enabled = !state.loading && !confirmMismatch,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            if (state.loading) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text("অপেক্ষা করুন…")
            } else {
                Text(if (isRegister) "রেজিস্ট্রেশন করুন" else "লগইন")
            }
        }
        if (!isRegister) {
            TextButton(onClick = { vm.resetPassword(email) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth()) {
                Text("পাসওয়ার্ড ভুলে গেছেন? রিসেট করুন")
            }
        }
        TextButton(onClick = { isRegister = !isRegister; vm.clearMessages() }, modifier = Modifier.fillMaxWidth()) {
            Text(if (isRegister) "ইতিমধ্যে অ্যাকাউন্ট আছে? লগইন করুন" else "নতুন ফার্মেসি? রেজিস্ট্রেশন করুন")
        }
    }
}
