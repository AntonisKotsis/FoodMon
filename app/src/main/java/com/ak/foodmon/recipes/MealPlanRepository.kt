package com.ak.foodmon.recipes

import com.ak.foodmon.data.CachedRecipe
import com.ak.foodmon.data.MealPlan
import com.ak.foodmon.data.PlannedMeal
import com.ak.foodmon.data.PlannerDao
import com.ak.foodmon.data.PoolRecipe
import com.ak.foodmon.data.ShoppingItem
import org.json.JSONObject
import java.time.LocalDate

/**
 * Meal plans, swaps and shopping lists. Spoonacular recipe data is cached for one hour at most
 * (their terms); after that only ids and titles remain and details are refetched in bulk.
 */
class MealPlanRepository(
    private val dao: PlannerDao,
    private val client: SpoonacularClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun observePlan(profileId: Long, weekStart: LocalDate) = dao.observePlan(profileId, weekStart.toString())
    fun observeMeals(planId: Long) = dao.observeMeals(planId)
    fun observeShoppingList(planId: Long) = dao.observeShoppingList(planId)

    private fun freshAfter() = now() - CACHE_MS

    suspend fun purgeExpiredCache() = dao.purgeCache(freshAfter())

    /** Builds a new week, replacing any existing plan for that week. */
    suspend fun generate(profileId: Long, weekStart: LocalDate, caps: CalorieCaps, existingPlanId: Long?) {
        val disliked = dao.disliked(profileId).toSet()
        val fetched = searchCandidates(caps, disliked)
        val assignment = MealPlanner.assign(fetched.map { it.recipe.toCandidate() }, disliked, caps)
            ?: throw RecipeException(
                "Spoonacular didn't return enough Greek recipes under ${caps.lunch} kcal. Please try again later.",
            )
        existingPlanId?.let { dao.deletePlan(it) }
        dao.savePlan(
            MealPlan(profileId = profileId, weekStart = weekStart.toString(), createdAt = now()),
            assignment.map { a ->
                PlannedMeal(
                    planId = 0, block = a.block, slot = a.slot.name, recipeId = a.candidate.id,
                    title = a.candidate.title, kcalPerServing = a.candidate.kcalPerServing,
                )
            },
            fetched.map { it.recipe.toPool(0) },
        )
    }

    /** Recipes left in the swap pool for each slot. Swapping from the pool costs no points. */
    suspend fun freeSwaps(profileId: Long, planId: Long, caps: CalorieCaps): Map<MealSlot, Int> {
        val pool = dao.pool(planId)
        val inPlan = dao.meals(planId).map { it.recipeId }.toSet()
        val disliked = dao.disliked(profileId).toSet()
        return MealSlot.entries.associateWith { slot ->
            pool.count { p ->
                p.recipeId !in inPlan && p.recipeId !in disliked &&
                    p.kcalPerServing != null && p.kcalPerServing <= caps.of(slot)
            }
        }
    }

    /** Replaces [meal] with another recipe, searching Spoonacular again only if the pool is empty. */
    suspend fun swap(profileId: Long, meal: PlannedMeal, caps: CalorieCaps) {
        val slot = MealSlot.valueOf(meal.slot)
        val inPlan = dao.meals(meal.planId).map { it.recipeId }.toSet()
        val disliked = dao.disliked(profileId).toSet() + meal.recipeId
        val pool = dao.pool(meal.planId).map { Candidate(it.recipeId, it.title, it.kcalPerServing) }

        var extraPool = emptyList<PoolRecipe>()
        val pick = MealPlanner.pickReplacement(slot, pool, inPlan, disliked, caps)
            ?: run {
                extraPool = searchCandidates(caps, disliked + inPlan).map { it.recipe.toPool(meal.planId) }
                MealPlanner.pickReplacement(
                    slot, extraPool.map { Candidate(it.recipeId, it.title, it.kcalPerServing) }, inPlan, disliked, caps,
                )
            }
            ?: throw RecipeException("No other Greek recipes fit this ${slot.label.lowercase()} right now. Please try again later.")

        dao.swap(profileId, meal, PoolRecipe(meal.planId, pick.id, pick.title, pick.kcalPerServing), extraPool)
    }

    /** Points needed to load [ids]: zero if they're all still cached. */
    suspend fun loadCost(ids: List<Long>): Double {
        val cached = dao.cached(ids, freshAfter()).map { it.recipeId }.toSet()
        return SpoonacularClient.bulkCost(ids.count { it !in cached })
    }

    /** Full recipes, from the cache when fresh, otherwise in one bulk call. Keeps the order of [ids]. */
    suspend fun recipes(ids: List<Long>): List<Recipe> {
        val cached = dao.cached(ids, freshAfter())
            .mapNotNull { SpoonacularClient.parseRecipe(JSONObject(it.json)) }
            .associateBy { it.id }
        val missing = ids.distinct().filter { it !in cached }
        val fetched = if (missing.isEmpty()) {
            emptyMap()
        } else {
            val list = client.bulkInfo(missing)
            cache(list)
            list.associate { it.recipe.id to it.recipe }
        }
        return ids.mapNotNull { cached[it] ?: fetched[it] }
    }

    suspend fun recipe(id: Long): Recipe? = recipes(listOf(id)).firstOrNull()

    /** Builds the shopping list from the plan's recipes, unless one already exists. */
    suspend fun ensureShoppingList(planId: Long, force: Boolean = false) {
        if (!force && dao.shoppingListSize(planId) > 0) return
        val recipes = recipes(dao.meals(planId).map { it.recipeId })
        val items = MealPlanner.buildShoppingList(recipes).map {
            ShoppingItem(planId = planId, name = it.name, amount = it.amount, unit = it.unit, aisle = it.aisle)
        }
        dao.replaceShoppingList(planId, items)
    }

    suspend fun setChecked(item: ShoppingItem, checked: Boolean) = dao.setChecked(item.id, checked)

    suspend fun planWeek(planId: Long): LocalDate? = dao.plan(planId)?.let { LocalDate.parse(it.weekStart) }

    /** Greek first; if too few usable results come back, Mediterranean recipes fill the gap. */
    private suspend fun searchCandidates(caps: CalorieCaps, exclude: Set<Long>): List<FetchedRecipe> {
        val greek = client.search("greek", caps.lunch, SEARCH_SIZE)
        cache(greek)
        if (greek.count { it.recipe.id !in exclude } >= MIN_USABLE) return greek
        val mediterranean = client.search("mediterranean", caps.lunch, SEARCH_SIZE)
        cache(mediterranean)
        return (greek + mediterranean).distinctBy { it.recipe.id }
    }

    private suspend fun cache(list: List<FetchedRecipe>) {
        dao.purgeCache(freshAfter())
        dao.cache(list.map { CachedRecipe(it.recipe.id, it.json, now()) })
    }

    private fun Recipe.toCandidate() = Candidate(id, title, kcalPerServing)
    private fun Recipe.toPool(planId: Long) = PoolRecipe(planId, id, title, kcalPerServing)

    companion object {
        /** Spoonacular's terms allow keeping their data for one hour. */
        const val CACHE_MS = 60 * 60 * 1000L
        const val SEARCH_SIZE = 30
        /** Enough for the 6 meals plus a couple of swaps. */
        const val MIN_USABLE = 8
        /** Cost of one search, to show next to buttons that may need it. */
        val SEARCH_COST = SpoonacularClient.searchCost(SEARCH_SIZE)
    }
}
