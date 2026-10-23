package com.shakib.pharmacykhata.domain.money

/**
 * Money is ALWAYS integer paisa (1 taka = 100 paisa). Double is never used for money
 * anywhere in domain/data layers — this was a root cause of errors in the old app.
 */
@JvmInline
value class Money(val paisa: Long) : Comparable<Money> {
    operator fun plus(other: Money) = Money(paisa + other.paisa)
    operator fun minus(other: Money) = Money(paisa - other.paisa)
    operator fun times(qty: Int) = Money(paisa * qty)
    operator fun unaryMinus() = Money(-paisa)
    override fun compareTo(other: Money): Int = paisa.compareTo(other.paisa)
    val isNegative: Boolean get() = paisa < 0
    val isZero: Boolean get() = paisa == 0L

    /** Taka as a decimal string with exactly 2 fraction digits, English digits, lakh grouping. */
    fun formatEn(): String = MoneyFormat.format(paisa, bengaliDigits = false)

    /** Same as [formatEn] but with Bengali digits, e.g. ৳১,০০,০০০.৫০ */
    fun formatBn(): String = MoneyFormat.format(paisa, bengaliDigits = true)

    override fun toString(): String = formatEn()

    companion object {
        val ZERO = Money(0)
        fun ofTaka(taka: Long) = Money(taka * 100)

        /**
         * Parse user input that may contain Bengali digits, a ৳ sign, commas, or a
         * decimal fraction ("১,২৫০.৫০", "1250.5", "300"). Returns null on invalid input
         * instead of silently returning 0 (old app bug).
         */
        fun parse(input: String?): Money? {
            if (input.isNullOrBlank()) return null
            val cleaned = BengaliDigits.toEnglish(input.trim())
                .replace("৳", "").replace(",", "").replace(" ", "")
            if (cleaned.isEmpty() || cleaned == "." || cleaned == "-") return null
            val negative = cleaned.startsWith("-")
            val body = cleaned.removePrefix("-")
            if (body.count { it == '.' } > 1) return null
            val parts = body.split(".")
            val whole = parts[0].ifEmpty { "0" }
            if (!whole.all { it.isDigit() }) return null
            val fracRaw = if (parts.size > 1) parts[1] else ""
            if (!fracRaw.all { it.isDigit() } || fracRaw.length > 2) return null
            val frac = fracRaw.padEnd(2, '0').ifEmpty { "00" }
            val paisa = whole.toLongOrNull()?.times(100)?.plus(frac.toLong()) ?: return null
            return Money(if (negative) -paisa else paisa)
        }
    }
}

object BengaliDigits {
    private val bn = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
    fun toBengali(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') bn[c - '0'] else c)
        return sb.toString()
    }
    fun toEnglish(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) {
            val idx = bn.indexOf(c)
            sb.append(if (idx >= 0) ('0' + idx) else c)
        }
        return sb.toString()
    }
}

/** Indian/Bangladeshi lakh grouping: last 3 digits, then groups of 2 (1,00,00,000). */
object MoneyFormat {
    fun format(paisa: Long, bengaliDigits: Boolean): String {
        val negative = paisa < 0
        val abs = kotlin.math.abs(paisa)
        val taka = abs / 100
        val frac = (abs % 100).toInt()
        val grouped = groupLakh(taka.toString())
        val base = if (frac == 0) grouped else "$grouped.${frac.toString().padStart(2, '0')}"
        val withSign = (if (negative) "-" else "") + "৳" + base
        return if (bengaliDigits) BengaliDigits.toBengali(withSign) else withSign
    }

    fun groupLakh(digits: String): String {
        if (digits.length <= 3) return digits
        val head = digits.dropLast(3)
        val tail = digits.takeLast(3)
        val sb = StringBuilder()
        var i = head.length % 2
        if (i > 0) sb.append(head, 0, i).append(',')
        while (i < head.length) {
            sb.append(head, i, i + 2).append(',')
            i += 2
        }
        sb.append(tail)
        return sb.toString()
    }
}
