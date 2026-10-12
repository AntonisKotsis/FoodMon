package com.ak.foodmon.food

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A packaged product's nutrition, per 100 g (or 100 ml for drinks). */
data class Product(
    val name: String,
    val kcalPer100g: Double,
    val proteinPer100g: Double,
    val carbsPer100g: Double,
    val fatPer100g: Double,
    /** Grams in one serving, if the package states it. */
    val servingGrams: Double?,
)

class LookupException(message: String) : Exception(message)

/** Looks up scanned barcodes in Open Food Facts. */
class ProductLookup {
    /** Returns null when the product is unknown or has no calorie data. */
    suspend fun lookup(barcode: String): Product? = withContext(Dispatchers.IO) {
        val connection = URL("$BASE_URL/$barcode?fields=$FIELDS").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            // Open Food Facts asks every app to identify itself.
            connection.setRequestProperty("User-Agent", "FoodMon/1.0 (Android)")
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parse(barcode, connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw LookupException("Open Food Facts error ($code). Please try again.")
            }
        } catch (e: IOException) {
            throw LookupException("Couldn't reach Open Food Facts. Check your internet connection.")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://world.openfoodfacts.org/api/v2/product"
        private const val FIELDS = "product_name,brands,nutriments,serving_quantity,serving_quantity_unit"
        private const val KJ_PER_KCAL = 4.184

        fun parse(barcode: String, json: String): Product? {
            val root = JSONObject(json)
            if (root.optInt("status") != 1) return null
            val product = root.optJSONObject("product") ?: return null
            val n = product.optJSONObject("nutriments") ?: return null

            fun value(key: String) = n.optDouble(key).takeUnless { it.isNaN() || it < 0 }
            val kcal = value("energy-kcal_100g")
                ?: (value("energy-kj_100g") ?: value("energy_100g"))?.let { it / KJ_PER_KCAL }
                ?: return null

            val title = product.optString("product_name").trim()
            val brand = product.optString("brands").substringBefore(',').trim()
            val name = when {
                title.isNotEmpty() && brand.isNotEmpty() && !title.contains(brand, ignoreCase = true) -> "$title ($brand)"
                title.isNotEmpty() -> title
                brand.isNotEmpty() -> brand
                else -> "Product $barcode"
            }

            // Millilitres count as grams; close enough for drinks.
            val unit = product.optString("serving_quantity_unit").lowercase()
            val serving = product.optDouble("serving_quantity")
                .takeIf { !it.isNaN() && it > 0 && unit in setOf("", "g", "ml") }

            return Product(
                name = name,
                kcalPer100g = kcal,
                proteinPer100g = value("proteins_100g") ?: 0.0,
                carbsPer100g = value("carbohydrates_100g") ?: 0.0,
                fatPer100g = value("fat_100g") ?: 0.0,
                servingGrams = serving,
            )
        }
    }
}