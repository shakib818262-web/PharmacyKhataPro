package com.shakib.pharmacykhata

import com.shakib.pharmacykhata.domain.money.Money
import com.shakib.pharmacykhata.domain.money.MoneyFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {
    @Test fun parse_plainTaka() {
        assertEquals(Money(30000), Money.parse("300"))
        assertEquals(Money(125050), Money.parse("1,250.50"))
        assertEquals(Money(50), Money.parse("0.5"))
    }
    @Test fun parse_bengaliDigits() {
        assertEquals(Money(50000), Money.parse("৫০০"))
        assertEquals(Money(125050), Money.parse("১,২৫০.৫০"))
    }
    @Test fun parse_invalidReturnsNullNotZero() {
        assertNull(Money.parse("abc"))
        assertNull(Money.parse(""))
        assertNull(Money.parse(null))
        assertNull(Money.parse("1.234")) // more than 2 decimal places: reject, don't guess
    }
    @Test fun lakhGrouping() {
        assertEquals("999", MoneyFormat.groupLakh("999"))
        assertEquals("1,000", MoneyFormat.groupLakh("1000"))
        assertEquals("1,00,000", MoneyFormat.groupLakh("100000"))
        assertEquals("1,00,00,000", MoneyFormat.groupLakh("10000000"))
        assertEquals("12,34,567", MoneyFormat.groupLakh("1234567"))
    }
    @Test fun format_bengali() {
        assertEquals("৳১,০০,০০০", Money.ofTaka(100000).formatBn())
        assertEquals("৳৩৫০", Money(35000).formatBn())
        assertEquals("৳১,২৫০.৫০", Money(125050).formatBn())
    }
    @Test fun arithmetic() {
        assertEquals(Money(150), Money(100) + Money(50))
        assertEquals(Money(200), Money(50) * 4)
        assertEquals(Money(-10), Money(10) - Money(20))
    }
}
