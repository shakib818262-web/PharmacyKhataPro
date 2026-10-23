package com.shakib.pharmacykhata.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.shakib.pharmacykhata.data.session.SessionStore
import com.shakib.pharmacykhata.domain.model.PharmacyProfile
import com.shakib.pharmacykhata.domain.model.Subscription
import com.shakib.pharmacykhata.domain.model.SubscriptionStatus
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tenancy model:
 *  - Firebase Auth user = pharmacy owner. uid == pharmacyId.
 *  - pharmacy_profiles/{uid} holds registration metadata + subscription. This is the ONLY
 *    document the super-admin can read (see firestore.rules) — never business data.
 *  - Business data lives on-device in Room, scoped by pharmacyId; cloud business data
 *    (when sync is enabled) lives under pharmacies/{pharmacyId}/... with matching rules.
 *  - Trial start is a Firestore SERVER timestamp; the client cannot extend its trial by
 *    changing the phone clock, and firestore.rules forbid the owner from editing the
 *    subscription fields after creation.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val session: SessionStore
) {
    val currentUid: String? get() = auth.currentUser?.uid
    val isSignedIn: Boolean get() = auth.currentUser != null

    suspend fun register(
        email: String, password: String,
        pharmacyName: String, ownerName: String, phone: String,
        address: String, district: String, drugLicenceNo: String?
    ): PharmacyProfile {
        val result = auth.createUserWithEmailAndPassword(email.trim(), password).await()
        val uid = result.user?.uid ?: throw IllegalStateException("Registration failed")
        val doc = hashMapOf<String, Any?>(
            "uid" to uid,
            "pharmacyId" to uid,
            "pharmacyName" to pharmacyName.trim(),
            "ownerName" to ownerName.trim(),
            "phone" to phone,
            "email" to email.trim(),
            "address" to address.trim(),
            "district" to district.trim(),
            "drugLicenceNo" to drugLicenceNo?.trim()?.ifEmpty { null },
            "createdAt" to FieldValue.serverTimestamp(),
            "lastActiveAt" to FieldValue.serverTimestamp(),
            "subscription" to hashMapOf<String, Any?>(
                "status" to "TRIAL",
                "plan" to "TRIAL",
                "trialStartAt" to FieldValue.serverTimestamp(),
                "trialDays" to TRIAL_DAYS,
                "expiresAt" to null
            )
        )
        firestore.collection("pharmacy_profiles").document(uid).set(doc).await()
        // Read back so trialEndsAt is computed from the SERVER timestamp, not the device clock.
        val profile = fetchProfile(uid) ?: throw IllegalStateException("Profile creation failed")
        cacheSession(profile)
        return profile
    }

    suspend fun login(email: String, password: String): PharmacyProfile {
        val result = auth.signInWithEmailAndPassword(email.trim(), password).await()
        val uid = result.user?.uid ?: throw IllegalStateException("Login failed")
        val profile = fetchProfile(uid) ?: throw IllegalStateException("Profile not found for this account")
        firestore.collection("pharmacy_profiles").document(uid)
            .update("lastActiveAt", FieldValue.serverTimestamp()).await()
        cacheSession(profile)
        return profile
    }

    suspend fun fetchProfile(uid: String): PharmacyProfile? {
        val snap = firestore.collection("pharmacy_profiles").document(uid).get().await()
        if (!snap.exists()) return null
        val sub = snap.get("subscription") as? Map<*, *>
        val trialStart = (sub?.get("trialStartAt") as? com.google.firebase.Timestamp)?.toDate()?.time
        val trialDays = (sub?.get("trialDays") as? Number)?.toLong() ?: TRIAL_DAYS
        val expiresAt = (sub?.get("expiresAt") as? com.google.firebase.Timestamp)?.toDate()?.time ?: 0L
        val status = try {
            SubscriptionStatus.valueOf((sub?.get("status") as? String) ?: "TRIAL")
        } catch (_: Exception) { SubscriptionStatus.TRIAL }
        return PharmacyProfile(
            uid = snap.getString("uid") ?: uid,
            pharmacyId = snap.getString("pharmacyId") ?: uid,
            pharmacyName = snap.getString("pharmacyName") ?: "",
            ownerName = snap.getString("ownerName") ?: "",
            phone = snap.getString("phone") ?: "",
            email = snap.getString("email") ?: "",
            address = snap.getString("address") ?: "",
            district = snap.getString("district") ?: "",
            drugLicenceNo = snap.getString("drugLicenceNo"),
            createdAt = snap.getTimestamp("createdAt")?.toDate()?.time ?: 0L,
            lastActiveAt = snap.getTimestamp("lastActiveAt")?.toDate()?.time ?: 0L,
            subscription = Subscription(
                status = status,
                plan = (sub?.get("plan") as? String) ?: "TRIAL",
                trialEndsAt = if (trialStart != null) trialStart + trialDays * 24L * 3600L * 1000L else 0L,
                expiresAt = expiresAt
            )
        )
    }

    fun cachedProfile(): PharmacyProfile? {
        val pid = session.pharmacyId ?: return null
        return PharmacyProfile(
            uid = pid, pharmacyId = pid,
            pharmacyName = session.pharmacyName, ownerName = session.ownerName,
            phone = "", email = auth.currentUser?.email ?: "", address = "", district = "",
            subscription = Subscription(
                status = try { SubscriptionStatus.valueOf(session.subscriptionStatus) } catch (_: Exception) { SubscriptionStatus.TRIAL },
                trialEndsAt = session.trialEndsAt, expiresAt = session.expiresAt
            )
        )
    }

    private fun cacheSession(p: PharmacyProfile) {
        session.pharmacyId = p.pharmacyId
        session.pharmacyName = p.pharmacyName
        session.ownerName = p.ownerName
        session.subscriptionStatus = p.subscription.status.name
        session.trialEndsAt = p.subscription.trialEndsAt
        session.expiresAt = p.subscription.expiresAt
    }

    fun signOut() {
        auth.signOut()
        session.clear()
    }

    suspend fun sendPasswordReset(email: String) {
        auth.sendPasswordResetEmail(email.trim()).await()
    }

    companion object { const val TRIAL_DAYS = 14L }
}
