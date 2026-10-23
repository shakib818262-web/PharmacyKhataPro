package com.shakib.pharmacykhata

import com.shakib.pharmacykhata.domain.validation.Validators
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.LocalDate

class ValidatorsTest {
    @Test fun phone_validFormats() {
        assertEquals("01712345678", Validators.bdPhone("01712345678"))
        assertEquals("01712345678", Validators.bdPhone("+8801712345678"))
        assertEquals("01712345678", Validators.bdPhone("০১৭১২৩৪৫৬৭৮")) // Bengali digits
    }
    @Test fun phone_invalid() {
        assertNull(Validators.bdPhone("01212345678")) // 012 prefix not a BD operator prefix
        assertNull(Validators.bdPhone("0171234567"))  // too short
        assertNull(Validators.bdPhone("abc"))
        assertFalse(Validators.isValidBdPhone(null))
    }
    @Test fun expiry_monthYear_isLastDayOfMonth() {
        val day = Validators.parseExpiry("03/2027")
        assertEquals(LocalDate.of(2027, 3, 31).toEpochDay(), day)
    }
    @Test fun expiry_fullDate() {
        assertEquals(LocalDate.of(2027, 3, 15).toEpochDay(), Validators.parseExpiry("15/03/2027"))
    }
    @Test fun expiry_freeTextRejected() {
        // The old app stored free text and could never flag expiry — parsing must fail loudly instead.
        assertNull(Validators.parseExpiry("next year"))
        assertNull(Validators.parseExpiry("13/2027"))
    }
    @Test fun positiveInt_bengaliDigits() {
        assertEquals(50, Validators.positiveInt("৫০"))
        assertNull(Validators.positiveInt("0"))
        assertNull(Validators.positiveInt("-3"))
    }
    @Test fun subscription_expiredTrialCannotSell() {
        val past = System.currentTimeMillis() - 1000
        val sub = com.shakib.pharmacykhata.domain.model.Subscription(
            status = com.shakib.pharmacykhata.domain.model.SubscriptionStatus.TRIAL, trialEndsAt = past)
        assertEquals(com.shakib.pharmacykhata.domain.model.SubscriptionStatus.EXPIRED,
            sub.effectiveStatus(System.currentTimeMillis()))
        assertFalse(sub.canSell(System.currentTimeMillis()))
        assertTrue(sub.effectiveStatus(System.currentTimeMillis()) !=
            com.shakib.pharmacykhata.domain.model.SubscriptionStatus.TRIAL)
    }
}
