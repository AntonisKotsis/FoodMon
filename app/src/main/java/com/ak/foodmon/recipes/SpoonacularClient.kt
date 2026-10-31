package com.ak.foodmon.recipes

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class Ingredient(
    /** Spoonacular ingredient id, used to merge the same ingredient across recipes. 0 if unknown. */
    val id: Long,
    val name: String,
    val aisle: String,
    val amount: Double,
    val unit: String,
)

data class Recipe(
    val id: Long,
    val title: String,
    val image: String?,
    val servings: Int,
    val readyInMinutes: Int?,
    val kcalPerServing: Double?,
    val sourceUrl: String?,
    val spoonacularUrl: String?,
    val cuisines: List<String>,
    val ingredients: List<Ingredient>,
    val steps: List<String>,
)

/** A parsed recipe plus the JSON it came from, so it can be cached for up to an hour. */
class FetchedRecipe(val recipe: Recipe, val json: String)

/** Talks to the Spoonacular recipe API. Every call goes through [guard] to respect the free plan. */
class SpoonacularClient(private val guard: QuotaGuard, private val apiKey: suspend () -> String) {

    /** Low-calorie, high-protein main courses for [cuisine], in random order. */
    suspend fun search(cuisine: String, maxKcal: Int, number: Int = 30): List<FetchedRecipe> {
        val params = mapOf(
            "cuisine" to cuisine,
            "type" to "main course",
            "sort" to "random",
            "maxCalories" to maxKcal.toString(),
            "minProtein" to "20",
            "addRecipeInformation" to "true",
            "fillIngredients" to "true",
            "addRecipeInstructions" to "true",
            "number" to number.toString(),
        )
        return parseSearch(get("/recipes/complexSearch", params, searchCost(number)))
    }

    /** Full details for [ids] in one call. */
    suspend fun bulkInfo(ids: List<Long>): List<FetchedRecipe> {
        if (ids.isEmpty()) return emptyList()
        val params = mapOf("ids" to ids.joinToString(","), "includeNutrition" to "false")
        return parseBulk(get("/recipes/informationBulk", params, bulkCost(ids.size)))
    }

    private suspend fun get(path: String, params: Map<String, String>, cost: Double): String {
        val key = apiKey()
        if (key.isBlank()) throw RecipeException("Add your Spoonacular API key in Settings first.")
        val query = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return guard.run(cost) {
            withContext(Dispatchers.IO) {
                val first = request("$BASE_URL$path?$query", key)
                if (first.code != HTTP_TOO_MANY_REQUESTS) return@withContext first.toResult()
                // Too fast for the per-second limit: wait and try once more.
                delay(2_000)
                request("$BASE_URL$path?$query", key).toResult()
            }
        }
    }

    private class Raw(val code: Int, val body: String, val used: Double?, val left: Double?)

    private fun Raw.toResult(): QuotaResponse<String> = when (code) {
        HttpURLConnection.HTTP_OK -> QuotaResponse(body, used, left)
        HttpURLConnection.HTTP_UNAUTHORIZED -> throw RecipeException("The Spoonacular API key was rejected. Check it in Settings.")
        HTTP_PAYMENT_REQUIRED -> throw QuotaExhaustedException(
            "Spoonacular's daily limit of 50 points is used up. It resets at ${localResetTime()}.",
        )
        HTTP_TOO_MANY_REQUESTS -> throw RecipeException("Spoonacular is busy. Please try again in a moment.")
        else -> throw RecipeException("Spoonacular error ($code). Please try again.")
    }

    private fun request(url: String, key: String): Raw {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            // Sent as a header so the key never shows up in URLs or logs.
            connection.setRequestProperty("x-api-key", key)
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return Raw(
                code,
                body,
                connection.getHeaderField("X-API-Quota-Used")?.toDoubleOrNull(),
                connection.getHeaderField("X-API-Quota-Left")?.toDoubleOrNull(),
            )
        } catch (e: IOException) {
            throw RecipeException("Couldn't reach Spoonacular. Check your internet connection.")
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://api.spoonacular.com"
        private const val HTTP_PAYMENT_REQUIRED = 402
        private const val HTTP_TOO_MANY_REQUESTS = 429

        /**
         * complexSearch: 1 point + 0.01 per result, +1 for the nutrient filters, and +0.025 per
         * result for each of recipe information, ingredients and instructions.
         */
        fun searchCost(number: Int) = 2.0 + number * (0.01 + 3 * 0.025)

        /** informationBulk: 1 point for the first recipe and 0.5 for each one after it. */
        fun bulkCost(count: Int) = if (count <= 0) 0.0 else 1.0 + 0.5 * (count - 1)

        /** Quota resets at midnight UTC; shown in the phone's time zone. */
        fun localResetTime(zone: ZoneId = ZoneId.systemDefault()): String =
            LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC)
                .withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm"))

        fun parseSearch(json: String): List<FetchedRecipe> {
            val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return results.objects().mapNotNull { obj -> parseRecipe(obj)?.let { FetchedRecipe(it, obj.toString()) } }
        }

        fun parseBulk(json: String): List<FetchedRecipe> =
            JSONArray(json).objects().mapNotNull { obj -> parseRecipe(obj)?.let { FetchedRecipe(it, obj.toString()) } }

        /** Parses one recipe from either a search result or a bulk-information item. */
        fun parseRecipe(obj: JSONObject): Recipe? {
            val id = obj.optLong("id").takeIf { it > 0 } ?: return null
            val title = obj.optString("title").trim().ifEmpty { return null }

            // Search results list ingredients as "missed"/"used"; bulk results as "extendedIngredients".
            val extended = obj.optJSONArray("extendedIngredients")
            val sources = if (extended != null && extended.length() > 0) listOf(extended)
            else listOf("missedIngredients", "usedIngredients").mapNotNull { obj.optJSONArray(it) }
            val ingredients = sources.flatMap { it.objects() }.mapNotNull(::parseIngredient)
                .distinctBy { Triple(it.id, it.name, it.unit) }

            val kcal = obj.optJSONObject("nutrition")?.optJSONArray("nutrients")?.objects()
                ?.firstOrNull { it.optString("name").equals("Calories", ignoreCase = true) }
                ?.optDouble("amount")?.takeIf { !it.isNaN() }

            val steps = obj.optJSONArray("analyzedInstructions")?.objects()
                ?.flatMap { section -> section.optJSONArray("steps")?.objects().orEmpty() }
                ?.map { it.optString("step").trim() }?.filter { it.isNotEmpty() }
                .orEmpty()
                .ifEmpty { plainSteps(obj.optString("instructions")) }

            return Recipe(
                id = id,
                title = title,
                image = obj.optString("image").ifBlank { null },
                servings = obj.optInt("servings").takeIf { it > 0 } ?: 1,
                readyInMinutes = obj.optInt("readyInMinutes").takeIf { it > 0 },
                kcalPerServing = kcal,
                sourceUrl = obj.optString("sourceUrl").ifBlank { null },
                spoonacularUrl = obj.optString("spoonacularSourceUrl").ifBlank { null },
                cuisines = obj.optJSONArray("cuisines")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                ingredients = ingredients,
                steps = steps,
            )
        }

        private fun parseIngredient(obj: JSONObject): Ingredient? {
            val name = obj.optString("name").trim().ifEmpty { obj.optString("originalName").trim() }
            if (name.isEmpty()) return null
            // Prefer metric measures when Spoonacular gives them.
            val metric = obj.optJSONObject("measures")?.optJSONObject("metric")
            val amount = (metric?.optDouble("amount") ?: obj.optDouble("amount")).takeIf { !it.isNaN() && it >= 0 } ?: 0.0
            val unit = (metric?.optString("unitShort") ?: obj.optString("unitShort").ifBlank { obj.optString("unit") }).trim()
            return Ingredient(
                id = obj.optLong("id"),
                name = name,
                aisle = obj.optString("aisle").trim().ifEmpty { "Other" },
                amount = amount,
                unit = unit,
            )
        }

        /** Older recipes only have instructions as an HTML blob. */
        private fun plainSteps(html: String): List<String> =
            html.replace(Regex("<li>|<br\\s*/?>|</p>", RegexOption.IGNORE_CASE), "\n")
                .replace(Regex("<[^>]+>"), "")
                .lines().map { it.trim() }.filter { it.isNotEmpty() && it != "null" }

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
