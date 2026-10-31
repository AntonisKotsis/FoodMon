package com.ak.foodmon

import com.ak.foodmon.recipes.CalorieCaps
import com.ak.foodmon.recipes.Candidate
import com.ak.foodmon.recipes.Ingredient
import com.ak.foodmon.recipes.MealPlanner
import com.ak.foodmon.recipes.MealSlot
import com.ak.foodmon.recipes.Recipe
import com.ak.foodmon.recipes.ShoppingLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

class MealPlannerTest {
    private val caps = CalorieCaps(lunch = 600, dinner = 450)
    private val pool = (1L..10L).map { Candidate(it, "Recipe $it", 300.0 + it * 30) } // 330..600 kcal

    private fun recipe(id: Long, servings: Int, vararg ing: Ingredient) = Recipe(
        id, "R$id", null, servings, null, null, null, null, emptyList(), ing.toList(), emptyList(),
    )

    @Test
    fun assignsSixDifferentRecipesWithLightDinners() {
        val plan = MealPlanner.assign(pool, disliked = setOf(1L), caps = caps, random = Random(7))!!
        assertEquals(6, plan.size)
        assertEquals(6, plan.map { it.candidate.id }.toSet().size)
        assertTrue(plan.none { it.candidate.id == 1L })
        plan.filter { it.slot == MealSlot.DINNER }.forEach { assertTrue(it.candidate.kcalPerServing!! <= 450) }
        plan.filter { it.slot == MealSlot.LUNCH }.forEach { assertTrue(it.candidate.kcalPerServing!! <= 600) }
        assertEquals(listOf(0, 0, 1, 1, 2, 2), plan.map { it.block })
    }

    @Test
    fun assignFailsWhenTooFewCandidates() {
        assertNull(MealPlanner.assign(pool.take(5), emptySet(), caps))
    }

    @Test
    fun replacementSkipsPlannedDislikedAndTooHeavy() {
        val pick = MealPlanner.pickReplacement(
            MealSlot.DINNER, pool, inPlan = setOf(2L, 3L), disliked = setOf(1L), caps = caps, random = Random(1),
        )
        assertTrue(pick!!.id in setOf(4L, 5L)) // only 4 (420) and 5 (450) are left under the 450 dinner cap
        assertNull(MealPlanner.pickReplacement(MealSlot.DINNER, pool, setOf(2L, 3L, 4L, 5L), setOf(1L), caps))
    }

    @Test
    fun capsFollowDailyTarget() {
        assertEquals(CalorieCaps(560, 480), MealPlanner.caps(1600))
        assertEquals(CalorieCaps(600, 600), MealPlanner.caps(3000))
        assertEquals(CalorieCaps(350, 350), MealPlanner.caps(900))
        assertEquals(CalorieCaps(500, 450), MealPlanner.caps(null))
    }

    @Test
    fun scalesSixServingsDownToFour() {
        val r = recipe(1, 6, Ingredient(1, "rice", "Pasta and Rice", 300.0, "g"))
        assertEquals(200.0, MealPlanner.scale(r).single().amount, 1e-9)
    }

    @Test
    fun mergesSameIngredientAcrossRecipes() {
        val list = MealPlanner.buildShoppingList(
            listOf(
                recipe(1, 4, Ingredient(10, "feta", "Cheese", 100.0, "g"), Ingredient(20, "egg", "Milk, Eggs, Other Dairy", 1.2, "")),
                recipe(2, 2, Ingredient(10, "feta cheese", "Cheese", 68.75, "g"), Ingredient(30, "olive oil", "Oil;Vinegar", 1.0, "Tbsp")),
            ),
        )
        assertEquals(
            listOf(
                ShoppingLine("feta", 240.0, "g", "Cheese"), // 100 + 137.5 = 237.5 → 240
                ShoppingLine("egg", 2.0, "", "Milk, Eggs, Other Dairy"),
                ShoppingLine("olive oil", 2.0, "Tbsp", "Oil"),
            ),
            list,
        )
    }

    @Test
    fun formatsForWhatsApp() {
        val text = MealPlanner.formatForWhatsApp(
            listOf(ShoppingLine("feta", 240.0, "g", "Cheese"), ShoppingLine("egg", 2.0, "", "Cheese")),
            LocalDate.of(2026, 11, 2),
        )
        assertEquals(
            "🛒 *Shopping list – week of 2 Nov*\n3 batches · 6 recipes · 4 servings each\n\n*Cheese*\n• 240 g feta\n• 2 egg",
            text,
        )
    }

    @Test
    fun weekStartIsMondayOrNextMondayOnSunday() {
        assertEquals(LocalDate.of(2026, 11, 2), MealPlanner.weekStart(LocalDate.of(2026, 11, 4))) // Wednesday
        assertEquals(LocalDate.of(2026, 11, 2), MealPlanner.weekStart(LocalDate.of(2026, 11, 1))) // Sunday
        assertEquals(LocalDate.of(2026, 11, 2), MealPlanner.weekStart(LocalDate.of(2026, 11, 2))) // Monday
    }
}
