package com.ak.foodmon.recipes

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.random.Random

enum class MealSlot(val label: String) { LUNCH("Lunch"), DINNER("Dinner") }

/** A recipe that could go into the plan. */
data class Candidate(val id: Long, val title: String, val kcalPerServing: Double?)

data class Assignment(val block: Int, val slot: MealSlot, val candidate: Candidate)

/** Calorie ceiling per serving for each meal. */
data class CalorieCaps(val lunch: Int, val dinner: Int) {
    fun of(slot: MealSlot) = if (slot == MealSlot.LUNCH) lunch else dinner
}

data class ShoppingLine(val name: String, val amount: Double, val unit: String, val aisle: String)

/** Weekly plan rules: cook every 2 days for 2 people, one lunch and one dinner per batch. */
object MealPlanner {
    /** Mon–Tue, Wed–Thu, Fri–Sat. */
    val BLOCKS = listOf("Mon–Tue", "Wed–Thu", "Fri–Sat")
    const val PEOPLE = 2
    const val DAYS_PER_BATCH = 2
    const val SERVINGS = PEOPLE * DAYS_PER_BATCH
    val MEALS_PER_WEEK = BLOCKS.size * MealSlot.entries.size

    /** The Monday of the week to plan. On Sunday the current week is over, so plan the next one. */
    fun weekStart(today: LocalDate): LocalDate =
        if (today.dayOfWeek == DayOfWeek.SUNDAY) today.plusDays(1)
        else today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Lunch gets up to 35% of the daily target and dinner up to 30%, between 350 and 600 kcal. */
    fun caps(dailyTarget: Int?): CalorieCaps {
        if (dailyTarget == null || dailyTarget <= 0) return CalorieCaps(lunch = 500, dinner = 450)
        return CalorieCaps(
            lunch = (dailyTarget * 0.35).toInt().coerceIn(350, 600),
            dinner = (dailyTarget * 0.30).toInt().coerceIn(350, 600),
        )
    }

    private fun Candidate.fits(cap: Int) = kcalPerServing != null && kcalPerServing <= cap

    /**
     * Picks 6 different recipes for the week, with the lighter ones going to dinner.
     * Returns null when there aren't enough usable candidates.
     */
    fun assign(
        candidates: List<Candidate>,
        disliked: Set<Long>,
        caps: CalorieCaps,
        random: Random = Random.Default,
    ): List<Assignment>? {
        val usable = candidates.distinctBy { it.id }.filter { it.id !in disliked }.shuffled(random)
        if (usable.size < MEALS_PER_WEEK) return null
        val perSlot = BLOCKS.size

        val dinners = usable.filter { it.fits(caps.dinner) }.take(perSlot).toMutableList()
        if (dinners.size < perSlot) {
            dinners += (usable - dinners.toSet()).sortedBy { it.kcalPerServing ?: Double.MAX_VALUE }.take(perSlot - dinners.size)
        }
        val rest = usable - dinners.toSet()
        val lunches = (rest.filter { it.kcalPerServing == null || it.kcalPerServing <= caps.lunch } +
            rest.filter { it.kcalPerServing != null && it.kcalPerServing > caps.lunch }).take(perSlot)
        if (lunches.size < perSlot) return null

        return BLOCKS.indices.flatMap { block ->
            listOf(
                Assignment(block, MealSlot.LUNCH, lunches[block]),
                Assignment(block, MealSlot.DINNER, dinners[block]),
            )
        }
    }

    /** Another recipe for [slot] that isn't in the plan or disliked, or null if the pool has none. */
    fun pickReplacement(
        slot: MealSlot,
        pool: List<Candidate>,
        inPlan: Set<Long>,
        disliked: Set<Long>,
        caps: CalorieCaps,
        random: Random = Random.Default,
    ): Candidate? = pool
        .filter { it.id !in inPlan && it.id !in disliked && it.fits(caps.of(slot)) }
        .randomOrNull(random)

    /** Ingredients for [SERVINGS] servings, whatever the recipe was written for. */
    fun scale(recipe: Recipe, targetServings: Int = SERVINGS): List<Ingredient> {
        val factor = targetServings.toDouble() / recipe.servings.coerceAtLeast(1)
        return recipe.ingredients.map { it.copy(amount = it.amount * factor) }
    }

    /** Every ingredient for the week, scaled, merged across recipes, grouped by aisle. */
    fun buildShoppingList(recipes: List<Recipe>): List<ShoppingLine> =
        recipes.flatMap { scale(it) }
            .groupBy { ing ->
                val key = if (ing.id > 0) ing.id.toString() else ing.name.lowercase(Locale.ROOT)
                key to ing.unit.lowercase(Locale.ROOT)
            }
            .values.map { group ->
                val first = group.first()
                ShoppingLine(
                    name = first.name,
                    amount = roundAmount(group.sumOf { it.amount }, first.unit),
                    unit = first.unit,
                    // Spoonacular aisles can be "Produce;Spices and Seasonings"; the first is enough.
                    aisle = first.aisle.substringBefore(';').trim().ifEmpty { "Other" },
                )
            }
            .sortedWith(compareBy({ it.aisle.lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))

    private val gramLike = setOf("g", "ml", "gram", "grams", "milliliter", "milliliters")
    private val kiloLike = setOf("kg", "l", "kilogram", "kilograms", "liter", "liters")
    private val spoonLike = setOf("tsp", "tsps", "teaspoon", "teaspoons", "tbsp", "tbsps", "tablespoon", "tablespoons", "cup", "cups")

    /** Rounds to amounts you can actually buy or measure: 237.5 g → 240 g, 1.2 eggs → 2 eggs. */
    fun roundAmount(amount: Double, unit: String): Double {
        if (amount <= 0) return 0.0
        return when (unit.lowercase(Locale.ROOT)) {
            in gramLike -> when {
                amount >= 100 -> (amount / 10).roundToLong() * 10.0
                amount >= 10 -> (amount / 5).roundToLong() * 5.0
                else -> ceil(amount)
            }
            in kiloLike -> ceil(amount * 10) / 10
            in spoonLike -> ceil(amount * 4) / 4
            else -> ceil(amount - 1e-9)
        }
    }

    fun formatAmount(amount: Double): String =
        if (amount % 1.0 == 0.0) amount.toLong().toString()
        else String.format(Locale.US, "%.2f", amount).trimEnd('0').trimEnd('.')

    fun lineText(line: ShoppingLine): String = buildString {
        if (line.amount > 0) {
            append(formatAmount(line.amount)).append(' ')
            if (line.unit.isNotBlank()) append(line.unit).append(' ')
        }
        append(line.name)
    }

    /** Plain text for WhatsApp: *bold* aisle headers and one bullet per item. */
    fun formatForWhatsApp(lines: List<ShoppingLine>, weekStart: LocalDate): String = buildString {
        val week = weekStart.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
        append("🛒 *Shopping list – week of ").append(week).append("*\n")
        append("${BLOCKS.size} batches · ${MEALS_PER_WEEK} recipes · $SERVINGS servings each\n")
        lines.groupBy { it.aisle }.forEach { (aisle, items) ->
            append("\n*").append(aisle).append("*\n")
            items.forEach { append("• ").append(lineText(it)).append('\n') }
        }
    }.trimEnd()
}
