package com.shakib.pharmacykhata.domain.validation

import com.shakib.pharmacykhata.domain.money.BengaliDigits
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

object Validators {
    private val BD_PHONE = Regex("^01[3-9]\\d{8}$")

    /** Accepts Bengali digits in the input. Returns the normalised 01xxxxxxxxx or null. */
    fun bdPhone(input: String?): String? {
        if (input.isNullOrBlank()) return null
        val digits = BengaliDigits.toEnglish(input.trim()).replace(" ", "").replace("-", "")
        // Strip the country code and restore the trunk 0: +8801712345678 / 8801712345678 -> 01712345678
        val cleaned = when {
            digits.startsWith("+880") -> "0" + digits.drop(4)
            digits.startsWith("880") && digits.length == 13 -> "0" + digits.drop(3)
            else -> digits
        }
        return if (BD_PHONE.matches(cleaned)) cleaned else null
    }

    fun isValidBdPhone(input: String?): Boolean = bdPhone(input) != null

    private val EMAIL = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    /** Pragmatic email shape check for form validation (deliverability is Firebase's job). */
    fun isValidEmail(input: String?): Boolean =
        !input.isNullOrBlank() && EMAIL.matches(input.trim())

    private val EXPIRY_FORMATS = listOf(
        DateTimeFormatter.ofPattern("dd/MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("MM/uuuu").withResolverStyle(ResolverStyle.STRICT),
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    )

    /**
     * Expiry must actually parse — the old app stored free text, so isExpired() could
     * never flag anything. "MM/yyyy" means the LAST day of that month.
     * Returns epoch-day or null when unparseable.
     */
    fun parseExpiry(input: String?): Long? {
        if (input.isNullOrBlank()) return null
        val s = BengaliDigits.toEnglish(input.trim())
        // MM/yyyy
        Regex("^(\\d{1,2})/(\\d{4})$").matchEntire(s)?.let { m ->
            val month = m.groupValues[1].toInt()
            val year = m.groupValues[2].toInt()
            if (month !in 1..12 || year !in 2000..2100) return null
            return LocalDate.of(year, month, 1).withDayOfMonth(
                LocalDate.of(year, month, 1).lengthOfMonth()
            ).toEpochDay()
        }
        for (fmt in EXPIRY_FORMATS) {
            try {
                return LocalDate.parse(s, fmt).toEpochDay()
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    fun formatExpiry(epochDay: Long): String =
        LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern("MM/yyyy"))

    fun positiveInt(input: String?): Int? =
        BengaliDigits.toEnglish(input?.trim().orEmpty()).toIntOrNull()?.takeIf { it > 0 }

    fun nonNegativeInt(input: String?): Int? =
        BengaliDigits.toEnglish(input?.trim().orEmpty()).toIntOrNull()?.takeIf { it >= 0 }
}
