package com.shakib.pharmacykhata.data.session

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Caches the signed-in pharmacy profile locally (SharedPreferences "session")
 * so the app works offline-first. The pharmacyId is ALWAYS the Firebase uid of the
 * pharmacy owner — every repository query is scoped by it, so a different account
 * signing in on the same phone never sees the previous pharmacy's data.
 */
@Singleton
class SessionStore @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    var pharmacyId: String?
        get() = prefs.getString("pharmacyId", null)
        set(v) { prefs.edit().putString("pharmacyId", v).apply() }

    var pharmacyName: String
        get() = prefs.getString("pharmacyName", "") ?: ""
        set(v) { prefs.edit().putString("pharmacyName", v).apply() }

    var ownerName: String
        get() = prefs.getString("ownerName", "") ?: ""
        set(v) { prefs.edit().putString("ownerName", v).apply() }

    var subscriptionStatus: String
        get() = prefs.getString("subStatus", "TRIAL") ?: "TRIAL"
        set(v) { prefs.edit().putString("subStatus", v).apply() }

    var trialEndsAt: Long
        get() = prefs.getLong("trialEndsAt", 0L)
        set(v) { prefs.edit().putLong("trialEndsAt", v).apply() }

    var expiresAt: Long
        get() = prefs.getLong("expiresAt", 0L)
        set(v) { prefs.edit().putLong("expiresAt", v).apply() }

    /** Optional local app-lock PIN (stored as a salted hash, never plaintext). */
    var pinHash: String?
        get() = prefs.getString("pinHash", null)
        set(v) { prefs.edit().putString("pinHash", v).apply() }

    fun clear() { prefs.edit().clear().apply() }

    companion object {
        fun hashPin(pin: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            return digest.digest("pharmacykhata::$pin".toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
