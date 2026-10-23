# PharmacyKhata Pro — ProGuard/R8 rules
# Room
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
# Firestore model mapping (reflection on field names)
-keepclassmembers class com.shakib.pharmacykhata.data.remote.** { *; }
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
# Kotlin / coroutines
-keep class kotlin.Metadata { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
