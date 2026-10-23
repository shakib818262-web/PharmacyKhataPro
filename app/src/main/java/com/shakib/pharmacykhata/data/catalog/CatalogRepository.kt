package com.shakib.pharmacykhata.data.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: CatalogDao
) {
    /** Import the bundled seed CSV on first run. Idempotent: skips when already populated. */
    suspend fun ensureSeeded() {
        if (dao.count() > 0) return
        val text = context.assets.open(SEED_ASSET).bufferedReader().readText()
        val items = parseCsv(text)
        if (items.isNotEmpty()) dao.insertAll(items)
    }

    /** Replace the bundled catalog with a newer full catalog (same CSV schema). User medicines are untouched. */
    suspend fun replaceCatalog(csvText: String): Int {
        val items = parseCsv(csvText)
        dao.clearCatalog()
        dao.insertAll(items)
        return items.size
    }

    suspend fun search(pharmacyId: String, query: String, limit: Int = 20): List<CatalogSuggestion> {
        val q = query.trim()
        if (q.length < 2) return emptyList()
        val user = dao.searchUser(pharmacyId, q, limit).map { it.toSuggestion() }
        val prefix = dao.searchBrandPrefix(q, limit).map { it.toSuggestion() }
        val contains = if (prefix.size < limit) dao.searchContains(q, limit - prefix.size).map { it.toSuggestion() } else emptyList()
        return (user + prefix + contains).distinctBy { it.catalogId to it.userId }.take(limit)
    }

    suspend fun byBarcode(pharmacyId: String, barcode: String): CatalogSuggestion? {
        dao.userByBarcode(pharmacyId, barcode)?.let { return it.toSuggestion() }
        return dao.byBarcode(barcode)?.toSuggestion()
    }

    /** "Add custom medicine" — persists in the pharmacy's own catalog so it appears in future autocomplete. */
    suspend fun addCustomMedicine(
        pharmacyId: String, brandName: String, genericName: String, strength: String,
        form: String, manufacturer: String, packSize: Int, unitMrpPaisa: Long, barcode: String?
    ): CatalogSuggestion {
        val id = dao.insertUser(
            UserMedicineEntity(
                pharmacyId = pharmacyId, brandName = brandName.trim(), genericName = genericName.trim(),
                strength = strength.trim(), form = form.trim(), manufacturer = manufacturer.trim(),
                packSize = packSize.coerceAtLeast(1), unitMrpPaisa = unitMrpPaisa,
                barcode = barcode?.trim()?.ifEmpty { null }
            )
        )
        return CatalogSuggestion(null, id, brandName.trim(), genericName.trim(), strength.trim(), form.trim(),
            manufacturer.trim(), packSize.coerceAtLeast(1), unitMrpPaisa, barcode, isCustom = true)
    }

    companion object {
        const val SEED_ASSET = "medicines_seed.csv"

        /** Minimal RFC-4180-ish CSV parser (handles quoted fields with commas). */
        fun parseCsv(text: String): List<CatalogMedicineEntity> {
            val rows = mutableListOf<List<String>>()
            var field = StringBuilder(); var row = mutableListOf<String>()
            var inQuotes = false; var i = 0
            fun endField() { row.add(field.toString()); field = StringBuilder() }
            fun endRow() { endField(); rows.add(row); row = mutableListOf() }
            while (i < text.length) {
                val c = text[i]
                when {
                    inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = !inQuotes
                    !inQuotes && c == ',' -> endField()
                    !inQuotes && c == '\n' -> { if (field.isNotEmpty() || row.isNotEmpty()) endRow() }
                    !inQuotes && c == '\r' -> Unit
                    else -> field.append(c)
                }
                i++
            }
            if (field.isNotEmpty() || row.isNotEmpty()) endRow()
            return rows.drop(1).mapNotNull { cols ->
                if (cols.size < 9) return@mapNotNull null
                val id = cols[0].trim().toLongOrNull() ?: return@mapNotNull null
                val brand = cols[1].trim(); if (brand.isEmpty()) return@mapNotNull null
                CatalogMedicineEntity(
                    id = id, brandName = brand, genericName = cols[2].trim(), strength = cols[3].trim(),
                    form = cols[4].trim(), manufacturer = cols[5].trim(),
                    packSize = cols[6].trim().toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    unitMrpPaisa = takaStringToPaisa(cols[7].trim()),
                    barcode = cols[8].trim().ifEmpty { null }
                )
            }
        }

        /** "12.50" -> 1250 without ever using floating point for money. */
        fun takaStringToPaisa(s: String): Long {
            val clean = s.replace(",", "")
            val parts = clean.split(".")
            val whole = parts[0].toLongOrNull() ?: return 0
            val frac = if (parts.size > 1) parts[1].padEnd(2, '0').take(2).toLongOrNull() ?: 0 else 0
            return whole * 100 + frac
        }
    }
}

private fun CatalogMedicineEntity.toSuggestion() = CatalogSuggestion(
    catalogId = id, userId = null, brandName = brandName, genericName = genericName, strength = strength,
    form = form, manufacturer = manufacturer, packSize = packSize, unitMrpPaisa = unitMrpPaisa,
    barcode = barcode, isCustom = false
)

private fun UserMedicineEntity.toSuggestion() = CatalogSuggestion(
    catalogId = null, userId = id, brandName = brandName, genericName = genericName, strength = strength,
    form = form, manufacturer = manufacturer, packSize = packSize, unitMrpPaisa = unitMrpPaisa,
    barcode = barcode, isCustom = true
)
