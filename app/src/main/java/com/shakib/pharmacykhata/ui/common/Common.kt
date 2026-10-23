package com.shakib.pharmacykhata.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.shakib.pharmacykhata.domain.money.BengaliDigits
import com.shakib.pharmacykhata.domain.money.Money
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Money is displayed in Bengali digits with lakh grouping throughout the app. */
fun Money.bn(): String = formatBn()

fun todayEpochDay(): Long = LocalDate.now().toEpochDay()

fun epochDayToDateString(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("bn", "BD")))

fun millisToDateString(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale("bn", "BD")))

fun expiryInputToEpochDay(input: String): Long? =
    com.shakib.pharmacykhata.domain.validation.Validators.parseExpiry(input)

/** Bengali digits are converted back to English before parsing anywhere in the app. */
fun String.englishDigits(): String = BengaliDigits.toEnglish(this)

@Composable
fun MoneyField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true, isError = isError, modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, isError: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true, isError = isError, modifier = modifier.fillMaxWidth()
    )
}

@Composable
fun ErrorText(message: String?) {
    if (!message.isNullOrBlank()) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Saleability guard: expired subscription blocks NEW sales only — data stays readable/exportable. */
fun subscriptionBannerText(statusName: String, trialEndsAt: Long, expiresAt: Long): String? {
    val now = System.currentTimeMillis()
    return when (statusName) {
        "EXPIRED" -> "সাবস্ক্রিপশন শেষ — নতুন বিক্রি বন্ধ আছে, তবে সব ডেটা দেখা ও export করা যাবে।"
        "TRIAL" -> if (trialEndsAt > now) {
            val days = ((trialEndsAt - now) / 86400000L).toInt()
            "ফ্রি ট্রায়াল চলছে — আর $days দিন বাকি।"
        } else "ট্রায়াল শেষ হয়ে গেছে।"
        "ACTIVE" -> if (expiresAt in 1..(now + 7 * 86400000L)) "সাবস্ক্রিপশনের মেয়াদ শীঘ্রই শেষ হবে।" else null
        else -> null
    }
}
