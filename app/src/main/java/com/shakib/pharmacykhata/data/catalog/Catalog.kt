package com.shakib.pharmacykhata.data.catalog

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Bundled medicine catalog lives in its OWN database (catalog.db) so a catalog
 * update (new asset / downloaded DB) can replace it without touching business data.
 * The pharmacy's own custom medicines live in a separate table of the same DB,
 * keyed by pharmacyId, and survive catalog updates.
 *
 * Seed/Full catalog CSV schema (drop-in replacement — see README):
 *   id,brand_name,generic_name,strength,form,manufacturer,pack_size,unit_mrp_taka,barcode
 * The full catalog is the cleaned Kaggle "Assorted Medicine Dataset of Bangladesh"
 * (~21k brands) converted to exactly this schema in assets/medicines_seed.csv.
 */
@Entity(tableName = "catalog_medicines", indices = [androidx.room.Index("brandName"), androidx.room.Index("genericName")])
data class CatalogMedicineEntity(
    @PrimaryKey val id: Long,
    val brandName: String,
    val genericName: String = "",
    val strength: String = "",
    val form: String = "",
    val manufacturer: String = "",
    val packSize: Int = 1,
    val unitMrpPaisa: Long = 0,
    val barcode: String? = null
) {
    val displayName: String get() = listOf(brandName, strength).filter { it.isNotBlank() }.joinToString(" ")
}

@Entity(tableName = "user_medicines", indices = [androidx.room.Index("pharmacyId"), androidx.room.Index("brandName")])
data class UserMedicineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pharmacyId: String,
    val brandName: String,
    val genericName: String = "",
    val strength: String = "",
    val form: String = "",
    val manufacturer: String = "",
    val packSize: Int = 1,
    val unitMrpPaisa: Long = 0,
    val barcode: String? = null
) {
    val displayName: String get() = listOf(brandName, strength).filter { it.isNotBlank() }.joinToString(" ")
}

@Dao
interface CatalogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(items: List<CatalogMedicineEntity>)
    @Query("SELECT COUNT(*) FROM catalog_medicines") suspend fun count(): Int
    @Query("DELETE FROM catalog_medicines") suspend fun clearCatalog()

    /** Indexed prefix search first (fast, matches "type name → suggestions"), then contains fallback merged by the repository. */
    @Query("SELECT * FROM catalog_medicines WHERE brandName LIKE :prefix || '%' COLLATE NOCASE ORDER BY brandName LIMIT :limit")
    suspend fun searchBrandPrefix(prefix: String, limit: Int): List<CatalogMedicineEntity>
    @Query("SELECT * FROM catalog_medicines WHERE (brandName LIKE '%' || :q || '%' COLLATE NOCASE OR genericName LIKE '%' || :q || '%' COLLATE NOCASE) AND brandName NOT LIKE :q || '%' COLLATE NOCASE ORDER BY brandName LIMIT :limit")
    suspend fun searchContains(q: String, limit: Int): List<CatalogMedicineEntity>
    @Query("SELECT * FROM catalog_medicines WHERE barcode = :barcode LIMIT 1")
    suspend fun byBarcode(barcode: String): CatalogMedicineEntity?

    @Insert suspend fun insertUser(m: UserMedicineEntity): Long
    @Query("SELECT * FROM user_medicines WHERE pharmacyId = :pid AND (brandName LIKE :prefix || '%' COLLATE NOCASE OR brandName LIKE '%' || :prefix || '%' COLLATE NOCASE) ORDER BY brandName LIMIT :limit")
    suspend fun searchUser(pid: String, prefix: String, limit: Int): List<UserMedicineEntity>
    @Query("SELECT * FROM user_medicines WHERE pharmacyId = :pid AND barcode = :barcode LIMIT 1")
    suspend fun userByBarcode(pid: String, barcode: String): UserMedicineEntity?
}

@Database(entities = [CatalogMedicineEntity::class, UserMedicineEntity::class], version = 1, exportSchema = false)
abstract class CatalogDatabase : RoomDatabase() {
    abstract fun catalogDao(): CatalogDao
    companion object { const val DB_NAME = "catalog.db" }
}

/** Unified search result shown in autocomplete, whether from the bundled catalog or the pharmacy's own additions. */
data class CatalogSuggestion(
    val catalogId: Long?,       // null for user-added
    val userId: Long?,          // null for bundled catalog rows
    val brandName: String,
    val genericName: String,
    val strength: String,
    val form: String,
    val manufacturer: String,
    val packSize: Int,
    val unitMrpPaisa: Long,
    val barcode: String?,
    val isCustom: Boolean
) {
    val displayName: String get() = listOf(brandName, strength).filter { it.isNotBlank() }.joinToString(" ")
    val subtitle: String get() = listOf(genericName, form, manufacturer).filter { it.isNotBlank() }.joinToString(" · ")
}
